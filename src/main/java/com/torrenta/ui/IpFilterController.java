package com.torrenta.ui;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.torrenta.engine.Blocklist;
import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineException;
import com.torrenta.engine.IpRange;
import com.torrenta.net.BlocklistFetch;
import com.torrenta.store.Settings;

import static com.torrenta.i18n.Messages.tr;

/**
 * Keeps the engine's IP filter in step with the settings: the downloaded blocklist (cached beside
 * the settings, refreshed on a schedule), a local list file, and the manual bans.
 *
 * <p>Every method here runs on a worker; the window hands results to the FX thread through the
 * {@code report} callback. Nothing is fatal: a list that cannot be fetched or read leaves the
 * previous filter in place and says so.
 */
final class IpFilterController {

    static final String CACHE_FILE = "blocklist.txt";

    /** What the last apply did, for the page's status line. */
    record Status(int ranges, int skipped, long loadedAt, String note) {}

    private final Settings settings;
    private final Path cache;
    private volatile Engine engine;
    private volatile Status status = new Status(0, 0, 0, tr("settings.ipFilter.status.off"));

    IpFilterController(Settings settings, Path configDir) {
        this.settings = settings;
        this.cache = configDir.resolve(CACHE_FILE);
    }

    void setEngine(Engine engine) {
        this.engine = engine;
    }

    Status status() {
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

    private boolean due() {
        long last = settings.ipFilterLastRefresh();
        long interval = settings.ipFilterRefreshHours() * 3600_000L;
        long now = System.currentTimeMillis();
        return last <= 0 || last > now || now - last >= interval;
    }

    private synchronized void apply(boolean fetch, Consumer<String> report) {
        Engine e = engine;
        if (e == null) return;
        if (!settings.ipFilterEnabled()) {
            try {
                e.setIpFilter(List.of());
            } catch (EngineException ex) {
                report.accept(ex.getMessage());
            }
            status = new Status(0, 0, 0, tr("settings.ipFilter.status.off"));
            return;
        }
        List<IpRange> ranges = new ArrayList<>();
        int skipped = 0;
        StringBuilder note = new StringBuilder();

        String url = settings.ipFilterUrl();
        if (fetch && !url.isEmpty()) {
            try {
                byte[] bytes = new BlocklistFetch().fetch(URI.create(url));
                Path tmp = Files.createTempFile(cache.getParent(), "blocklist", ".part");
                Files.write(tmp, bytes);
                Files.move(tmp, cache, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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
            Blocklist.Parsed parsed = read(cache);
            if (parsed != null) {
                ranges.addAll(parsed.ranges());
                skipped += parsed.skipped();
            }
        }
        String file = settings.ipFilterFile();
        if (!file.isEmpty()) {
            Blocklist.Parsed parsed = read(Path.of(file));
            if (parsed == null)
                note.append(note.isEmpty() ? "" : "; ").append(tr("settings.ipFilter.status.localUnreadable"));
            else {
                ranges.addAll(parsed.ranges());
                skipped += parsed.skipped();
            }
        }
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

    private static Blocklist.Parsed read(Path path) {
        try {
            byte[] bytes = BlocklistFetch.gunzipIfNeeded(Files.readAllBytes(path));
            try (Stream<String> lines = new String(bytes, StandardCharsets.UTF_8).lines()) {
                return Blocklist.parse(lines);
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
