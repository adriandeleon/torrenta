package com.torrenta.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Puts a {@link DedupPlan}'s files where the add will look for them, before the add: a hard link
 * where the plan says one volume, else (when copies are allowed) a clone where the file system
 * can make one ({@code cp --reflink=always} on Linux, {@code cp -c}, which is {@code clonefile},
 * on macOS), else a full copy. A copy is written beside its target under a temporary name and
 * moved into place only once it has the size it should, so the add's check never meets half a
 * file; that check then verifies every byte, v2 pieces being per file. A target that appeared
 * meanwhile is left alone; a file that cannot be placed is downloaded as it would have been.
 *
 * <p>Blocking, and a copy can take minutes: never on the FX thread, never under the engine's lock.
 */
public final class DedupFiles {

    /** The suffix of a copy on its way: never a name a torrent's own file has. */
    static final String COPYING = ".torrenta-copy";

    private DedupFiles() {}

    /**
     * What was placed.
     *
     * @param found the files put in place, by index, with how and from which torrent
     * @param bytes their bytes
     * @param problems a line per file that could not be placed (it is downloaded instead)
     */
    public record Placed(Map<Integer, FoundFile> found, long bytes, List<String> problems) {
        public static final Placed NOTHING = new Placed(Map.of(), 0, List.of());

        public Placed {
            found = Map.copyOf(found);
            problems = List.copyOf(problems);
        }

        public boolean isEmpty() {
            return found.isEmpty();
        }
    }

    /** A copy-on-write clone of a file, where the file system can make one. */
    public interface Cloner {
        /**
         * Clones {@code source} to {@code target}, which does not exist; true when it did, false
         * when the file system (or this platform) cannot, in which case {@code target} is absent.
         */
        boolean cloneFile(Path source, Path target);

        /** None: every copy is a full one. */
        Cloner NONE = (source, target) -> false;

        /** This machine's: {@code cp} with its clone flag on Linux and macOS, none on Windows. */
        Cloner SYSTEM = (source, target) -> {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            List<String> argv;
            if (os.contains("linux"))
                argv = List.of("cp", "--reflink=always", "--", source.toString(), target.toString());
            else if (os.contains("mac")) argv = List.of("cp", "-c", source.toString(), target.toString());
            else return false;
            try {
                Process p = new ProcessBuilder(argv)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
                        .start();
                // A clone is metadata only: it is done at once, or it is not a clone.
                if (!p.waitFor(30, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    Files.deleteIfExists(target);
                    return false;
                }
                if (p.exitValue() == 0) return true;
                Files.deleteIfExists(target);
                return false;
            } catch (IOException e) {
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        };
    }

    private static java.io.File nullDevice() {
        return new java.io.File(System.getProperty("os.name", "").startsWith("Windows") ? "NUL" : "/dev/null");
    }

    /**
     * Places the plan's files: its links when {@code links}, its copies when {@code copies}.
     *
     * @param names a torrent's name by its id, for the record of where each file came from
     */
    public static Placed place(
            DedupPlan plan, boolean links, boolean copies, Cloner cloner, Function<String, String> names) {
        Map<Integer, FoundFile> found = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        long bytes = 0;
        for (DedupPlan.Match m : plan.matches()) {
            if (m.method() == DedupPlan.Method.COPY ? !copies : !links) continue;
            Path target = m.target();
            Path source = m.source().path();
            try {
                if (Files.exists(target)) continue;
                Files.createDirectories(target.getParent());
                FoundFile.How how = null;
                if (m.method() == DedupPlan.Method.LINK) {
                    try {
                        Files.createLink(target, source);
                        how = FoundFile.How.LINK;
                    } catch (IOException | UnsupportedOperationException e) {
                        // One volume that takes no hard links (FAT, exFAT, some network shares).
                        if (!copies) throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
                    }
                }
                if (how == null) how = copy(source, target, m.size(), cloner);
                found.put(m.index(), new FoundFile(how, names.apply(m.source().torrentId())));
                bytes += m.size();
            } catch (IOException | RuntimeException e) {
                problems.add(m.path() + ": " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }
        return found.isEmpty() && problems.isEmpty() ? Placed.NOTHING : new Placed(found, bytes, problems);
    }

    /** A clone, else a full copy, under a temporary name, then moved into place at its size. */
    private static FoundFile.How copy(Path source, Path target, long size, Cloner cloner) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + COPYING);
        Files.deleteIfExists(temporary);
        try {
            FoundFile.How how = FoundFile.How.CLONE;
            if (!cloner.cloneFile(source, temporary)) {
                Files.copy(source, temporary);
                how = FoundFile.How.COPY;
            }
            long copied = Files.size(temporary);
            if (copied != size) throw new IOException("copied " + copied + " bytes of " + size);
            // Not ATOMIC_MOVE: rename(2) would replace a file that appeared at the target since.
            // A plain move refuses one, and in one folder it is a rename all the same.
            Files.move(temporary, target);
            return how;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
