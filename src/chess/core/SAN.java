package chess.core;

/**
 * Used for conversion between moves and Standard Algebraic Notation (SAN).
 * <p>
 * This class supports:
 * <ul>
 * <li>Generating SAN from a given {@link Position} and {@link Move}, including
 * disambiguation, captures, promotions, and check/checkmate indicators.</li>
 * <li>Parsing a SAN string back into a legal {@link Move} in a given
 * position.</li>
 * </ul>
 * </p>
 * 
 * @author Lennart A. Conrad
 * @since 2025
 */
public class SAN {

	/**
	 * Used for notation of kingside castling in SAN.
	 */
	private static final String CASTLING_KINGSIDE = "O-O";

	/**
	 * Used for notation of queenside castling in SAN.
	 */
	private static final String CASTLING_QUEENSIDE = "O-O-O";

	/**
	 * Used for the result token representing a White win.
	 */
	public static final String RESULT_WHITE_WIN = "1-0";

	/**
	 * Used for the result token representing a Black win.
	 */
	public static final String RESULT_BLACK_WIN = "0-1";

	/**
	 * Used for the result token representing a draw.
	 */
	public static final String RESULT_DRAW = "1/2-1/2";

	/**
	 * Used for the result token representing an undefined or ongoing game.
	 */
	public static final String RESULT_UNKNOWN = "*";

	/**
	 * Private constructor to prevent instantiation of this class.
	 */
	private SAN() {
		// Prevent instantiation
	}

	/**
	 * Converts a move into Standard Algebraic Notation (SAN) within the supplied context.
	 *
	 * <p>
	 * Handles castling, pawn promotions, captures with file disambiguation, and
	 * check/checkmate suffixes determined by {@link #algebraicEnding(Position, short)}.
	 * </p>
	 *
	 * @param context the current position before the move
	 * @param move move to describe
	 * @return SAN string describing the move
	 */
	public static String toAlgebraic(Position context, short move) {
		String ending = algebraicEnding(context, move);

		if (Move.isKingsideCastle(context, move)) {
			return CASTLING_KINGSIDE + ending;
		}

		if (Move.isQueensideCastle(context, move)) {
			return CASTLING_QUEENSIDE + ending;
		}

		byte moveto = Move.getToIndex(move);
		byte movefrom = Move.getFromIndex(move);
		byte promotion = Move.getPromotion(move);

		boolean iscapture = context.isCapture(movefrom, moveto);
		byte piece = context.board[movefrom];
		String disambiguation = buildDisambiguation(context, move, piece);
		String capture = iscapture ? "x" : "";

		if (iscapture && Piece.isPawn(piece)) {
			capture = Field.getFile(movefrom) + capture;
		}

		String destination = Field.toString(moveto);
		String promotions = buildPromotionSuffix(promotion);

		return new StringBuilder(7)
				.append(getPieceSymbol(piece))
				.append(disambiguation)
				.append(capture)
				.append(destination)
				.append(promotions)
				.append(ending)
				.toString();
	}

	/**
	 * Generates the check/mate suffix for a SAN string.
	 *
	 * <p>
	 * A move results in {@code "+"} when the resulting position leaves the opponent
	 * in check, and {@code "#"} when no legal moves remain (checkmate).
	 * </p>
	 *
	 * @param context the position before the move
	 * @param move move being played
	 * @return {@code "+"} for check, {@code "#"} for mate, or {@code ""} otherwise
	 */
	private static String algebraicEnding(Position context, short move) {
		Position next = context.copyOf().play(move);
		if (!next.inCheck()) {
			return "";
		}

		return next.getMoves().size == 0
				? "#"
				: "+";
	}

	/**
	 * Maps a piece code to the algebraic symbol used in SAN.
	 *
	 * <p>
	 * Returns uppercase letters for minor/major pieces and an empty string for
	 * pawns, whose origin file is printed explicitly when capturing.
	 * </p>
	 *
	 * @param piece the piece code
	 * @return single-character notation or {@code ""} for pawns
	 */
	private static String getPieceSymbol(byte piece) {
		switch (piece) {
			case Piece.BLACK_BISHOP, Piece.WHITE_BISHOP:
				return "B";
			case Piece.BLACK_KING, Piece.WHITE_KING:
				return "K";
			case Piece.BLACK_KNIGHT, Piece.WHITE_KNIGHT:
				return "N";
			case Piece.BLACK_QUEEN, Piece.WHITE_QUEEN:
				return "Q";
			case Piece.BLACK_ROOK, Piece.WHITE_ROOK:
				return "R";
			case Piece.BLACK_PAWN, Piece.WHITE_PAWN:
				return "";
			default:
				return "?";
		}
	}

	/**
	 * Builds the disambiguation string when multiple pieces of the same type can reach the destination.
	 *
	 * <p>
	 * Checks every legal move in the position and returns either the moving piece's file,
	 * rank, or both (ordered) just enough to make the SAN string unambiguous.
	 * </p>
	 *
	 * @param context the current position
	 * @param move    the move that requires disambiguation
	 * @param piece   the moving piece code
	 * @return file and/or rank string or {@code ""} when uniquely identified
	 */
	private static String buildDisambiguation(Position context, short move, byte piece) {
		MoveList moves = context.getMoves();
		byte movefrom = Move.getFromIndex(move);
		byte moveto = Move.getToIndex(move);
		char file = Field.getFile(movefrom);
		char rank = Field.getRank(movefrom);
		String fileString = "";
		String rankString = "";

		if (!Piece.isPawn(piece)) {
			for (int i = 0; i < moves.size; i++) {
				short m = moves.moves[i];
				byte mfrom = Move.getFromIndex(m);
				byte mto = Move.getToIndex(m);
				if (movefrom != mfrom && moveto == mto && piece == context.board[mfrom]) {
					if (file != Field.getFile(mfrom)) {
						fileString = String.valueOf(file);
					} else if (rank != Field.getRank(mfrom)) {
						rankString = String.valueOf(rank);
					}
				}
			}
		}

		return fileString + rankString;
	}

	/**
	 * Builds the promotion suffix added to a SAN move.
	 *
	 * @param promotion promotion piece code from the move
	 * @return string like {@code =Q} when promotion occurs, otherwise {@code ""}
	 */
	private static String buildPromotionSuffix(byte promotion) {
		switch (promotion) {
			case Move.PROMOTION_BISHOP:
				return "=B";
			case Move.PROMOTION_KNIGHT:
				return "=N";
			case Move.PROMOTION_QUEEN:
				return "=Q";
			case Move.PROMOTION_ROOK:
				return "=R";
			default:
				return "";
		}
	}

	/**
	 * Parses a SAN string back into the corresponding legal move in the
	 * supplied position.
	 *
	 * <p>
	 * Accepts strings with trailing annotations such as {@code !} or {@code ?}
	 * and matches them against the generated SAN list to ensure legality.
	 * </p>
	 *
	 * @param context   the current position
	 * @param algebraic the SAN string of the move (may include annotations like {@code !} or {@code ?})
	 * @return the matching {@link Move}
	 * @throws IllegalArgumentException if no matching legal move is found
	 */
	public static short fromAlgebraic(Position context, String algebraic) throws IllegalArgumentException {
		String san = algebraic.replaceAll("[!?]+", "");

		MoveList moveList = context.getMoves();
		for (int i = 0; i < moveList.size; i++) {
			short move = moveList.moves[i];
			if (toAlgebraic(context, move).equals(san)) {
				return move;
			}
		}

		throw new IllegalArgumentException("Invalid SAN '" + algebraic + "' in position '" + context.toString() + "'");
	}

	/**
	 * Cleans raw PGN move text by removing comments, variations, NAGs,
	 * move numbers, and result tokens, leaving only SAN move tokens separated
	 * by single spaces.
	 *
	 * <p>
	 * Example:
	 * </p>
	 * <pre>
	 * String before = "1. e4 e5 (2. Nc3) 2... Nc6 {[%clk 2:34:56]} 3. Bb5 a6 $5 4. Ba4 Nf6 1-0";
	 * String after = cleanMoveString(before); // "e4 e5 Nc6 Bb5 a6 Ba4 Nf6"
	 * </pre>
	 *
	 * @param movetext the raw PGN move string (including comments, variations, clocks, etc.)
	 * @return a cleaned string containing only SAN moves separated by spaces; empty when none found
	 */
	public static String cleanMoveString(String movetext) {
		if (movetext == null || movetext.isEmpty()) {
			return "";
		}
		return movetext.replaceAll("\\{[^}]*\\}", " ").replaceAll("\\([^)]*\\)", " ").replaceAll("\\$\\d+", " ")
				.replaceAll("\\d+\\.(?:\\.\\.)?", " ").replaceAll("\\b1-0\\b|\\b0-1\\b|1/2-1/2|\\*", " ").trim()
				.replaceAll("\\s+", " ");
	}

	/**
	 * Cleans PGN move text while preserving variation parentheses.
	 *
	 * <p>
	 * Block/line comments, NAGs, move numbers, and result tokens are removed, but
	 * variation groups remain (with spacing normalized) so downstream parsers can
	 * still detect sideline content.
	 * </p>
	 *
	 * <pre>
	 * String before = "1. e4 e5 (2. Nc3) 2... Nc6 {[%clk 2:34:56]} 3. Bb5 a6";
	 * String after = cleanMoveStringKeepVariationsRegex(before);
	 * // result: "e4 e5 (Nc3) Nc6 Bb5 a6"
	 * </pre>
	 *
	 * @param movetext the raw PGN move string (including comments, variations, clocks, etc.)
	 * @return a cleaned string with SAN moves and variations preserved, or empty if input is null/empty
	 */
	public static String cleanMoveStringKeepVariationsRegex(String movetext) {
		if (movetext == null || movetext.isEmpty()) {
			return "";
		}
		// Remove: {…} comments, ; line comments, $N NAGs, move numbers, result tokens.
		movetext = movetext
				.replaceAll("\\{[^}]*\\}", " ") // block comments
				.replaceAll("(?m);[^\\r\\n]*", " ") // line comments
				.replaceAll("\\$\\d+", " ") // NAGs
				.replaceAll("\\d+\\.(?:\\.\\.)?", " ") // 12. or 12...
				.replaceAll("(?<!\\S)(?:1-0|0-1|1/2-1/2|\\*)(?!\\S)", " "); // results w/ token boundaries

		// Normalize spacing while preserving variations
		movetext = movetext.replaceAll("\\s*\\(\\s*", " ( "); // space before '('; none after
		movetext = movetext.replaceAll("\\s*\\)\\s*", " ) "); // space after ')'; none before
		movetext = movetext.replaceAll("\\s+", " ").trim();
		return movetext;
	}

}
