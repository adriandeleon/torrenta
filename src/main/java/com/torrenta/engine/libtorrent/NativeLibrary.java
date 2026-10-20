package com.torrenta.engine.libtorrent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

import com.torrenta.AppInfo;
import com.torrenta.engine.EngineException;

/**
 * Gets libtorrent4j's native library onto disk and tells libtorrent4j where it is.
 *
 * <p>libtorrent4j does not do this itself. Its JNI class's static initialiser calls {@code
 * System.load} on the path in {@value #PATH_PROPERTY}, or failing that {@code System.loadLibrary},
 * which walks {@code java.library.path}. The per-OS jars ({@code libtorrent4j-linux} and friends)
 * carry the library at {@code lib/<arch>/libtorrent4j.<ext>} — the Android APK layout — as a plain
 * resource. So: find the resource, copy it to a per-version cache directory once, set the property.
 * All of it must happen before any {@code org.libtorrent4j} class that touches native code is
 * loaded, which is why {@link LibtorrentEngine#start()} calls this first and nothing else in the
 * engine package is reachable before it.
 */
final class NativeLibrary {

    static final String PATH_PROPERTY = "libtorrent4j.jni.path";

    /** Overrides the cache directory; the tests point it into {@code target/}. */
    static final String DIR_PROPERTY = "torrenta.nativeDir";

    private static boolean ready;

    private NativeLibrary() {}

    static synchronized void ensureAvailable() throws EngineException {
        if (ready) return;
        if (System.getProperty(PATH_PROPERTY) != null) {
            // Somebody (a packaged launcher, a developer) already pointed at a library.
            ready = true;
            return;
        }
        String resource = resourcePath(System.getProperty("os.name"), System.getProperty("os.arch"));
        // Absolute: System.load refuses a relative path outright.
        Path target = cacheDir()
                .resolve(resource.substring(resource.lastIndexOf('/') + 1))
                .toAbsolutePath();
        try {
            extract(resource, target);
        } catch (IOException e) {
            throw new EngineException("Could not unpack the BitTorrent engine to " + target, e);
        }
        System.setProperty(PATH_PROPERTY, target.toString());
        ready = true;
    }

    /**
     * The resource name inside the per-OS jar, from the JVM's idea of the platform.
     *
     * <p>Pure, so the mapping is testable on every platform regardless of which jar is present.
     * Linux arm64 maps to a path that no jar provides; the lookup then fails with a message that
     * says so, which is better than this method pretending to know.
     */
    static String resourcePath(String osName, String osArch) throws EngineException {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch =
                switch (osArch.toLowerCase(Locale.ROOT)) {
                    case "amd64", "x86_64", "x64" -> "x86_64";
                    case "aarch64", "arm64" -> "arm64";
                    default -> throw new EngineException("No BitTorrent engine build for this CPU (" + osArch + ")");
                };
        String ext;
        if (os.contains("mac") || os.contains("darwin")) ext = "dylib";
        else if (os.contains("windows")) ext = "dll";
        else if (os.contains("linux")) ext = "so";
        else throw new EngineException("No BitTorrent engine build for this operating system (" + osName + ")");
        return "lib/" + arch + "/libtorrent4j." + ext;
    }

    private static Path cacheDir() {
        String override = System.getProperty(DIR_PROPERTY);
        Path base =
                override != null ? Path.of(override) : Path.of(System.getProperty("user.home"), ".torrenta", "native");
        // Per engine version, so an upgrade never loads last version's library by its old name.
        return base.resolve(AppInfo.ENGINE_VERSION);
    }

    /** Copies the resource out unless an identical-size copy is already there. */
    private static void extract(String resource, Path target) throws IOException, EngineException {
        ClassLoader loader = NativeLibrary.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                throw new EngineException("This build has no BitTorrent engine for "
                        + System.getProperty("os.name") + " " + System.getProperty("os.arch")
                        + " (looked for " + resource + " on the class or module path)");
            }
            Files.createDirectories(target.getParent());
            if (Files.exists(target) && Files.size(target) == sizeOf(resource, loader)) return;
            // Written beside the target and moved into place, so a crash mid-copy never leaves a
            // truncated library that the next launch would try to load.
            Path tmp = Files.createTempFile(target.getParent(), "libtorrent4j", ".part");
            try {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
    }

    private static long sizeOf(String resource, ClassLoader loader) throws IOException {
        var url = loader.getResource(resource);
        if (url == null) return -1;
        var connection = url.openConnection();
        connection.setUseCaches(false);
        try (InputStream ignored = connection.getInputStream()) {
            return connection.getContentLengthLong();
        }
    }
}
