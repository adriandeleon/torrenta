package com.torrenta.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;

import com.torrenta.engine.DownloadPlan;

/** Usable bytes on the volume a path is (or will be) on. */
public final class FreeSpace {

    private FreeSpace() {}

    /**
     * The Add dialog's lines under the save path: the free space where the files end up and,
     * when they start in the incomplete folder, that folder and its free space on a line of its
     * own (the download has to fit there first). Pure: the dialog hands in what it measured.
     */
    public static String hint(DownloadPlan plan, OptionalLong freeAtStart, OptionalLong freeAtDestination) {
        String destination = freeAtDestination.isPresent() ? Bytes.human(freeAtDestination.getAsLong()) + " free" : "";
        if (!plan.viaIncomplete()) return destination;
        String start = "Starts in " + plan.start()
                + (freeAtStart.isPresent() ? " (" + Bytes.human(freeAtStart.getAsLong()) + " free)" : "");
        String then = "Moved here when complete" + (destination.isEmpty() ? "" : " (" + destination + ")");
        return start + "\n" + then;
    }

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
