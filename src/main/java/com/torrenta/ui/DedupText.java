package com.torrenta.ui;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;

import com.torrenta.engine.DedupPlan;

import static com.torrenta.i18n.Messages.tr;

/** The Add dialog's lines about files already on disk, from a {@link DedupPlan}. Pure. */
final class DedupText {

    private DedupText() {}

    /**
     * What the plan offers, by way: the links (no bytes written) and the copies (from another
     * volume), with the torrents the files are in.
     */
    record Offer(int links, long linkBytes, int copies, long copyBytes, Set<String> linkFrom, Set<String> copyFrom) {
        static final Offer NONE = new Offer(0, 0, 0, 0, Set.of(), Set.of());

        boolean isEmpty() {
            return links == 0 && copies == 0;
        }
    }

    static Offer offer(DedupPlan plan, Function<String, String> names) {
        if (plan.isEmpty()) return Offer.NONE;
        int links = 0;
        int copies = 0;
        long linkBytes = 0;
        long copyBytes = 0;
        Set<String> linkFrom = new LinkedHashSet<>();
        Set<String> copyFrom = new LinkedHashSet<>();
        for (DedupPlan.Match m : plan.matches()) {
            String from = names.apply(m.source().torrentId());
            if (m.method() == DedupPlan.Method.LINK) {
                links++;
                linkBytes += m.size();
                linkFrom.add(from);
            } else {
                copies++;
                copyBytes += m.size();
                copyFrom.add(from);
            }
        }
        return new Offer(links, linkBytes, copies, copyBytes, Set.copyOf(linkFrom), Set.copyOf(copyFrom));
    }

    /** "Link 3 files (4.2 GB) already on disk in “Debian 13.1”". */
    static String linkLine(Offer o) {
        return tr("dialog.add.dedup.link", o.links(), Bytes.human(o.linkBytes()), where(o.linkFrom()));
    }

    /** "Copy 2 files (1.1 GB) from “Debian 13.1” on another drive". */
    static String copyLine(Offer o) {
        return tr("dialog.add.dedup.copy", o.copies(), Bytes.human(o.copyBytes()), where(o.copyFrom()));
    }

    /** One torrent by its name, several by their count. */
    static String where(Set<String> from) {
        return from.size() == 1
                ? tr("dialog.add.dedup.in", from.iterator().next())
                : tr("dialog.add.dedup.inTorrents", from.size());
    }
}
