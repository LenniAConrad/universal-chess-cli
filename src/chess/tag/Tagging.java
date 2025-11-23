package chess.tag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import chess.core.Position;
import chess.eco.Encyclopedia;
import chess.eco.Entry;

/**
 * Used for tagging {@code Position} with standard chess tags.
 *
 * <p>
 * Thin facade over {@link PositionTagExtractor}, which uses heuristics inspired
 * by Chess.com, Lichess, and ChessTempo puzzle themes. When an ECO book is
 * available, ECO code/name tags are added as well.
 * 
 * @since 2025
 * @author Lennart A. Conrad
 */
public class Tagging {

    // Prevent instantiation
    private Tagging() {
        // non-instantiable
    }

    /**
     * Used for generating standard tags for a given position.
     * 
     * @param parent   the position before the move (optional; required for tactical
     *                 tags)
     * @param position the position to generate tags for
     * @return an array of standard tags
     */
    public static String[] positionalTags(Position parent, Position position) {
        if (position == null) {
            return new String[0];
        }

        // Some sources (e.g., FEN seed lists) do not provide a parent position. In
        // that case, fall back to using the current position for structural tagging
        // so callers still get phase/material/opening tags instead of an empty set.
        Position contextParent = parent != null ? parent : position;

        EnumSet<PuzzleTheme> themes = PositionTagExtractor.extract(contextParent, position);
        List<String> tags = new ArrayList<>(themes.size() + 2);
        for (PuzzleTheme theme : themes) {
            tags.add(theme.getCanonicalName());
        }

        // Ensure every record receives at least one tag so downstream consumers
        // never see an empty tag array (which currently happens when the
        // heuristic extractors find nothing). This prevents mined/analysed
        // puzzles from being stored as untagged.
        if (tags.isEmpty()) {
            tags.add("untagged");
        }

        addEcoTags(position, tags);

        Collections.sort(tags);
        return tags.toArray(String[]::new);
    }

    private static void addEcoTags(Position position, List<String> tags) {
        Entry entry = safeEcoEntry(position);
        if (entry == null) {
            return;
        }

        String ecoCode = entry.getECO();
        if (ecoCode != null && !ecoCode.isEmpty()) {
            tags.add("eco:" + ecoCode);
        }

        String openingName = entry.getName();
        if (openingName != null && !openingName.isEmpty()) {
            tags.add("opening:" + openingName);
        }
    }

    private static Entry safeEcoEntry(Position position) {
        try {
            return Encyclopedia.node(position);
        } catch (IllegalStateException ex) {
            // Optional ECO book missing or unreadable; skip opening tags gracefully.
            return null;
        }
    }
}
