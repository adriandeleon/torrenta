package com.torrenta.migrate;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.torrenta.i18n.Messages.tr;

/**
 * What to do with each torrent another client has: the verdicts the import dialog shows, decided
 * from the candidates, the ids Torrenta already has, and a look at the disk. Pure but for the
 * {@link FileProbe}, which a test answers by hand.
 *
 * <p>The data is trusted as the other client left it: its resume data says which pieces are
 * there, and the engine adds it without checking, as at its own start. Where the files on disk
 * do not match (a complete torrent with a file of another size, or missing), the engine's add
 * finds it out by itself and checks that torrent, which is slower and comes up short, never
 * wrong: the verdict says so beforehand. An incomplete torrent is left alone while the other
 * client runs: two clients writing one file is corruption.
 */
public final class ImportPlan {

    private ImportPlan() {}

    /** The size of a file on disk, or -1 when there is none. */
    @FunctionalInterface
    public interface FileProbe {
        long size(Path file);
    }

    /** The probe the import uses: the file's size, -1 when it is not a file or will not say. */
    public static long sizeOnDisk(Path file) {
        try {
            return java.nio.file.Files.isRegularFile(file) ? java.nio.file.Files.size(file) : -1;
        } catch (java.io.IOException e) {
            return -1;
        }
    }

    public enum Verdict {
        /** Its files are where its resume data says: added as it is, no check. */
        IMPORT,
        /** A magnet whose metadata never arrived: added as a magnet, to fetch it. */
        MAGNET,
        /** Some files differ from what the resume data expects: added, and checked by the engine. */
        CHECK,
        /** Nothing of its data is where it should be: not imported unless a person says so. */
        MISSING,
        /** Torrenta has it already. */
        ALREADY_HERE,
        /** Incomplete, and the other client is running: not imported. */
        CLIENT_RUNNING;

        /** Imported when the dialog's defaults are taken. */
        public boolean importedByDefault() {
            return this == IMPORT || this == MAGNET || this == CHECK;
        }
    }

    /** One torrent and what to do with it, with the reason in the interface language. */
    public record Item(ImportCandidate candidate, Verdict verdict, String reason) {}

    public static List<Item> of(
            List<ImportCandidate> candidates, Set<String> existingIds, FileProbe probe, boolean clientRunning) {
        List<Item> out = new ArrayList<>();
        for (ImportCandidate c : candidates) out.add(decide(c, existingIds, probe, clientRunning));
        return out;
    }

    static Item decide(ImportCandidate c, Set<String> existingIds, FileProbe probe, boolean clientRunning) {
        if (existingIds.contains(c.id())) return new Item(c, Verdict.ALREADY_HERE, tr("import.verdict.alreadyHere"));
        if (clientRunning && !c.complete())
            return new Item(c, Verdict.CLIENT_RUNNING, tr("import.verdict.clientRunning", c.client()));
        if (c.metainfo() == null) return new Item(c, Verdict.MAGNET, tr("import.verdict.magnet"));
        if (c.piecesHave() == 0) return new Item(c, Verdict.IMPORT, tr("import.verdict.nothingYet"));
        int present = 0;
        int differ = 0;
        for (ImportCandidate.FileEntry f : c.files()) {
            long size = size(probe, c.savePath(), f.path());
            if (size >= 0) present++;
            // Only a file the resume data claims pieces of has to be there.
            if (!f.claimed()) continue;
            // A complete torrent's files are whole; an incomplete one's may be sparse and shorter,
            // never longer.
            if (size < 0 || (c.complete() ? size != f.size() : size > f.size())) differ++;
        }
        if (present == 0) return new Item(c, Verdict.MISSING, tr("import.verdict.missing", c.savePath()));
        if (differ > 0) return new Item(c, Verdict.CHECK, tr("import.verdict.check", differ, c.client()));
        return new Item(c, Verdict.IMPORT, c.complete() ? tr("import.verdict.complete") : tr("import.verdict.partial"));
    }

    private static long size(FileProbe probe, String savePath, String relative) {
        try {
            return probe.size(Path.of(savePath).resolve(relative));
        } catch (InvalidPathException e) {
            return -1;
        }
    }
}
