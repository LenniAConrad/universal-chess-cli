package chess.mlp;

import java.util.Objects;

import chess.core.Field;
import chess.core.Piece;
import chess.core.Position;

/**
 * Encode a {@link Position} into the 781-float feature vector used by training.
 *
 * Order:
 * - Board: 64 squares, A8 -> H1, each with 12 channels (white P,N,B,R,Q,K then black p,n,b,r,q,k).
 *   Exactly one channel is 1.0 where a piece sits, else 0.0.
 * - Castling rights: White K, White Q, Black K, Black Q (1.0 if right exists).
 * - En-passant file: 8 bits a-h (one-hot if ep target exists, else all 0).
 * - Side to move: +1.0 for white, -1.0 for black.
 *
 * Board indexing follows {@link chess.core.Field}: A8 = 0, H1 = 63.
 *
 * This encoder is what {@link chess.mlp.Evaluator} expects; its output aligns with the
 * Python dual-head MLP training pipeline and the weights exported to {@code models/mlp_wide.bin}.
 * 
 * @author Lennart A. Conrad
 */
public final class Encoder {
    
    /**
     * Length of the flattened feature vector consumed by the MLP (board + extras).
     */
    public static final int FEATURE_LENGTH = 781;

    /**
     * Utility class; not instantiable.
     */
    private Encoder() {}

    /**
     * Map an internal piece code to its channel index (0-11) or -1 if empty/unknown.
     *
     * @param piece encoded piece constant
     * @return channel index or -1 if no channel applies
     */
    public static int Channel(byte piece) {
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

    /**
     * Encode a {@link Position} into the 781-element float array expected by training and
     * {@link chess.mlp.Evaluator}.
     *
     * @param pos position to encode (required)
     * @return 781-length feature vector
     */
    public static float[] encode(Position pos) {
        Objects.requireNonNull(pos, "pos");
        float[] f = new float[FEATURE_LENGTH];

        byte[] board = pos.getBoard();
        for (int sq = 0; sq < 64; sq++) {
            int ch = Channel(board[sq]);
            if (ch >= 0) {
                f[sq * 12 + ch] = 1.0f;
            }
        }

        int idx = 64 * 12;
        f[idx++] = pos.getWhiteKingside() != Field.NO_SQUARE ? 1.0f : 0.0f;
        f[idx++] = pos.getWhiteQueenside() != Field.NO_SQUARE ? 1.0f : 0.0f;
        f[idx++] = pos.getBlackKingside() != Field.NO_SQUARE ? 1.0f : 0.0f;
        f[idx++] = pos.getBlackQueenside() != Field.NO_SQUARE ? 1.0f : 0.0f;

        byte ep = pos.getEnPassant();
        if (ep != Field.NO_SQUARE) {
            int file = Field.getX(ep);
            if (file >= 0 && file < 8) {
                f[idx + file] = 1.0f;
            }
        }
        idx += 8;

        f[idx] = pos.isWhiteTurn() ? 1.0f : -1.0f;
        return f;
    }
}
