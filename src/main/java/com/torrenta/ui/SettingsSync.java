package com.torrenta.ui;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.util.Duration;

import com.torrenta.engine.Category;
import com.torrenta.search.TorznabEndpoint;
import com.torrenta.store.DefinitionStore;
import com.torrenta.store.LabelStore;
import com.torrenta.store.SearchProviderStore;
import com.torrenta.store.Settings;
import com.torrenta.sync.GitHubCli;
import com.torrenta.sync.QuietGit;
import com.torrenta.sync.SyncAccount;
import com.torrenta.sync.SyncEngine;
import com.torrenta.sync.SyncKeys;
import com.torrenta.sync.SyncLabels;
import com.torrenta.sync.SyncReport;
import com.torrenta.sync.SyncSearch;
import com.torrenta.sync.SyncTarget;
import org.jspecify.annotations.Nullable;

import static com.torrenta.i18n.Messages.tr;

/**
 * Settings sync for the application, as Editora runs it: decides when a {@link SyncEngine} round
 * happens, runs it off the FX thread, writes what it brings into the live settings, the label
 * store (category names and tags, {@link SyncLabels}) and the search providers ({@link SyncSearch}:
 * Torznab servers without their keys, definition files), and says what happened. Every method is
 * called on the FX thread.
 *
 * <p>Automatic rounds (after startup, {@value #DIRTY_DELAY_SECONDS} s after a local change, and on
 * the interval timer while the window is focused) never ask for anything: git runs without a
 * terminal, an askpass program or a credential window, and a failure is one status line and a
 * status-bar marker, not a dialog. A round the user asked for may use their credential helper.
 */
final class SettingsSync {

    private static final Logger LOG = Logger.getLogger(SettingsSync.class.getName());
    static final int DIRTY_DELAY_SECONDS = 30;
    private static final int STARTUP_DELAY_SECONDS = 5;
    private static final long FX_HOP_SECONDS = 30;

    enum Phase {
        /** Not connected. */
        OFF,
        /** Connected; the last round, if any, went well. */
        IDLE,
        SYNCING,
        /** The last round failed. */
        PROBLEM
    }

    /**
     * @param report the last finished round, or null
     * @param at when it finished, or null
     * @param account the GitHub CLI account the last round signed in as; blank for git's own way
     */
    record State(
            Phase phase,
            @Nullable SyncReport report,
            @Nullable LocalDateTime at,
            String account) {}

    /** What the window does for the service. */
    interface Host {
        /** A line in the status bar. */
        void reportStatus(String text);
        /** The live settings changed under the window: re-apply what they drive. */
        void settingsApplied();

        /**
         * The label store changed under the window: the removed names are gone from it already,
         * and the torrents here that carry them are to let go of them as a deletion on this machine would.
         */
        void labelsApplied(SyncLabels.Change change);

        /** The search providers or the definition files changed under the window. */
        void searchApplied();

        boolean windowFocused();
    }

    private final Settings settings;
    private final LabelStore labels;
    private final SearchProviderStore searchStore;
    private final DefinitionStore definitions;
    private final Path configDir;
    private final Host host;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "settings-sync");
        t.setDaemon(true);
        return t;
    });
    private final PauseTransition dirty = new PauseTransition(Duration.seconds(DIRTY_DELAY_SECONDS));
    private final PauseTransition startup = new PauseTransition(Duration.seconds(STARTUP_DELAY_SECONDS));
    private final List<Runnable> listeners = new ArrayList<>();
    private @Nullable Timeline timer;
    private int timerMinutes;
    private State state = new State(Phase.OFF, null, null, "");
    private boolean running;
    private boolean again;
    /** True while a round's result is being written: those saves are not "the user changed something". */
    private boolean applying;
    /**
     * The synced entries as this service last saw them: at start, when a save moved them, after a
     * good round. A save that leaves them so is no reason for a round.
     */
    private Map<String, String> seen;
    /** Torznab servers the round brought that wait for their key here; said with the round's line. */
    private int keysNeeded;
    /** The failure already reported, so an automatic round that fails the same way every interval says it once. */
    private SyncReport.@Nullable Status reportedFailure;

    private String account = "";
    private boolean accountSearched;
    private boolean remoteFailed;
    private boolean startupDone;
    private volatile String machine = "";

    SettingsSync(
            Settings settings,
            LabelStore labels,
            SearchProviderStore searchStore,
            DefinitionStore definitions,
            Path configDir,
            Host host) {
        this.settings = settings;
        this.labels = labels;
        this.searchStore = searchStore;
        this.definitions = definitions;
        this.configDir = configDir;
        this.host = host;
        this.seen = settings.syncedEntries();
        dirty.setOnFinished(e -> request(false));
        startup.setOnFinished(e -> request(false));
        worker.execute(() -> machine = machineName());
        settingsChanged();
    }

    State state() {
        return state;
    }

    /** Runs {@code listener} on the FX thread after every state change. */
    void addListener(Runnable listener) {
        listeners.add(listener);
    }

    /** Connected: switched on with a usable repository. */
    boolean active() {
        return settings.syncEnabled() && SyncEngine.isUsableUrl(settings.syncUrl());
    }

    /** The folder that holds the clone. */
    Path syncDir() {
        return configDir.resolve("sync");
    }

    private void setState(State next) {
        state = next;
        for (Runnable listener : new ArrayList<>(listeners)) listener.run();
    }

    // ---- triggers -------------------------------------------------------------------------------

    /** Re-reads the sync settings: starts or stops the timers. Call after any {@code sync.*} change. */
    void settingsChanged() {
        boolean auto = active();
        int minutes = auto ? settings.syncIntervalMinutes() : 0;
        if (minutes != timerMinutes) { // an unrelated change must not push the next sync back
            timerMinutes = minutes;
            if (timer != null) {
                timer.stop();
                timer = null;
            }
            if (auto) {
                timer = new Timeline(new KeyFrame(Duration.minutes(minutes), e -> tick()));
                timer.setCycleCount(Animation.INDEFINITE);
                timer.play();
            }
        }
        if (auto) {
            if (!startupDone) {
                startupDone = true;
                startup.playFromStart();
            }
        } else {
            dirty.stop();
            startup.stop();
        }
        if (!auto) {
            reportedFailure = null;
            setState(new State(Phase.OFF, null, null, ""));
        } else if (state.phase() == Phase.OFF) {
            setState(new State(Phase.IDLE, null, null, account));
        }
    }

    private void tick() {
        // A window nobody is looking at does not talk to the network.
        if (host.windowFocused()) request(false);
    }

    /** The settings or the label lists were saved: sync soon, unless the save was a sync's own. */
    void markDirty() {
        if (!applying && active()) dirty.playFromStart();
    }

    /**
     * The settings file was saved: sync soon only if something that travels changed. Most saves
     * are of this machine's keys alone (the divider's place, the window, a torrent's share
     * limit, a refresh clock), and each used to cost a git round 30 s later for nothing.
     */
    void settingsSaved() {
        if (applying || !active()) return;
        Map<String, String> now = settings.syncedEntries();
        if (!SyncKeys.changed(seen, now)) return;
        seen = now;
        dirty.playFromStart();
    }

    /** "Sync now". */
    void syncNow() {
        if (!active()) {
            host.reportStatus(tr("settings.sync.notSetUp"));
            return;
        }
        request(true);
    }

    /** Switches sync off, forgets the repository and removes the clone. */
    void disconnect() {
        settings.setSyncEnabled(false);
        settings.setSyncUrl("");
        saveOwn();
        settingsChanged();
        account = "";
        accountSearched = false;
        Path dir = syncDir();
        worker.execute(() -> {
            try {
                if (Files.exists(dir)) {
                    try (var walk = Files.walk(dir)) {
                        for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
                    }
                }
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Could not remove " + dir, e);
            }
        });
    }

    /**
     * What connecting to {@code url} would bring, without changing anything here; the answer comes
     * back on the FX thread. The user is waiting, so git may use their credential helper.
     */
    void preview(String url, String branch, Consumer<SyncReport> done) {
        worker.execute(() -> {
            SyncReport report = new SyncEngine(
                            gitFor(url, true),
                            url,
                            branch,
                            new LiveTarget(),
                            new LiveLabels(),
                            new LiveSearch(),
                            machine)
                    .preview();
            Platform.runLater(() -> done.accept(report));
        });
    }

    /** Creates a private repository under the GitHub CLI's account; the answer comes back on the FX thread. */
    void createOnGitHub(String name, Consumer<GitHubCli.Created> done) {
        worker.execute(() -> {
            GitHubCli.Created created =
                    GitHubCli.createPrivateRepo(GitHubCli.COMMAND, name, tr("settings.sync.repoDescription"));
            Platform.runLater(() -> done.accept(created));
        });
    }

    void shutdown() {
        dirty.stop();
        startup.stop();
        if (timer != null) timer.stop();
        worker.shutdownNow();
    }

    // ---- one round ------------------------------------------------------------------------------

    private void request(boolean interactive) {
        if (!active()) return;
        if (running) {
            again = true;
            return;
        }
        running = true;
        setState(new State(Phase.SYNCING, state.report(), state.at(), account));
        String url = settings.syncUrl();
        String branch = settings.syncBranch();
        worker.execute(() -> {
            SyncReport report;
            try {
                report = new SyncEngine(
                                gitFor(url, interactive),
                                url,
                                branch,
                                new LiveTarget(),
                                new LiveLabels(),
                                new LiveSearch(),
                                machine)
                        .run();
            } catch (RuntimeException e) {
                report = new SyncReport(SyncReport.Status.FAILED, List.of(), List.of(), List.of(), String.valueOf(e));
            }
            SyncReport finished = report;
            Platform.runLater(() -> finished(finished, interactive));
        });
    }

    private void finished(SyncReport report, boolean interactive) {
        running = false;
        remoteFailed =
                report.status() == SyncReport.Status.FETCH_FAILED || report.status() == SyncReport.Status.PUSH_FAILED;
        String line = null;
        if (report.ok()) {
            seen = settings.syncedEntries(); // what the remote has now, a merge's arrivals included
            settings.setSyncLastEpochMs(System.currentTimeMillis());
            saveOwn();
            reportedFailure = null;
            if (report.changedAnything() || interactive) line = report.summary();
        } else if (interactive || reportedFailure != report.status()) {
            reportedFailure = report.status();
            line = report.summary();
        }
        if (keysNeeded > 0) {
            String keys = tr("sync.report.keysNeeded", keysNeeded);
            line = line == null ? keys : tr("sync.report.andKeys", line, keys);
            keysNeeded = 0;
        }
        if (line != null) host.reportStatus(line);
        setState(new State(report.ok() ? Phase.IDLE : Phase.PROBLEM, report, LocalDateTime.now(), account));
        boolean more = again || report.status() == SyncReport.Status.LOCAL_BUSY;
        again = false;
        if (more && active()) request(false);
    }

    /** The runner for a round: the clone, and the gh account that can read the repository, if one is needed. */
    private QuietGit gitFor(String url, boolean interactive) {
        QuietGit git = new QuietGit(syncDir().resolve("repo"), interactive);
        if (!Files.isDirectory(git.dir().resolve(".git"))) return git; // the engine creates it; no account yet
        String login = SyncAccount.choose(
                git,
                GitHubCli.COMMAND,
                url,
                host -> GitHubCli.logins(GitHubCli.COMMAND, host),
                remoteFailed,
                !accountSearched);
        accountSearched = true;
        account = login;
        if (login.isEmpty()) return git;
        return git.with(SyncAccount.credentialConfig(GitHubCli.COMMAND, SyncAccount.httpsHost(url), login));
    }

    /** A save of the service's own keys: not a reason to sync. */
    private void saveOwn() {
        applying = true;
        try {
            settings.saveQuietly();
        } finally {
            // The save's listener reaches markDirty through runLater; clear the flag behind it.
            Platform.runLater(() -> applying = false);
        }
    }

    /** The live settings, read and written on the FX thread where everything else touches them. */
    private final class LiveTarget implements SyncTarget {
        @Override
        public Map<String, String> read() {
            Map<String, String> out = onFx(settings::syncedEntries);
            return out == null ? Map.of() : out;
        }

        @Override
        public boolean apply(Map<String, String> expected, Map<String, String> merged) {
            Boolean ok = onFx(() -> {
                if (!settings.syncedEntries().equals(expected)) return false;
                applying = true;
                try {
                    settings.replaceSynced(merged);
                    settings.saveQuietly();
                    host.settingsApplied();
                } finally {
                    Platform.runLater(() -> applying = false);
                }
                return true;
            });
            return Boolean.TRUE.equals(ok);
        }
    }

    /**
     * The label store's names, on the FX thread like the settings. A category that arrives gets no
     * save path; one respelled keeps the one it has here.
     */
    private final class LiveLabels implements SyncTarget {
        @Override
        public Map<String, String> read() {
            Map<String, String> out = onFx(this::current);
            return out == null ? Map.of() : out;
        }

        private Map<String, String> current() {
            return SyncLabels.entries(
                    labels.categories().stream().map(Category::name).toList(), labels.tags());
        }

        @Override
        public boolean apply(Map<String, String> expected, Map<String, String> merged) {
            AtomicReference<IOException> failed = new AtomicReference<>();
            Boolean ok = onFx(() -> {
                Map<String, String> now = current();
                if (!now.equals(expected)) return false;
                SyncLabels.Change change = SyncLabels.change(now, merged);
                applying = true;
                try {
                    for (String name : change.putCategories()) {
                        String path =
                                labels.category(name).map(Category::savePath).orElse("");
                        labels.putCategory(new Category(name, path));
                    }
                    for (String name : change.removedCategories()) labels.removeCategory(name);
                    for (String tag : change.putTags()) {
                        labels.removeTag(tag); // a respelling replaces the one equal ignoring case
                        labels.addTags(List.of(tag));
                    }
                    for (String tag : change.removedTags()) labels.removeTag(tag);
                } catch (IOException e) {
                    // Half applied: the base stays, so the next round merges again from what is here.
                    failed.set(e);
                } finally {
                    host.labelsApplied(change);
                    Platform.runLater(() -> applying = false);
                }
                return true;
            });
            if (failed.get() != null) throw new UncheckedIOException(failed.get());
            return Boolean.TRUE.equals(ok);
        }
    }

    /**
     * The Torznab servers and the definition files, on the FX thread where the Search page edits
     * them. Counts the servers that arrive waiting for a key, for the round's status line.
     */
    private final class LiveSearch implements SyncTarget {
        @Override
        public Map<String, String> read() {
            Map<String, String> out = onFx(this::current);
            return out == null ? Map.of() : out;
        }

        private Map<String, String> current() {
            return SyncSearch.entries(searchStore.entries(), definitions.texts(), DefinitionStore.shippedTexts());
        }

        @Override
        public boolean apply(Map<String, String> expected, Map<String, String> merged) {
            AtomicReference<IOException> failed = new AtomicReference<>();
            Boolean ok = onFx(() -> {
                Map<String, String> now = current();
                if (!now.equals(expected)) return false;
                applying = true;
                try {
                    List<TorznabEndpoint> before = searchStore.entries();
                    List<TorznabEndpoint> after = SyncSearch.endpoints(before, merged);
                    if (!after.equals(before)) searchStore.set(after);
                    keysNeeded += SyncSearch.newlyWaiting(before, after);
                    SyncSearch.DefinitionChange change = SyncSearch.definitionChange(now, merged);
                    for (var put : change.put().entrySet()) definitions.put(put.getKey(), put.getValue());
                    for (String id : change.removed()) definitions.remove(id);
                    if (!change.isEmpty()) definitions.load();
                } catch (IOException e) {
                    // Half applied: the base stays, so the next round merges again from what is here.
                    failed.set(e);
                } finally {
                    host.searchApplied();
                    Platform.runLater(() -> applying = false);
                }
                return true;
            });
            if (failed.get() != null) throw new UncheckedIOException(failed.get());
            return Boolean.TRUE.equals(ok);
        }
    }

    private static <T> @Nullable T onFx(java.util.function.Supplier<T> work) {
        if (Platform.isFxApplicationThread()) return work.get();
        AtomicReference<T> out = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                out.set(work.get());
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(FX_HOP_SECONDS, TimeUnit.SECONDS)) return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return out.get();
    }

    private static String machineName() {
        try {
            String name = InetAddress.getLocalHost().getHostName();
            return name == null || name.isBlank() ? "this computer" : name;
        } catch (IOException | RuntimeException e) {
            return "this computer";
        }
    }
}
