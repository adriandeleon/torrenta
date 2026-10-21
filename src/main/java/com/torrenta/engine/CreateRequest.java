package com.torrenta.engine;

import java.nio.file.Path;
import java.util.List;

/**
 * Everything the Create Torrent dialog decides, in one engine-neutral request.
 *
 * @param path the file or folder to make the torrent of; a folder's name becomes the torrent's
 *     root folder, a file's name the torrent's name
 * @param format v1, v2 or both
 * @param pieceSize bytes, a power of two; 0 lets the engine choose
 * @param isPrivate the private flag: no DHT, no PEX, no local discovery
 * @param trackers announce URLs by tier, the first tier tried first
 * @param webSeeds HTTP sources ({@code url-list})
 * @param comment the torrent's comment, or empty
 * @param source the {@code source} key of the info dictionary, or empty: what a private tracker
 *     asks for so that the same files make a different torrent for each of them
 * @param alignFiles pad every file to a piece boundary (libtorrent's canonical layout); a v2 or
 *     hybrid torrent is always laid out this way, so the flag only matters for a v1-only one
 * @param startSeeding add the torrent once created, the files trusted as complete, with the
 *     parent of {@code path} as the save path so they are found without a recheck
 */
public record CreateRequest(
        Path path,
        Format format,
        int pieceSize,
        boolean isPrivate,
        List<List<String>> trackers,
        List<String> webSeeds,
        String comment,
        String source,
        boolean alignFiles,
        boolean startSeeding) {

    public CreateRequest {
        if (format == null) format = Format.HYBRID;
        if (pieceSize < 0) throw new IllegalArgumentException("piece size < 0: " + pieceSize);
        trackers = trackers == null
                ? List.of()
                : trackers.stream().map(List::copyOf).toList();
        webSeeds = webSeeds == null ? List.of() : List.copyOf(webSeeds);
        if (comment == null) comment = "";
        if (source == null) source = "";
    }

    /** The dialog's defaults for a path: hybrid, automatic piece size, public, nothing else. */
    public static CreateRequest defaults(Path path) {
        return new CreateRequest(path, Format.HYBRID, 0, false, List.of(), List.of(), "", "", false, false);
    }

    /** Where the files are seeded from: the parent of the source, so the torrent's own paths match. */
    public Path savePath() {
        Path parent = path.toAbsolutePath().normalize().getParent();
        return parent == null ? path.toAbsolutePath().normalize() : parent;
    }

    /** The trackers flattened, tier order kept. */
    public List<String> allTrackers() {
        return trackers.stream().flatMap(List::stream).toList();
    }

    /** Which piece hashes the torrent carries. */
    public enum Format {
        HYBRID("Hybrid (v1 + v2)"),
        V1("v1 only"),
        V2("v2 only");

        private final String label;

        Format(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** Files start on piece boundaries: always for v2, so for a hybrid too. */
        public boolean alignsFiles() {
            return this != V1;
        }
    }
}
