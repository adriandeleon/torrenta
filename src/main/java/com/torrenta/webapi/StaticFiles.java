package com.torrenta.webapi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.torrenta.http.Request;
import com.torrenta.http.Response;

/**
 * {@code webapi.rootFolder} served as qBittorrent serves an alternative WebUI, so unpacking
 * VueTorrent (or any web UI written for qBittorrent's API) there is a phone interface Torrenta
 * does not have to write. A folder with {@code public/} and {@code private/} is qBittorrent's
 * layout: {@code public/} to anyone (the login page), {@code private/} to a session only, and
 * {@code /} is {@code private/index.html} for a session and {@code public/login.html} without one,
 * each falling back to {@code public/index.html} (VueTorrent has only that). A folder without
 * either is served whole, to anyone.
 *
 * <p>Nothing outside the folder is reached: a path is taken segment by segment, a segment that
 * starts with a dot or holds a separator is refused, and the file found is compared to the folder
 * on real paths, so a link out of it is out. No listing; GET only; a page may not be framed by
 * another site, and the browser is told what a file is rather than left to guess.
 */
final class StaticFiles {

    /** The content types a web UI's files have; anything else is sent as bytes. */
    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("htm", "text/html; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("json", "application/json"),
            Map.entry("webmanifest", "application/manifest+json"),
            Map.entry("map", "application/json"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("wasm", "application/wasm"));

    /** qBittorrent's own policy for its WebUI: this server's scripts and styles, nobody's frames. */
    static final String CSP = "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
            + "script-src 'self' 'unsafe-inline'; object-src 'none'; form-action 'self'; frame-ancestors 'self'";

    private final Supplier<Optional<Path>> root;
    private final Predicate<Request> signedIn;

    StaticFiles(Supplier<Optional<Path>> root, Predicate<Request> signedIn) {
        this.root = root;
        this.signedIn = signedIn;
    }

    Response serve(Request request) {
        Optional<Path> folder = root.get();
        if (folder.isEmpty()) return Response.text(404, "Not Found");
        Optional<Path> file = find(folder.get(), request.path(), signedIn.test(request));
        if (file.isEmpty()) return Response.text(404, "Not Found");
        return Response.file(file.get(), type(file.get()))
                .withHeader("X-Content-Type-Options", "nosniff")
                .withHeader("X-Frame-Options", "SAMEORIGIN")
                .withHeader("Referrer-Policy", "same-origin")
                .withHeader("Content-Security-Policy", CSP)
                .withHeader("Cache-Control", "no-cache");
    }

    /** The file {@code path} names under {@code folder} for a client signed in or not; empty for none. */
    static Optional<Path> find(Path folder, String path, boolean signedIn) {
        Path base;
        try {
            base = folder.toRealPath();
        } catch (IOException e) {
            return Optional.empty();
        }
        Optional<List<String>> segments = segments(path);
        if (segments.isEmpty()) return Optional.empty();
        boolean split = Files.isDirectory(base.resolve("public")) || Files.isDirectory(base.resolve("private"));
        List<Path> candidates = new ArrayList<>();
        if (segments.get().isEmpty()) {
            if (!split) candidates.add(Path.of("index.html"));
            else if (signedIn) candidates.add(Path.of("private", "index.html"));
            else candidates.add(Path.of("public", "login.html"));
            if (split) candidates.add(Path.of("public", "index.html"));
        } else {
            Path relative = Path.of("", segments.get().toArray(String[]::new));
            if (!split) candidates.add(relative);
            else {
                if (signedIn) candidates.add(Path.of("private").resolve(relative));
                candidates.add(Path.of("public").resolve(relative));
            }
        }
        for (Path c : candidates) {
            Path file = base.resolve(c);
            if (!Files.isRegularFile(file)) continue;
            try {
                Path real = file.toRealPath();
                if (real.startsWith(base)) return Optional.of(real);
            } catch (IOException e) {
                // Gone between the look and the read: the next candidate, or none.
            }
        }
        return Optional.empty();
    }

    /** The path's segments; empty for {@code /}; none at all for one that could climb or hide. */
    static Optional<List<String>> segments(String path) {
        List<String> out = new ArrayList<>();
        for (String s : path.split("/")) {
            if (s.isEmpty()) continue;
            if (s.startsWith(".") || s.indexOf('\\') >= 0 || s.indexOf(':') >= 0 || s.indexOf('\0') >= 0)
                return Optional.empty();
            try {
                Path.of(s);
            } catch (InvalidPathException e) {
                return Optional.empty();
            }
            out.add(s);
        }
        return Optional.of(out);
    }

    static String type(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return TYPES.getOrDefault(ext, "application/octet-stream");
    }
}
