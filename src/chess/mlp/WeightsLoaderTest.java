package chess.mlp;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import chess.core.Position;
import chess.core.Piece;

/**
 * Minimal sanity checks for the MLP loader and evaluator.
 * Generates a zero-initialized weight file, loads it, and verifies that
 * the outputs are zero for a sample position. Also exercises the byte[] loader.
 */
public final class WeightsLoaderTest {

	/**
	 * Entry point. Runs the zero-weight evaluation test and prints a success marker.
	 *
	 * @param args ignored
	 * @throws Exception if any assertion fails or I/O fails
	 */
	public static void main(String[] args) throws Exception {
		if (args.length == 0) {
			testZeroWeightsProduceZeroOutput();
			System.out.println("WeightsLoaderTest OK (zero-weights sanity)");
		} else {
			runPrettyPrint(args);
		}
	}

	private static void testZeroWeightsProduceZeroOutput() throws Exception {
		Path tmp = Files.createTempFile("mlp_zero", ".bin");
		try {
			writeZeroWeights(tmp);
			Evaluator evalFromFile = WeightsLoader.load(tmp);
			byte[] bytes = Files.readAllBytes(tmp);
			Evaluator evalFromBytes = WeightsLoader.load(bytes);

			Position start = new Position("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");

			checkZeroOutputs(evalFromFile.evaluate(start));
			checkZeroOutputs(evalFromBytes.evaluate(start));
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	private static void runPrettyPrint(String[] args) throws Exception {
		Path weights = Path.of(args[0]);
		String fen = args.length > 1
				? args[1]
				: "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
		Position position = new Position(fen);
		Evaluator evaluator = WeightsLoader.load(weights);
		Evaluator.Result result = evaluator.evaluate(position);
		System.out.printf("Scalar evaluation: %+8.4f%n", result.scalarEval);
		System.out.println(renderGrid(result.grid64, position));
	}

	private static void checkZeroOutputs(Evaluator.Result result) {
		if (Math.abs(result.scalarEval) > 1e-6f) {
			throw new AssertionError("Expected scalar 0, got " + result.scalarEval);
		}
		for (int i = 0; i < result.grid64.length; i++) {
			if (Math.abs(result.grid64[i]) > 1e-6f) {
				throw new AssertionError("Expected grid[" + i + "] = 0, got " + result.grid64[i]);
			}
		}
	}

	private static void writeZeroWeights(Path path) throws IOException {
		try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
			writeZeros(out, 1024, 781); // w1
			writeZeros(out, 1024, 1);   // b1
			writeZeros(out, 768, 1024); // w2
			writeZeros(out, 768, 1);    // b2
			writeZeros(out, 512, 768);  // w3
			writeZeros(out, 512, 1);    // b3
			writeZeros(out, 1, 512);    // wScalar
			writeZeros(out, 1, 1);      // bScalar
			writeZeros(out, 64, 512);   // wGrid
			writeZeros(out, 64, 1);     // bGrid
		}
	}

	private static String renderGrid(float[] grid, Position position) {
		if (grid.length != 64) {
			throw new IllegalArgumentException("Expected grid length 64, got " + grid.length);
		}
		StringBuilder sb = new StringBuilder();
		final int cellWidth = 7;
		String sep = ("+" + "-".repeat(cellWidth)).repeat(8) + "+\n";
		byte[] board = position.getBoard();
		for (int row = 0; row < 8; row++) {
			sb.append(sep);
			// Pieces line
			sb.append("|");
			for (int col = 0; col < 8; col++) {
				int idx = row * 8 + col;
				char p = pieceChar(board[idx]);
				String content = p == ' ' ? "" : String.valueOf(p);
				sb.append(center(content, cellWidth)).append("|");
			}
			sb.append("\n");
			// Values line
			sb.append("|");
			for (int col = 0; col < 8; col++) {
				int idx = row * 8 + col;
				char p = pieceChar(board[idx]);
				String content = (p == ' ')
						? ""
						: String.format("%+.2f", grid[idx]);
				sb.append(center(content, cellWidth)).append("|");
			}
			sb.append("\n");
		}
		sb.append(sep);
		return sb.toString();
	}

	private static char pieceChar(byte piece) {
		return switch (piece) {
		case Piece.WHITE_PAWN -> 'P';
		case Piece.WHITE_KNIGHT -> 'N';
		case Piece.WHITE_BISHOP -> 'B';
		case Piece.WHITE_ROOK -> 'R';
		case Piece.WHITE_QUEEN -> 'Q';
		case Piece.WHITE_KING -> 'K';
		case Piece.BLACK_PAWN -> 'p';
		case Piece.BLACK_KNIGHT -> 'n';
		case Piece.BLACK_BISHOP -> 'b';
		case Piece.BLACK_ROOK -> 'r';
		case Piece.BLACK_QUEEN -> 'q';
		case Piece.BLACK_KING -> 'k';
		default -> ' ';
		};
	}

	private static void writeZeros(DataOutputStream out, int rows, int cols) throws IOException {
		writeIntLE(out, rows);
		writeIntLE(out, cols);
		int total = rows * cols;
		for (int i = 0; i < total; i++) {
			writeFloatLE(out, 0.0f);
		}
	}

	private static void writeIntLE(DataOutputStream out, int value) throws IOException {
		out.writeByte(value & 0xFF);
		out.writeByte((value >>> 8) & 0xFF);
		out.writeByte((value >>> 16) & 0xFF);
		out.writeByte((value >>> 24) & 0xFF);
	}

	private static void writeFloatLE(DataOutputStream out, float value) throws IOException {
		writeIntLE(out, Float.floatToRawIntBits(value));
	}

	private static String center(String text, int width) {
		String s = text == null ? "" : text;
		if (s.length() >= width) {
			return s;
		}
		int left = (width - s.length()) / 2;
		int right = width - s.length() - left;
		return " ".repeat(left) + s + " ".repeat(right);
	}
}
