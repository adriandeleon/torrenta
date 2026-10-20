package com.torrenta.engine;

import java.nio.file.Path;
import java.util.Map;

/**
 * Everything the Add dialog can decide, in one engine-neutral request.
 *
 * @param source the torrent itself or the magnet link
 * @param savePath where the files go
 * @param startPaused added stopped; the person resumes it
 * @param sequential pieces in order
 * @param skipCheck trust that the files on disk are complete (seed mode); for re-adding something
 *     already downloaded
 * @param stopCondition stop by itself once this is reached
 * @param filePriorities by file index; a file not in the map gets {@link Priority#NORMAL}
 * @param labels the category and tags to file it under; {@link TorrentLabels#NONE} for none
 */
public record AddRequest(
        Source source,
        Path savePath,
        boolean startPaused,
        boolean sequential,
        boolean skipCheck,
        StopCondition stopCondition,
        Map<Integer, Priority> filePriorities,
        TorrentLabels labels) {

    public AddRequest {
        if (labels == null) labels = TorrentLabels.NONE;
    }

    public sealed interface Source permits TorrentFile, Magnet {}

    /** The bytes of a {@code .torrent}; {@code origin} is the path or URL it came from, for messages. */
    public record TorrentFile(byte[] bytes, String origin) implements Source {}

    public record Magnet(String uri) implements Source {}

    public enum StopCondition {
        NONE("Don't stop"),
        METADATA_RECEIVED("After the metadata arrives"),
        FILES_CHECKED("After the files are checked");

        private final String label;

        StopCondition(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** The dialog's defaults: start now, rarest first, check, no stop, all files, no labels. */
    public static AddRequest defaults(Source source, Path savePath) {
        return new AddRequest(source, savePath, false, false, false, StopCondition.NONE, Map.of(), TorrentLabels.NONE);
    }
}
