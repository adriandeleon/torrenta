package com.torrenta.session;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.torrenta.engine.Blocklist;
import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineException;
import com.torrenta.engine.IpRange;
import com.torrenta.engine.PackedRanges;
import com.torrenta.net.BlocklistFetch;
import com.torrenta.store.Settings;
import org.jspecify.annotations.Nullable;

import static com.torrenta.i18n.Messages.tr;

/**
 * Keeps the engine's IP filter in step with the settings: the downloaded blocklist (cached beside
 * the settings, refreshed on a schedule), a local list file, and the manual bans.
 *
 * <p>Every method here runs on a worker; the window hands results to the FX thread through the
 * {@code report} callback. Nothing is fatal: a list that cannot be fetched or read leaves the
 * previous filter in place and says so.
 *
 * <p>A list file is parsed once per change, not once per apply: a ban, a settings edit or a sync
 * round re-applies, and parsing the blocklist each time was most of a second. The parsed ranges
 * are kept packed ({@link PackedRanges}) by the file's {@link Stamp}; the filter handed to the
 * engine is still built whole from them, the local file and the bans.
 */
public final class IpFilterController {

    static final String CACHE_FILE = "blocklist.txt";

    /** What the last apply did, for the page's status line. */
    public record Status(int ranges, int skipped, long loadedAt, String note) {}

    /**
     * What a list file looked like when it was read. The file key (the inode where there is one)
     * tells a list replaced by a move, as the download does, even within the clock's tick.
     */
    record Stamp(
            Path path,
            long size,
            FileTime modified,
            @Nullable Object key) {

        static @Nullable Stamp of(Path path) {
            try {
                BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class);
                if (!a.isRegularFile()) return null;
                return new Stamp(path.toAbsolutePath(), a.size(), a.lastModifiedTime(), a.fileKey());
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
    }

    /** A list file's ranges as read when it had {@code stamp}. */
    record Parsed(Stamp stamp, PackedRanges ranges, int skipped) {}

    /** Whether what was read from a file can stand for it now: the same file, unchanged since. */
    static boolean fresh(@Nullable Parsed parsed, @Nullable Stamp now) {
        return parsed != null && now != null && parsed.stamp().equals(now);
    }

    private final Settings settings;
    private final Path cache;
    private volatile @Nullable Engine engine;
    private volatile Status status = new Status(0, 0, 0, tr("settings.ipFilter.status.off"));
    /** By path, the lists the last apply used; only {@link #apply} touches it, under its lock. */
    private final Map<Path, Parsed> parsedLists = new HashMap<>();
    /** How many times a list file was really parsed; for the test. */
    private int parses;

    IpFilterController(Settings settings, Path configDir) {
        this.settings = settings;
        this.cache = configDir.resolve(CACHE_FILE);
    }

    void setEngine(Engine engine) {
        this.engine = engine;
    }

    public Status status() {
        return status;
    }

    /** Applies what is on disk (cache, local file, bans) without touching the network. */
    void applyStored(Consumer<String> report) {
        apply(false, report);
    }

    /** Refreshes from the URL if one is set and it is due (or forced), then applies everything. */
    void refresh(boolean force, Consumer<String> report) {
        apply(force || due(), report);
    }

    /**
     * The start's and the schedule's refresh: fetches and applies only when a download is due.
     * {@link #applyStored} has already put the filter in place at start, so applying again with
     * nothing new said "IP filter cleared" twice at every start (and once an hour after) when the
     * filter is off.
     */
    void refreshIfDue(Consumer<String> report) {
        if (fetchDue(
                settings.ipFilterEnabled(),
                settings.ipFilterUrl(),
                settings.ipFilterLastRefresh(),
                settings.ipFilterRefreshHours(),
                System.currentTimeMillis())) apply(true, report);
    }

    private boolean due() {
        return due(settings.ipFilterLastRefresh(), settings.ipFilterRefreshHours(), System.currentTimeMillis());
    }

    /** Whether a list last fetched at {@code last} (epoch ms, 0 for never) is due a new download. */
    static boolean due(long last, int intervalHours, long now) {
        long interval = intervalHours * 3600_000L;
        return last <= 0 || last > now || now - last >= interval;
    }

    /** Whether the schedule has anything to do: the filter on, a URL to fetch from, and due. */
    static boolean fetchDue(boolean enabled, String url, long last, int intervalHours, long now) {
        return enabled && url != null && !url.isEmpty() && due(last, intervalHours, now);
    }

    private synchronized void apply(boolean fetch, Consumer<String> report) {
        Engine e = engine;
        if (e == null) return;
        if (!settings.ipFilterEnabled()) {
            parsedLists.clear();
            try {
                e.setIpFilter(List.of());
            } catch (EngineException ex) {
                report.accept(ex.getMessage());
            }
            status = new Status(0, 0, 0, tr("settings.ipFilter.status.off"));
            return;
        }
        List<Parsed> lists = new ArrayList<>();
        int skipped = 0;
        StringBuilder note = new StringBuilder();

        String url = settings.ipFilterUrl();
        if (fetch && !url.isEmpty()) {
            try {
                byte[] bytes = new BlocklistFetch().fetch(URI.create(url));
                Path tmp = Files.createTempFile(cache.getParent(), "blocklist", ".part");
                Files.write(tmp, bytes);
                Files.move(tmp, cache, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                // The stamp would tell, but a file system with neither inodes nor a fine clock
                // could give a same-sized list the old one's.
                parsedLists.remove(cache.toAbsolutePath());
                settings.setIpFilterLastRefresh(System.currentTimeMillis());
                settings.saveQuietly();
                note.append(tr("settings.ipFilter.status.downloaded"));
            } catch (IOException | RuntimeException ex) {
                note.append(tr(
                        "settings.ipFilter.status.downloadFailed",
                        ex.getMessage() == null ? ex.toString() : ex.getMessage()));
            }
        }
        if (!url.isEmpty() && Files.isRegularFile(cache)) {
            Parsed parsed = load(cache);
            if (parsed != null) lists.add(parsed);
        }
        String file = settings.ipFilterFile();
        if (!file.isEmpty()) {
            Parsed parsed = load(Path.of(file));
            if (parsed == null)
                note.append(note.isEmpty() ? "" : "; ").append(tr("settings.ipFilter.status.localUnreadable"));
            else lists.add(parsed);
        }
        Set<Path> used = new HashSet<>();
        int size = settings.ipFilterBans().size();
        for (Parsed parsed : lists) {
            used.add(parsed.stamp().path());
            size += parsed.ranges().size();
            skipped += parsed.skipped();
        }
        parsedLists.keySet().retainAll(used);
        List<IpRange> ranges = new ArrayList<>(size);
        for (Parsed parsed : lists) parsed.ranges().addTo(ranges);
        for (String ban : settings.ipFilterBans()) {
            IpRange range = Blocklist.parseLine(ban);
            if (range == null) skipped++;
            else ranges.add(range);
        }
        try {
            e.setIpFilter(ranges);
            status = new Status(ranges.size(), skipped, System.currentTimeMillis(), note.toString());
            report.accept(
                    note.isEmpty()
                            ? tr("settings.ipFilter.applied", ranges.size(), skipped)
                            : tr("settings.ipFilter.appliedNote", ranges.size(), skipped, note.toString()));
        } catch (EngineException ex) {
            report.accept(tr("settings.ipFilter.notApplied", ex.getMessage()));
        }
    }

    /** The file's ranges, parsed again only when it changed since the last apply; null if unreadable. */
    private @Nullable Parsed load(Path path) {
        // Stamped before reading, so a write while it is read shows as a change next time.
        Stamp stamp = Stamp.of(path);
        if (stamp == null) {
            parsedLists.remove(path.toAbsolutePath());
            return null;
        }
        Parsed known = parsedLists.get(stamp.path());
        if (fresh(known, stamp)) return known;
        Blocklist.Parsed read = read(path);
        if (read == null) {
            parsedLists.remove(stamp.path());
            return null;
        }
        parses++;
        Parsed parsed = new Parsed(stamp, PackedRanges.of(read.ranges()), read.skipped());
        parsedLists.put(stamp.path(), parsed);
        return parsed;
    }

    synchronized int parsesForTest() {
        return parses;
    }

    /** Line by line, never the whole file in memory: the ranges are what is kept. */
    private static Blocklist.@Nullable Parsed read(Path path) {
        try (InputStream raw = Files.newInputStream(path);
                InputStream in = BlocklistFetch.gunzipIfNeeded(raw);
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
                Stream<String> lines = reader.lines()) {
            return Blocklist.parse(lines);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
