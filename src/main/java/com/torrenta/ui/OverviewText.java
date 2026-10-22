package com.torrenta.ui;

import java.util.ArrayList;
import java.util.List;

import com.torrenta.engine.Torrent;

import static com.torrenta.i18n.Messages.tr;

/**
 * The detail pane's words: the line under the name ("6.2 GB · 4 files · Films · added 2 days
 * ago"), the Overview's facts, and the info-hash shortened to what a person compares. Pure and
 * tested, like {@link StatusLine}.
 */
final class OverviewText {

    /** The bullet between clauses; punctuation, the same in every language. */
    static final String SEP = " · ";

    private OverviewText() {}

    /**
     * Size, file count, category and age, each left out while unknown or empty: a magnet without
     * its metadata has no size and no files yet.
     */
    static String subtitle(Torrent t, int files, long nowSeconds) {
        List<String> parts = new ArrayList<>();
        if (t.size() > 0) parts.add(Bytes.human(t.size()));
        if (files > 0) parts.add(tr("detail.header.files", files));
        if (!t.category().isEmpty()) parts.add(t.category());
        if (t.added() > 0) parts.add(tr("detail.header.added", Format.coarse(Math.max(0, nowSeconds - t.added()))));
        return String.join(SEP, parts);
    }

    /** "23 connected · 0 seeds". */
    static String peers(Torrent t) {
        return tr("detail.overview.peersValue", t.peers(), t.seeds());
    }

    /** "6.0 GB of 6.2 GB", or the size alone once every wanted byte is there. */
    static String downloaded(Torrent t) {
        if (t.size() <= 0) return Bytes.human(t.done());
        if (t.done() >= t.size()) return Bytes.human(t.size());
        return tr("detail.overview.downloadedValue", Bytes.human(t.done()), Bytes.human(t.size()));
    }

    /** "1.1 GB · ratio 0.18"; the ratio left out until something has come down. */
    static String uploaded(Torrent t) {
        double ratio = Format.ratioValue(t.uploaded(), t.downloaded());
        if (ratio < 0) return Bytes.human(t.uploaded());
        return tr("detail.overview.uploadedValue", Bytes.human(t.uploaded()), Format.ratio(ratio));
    }

    /** "1,153 of 1,184 · 97.4%", the map's caption. */
    static String pieces(int have, int count) {
        double fraction = count == 0 ? 0 : (double) have / count;
        return tr("detail.overview.piecesValue", have, count, Format.percent(fraction));
    }

    /**
     * The first and last eight hex digits in groups of four, {@code 7a3f 02c1 … 9e4d c912}: enough
     * to tell two torrents apart by eye, short enough not to wrap. A short id is grouped whole.
     */
    static String shortHash(String hex) {
        if (hex == null || hex.isEmpty()) return "—";
        if (hex.length() <= 20) return grouped(hex);
        return grouped(hex.substring(0, 8)) + " … " + grouped(hex.substring(hex.length() - 8));
    }

    private static String grouped(String hex) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < hex.length(); i += 4) {
            if (i > 0) out.append(' ');
            out.append(hex, i, Math.min(hex.length(), i + 4));
        }
        return out.toString();
    }
}
