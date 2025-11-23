package chess.tag;

import java.util.Objects;

/**
 * Computes the Levenshtein edit distance between two strings.
 *
 * <p>
 * The distance expresses how many single-character edits (insertions, deletions
 * or substitutions) are required to transform one string into the other. This
 * is useful for gauging how "unique" two chess position encodings (FEN, PGN
 * snippets, move sequences) are compared to each other.
 * </p>
 * 
 * @since 2025
 * @author Lennart A. Conrad
 */
public final class StringDistance {

    private StringDistance() {
        // utility class
    }

    /**
     * Calculates the Levenshtein distance between {@code left} and {@code right}
     * using a memory-friendly dynamic programming routine.
     *
     * <p>
     * Runs in {@code O(m*n)} time with {@code O(min(m,n))} memory, where
     * {@code m} and {@code n} are the input lengths.
     * </p>
     *
     * @param left  first string to compare
     * @param right second string to compare
     * @return number of single-character edits needed to transform {@code left}
     *         into {@code right}
     * @throws NullPointerException if either argument is {@code null}
     */
    public static int levenshtein(String left, String right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");

        int leftLength = left.length();
        int rightLength = right.length();

        if (leftLength == 0) {
            return rightLength;
        }
        if (rightLength == 0) {
            return leftLength;
        }

        // Ensure right is the shorter string to reduce memory consumption.
        if (leftLength < rightLength) {
            String tmpStr = left;
            left = right;
            right = tmpStr;
            leftLength = left.length();
            rightLength = right.length();
        }

        int[] previous = new int[rightLength + 1];
        int[] current = new int[rightLength + 1];

        for (int j = 0; j <= rightLength; j++) {
            previous[j] = j;
        }

        for (int i = 1; i <= leftLength; i++) {
            current[0] = i;
            char leftChar = left.charAt(i - 1);

            for (int j = 1; j <= rightLength; j++) {
                int cost = leftChar == right.charAt(j - 1) ? 0 : 1;
                int deletion = previous[j] + 1;
                int insertion = current[j - 1] + 1;
                int substitution = previous[j - 1] + cost;

                int best = Math.min(deletion, insertion);
                current[j] = Math.min(substitution, best);
            }

            int[] temp = previous;
            previous = current;
            current = temp;
        }

        return previous[rightLength];
    }

    /**
     * Returns the Levenshtein distance normalized to {@code [0,1]} by dividing by
     * the longer input length. A value of {@code 0} means both strings are
     * identical.
     *
     * @param left  first string to compare
     * @param right second string to compare
     * @return normalized distance in {@code [0,1]}
     */
    public static double normalizedLevenshtein(String left, String right) {
        int maxLen = Math.max(
                Objects.requireNonNull(left, "left").length(),
                Objects.requireNonNull(right, "right").length());
        if (maxLen == 0) {
            return 0.0d;
        }
        return (double) levenshtein(left, right) / maxLen;
    }
}
