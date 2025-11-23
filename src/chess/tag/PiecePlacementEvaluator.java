package chess.tag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import chess.core.Field;
import chess.core.Piece;
import chess.core.Position;

/**
 * Provides a lightweight, rule-based piece placement evaluation.
 *
 * <p>
 * The heuristics focus on classical ideas: centralized knights, rooks on open
 * files, advanced pawns, bishop mobility, and king safety depending on the
 * phase of the game. The goal is not to replace a full evaluation function, but
 * to offer human readable hints about how well or poorly each piece is placed.
 * </p>
 * 
 * @since 2025
 * @author Lennart A. Conrad
 */
public final class PiecePlacementEvaluator {

    /**
     * Orthogonal deltas for rook movement. Used for evaluating rook mobility as
     * well as helper functions that count sliding moves.
     */
    private static final int[][] ROOK_DIRECTIONS = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };

    /**
     * Diagonal deltas for bishop movement. Used for evaluating bishop mobility
     * (and part of the queen mobility metrics).
     */
    private static final int[][] BISHOP_DIRECTIONS = { { 1, 1 }, { 1, -1 }, { -1, 1 }, { -1, -1 } };

    /**
     * Material level that roughly separates middlegame from endgame. When the
     * active material falls below this threshold the king evaluation encourages
     * central play.
     */
    private static final int ENDGAME_MATERIAL_THRESHOLD = Piece.VALUE_QUEEN + 2 * Piece.VALUE_ROOK
            + 2 * Piece.VALUE_BISHOP + 2 * Piece.VALUE_KNIGHT;

    /**
     * Prevent instantiation, this class only exposes static helpers.
     */
    private PiecePlacementEvaluator() {
        // Utility class
    }

    /**
     * Evaluates every piece in the provided position and returns a structured
     * explanation of the placement.
     *
     * @param position the position to inspect
     * @return immutable list with one entry per non-empty square
     * @throws NullPointerException if {@code position} is {@code null}
     */
    public static List<PiecePlacementInsight> evaluate(Position position) {
        Objects.requireNonNull(position, "position");

        byte[] board = position.getBoard();
        boolean endgame = position.countTotalMaterial() <= ENDGAME_MATERIAL_THRESHOLD;
        List<PiecePlacementInsight> insights = new ArrayList<>();

        for (int index = 0; index < board.length; index++) {
            byte piece = board[index];
            if (piece == Piece.EMPTY) {
                continue;
            }
            insights.add(evaluatePiece(board, (byte) index, piece, endgame));
        }

        return Collections.unmodifiableList(insights);
    }

    /**
     * Builds a placement summary that aggregates the individual insights and adds
     * overall side-to-move scoring information.
     */
    public static PlacementSummary summarize(Position position) {
        List<PiecePlacementInsight> insights = evaluate(position);
        int whiteScore = 0;
        int blackScore = 0;
        for (PiecePlacementInsight insight : insights) {
            if (insight.isWhitePiece()) {
                whiteScore += insight.getScore();
            } else {
                blackScore += insight.getScore();
            }
        }
        return new PlacementSummary(insights, whiteScore, blackScore, position.isWhiteTurn());
    }

    /**
     * Builds an 8x8 heatmap describing how well pieces are placed.
     *
     * <p>
     * Each entry holds the score returned for the piece currently located on the
     * respective square (zero for empty squares). The matrix is indexed by rank
     * from White's perspective: {@code heatmap[0][0]} corresponds to a1,
     * {@code heatmap[7][7]} to h8.
     * </p>
     *
     * @param position the position that should be visualized
     * @return a newly allocated int matrix with placement scores
     * @throws NullPointerException if {@code position} is {@code null}
     */
    public static int[][] buildHeatmap(Position position) {
        Objects.requireNonNull(position, "position");

        int[][] heatmap = new int[8][8];
        for (PiecePlacementInsight insight : evaluate(position)) {
            int file = Field.getX(insight.getSquare());
            int rankFromBottom = Field.getY(insight.getSquare());
            heatmap[rankFromBottom][file] = insight.getScore();
        }
        return heatmap;
    }

    /**
     * Builds an insight structure for a single piece.
     *
     * @param board   current board array
     * @param square  board index of the piece
     * @param piece   piece code from {@link Piece}
     * @param endgame whether the overall material level suggests an endgame
     * @return descriptive insight for the provided coordinates
     */
    private static PiecePlacementInsight evaluatePiece(byte[] board, byte square, byte piece, boolean endgame) {
        List<String> comments = new ArrayList<>();
        int score = 0;

        if (Piece.isKnight(piece)) {
            score += assessKnight(board, square, piece, comments);
        } else if (Piece.isBishop(piece)) {
            score += assessBishop(board, square, piece, comments);
        } else if (Piece.isRook(piece)) {
            score += assessRook(board, square, piece, comments);
        } else if (Piece.isQueen(piece)) {
            score += assessQueen(board, square, piece, comments);
        } else if (Piece.isKing(piece)) {
            score += assessKing(square, piece, endgame, comments);
        } else if (Piece.isPawn(piece)) {
            score += assessPawn(board, square, piece, comments);
        }

        score += assessProtection(board, square, piece, comments);

        return new PiecePlacementInsight(piece, square, score, comments);
    }

    /**
     * Scores a knight based on basic strategic principles (centralization and rim
     * penalties).
     *
     * @param square   current square of the knight
     * @param comments list that gets populated with textual explanations
     * @return heuristic score contribution for the knight
     */
    private static int assessKnight(byte[] board, byte square, byte piece, List<String> comments) {
        int file = Field.getX(square);
        int rank = Field.getY(square);
        boolean white = Piece.isWhite(piece);
        int score = 0;

        if (isStrongCenter(file, rank)) {
            score += 35;
            comments.add("Knight perfectly centralized.");
        } else if (isExtendedCenter(file, rank)) {
            score += 20;
            comments.add("Knight eyes the center.");
        }

        if (isOnRim(file, rank)) {
            score -= 25;
            comments.add("Knight on the rim is dim.");
        }

        if ((white && rank >= 5) || (!white && rank <= 2)) {
            score += 10;
            comments.add("Advanced knight pressures the opponent camp.");
        }

        if (isKnightOutpost(board, square, white)) {
            score += 25;
            comments.add("Knight entrenched on an outpost.");
        }

        return score;
    }

    /**
     * Scores a bishop by taking mobility and diagonal placement into account.
     *
     * @param board    current board layout
     * @param square   square of the bishop
     * @param piece    bishop code
     * @param comments list to append explanations to
     * @return heuristic score for the bishop
     */
    private static int assessBishop(byte[] board, byte square, byte piece, List<String> comments) {
        int score = 0;
        int mobility = countSlidingMobility(board, square, piece, BISHOP_DIRECTIONS);

        if (mobility >= 7) {
            score += 20;
            comments.add("Bishop controls a long diagonal.");
        } else if (mobility <= 3) {
            score -= 10;
            comments.add("Bishop is constrained by nearby pieces.");
        }

        if (isOnLongDiagonal(square)) {
            score += 10;
            comments.add("Bishop sits on the long diagonal.");
        }

        if (isBishopHemmedByPawns(board, square, Piece.isWhite(piece))) {
            score -= 12;
            comments.add("Bishop blocked by own pawns.");
        }

        return score;
    }

    /**
     * Scores a rook by evaluating mobility, file control, and rank placement.
     *
     * @param board    current board layout
     * @param square   square of the rook
     * @param piece    rook code
     * @param comments list to append explanations to
     * @return heuristic score for the rook
     */
    private static int assessRook(byte[] board, byte square, byte piece, List<String> comments) {
        int score = 0;
        boolean white = Piece.isWhite(piece);
        int file = Field.getX(square);
        int rank = Field.getY(square);

        int mobility = countSlidingMobility(board, square, piece, ROOK_DIRECTIONS);
        if (mobility >= 7) {
            score += 15;
            comments.add("Rook has open lines.");
        }

        boolean friendlyPawn = fileContainsPawn(board, file, white, Field.NO_SQUARE);
        boolean enemyPawn = fileContainsPawn(board, file, !white, Field.NO_SQUARE);

        if (!friendlyPawn && !enemyPawn) {
            score += 25;
            comments.add("Rook occupies a fully open file.");
        } else if (!friendlyPawn) {
            score += 15;
            comments.add("Rook controls a semi-open file.");
        }

        if (white && Field.isOn7thRank(square)) {
            score += 20;
            comments.add("White rook on the seventh rank.");
        } else if (!white && Field.isOn2ndRank(square)) {
            score += 20;
            comments.add("Black rook on the seventh rank.");
        }

        if ((white && rank == 0) || (!white && rank == 7)) {
            score -= 10;
            comments.add("Rook is undeveloped on the starting rank.");
        }

        return score;
    }

    /**
     * Scores a queen based on centralization and development.
     *
     * @param square   queen square
     * @param piece    queen code
     * @param comments list of textual explanations
     * @return heuristic score for the queen
     */
    private static int assessQueen(byte[] board, byte square, byte piece, List<String> comments) {
        int file = Field.getX(square);
        int rank = Field.getY(square);
        int score = 0;

        if (isStrongCenter(file, rank)) {
            score += 15;
            comments.add("Queen dominates the central squares.");
        } else if (isExtendedCenter(file, rank)) {
            score += 8;
            comments.add("Queen eyes the center.");
        }

        if (isOnRim(file, rank)) {
            score -= 10;
            comments.add("Queen on the rim lacks scope.");
        }

        if ((Piece.isWhite(piece) && rank <= 1) || (Piece.isBlack(piece) && rank >= 6)) {
            score -= 5;
            comments.add("Queen has not left the back rank.");
        }

        int queenMobility = countSlidingMobility(board, square, piece, ROOK_DIRECTIONS)
                + countSlidingMobility(board, square, piece, BISHOP_DIRECTIONS);
        if (queenMobility >= 12) {
            score += 15;
            comments.add("Queen enjoys excellent mobility.");
        } else if (queenMobility <= 6) {
            score -= 10;
            comments.add("Queen is boxed in by surrounding pieces.");
        }

        return score;
    }

    /**
     * Scores a king with phase-specific heuristics (centralizing in the endgame,
     * safety otherwise).
     *
     * @param square   king square
     * @param piece    king code
     * @param endgame  indicates if the material threshold is below endgame limit
     * @param comments list of textual explanations
     * @return heuristic score for the king
     */
    private static int assessKing(byte square, byte piece, boolean endgame, List<String> comments) {
        boolean white = Piece.isWhite(piece);
        int file = Field.getX(square);
        int rank = Field.getY(square);
        int score = 0;

        if (endgame) {
            if (isStrongCenter(file, rank)) {
                score += 25;
                comments.add("King centralized for the endgame.");
            } else if (isExtendedCenter(file, rank)) {
                score += 10;
                comments.add("King is moving towards the center.");
            }
            if (isOnRim(file, rank)) {
                score -= 15;
                comments.add("Endgame king stuck on the rim.");
            }
        } else {
            if ((white && (square == Field.G1 || square == Field.C1))
                    || (!white && (square == Field.G8 || square == Field.C8))) {
                score += 25;
                comments.add("King safely castled.");
            }
            if (isExtendedCenter(file, rank)) {
                score -= 20;
                comments.add("Exposed king in the center.");
            }
            if ((white && rank >= 2) || (!white && rank <= 5)) {
                score -= 10;
                comments.add("King ventured forward before the endgame.");
            }
        }

        return score;
    }

    /**
     * Scores a pawn using simple structure heuristics (advance, passed, isolated,
     * centralization).
     *
     * @param board    current board layout
     * @param square   pawn square
     * @param piece    pawn code
     * @param comments list to append explanations to
     * @return heuristic score for the pawn
     */
    private static int assessPawn(byte[] board, byte square, byte piece, List<String> comments) {
        boolean white = Piece.isWhite(piece);
        int file = Field.getX(square);
        int rank = Field.getY(square);
        int score = 0;

        int progress = white ? rank - 1 : 6 - rank;
        if (progress > 0) {
            score += progress * 3;
            comments.add("Pawn advanced " + progress + " ranks.");
        }

        if (file >= 2 && file <= 5) {
            score += 5;
            comments.add("Central pawn controls key files.");
        }

        if (isPawnSupportedByPawn(board, file, rank, white)) {
            score += 8;
            comments.add("Pawn supported by fellow pawn.");
        }

        if (isPassedPawn(board, file, rank, white)) {
            score += 25;
            comments.add("Passed pawn ready to advance.");
        }

        if (isIsolatedPawn(board, square, white)) {
            score -= 15;
            comments.add("Isolated pawn lacks support.");
        }

        if (isDoubledPawn(board, file, rank, white)) {
            score -= 12;
            comments.add("Doubled pawn on the same file.");
        }

        return score;
    }

    /**
     * Adds general-purpose protection insights for any piece.
     */
    private static int assessProtection(byte[] board, byte square, byte piece, List<String> comments) {
        boolean white = Piece.isWhite(piece);
        List<String> defenders = listDefenders(board, square, white);
        int score = 0;

        if (defenders.isEmpty()) {
            score -= 12;
            comments.add("Undefended piece.");
        } else if (defenders.size() >= 2) {
            score += 6;
            comments.add("Well defended by " + String.join(", ", defenders) + ".");
        } else {
            comments.add("Defended by " + defenders.get(0) + ".");
        }

        List<String> protectedPieces = listProtectedPieces(board, square, piece);
        if (!protectedPieces.isEmpty()) {
            comments.add("Protects " + String.join(", ", protectedPieces) + ".");
        }

        return score;
    }

    /**
     * Counts the number of legal squares a sliding piece (bishop/rook) can reach,
     * including capture squares.
     *
     * @param board      current board
     * @param square     start square
     * @param piece      sliding piece code
     * @param directions normalized (file, rank) direction deltas to travel
     * @return number of plies available before pieces block further travel
     */
    private static int countSlidingMobility(byte[] board, byte square, byte piece, int[][] directions) {
        int mobility = 0;
        boolean white = Piece.isWhite(piece);
        int startFile = Field.getX(square);
        int startRank = Field.getY(square);

        for (int[] delta : directions) {
            int file = startFile + delta[0];
            int rank = startRank + delta[1];
            while (isOnBoard(file, rank)) {
                byte occupant = board[toIndex(file, rank)];
                if (occupant == Piece.EMPTY) {
                    mobility++;
                } else {
                    if (Piece.isWhite(occupant) != white) {
                        mobility++; // count capture squares
                    }
                    break;
                }
                file += delta[0];
                rank += delta[1];
            }
        }

        return mobility;
    }

    private static List<String> listDefenders(byte[] board, byte targetSquare, boolean whitePiece) {
        List<String> defenders = new ArrayList<>();
        for (int index = 0; index < board.length; index++) {
            if (index == targetSquare) {
                continue;
            }
            byte occupant = board[index];
            if (occupant == Piece.EMPTY || Piece.isWhite(occupant) != whitePiece) {
                continue;
            }
            if (attacksSquare(board, (byte) index, occupant, targetSquare)) {
                defenders.add(describePiece(occupant, (byte) index));
            }
        }
        return defenders;
    }

    private static List<String> listProtectedPieces(byte[] board, byte square, byte piece) {
        boolean white = Piece.isWhite(piece);
        List<String> protectedPieces = new ArrayList<>();
        int file = Field.getX(square);
        int rank = Field.getY(square);

        if (Piece.isPawn(piece)) {
            int forwardRank = rank + (white ? 1 : -1);
            addIfFriendly(board, file - 1, forwardRank, white, protectedPieces);
            addIfFriendly(board, file + 1, forwardRank, white, protectedPieces);
        } else if (Piece.isKnight(piece)) {
            for (int dx : new int[] { -2, -1, 1, 2 }) {
                for (int dy : new int[] { -2, -1, 1, 2 }) {
                    if (Math.abs(dx) == Math.abs(dy)) {
                        continue;
                    }
                    addIfFriendly(board, file + dx, rank + dy, white, protectedPieces);
                }
            }
        } else if (Piece.isBishop(piece)) {
            collectSlidingProtections(board, file, rank, white, protectedPieces, BISHOP_DIRECTIONS);
        } else if (Piece.isRook(piece)) {
            collectSlidingProtections(board, file, rank, white, protectedPieces, ROOK_DIRECTIONS);
        } else if (Piece.isQueen(piece)) {
            collectSlidingProtections(board, file, rank, white, protectedPieces, BISHOP_DIRECTIONS);
            collectSlidingProtections(board, file, rank, white, protectedPieces, ROOK_DIRECTIONS);
        } else if (Piece.isKing(piece)) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) {
                        continue;
                    }
                    addIfFriendly(board, file + dx, rank + dy, white, protectedPieces);
                }
            }
        }

        return protectedPieces;
    }

    private static void collectSlidingProtections(byte[] board, int startFile, int startRank, boolean white,
            List<String> protectedPieces, int[][] directions) {
        for (int[] delta : directions) {
            int file = startFile + delta[0];
            int rank = startRank + delta[1];
            while (isOnBoard(file, rank)) {
                byte occupant = board[toIndex(file, rank)];
                if (occupant != Piece.EMPTY) {
                    if (Piece.isWhite(occupant) == white) {
                        protectedPieces.add(describePiece(occupant, (byte) toIndex(file, rank)));
                    }
                    break;
                }
                file += delta[0];
                rank += delta[1];
            }
        }
    }

    private static void addIfFriendly(byte[] board, int file, int rank, boolean white, List<String> protectedPieces) {
        if (!isOnBoard(file, rank)) {
            return;
        }
        byte occupant = board[toIndex(file, rank)];
        if (occupant != Piece.EMPTY && Piece.isWhite(occupant) == white) {
            protectedPieces.add(describePiece(occupant, (byte) toIndex(file, rank)));
        }
    }

    /**
     * Detects whether a knight is on an outpost (no enemy pawn can chase it and a
     * friendly pawn supports it from behind).
     */
    private static boolean isKnightOutpost(byte[] board, byte square, boolean white) {
        int file = Field.getX(square);
        int rank = Field.getY(square);

        int enemyRank = rank + (white ? 1 : -1);
        boolean chasedFromLeft = isPawnOnSquare(board, file - 1, enemyRank, !white);
        boolean chasedFromRight = isPawnOnSquare(board, file + 1, enemyRank, !white);
        if (chasedFromLeft || chasedFromRight) {
            return false;
        }

        int friendlyRank = rank + (white ? -1 : 1);
        return isPawnOnSquare(board, file - 1, friendlyRank, white)
                || isPawnOnSquare(board, file + 1, friendlyRank, white);
    }

    /**
     * @return {@code true} when both diagonals in front of the bishop are blocked
     *         by friendly pawns so that development is difficult.
     */
    private static boolean isBishopHemmedByPawns(byte[] board, byte square, boolean white) {
        int file = Field.getX(square);
        int rank = Field.getY(square);
        int forwardRank = rank + (white ? 1 : -1);
        if (!isOnBoard(file, forwardRank)) {
            return false;
        }
        boolean left = isPawnOnSquare(board, file - 1, forwardRank, white);
        boolean right = isPawnOnSquare(board, file + 1, forwardRank, white);
        return left && right;
    }

    /**
     * Checks whether a pawn is defended by another pawn on the previous rank.
     */
    private static boolean isPawnSupportedByPawn(byte[] board, int file, int rank, boolean white) {
        int supportRank = rank + (white ? -1 : 1);
        return isPawnOnSquare(board, file - 1, supportRank, white)
                || isPawnOnSquare(board, file + 1, supportRank, white);
    }

    /**
     * Detects doubled pawns (two or more pawns of the same color residing on one
     * file).
     */
    private static boolean isDoubledPawn(byte[] board, int file, int rank, boolean white) {
        if (white) {
            for (int r = 0; r < rank; r++) {
                if (isPawnOnSquare(board, file, r, true)) {
                    return true;
                }
            }
        } else {
            for (int r = 7; r > rank; r--) {
                if (isPawnOnSquare(board, file, r, false)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isPawnOnSquare(byte[] board, int file, int rank, boolean white) {
        if (!isOnBoard(file, rank)) {
            return false;
        }
        byte occupant = board[toIndex(file, rank)];
        return Piece.isPawn(occupant) && Piece.isWhite(occupant) == white;
    }

    private static String describePiece(byte piece, byte square) {
        return Piece.toLowerCaseChar(piece) + Field.toString(square);
    }

    private static boolean attacksSquare(byte[] board, byte attackerSquare, byte attackerPiece, byte targetSquare) {
        int attackerFile = Field.getX(attackerSquare);
        int attackerRank = Field.getY(attackerSquare);
        int targetFile = Field.getX(targetSquare);
        int targetRank = Field.getY(targetSquare);

        if (Piece.isPawn(attackerPiece)) {
            int forward = Piece.isWhite(attackerPiece) ? 1 : -1;
            return targetRank - attackerRank == forward && Math.abs(targetFile - attackerFile) == 1;
        } else if (Piece.isKnight(attackerPiece)) {
            int dx = Math.abs(targetFile - attackerFile);
            int dy = Math.abs(targetRank - attackerRank);
            return (dx == 1 && dy == 2) || (dx == 2 && dy == 1);
        } else if (Piece.isBishop(attackerPiece)) {
            return attacksAlongDirections(board, attackerFile, attackerRank, targetFile, targetRank, BISHOP_DIRECTIONS);
        } else if (Piece.isRook(attackerPiece)) {
            return attacksAlongDirections(board, attackerFile, attackerRank, targetFile, targetRank, ROOK_DIRECTIONS);
        } else if (Piece.isQueen(attackerPiece)) {
            return attacksAlongDirections(board, attackerFile, attackerRank, targetFile, targetRank, BISHOP_DIRECTIONS)
                    || attacksAlongDirections(board, attackerFile, attackerRank, targetFile, targetRank,
                            ROOK_DIRECTIONS);
        } else if (Piece.isKing(attackerPiece)) {
            return Math.abs(attackerFile - targetFile) <= 1 && Math.abs(attackerRank - targetRank) <= 1
                    && !(attackerFile == targetFile && attackerRank == targetRank);
        }
        return false;
    }

    private static boolean attacksAlongDirections(byte[] board, int attackerFile, int attackerRank, int targetFile,
            int targetRank, int[][] directions) {
        for (int[] delta : directions) {
            int file = attackerFile + delta[0];
            int rank = attackerRank + delta[1];
            while (isOnBoard(file, rank)) {
                if (file == targetFile && rank == targetRank) {
                    return true;
                }
                byte occupant = board[toIndex(file, rank)];
                if (occupant != Piece.EMPTY) {
                    break;
                }
                file += delta[0];
                rank += delta[1];
            }
        }
        return false;
    }

    /**
     * Checks if no friendly pawn occupies an adjacent file.
     *
     * @param board  current board
     * @param square location of the pawn
     * @param white  true if the pawn belongs to White
     * @return {@code true} if the pawn is isolated
     */
    private static boolean isIsolatedPawn(byte[] board, byte square, boolean white) {
        int file = Field.getX(square);
        for (int adjacent = file - 1; adjacent <= file + 1; adjacent++) {
            if (adjacent == file || adjacent < 0 || adjacent > 7) {
                continue;
            }
            if (fileContainsPawn(board, adjacent, white, square)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks whether a pawn is passed (no opposing pawns on same or adjacent files
     * ahead of it).
     *
     * @param board current board
     * @param file  file index of the pawn
     * @param rank  rank from White's perspective (0 bottom)
     * @param white {@code true} when the pawn is White
     * @return {@code true} if the pawn is passed
     */
    private static boolean isPassedPawn(byte[] board, int file, int rank, boolean white) {
        for (int targetFile = file - 1; targetFile <= file + 1; targetFile++) {
            if (targetFile < 0 || targetFile > 7) {
                continue;
            }
            if (white) {
                for (int targetRank = rank + 1; targetRank < 8; targetRank++) {
                    byte occupant = board[toIndex(targetFile, targetRank)];
                    if (Piece.isPawn(occupant) && Piece.isBlack(occupant)) {
                        return false;
                    }
                }
            } else {
                for (int targetRank = rank - 1; targetRank >= 0; targetRank--) {
                    byte occupant = board[toIndex(targetFile, targetRank)];
                    if (Piece.isPawn(occupant) && Piece.isWhite(occupant)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Checks whether a file contains a pawn of the requested color (optionally
     * skipping a particular square).
     *
     * @param board        board state
     * @param file         file to search
     * @param white        {@code true} for White pawns
     * @param ignoreSquare board index to skip or {@link Field#NO_SQUARE}
     * @return {@code true} if a matching pawn is located somewhere on the file
     */
    private static boolean fileContainsPawn(byte[] board, int file, boolean white, byte ignoreSquare) {
        for (int rank = 0; rank < 8; rank++) {
            byte square = (byte) toIndex(file, rank);
            if (ignoreSquare != Field.NO_SQUARE && square == ignoreSquare) {
                continue;
            }
            byte occupant = board[square];
            if (Piece.isPawn(occupant) && Piece.isWhite(occupant) == white) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether the square lies on either long diagonal a1-h8 or a8-h1.
     *
     * @param square board index
     * @return {@code true} if the square is on a long diagonal
     */
    private static boolean isOnLongDiagonal(byte square) {
        int file = Field.getX(square);
        int rank = Field.getY(square);
        return file == rank || file + rank == 7;
    }

    /**
     * Checks whether coordinates belong to the extended center (c3-f6).
     *
     * @param file file coordinate (0-7)
     * @param rank rank coordinate (0 bottom)
     * @return {@code true} if square is inside extended center bounds
     */
    private static boolean isExtendedCenter(int file, int rank) {
        return file >= 2 && file <= 5 && rank >= 2 && rank <= 5;
    }

    /**
     * Checks whether coordinates belong to the strong center (d4-e5).
     *
     * @param file file coordinate (0-7)
     * @param rank rank coordinate (0 bottom)
     * @return {@code true} if square is inside the strong center
     */
    private static boolean isStrongCenter(int file, int rank) {
        return file >= 3 && file <= 4 && rank >= 3 && rank <= 4;
    }

    /**
     * Checks whether a coordinate sits on the rim (files a/h or ranks 1/8).
     *
     * @param file file coordinate (0-7)
     * @param rank rank coordinate (0 bottom)
     * @return {@code true} if the coordinate is on the rim
     */
    private static boolean isOnRim(int file, int rank) {
        return file == 0 || file == 7 || rank == 0 || rank == 7;
    }

    /**
     * Validates that (file, rank) is inside the board boundaries.
     *
     * @param file file coordinate (0-7)
     * @param rank rank coordinate (0-7)
     * @return {@code true} if the coordinate is a valid square
     */
    private static boolean isOnBoard(int file, int rank) {
        return file >= 0 && file < 8 && rank >= 0 && rank < 8;
    }

    /**
     * Converts cartesian coordinates into the 0..63 board index.
     *
     * @param file           file coordinate (0 left/a .. 7 right/h)
     * @param rankFromBottom rank coordinate counted from White's side (0 == first
     *                       rank)
     * @return numeric board index (0 == a8)
     */
    private static int toIndex(int file, int rankFromBottom) {
        return (7 - rankFromBottom) * 8 + file;
    }

    /**
     * Immutable data holder describing the score and explanation for a single piece
     * on a given square.
     */
    public static final class PiecePlacementInsight {
        private final byte piece;
        private final byte square;
        private final int score;
        private final List<String> comments;

        private PiecePlacementInsight(byte piece, byte square, int score, List<String> comments) {
            this.piece = piece;
            this.square = square;
            this.score = score;
            this.comments = Collections.unmodifiableList(new ArrayList<>(comments));
        }

        /**
         * @return raw {@link Piece} code stored in the board array
         */
        public byte getPiece() {
            return piece;
        }

        /**
         * @return board index identifying where the piece currently sits
         */
        public byte getSquare() {
            return square;
        }

        /**
         * @return placement score accumulated for the piece
         */
        public int getScore() {
            return score;
        }

        /**
         * @return immutable set of textual explanations
         */
        public List<String> getComments() {
            return comments;
        }

        /**
         * @return {@code true} if the piece belongs to White
         */
        public boolean isWhitePiece() {
            return Piece.isWhite(piece);
        }

        /**
         * @return string representation used for debugging and CLI dumps
         */
        @Override
        public String toString() {
            return String.format("%s on %s (%d): %s", Piece.toUpperCaseChar(piece), Field.toString(square), score,
                    comments);
        }
    }

    /**
     * Immutable view aggregating the placement totals for each side.
     */
    public static final class PlacementSummary {
        private final List<PiecePlacementInsight> insights;
        private final int whiteScore;
        private final int blackScore;
        private final boolean whiteToMove;

        private PlacementSummary(List<PiecePlacementInsight> insights, int whiteScore, int blackScore,
                boolean whiteToMove) {
            this.insights = insights;
            this.whiteScore = whiteScore;
            this.blackScore = blackScore;
            this.whiteToMove = whiteToMove;
        }

        /**
         * @return immutable list with a breakdown per piece.
         */
        public List<PiecePlacementInsight> getInsights() {
            return insights;
        }

        /**
         * @return aggregate score for White pieces.
         */
        public int getWhiteScore() {
            return whiteScore;
        }

        /**
         * @return aggregate score for Black pieces.
         */
        public int getBlackScore() {
            return blackScore;
        }

        /**
         * @return {@code true} if it's White's turn.
         */
        public boolean isWhiteToMove() {
            return whiteToMove;
        }

        /**
         * @return placement evaluation from the side-to-move perspective (positive is
         *         better for the mover).
         */
        public int getSideToMoveScore() {
            return whiteToMove ? whiteScore - blackScore : blackScore - whiteScore;
        }
    }
}
