package com.torrenta.sync;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One settings sync between this machine and the user's repository; Editora's engine, for the
 * settings file, the labels file ({@link SyncLabels}) and the search providers ({@link SyncSearch}).
 * Blocking and free of JavaFX; the caller picks the thread.
 *
 * <p>Git only carries the data. Torrenta keeps a clone of its own ({@code sync/repo} in the config
 * directory) that nothing else reads, and never asks git to merge: the working tree is set to what
 * the remote has, the merged settings ({@link SyncMerge}) are written over it, and that is committed
 * and pushed. So there are no conflict markers and no half-finished rebase to recover from.
 *
 * <p>The three sides of the merge are the live settings (mine), the remote branch (theirs) and
 * {@value #BASE_REF}, the commit this machine last finished a sync at (base). The base moves only
 * after the push succeeded and the live settings were written, so a sync interrupted anywhere is
 * simply repeated by the next one: until then this machine's edits still differ from the base and
 * are sent again.
 *
 * <p>The labels file is merged the same way against {@value #LABELS_BASE_REF}, a base of its own: a
 * machine whose last sync was by a build that did not sync labels has a {@value #BASE_REF} whose
 * labels file it never applied, and against that base its own lists would read as removals. With
 * no labels base the merge is a first one, which only adds. The search providers have
 * {@value #SEARCH_BASE_REF} for the same reason. Each is a {@link Part}.
 */
public final class SyncEngine {

    /** The repository layout this build reads and writes ({@code formatVersion} in {@value #FORMAT_FILE}). */
    public static final int FORMAT_VERSION = 1;

    static final String FORMAT_FILE = "torrenta-sync.properties";
    static final String SETTINGS_FILE = "settings.properties";
    static final String LABELS_FILE = "labels.properties";
    static final String BASE_REF = "refs/torrenta/base";
    static final String LABELS_BASE_REF = "refs/torrenta/labels-base";
    static final String SEARCH_BASE_REF = "refs/torrenta/search-base";
    private static final int ATTEMPTS = 3;
    private static final String README = """
            # Torrenta settings

            Written by Torrenta's settings sync. Every computer that connects to this repository keeps
            the settings in `settings.properties` the same, and the category names and tags in
            `labels.properties`. The search providers travel too: the Torznab servers in
            `search-providers.json`, without their API keys, which each computer keeps to itself, and
            the definition files in `search-providers/` that differ from the shipped examples. Edit
            them by hand if you like; the next sync on each computer merges the change, one key, server
            or file at a time.
            """;

    private final QuietGit git;
    private final Path clone;
    private final String url;
    private final String branch;
    private final SyncTarget target;
    private final List<Part> parts;
    private final String machine;

    /** A command git refused; the message is its output. */
    static final class GitFailure extends Exception {
        GitFailure(String message) {
            super(message);
        }
    }

    /**
     * @param git a runner whose directory is the clone
     * @param machine this computer's name, for the commit message
     */
    public SyncEngine(QuietGit git, String url, String branch, SyncTarget target, String machine) {
        this(git, url, branch, target, null, machine);
    }

    /**
     * @param labels the category names and tags as {@link SyncLabels#entries}, or null to leave the
     *     repository's labels file as it is
     */
    public SyncEngine(QuietGit git, String url, String branch, SyncTarget target, SyncTarget labels, String machine) {
        this(git, url, branch, target, labels, null, machine);
    }

    /**
     * @param search the search providers as {@link SyncSearch#entries}, or null to leave the
     *     repository's as they are
     */
    public SyncEngine(
            QuietGit git,
            String url,
            String branch,
            SyncTarget target,
            SyncTarget labels,
            SyncTarget search,
            String machine) {
        this.git = git;
        this.clone = git.dir();
        this.url = url == null ? "" : url.strip();
        this.branch = branch == null || branch.isBlank() ? "main" : branch.strip();
        this.target = target;
        List<Part> parts = new ArrayList<>();
        if (labels != null) parts.add(labelsPart(labels));
        if (search != null) parts.add(searchPart(search));
        this.parts = List.copyOf(parts);
        this.machine = machine == null || machine.isBlank() ? "this computer" : machine.strip();
    }

    /** Whether {@code url} can be handed to git as a remote: not blank, not something git reads as an option. */
    public static boolean isUsableUrl(String url) {
        return url != null && !url.isBlank() && !url.strip().startsWith("-") && !url.contains("\n");
    }

    /**
     * Whether {@code url} carries a secret: a password after the user name ({@code https://me:TOKEN@host/…}),
     * or a user name that is itself an access token. Sync refuses such a URL: it would sit in plain
     * text in the settings file and the clone's configuration. An SSH user is never a secret. Pure.
     */
    public static boolean hasCredentials(String url) {
        if (url == null) return false;
        Matcher m =
                Pattern.compile("^\\s*([A-Za-z][A-Za-z0-9+.-]*)://([^/@?#]*)@").matcher(url);
        if (!m.find()) return false;
        String scheme = m.group(1).toLowerCase(Locale.ROOT);
        String userInfo = m.group(2);
        int colon = userInfo.indexOf(':');
        if (colon >= 0) return colon < userInfo.length() - 1;
        return (scheme.equals("http") || scheme.equals("https")) && looksLikeToken(userInfo);
    }

    private static boolean looksLikeToken(String user) {
        return user.matches("(ghp|gho|ghu|ghs|ghr)_\\w+|github_pat_\\w+|glpat-[\\w-]+") || user.matches("[\\w-]{30,}");
    }

    /** Whether {@code branch} is a plain branch name. */
    public static boolean isUsableBranch(String branch) {
        return branch != null
                && branch.matches("[A-Za-z0-9._/-]+")
                && !branch.startsWith("-")
                && !branch.contains("..")
                && !branch.startsWith("/")
                && !branch.endsWith("/")
                && !branch.endsWith(".lock");
    }

    /** A sync. */
    public SyncReport run() {
        return run(false);
    }

    /**
     * What a sync would do, without writing anything here or pushing anything: the repository is
     * fetched and merged in memory only. For showing what connecting brings before it does.
     */
    public SyncReport preview() {
        return run(true);
    }

    private SyncReport run(boolean previewOnly) {
        if (!isUsableUrl(url) || !isUsableBranch(branch)) {
            return SyncReport.failure(SyncReport.Status.FAILED, "invalid repository URL or branch");
        }
        if (!QuietGit.available()) return SyncReport.failure(SyncReport.Status.NO_GIT, "");
        try {
            prepareClone();
            for (int attempt = 1; ; attempt++) {
                SyncReport report = cycle(previewOnly);
                if (report != null) return report;
                if (attempt == ATTEMPTS) {
                    return SyncReport.failure(SyncReport.Status.PUSH_FAILED, "the repository kept changing");
                }
            }
        } catch (GitFailure e) {
            return SyncReport.failure(SyncReport.Status.FAILED, e.getMessage());
        } catch (IOException | RuntimeException e) {
            return SyncReport.failure(SyncReport.Status.FAILED, String.valueOf(e.getMessage()));
        }
    }

    /** One fetch-merge-push round; null when the push lost a race and the round must be repeated. */
    private SyncReport cycle(boolean previewOnly) throws IOException, GitFailure {
        Proc.Result fetch = git.network("fetch", "--prune", "--quiet", "origin");
        if (!fetch.ok()) return SyncReport.failure(SyncReport.Status.FETCH_FAILED, fetch.transcript());
        String remoteRef = "refs/remotes/origin/" + branch;
        boolean remoteHas = exists(remoteRef);
        Map<String, String> theirs;
        if (remoteHas) {
            must(git.run("checkout", "--quiet", "--force", "-B", branch, remoteRef));
            must(git.run("clean", "-fdq"));
            if (formatVersion(clone.resolve(FORMAT_FILE)) > FORMAT_VERSION) {
                return SyncReport.failure(SyncReport.Status.NEWER_FORMAT, "");
            }
            theirs = readSettings(clone.resolve(SETTINGS_FILE));
        } else {
            // An empty repository (or a new branch). Whatever an earlier, unpushed attempt left in
            // the clone is not the remote's: start from nothing.
            must(git.run("symbolic-ref", "HEAD", "refs/heads/" + branch));
            if (exists("HEAD")) must(git.run("update-ref", "-d", "refs/heads/" + branch));
            must(git.run("read-tree", "--empty"));
            must(git.run("clean", "-fdxq"));
            theirs = Map.of();
        }
        Map<String, String> base = null;
        if (exists(BASE_REF)) {
            Proc.Result shown = git.run("show", BASE_REF + ":" + SETTINGS_FILE);
            base = shown.ok() ? SyncKeys.parse(shown.out()) : Map.of();
        }
        Map<String, String> mine = target.read();
        SyncMerge.Result merged = SyncMerge.merge(base, mine, theirs);
        List<Round> rounds = new ArrayList<>();
        for (Part part : parts) {
            Map<String, String> partBase =
                    exists(part.baseRef()) ? part.reader().read(commitTree(part.baseRef())) : null;
            Map<String, String> partTheirs = remoteHas ? part.reader().read(workTree()) : Map.of();
            Map<String, String> partMine = part.target().read();
            rounds.add(new Round(part, partMine, SyncMerge.merge(partBase, partMine, partTheirs)));
        }
        List<SyncMerge.Result> results = new ArrayList<>(List.of(merged));
        for (Round round : rounds) results.add(round.merged());
        if (previewOnly) return ok(results);
        if (!merged.merged().equals(mine) && !target.apply(mine, merged.merged())) {
            return SyncReport.failure(SyncReport.Status.LOCAL_BUSY, "");
        }
        for (Round round : rounds) {
            Map<String, String> next = round.merged().merged();
            if (!next.equals(round.mine()) && !round.part().target().apply(round.mine(), next)) {
                return SyncReport.failure(SyncReport.Status.LOCAL_BUSY, "");
            }
        }
        Files.writeString(clone.resolve(SETTINGS_FILE), SyncKeys.render(merged.merged()));
        for (Round round : rounds)
            round.part().writer().write(clone, round.merged().merged());
        writeIfAbsent(FORMAT_FILE, "formatVersion=" + FORMAT_VERSION + "\n");
        writeIfAbsent(".gitattributes", "* -text\n");
        writeIfAbsent("README.md", README);
        must(git.run("add", "--all"));
        if (!must(git.run("status", "--porcelain")).out().isBlank()) {
            List<String> commit = new ArrayList<>();
            if (!git.run("config", "--get", "user.email").ok()) {
                commit.addAll(List.of("-c", "user.name=Torrenta", "-c", "user.email=torrenta@localhost"));
            }
            commit.addAll(List.of("commit", "--quiet", "--no-verify", "-m", "Sync from " + machine));
            must(git.run(commit.toArray(String[]::new)));
        }
        if (!remoteHas || !rev("HEAD").equals(rev(remoteRef))) {
            Proc.Result push = git.network("push", "--quiet", "origin", "HEAD:refs/heads/" + branch);
            if (!push.ok()) {
                if (nonFastForward(push)) return null; // another machine pushed first; merged next round
                // The live settings already hold the merge; the base stays, so the next sync sends again.
                return new SyncReport(
                        SyncReport.Status.PUSH_FAILED,
                        all(results, SyncMerge.Result::received),
                        List.of(),
                        all(results, SyncMerge.Result::conflicts),
                        push.transcript());
            }
        }
        must(git.run("update-ref", BASE_REF, "HEAD"));
        for (Round round : rounds) must(git.run("update-ref", round.part().baseRef(), "HEAD"));
        return ok(results);
    }

    private static SyncReport ok(List<SyncMerge.Result> results) {
        return new SyncReport(
                SyncReport.Status.OK,
                all(results, SyncMerge.Result::received),
                all(results, SyncMerge.Result::sent),
                all(results, SyncMerge.Result::conflicts),
                "");
    }

    /** The settings' keys, then each part's. */
    private static List<String> all(List<SyncMerge.Result> results, Function<SyncMerge.Result, List<String>> part) {
        List<String> out = new ArrayList<>();
        for (SyncMerge.Result r : results) out.addAll(part.apply(r));
        return List.copyOf(out);
    }

    // ---- the parts ------------------------------------------------------------------------------

    /** A part's entries out of a tree. */
    @FunctionalInterface
    interface Reader {
        Map<String, String> read(RepoTree tree) throws IOException;
    }

    /** A part's merged entries into the clone's working tree. */
    @FunctionalInterface
    interface Writer {
        void write(Path clone, Map<String, String> entries) throws IOException;
    }

    /** What is merged beside the settings, against a base of its own, and where it sits in the repository. */
    record Part(String baseRef, SyncTarget target, Reader reader, Writer writer) {}

    private record Round(Part part, Map<String, String> mine, SyncMerge.Result merged) {}

    private static Part labelsPart(SyncTarget labels) {
        return new Part(
                LABELS_BASE_REF,
                labels,
                tree -> SyncLabels.parse(tree.file(LABELS_FILE)),
                (clone, entries) -> Files.writeString(clone.resolve(LABELS_FILE), SyncKeys.render(entries)));
    }

    private static Part searchPart(SyncTarget search) {
        return new Part(SEARCH_BASE_REF, search, SyncSearch::read, SyncEngine::writeSearch);
    }

    /**
     * The endpoints file and the definition files for {@code entries}: an absent endpoint list is no
     * file, and a definition file the merge has not got is deleted (only the names a definition can
     * have: anything else in the folder was put there by hand and is left alone).
     */
    private static void writeSearch(Path clone, Map<String, String> entries) throws IOException {
        Path torznab = clone.resolve(SyncSearch.TORZNAB_FILE);
        String text = SyncSearch.torznabFile(entries);
        if (text == null) Files.deleteIfExists(torznab);
        else Files.writeString(torznab, text);
        Map<String, String> files = SyncSearch.definitionFiles(entries);
        Path folder = clone.resolve(SyncSearch.DEFINITIONS);
        for (String name : listFiles(folder)) {
            String stem = name.endsWith(".json") ? name.substring(0, name.length() - 5) : "";
            if (SyncSearch.isDefinitionId(stem) && !files.containsKey(name)) Files.delete(folder.resolve(name));
        }
        if (!files.isEmpty()) Files.createDirectories(folder);
        for (var file : files.entrySet()) Files.writeString(folder.resolve(file.getKey()), file.getValue());
    }

    private RepoTree workTree() {
        return new RepoTree() {
            @Override
            public String file(String path) throws IOException {
                Path file = clone.resolve(path);
                return Files.isRegularFile(file) ? Files.readString(file) : null;
            }

            @Override
            public List<String> list(String folder) throws IOException {
                return listFiles(clone.resolve(folder));
            }
        };
    }

    /** A commit's tree, through git: the base a part was last synced at. */
    private RepoTree commitTree(String ref) {
        return new RepoTree() {
            @Override
            public String file(String path) {
                Proc.Result shown = git.run("show", ref + ":" + path);
                return shown.ok() ? shown.out() : null;
            }

            @Override
            public List<String> list(String folder) throws IOException {
                Proc.Result listed = git.run("ls-tree", "--name-only", ref, folder);
                if (!listed.ok()) return List.of();
                List<String> out = new ArrayList<>();
                for (String line : listed.out().split("\\R")) {
                    if (line.startsWith(folder) && line.length() > folder.length()) {
                        out.add(line.substring(folder.length()));
                    }
                }
                return out;
            }
        };
    }

    private static List<String> listFiles(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) return List.of();
        try (var list = Files.list(folder)) {
            return list.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    static boolean nonFastForward(Proc.Result push) {
        String text = (push.err() + "\n" + push.out()).toLowerCase(Locale.ROOT);
        return text.contains("non-fast-forward") || text.contains("fetch first") || text.contains("[rejected]");
    }

    /** Creates the clone on first use, and points it at the URL; a different repository forgets the base. */
    private void prepareClone() throws IOException, GitFailure {
        Files.createDirectories(clone);
        if (!Files.exists(clone.resolve(".git"))) must(git.run("init", "--quiet"));
        // The URL as stored: "remote get-url" would apply the user's url.<base>.insteadOf rewriting,
        // and a rewritten URL never equals the configured one.
        Proc.Result current = git.run("config", "--local", "--get", "remote.origin.url");
        if (!current.ok()) {
            must(git.run("remote", "add", "origin", url));
        } else if (!current.out().strip().equals(url)) {
            // What was synced with the old repository says nothing about this one: a first sync.
            must(git.run("remote", "set-url", "origin", url));
            if (exists(BASE_REF)) must(git.run("update-ref", "-d", BASE_REF));
            for (String ref : List.of(LABELS_BASE_REF, SEARCH_BASE_REF)) {
                if (exists(ref)) must(git.run("update-ref", "-d", ref));
            }
            git.run("config", "--local", "--unset-all", SyncAccount.KEY); // absent is fine
        }
    }

    private boolean exists(String ref) {
        return git.run("rev-parse", "--verify", "--quiet", ref + "^{commit}").ok();
    }

    private String rev(String ref) throws GitFailure {
        return must(git.run("rev-parse", "--verify", ref + "^{commit}")).out().strip();
    }

    private static Proc.Result must(Proc.Result result) throws GitFailure {
        if (!result.ok()) throw new GitFailure(result.transcript());
        return result;
    }

    private void writeIfAbsent(String name, String text) throws IOException {
        Path path = clone.resolve(name);
        if (!Files.exists(path)) Files.writeString(path, text);
    }

    private static Map<String, String> readSettings(Path file) throws IOException {
        return Files.isRegularFile(file) ? SyncKeys.parse(Files.readString(file)) : Map.of();
    }

    /** The format version a repository declares; 0 when it declares none. */
    static int formatVersion(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return 0;
        for (String line : Files.readAllLines(file)) {
            String[] kv = line.split("=", 2);
            if (kv.length == 2 && kv[0].strip().equals("formatVersion")) {
                try {
                    return Integer.parseInt(kv[1].strip());
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 0;
    }
}
