package com.torrenta.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;

/** Usable bytes on the volume a path is (or will be) on. */
public final class FreeSpace {

    private FreeSpace() {}

    /**
     * Walks up to the nearest existing ancestor, since the save folder usually does not exist yet
     * while it is being typed. Empty when nothing on the way exists or the store cannot be read.
     */
    public static OptionalLong of(Path path) {
        Path probe = path.toAbsolutePath();
        while (probe != null && !Files.exists(probe)) probe = probe.getParent();
        if (probe == null) return OptionalLong.empty();
        try {
            return OptionalLong.of(Files.getFileStore(probe).getUsableSpace());
        } catch (IOException | RuntimeException e) {
            return OptionalLong.empty();
        }
    }
}
