package chess.tag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import chess.core.Field;
import chess.core.Piece;
import chess.core.Position;

/**
 * Provides a focused pawn-structure inspection.
 *
 * <p>
 * The analyzer highlights classic strengths (passed/connected/advanced pawns or
 * majorities) and weaknesses (isolated, doubled, backward pawns). The output is
 * intentionally descriptive so CLI tools can dump a quick summary without
 * needing a full evaluation function.
 * </p>
 */
public final class PawnStructureAnalyzer {

    private PawnStructureAnalyzer() {
        // utility
    }

    /**
     * Analyzes the pawn structure for both sides.
     *
     * @param position position to inspect
     * @return immutable report with strengths and weaknesses for White and Black
     * @throws NullPointerException if {@code position} is {@code null}
     */
    public static PawnStructureReport analyze(Position position) {
        Objects.requireNonNull(position, "position");

        byte[] board = position.getBoard();
        @SuppressWarnings("unchecked")
        List<Byte>[] whitePawnsByFile = new ArrayList[8];
        @SuppressWarnings("unchecked")
        List<Byte>[] blackPawnsByFile = new ArrayList[8];
        int[] whiteFileCounts = new int[8];
        int[] blackFileCounts = new int[8];
        for (int file = 0; file < 8; file++) {
            whitePawnsByFile[file] = new ArrayList<>();
            blackPawnsByFile[file] = new ArrayList<>();
        }

        for (int index = 0; index < board.length; index++) {
            byte piece = board[index];
            if (!Piece.isPawn(piece)) {
                continue;
            }
            int file = Field.getX((byte) index);
            if (Piece.isWhite(piece)) {
                whitePawnsByFile[file].add((byte) index);
                whiteFileCounts[file]++;
            } else {
                blackPawnsByFile[file].add((byte) index);
                blackFileCounts[file]++;
            }
        }

        SideReport white = analyzeSide(board, true, whitePawnsByFile, whiteFileCounts, blackFileCounts);
        SideReport black = analyzeSide(board, false, blackPawnsByFile, blackFileCounts, whiteFileCounts);
        return new PawnStructureReport(white, black);
    }

    private static SideReport analyzeSide(byte[] board, boolean white, List<Byte>[] pawnsByFile, int[] ownFileCounts,
            int[] enemyFileCounts) {
        List<PawnFeature> strengths = new ArrayList<>();
        List<PawnFeature> weaknesses = new ArrayList<>();
        int pawnCount = 0;

        for (int file = 0; file < pawnsByFile.length; file++) {
            List<Byte> pawnsOnFile = pawnsByFile[file];
            pawnCount += pawnsOnFile.size();

            if (pawnsOnFile.size() > 1) {
                weaknesses.add(new PawnFeature(PawnStructureFeatureType.DOUBLED, Field.NO_SQUARE,
                        "Doubled pawns on file " + fileName(file) + " (" + describeSquares(pawnsOnFile) + ")"));
            }

            for (byte square : pawnsOnFile) {
                if (isPassedPawn(board, square, white)) {
                    strengths.add(new PawnFeature(PawnStructureFeatureType.PASSED, square,
                            "Passed pawn on " + Field.toString(square)));
                }
                if (isIsolatedPawn(board, square, white)) {
                    weaknesses.add(new PawnFeature(PawnStructureFeatureType.ISOLATED, square,
                            "Isolated pawn on " + Field.toString(square)));
                }
                if (isBackwardPawn(board, square, white)) {
                    weaknesses.add(new PawnFeature(PawnStructureFeatureType.BACKWARD, square,
                            "Backward pawn on " + Field.toString(square)));
                }
                if (isSupportedByPawn(board, square, white)) {
                    strengths.add(new PawnFeature(PawnStructureFeatureType.CONNECTED, square,
                            "Pawn chain with support on " + Field.toString(square)));
                }
                if (isAdvanced(square, white)) {
                    strengths.add(new PawnFeature(PawnStructureFeatureType.ADVANCED, square,
                            "Advanced pawn on " + Field.toString(square)));
                }
            }
        }

        addMajorityNotes(ownFileCounts, enemyFileCounts, strengths, white);

        return new SideReport(strengths, weaknesses, pawnCount, white);
    }

    private static void addMajorityNotes(int[] ownFileCounts, int[] enemyFileCounts, List<PawnFeature> strengths,
            boolean white) {
        int ownQueenSide = sumFiles(ownFileCounts, 0, 3);
        int ownKingSide = sumFiles(ownFileCounts, 4, 7);
        int enemyQueenSide = sumFiles(enemyFileCounts, 0, 3);
        int enemyKingSide = sumFiles(enemyFileCounts, 4, 7);

        int queenDiff = ownQueenSide - enemyQueenSide;
        if (queenDiff >= 2) {
            strengths.add(new PawnFeature(PawnStructureFeatureType.MAJORITY, Field.NO_SQUARE,
                    sideLabel(white) + " queenside pawn majority (" + ownQueenSide + " vs " + enemyQueenSide + ")"));
        }

        int kingDiff = ownKingSide - enemyKingSide;
        if (kingDiff >= 2) {
            strengths.add(new PawnFeature(PawnStructureFeatureType.MAJORITY, Field.NO_SQUARE,
                    sideLabel(white) + " kingside pawn majority (" + ownKingSide + " vs " + enemyKingSide + ")"));
        }
    }

    private static int sumFiles(int[] counts, int fromFile, int toFile) {
        int sum = 0;
        for (int f = fromFile; f <= toFile; f++) {
            sum += counts[f];
        }
        return sum;
    }

    private static boolean isAdvanced(byte square, boolean white) {
        int rank = Field.getY(square);
        return white ? rank >= 5 : rank <= 2;
    }

    private static boolean isPassedPawn(byte[] board, byte square, boolean white) {
        int file = Field.getX(square);
        int rank = Field.getY(square);
        for (int f = file - 1; f <= file + 1; f++) {
            if (f < 0 || f > 7) {
                continue;
            }
            if (white) {
                for (int r = rank + 1; r < 8; r++) {
                    byte occupant = board[toIndex(f, r)];
                    if (occupant == Piece.BLACK_PAWN) {
                        return false;
                    }
                }
            } else {
                for (int r = rank - 1; r >= 0; r--) {
                    byte occupant = board[toIndex(f, r)];
                    if (occupant == Piece.WHITE_PAWN) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean isIsolatedPawn(byte[] board, byte square, boolean white) {
        int file = Field.getX(square);
        for (int adjacent = file - 1; adjacent <= file + 1; adjacent++) {
            if (adjacent == file || adjacent < 0 || adjacent > 7) {
                continue;
            }
            if (hasFriendlyPawnOnFile(board, adjacent, white, square)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBackwardPawn(byte[] board, byte square, boolean white) {
        int file = Field.getX(square);
        int rank = Field.getY(square);
        int forwardRank = rank + (white ? 1 : -1);

        if (!isOnBoard(file, forwardRank)) {
            return false;
        }

        boolean blockedAhead = board[toIndex(file, forwardRank)] != Piece.EMPTY;
        boolean controlledAhead = isControlledByEnemyPawn(board, file, forwardRank, white);
        if (!blockedAhead && !controlledAhead) {
            return false;
        }

        boolean supported = false;
        for (int adjacent = file - 1; adjacent <= file + 1; adjacent++) {
            if (adjacent == file || adjacent < 0 || adjacent > 7) {
                continue;
            }
            for (int r = rank; white ? r >= 0 : r < 8; r = white ? r - 1 : r + 1) {
                byte occupant = board[toIndex(adjacent, r)];
                if (occupant != Piece.EMPTY && Piece.isPawn(occupant) && Piece.isWhite(occupant) == white) {
                    supported = true;
                    break;
                }
            }
            if (supported) {
                break;
            }
        }

        return !supported;
    }

    private static boolean isSupportedByPawn(byte[] board, byte square, boolean white) {
        int file = Field.getX(square);
        int rank = Field.getY(square);
        int supportRank = rank + (white ? -1 : 1);
        return isPawnOnSquare(board, file - 1, supportRank, white) || isPawnOnSquare(board, file + 1, supportRank, white);
    }

    private static boolean hasFriendlyPawnOnFile(byte[] board, int file, boolean white, byte ignoreSquare) {
        for (int rank = 0; rank < 8; rank++) {
            int index = toIndex(file, rank);
            if (index == ignoreSquare) {
                continue;
            }
            byte occupant = board[index];
            if (Piece.isPawn(occupant) && Piece.isWhite(occupant) == white) {
                return true;
            }
        }
        return false;
    }

    private static boolean isControlledByEnemyPawn(byte[] board, int file, int rank, boolean white) {
        int enemyRank = rank + (white ? 1 : -1);
        return isPawnOnSquare(board, file - 1, enemyRank, !white) || isPawnOnSquare(board, file + 1, enemyRank, !white);
    }

    private static boolean isPawnOnSquare(byte[] board, int file, int rank, boolean white) {
        if (!isOnBoard(file, rank)) {
            return false;
        }
        byte occupant = board[toIndex(file, rank)];
        return Piece.isPawn(occupant) && Piece.isWhite(occupant) == white;
    }

    private static int toIndex(int file, int rank) {
        return (7 - rank) * 8 + file;
    }

    private static boolean isOnBoard(int file, int rank) {
        return file >= 0 && file < 8 && rank >= 0 && rank < 8;
    }

    private static String describeSquares(List<Byte> squares) {
        List<String> coords = new ArrayList<>();
        for (byte square : squares) {
            coords.add(Field.toString(square));
        }
        return String.join(", ", coords);
    }

    private static String fileName(int file) {
        return String.valueOf((char) ('a' + file));
    }

    private static String sideLabel(boolean white) {
        return white ? "White" : "Black";
    }

    /**
     * Immutable result describing both sides.
     */
    public static final class PawnStructureReport {
        private final SideReport white;
        private final SideReport black;

        private PawnStructureReport(SideReport white, SideReport black) {
            this.white = white;
            this.black = black;
        }

        public SideReport getWhite() {
            return white;
        }

        public SideReport getBlack() {
            return black;
        }
    }

    /**
     * Per-side strengths and weaknesses.
     */
    public static final class SideReport {
        private final List<PawnFeature> strengths;
        private final List<PawnFeature> weaknesses;
        private final int pawnCount;
        private final boolean whiteSide;

        private SideReport(List<PawnFeature> strengths, List<PawnFeature> weaknesses, int pawnCount, boolean whiteSide) {
            this.strengths = Collections.unmodifiableList(new ArrayList<>(strengths));
            this.weaknesses = Collections.unmodifiableList(new ArrayList<>(weaknesses));
            this.pawnCount = pawnCount;
            this.whiteSide = whiteSide;
        }

        public List<PawnFeature> getStrengths() {
            return strengths;
        }

        public List<PawnFeature> getWeaknesses() {
            return weaknesses;
        }

        public int getPawnCount() {
            return pawnCount;
        }

        public boolean isWhiteSide() {
            return whiteSide;
        }
    }

    /**
     * Describes a single pawn-structure feature.
     */
    public static final class PawnFeature {
        private final PawnStructureFeatureType type;
        private final byte square;
        private final String description;

        private PawnFeature(PawnStructureFeatureType type, byte square, String description) {
            this.type = type;
            this.square = square;
            this.description = description;
        }

        public PawnStructureFeatureType getType() {
            return type;
        }

        public byte getSquare() {
            return square;
        }

        public String getDescription() {
            return description;
        }

        @Override
        public String toString() {
            if (square == Field.NO_SQUARE) {
                return type + ": " + description;
            }
            return type + " on " + Field.toString(square) + ": " + description;
        }
    }

    /**
     * Types of pawn-structure motifs captured by the analyzer.
     */
    public enum PawnStructureFeatureType {
        PASSED,
        ISOLATED,
        DOUBLED,
        BACKWARD,
        CONNECTED,
        ADVANCED,
        MAJORITY
    }
}
