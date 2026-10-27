package com.torrenta.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Placing on a real disk: links, copies, clones, and what is never touched. */
class DedupFilesTest {

    static final String ROOT = "4".repeat(64);
    static final Function<String, String> NAMES = id -> id.equals("old") ? "Old Torrent" : id;

    private static DedupPlan.Match match(int index, Path source, Path target, long size, DedupPlan.Method method) {
        return new DedupPlan.Match(
                index,
                "New/" + target.getFileName(),
                size,
                new ContentIndex.Entry(ROOT, "old", 0, source, size),
                target,
                method);
    }

    private static Path source(Path dir, String name, int size) throws IOException {
        Path f = dir.resolve("seeds/" + name);
        Files.createDirectories(f.getParent());
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) bytes[i] = (byte) (i * 31);
        Files.write(f, bytes);
        return f;
    }

    @Test
    void aLinkIsTheSameFileAndACopyItsOwn(@TempDir Path dir) throws IOException {
        Path a = source(dir, "a.bin", 1000);
        Path b = source(dir, "b.bin", 2000);
        Path linkTarget = dir.resolve("dl/New/a.bin");
        Path copyTarget = dir.resolve("dl/New/sub/b.bin");
        DedupPlan plan = new DedupPlan(List.of(
                match(0, a, linkTarget, 1000, DedupPlan.Method.LINK),
                match(1, b, copyTarget, 2000, DedupPlan.Method.COPY)));
        DedupFiles.Placed placed = DedupFiles.place(plan, true, true, DedupFiles.Cloner.NONE, NAMES);
        assertEquals(
                Map.of(
                        0,
                        new FoundFile(FoundFile.How.LINK, "Old Torrent"),
                        1,
                        new FoundFile(FoundFile.How.COPY, "Old Torrent")),
                placed.found());
        assertEquals(3000, placed.bytes());
        assertTrue(Files.isSameFile(a, linkTarget), "a hard link: one file");
        assertFalse(Files.isSameFile(b, copyTarget), "a copy: its own file");
        assertArrayEquals(Files.readAllBytes(b), Files.readAllBytes(copyTarget));
        assertFalse(Files.exists(copyTarget.resolveSibling("b.bin" + DedupFiles.COPYING)), "no copy left half-way");
    }

    @Test
    void onlyWhatIsAllowedIsPlacedAndNothingThereIsTouched(@TempDir Path dir) throws IOException {
        Path a = source(dir, "a.bin", 1000);
        Path b = source(dir, "b.bin", 2000);
        Path taken = dir.resolve("dl/New/a.bin");
        Files.createDirectories(taken.getParent());
        Files.writeString(taken, "someone's own");
        DedupPlan plan = new DedupPlan(List.of(
                match(0, a, taken, 1000, DedupPlan.Method.LINK),
                match(1, b, dir.resolve("dl/New/b.bin"), 2000, DedupPlan.Method.COPY)));
        DedupFiles.Placed linksOnly = DedupFiles.place(plan, true, false, DedupFiles.Cloner.NONE, NAMES);
        assertTrue(linksOnly.isEmpty(), "the link's target is taken, and copies are not allowed");
        assertEquals("someone's own", Files.readString(taken), "never written over");
        assertFalse(Files.exists(dir.resolve("dl/New/b.bin")));
        DedupFiles.Placed copiesOnly = DedupFiles.place(plan, false, true, DedupFiles.Cloner.NONE, NAMES);
        assertEquals(Map.of(1, new FoundFile(FoundFile.How.COPY, "Old Torrent")), copiesOnly.found());
    }

    @Test
    void aCloneIsSaidAndABadOneNeverReachesTheTarget(@TempDir Path dir) throws IOException {
        Path b = source(dir, "b.bin", 2000);
        Path target = dir.resolve("dl/New/b.bin");
        DedupPlan plan = new DedupPlan(List.of(match(1, b, target, 2000, DedupPlan.Method.COPY)));
        DedupFiles.Cloner cloner = (from, to) -> {
            try {
                Files.copy(from, to);
                return true;
            } catch (IOException e) {
                return false;
            }
        };
        assertEquals(
                FoundFile.How.CLONE,
                DedupFiles.place(plan, true, true, cloner, NAMES).found().get(1).how());

        // A clone that comes out short: refused, the temporary file gone, the target absent.
        Path other = dir.resolve("dl2/New/b.bin");
        DedupPlan second = new DedupPlan(List.of(match(1, b, other, 2000, DedupPlan.Method.COPY)));
        DedupFiles.Cloner shortClone = (from, to) -> {
            try {
                Files.write(to, new byte[10]);
                return true;
            } catch (IOException e) {
                return false;
            }
        };
        DedupFiles.Placed placed = DedupFiles.place(second, true, true, shortClone, NAMES);
        assertTrue(placed.isEmpty());
        assertEquals(1, placed.problems().size());
        assertTrue(placed.problems().getFirst().startsWith("New/b.bin: copied 10 bytes of 2000"));
        assertFalse(Files.exists(other));
        assertFalse(Files.exists(other.resolveSibling("b.bin" + DedupFiles.COPYING)));
    }

    @Test
    void aSourceGoneIsAProblemNotAFailure(@TempDir Path dir) throws IOException {
        Path gone = dir.resolve("seeds/gone.bin");
        DedupPlan plan =
                new DedupPlan(List.of(match(0, gone, dir.resolve("dl/New/gone.bin"), 5, DedupPlan.Method.LINK)));
        DedupFiles.Placed placed = DedupFiles.place(plan, true, true, DedupFiles.Cloner.NONE, NAMES);
        assertTrue(placed.isEmpty());
        assertEquals(1, placed.problems().size(), "a link that fails falls back to a copy, which fails too");
    }
}
