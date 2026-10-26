package com.torrenta.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.torrenta.i18n.Messages.tr;

/**
 * Watches the {@link WatchFolder}s and adds what appears in them, silently: no dialog, one event
 * per file.
 *
 * <p>Two mechanisms, as qBittorrent has them: a {@link WatchService} thread for the folders that
 * can be watched, and a poll every {@value #POLL_SECONDS} seconds as the safety net, since the
 * watch service misses events on network mounts and is a poll itself on macOS. A file is read
 * only once it has stopped growing ({@link WatchFolder.Growth}: the same size at two looks in a
 * row, a second apart after an event), so a {@code .torrent} still being copied in is not read
 * half-way. Then it goes through the engine's own add path ({@link Engine#add(AddRequest)}) and
 * is deleted or renamed as the entry says. A file that is not a torrent is renamed {@code
 * .invalid} once and reported once; a torrent already in the session is reported and the file is
 * still handled; a file that cannot be handled is reported once and left alone until it changes
 * or the watcher restarts. Nothing here is retried every minute.
 *
 * <p>Every engine call happens on the watcher's own scheduler thread; the host's callbacks arrive
 * there too and hop to the FX thread themselves. {@link #poll()} is public so a test can drive it
 * without waiting on the watch service.
 */
public final class WatchFolders implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(WatchFolders.class.getName());

    /** The safety-net poll. */
    public static final long POLL_SECONDS = 60;

    /** The second look after a file was seen, so a drop is picked up in a second, not a minute. */
    public static final long SETTLE_MS = 1000;

    /** What the watcher needs from the application; every method is called on the watcher's thread. */
    public interface Host {
        /** The download folder as it is now. */
        Path defaultDownloadDir();

        /** The category of that name, if it exists. */
        Optional<Category> category(String name);

        /** The storage choices (incomplete folder, suffix, allocation) for a torrent saved there. */
        AddRequest.Storage storage(Path destination);

        /** Something to log: an add, a file that is not a torrent, a folder that is gone. */
        void report(EngineEvent event);

        /** The list of missing folders changed; the Settings page's status line wants to know. */
        default void statusChanged() {}

        /**
         * A source as the add takes it: the application looks an updatable address up for its
         * current version ({@code Session.resolve}); anything else, and by default, as it is.
         * Blocks; a failure is an {@link EngineException}, reported like a failed add.
         */
        default AddRequest.Source resolve(AddRequest.Source source) throws EngineException {
            return source;
        }

        /**
         * The add itself: the engine's, unless the application screens it first (the content
         * check). A refusal is an {@link EngineException}, reported like any failed add.
         */
        default Torrent add(Engine engine, AddRequest request) throws EngineException {
            return engine.add(request);
        }
    }

    private final Engine engine;
    private final Host host;
    private final WatchFolder.Growth growth = new WatchFolder.Growth();
    /** Reported once; skipped until the file is gone or the watcher restarts. */
    private final Set<Path> failed = new HashSet<>();

    private final AtomicBoolean relookDue = new AtomicBoolean();
    private final AtomicBoolean eventDue = new AtomicBoolean();

    private List<WatchFolder> entries = List.of();
    private volatile List<Path> missing = List.of();
    private volatile boolean running;
    private ScheduledExecutorService scheduler;
    private Thread watcher;
    private WatchService service;

    public WatchFolders(Engine engine, Host host) {
        this.engine = engine;
        this.host = host;
    }

    /**
     * Takes the list without starting a thread: what {@link #start} does first, and what a test
     * drives {@link #poll()} against. A folder that does not exist is said once, here.
     */
    public synchronized void configure(List<WatchFolder> list) {
        entries = List.copyOf(list);
        growth.retain(List.of());
        failed.clear();
        List<Path> absent = new ArrayList<>();
        for (WatchFolder entry : entries) if (!Files.isDirectory(entry.path())) absent.add(entry.path());
        missing = List.copyOf(absent);
        for (Path dir : absent)
            host.report(EngineEvent.session(EngineEvent.Level.WARNING, tr("engine.watch.missing", dir)));
    }

    /** Begins watching these folders: the first poll at once, then the service and the schedule. */
    public synchronized void start(List<WatchFolder> list) {
        stop();
        configure(list);
        running = true;
        if (entries.isEmpty()) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "watch-folders");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::pollSafely, 0, POLL_SECONDS, TimeUnit.SECONDS);
        try {
            service = FileSystems.getDefault().newWatchService();
            for (WatchFolder entry : entries) {
                Path dir = entry.path();
                if (!Files.isDirectory(dir)) continue;
                try {
                    dir.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
                } catch (IOException e) {
                    LOG.log(Level.FINE, "Cannot watch " + dir + "; the poll covers it", e);
                }
            }
            WatchService ws = service;
            watcher = new Thread(() -> watch(ws), "watch-folders-events");
            watcher.setDaemon(true);
            watcher.start();
        } catch (IOException | UnsupportedOperationException e) {
            LOG.log(Level.FINE, "No watch service; the poll covers it", e);
        }
    }

    /** Stops the threads; idempotent. The entries are forgotten with the next {@link #start}. */
    public synchronized void stop() {
        running = false;
        if (scheduler != null) scheduler.shutdownNow();
        scheduler = null;
        if (service != null) {
            try {
                service.close();
            } catch (IOException e) {
                LOG.log(Level.FINE, "closing the watch service", e);
            }
        }
        service = null;
        if (watcher != null) watcher.interrupt();
        watcher = null;
    }

    @Override
    public void close() {
        stop();
    }

    public boolean isRunning() {
        return running;
    }

    public List<WatchFolder> entries() {
        return entries;
    }

    /** The folders that did not exist at the last look. */
    public List<Path> missing() {
        return missing;
    }

    /** The Settings page's status line. Pure. */
    public static String statusText(int folders, List<Path> missing, boolean running) {
        if (folders == 0) return tr("engine.watch.none");
        if (!running) return tr("engine.watch.waiting");
        String watching = tr("engine.watch.watching", folders, POLL_SECONDS);
        if (missing.isEmpty()) return watching;
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < missing.size(); i++) {
            if (i > 0) list.append(", ");
            list.append(missing.get(i));
        }
        return watching + " " + tr("engine.watch.notFound", list);
    }

    // ---- the threads ----------------------------------------------------------------------------

    private void watch(WatchService ws) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key = ws.take();
                key.pollEvents();
                key.reset();
                ScheduledExecutorService s = scheduler;
                // Coalesced: copying one file in raises a burst of modify events.
                if (s != null && eventDue.compareAndSet(false, true)) {
                    s.execute(() -> {
                        eventDue.set(false);
                        pollSafely();
                    });
                }
            }
        } catch (InterruptedException | ClosedWatchServiceException e) {
            // stop()
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "The watch service stopped; the poll carries on", e);
        }
    }

    private void pollSafely() {
        boolean again;
        try {
            again = poll();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Watch folder poll failed", e);
            again = false;
        }
        ScheduledExecutorService s = scheduler;
        if (again && s != null && relookDue.compareAndSet(false, true)) {
            try {
                s.schedule(
                        () -> {
                            relookDue.set(false);
                            pollSafely();
                        },
                        SETTLE_MS,
                        TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                relookDue.set(false);
            }
        }
    }

    // ---- one pass -------------------------------------------------------------------------------

    /**
     * Looks at every folder once: files seen at the same size as last time are added and handled,
     * files seen for the first time or still growing are noted. Returns whether some file is
     * waiting for its second look, so the caller knows to come back soon rather than in a minute.
     */
    public synchronized boolean poll() {
        boolean lookAgain = false;
        List<Path> present = new ArrayList<>();
        List<Path> absent = new ArrayList<>();
        for (WatchFolder entry : entries) {
            Path dir = entry.path();
            List<Path> files = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path file : stream) if (WatchFolder.watched(file) && Files.isRegularFile(file)) files.add(file);
            } catch (IOException | RuntimeException e) {
                absent.add(dir);
                continue;
            }
            files.sort(null);
            for (Path file : files) {
                present.add(file);
                if (failed.contains(file)) continue;
                long size;
                try {
                    size = Files.size(file);
                } catch (IOException e) {
                    continue;
                }
                if (!growth.settled(file, size)) {
                    lookAgain = true;
                    continue;
                }
                growth.forget(file);
                handle(entry, file);
            }
        }
        growth.retain(present);
        failed.retainAll(new HashSet<>(present));
        List<Path> before = missing;
        missing = List.copyOf(absent);
        if (!before.equals(missing)) host.statusChanged();
        return lookAgain;
    }

    /** Reads, adds, and deletes or renames; every outcome is one event. */
    private void handle(WatchFolder entry, Path file) {
        String where = entry.path().toString();
        String name = file.getFileName().toString();
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            giveUp(
                    file,
                    EngineEvent.session(
                            EngineEvent.Level.ERROR, tr("engine.watch.couldNotRead", name, where, e.getMessage())));
            return;
        }
        // Parsing first, so a file that is not a torrent is set aside before anything is added.
        List<Candidate> candidates = new ArrayList<>();
        try {
            if (WatchFolder.isMagnetFile(file)) {
                List<String> magnets = WatchFolder.magnetLines(new String(bytes, StandardCharsets.UTF_8));
                if (magnets.isEmpty()) throw new EngineException(tr("engine.watch.noMagnets"));
                for (String magnet : magnets)
                    candidates.add(new Candidate(new AddRequest.Magnet(magnet), WatchFolder.magnetId(magnet)));
            } else {
                TorrentPreview preview = engine.inspect(bytes);
                candidates.add(
                        new Candidate(new AddRequest.TorrentFile(bytes, file.toString()), Optional.of(preview.id())));
            }
        } catch (EngineException | RuntimeException e) {
            setAside(file, name, where, e.getMessage() == null ? e.toString() : e.getMessage());
            return;
        }
        Set<String> present = new LinkedHashSet<>();
        for (Torrent t : engine.torrents()) present.add(t.id());
        boolean trouble = false;
        for (Candidate candidate : candidates) {
            try {
                // An updatable address names no torrent until it is looked up.
                AddRequest.Source source = host.resolve(candidate.source());
                Optional<String> id = candidate.id().isPresent() || !(source instanceof AddRequest.Magnet m)
                        ? candidate.id()
                        : WatchFolder.magnetId(m.uri());
                if (id.isPresent() && present.contains(id.get())) {
                    host.report(EngineEvent.info(id.get(), "", tr("engine.watch.already", name, where)));
                    continue;
                }
                Path destination = entry.destination(host.defaultDownloadDir(), host.category(entry.category()));
                AddRequest request = entry.request(source, destination, host.storage(destination));
                Torrent added = host.add(engine, request);
                present.add(added.id());
                host.report(EngineEvent.info(added.id(), added.name(), tr("engine.watch.added", added.name(), where)));
            } catch (EngineException | RuntimeException e) {
                trouble = true;
                host.report(EngineEvent.session(
                        EngineEvent.Level.ERROR, tr("engine.watch.addFailed", name, where, e.getMessage())));
            }
        }
        if (trouble) {
            // The file stays, so the person can retry by touching it; not every minute by us.
            failed.add(file);
            return;
        }
        try {
            switch (entry.afterAdd()) {
                case DELETE -> Files.deleteIfExists(file);
                case RENAME -> Files.move(file, WatchFolder.handled(file), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            giveUp(
                    file,
                    EngineEvent.session(
                            EngineEvent.Level.WARNING,
                            tr(
                                    entry.afterAdd() == WatchFolder.AfterAdd.DELETE
                                            ? "engine.watch.deleteFailed"
                                            : "engine.watch.renameFailed",
                                    name,
                                    where,
                                    e.getMessage())));
        }
    }

    private record Candidate(AddRequest.Source source, Optional<String> id) {}

    /** Not a torrent: renamed {@code .invalid} so it is never looked at again, and said once. */
    private void setAside(Path file, String name, String where, String reason) {
        String renamed;
        try {
            Files.move(file, WatchFolder.invalid(file), StandardCopyOption.REPLACE_EXISTING);
            renamed = tr("engine.watch.setAside", WatchFolder.invalid(file).getFileName());
        } catch (IOException e) {
            failed.add(file);
            renamed = tr("engine.watch.setAsideFailed", e.getMessage());
        }
        host.report(EngineEvent.session(
                EngineEvent.Level.WARNING, tr("engine.watch.notATorrent", name, where, reason, renamed)));
    }

    private void giveUp(Path file, EngineEvent event) {
        failed.add(file);
        host.report(event);
    }
}
