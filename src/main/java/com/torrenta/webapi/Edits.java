package com.torrenta.webapi;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.torrenta.engine.Category;
import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineException;
import com.torrenta.engine.Priority;
import com.torrenta.engine.QueueOrder;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;
import com.torrenta.engine.TorrentLimits;
import com.torrenta.http.Form;
import com.torrenta.http.Request;
import com.torrenta.http.Response;

/**
 * Tier 2's torrent edits, the ones a remote app's detail screens make: file priorities, the
 * location, names, trackers, per-torrent limits and switches, the queue, and the categories and
 * tags themselves. An edit the engine has no capability for is qBittorrent's 409 with the reason,
 * never imitated; a new location, like a save path, must be inside the allowed roots ({@link
 * SavePaths}), and so must a category's new folder.
 */
final class Edits {

    private static final Logger LOG = Logger.getLogger(Edits.class.getName());

    private final WebApi.Backend backend;
    private final LongSupplier clock;
    private final Reads reads;

    Edits(WebApi.Backend backend, LongSupplier clock, Reads reads) {
        this.backend = backend;
        this.clock = clock;
        this.reads = reads;
    }

    @FunctionalInterface
    private interface Each {
        void apply(Engine engine, Torrent torrent) throws EngineException;
    }

    @FunctionalInterface
    private interface One {
        Response apply(Engine engine, Torrent torrent, Form params) throws EngineException;
    }

    private static Response unsupported() {
        return Response.text(409, "Not supported by this engine");
    }

    private static Response noEngine() {
        return Response.text(409, "Torrenta's engine is not running");
    }

    /** Every torrent in {@code hashes}, in the order given, a failure logged and the rest done. */
    private Response each(Request request, Engine.Capability needs, List<Torrent> ordered, Each action) {
        Engine engine = backend.engine();
        if (engine == null) return noEngine();
        if (needs != null && engine.capabilities().lacks(needs)) return unsupported();
        for (Torrent t : ordered) {
            try {
                action.apply(engine, t);
            } catch (EngineException | RuntimeException e) {
                LOG.log(Level.INFO, "Remote access: " + request.path() + " failed for " + t.name(), e);
            }
        }
        return Response.text(200, "");
    }

    private Response each(Request request, Engine.Capability needs, Each action) {
        return each(request, needs, targets(request), action);
    }

    private List<Torrent> targets(Request request) {
        return reads.targets(Reads.params(request).first("hashes").orElse(""), clock.getAsLong());
    }

    /** One torrent by {@code hash}; its failure is the answer, 409 with the engine's reason. */
    private Response one(Request request, Engine.Capability needs, One action) {
        Engine engine = backend.engine();
        if (engine == null) return noEngine();
        if (needs != null && engine.capabilities().lacks(needs)) return unsupported();
        Form p = Reads.params(request);
        Optional<String> hash = p.first("hash");
        if (hash.isEmpty() || hash.get().isBlank()) return Response.text(400, "Bad Request");
        List<Torrent> found = reads.targets(hash.get().trim(), clock.getAsLong());
        if (found.size() != 1) return Response.text(404, "Not Found");
        try {
            return action.apply(engine, found.getFirst(), p);
        } catch (EngineException e) {
            return Response.text(409, e.getMessage() == null ? "Conflict" : e.getMessage());
        }
    }

    // ---- files, location, names -----------------------------------------------------------------

    /** qBittorrent's priorities onto Torrenta's ladder: 0 skip, 1 normal, 6 and 7 high. */
    static Optional<Priority> priority(String text) {
        return switch (text.trim()) {
            case "0" -> Optional.of(Priority.IGNORE);
            case "1" -> Optional.of(Priority.NORMAL);
            case "6", "7" -> Optional.of(Priority.HIGH);
            default -> Optional.empty();
        };
    }

    Response filePrio(Request request) {
        return one(request, null, (e, t, p) -> {
            Optional<Priority> priority = priority(p.first("priority").orElse(""));
            if (priority.isEmpty()) return Response.text(400, "Priority is not valid");
            TorrentDetail detail = e.detail(t.id());
            if (detail.files().isEmpty()) return Response.text(409, "Torrent's metadata has not yet downloaded");
            List<Integer> known =
                    detail.files().stream().map(TorrentDetail.FileStatus::index).toList();
            List<Integer> ids = new ArrayList<>();
            for (String s : p.first("id").orElse("").split("\\|")) {
                try {
                    int id = Integer.parseInt(s.trim());
                    if (!known.contains(id)) return Response.text(409, "File IDs are not valid");
                    ids.add(id);
                } catch (NumberFormatException ex) {
                    return Response.text(400, "File IDs are not valid");
                }
            }
            for (int id : ids) e.setFilePriority(t.id(), id, priority.get());
            return Response.text(200, "");
        });
    }

    /** Moves the files; the new folder must be inside the allowed roots. */
    Response setLocation(Request request) {
        String location = Reads.params(request).first("location").orElse("").trim();
        if (location.isEmpty()) return Response.text(400, "Save path is empty");
        SavePaths.Decision where = SavePaths.resolve(location, backend.defaultSavePath(), backend.allowedRoots());
        if (where.path().isEmpty()) return Response.text(400, where.problem());
        String to = where.path().get().toString();
        return each(request, Engine.Capability.MOVE_STORAGE, (e, t) -> e.moveStorage(t.id(), to, true));
    }

    Response rename(Request request) {
        return one(request, Engine.Capability.RENAME_TORRENT, (e, t, p) -> {
            String name = p.first("name").orElse("").trim();
            if (name.isEmpty()) return Response.text(409, "Incorrect torrent name");
            e.rename(t.id(), name);
            return Response.text(200, "");
        });
    }

    /**
     * {@code renameFile} and {@code renameFolder}: Torrenta renames in place, so {@code newPath}
     * must differ from {@code oldPath} in its last segment alone; a move to another folder is 409.
     */
    Response renamePath(Request request) {
        return one(request, null, (e, t, p) -> {
            String from = slashes(p.first("oldPath").orElse(""));
            String to = slashes(p.first("newPath").orElse(""));
            if (from.isEmpty() || to.isEmpty()) return Response.text(400, "Bad Request");
            if (!parent(from).equals(parent(to))) return Response.text(409, "Only a rename in place is supported");
            String name = to.substring(to.lastIndexOf('/') + 1);
            e.renamePath(t.id(), from, name);
            return Response.text(200, "");
        });
    }

    private static String slashes(String path) {
        String p = path.trim().replace('\\', '/');
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    private static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    // ---- trackers -------------------------------------------------------------------------------

    Response addTrackers(Request request) {
        return one(request, null, (e, t, p) -> {
            List<String> urls = Arrays.stream(p.first("urls").orElse("").split("\\R"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            if (!urls.isEmpty()) e.addTrackers(t.id(), urls);
            return Response.text(200, "");
        });
    }

    Response removeTrackers(Request request) {
        return one(request, null, (e, t, p) -> {
            for (String url : p.first("urls").orElse("").split("\\|"))
                if (!url.isBlank()) e.removeTracker(t.id(), url.trim());
            return Response.text(200, "");
        });
    }

    /** The tracker replaced by another, on its own tier: libtorrent edits no URL in place. */
    Response editTracker(Request request) {
        return one(request, null, (e, t, p) -> {
            String from = p.first("origUrl").orElse("").trim();
            String to = p.first("newUrl").orElse("").trim();
            if (to.isEmpty()) return Response.text(400, "New tracker URL is empty");
            boolean known =
                    e.detail(t.id()).trackers().stream().anyMatch(tr -> tr.url().equals(from));
            if (!known) return Response.text(409, "Tracker not found");
            e.removeTracker(t.id(), from);
            e.addTrackers(t.id(), List.of(to));
            return Response.text(200, "");
        });
    }

    // ---- limits, switches, the queue ------------------------------------------------------------

    private static long bytes(Form p) {
        try {
            return Long.parseLong(p.first("limit").orElse("").trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    Response setDownloadLimit(Request request) {
        int limit = (int) Math.max(0, Math.min(Integer.MAX_VALUE, bytes(Reads.params(request))));
        return each(request, null, (e, t) -> {
            TorrentLimits l = e.limits(t.id());
            e.setLimits(
                    t.id(),
                    new TorrentLimits(
                            limit, l.uploadLimit(), l.maxConnections(), l.maxUploads(), l.firstAndLastFirst()));
            reads.forget(t);
        });
    }

    Response setUploadLimit(Request request) {
        int limit = (int) Math.max(0, Math.min(Integer.MAX_VALUE, bytes(Reads.params(request))));
        return each(request, null, (e, t) -> {
            TorrentLimits l = e.limits(t.id());
            e.setLimits(
                    t.id(),
                    new TorrentLimits(
                            l.downloadLimit(), limit, l.maxConnections(), l.maxUploads(), l.firstAndLastFirst()));
            reads.forget(t);
        });
    }

    /** {@code {hash: bytes per second}}, -1 for none, as {@code torrents/info}'s {@code dl_limit}. */
    Response downloadLimit(Request request) {
        return limits(request, true);
    }

    Response uploadLimit(Request request) {
        return limits(request, false);
    }

    private Response limits(Request request, boolean download) {
        Engine engine = backend.engine();
        if (engine == null) return noEngine();
        long now = clock.getAsLong();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Torrent t : targets(request)) {
            try {
                TorrentLimits l = engine.limits(t.id());
                int value = download ? l.downloadLimit() : l.uploadLimit();
                out.put(reads.hashOf(t, now), value > 0 ? value : -1);
            } catch (EngineException | RuntimeException e) {
                LOG.log(Level.FINE, "No limits for " + t.name(), e);
            }
        }
        return Response.json(JsonOut.write(out));
    }

    Response toggleSequentialDownload(Request request) {
        return each(request, Engine.Capability.SEQUENTIAL_DOWNLOAD, (e, t) -> e.setSequential(t.id(), !t.sequential()));
    }

    Response toggleFirstLastPiecePrio(Request request) {
        return each(request, Engine.Capability.FIRST_LAST_PIECES, (e, t) -> {
            TorrentLimits l = e.limits(t.id());
            e.setLimits(
                    t.id(),
                    new TorrentLimits(
                            l.downloadLimit(),
                            l.uploadLimit(),
                            l.maxConnections(),
                            l.maxUploads(),
                            !l.firstAndLastFirst()));
            reads.forget(t);
        });
    }

    Response setSuperSeeding(Request request) {
        boolean on = Writes.bool(Reads.params(request), "value").orElse(false);
        return each(request, Engine.Capability.SUPER_SEEDING, (e, t) -> e.setSuperSeeding(t.id(), on));
    }

    /** Torrenta has no automatic torrent management to switch: accepted, nothing to do. */
    Response setAutoManagement(Request request) {
        return Response.text(200, "");
    }

    /** In {@link QueueOrder}'s order, so the torrents named keep theirs among themselves, as the window does. */
    Response queue(Request request, Engine.QueueMove move) {
        return each(
                request,
                Engine.Capability.QUEUE_ORDER,
                QueueOrder.plan(targets(request), move),
                (e, t) -> e.queueMove(t.id(), move));
    }

    // ---- categories and tags --------------------------------------------------------------------

    /** A category's folder changed; it must exist, and the folder be inside the allowed roots. */
    Response editCategory(Request request) {
        Form p = Reads.params(request);
        String name = p.first("category").orElse("").trim();
        Optional<Category> known = backend.categories().stream()
                .filter(c -> c.name().equalsIgnoreCase(name))
                .findFirst();
        if (known.isEmpty()) return Response.text(409, "Category does not exist");
        String path = p.first("savePath").orElse("").trim();
        String kept = "";
        if (!path.isEmpty()) {
            SavePaths.Decision where = SavePaths.resolve(path, backend.defaultSavePath(), backend.allowedRoots());
            if (where.path().isEmpty()) return Response.text(400, where.problem());
            kept = where.path().get().toString();
        }
        try {
            backend.putCategory(new Category(known.get().name(), kept));
            return Response.text(200, "");
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Remote access could not change the category " + name, e);
            return Response.text(500, "Internal Server Error");
        }
    }

    /** The categories go, and the torrents filed under them are filed under none, as Settings does it. */
    Response removeCategories(Request request) {
        List<String> names = Arrays.stream(
                        Reads.params(request).first("categories").orElse("").split("\\R"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        Engine engine = backend.engine();
        try {
            for (String name : names) {
                Optional<Category> known = backend.categories().stream()
                        .filter(c -> c.name().equalsIgnoreCase(name))
                        .findFirst();
                if (known.isEmpty()) continue;
                backend.removeCategory(known.get().name());
                if (engine == null) continue;
                for (Torrent t : backend.torrents())
                    if (t.category().equalsIgnoreCase(known.get().name())) clearCategory(engine, t);
            }
            return Response.text(200, "");
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Remote access could not remove categories", e);
            return Response.text(500, "Internal Server Error");
        }
    }

    private static void clearCategory(Engine engine, Torrent t) {
        try {
            engine.setCategory(t.id(), "");
        } catch (EngineException | RuntimeException e) {
            LOG.log(Level.INFO, "Could not clear the category of " + t.name(), e);
        }
    }

    Response createTags(Request request) {
        List<String> tags = Writes.tags(Reads.params(request).first("tags").orElse(""));
        try {
            if (!tags.isEmpty()) backend.addTags(tags);
            return Response.text(200, "");
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Remote access could not keep the tags", e);
            return Response.text(500, "Internal Server Error");
        }
    }

    /** The tags go from the list and from every torrent carrying them. */
    Response deleteTags(Request request) {
        List<String> tags = Writes.tags(Reads.params(request).first("tags").orElse(""));
        Engine engine = backend.engine();
        try {
            for (String tag : tags) backend.removeTag(tag);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Remote access could not remove the tags", e);
            return Response.text(500, "Internal Server Error");
        }
        if (engine != null)
            for (Torrent t : backend.torrents())
                if (t.tags().stream().anyMatch(tags::contains)) dropTags(engine, t, tags);
        return Response.text(200, "");
    }

    /** From the torrents named; no {@code tags} at all takes every tag off them, as qBittorrent does. */
    Response removeTags(Request request) {
        Form p = Reads.params(request);
        List<String> tags = Writes.tags(p.first("tags").orElse(""));
        boolean all = !p.has("tags") || tags.isEmpty();
        return each(request, null, (e, t) -> {
            if (all) e.setTags(t.id(), List.of());
            else dropTags(e, t, tags);
        });
    }

    private static void dropTags(Engine engine, Torrent t, List<String> tags) {
        try {
            List<String> kept = new ArrayList<>(engine.tags(t.id()));
            kept.removeIf(tag -> tags.stream().anyMatch(tag::equalsIgnoreCase));
            engine.setTags(t.id(), kept);
        } catch (EngineException | RuntimeException e) {
            LOG.log(Level.INFO, "Could not take tags off " + t.name(), e);
        }
    }
}
