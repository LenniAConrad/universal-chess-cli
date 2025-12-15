package chess.io;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;

import chess.core.Field;
import chess.core.Piece;
import chess.core.Position;
import chess.model.Record;
import chess.uci.Analysis;
import chess.uci.Evaluation;
import chess.uci.Output;
import utility.Json;

/**
 * Exporter that converts {@code .record} JSON arrays into Numpy-compatible
 * {@code .npy} tensors (float32) for training.
 *
 * Writes two files next to the requested output stem:
 *  - {@code <stem>.features.npy} shaped (N, 781)
 *  - {@code <stem>.labels.npy} shaped (N,)
 *
 * Streams directly to .npy (constant memory) by writing a placeholder header
 * and patching N in-place at the end.
 *
 * @author Lennart A. Conrad
 */
public final class RecordDatasetExporter {

	private static final int INPUTS = 64 * 12 + 4 + 8 + 1; // 781

	private RecordDatasetExporter() {
	}

	public static void export(Path recordFile, Path outStem) throws IOException {
		if (recordFile == null || outStem == null) {
			throw new IllegalArgumentException("recordFile and outStem must be non-null");
		}

		Path featPath = outStem.resolveSibling(outStem.getFileName().toString() + ".features.npy");
		Path labPath = outStem.resolveSibling(outStem.getFileName().toString() + ".labels.npy");

		// If we crash mid-way, delete partial outputs.
		boolean success = false;

		try (NpyFloat32Writer feat = NpyFloat32Writer.open2D(featPath, INPUTS);
				NpyFloat32Writer lab = NpyFloat32Writer.open1D(labPath)) {

			// Reuse feature buffer to avoid allocating millions of float[] arrays.
			final float[] featsBuf = new float[INPUTS];

			Consumer<String> sink = json -> {
				Record rec = Record.fromJson(json);
				if (rec == null) return;

				Position pos = rec.getPosition();
				if (pos == null) return;

				Analysis a = rec.getAnalysis();
				if (a == null) return;

				Output best = a.getBestOutput();
				if (best == null) return;

				Evaluation ev = best.getEvaluation();
				if (ev == null || !ev.isValid()) return;

				float pawns;
				if (ev.isMate()) {
					int sign = ev.getValue() >= 0 ? 1 : -1;
					pawns = 20.0f * sign;
				} else {
					pawns = clamp(ev.getValue() / 100.0f, -20.0f, 20.0f);
				}

				try {
					encodeInto(pos, featsBuf);
					feat.writeRow(featsBuf);     // streams straight into .npy payload
					lab.writeScalar(pawns);      // streams straight into .npy payload
				} catch (IOException io) {
					throw new RuntimeException(io);
				}
			};

			Json.streamTopLevelObjects(recordFile, sink);

			success = true;
		} catch (RuntimeException re) {
			if (re.getCause() instanceof IOException io) {
				throw io;
			}
			throw re;
		} finally {
			if (!success) {
				Files.deleteIfExists(featPath);
				Files.deleteIfExists(labPath);
			}
		}
	}

	private static void encodeInto(Position position, float[] feats) {
		Arrays.fill(feats, 0.0f);

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
		return v < lo ? lo : (v > hi ? hi : v);
	}

	/**
	 * Streaming .npy float32 writer that writes a placeholder header and patches
	 * the row count in-place on close. Constant memory.
	 */
	private static final class NpyFloat32Writer implements Closeable {
		// Keep this fairly large so you never exceed it even for huge datasets.
		private static final int ROWS_FIELD_WIDTH = 20;

		private final RandomAccessFile raf;
		private final FileChannel ch;
		private final boolean oneD;
		private final int cols;

		private final long rowsFieldOffsetInFile; // where the rows digits/spaces begin
		private final int rowsFieldWidth;

		private final ByteBuffer rowBuf;
		private final ByteBuffer scalarBuf;

		private long rows = 0;
		private boolean closed = false;

		static NpyFloat32Writer open2D(Path path, int cols) throws IOException {
			return new NpyFloat32Writer(path, false, cols);
		}

		static NpyFloat32Writer open1D(Path path) throws IOException {
			return new NpyFloat32Writer(path, true, -1);
		}

		private NpyFloat32Writer(Path path, boolean oneD, int cols) throws IOException {
			this.oneD = oneD;
			this.cols = cols;

			Files.createDirectories(path.toAbsolutePath().getParent());

			this.raf = new RandomAccessFile(path.toFile(), "rw");
			this.raf.setLength(0);
			this.ch = raf.getChannel();

			// Build header with fixed-width rows field using spaces (NOT leading zeros).
			String rowsPlaceholder = String.format(Locale.ROOT, "%" + ROWS_FIELD_WIDTH + "d", 0);

			String shape = oneD
					? "(" + rowsPlaceholder + ",)"
					: "(" + rowsPlaceholder + ", " + cols + ",)";

			String header = "{'descr': '<f4', 'fortran_order': False, 'shape': " + shape + ", }";

			// NPY v1.0 header: magic(6) + version(2) + headerlen(2) + header bytes, padded to 16-byte alignment.
			int preamble = 10;
			int headerLenNoPad = header.length() + 1; // + '\n'
			int pad = (16 - ((preamble + headerLenNoPad) % 16)) % 16;
			String headerPadded = header + " ".repeat(pad) + "\n";
			byte[] headerBytes = headerPadded.getBytes(StandardCharsets.US_ASCII);

			// Locate where the placeholder lives within the header bytes so we can patch it later.
			int idx = headerPadded.indexOf(rowsPlaceholder);
			if (idx < 0) {
				throw new IOException("Internal error: rows placeholder not found in header");
			}
			this.rowsFieldOffsetInFile = preamble + idx;
			this.rowsFieldWidth = rowsPlaceholder.length();

			// Write magic + version
			raf.write(new byte[] { (byte) 0x93, 'N', 'U', 'M', 'P', 'Y' });
			raf.write(new byte[] { 1, 0 }); // v1.0

			// header length (uint16 little-endian)
			int hlen = headerBytes.length;
			if (hlen > 0xFFFF) {
				throw new IOException("NPY header too large for v1.0: " + hlen);
			}
			raf.write((byte) (hlen & 0xFF));
			raf.write((byte) ((hlen >>> 8) & 0xFF));

			// header bytes
			raf.write(headerBytes);

			// Payload buffers (reused; no per-row allocations)
			this.scalarBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
			this.rowBuf = oneD
					? ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
					: ByteBuffer.allocate(cols * 4).order(ByteOrder.LITTLE_ENDIAN);
		}

		void writeRow(float[] row) throws IOException {
			if (oneD) {
				throw new IllegalStateException("This writer is 1D; use writeScalar()");
			}
			if (row.length != cols) {
				throw new IllegalArgumentException("Expected row length " + cols + " but got " + row.length);
			}

			rowBuf.clear();
			for (int i = 0; i < cols; i++) {
				rowBuf.putFloat(row[i]);
			}
			rowBuf.flip();
			while (rowBuf.hasRemaining()) {
				ch.write(rowBuf);
			}
			rows++;
		}

		void writeScalar(float v) throws IOException {
			scalarBuf.clear();
			scalarBuf.putFloat(v);
			scalarBuf.flip();
			while (scalarBuf.hasRemaining()) {
				ch.write(scalarBuf);
			}
			rows++;
		}

		@Override
		public void close() throws IOException {
			if (closed) return;
			closed = true;

			// Patch the rows field in-place using spaces (valid Python integer literal formatting).
			String rowsStr = String.format(Locale.ROOT, "%" + rowsFieldWidth + "d", rows);
			byte[] rowsBytes = rowsStr.getBytes(StandardCharsets.US_ASCII);

			raf.seek(rowsFieldOffsetInFile);
			raf.write(rowsBytes);

			ch.force(false);
			ch.close();
			raf.close();
		}
	}
}
