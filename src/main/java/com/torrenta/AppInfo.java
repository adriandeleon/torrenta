package com.torrenta;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Identity: the one place a version number is read from.
 *
 * <p>It comes from {@code pom.xml} through a Maven-filtered {@code build-info.properties}, so
 * cutting a release means editing one number. A constant here instead would be a second source that
 * quietly disagrees with the artifact it is compiled into.
 */
public final class AppInfo {

    public static final String NAME = "Torrenta";
    public static final String VERSION = load("version", "0.0.0");
    public static final String BUILD_TIME = load("build.time", "");
    /** The libtorrent4j version the build was made with; names the native-library cache folder. */
    public static final String ENGINE_VERSION = load("engine.version", "unknown");

    public static final String COPYRIGHT = "Copyright © 2026 Adrian De Leon";
    public static final String LICENSE = "MIT License";

    public static final String GITHUB_REPO = "adriandeleon/torrenta";
    public static final String HOMEPAGE = "https://github.com/" + GITHUB_REPO;
    public static final String RELEASES_PAGE = HOMEPAGE + "/releases";

    private AppInfo() {}

    /**
     * Falls back to the default when the resource was not filtered — that happens when classes are
     * run straight from an IDE build rather than through Maven, and it should read as "unknown
     * build" rather than crash.
     */
    private static String load(String key, String fallback) {
        try (InputStream in = AppInfo.class.getResourceAsStream("/com/torrenta/build-info.properties")) {
            if (in == null) return fallback;
            Properties properties = new Properties();
            properties.load(in);
            String value = properties.getProperty(key, fallback);
            // An unfiltered resource still contains the literal ${...} placeholder.
            return value.startsWith("${") ? fallback : value;
        } catch (IOException e) {
            return fallback;
        }
    }

    /** True for a development build; releases drop the suffix. */
    public static boolean isSnapshot() {
        return VERSION.endsWith("-SNAPSHOT");
    }
}
