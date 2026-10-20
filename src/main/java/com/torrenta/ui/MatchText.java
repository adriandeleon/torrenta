package com.torrenta.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.text.Text;

/**
 * A palette row's title as styled runs: the characters the query matched wear
 * {@value #MATCHED} (accent, bold: the kit's {@code <mark>}), the rest {@value #PLAIN}. The runs
 * come from the matcher's own placement, so the highlight and the ranking always agree. Editora's.
 */
final class MatchText {

    static final String MATCHED = "palette-match";
    static final String PLAIN = "palette-cell-text";

    private MatchText() {}

    /** Nothing is highlighted for a blank query, which is exactly the "no filter typed yet" state. */
    static List<Text> runs(String text, String query) {
        String s = text == null ? "" : text;
        List<Integer> hits = CommandMatcher.positions(query, s);
        List<Text> parts = new ArrayList<>();
        int pos = 0;
        int i = 0;
        while (i < hits.size()) {
            int start = hits.get(i);
            int end = start + 1;
            while (i + 1 < hits.size() && hits.get(i + 1) == end) {
                end++;
                i++;
            }
            if (start > pos) parts.add(run(s.substring(pos, start), PLAIN));
            parts.add(run(s.substring(start, end), MATCHED));
            pos = end;
            i++;
        }
        if (pos < s.length() || parts.isEmpty()) parts.add(run(s.substring(pos), PLAIN));
        return parts;
    }

    private static Text run(String text, String styleClass) {
        Text t = new Text(text);
        t.getStyleClass().add(styleClass);
        return t;
    }
}
