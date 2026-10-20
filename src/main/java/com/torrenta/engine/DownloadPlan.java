package com.torrenta.engine;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Where a new torrent's files start and where they end up: the one rule behind the incomplete
 * folder.
 *
 * <p>The destination is what the person chose in the Add dialog (a category with a save path
 * fills that in; see {@link #destination(Path, Optional)}). When the incomplete folder is on, the
 * torrent downloads there and is moved to the destination once complete; when it is off, or the
 * two folders are the same, it downloads straight to the destination. Pure, so it is tested.
 *
 * @param start the folder the files are written to while downloading
 * @param destination the folder they are in once complete
 */
public record DownloadPlan(Path start, Path destination) {

    public DownloadPlan {
        start = start.toAbsolutePath().normalize();
        destination = destination.toAbsolutePath().normalize();
    }

    /** Whether the files move when the torrent completes. */
    public boolean viaIncomplete() {
        return !start.equals(destination);
    }

    /** The folder the engine is told about as the incomplete one, if the plan uses it. */
    public Optional<Path> incomplete() {
        return viaIncomplete() ? Optional.of(start) : Optional.empty();
    }

    public static DownloadPlan of(Path destination, boolean incompleteEnabled, Path incompleteDir) {
        Path start = incompleteEnabled && incompleteDir != null ? incompleteDir : destination;
        return new DownloadPlan(start, destination);
    }

    /** The category's folder when it has one, else the default download folder. */
    public static Path destination(Path defaultDir, Optional<Category> category) {
        return category.flatMap(Category::path).orElse(defaultDir);
    }
}
