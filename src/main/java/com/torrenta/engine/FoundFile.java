package com.torrenta.engine;

import java.util.Locale;

import static com.torrenta.i18n.Messages.tr;

/**
 * A file of a torrent that was not downloaded but found on disk, complete, in another torrent
 * ({@link DedupPlan}), and put in place before the add: how, and from which torrent. Kept in the
 * {@code .storage} sidecar so the Files tab can say so for as long as the torrent is in the list.
 *
 * @param how how it got there
 * @param from the name of the torrent it came from, as it was then
 */
public record FoundFile(How how, String from) {

    public FoundFile {
        if (how == null) how = How.COPY;
        from = from == null ? "" : from;
    }

    /** How the file was put in place. */
    public enum How {
        /** A hard link: one file, two torrents. */
        LINK,
        /** A reflink (a clone): its own file sharing the blocks until either changes. */
        CLONE,
        /** A full copy. */
        COPY;

        /** Its word in the sidecar. */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The word in the sidecar back; anything else is a copy, the one that promises least. */
        public static How byKey(String key) {
            for (How h : values()) if (h.key().equals(key)) return h;
            return COPY;
        }
    }

    /** The Files tab's words: "Linked from 'Debian 13.1'". */
    public String sentence() {
        return tr("found." + how.key(), from);
    }
}
