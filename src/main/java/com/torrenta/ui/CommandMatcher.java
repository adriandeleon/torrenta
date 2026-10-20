package com.torrenta.ui;

import java.util.Locale;

/**
 * How a typed query is matched against a command name, and how well.
 *
 * <p>Subsequence matching, not substring: the point of a palette is that "ct" finds "Close Tab"
 * without typing either word. That alone matches far too much, though — "ct" is a subsequence of
 * almost every multi-word label — so the score is what does the real work, and the score is the part
 * that is easy to get subtly wrong. Hence pure, and tested.
 *
 * <p>Three things earn points, in the order a person would rank them: a character that begins a
 * word, a character that continues the previous match, and a match that starts near the front of
 * the label. Nothing here is novel; it is the shape every fuzzy finder converges on.
 */
final class CommandMatcher {

    /** Returned when the query is not a subsequence of the label at all. */
    static final int NO_MATCH = -1;

    private static final int WORD_START_BONUS = 10;

    /**
     * Worth more than a word start, deliberately. A run of characters is much stronger evidence
     * than the same number of scattered initials — with it the other way round, "tab" preferred
     * "Terminal About Box" (three word starts) to "Close Tab" (a literal substring).
     */
    private static final int CONSECUTIVE_BONUS = 14;

    private static final int LEADING_PENALTY = 1;

    /** Charged per character skipped between matches, so a tight match beats a sprawling one. */
    private static final int GAP_PENALTY = 1;

    private CommandMatcher() {}

    /**
     * @return a score, higher being better, or {@link #NO_MATCH}. An empty query matches everything
     *     with an equal score, which leaves the caller's own order intact — for a palette that is
     *     the menu order, which is the order the user already knows.
     */
    static int score(String query, String label) {
        if (label == null) return NO_MATCH;
        if (query == null || query.isBlank()) return 0;

        String q = query.toLowerCase(Locale.ROOT).replace(" ", "");
        String text = label.toLowerCase(Locale.ROOT);
        if (q.length() > text.length()) return NO_MATCH;

        // The best placement of the query over the label, not the first one found. Taking the
        // leftmost match for each character is the obvious implementation and it is wrong in a way
        // that shows: "nw" against "New Window" seizes the w of "New", never reaches the one that
        // starts "Window", and scores the same as "New Tab". Every placement is considered instead
        // and the best kept, which at these lengths — a few characters over a menu label — is far
        // too cheap to be worth approximating.
        int[][] memo = new int[q.length()][text.length()];
        for (int[] row : memo) java.util.Arrays.fill(row, Integer.MIN_VALUE);
        int best = best(q, text, 0, 0, memo);
        return best == Integer.MIN_VALUE ? NO_MATCH : best;
    }

    /**
     * Best score for placing {@code q[i..]} within {@code text} at or after {@code from}.
     *
     * @return {@link Integer#MIN_VALUE} when the rest of the query does not fit
     */
    private static int best(String q, String text, int i, int from, int[][] memo) {
        if (i == q.length()) return 0;
        if (from >= text.length()) return Integer.MIN_VALUE;
        if (memo[i][from] != Integer.MIN_VALUE) return memo[i][from];

        int result = Integer.MIN_VALUE;
        for (int at = from; at < text.length(); at++) {
            if (text.charAt(at) != q.charAt(i)) continue;

            int here = 0;
            if (at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1))) {
                here += WORD_START_BONUS;
            }
            if (i > 0) {
                // from is one past the previous match, so at - from is the gap. Zero means a run,
                // which reads as a real prefix; anything else is distance, and distance is what
                // separates "New Tab" from "Next Tab" when both offer the same two word starts.
                int gap = at - from;
                here += gap == 0 ? CONSECUTIVE_BONUS : -gap * GAP_PENALTY;
            }
            if (i == 0) {
                // Only the first character pays for its distance from the front. Charging every one
                // would punish a long label for being long, which is not what anybody means.
                here -= Math.min(at, 10) * LEADING_PENALTY;
            }

            int rest = best(q, text, i + 1, at + 1, memo);
            if (rest != Integer.MIN_VALUE) result = Math.max(result, here + rest);
        }
        memo[i][from] = result;
        return result;
    }
}
