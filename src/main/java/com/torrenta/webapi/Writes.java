package com.torrenta.webapi;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.torrenta.engine.AddRequest;
import com.torrenta.engine.Category;
import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineException;
import com.torrenta.engine.FileRenames;
import com.torrenta.engine.ShareLimit;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentLabels;
import com.torrenta.engine.TorrentLimits;
import com.torrenta.engine.transmission.Bencode;
import com.torrenta.http.Form;
import com.torrenta.http.Multipart;
import com.torrenta.http.Request;
import com.torrenta.http.Response;
import com.torrenta.session.AddDefaults;
import org.jspecify.annotations.Nullable;

/**
 * The acting endpoints of Tier 1: {@code torrents/add} and {@code delete}, {@code stop}/{@code
 * start} (4.x's {@code pause}/{@code resume} too), {@code recheck}, {@code setCategory}, {@code
 * createCategory}, {@code addTags}, {@code setShareLimits}, {@code topPrio}, {@code
 * setForceStart}. A torrent a client names that is not here is skipped, as qBittorrent skips it.
 *
 * <p>What the network may hand over is bounded before anything acts on it: {@code urls} are
 * magnets and http(s) links only (a path would read this machine's files), every {@code .torrent}
 * passes {@link Bencode#check} and a size cap first, and {@code savepath} must be inside the
 * allowed roots ({@link SavePaths}), as must a new category's folder, since a category's folder
 * becomes a root itself. An add goes through the session's content check like any add without a
 * dialog.
 */
final class Writes {

    private static final Logger LOG = Logger.getLogger(Writes.class.getName());

    /** A {@code .torrent} is kilobytes; the fetch has the same cap. */
    static final int MAX_TORRENT_BYTES = 10 << 20;

    private final WebApi.Backend backend;
    private final LongSupplier clock;
    private final Reads reads;

    Writes(WebApi.Backend backend, LongSupplier clock, Reads reads) {
        this.backend = backend;
        this.clock = clock;
        this.reads = reads;
    }

    // ---- torrents/add ---------------------------------------------------------------------------

    /** What the request asks for, read and checked before anything is added. Pure but for the roots. */
    record AddPlan(
            Path savePath,
            String category,
            List<String> tags,
            Optional<Boolean> stopped,
            boolean skipCheck,
            Optional<Boolean> sequential,
            Optional<FileRenames.Layout> layout,
            String rename,
            TorrentLimits limits,
            Optional<ShareLimit> share) {}

    Response add(Request request) {
        Engine engine = backend.engine();
        if (engine == null) return Response.text(409, "Torrenta's engine is not running");
        Form p = Reads.params(request);
        List<String> urls = Arrays.stream(p.first("urls").orElse("").split("\\R"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        List<Multipart.Part> files = request.parts().stream()
                .filter(part -> part.isFile() && part.name().equals("torrents"))
                .toList();
        // qBittorrent's answer to an add with nothing in it.
        if (urls.isEmpty() && files.isEmpty()) return Response.text(200, "Fails.");

        AddDefaults defaults = backend.addDefaults();
        String category = p.first("category").orElse("").trim();
        Optional<Category> known = backend.categories().stream()
                .filter(c -> c.name().equalsIgnoreCase(category))
                .findFirst();
        // A category's own folder when no savepath is given, as Torrenta's watch folders and rules do.
        Path base = known.filter(c -> !c.savePath().isEmpty())
                .map(c -> Path.of(c.savePath()))
                .orElse(defaults.savePath());
        String requested = p.first("savepath").orElse("");
        SavePaths.Decision where = SavePaths.resolve(requested, base, backend.allowedRoots());
        if (where.path().isEmpty()) return Response.text(400, where.problem());
        try {
            if (!category.isEmpty() && known.isEmpty()) backend.putCategory(new Category(category));
            List<String> tags = tags(p.first("tags").orElse(""));
            if (!tags.isEmpty()) backend.addTags(tags);
            AddPlan plan = plan(
                    p, where.path().orElseThrow(), known.map(Category::name).orElse(category), tags);
            int added = 0;
            for (String url : urls) if (addOne(engine, source(url), plan, defaults, url)) added++;
            for (Multipart.Part part : files)
                if (addOne(engine, upload(part), plan, defaults, part.fileName())) added++;
            return Response.text(200, added > 0 ? "Ok." : "Fails.");
        } catch (IOException e) {
            LOG.log(Level.WARNING, "An add through Remote access could not file its labels", e);
            return Response.text(500, "Internal Server Error");
        }
    }

    /** The options, in qBittorrent's names; a value that does not read leaves the default. */
    static AddPlan plan(Form p, Path savePath, String category, List<String> tags) {
        Optional<Boolean> stopped = bool(p, "stopped").or(() -> bool(p, "paused"));
        Optional<FileRenames.Layout> layout =
                switch (p.first("contentLayout").orElse("").trim()) {
                    case "Original" -> Optional.of(FileRenames.Layout.ORIGINAL);
                    case "Subfolder" -> Optional.of(FileRenames.Layout.SUBFOLDER);
                    case "NoSubfolder" -> Optional.of(FileRenames.Layout.NO_SUBFOLDER);
                    default ->
                        bool(p, "root_folder")
                                .map(root -> root ? FileRenames.Layout.SUBFOLDER : FileRenames.Layout.NO_SUBFOLDER);
                };
        TorrentLimits limits = new TorrentLimits(
                (int) Math.min(Integer.MAX_VALUE, number(p, "dlLimit", 0)),
                (int) Math.min(Integer.MAX_VALUE, number(p, "upLimit", 0)),
                0,
                0,
                bool(p, "firstLastPiecePrio").orElse(false));
        Optional<ShareLimit> share = share(p, null);
        return new AddPlan(
                savePath,
                category,
                tags,
                stopped,
                bool(p, "skip_checking").orElse(false),
                bool(p, "sequentialDownload"),
                layout,
                p.first("rename").orElse("").trim(),
                limits,
                share);
    }

    /** A url as an add's source: a magnet as it is, an http(s) link fetched; anything else none. */
    private Optional<AddRequest.Source> source(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("magnet:")) return Optional.of(new AddRequest.Magnet(url));
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            LOG.info("Remote access refused a url that is neither a magnet nor http(s): " + url);
            return Optional.empty();
        }
        try {
            return torrent(backend.fetch(URI.create(url)), url);
        } catch (IOException | IllegalArgumentException e) {
            LOG.log(Level.INFO, "Remote access could not fetch " + url, e);
            return Optional.empty();
        }
    }

    private static Optional<AddRequest.Source> upload(Multipart.Part part) {
        return torrent(part.bytes(), part.fileName());
    }

    /** The bytes as a {@code .torrent}, if they read as one within the bounds. */
    static Optional<AddRequest.Source> torrent(byte[] bytes, String origin) {
        if (bytes.length > MAX_TORRENT_BYTES) return Optional.empty();
        try {
            Bencode.check(bytes);
        } catch (IOException e) {
            LOG.info("Remote access refused " + origin + ": " + e.getMessage());
            return Optional.empty();
        }
        return Optional.of(new AddRequest.TorrentFile(bytes, origin));
    }

    private boolean addOne(
            Engine engine, Optional<AddRequest.Source> source, AddPlan plan, AddDefaults defaults, String origin) {
        if (source.isEmpty()) return false;
        AddRequest.Storage storage = defaults.storage(plan.savePath());
        if (plan.layout().isPresent())
            storage = storage.withLayout(plan.layout().get());
        AddRequest request = new AddRequest(
                source.get(),
                plan.savePath(),
                plan.stopped().orElse(defaults.startPaused()),
                plan.sequential().orElse(defaults.sequential()),
                plan.skipCheck(),
                AddRequest.StopCondition.NONE,
                java.util.Map.of(),
                new TorrentLabels(plan.category(), plan.tags(), plan.rename()),
                storage);
        try {
            Torrent t = backend.add(request);
            if (!plan.limits().equals(TorrentLimits.NONE)) engine.setLimits(t.id(), plan.limits());
            plan.share().ifPresent(s -> backend.setShareOverride(t.id(), s));
            return true;
        } catch (EngineException | RuntimeException e) {
            LOG.log(Level.INFO, "Remote access could not add " + origin, e);
            return false;
        }
    }

    // ---- the actions ----------------------------------------------------------------------------

    /** Something to do to each torrent named. */
    @FunctionalInterface
    private interface Each {
        void apply(Engine engine, Torrent torrent) throws EngineException;
    }

    /** {@code hashes} acted on one by one; a failure is logged and the rest go on, as qBittorrent's do. */
    private Response each(Request request, Each action) {
        Engine engine = backend.engine();
        if (engine == null) return Response.text(409, "Torrenta's engine is not running");
        for (Torrent t : reads.targets(Reads.params(request).first("hashes").orElse(""), clock.getAsLong())) {
            try {
                action.apply(engine, t);
            } catch (EngineException | RuntimeException e) {
                LOG.log(Level.INFO, "Remote access: " + request.path() + " failed for " + t.name(), e);
            }
        }
        return Response.text(200, "");
    }

    Response delete(Request request) {
        boolean files = bool(Reads.params(request), "deleteFiles").orElse(false);
        return each(request, (e, t) -> {
            if (files) backend.removeWithFiles(e, t);
            else e.remove(t.id(), false);
            backend.setShareOverride(t.id(), null);
        });
    }

    Response stop(Request request) {
        return each(request, (e, t) -> e.pause(t.id()));
    }

    Response start(Request request) {
        return each(request, (e, t) -> e.resume(t.id()));
    }

    Response recheck(Request request) {
        return each(request, (e, t) -> e.recheck(t.id()));
    }

    /** The category must exist (409 otherwise, qBittorrent's answer); empty files a torrent under none. */
    Response setCategory(Request request) {
        String name = Reads.params(request).first("category").orElse("").trim();
        Optional<Category> known = backend.categories().stream()
                .filter(c -> c.name().equalsIgnoreCase(name))
                .findFirst();
        if (!name.isEmpty() && known.isEmpty()) return Response.text(409, "Incorrect category name");
        String filed = known.map(Category::name).orElse("");
        return each(request, (e, t) -> e.setCategory(t.id(), filed));
    }

    /** A new category; its folder, if it has one, inside the allowed roots like any {@code savepath}. */
    Response createCategory(Request request) {
        Form p = Reads.params(request);
        String name = p.first("category").orElse("").trim();
        if (name.isEmpty()) return Response.text(400, "Bad Request");
        if (backend.categories().stream().anyMatch(c -> c.name().equalsIgnoreCase(name)))
            return Response.text(409, "Unable to create category");
        String path = p.first("savePath").orElse("").trim();
        String kept = "";
        if (!path.isEmpty()) {
            SavePaths.Decision where = SavePaths.resolve(path, backend.defaultSavePath(), backend.allowedRoots());
            if (where.path().isEmpty()) return Response.text(400, where.problem());
            kept = where.path().get().toString();
        }
        try {
            backend.putCategory(new Category(name, kept));
            return Response.text(200, "");
        } catch (IOException | IllegalArgumentException e) {
            LOG.log(Level.WARNING, "Remote access could not create the category " + name, e);
            return Response.text(500, "Internal Server Error");
        }
    }

    Response addTags(Request request) {
        List<String> tags = tags(Reads.params(request).first("tags").orElse(""));
        if (tags.isEmpty()) return Response.text(200, "");
        try {
            backend.addTags(tags);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Remote access could not keep the tags", e);
            return Response.text(500, "Internal Server Error");
        }
        return each(request, (e, t) -> {
            List<String> all = new ArrayList<>(e.tags(t.id()));
            all.addAll(tags);
            e.setTags(t.id(), all);
        });
    }

    /**
     * qBittorrent's numbers: -2 is the global limit, -1 no limit, else the limit (a ratio, minutes).
     * Both at -2 drop the torrent's own limit; otherwise a -2 half takes the global value.
     */
    Response setShareLimits(Request request) {
        Form p = Reads.params(request);
        if (!p.has("ratioLimit") || !p.has("seedingTimeLimit")) return Response.text(400, "Bad Request");
        ShareLimit global = backend.globalShare();
        Optional<ShareLimit> own = share(p, global);
        return each(request, (e, t) -> backend.setShareOverride(t.id(), own.orElse(null)));
    }

    Response topPrio(Request request) {
        return each(request, (e, t) -> e.queueMove(t.id(), Engine.QueueMove.TOP));
    }

    /** Forced past the queue, or back into it. */
    Response setForceStart(Request request) {
        boolean on = bool(Reads.params(request), "value").orElse(false);
        return each(request, (e, t) -> {
            if (on) e.forceResume(t.id());
            else e.resume(t.id());
        });
    }

    // ---- reading the parameters -----------------------------------------------------------------

    /** {@code a, b,c} into its tags; blank ones dropped. */
    static List<String> tags(String text) {
        return Arrays.stream(text.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    static Optional<Boolean> bool(Form p, String name) {
        return p.first(name).map(v -> v.trim().equalsIgnoreCase("true"));
    }

    private static double number(Form p, String name, double fallback) {
        try {
            return Double.parseDouble(p.first(name).orElse("").trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * The torrent's own share limit from {@code ratioLimit} and {@code seedingTimeLimit}; empty
     * when neither is given or both are -2 (the global one). {@code global} fills a -2 half, and is
     * null when nothing is known yet (an add: a -2 half is then no limit of that kind).
     */
    static Optional<ShareLimit> share(Form p, @Nullable ShareLimit global) {
        if (!p.has("ratioLimit") && !p.has("seedingTimeLimit")) return Optional.empty();
        double ratio = number(p, "ratioLimit", -2);
        double minutes = number(p, "seedingTimeLimit", -2);
        if (ratio == -2 && minutes == -2) return Optional.empty();
        ShareLimit g = global == null ? ShareLimit.NONE : global;
        double r = ratio == -2 ? g.ratio() : Math.max(0, ratio);
        int m = minutes == -2 ? g.seedingMinutes() : (int) Math.max(0, Math.min(Integer.MAX_VALUE, minutes));
        return Optional.of(new ShareLimit(r, m, g.action()));
    }
}
