package chess.tag;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

import chess.core.Field;
import chess.core.Move;
import chess.core.MoveList;
import chess.core.Piece;
import chess.core.Position;

/**
 * Lightweight, rule-based feature extractor that annotates positions with puzzle
 * themes frequently used on Chess.com, Lichess, ChessTempo, and similar
 * services.
 *
 * <p>
 * The implementation intentionally favors explainability over absolute tactical
 * accuracy. Each heuristic is documented so we can progressively expand the set
 * of recognised themes.
 * </p>
 */
public final class PositionTagExtractor {

    private static final int[][] KNIGHT_DELTAS = {
            { 1, 2 }, { 2, 1 }, { 2, -1 }, { 1, -2 },
            { -1, -2 }, { -2, -1 }, { -2, 1 }, { -1, 2 }
    };
    private static final int[][] KING_DELTAS = {
            { 1, 0 }, { 1, 1 }, { 0, 1 }, { -1, 1 },
            { -1, 0 }, { -1, -1 }, { 0, -1 }, { 1, -1 }
    };
    private static final int[][] BISHOP_DIRECTIONS = { { 1, 1 }, { 1, -1 }, { -1, 1 }, { -1, -1 } };
    private static final int[][] ROOK_DIRECTIONS = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };

    private PositionTagExtractor() {
        // utility class
    }

    /**
     * Extracts all applicable puzzle themes for the transition {@code parent -> position}.
     *
     * @param parent   position before the move
     * @param position position after the move
     * @return set of puzzle themes that describe the tactic/structure
     */
    public static EnumSet<PuzzleTheme> extract(Position parent, Position position) {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(position, "position");

        EnumSet<PuzzleTheme> tags = EnumSet.noneOf(PuzzleTheme.class);
        byte[] board = position.getBoard();
        byte[] parentBoard = parent.getBoard();
        boolean moverIsWhite = parent.isWhiteTurn();

        applyPhaseTags(position, tags);
        applyMaterialTags(parent, position, moverIsWhite, tags);
        applyKingSafetyTags(position, board, tags);
        applyPawnStructureTags(board, tags);
        applyTacticalTags(parent, position, parentBoard, board, moverIsWhite, tags);
        applyStateTags(parent, position, moverIsWhite, tags);

        return tags;
    }

    private static void applyPhaseTags(Position position, EnumSet<PuzzleTheme> tags) {
        int totalPieces = position.countTotalPieces();
        if (totalPieces >= 26) {
            tags.add(PuzzleTheme.OPENING);
        } else if (totalPieces >= 13) {
            tags.add(PuzzleTheme.MIDDLEGAME);
        } else {
            tags.add(PuzzleTheme.ENDGAME);
        }
    }

    private static void applyMaterialTags(Position parent, Position position, boolean moverIsWhite,
            EnumSet<PuzzleTheme> tags) {
        int balance = position.materialDiscrepancy();
        if (balance >= 900) {
            tags.add(PuzzleTheme.WHITE_CRUSHING);
        } else if (balance >= 300) {
            tags.add(PuzzleTheme.WHITE_ADVANTAGE);
        } else if (balance <= -900) {
            tags.add(PuzzleTheme.BLACK_CRUSHING);
        } else if (balance <= -300) {
            tags.add(PuzzleTheme.BLACK_ADVANTAGE);
        } else {
            tags.add(PuzzleTheme.EQUALITY);
        }

        if (Math.abs(position.countWhitePieces() - position.countBlackPieces()) >= 2) {
            tags.add(PuzzleTheme.MATERIAL_IMBALANCE);
        }

        int heroBefore = moverIsWhite ? parent.countWhiteMaterial() : parent.countBlackMaterial();
        int heroAfter = moverIsWhite ? position.countWhiteMaterial() : position.countBlackMaterial();
        if (heroAfter > heroBefore + Piece.VALUE_PAWN) {
            if (moverIsWhite) {
                tags.add(PuzzleTheme.WHITE_ADVANTAGE);
            } else {
                tags.add(PuzzleTheme.BLACK_ADVANTAGE);
            }
        }
    }

    private static void applyKingSafetyTags(Position position, byte[] board, EnumSet<PuzzleTheme> tags) {
        boolean defenderIsWhite = position.isWhiteTurn();
        byte kingSquare = defenderIsWhite ? position.getWhiteKing() : position.getBlackKing();
        List<Integer> attackers = collectAttackers(board, kingSquare, !defenderIsWhite);

        if (position.inCheck()) {
            tags.add(PuzzleTheme.CHECK);
            tags.add(PuzzleTheme.EXPOSED_KING);
            if (attackers.size() >= 2) {
                tags.add(PuzzleTheme.DOUBLE_CHECK);
            }
        }

        if (position.isMate()) {
            tags.add(PuzzleTheme.CHECKMATE);
            tags.add(PuzzleTheme.MATE_IN_ONE);
            if (defenderIsWhite && Field.isOn1stRank(kingSquare)
                    || !defenderIsWhite && Field.isOn8thRank(kingSquare)) {
                tags.add(PuzzleTheme.BACK_RANK_MATE);
            }

            if (attackers.size() == 1) {
                byte attackerPiece = board[attackers.get(0)];
                if (Piece.isKnight(attackerPiece) && isSmothered(board, kingSquare, defenderIsWhite)) {
                    tags.add(PuzzleTheme.SMOTHERED_MATE);
                }
            }
        }
    }

    private static void applyPawnStructureTags(byte[] board, EnumSet<PuzzleTheme> tags) {
        boolean advancedPawn = false;
        boolean passedPawn = false;
        boolean isolatedPawn = false;
        boolean doubledPawn = false;
        boolean backwardPawn = false;
        int[] whiteFileCount = new int[8];
        int[] blackFileCount = new int[8];

        for (int index = 0; index < board.length; index++) {
            byte piece = board[index];
            if (!Piece.isPawn(piece)) {
                continue;
            }
            boolean white = Piece.isWhite(piece);
            int file = Field.getX((byte) index);
            int rank = Field.getY((byte) index);
            if (white) {
                whiteFileCount[file]++;
            } else {
                blackFileCount[file]++;
            }
            if ((white && rank >= 5) || (!white && rank <= 2)) {
                advancedPawn = true;
            }
            if (isPassedPawn(board, (byte) index, white)) {
                passedPawn = true;
            }
            if (isIsolatedPawn(board, (byte) index, white)) {
                isolatedPawn = true;
            }
            if (isBackwardPawn(board, (byte) index, white)) {
                backwardPawn = true;
            }
        }

        for (int file = 0; file < 8; file++) {
            if (whiteFileCount[file] > 1 || blackFileCount[file] > 1) {
                doubledPawn = true;
                break;
            }
        }

        if (advancedPawn) {
            tags.add(PuzzleTheme.ADVANCED_PAWN);
        }
        if (passedPawn) {
            tags.add(PuzzleTheme.PASSED_PAWN);
        }
        if (isolatedPawn) {
            tags.add(PuzzleTheme.ISOLATED_PAWN);
        }
        if (doubledPawn) {
            tags.add(PuzzleTheme.DOUBLED_PAWN);
        }
        if (backwardPawn) {
            tags.add(PuzzleTheme.BACKWARD_PAWN);
        }
    }

    private static void applyTacticalTags(Position parent, Position position, byte[] parentBoard, byte[] board,
            boolean moverIsWhite, EnumSet<PuzzleTheme> tags) {
        short move = identifyMove(parent, position);
        boolean givesCheck = position.inCheck();
        boolean wasInCheck = parent.inCheck();
        if (move != Move.NO_MOVE) {
            applyMoveSpecificTags(parentBoard, move, tags);
            applyMoveTacticalThemes(parent, position, parentBoard, board, moverIsWhite, move, givesCheck, wasInCheck,
                    tags);
        }

        if (hasFork(board, moverIsWhite)) {
            tags.add(PuzzleTheme.FORK);
        }

        if (hasHangingPiece(board, !moverIsWhite)) {
            tags.add(PuzzleTheme.HANGING_PIECE);
        }

        if (isSacrifice(parent, position, moverIsWhite)) {
            tags.add(PuzzleTheme.SACRIFICE);
        }

        if (hasPin(position, board)) {
            tags.add(PuzzleTheme.PIN);
        }

        if (hasSkewer(board)) {
            tags.add(PuzzleTheme.SKEWER);
        }

        if (hasXRayAttack(board)) {
            tags.add(PuzzleTheme.X_RAY_ATTACK);
        }

        if (hasOverloadedDefender(board, moverIsWhite)) {
            tags.add(PuzzleTheme.OVERLOADING);
        }

        if (hasTrappedPiece(position, board)) {
            tags.add(PuzzleTheme.TRAPPED_PIECE);
        }
    }

    private static void applyStateTags(Position parent, Position position, boolean moverIsWhite,
            EnumSet<PuzzleTheme> tags) {
        MoveList legalMoves = position.getMoves();
        if (!position.inCheck() && legalMoves.size() == 0) {
            tags.add(PuzzleTheme.STALEMATE);
        }
        if (!position.inCheck() && legalMoves.size() <= 2 && position.countTotalPieces() <= 10) {
            tags.add(PuzzleTheme.ZUGZWANG);
        }
        if (isPerpetualCheckAttempt(position, moverIsWhite)) {
            tags.add(PuzzleTheme.PERPETUAL_CHECK);
        }
        if (position.getHalfMove() >= 90) {
            tags.add(PuzzleTheme.THREEFOLD);
        }
        if (position.getFullMove() >= 60 || position.getHalfMove() >= 80) {
            tags.add(PuzzleTheme.TIME_PRESSURE);
        }
        applyMateDistanceTags(position, tags);
        if (isStudyLike(position, tags)) {
            tags.add(PuzzleTheme.STUDY_LIKE);
        }
    }

    private static void applyMoveSpecificTags(byte[] parentBoard, short move, EnumSet<PuzzleTheme> tags) {
        byte from = Move.getFromIndex(move);
        byte to = Move.getToIndex(move);
        byte movedPiece = parentBoard[from];

        String notation = Move.toString(move);
        if (notation.length() == 5) {
            tags.add(PuzzleTheme.PROMOTION);
            if (notation.charAt(4) != 'q') {
                tags.add(PuzzleTheme.UNDERPROMOTION);
            }
        }

        if (Piece.isPawn(movedPiece)) {
            int rank = Field.getY(to);
            if ((Piece.isWhite(movedPiece) && rank >= 5) || (Piece.isBlack(movedPiece) && rank <= 2)) {
                tags.add(PuzzleTheme.ADVANCED_PAWN);
            }
        }
    }

    private static void applyMoveTacticalThemes(Position parent, Position position, byte[] parentBoard, byte[] board,
            boolean moverIsWhite, short move, boolean givesCheck, boolean wasInCheck, EnumSet<PuzzleTheme> tags) {
        byte from = Move.getFromIndex(move);
        byte to = Move.getToIndex(move);
        byte capturedPiece = parentBoard[to];
        boolean isCapture = capturedPiece != Piece.EMPTY;

        if (givesCheck && isDiscoveredCheck(board, parentBoard, moverIsWhite, from, to, position)) {
            tags.add(PuzzleTheme.DISCOVERED_CHECK);
        } else if (isDiscoveredAttack(board, parentBoard, moverIsWhite, from, to)) {
            tags.add(PuzzleTheme.DISCOVERED_ATTACK);
        }

        if (createsClearance(parentBoard, board, moverIsWhite, from)) {
            tags.add(PuzzleTheme.CLEARANCE);
        }

        if (createsInterference(position, parentBoard, moverIsWhite, to)) {
            tags.add(PuzzleTheme.INTERFERENCE);
        }

        if (detectDeflection(position, board, moverIsWhite, to, givesCheck)) {
            tags.add(PuzzleTheme.DEFLECTION);
        }

        if (detectDecoy(board, parentBoard, moverIsWhite, from, to, isCapture)) {
            tags.add(PuzzleTheme.DECOY);
        }

        if (detectAttraction(position, moverIsWhite, to, isCapture)) {
            tags.add(PuzzleTheme.ATTRACTION);
        }

        if (wasInCheck && givesCheck) {
            tags.add(PuzzleTheme.COUNTERATTACK);
        }

        if (isQuietMove(parent, position, moverIsWhite, isCapture, givesCheck)) {
            tags.add(PuzzleTheme.QUIET_MOVE);
        }
    }

    private static boolean isSacrifice(Position parent, Position position, boolean moverIsWhite) {
        int before = moverIsWhite ? parent.countWhiteMaterial() : parent.countBlackMaterial();
        int after = moverIsWhite ? position.countWhiteMaterial() : position.countBlackMaterial();
        int loss = before - after;
        if (loss >= Piece.VALUE_BISHOP) {
            return position.inCheck() || position.isMate();
        }
        return false;
    }

    private static boolean hasFork(byte[] board, boolean attackerIsWhite) {
        for (int index = 0; index < board.length; index++) {
            byte piece = board[index];
            if (piece == Piece.EMPTY || Piece.isWhite(piece) != attackerIsWhite) {
                continue;
            }
            List<Integer> attacks = enumerateAttacks(board, piece, (byte) index);
            int valuableTargets = 0;
            for (int square : attacks) {
                byte target = board[square];
                if (target != Piece.EMPTY && Piece.isWhite(target) != attackerIsWhite
                        && Math.abs(Piece.getValue(target)) >= Piece.VALUE_BISHOP) {
                    valuableTargets++;
                }
            }
            if (valuableTargets >= 2) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasHangingPiece(byte[] board, boolean targetIsWhite) {
        for (int index = 0; index < board.length; index++) {
            byte piece = board[index];
            if (piece == Piece.EMPTY || Piece.isWhite(piece) != targetIsWhite) {
                continue;
            }
            byte square = (byte) index;
            if (countAttackers(board, square, !targetIsWhite) > 0
                    && countAttackers(board, square, targetIsWhite) == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSmothered(byte[] board, byte kingSquare, boolean kingIsWhite) {
        for (int[] delta : KING_DELTAS) {
            int file = Field.getX(kingSquare) + delta[0];
            int rank = Field.getY(kingSquare) + delta[1];
            if (!isOnBoard(file, rank)) {
                continue;
            }
            byte occupant = board[toIndex(file, rank)];
            if (occupant == Piece.EMPTY || Piece.isWhite(occupant) != kingIsWhite) {
                return false;
            }
        }
        return true;
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
            if (fileContainsPawn(board, adjacent, white)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBackwardPawn(byte[] board, byte square, boolean white) {
        int file = Field.getX(square);
        int rank = Field.getY(square);
        int direction = white ? 1 : -1;
        int frontRank = rank + direction;

        // If the pawn can simply advance, it is not considered backward.
        if (isOnBoard(file, frontRank) && board[toIndex(file, frontRank)] == Piece.EMPTY
                && countAttackers(board, (byte) toIndex(file, frontRank), !white) == 0) {
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
        }

        if (supported) {
            return false;
        }

        if (!isOnBoard(file, frontRank)) {
            return false;
        }

        return countAttackers(board, (byte) toIndex(file, frontRank), !white) > 0;
    }

    private static boolean fileContainsPawn(byte[] board, int file, boolean white) {
        for (int rank = 0; rank < 8; rank++) {
            byte occupant = board[toIndex(file, rank)];
            if (occupant != Piece.EMPTY && Piece.isPawn(occupant) && Piece.isWhite(occupant) == white) {
                return true;
            }
        }
        return false;
    }

    private static short identifyMove(Position parent, Position position) {
        MoveList moves = parent.getMoves();
        for (int i = 0; i < moves.size(); i++) {
            short move = moves.get(i);
            Position candidate = parent.copyOf();
            candidate.play(move);
            if (candidate.equals(position)) {
                return move;
            }
        }
        return Move.NO_MOVE;
    }

    private static int countAttackers(byte[] board, byte targetSquare, boolean whiteAttackers) {
        int count = 0;
        for (int index = 0; index < board.length; index++) {
            byte piece = board[index];
            if (piece == Piece.EMPTY || Piece.isWhite(piece) != whiteAttackers) {
                continue;
            }
            if (attacksSquare(board, (byte) index, targetSquare, piece)) {
                count++;
            }
        }
        return count;
    }

    private static List<Integer> collectAttackers(byte[] board, byte targetSquare, boolean whiteAttackers) {
        List<Integer> attackers = new ArrayList<>();
        for (int index = 0; index < board.length; index++) {
            byte piece = board[index];
            if (piece == Piece.EMPTY || Piece.isWhite(piece) != whiteAttackers) {
                continue;
            }
            if (attacksSquare(board, (byte) index, targetSquare, piece)) {
                attackers.add(index);
            }
        }
        return attackers;
    }

    private static boolean attacksSquare(byte[] board, byte fromSquare, byte targetSquare, byte piece) {
        int file = Field.getX(fromSquare);
        int rank = Field.getY(fromSquare);
        int targetFile = Field.getX(targetSquare);
        int targetRank = Field.getY(targetSquare);
        int df = targetFile - file;
        int dr = targetRank - rank;
        byte absPiece = (byte) Math.abs(piece);

        return switch (absPiece) {
            case Piece.WHITE_PAWN -> {
                if (Piece.isWhite(piece)) {
                    yield dr == 1 && Math.abs(df) == 1;
                }
                yield dr == -1 && Math.abs(df) == 1;
            }
            case Piece.WHITE_KNIGHT -> Math.abs(df * df + dr * dr) == 5;
            case Piece.WHITE_BISHOP -> Math.abs(df) == Math.abs(dr)
                    && isPathClear(board, file, rank, targetFile, targetRank, Integer.signum(df), Integer.signum(dr));
            case Piece.WHITE_ROOK -> (df == 0 || dr == 0)
                    && isPathClear(board, file, rank, targetFile, targetRank, Integer.signum(df), Integer.signum(dr));
            case Piece.WHITE_QUEEN -> ((Math.abs(df) == Math.abs(dr))
                    || df == 0 || dr == 0)
                    && isPathClear(board, file, rank, targetFile, targetRank, Integer.signum(df), Integer.signum(dr));
            case Piece.WHITE_KING -> Math.max(Math.abs(df), Math.abs(dr)) == 1;
            default -> false;
        };
    }

    private static boolean isPathClear(byte[] board, int fromFile, int fromRank, int toFile, int toRank, int stepFile,
            int stepRank) {
        int file = fromFile + stepFile;
        int rank = fromRank + stepRank;
        while (file != toFile || rank != toRank) {
            if (!isOnBoard(file, rank)) {
                return false;
            }
            if (board[toIndex(file, rank)] != Piece.EMPTY) {
                return false;
            }
            file += stepFile;
            rank += stepRank;
        }
        return true;
    }

    private static List<Integer> enumerateAttacks(byte[] board, byte piece, byte square) {
        List<Integer> targets = new ArrayList<>();
        int file = Field.getX(square);
        int rank = Field.getY(square);
        byte absPiece = (byte) Math.abs(piece);

        switch (absPiece) {
            case Piece.WHITE_PAWN: {
                int direction = Piece.isWhite(piece) ? 1 : -1;
                addIfValid(targets, file - 1, rank + direction);
                addIfValid(targets, file + 1, rank + direction);
                break;
            }
            case Piece.WHITE_KNIGHT: {
                for (int[] delta : KNIGHT_DELTAS) {
                    addIfValid(targets, file + delta[0], rank + delta[1]);
                }
                break;
            }
            case Piece.WHITE_BISHOP: {
                addSlidingTargets(targets, board, file, rank, BISHOP_DIRECTIONS);
                break;
            }
            case Piece.WHITE_ROOK: {
                addSlidingTargets(targets, board, file, rank, ROOK_DIRECTIONS);
                break;
            }
            case Piece.WHITE_QUEEN: {
                addSlidingTargets(targets, board, file, rank, BISHOP_DIRECTIONS);
                addSlidingTargets(targets, board, file, rank, ROOK_DIRECTIONS);
                break;
            }
            case Piece.WHITE_KING: {
                for (int[] delta : KING_DELTAS) {
                    addIfValid(targets, file + delta[0], rank + delta[1]);
                }
                break;
            }
            default:
                break;
        }
        return targets;
    }

    private static void addSlidingTargets(List<Integer> targets, byte[] board, int file, int rank, int[][] directions) {
        for (int[] dir : directions) {
            int f = file + dir[0];
            int r = rank + dir[1];
            while (isOnBoard(f, r)) {
                int index = toIndex(f, r);
                targets.add(index);
                if (board[index] != Piece.EMPTY) {
                    break;
                }
                f += dir[0];
                r += dir[1];
            }
        }
    }

    private static void addIfValid(List<Integer> targets, int file, int rank) {
        if (isOnBoard(file, rank)) {
            targets.add(toIndex(file, rank));
        }
    }

    private static boolean isSlidingPiece(byte piece) {
        return Piece.isBishop(piece) || Piece.isRook(piece) || Piece.isQueen(piece);
    }

    private static boolean isValuablePiece(byte piece) {
        return Piece.getValue(piece) >= Piece.VALUE_ROOK || Piece.isKing(piece);
    }

    private static boolean liesOnLine(byte from, byte to, byte candidate) {
        int fromFile = Field.getX(from);
        int fromRank = Field.getY(from);
        int toFile = Field.getX(to);
        int toRank = Field.getY(to);
        int candFile = Field.getX(candidate);
        int candRank = Field.getY(candidate);

        int df = Integer.signum(toFile - fromFile);
        int dr = Integer.signum(toRank - fromRank);
        if (df == 0 && dr == 0) {
            return false;
        }
        if (df != 0 && dr != 0 && Math.abs(toFile - fromFile) != Math.abs(toRank - fromRank)) {
            return false;
        }
        int f = fromFile + df;
        int r = fromRank + dr;
        while (f != toFile || r != toRank) {
            if (f == candFile && r == candRank) {
                return true;
            }
            f += df;
            r += dr;
        }
        return false;
    }

    private static boolean isDiscoveredCheck(byte[] board, byte[] parentBoard, boolean moverIsWhite, byte from,
            byte to, Position position) {
        boolean defenderIsWhite = !moverIsWhite;
        byte kingSquare = defenderIsWhite ? position.getWhiteKing() : position.getBlackKing();
        List<Integer> attackers = collectAttackers(board, kingSquare, moverIsWhite);
        for (int attacker : attackers) {
            if (attacker == to) {
                continue;
            }
            byte piece = board[attacker];
            if (!isSlidingPiece(piece)) {
                continue;
            }
            if (liesOnLine((byte) attacker, kingSquare, from)
                    && parentBoard[from] != Piece.EMPTY && Piece.isWhite(parentBoard[from]) == moverIsWhite) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDiscoveredAttack(byte[] board, byte[] parentBoard, boolean moverIsWhite, byte from,
            byte to) {
        if (parentBoard[from] == Piece.EMPTY) {
            return false;
        }
        for (int attacker = 0; attacker < board.length; attacker++) {
            byte piece = board[attacker];
            if (piece == Piece.EMPTY || Piece.isWhite(piece) != moverIsWhite || !isSlidingPiece(piece)) {
                continue;
            }
            for (int target = 0; target < board.length; target++) {
                byte targetPiece = board[target];
                if (targetPiece == Piece.EMPTY || Piece.isWhite(targetPiece) == moverIsWhite) {
                    continue;
                }
                if (!isValuablePiece(targetPiece)) {
                    continue;
                }
                if (liesOnLine((byte) attacker, (byte) target, from)) {
                    int stepFile = Integer.signum(Field.getX((byte) target) - Field.getX((byte) attacker));
                    int stepRank = Integer.signum(Field.getY((byte) target) - Field.getY((byte) attacker));
                    if (isPathClear(board, Field.getX((byte) attacker), Field.getY((byte) attacker),
                            Field.getX((byte) target), Field.getY((byte) target), stepFile, stepRank)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean createsClearance(byte[] parentBoard, byte[] board, boolean moverIsWhite, byte from) {
        if (parentBoard[from] == Piece.EMPTY) {
            return false;
        }
        for (int slider = 0; slider < board.length; slider++) {
            byte piece = board[slider];
            if (piece == Piece.EMPTY || Piece.isWhite(piece) != moverIsWhite || !isSlidingPiece(piece)) {
                continue;
            }
            for (int target = 0; target < board.length; target++) {
                byte targetPiece = board[target];
                if (targetPiece == Piece.EMPTY || Piece.isWhite(targetPiece) == moverIsWhite) {
                    continue;
                }
                if (!isValuablePiece(targetPiece) && !Piece.isKing(targetPiece)) {
                    continue;
                }
                if (!liesOnLine((byte) slider, (byte) target, from)) {
                    continue;
                }
                int stepFile = Integer.signum(Field.getX((byte) target) - Field.getX((byte) slider));
                int stepRank = Integer.signum(Field.getY((byte) target) - Field.getY((byte) slider));
                if (isPathClear(board, Field.getX((byte) slider), Field.getY((byte) slider),
                        Field.getX((byte) target), Field.getY((byte) target), stepFile, stepRank)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean createsInterference(Position position, byte[] parentBoard, boolean moverIsWhite, byte to) {
        boolean defenderIsWhite = !moverIsWhite;
        byte kingSquare = defenderIsWhite ? position.getWhiteKing() : position.getBlackKing();
        for (int index = 0; index < parentBoard.length; index++) {
            byte piece = parentBoard[index];
            if (piece == Piece.EMPTY || Piece.isWhite(piece) != defenderIsWhite || !isSlidingPiece(piece)) {
                continue;
            }
            if (liesOnLine((byte) index, kingSquare, to)) {
                int stepFile = Integer.signum(Field.getX(kingSquare) - Field.getX((byte) index));
                int stepRank = Integer.signum(Field.getY(kingSquare) - Field.getY((byte) index));
                if (isPathClear(parentBoard, Field.getX((byte) index), Field.getY((byte) index),
                        Field.getX(kingSquare), Field.getY(kingSquare), stepFile, stepRank)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean detectDeflection(Position position, byte[] board, boolean moverIsWhite, byte attackerSquare,
            boolean givesCheck) {
        if (!givesCheck) {
            return false;
        }
        for (int target : enumerateAttacks(board, board[attackerSquare], attackerSquare)) {
            byte targetPiece = board[target];
            if (targetPiece != Piece.EMPTY && Piece.isWhite(targetPiece) != moverIsWhite && isValuablePiece(targetPiece)) {
                return true;
            }
        }
        return false;
    }

    private static boolean detectDecoy(byte[] board, byte[] parentBoard, boolean moverIsWhite, byte from, byte to,
            boolean isCapture) {
        byte movedPieceBefore = parentBoard[from];
        if (movedPieceBefore == Piece.EMPTY) {
            return false;
        }
        boolean defended = countAttackers(board, to, !moverIsWhite) > 0;
        boolean lightlyDefended = countAttackers(board, to, !moverIsWhite) > countAttackers(board, to, moverIsWhite);
        return defended && lightlyDefended && (isCapture || Piece.getValue(movedPieceBefore) <= Piece.VALUE_ROOK);
    }

    private static boolean detectAttraction(Position position, boolean moverIsWhite, byte to, boolean isCapture) {
        boolean defenderIsWhite = !moverIsWhite;
        byte kingSquare = defenderIsWhite ? position.getWhiteKing() : position.getBlackKing();
        int dx = Math.abs(Field.getX(to) - Field.getX(kingSquare));
        int dy = Math.abs(Field.getY(to) - Field.getY(kingSquare));
        return Math.max(dx, dy) == 1 && (isCapture || position.inCheck());
    }

    private static boolean isQuietMove(Position parent, Position position, boolean moverIsWhite, boolean isCapture,
            boolean givesCheck) {
        if (isCapture || givesCheck) {
            return false;
        }
        int before = moverIsWhite ? parent.countWhiteMaterial() : parent.countBlackMaterial();
        int after = moverIsWhite ? position.countWhiteMaterial() : position.countBlackMaterial();
        return after > before || position.isMate();
    }

    private static boolean hasPin(Position position, byte[] board) {
        return hasPinnedKingLine(board, position.getWhiteKing(), true)
                || hasPinnedKingLine(board, position.getBlackKing(), false);
    }

    private static boolean hasPinnedKingLine(byte[] board, byte kingSquare, boolean kingIsWhite) {
        if (kingSquare == Field.NO_SQUARE) {
            return false;
        }
        int[][] directions = {
                { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 },
                { 1, 1 }, { 1, -1 }, { -1, 1 }, { -1, -1 }
        };
        for (int[] dir : directions) {
            int f = Field.getX(kingSquare) + dir[0];
            int r = Field.getY(kingSquare) + dir[1];
            int firstIndex = -1;
            while (isOnBoard(f, r)) {
                int index = toIndex(f, r);
                byte occupant = board[index];
                if (occupant != Piece.EMPTY) {
                    if (Piece.isWhite(occupant) == kingIsWhite && firstIndex == -1) {
                        firstIndex = index;
                    } else if (Piece.isWhite(occupant) != kingIsWhite && firstIndex != -1
                            && isSlidingInDirection(occupant, dir)) {
                        return true;
                    } else {
                        break;
                    }
                }
                f += dir[0];
                r += dir[1];
            }
        }
        return false;
    }

    private static boolean isSlidingInDirection(byte piece, int[] dir) {
        boolean diagonal = Math.abs(dir[0]) == Math.abs(dir[1]);
        if (diagonal) {
            return Piece.isBishop(piece) || Piece.isQueen(piece);
        }
        return Piece.isRook(piece) || Piece.isQueen(piece);
    }

    private static boolean hasSkewer(byte[] board) {
        for (int index = 0; index < board.length; index++) {
            byte piece = board[index];
            if (piece == Piece.EMPTY || !isSlidingPiece(piece)) {
                continue;
            }
            int[][] directions = Piece.isBishop(piece) ? BISHOP_DIRECTIONS
                    : Piece.isRook(piece) ? ROOK_DIRECTIONS : new int[][] { { 1, 1 }, { 1, -1 }, { -1, 1 }, { -1, -1 },
                            { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
            for (int[] dir : directions) {
                byte firstVictim = Piece.EMPTY;
                byte secondVictim = Piece.EMPTY;
                int f = Field.getX((byte) index) + dir[0];
                int r = Field.getY((byte) index) + dir[1];
                while (isOnBoard(f, r)) {
                    byte occupant = board[toIndex(f, r)];
                    if (occupant != Piece.EMPTY) {
                        if (Piece.isWhite(occupant) == Piece.isWhite(piece)) {
                            break;
                        }
                        if (firstVictim == Piece.EMPTY) {
                            firstVictim = occupant;
                        } else {
                            secondVictim = occupant;
                            break;
                        }
                    }
                    f += dir[0];
                    r += dir[1];
                }
                if (firstVictim != Piece.EMPTY && secondVictim != Piece.EMPTY
                        && Piece.getValue(firstVictim) >= Piece.getValue(secondVictim)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasXRayAttack(byte[] board) {
        for (int index = 0; index < board.length; index++) {
            byte piece = board[index];
            if (piece == Piece.EMPTY || !isSlidingPiece(piece)) {
                continue;
            }
            int[][] directions = Piece.isBishop(piece) ? BISHOP_DIRECTIONS
                    : Piece.isRook(piece) ? ROOK_DIRECTIONS : new int[][] { { 1, 1 }, { 1, -1 }, { -1, 1 }, { -1, -1 },
                            { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
            for (int[] dir : directions) {
                byte firstVictim = Piece.EMPTY;
                int f = Field.getX((byte) index) + dir[0];
                int r = Field.getY((byte) index) + dir[1];
                while (isOnBoard(f, r)) {
                    byte occupant = board[toIndex(f, r)];
                    if (occupant != Piece.EMPTY) {
                        if (Piece.isWhite(occupant) == Piece.isWhite(piece)) {
                            break;
                        }
                        if (firstVictim == Piece.EMPTY) {
                            firstVictim = occupant;
                        } else {
                            return true;
                        }
                    }
                    f += dir[0];
                    r += dir[1];
                }
            }
        }
        return false;
    }

    private static boolean hasOverloadedDefender(byte[] board, boolean attackerIsWhite) {
        boolean defenderIsWhite = !attackerIsWhite;
        for (int index = 0; index < board.length; index++) {
            byte defender = board[index];
            if (defender == Piece.EMPTY || Piece.isWhite(defender) != defenderIsWhite) {
                continue;
            }
            int defendedCritical = 0;
            for (int target = 0; target < board.length; target++) {
                byte ally = board[target];
                if (ally == Piece.EMPTY || Piece.isWhite(ally) != defenderIsWhite) {
                    continue;
                }
                if (!isValuablePiece(ally) && !Piece.isKing(ally)) {
                    continue;
                }
                if (attacksSquare(board, (byte) index, (byte) target, defender)
                        && countAttackers(board, (byte) target, defenderIsWhite) == 1
                        && countAttackers(board, (byte) target, attackerIsWhite) > 0) {
                    defendedCritical++;
                }
            }
            if (defendedCritical >= 2 && countAttackers(board, (byte) index, attackerIsWhite) > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTrappedPiece(Position position, byte[] board) {
        MoveList legal = position.getMoves();
        boolean[] pieceHasMove = new boolean[board.length];
        for (int i = 0; i < legal.size(); i++) {
            short move = legal.get(i);
            byte from = Move.getFromIndex(move);
            pieceHasMove[from] = true;
        }
        boolean defenderIsWhite = position.isWhiteTurn();
        for (int index = 0; index < board.length; index++) {
            byte piece = board[index];
            if (piece == Piece.EMPTY || Piece.isWhite(piece) != defenderIsWhite) {
                continue;
            }
            if (Piece.getValue(piece) < Piece.VALUE_BISHOP) {
                continue;
            }
            if (!pieceHasMove[index] && countAttackers(board, (byte) index, !defenderIsWhite) > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPerpetualCheckAttempt(Position position, boolean moverIsWhite) {
        if (!position.inCheck()) {
            return false;
        }
        int balance = position.materialDiscrepancy();
        if (moverIsWhite) {
            return balance < -200;
        }
        return balance > 200;
    }

    private static final int MATE_SEARCH_NODE_LIMIT = 4000;

    private static void applyMateDistanceTags(Position position, EnumSet<PuzzleTheme> tags) {
        if (position.isMate() || position.countTotalPieces() > 18) {
            return;
        }
        boolean heroIsWhite = position.isWhiteTurn();
        int[] budget = new int[] { MATE_SEARCH_NODE_LIMIT };
        if (hasForcedMate(position, 2, heroIsWhite, budget)) {
            tags.add(PuzzleTheme.MATE_IN_TWO);
        } else if (hasForcedMate(position, 3, heroIsWhite, budget)) {
            tags.add(PuzzleTheme.MATE_IN_THREE);
        }
    }

    private static boolean hasForcedMate(Position position, int movesLeft, boolean heroIsWhite, int[] budget) {
        if (budget[0] <= 0) {
            return false;
        }
        budget[0]--;
        if (position.isMate()) {
            return position.isWhiteTurn() != heroIsWhite;
        }
        if (movesLeft == 0) {
            return false;
        }
        MoveList moves = position.getMoves();
        if (moves.size() == 0) {
            return false;
        }
        boolean heroToMove = position.isWhiteTurn() == heroIsWhite;
        if (heroToMove) {
            for (int i = 0; i < moves.size(); i++) {
                short move = moves.get(i);
                Position next = position.copyOf();
                next.play(move);
                if (next.isMate() || hasForcedMate(next, movesLeft - 1, heroIsWhite, budget)) {
                    return true;
                }
            }
            return false;
        }
        for (int i = 0; i < moves.size(); i++) {
            short move = moves.get(i);
            Position next = position.copyOf();
            next.play(move);
            if (!hasForcedMate(next, movesLeft, heroIsWhite, budget)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isStudyLike(Position position, EnumSet<PuzzleTheme> tags) {
        if (position.countTotalPieces() > 7) {
            return false;
        }
        return tags.contains(PuzzleTheme.UNDERPROMOTION)
                || tags.contains(PuzzleTheme.STALEMATE)
                || tags.contains(PuzzleTheme.ZUGZWANG)
                || tags.contains(PuzzleTheme.MATE_IN_THREE);
    }

    private static boolean isOnBoard(int file, int rank) {
        return file >= 0 && file < 8 && rank >= 0 && rank < 8;
    }

    private static int toIndex(int file, int rank) {
        return (7 - rank) * 8 + file;
    }
}
