package chess.tag;

import chess.core.Position;

/**
 * Console harness for {@link PawnStructureAnalyzer}. Pass a FEN as the first
 * argument to inspect that position, otherwise a sample Caro-Kann structure is
 * analyzed. The output lists pawn strengths and weaknesses for both sides.
 */
public final class PawnStructureAnalyzerTest {

    private PawnStructureAnalyzerTest() {
        // utility
    }

    public static void main(String[] args) {
        String fen = args.length == 0 ? "rnbqkbnr/pp1ppppp/8/2p5/3P4/2N5/PPP1PPPP/R1BQKBNR w KQkq - 0 3" : args[0];
        Position position = new Position(fen);

        System.out.println("Analyzing pawn structure for: " + fen);
        PawnStructureAnalyzer.PawnStructureReport report = PawnStructureAnalyzer.analyze(position);

        printSide("White", report.getWhite());
        printSide("Black", report.getBlack());
    }

    private static void printSide(String label, PawnStructureAnalyzer.SideReport report) {
        System.out.println(label + " pawns (" + report.getPawnCount() + "):");
        if (report.getStrengths().isEmpty()) {
            System.out.println("  Strengths: none detected");
        } else {
            System.out.println("  Strengths:");
            report.getStrengths().forEach(feature -> System.out.println("   + " + feature.getDescription()));
        }

        if (report.getWeaknesses().isEmpty()) {
            System.out.println("  Weaknesses: none detected");
        } else {
            System.out.println("  Weaknesses:");
            report.getWeaknesses().forEach(feature -> System.out.println("   - " + feature.getDescription()));
        }
        System.out.println();
    }
}
