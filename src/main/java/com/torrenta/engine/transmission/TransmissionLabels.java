package com.torrenta.engine.transmission;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.torrenta.engine.TorrentLabels;

/**
 * Torrenta's one category and many tags, in Transmission's one flat list of labels.
 *
 * <p>The category travels as a label with the {@value #CATEGORY_PREFIX} prefix, the tags as they
 * are; so the daemon's other clients see {@code category:Movies} beside {@code linux}, and a
 * torrent filed from one of them with a plain label shows up here as tagged. Pure.
 */
final class TransmissionLabels {

    static final String CATEGORY_PREFIX = "category:";

    private TransmissionLabels() {}

    /** The daemon's labels as the window's filing. A second category label counts as a tag. */
    static TorrentLabels fromLabels(Collection<String> labels) {
        String category = "";
        List<String> tags = new ArrayList<>();
        for (String label : labels) {
            if (label == null) continue;
            String l = label.trim();
            if (l.isEmpty()) continue;
            if (category.isEmpty() && l.regionMatches(true, 0, CATEGORY_PREFIX, 0, CATEGORY_PREFIX.length())) {
                category = l.substring(CATEGORY_PREFIX.length()).trim();
                if (category.isEmpty()) continue;
            } else {
                tags.add(l);
            }
        }
        return new TorrentLabels(category, tags);
    }

    /** The filing as the daemon's labels: the category first, then the tags. */
    static List<String> toLabels(TorrentLabels labels) {
        List<String> out = new ArrayList<>();
        if (!labels.category().isEmpty()) out.add(CATEGORY_PREFIX + labels.category());
        out.addAll(labels.tags());
        return List.copyOf(out);
    }
}
