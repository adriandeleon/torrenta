package com.torrenta.engine.direct;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.torrenta.engine.EngineEvent;
import com.torrenta.engine.EngineException;
import com.torrenta.engine.Priority;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;
import com.torrenta.engine.TorrentLabels;
import com.torrenta.engine.TorrentState;

import static com.torrenta.i18n.Messages.tr;

/**
 * Downloads from links, each by a curl process of its own, beside whatever engine has the
 * torrents.
 *
 * <p>The list is {@code downloads.json} in the config folder, written whole and atomically when it
 * changes; each download's file grows in its save path under a {@code .part} name and is renamed
 * when curl exits cleanly, to the name the server gave if it gave one. Pausing ends the process
 * and resuming starts a new one that carries on from the end of the file ({@code --continue-at
 * -}); a server that will not resume gets the file started again, once. A few run at once, the
 * rest wait in the order they were added.
 *
 * <p>One thread ({@code direct-downloads}) looks at the files every second for the sizes and
 * rates, starts what may start, and handles every process's exit; the public methods may be
 * called from any thread and take the same lock, never held while a process is waited for long.
 */
public final class DirectDownloads {

    private static final Logger LOG = Logger.getLogger(DirectDownloads.class.getName());

    public static final String FILE = "downloads.json";

    /** Where the headers and error output of each run go, under the config folder. */
    static final String RUNS = "downloads";

    /** How long a stopped curl gets to exit before it is killed, and the file let go of. */
    private static final long EXIT_WAIT_MS = 3000;

    /**
     * What the Settings page says.
     *
     * @param curl the program; empty for {@code curl} on the {@code PATH}
     * @param active downloads running at once, at least 1
     */
    public record Options(String curl, int active) {

        public static final Options DEFAULTS = new Options("", 3);

        public Options {
            curl = curl == null ? "" : curl.strip();
            active = Math.max(1, active);
        }
    }

    private final Path configDir;
    private final Supplier<String> downloadDir;
    private final Supplier<Options> options;
    private final String userAgent;
    private volatile Curl.Proxy proxy = Curl.Proxy.NONE;

    private final Map<String, DirectDownload> downloads = new LinkedHashMap<>();
    private final Map<String, Run> running = new HashMap<>();
    /** The part file's size for every unfinished download, read each second. */
    private final Map<String, Long> sizes = new HashMap<>();
    /**
     * Not to be started: a curl of theirs is being ended, or their file moved, outside the lock.
     * Without it the next tick could start a second curl on the same part file.
     */
    private final java.util.Set<String> held = new java.util.HashSet<>();
    /** Started regardless of the limit. */
    private final java.util.Set<String> forced = new java.util.HashSet<>();

    private final List<Consumer<EngineEvent>> listeners = new CopyOnWriteArrayList<>();
    private volatile Consumer<DirectDownload> onTorrentFile = d -> {};
    private ScheduledExecutorService thread;
    private boolean dirty;

    /** One curl process. Fields other than the process are the ticking thread's, under the lock. */
    private static final class Run {
        final Process process;
        final boolean restarted;
        final long startedAt = System.nanoTime();
        boolean stopping;
        long lastSize;
        long lastAt = System.nanoTime();
        double rate;
        CurlHeaders headers = CurlHeaders.NONE;

        Run(Process process, boolean restarted, long size) {
            this.process = process;
            this.restarted = restarted;
            this.lastSize = size;
        }
    }

    public DirectDownloads(Path configDir, Supplier<String> downloadDir, Supplier<Options> options, String userAgent) {
        this.configDir = configDir;
        this.downloadDir = downloadDir;
        this.options = options;
        this.userAgent = userAgent;
    }

    // ---- life ---------------------------------------------------------------------------------------

    /** Reads the list and starts what was running when the app last stopped. */
    public synchronized void start() {
        downloads.clear();
        Path file = configDir.resolve(FILE);
        if (Files.isRegularFile(file)) {
            try {
                for (DirectDownload d : DirectDownload.parse(Files.readString(file))) downloads.put(d.id(), d);
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "Could not read " + file + "; no downloads from links", e);
            }
        }
        for (DirectDownload d : downloads.values()) {
            if (!d.finished()) sizes.put(d.id(), sizeOf(partOf(d)));
        }
        thread = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "direct-downloads");
            t.setDaemon(true);
            return t;
        });
        thread.scheduleWithFixedDelay(this::tick, 0, 1, TimeUnit.SECONDS);
    }

    /** Ends every curl, keeping the part files for next time, and writes the list. */
    public void stop() {
        List<Run> ending;
        synchronized (this) {
            if (thread != null) thread.shutdownNow();
            thread = null;
            ending = new ArrayList<>(running.values());
            for (Run r : ending) r.stopping = true;
            running.clear();
            save();
        }
        for (Run r : ending) end(r);
    }

    public void addListener(Consumer<EngineEvent> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<EngineEvent> listener) {
        listeners.remove(listener);
    }

    /** Hears a download that turned out to be a {@code .torrent}, on the ticking thread. */
    public void onTorrentFile(Consumer<DirectDownload> handler) {
        this.onTorrentFile = handler == null ? d -> {} : handler;
    }

    /** Applies from the next run of each download: the ones running keep the proxy they had. */
    public void setProxy(Curl.Proxy proxy) {
        this.proxy = proxy == null ? Curl.Proxy.NONE : proxy;
    }

    // ---- the list -----------------------------------------------------------------------------------

    /**
     * Adds a link, to {@code savePath} or the download folder when that is null. A link already in
     * the list is that download, as a torrent added twice is.
     */
    public Torrent add(String url, String savePath, boolean paused) throws EngineException {
        return add(url, savePath, null, paused);
    }

    /**
     * Adds a link as {@link #add(String, String, boolean)} does, under {@code fileName} when one is
     * given: made safe, numbered past a file already there, and kept when the download finishes
     * whatever the server calls it. Null or blank takes the link's own name.
     */
    public synchronized Torrent add(String url, String savePath, String fileName, boolean paused)
            throws EngineException {
        String link = url.strip();
        String id = DirectDownload.idOf(link);
        DirectDownload existing = downloads.get(id);
        if (existing != null) return snapshot(existing);
        String dir = savePath == null || savePath.isBlank() ? downloadDir.get() : savePath;
        try {
            Files.createDirectories(Path.of(dir));
        } catch (IOException | RuntimeException e) {
            throw new EngineException(tr("engine.error.downloadFolder", dir), e);
        }
        boolean chosen = fileName != null && !fileName.isBlank();
        String file = unusedFile(dir, chosen ? DirectNames.safe(fileName.strip()) : DirectNames.fromUrl(link), null);
        DirectDownload d = DirectDownload.of(link, file, dir, Instant.now().getEpochSecond())
                .withPaused(paused);
        if (chosen) d = d.withChosenName(file);
        downloads.put(id, d);
        sizes.put(id, 0L);
        save();
        emit(EngineEvent.of(
                EngineEvent.Kind.ADDED,
                EngineEvent.Level.INFO,
                id,
                d.name(),
                tr("direct.event.added", d.name(), DirectNames.host(link))));
        schedule();
        return snapshot(d);
    }

    public synchronized boolean has(String id) {
        return downloads.containsKey(id);
    }

    public synchronized Optional<DirectDownload> get(String id) {
        return Optional.ofNullable(downloads.get(id));
    }

    /** Every download, in the order they were added. */
    public synchronized List<Torrent> torrents() {
        List<Torrent> out = new ArrayList<>(downloads.size());
        for (DirectDownload d : downloads.values()) out.add(snapshot(d));
        return out;
    }

    /** Bytes per second, all of them together. */
    public synchronized long rate() {
        double sum = 0;
        for (Run r : running.values()) sum += r.rate;
        return Math.round(sum);
    }

    public void remove(String id, boolean deleteFiles) throws EngineException {
        Run ending;
        DirectDownload d;
        synchronized (this) {
            d = require(id);
            ending = running.remove(id);
            if (ending != null) ending.stopping = true;
            downloads.remove(id);
            sizes.remove(id);
            forced.remove(id);
            save();
        }
        if (ending != null) end(ending);
        deleteRunFiles(id);
        if (deleteFiles) {
            try {
                Files.deleteIfExists(partOf(d));
                if (d.finished()) Files.deleteIfExists(Path.of(d.savePath(), d.file()));
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "Could not delete the file of " + d.url(), e);
            }
        }
        emit(EngineEvent.info(
                id, d.name(), tr(deleteFiles ? "engine.event.removedWithFiles" : "engine.event.removed", d.name())));
        synchronized (this) {
            schedule();
        }
    }

    public void pause(String id) throws EngineException {
        Run ending;
        synchronized (this) {
            DirectDownload d = require(id);
            if (d.finished()) return;
            downloads.put(id, d.withPaused(true));
            forced.remove(id);
            ending = stopRun(id);
            save();
        }
        endAndRelease(id, ending);
    }

    /** Back in line; also clears an error, so a failed download is tried again. */
    public synchronized void resume(String id) throws EngineException {
        DirectDownload d = require(id);
        if (d.finished()) return;
        downloads.put(id, d.withPaused(false).withError(""));
        save();
        schedule();
    }

    /** Starts it now, past the limit. */
    public synchronized void forceResume(String id) throws EngineException {
        DirectDownload d = require(id);
        if (d.finished()) return;
        forced.add(id);
        resume(id);
    }

    /** The name shown, the file keeping its own; empty goes back to the file's. */
    public synchronized void rename(String id, String name) throws EngineException {
        DirectDownload d = require(id);
        downloads.put(id, d.withOwnName(name == null ? "" : name));
        save();
    }

    public synchronized void setCategory(String id, String category) throws EngineException {
        DirectDownload d = require(id);
        downloads.put(id, d.withCategory(TorrentLabels.normalizeName(category)));
        save();
    }

    public synchronized void setTags(String id, List<String> tags) throws EngineException {
        DirectDownload d = require(id);
        downloads.put(id, d.withTags(TorrentLabels.normalizeTags(tags)));
        save();
    }

    public synchronized long rateLimit(String id) throws EngineException {
        return require(id).rateLimit();
    }

    /** A new limit: a running download is restarted for it, carrying on where it was. */
    public void setRateLimit(String id, long bytesPerSecond) throws EngineException {
        Run ending;
        synchronized (this) {
            DirectDownload d = require(id);
            if (d.rateLimit() == Math.max(0, bytesPerSecond)) return;
            downloads.put(id, d.withRateLimit(bytesPerSecond));
            ending = stopRun(id);
            save();
        }
        endAndRelease(id, ending);
    }

    /**
     * Moves the file (whole or partial) to {@code destination}, or only points the download
     * there. A file already at the destination is kept and used, never overwritten.
     */
    public void moveStorage(String id, String destination, boolean moveFiles) throws EngineException {
        Run ending;
        DirectDownload d;
        synchronized (this) {
            d = require(id);
            ending = stopRun(id);
            held.add(id);
        }
        try {
            moveFile(id, d, ending, destination, moveFiles);
        } finally {
            release(id);
        }
        emit(EngineEvent.info(
                id, d.name(), tr(moveFiles ? "engine.event.moved" : "engine.event.pointed", d.name(), destination)));
    }

    private void moveFile(String id, DirectDownload d, Run ending, String destination, boolean moveFiles)
            throws EngineException {
        if (ending != null) end(ending);
        Path from = d.finished() ? Path.of(d.savePath(), d.file()) : partOf(d);
        Path to = Path.of(destination).resolve(from.getFileName());
        try {
            Files.createDirectories(Path.of(destination));
            if (moveFiles && Files.exists(from) && !Files.exists(to)) Files.move(from, to);
        } catch (IOException | RuntimeException e) {
            emit(EngineEvent.warning(id, d.name(), tr("engine.event.moveFailed", d.name(), e.getMessage())));
            throw new EngineException(tr("engine.event.moveFailed", d.name(), e.getMessage()), e);
        }
        synchronized (this) {
            DirectDownload now = downloads.get(id);
            if (now != null) {
                downloads.put(id, now.withSavePath(destination));
                if (!now.finished()) sizes.put(id, sizeOf(to));
                save();
            }
        }
    }

    public synchronized TorrentDetail detail(String id) throws EngineException {
        DirectDownload d = require(id);
        Torrent t = snapshot(d);
        Run r = running.get(id);
        long active = r == null ? 0 : (System.nanoTime() - r.startedAt) / 1_000_000_000L;
        TorrentDetail.General general = new TorrentDetail.General(
                id, d.savePath(), false, 0, 0, 0, 0, t.done(), 0, 0, active, 0, 0, r == null ? 0 : 1, d.url(), "", "");
        List<TorrentDetail.FileStatus> files =
                List.of(new TorrentDetail.FileStatus(0, d.onDisk(), t.size(), t.done(), Priority.NORMAL));
        return new TorrentDetail(general, files, List.of(), List.of(), new boolean[0]);
    }

    // ---- the ticking thread -------------------------------------------------------------------------

    private void tick() {
        try {
            synchronized (this) {
                long now = System.nanoTime();
                for (Map.Entry<String, Run> e : running.entrySet()) {
                    DirectDownload d = downloads.get(e.getKey());
                    if (d == null) continue;
                    Run r = e.getValue();
                    long size = sizeOf(partOf(d));
                    double seconds = Math.max(0.001, (now - r.lastAt) / 1e9);
                    double instant = Math.max(0, size - r.lastSize) / seconds;
                    r.rate = r.rate == 0 ? instant : r.rate * 0.5 + instant * 0.5;
                    r.lastSize = size;
                    r.lastAt = now;
                    sizes.put(d.id(), size);
                    r.headers = readHeaders(d.id());
                    if (r.headers.total() >= 0 && r.headers.total() != d.size()) {
                        downloads.put(d.id(), d.withSize(r.headers.total()));
                        dirty = true;
                    }
                }
                for (DirectDownload d : downloads.values()) {
                    if (!d.finished() && !running.containsKey(d.id())) sizes.put(d.id(), sizeOf(partOf(d)));
                }
                if (dirty) save();
                schedule();
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Direct downloads tick failed", e);
        }
    }

    /** Lock held. Starts the downloads that may start: forced ones, then by age up to the limit. */
    private void schedule() {
        if (thread == null) return;
        int slots = options.get().active()
                - (int) running.keySet().stream()
                        .filter(id -> !forced.contains(id))
                        .count();
        for (DirectDownload d : List.copyOf(downloads.values())) {
            if (!waiting(d)) continue;
            boolean force = forced.contains(d.id());
            if (!force && slots <= 0) continue;
            if (launch(d, false) && !force) slots--;
        }
    }

    private boolean waiting(DirectDownload d) {
        return !d.finished()
                && !d.paused()
                && d.error().isEmpty()
                && !running.containsKey(d.id())
                && !held.contains(d.id());
    }

    /** Lock held. Starts curl; false (and the download in error) when it cannot be started. */
    private boolean launch(DirectDownload d, boolean restarted) {
        Path part = partOf(d);
        Path runs = configDir.resolve(RUNS);
        Path headers = runs.resolve(d.id() + ".headers");
        Path errors = runs.resolve(d.id() + ".err");
        Options o = options.get();
        try {
            Files.createDirectories(runs);
            Files.createDirectories(part.getParent());
            Files.deleteIfExists(headers);
            long have = sizeOf(part);
            Curl.Request request = new Curl.Request(d.url(), part, headers, have > 0, d.rateLimit(), proxy, userAgent);
            ProcessBuilder pb = new ProcessBuilder(Curl.command(o.curl()))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(errors.toFile());
            Process process = pb.start();
            try (OutputStream in = process.getOutputStream()) {
                in.write(Curl.config(request).getBytes(StandardCharsets.UTF_8));
            }
            Run run = new Run(process, restarted, have);
            running.put(d.id(), run);
            ScheduledExecutorService t = thread;
            process.onExit().thenRun(() -> {
                if (!t.isShutdown()) t.execute(() -> exited(d.id(), run));
            });
            return true;
        } catch (IOException | RuntimeException e) {
            String program = o.curl().isEmpty() ? "curl" : o.curl();
            String message = e instanceof IOException && !Files.isDirectory(part.getParent())
                    ? tr("direct.error.write")
                    : tr("direct.error.noCurl", program);
            LOG.log(Level.INFO, "Could not start " + program + " for " + d.url(), e);
            fail(d, message);
            return false;
        }
    }

    private void exited(String id, Run run) {
        DirectDownload torrent = null;
        synchronized (this) {
            if (running.get(id) != run || run.stopping) return;
            running.remove(id);
            forced.remove(id);
            DirectDownload d = downloads.get(id);
            if (d == null) return;
            CurlHeaders headers = readHeaders(id);
            Path part = partOf(d);
            long size = sizeOf(part);
            Curl.Outcome outcome = Curl.outcome(
                    run.process.exitValue(), headers, size, run.restarted, DirectNames.host(d.url()), readErrors(id));
            switch (outcome) {
                case Curl.Outcome.Finished f -> {
                    DirectDownload done = finish(d, headers, part);
                    if (done != null && (headers.isTorrent() || done.file().endsWith(".torrent"))) torrent = done;
                }
                case Curl.Outcome.Restart r -> {
                    try {
                        Files.deleteIfExists(part);
                    } catch (IOException e) {
                        LOG.log(Level.FINE, "Could not delete " + part, e);
                    }
                    sizes.put(id, 0L);
                    DirectDownload again = d.withSize(-1);
                    downloads.put(id, again);
                    launch(again, true);
                }
                case Curl.Outcome.Failed failed -> fail(d, failed.message());
            }
            save();
            schedule();
        }
        if (torrent != null) onTorrentFile.accept(torrent);
    }

    /** Lock held. The part renamed to its final name; null (and in error) when it cannot be. */
    private DirectDownload finish(DirectDownload d, CurlHeaders headers, Path part) {
        String wanted =
                d.named() ? d.file() : headers.name().map(DirectNames::safe).orElse(d.file());
        String file = unusedFile(d.savePath(), wanted, d.id());
        Path target = Path.of(d.savePath(), file);
        try {
            if (Files.exists(part)) Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
            else Files.createFile(target);
        } catch (IOException e) {
            try {
                Files.move(part, target);
            } catch (IOException again) {
                fail(d, tr("direct.error.write"));
                return null;
            }
        }
        long size = sizeOf(target);
        DirectDownload done = d.withFile(file).withCompleted(Instant.now().getEpochSecond(), size);
        downloads.put(d.id(), done);
        sizes.remove(d.id());
        deleteRunFiles(d.id());
        emit(EngineEvent.of(
                EngineEvent.Kind.FINISHED,
                EngineEvent.Level.INFO,
                d.id(),
                done.name(),
                tr("engine.event.finished", done.name())));
        return done;
    }

    private void fail(DirectDownload d, String message) {
        downloads.put(d.id(), d.withError(message));
        dirty = true;
        emit(EngineEvent.error(d.id(), d.name(), tr("engine.event.torrentError", d.name(), message)));
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private DirectDownload require(String id) throws EngineException {
        DirectDownload d = downloads.get(id);
        if (d == null) throw new EngineException(tr("direct.error.noSuch", id));
        return d;
    }

    /**
     * Lock held. Marks the run as ended by us, takes it out and holds the download back; the
     * caller ends it unlocked, then {@link #release}s it.
     */
    private Run stopRun(String id) {
        Run r = running.remove(id);
        if (r != null) {
            r.stopping = true;
            held.add(id);
        }
        return r;
    }

    private void endAndRelease(String id, Run ending) {
        if (ending == null) return;
        try {
            end(ending);
        } finally {
            release(id);
        }
    }

    /** The download may start again, and whatever may start does. */
    private synchronized void release(String id) {
        held.remove(id);
        schedule();
    }

    /** Ends a curl and waits for it, so the part file is let go of (Windows) before it is touched. */
    private static void end(Run r) {
        r.process.destroy();
        try {
            if (!r.process.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS)) {
                r.process.destroyForcibly().waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A file name in {@code dir} that neither an existing file (finished or {@code .part}) nor
     * another download there has; {@code self} is the download asking, whose own part is no clash.
     */
    private String unusedFile(String dir, String wanted, String self) {
        DirectDownload own = self == null ? null : downloads.get(self);
        return DirectNames.unused(wanted, name -> {
            for (DirectDownload other : downloads.values()) {
                if (other.id().equals(self) || !other.savePath().equals(dir)) continue;
                if (other.file().equals(name)) return true;
            }
            Path p = Path.of(dir, name);
            if (own != null && own.file().equals(name)) return Files.exists(p);
            return Files.exists(p) || Files.exists(Path.of(dir, name + DirectDownload.PART));
        });
    }

    private static Path partOf(DirectDownload d) {
        return Path.of(d.savePath(), d.onDisk());
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    private CurlHeaders readHeaders(String id) {
        Path p = configDir.resolve(RUNS).resolve(id + ".headers");
        try {
            return Files.isRegularFile(p)
                    ? CurlHeaders.parse(Files.readString(p, StandardCharsets.ISO_8859_1))
                    : CurlHeaders.NONE;
        } catch (IOException | RuntimeException e) {
            return CurlHeaders.NONE;
        }
    }

    private String readErrors(String id) {
        Path p = configDir.resolve(RUNS).resolve(id + ".err");
        try {
            if (!Files.isRegularFile(p)) return "";
            byte[] bytes = Files.readAllBytes(p);
            int from = Math.max(0, bytes.length - 4096);
            return new String(bytes, from, bytes.length - from, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private void deleteRunFiles(String id) {
        Path runs = configDir.resolve(RUNS);
        try {
            Files.deleteIfExists(runs.resolve(id + ".headers"));
            Files.deleteIfExists(runs.resolve(id + ".err"));
        } catch (IOException e) {
            LOG.log(Level.FINE, "Could not tidy the run files of " + id, e);
        }
    }

    /** Lock held. Written beside the file and moved into place. */
    private void save() {
        dirty = false;
        Path file = configDir.resolve(FILE);
        try {
            Files.createDirectories(configDir);
            Path tmp = Files.createTempFile(configDir, FILE, ".part");
            try {
                Files.writeString(tmp, DirectDownload.format(downloads.values()));
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not write " + file, e);
        }
    }

    private void emit(EngineEvent event) {
        for (Consumer<EngineEvent> l : listeners) {
            try {
                l.accept(event);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "A listener failed on " + event, e);
            }
        }
    }

    /** Lock held. The download as the list shows it. */
    private Torrent snapshot(DirectDownload d) {
        Run r = running.get(d.id());
        String shown = d.ownName().isEmpty() && !d.named() && r != null
                ? r.headers.name().map(DirectNames::safe).orElse(d.file())
                : d.name();
        long have = d.finished() ? d.size() : sizes.getOrDefault(d.id(), 0L);
        return snapshot(d, shown, have, r == null ? 0 : Math.round(r.rate), r != null);
    }

    /**
     * The download as a {@link Torrent} row. Pure. Finished is {@link TorrentState#FINISHED};
     * anything else is downloading, paused by a person, in error, or waiting for a slot ({@code
     * queued}). The link's host is its one "tracker", so the sidebar groups downloads by server.
     */
    static Torrent snapshot(DirectDownload d, String shownName, long have, long rate, boolean running) {
        long size = Math.max(0, d.size());
        double progress = d.finished() ? 1.0 : size > 0 ? Math.min(1.0, (double) have / size) : 0;
        String host = DirectNames.host(d.url());
        boolean queued = !d.finished() && !d.paused() && d.error().isEmpty() && !running;
        return new Torrent(
                d.id(),
                shownName,
                d.finished() ? Math.max(size, have) : size,
                have,
                progress,
                d.finished() ? TorrentState.FINISHED : TorrentState.DOWNLOADING,
                running ? rate : 0,
                0,
                0,
                0,
                0,
                have,
                d.added(),
                d.completed(),
                d.url(),
                d.savePath(),
                d.paused(),
                false,
                0,
                d.category(),
                d.tags(),
                d.error(),
                host.isEmpty() ? List.of() : List.of(host),
                false,
                queued,
                -1,
                false,
                false);
    }
}
