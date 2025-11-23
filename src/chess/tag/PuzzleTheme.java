package chess.tag;

/**
 * Canonical list of puzzle/position tags inspired by major tactic platforms.
 *
 * <p>
 * The names draw inspiration from:
 * </p>
 * <ul>
 * <li><strong>Lichess</strong> puzzle themes (see
 * https://lichess.org/api#tag/Tag)</li>
 * <li><strong>Chess.com</strong> puzzle themes (see
 * https://www.chess.com/article/view/chess-puzzles-theme)</li>
 * <li><strong>ChessTempo</strong> tactical motifs (see
 * https://chesstempo.com/manual/en/manual.html#tacticTags)</li>
 * </ul>
 *
 * <p>
 * The {@link #getCanonicalName()} string mirrors the lower-case identifiers used
 * by these platforms so downstream consumers can export/import metadata without
 * extra mapping tables.
 * </p>
 * 
 * @since 2025
 * @author Lennart A. Conrad
 */
public enum PuzzleTheme {

    MATE_IN_ONE("mateIn1", "Mate in one."),
    MATE_IN_TWO("mateIn2", "Mate in two theme."),
    MATE_IN_THREE("mateIn3", "Mate in three theme."),
    CHECKMATE("mate", "Generic mate finisher."),
    BACK_RANK_MATE("backRankMate", "Back rank mate corridor theme."),
    SMOTHERED_MATE("smotheredMate", "Smothered mate with a knight delivering the final blow."),
    DOUBLE_CHECK("doubleCheck", "Double-check motif."),
    CHECK("check", "Any checking move; \"Checking\"."),
    EXPOSED_KING("exposedKing", "Exposed king theme."),

    FORK("fork", "Multiple threats from one piece (fork)."),
    PIN("pin", "Pinned piece motif."),
    SKEWER("skewer", "Skewer tactic forcing higher-value piece to move."),
    DISCOVERED_ATTACK("discoveredAttack", "Discovered attack/double attack."),
    DISCOVERED_CHECK("discoveredCheck", "Discovered check motif."),
    DECOY("decoy", "Forcing an enemy piece onto a bad square."),
    DEFLECTION("deflection", "Drag a defender away."),
    CLEARANCE("clearance", "Vacating a line or square (clearance)."),
    INTERFERENCE("interference", "Blocking a defender's line."),
    X_RAY_ATTACK("xRayAttack", "X-ray tactic / hidden attack."),
    OVERLOADING("overload", "Overloaded defender theme."),
    ATTRACTION("attraction", "Attracting the king/piece to a square."),
    HANGING_PIECE("hangingPiece", "Loose/hanging piece tactic."),
    SACRIFICE("sacrifice", "Sacrificial motif leading to initiative."),
    QUIET_MOVE("quietMove", "Precise quiet move finishes."),
    COUNTERATTACK("counterAttack", "Counterattack motif (turning tables)."),
    TRAPPED_PIECE("trappedPiece", "Winning material by trapping a piece."),
    ZUGZWANG("zugzwang", "Zugzwang positions."),
    STALEMATE("stalemate", "Forcing stalemate as resource."),

    ADVANCED_PAWN("advancedPawn", "Advanced/passed pawn on 6th/7th ranks."),
    PASSED_PAWN("passedPawn", "Passed pawn conversion theme."),
    ISOLATED_PAWN("isolatedPawn", "Isolated pawn weaknesses."),
    DOUBLED_PAWN("doubledPawn", "Doubled pawn formation theme."),
    BACKWARD_PAWN("backwardPawn", "Backward pawn weaknesses."),
    PROMOTION("promotion", "Promotion combinations."),
    UNDERPROMOTION("underPromotion", "Underpromotion motifs."),

    WHITE_ADVANTAGE("whiteAdvantage", "White holds the advantage after the tactic."),
    BLACK_ADVANTAGE("blackAdvantage", "Black holds the advantage after the tactic."),
    WHITE_CRUSHING("whiteCrushing", "White is winning decisively."),
    BLACK_CRUSHING("blackCrushing", "Black is winning decisively."),
    EQUALITY("equal", "Resulting position remains roughly equal."),
    MATERIAL_IMBALANCE("materialImbalance", "Material imbalance theme."),

    OPENING("opening", "Position belongs to the opening phase."),
    MIDDLEGAME("middlegame", "Middlegame struggle."),
    ENDGAME("endgame", "Endgame themes."),
    PERPETUAL_CHECK("perpetualCheck", "Perpetual check drawing resource."),
    THREEFOLD("threefold", "Threefold repetition resource."),
    TIME_PRESSURE("timePressure", "Time scramble ideas (informational)."),
    STUDY_LIKE("studyLike", "Study-like composition motif.");

    private final String canonicalName;
    private final String description;

    PuzzleTheme(String canonicalName, String description) {
        this.canonicalName = canonicalName;
        this.description = description;
    }

    /**
     * Returns the canonical string identifier (matching the lower-case naming used
     * by major puzzle platforms).
     *
     * @return canonical lower-case tag identifier
     */
    public String getCanonicalName() {
        return canonicalName;
    }

    /**
     * Returns a short explanation describing how platforms use this tag.
     *
     * @return human readable description
     */
    public String getDescription() {
        return description;
    }
}
