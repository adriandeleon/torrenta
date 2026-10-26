package com.torrenta.webapi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The root folder: qBittorrent's public/private layout, and nothing outside it. */
class StaticFilesTest {

    @TempDir
    Path dir;

    private Path write(String relative, String text) throws IOException {
        Path p = dir.resolve(relative);
        Files.createDirectories(p.getParent());
        return Files.writeString(p, text);
    }

    private String found(String path, boolean signedIn) throws IOException {
        Optional<Path> f = StaticFiles.find(dir, path, signedIn);
        return f.isEmpty() ? null : Files.readString(f.get());
    }

    /** VueTorrent's layout: public/index.html and its assets, nothing private. */
    @Test
    void aPublicOnlyUiIsServedToAnyoneFromItsIndex() throws IOException {
        write("public/index.html", "vue");
        write("public/assets/app.js", "js");
        assertEquals("vue", found("/", false), "no login page: the index");
        assertEquals("vue", found("/", true));
        assertEquals("js", found("/assets/app.js", false));
    }

    /** qBittorrent's own layout: a login page in public, the app in private for a session only. */
    @Test
    void thePrivateHalfIsForASessionAlone() throws IOException {
        write("public/login.html", "login");
        write("private/index.html", "app");
        write("private/scripts/app.js", "secret js");
        assertEquals("login", found("/", false));
        assertEquals("app", found("/", true));
        assertEquals(null, found("/scripts/app.js", false));
        assertEquals("secret js", found("/scripts/app.js", true));
    }

    @Test
    void aFolderWithoutEitherHalfIsServedWhole() throws IOException {
        write("index.html", "flat");
        write("css/site.css", "css");
        assertEquals("flat", found("/", false));
        assertEquals("css", found("/css/site.css", false));
        assertEquals(null, found("/missing.html", true));
    }

    @Test
    void nothingOutsideTheFolderOrHiddenInItIsReached() throws IOException {
        Path ui = Files.createDirectories(dir.resolve("ui"));
        Files.writeString(ui.resolve("index.html"), "ui");
        Files.writeString(dir.resolve("secret.txt"), "secret");
        Files.writeString(ui.resolve(".htpasswd"), "hidden");
        for (String path :
                new String[] {"/../secret.txt", "/a/../../secret.txt", "/.htpasswd", "/..", "/x\\..\\secret.txt"})
            assertTrue(StaticFiles.find(ui, path, true).isEmpty(), path);
    }

    @Test
    void aLinkOutOfTheFolderIsOut() throws IOException {
        Path ui = Files.createDirectories(dir.resolve("ui"));
        Path outside = Files.writeString(dir.resolve("outside.txt"), "outside");
        try {
            Files.createSymbolicLink(ui.resolve("link.txt"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "no symbolic links here");
        }
        assertTrue(StaticFiles.find(ui, "/link.txt", true).isEmpty());
    }

    @Test
    void aFileSaysWhatItIs() {
        assertEquals("text/html; charset=utf-8", StaticFiles.type(Path.of("index.html")));
        assertEquals("text/javascript; charset=utf-8", StaticFiles.type(Path.of("a.MJS")));
        assertEquals("font/woff2", StaticFiles.type(Path.of("f.woff2")));
        assertEquals("application/octet-stream", StaticFiles.type(Path.of("x.bin")));
    }
}
