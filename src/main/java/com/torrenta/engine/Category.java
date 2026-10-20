package com.torrenta.engine;

import java.nio.file.Path;
import java.util.Optional;

/**
 * A category: a name a torrent can be filed under, and optionally where such torrents download.
 *
 * <p>The save path is the automatic part of "automatic torrent management": the Add dialog fills
 * its folder from the chosen category, so a new torrent in it lands there without a second
 * thought. Changing the path later moves nothing by itself; new torrents follow it. Engine-neutral;
 * kept in {@code categories.json} by the store.
 *
 * @param name trimmed, never blank
 * @param savePath the download folder as typed, or empty for the default one
 */
public record Category(String name, String savePath) {

    public Category {
        name = name == null ? "" : name.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("a category needs a name");
        savePath = savePath == null ? "" : savePath.trim();
    }

    public Category(String name) {
        this(name, "");
    }

    /** The folder, if the category has one. */
    public Optional<Path> path() {
        if (savePath.isEmpty()) return Optional.empty();
        try {
            return Optional.of(Path.of(savePath).toAbsolutePath());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
