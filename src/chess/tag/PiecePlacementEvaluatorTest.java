package chess.tag;

import chess.core.Position;

/**
 * Simple console harness for {@link PiecePlacementEvaluator}. Pass a FEN as the
 * first argument to inspect that position, otherwise the regular starting
 * position is evaluated. The output lists individual piece insights and prints
 * an 8x8 matrix so you can eyeball how well each piece is placed.
 */
public final class PiecePlacementEvaluatorTest {

    private PiecePlacementEvaluatorTest() {
        // utility
    }

    public static void main(String[] args) {
        String fen = args.length == 0 ? "8/p1p3Q1/1p4r1/5qk1/5pp1/P7/1P5R/K7 w - - 0 1" : args[0];
        Position position = new Position(fen);

        System.out.println("Evaluating: " + fen);
        PiecePlacementEvaluator.PlacementSummary summary = PiecePlacementEvaluator.summarize(position);

        summary.getInsights()
                .forEach(System.out::println);

        String mover = summary.isWhiteToMove() ? "White" : "Black";
        System.out.printf("Overall placement for %s to move: %+d (White total=%d, Black total=%d)%n", mover,
                summary.getSideToMoveScore(), summary.getWhiteScore(), summary.getBlackScore());

        System.out.println();
        printHeatmap(position);
    }

    private static void printHeatmap(Position position) {
        int[][] heatmap = PiecePlacementEvaluator.buildHeatmap(position);
        System.out.println("Placement heatmap (White at bottom):");
        for (int rank = 7; rank >= 0; rank--) {
            System.out.print((rank + 1) + " | ");
            for (int file = 0; file < 8; file++) {
                System.out.printf("%4d", heatmap[rank][file]);
            }
            System.out.println();
        }
        System.out.println("    ---------------------------------");
        System.out.println("      a   b   c   d   e   f   g   h");

        System.out.println();
        System.out.println("Legend: positive = good placement, negative = poor.");
        System.out.println("Squares correspond to FEN coordinates (a1 bottom left).");
    }
}
