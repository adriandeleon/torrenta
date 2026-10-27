package com.torrenta.session;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.torrenta.engine.Engine;
import com.torrenta.engine.IpRange;
import com.torrenta.engine.PackedRanges;
import com.torrenta.store.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The schedule's rule (when a refresh has anything to do) and the parsed lists' (when to read again). */
class IpFilterControllerTest {

    private static final long HOUR = 3600_000L;
    private static final long NOW = 1_000 * HOUR;
    private static final String URL = "https://example.org/list.p2p.gz";

    @Test
    void aListNeverFetchedOrFetchedLongAgoIsDue() {
        assertTrue(IpFilterController.due(0, 24, NOW));
        assertTrue(IpFilterController.due(NOW - 24 * HOUR, 24, NOW));
        assertFalse(IpFilterController.due(NOW - 23 * HOUR, 24, NOW));
    }

    @Test
    void aFetchInTheFutureMeansTheClockMovedAndIsDue() {
        assertTrue(IpFilterController.due(NOW + HOUR, 24, NOW));
    }

    @Test
    void nothingIsFetchedWithTheFilterOffOrNoUrl() {
        // Off, or on with only a local file and bans: the start's apply has already said it all.
        assertFalse(IpFilterController.fetchDue(false, URL, 0, 24, NOW));
        assertFalse(IpFilterController.fetchDue(true, "", 0, 24, NOW));
        assertFalse(IpFilterController.fetchDue(true, null, 0, 24, NOW));
        assertTrue(IpFilterController.fetchDue(true, URL, 0, 24, NOW));
        assertFalse(IpFilterController.fetchDue(true, URL, NOW - HOUR, 24, NOW));
    }

    private static final Path LIST = Path.of("/config/blocklist.txt");
    private static final IpFilterController.Stamp STAMP =
            new IpFilterController.Stamp(LIST, 1000, FileTime.fromMillis(NOW), "inode 7");

    private static IpFilterController.Parsed parsedAt(IpFilterController.Stamp stamp) {
        return new IpFilterController.Parsed(stamp, PackedRanges.EMPTY, 0);
    }

    @Test
    void aListIsReadAgainWhenItsSizeTimeFileOrPathMoved() {
        assertTrue(IpFilterController.fresh(parsedAt(STAMP), STAMP));
        assertTrue(IpFilterController.fresh(
                parsedAt(STAMP), new IpFilterController.Stamp(LIST, 1000, FileTime.fromMillis(NOW), "inode 7")));
        assertFalse(IpFilterController.fresh(
                parsedAt(STAMP), new IpFilterController.Stamp(LIST, 1001, FileTime.fromMillis(NOW), "inode 7")));
        assertFalse(IpFilterController.fresh(
                parsedAt(STAMP), new IpFilterController.Stamp(LIST, 1000, FileTime.fromMillis(NOW + 1), "inode 7")));
        assertFalse(
                IpFilterController.fresh(
                        parsedAt(STAMP), new IpFilterController.Stamp(LIST, 1000, FileTime.fromMillis(NOW), "inode 8")),
                "replaced by a move within the clock's tick");
        assertFalse(IpFilterController.fresh(
                parsedAt(STAMP),
                new IpFilterController.Stamp(Path.of("/elsewhere.p2p"), 1000, FileTime.fromMillis(NOW), "inode 7")));
        assertTrue(
                IpFilterController.fresh(
                        parsedAt(new IpFilterController.Stamp(LIST, 1000, FileTime.fromMillis(NOW), null)),
                        new IpFilterController.Stamp(LIST, 1000, FileTime.fromMillis(NOW), null)),
                "no file key where the platform has none");
        assertFalse(IpFilterController.fresh(null, STAMP), "never read");
        assertFalse(IpFilterController.fresh(parsedAt(STAMP), null), "gone or unreadable");
    }

    @Test
    void aStampIsOfARegularFileAndChangesWithIt(@TempDir Path dir) throws Exception {
        Path list = dir.resolve("list.p2p");
        assertNull(IpFilterController.Stamp.of(list), "no file");
        assertNull(IpFilterController.Stamp.of(dir), "a folder");
        Files.writeString(list, "a:1.2.3.4-1.2.3.5\n");
        IpFilterController.Stamp first = IpFilterController.Stamp.of(list);
        assertEquals(first, IpFilterController.Stamp.of(list));
        Path replacement = dir.resolve("next.part");
        Files.writeString(replacement, "a:1.2.3.6-1.2.3.7\n");
        Files.setLastModifiedTime(replacement, first.modified());
        Files.move(replacement, list, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        IpFilterController.Stamp after = IpFilterController.Stamp.of(list);
        if (first.key() != null) assertNotEquals(first, after, "same size and time, another file");
    }

    @Test
    void aBanRebuildsTheFilterWholeWithoutParsingTheListAgain(@TempDir Path dir) throws Exception {
        Path list = dir.resolve("local.p2p");
        Files.writeString(list, "Org:1.2.3.0-1.2.3.255\nnonsense\nOther:5.6.7.8-5.6.7.9\n");
        Settings settings = new Settings(dir.resolve(Settings.FILE_NAME));
        settings.set(Settings.IPFILTER_ENABLED, "true");
        settings.set(Settings.IPFILTER_FILE, list.toString());
        List<List<IpRange>> filters = new CopyOnWriteArrayList<>();
        Engine engine = (Engine) Proxy.newProxyInstance(
                Engine.class.getClassLoader(), new Class<?>[] {Engine.class}, (p, m, a) -> {
                    if (m.getName().equals("setIpFilter")) {
                        @SuppressWarnings("unchecked")
                        List<IpRange> ranges = (List<IpRange>) a[0];
                        filters.add(List.copyOf(ranges));
                    }
                    return null;
                });
        IpFilterController controller = new IpFilterController(settings, dir);
        controller.setEngine(engine);
        List<String> reports = new CopyOnWriteArrayList<>();

        controller.applyStored(reports::add);
        assertEquals(List.of(new IpRange("1.2.3.0", "1.2.3.255"), new IpRange("5.6.7.8", "5.6.7.9")), filters.get(0));
        assertEquals(1, controller.status().skipped());

        settings.setIpFilterBans(List.of("203.0.113.9"));
        controller.applyStored(reports::add);
        assertEquals(1, controller.parsesForTest(), "a ban does not parse the list again");
        assertEquals(
                List.of(
                        new IpRange("1.2.3.0", "1.2.3.255"),
                        new IpRange("5.6.7.8", "5.6.7.9"),
                        IpRange.single("203.0.113.9")),
                filters.get(1),
                "the whole filter again, with the ban");
        assertEquals(1, controller.status().skipped(), "the list's own skipped line still counts");

        Files.writeString(list, "Org:9.9.9.0-9.9.9.255\n");
        controller.applyStored(reports::add);
        assertEquals(2, controller.parsesForTest(), "a changed list is read again");
        assertEquals(List.of(new IpRange("9.9.9.0", "9.9.9.255"), IpRange.single("203.0.113.9")), filters.get(2));

        Files.delete(list);
        controller.applyStored(reports::add);
        assertEquals(List.of(IpRange.single("203.0.113.9")), filters.get(3), "a list gone is not remembered");
    }
}
