package chess.io;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;

import chess.core.Field;
import chess.core.Piece;
import chess.core.Position;
import utility.Json;

// TODO(codex): stack-to-npy export still spikes RAM / GC even though it’s “streaming”.
// Likely causes in current code:
// 1) Json.streamTopLevelObjects() accumulates each full JSON object into a StringBuilder (starts ~1MiB),
//    then emits consumer.accept(st.obj.toString()). If any object is large (e.g., huge "analysis"),
//    the builder grows and never shrinks, so its backing char[] can stay huge for the rest of the run.
//    Fix: make StreamScan.obj non-final and, after emitting an object, if obj.capacity() > MAX_CAP
//    (e.g., 4–16 MiB), replace it with new StringBuilder(INIT_CAP) to release the big char[].
//    (See StreamScan + accept(toString()) path.)
//
// 2) Per-record allocations are heavy:
//    - encode() allocates new float[781] for every row. 
//    - writeFloats() allocates a new ByteBuffer every row; writeFloat() allocates too. 
//    Fix: reuse buffers:
//      * keep one float[] feats reused (Arrays.fill(feats, 0f) per row) OR write directly into a reusable byte[]/ByteBuffer.
//      * keep one byte[] rowBytes = new byte[INPUTS*4] and pack floats little-endian via Float.floatToIntBits(...) to avoid ByteBuffer.allocate.
//      * keep one 4-byte label buffer reused.
//
// 3) Optional further reduction: avoid building String[] analysis at all; scan the raw JSON slice for the single
//    "multipv 1 score ..." line you need, to avoid allocating many Strings per object.
//
// 4) Robustness: count can overflow int for very large datasets; use long for row count and validate before writing NPY header.

/**
 * Stream-convert Stack-*.json puzzle dumps (JSON array) into NPY tensors.
 *
 * For each input file, writes:
 *  - &lt;stem&gt;.features.npy shaped (N, 781) float32
 *  - &lt;stem&gt;.labels.npy shaped (N,) float32 (pawns, White POV)
 *
 * Avoids loading the whole JSON array into RAM; processes one object at a time.
 */
public final class StackDatasetExporter {

	private static final int INPUTS = 64 * 12 + 4 + 8 + 1; // 781
	private static final Pattern SCORE_RE = Pattern.compile("score\\s+(cp|mate)\\s+(-?\\d+)");
	private static final Pattern DEPTH_RE = Pattern.compile("depth\\s+(\\d+)");

	private StackDatasetExporter() {
	}

	public static void export(Path jsonFile, Path outStem) throws IOException {
		if (jsonFile == null || outStem == null) {
			throw new IllegalArgumentException("jsonFile and outStem must be non-null");
		}

		final Path featTmp = Files.createTempFile("stack-dataset-feat", ".bin");
		final Path labTmp = Files.createTempFile("stack-dataset-labels", ".bin");
		AtomicInteger count = new AtomicInteger();

		try (OutputStream featOut = new BufferedOutputStream(Files.newOutputStream(featTmp));
				OutputStream labOut = new BufferedOutputStream(Files.newOutputStream(labTmp))) {

			Consumer<String> sink = obj -> {
				String posFen = Json.parseStringField(obj, "position");
				if (posFen == null) {
					return;
				}
				String[] analysis = Json.parseStringArrayField(obj, "analysis");
				float eval = selectBest(analysis);
				if (Float.isNaN(eval)) {
					return;
				}
				Position pos;
				try {
					pos = new Position(posFen);
				} catch (IllegalArgumentException e) {
					return;
				}
				try {
					writeFloats(featOut, encode(pos));
					writeFloat(labOut, eval);
					count.incrementAndGet();
				} catch (IOException io) {
					throw new RuntimeException(io);
				}
			};

			Json.streamTopLevelObjects(jsonFile, sink);
		} catch (RuntimeException re) {
			Files.deleteIfExists(featTmp);
			Files.deleteIfExists(labTmp);
			if (re.getCause() instanceof IOException io) {
				throw io;
			}
			throw re;
		}

		int n = count.get();
		Path featPath = outStem.resolveSibling(outStem.getFileName().toString() + ".features.npy");
		Path labPath = outStem.resolveSibling(outStem.getFileName().toString() + ".labels.npy");

		try {
			writeNpyFromRaw(featTmp, featPath, n, INPUTS);
			writeNpyFromRaw(labTmp, labPath, n, -1);
		} finally {
			Files.deleteIfExists(featTmp);
			Files.deleteIfExists(labTmp);
		}
	}

	private static float selectBest(String[] analysis) {
		if (analysis == null || analysis.length == 0) {
			return Float.NaN;
		}
		int bestDepth = -1;
		float best = Float.NaN;
		for (String line : analysis) {
			if (line == null || !line.contains("multipv 1")) {
				continue;
			}
			Matcher s = SCORE_RE.matcher(line);
			if (!s.find()) {
				continue;
			}
			String kind = s.group(1);
			int val = Integer.parseInt(s.group(2));
			int depth = 0;
			Matcher d = DEPTH_RE.matcher(line);
			if (d.find()) {
				depth = Integer.parseInt(d.group(1));
			}
			if (depth < bestDepth) {
				continue;
			}
			float pawns;
			if ("cp".equals(kind)) {
				pawns = clamp(val / 100.0f, -20.0f, 20.0f);
			} else {
				int sign = val >= 0 ? 1 : -1;
				pawns = 20.0f * sign;
			}
			bestDepth = depth;
			best = pawns;
		}
		return best;
	}

	private static float[] encode(Position position) {
		float[] feats = new float[INPUTS];
		byte[] board = position.getBoard(); // Field order (A8 index 0)
		for (int sq = 0; sq < 64; sq++) {
			byte piece = board[sq];
			int ch = channel(piece);
			if (ch >= 0) {
				feats[sq * 12 + ch] = 1.0f;
			}
		}
		int idx = 64 * 12;
		feats[idx++] = position.getWhiteKingside() != Field.NO_SQUARE ? 1.0f : 0.0f;
		feats[idx++] = position.getWhiteQueenside() != Field.NO_SQUARE ? 1.0f : 0.0f;
		feats[idx++] = position.getBlackKingside() != Field.NO_SQUARE ? 1.0f : 0.0f;
		feats[idx++] = position.getBlackQueenside() != Field.NO_SQUARE ? 1.0f : 0.0f;
		byte ep = position.getEnPassant();
		if (ep != Field.NO_SQUARE) {
			int file = Field.getX(ep);
			feats[idx + file] = 1.0f;
		}
		idx += 8;
		feats[idx] = position.isWhiteTurn() ? 1.0f : -1.0f;
		return feats;
	}

	private static int channel(byte piece) {
		return switch (piece) {
		case Piece.WHITE_PAWN -> 0;
		case Piece.WHITE_KNIGHT -> 1;
		case Piece.WHITE_BISHOP -> 2;
		case Piece.WHITE_ROOK -> 3;
		case Piece.WHITE_QUEEN -> 4;
		case Piece.WHITE_KING -> 5;
		case Piece.BLACK_PAWN -> 6;
		case Piece.BLACK_KNIGHT -> 7;
		case Piece.BLACK_BISHOP -> 8;
		case Piece.BLACK_ROOK -> 9;
		case Piece.BLACK_QUEEN -> 10;
		case Piece.BLACK_KING -> 11;
		default -> -1;
		};
	}

	private static float clamp(float v, float lo, float hi) {
		if (v < lo) {
			return lo;
		}
		if (v > hi) {
			return hi;
		}
		return v;
	}

	/**
	 * Write an array of float values to the output stream in little-endian order.
	 *
	 * @param out    destination stream
	 * @param values values to serialize
	 * @throws IOException if writing fails
	 */
	private static void writeFloats(OutputStream out, float[] values) throws IOException {
		ByteBuffer buf = ByteBuffer.allocate(values.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
		for (float v : values) {
			buf.putFloat(v);
		}
		out.write(buf.array());
	}

	/**
	 * Write a single float value to the output stream in little-endian order.
	 *
	 * @param out   destination stream
	 * @param value value to serialize
	 * @throws IOException if writing fails
	 */
	private static void writeFloat(OutputStream out, float value) throws IOException {
		ByteBuffer buf = ByteBuffer.allocate(Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
		buf.putFloat(value);
		out.write(buf.array());
	}

	/**
	 * Write a float32 numpy array using the raw float payload stored in {@code raw}.
	 * If {@code cols < 0}, shape is (rows,); otherwise (rows, cols).
	 *
	 * @param raw   temporary file containing little-endian float payload
	 * @param path  destination .npy path
	 * @param rows  number of rows
	 * @param cols  number of columns or -1 for 1-D
	 * @throws IOException if writing fails
	 */
	private static void writeNpyFromRaw(Path raw, Path path, int rows, int cols) throws IOException {
		String shape = cols < 0 ? "(" + rows + ",)" : "(" + rows + ", " + cols + ",)";
		String header = "{'descr': '<f4', 'fortran_order': False, 'shape': " + shape + ", }";
		int headerLen = header.length() + 1; // + newline
		int pad = (16 - ((10 + headerLen) % 16)) % 16;
		header = header + " ".repeat(pad) + "\n";
		headerLen = header.length();

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		baos.write(new byte[] { (byte) 0x93, 'N', 'U', 'M', 'P', 'Y' });
		baos.write(new byte[] { 1, 0 }); // version 1.0
		ByteBuffer lenBuf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
		lenBuf.putShort((short) headerLen);
		baos.write(lenBuf.array());
		baos.write(header.getBytes(java.nio.charset.StandardCharsets.US_ASCII));

		try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(path))) {
			out.write(baos.toByteArray());
			Files.copy(raw, out);
		}
	}
}
