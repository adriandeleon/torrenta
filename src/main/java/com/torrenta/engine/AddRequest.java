package com.torrenta.engine;

import java.nio.file.Path;
import java.util.Map;

import static com.torrenta.i18n.Messages.tr;

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
 * @param storage where the files start, how they are named and allocated; {@link Storage#DEFAULT}
 *     for straight to the save path, sparse, no suffix
 */
public record AddRequest(
        Source source,
        Path savePath,
        boolean startPaused,
        boolean sequential,
        boolean skipCheck,
        StopCondition stopCondition,
        Map<Integer, Priority> filePriorities,
        TorrentLabels labels,
        Storage storage) {

    public AddRequest {
        if (labels == null) labels = TorrentLabels.NONE;
        if (storage == null) storage = Storage.DEFAULT;
    }

    /** Without storage choices: the files go straight to the save path. */
    public AddRequest(
            Source source,
            Path savePath,
            boolean startPaused,
            boolean sequential,
            boolean skipCheck,
            StopCondition stopCondition,
            Map<Integer, Priority> filePriorities,
            TorrentLabels labels) {
        this(
                source,
                savePath,
                startPaused,
                sequential,
                skipCheck,
                stopCondition,
                filePriorities,
                labels,
                Storage.DEFAULT);
    }

    public AddRequest withStorage(Storage s) {
        return new AddRequest(
                source, savePath, startPaused, sequential, skipCheck, stopCondition, filePriorities, labels, s);
    }

    public AddRequest withFilePriorities(Map<Integer, Priority> priorities) {
        return new AddRequest(
                source, savePath, startPaused, sequential, skipCheck, stopCondition, priorities, labels, storage);
    }

    public AddRequest withStopCondition(StopCondition condition) {
        return new AddRequest(
                source, savePath, startPaused, sequential, skipCheck, condition, filePriorities, labels, storage);
    }

    /**
     * The storage choices, nested so the request did not grow three more positional arguments.
     *
     * @param incompletePath the folder to download into, to be moved to the save path once
     *     complete; null for none (download straight to the save path)
     * @param partSuffix incomplete files carry {@link TorrentStorage#SUFFIX} until each is done
     * @param preallocate every file is allocated in full when the torrent starts, rather than
     *     growing as pieces arrive
     * @param layout how the files sit under the save path; needs the file list, so a magnet
     *     added before its metadata keeps the torrent's own
     * @param found the files already put where the add will look for them, found on disk in
     *     another torrent ({@link DedupPlan}), by index: the add's own check verifies them, and
     *     the engine keeps the record for the Files tab
     */
    public record Storage(
            Path incompletePath,
            boolean partSuffix,
            boolean preallocate,
            FileRenames.Layout layout,
            Map<Integer, FoundFile> found) {
        public static final Storage DEFAULT = new Storage(null, false, false);

        public Storage {
            if (layout == null) layout = FileRenames.Layout.ORIGINAL;
            found = found == null ? Map.of() : Map.copyOf(found);
        }

        /** Nothing found on disk. */
        public Storage(Path incompletePath, boolean partSuffix, boolean preallocate, FileRenames.Layout layout) {
            this(incompletePath, partSuffix, preallocate, layout, Map.of());
        }

        /** The torrent's own layout. */
        public Storage(Path incompletePath, boolean partSuffix, boolean preallocate) {
            this(incompletePath, partSuffix, preallocate, FileRenames.Layout.ORIGINAL);
        }

        public Storage withLayout(FileRenames.Layout l) {
            return new Storage(incompletePath, partSuffix, preallocate, l, found);
        }

        public Storage withFound(Map<Integer, FoundFile> files) {
            return new Storage(incompletePath, partSuffix, preallocate, layout, files);
        }

        /** Where the add puts the files first: the incomplete folder, else {@code savePath}. */
        public Path start(Path savePath) {
            return incompletePath == null ? savePath : incompletePath;
        }

        /** The storage for a plan: its incomplete folder if it has one. */
        public static Storage of(DownloadPlan plan, boolean partSuffix, boolean preallocate) {
            return new Storage(plan.incomplete().orElse(null), partSuffix, preallocate);
        }
    }

    public sealed interface Source permits TorrentFile, Magnet {}

    /** The bytes of a {@code .torrent}; {@code origin} is the path or URL it came from, for messages. */
    public record TorrentFile(byte[] bytes, String origin) implements Source {}

    public record Magnet(String uri) implements Source {}

    public enum StopCondition {
        NONE("never"),
        METADATA_RECEIVED("metadataReceived"),
        FILES_CHECKED("filesChecked");

        private final String label;

        StopCondition(String label) {
            this.label = label;
        }

        public String label() {
            return tr("stopCondition." + label);
        }
    }

    /** The dialog's defaults: start now, rarest first, check, no stop, all files, no labels. */
    public static AddRequest defaults(Source source, Path savePath) {
        return new AddRequest(
                source,
                savePath,
                false,
                false,
                false,
                StopCondition.NONE,
                Map.of(),
                TorrentLabels.NONE,
                Storage.DEFAULT);
    }
}
