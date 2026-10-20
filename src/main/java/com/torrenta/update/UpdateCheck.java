package com.torrenta.update;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

/**
 * The decisions behind an update check, separated from the network so they can be tested.
 *
 * <p>All of it is pure: parse a release payload, decide whether a version is newer, decide whether
 * enough time has passed to ask again.
 */
public final class UpdateCheck {

    private static final JsonFactory JSON = new JsonFactory();

    /** How often to ask. Once a day is plenty for something the user did not request. */
    public static final long DEFAULT_INTERVAL_MS = 24 * 60 * 60 * 1000L;

    private UpdateCheck() {}

    /**
     * Reads a GitHub {@code releases/latest} payload.
     *
     * <p>Returns null rather than throwing for anything unusable — a draft, a pre-release, a
     * missing tag, or a body that is not the JSON we expected. A failed update check must never be
     * something the user has to deal with.
     *
     * <p>Only top-level fields are read. The payload nests author and asset objects that have their
     * own {@code name} and {@code url} keys, so depth is tracked rather than scanning for the first
     * match.
     */
    public static ReleaseInfo parseLatest(String json) {
        if (json == null || json.isBlank()) return null;
        String tag = null;
        String url = null;
        String name = null;
        boolean draft = false;
        boolean prerelease = false;

        try (JsonParser parser = JSON.createParser(json)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return null;
            int depth = 0;
            String field = null;
            while (true) {
                JsonToken token = parser.nextToken();
                if (token == null) break;
                if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                    depth++;
                    continue;
                }
                if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
                    if (depth == 0) break;
                    depth--;
                    continue;
                }
                if (token == JsonToken.FIELD_NAME) {
                    field = parser.currentName();
                    continue;
                }
                if (depth != 0 || field == null) continue;
                switch (field) {
                    case "tag_name" -> tag = parser.getValueAsString();
                    case "html_url" -> url = parser.getValueAsString();
                    case "name" -> name = parser.getValueAsString();
                    case "draft" -> draft = parser.getValueAsBoolean();
                    case "prerelease" -> prerelease = parser.getValueAsBoolean();
                    default -> {}
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }

        if (draft || prerelease) return null;
        if (tag == null || tag.isBlank()) return null;
        return new ReleaseInfo(normalizeVersion(tag), url == null ? "" : url, name == null ? "" : name);
    }

    /** Strips a leading {@code v}, so {@code v1.2.3} compares against {@code 1.2.3}. */
    public static String normalizeVersion(String tag) {
        String trimmed = tag.trim();
        if (trimmed.startsWith("v") || trimmed.startsWith("V")) trimmed = trimmed.substring(1);
        return trimmed;
    }

    /** Whether {@code latest} is a later version than {@code current}. */
    public static boolean isNewer(String current, String latest) {
        if (latest == null || latest.isBlank()) return false;
        if (current == null || current.isBlank()) return true;
        return compareVersions(normalizeVersion(latest), releaseOf(current)) > 0;
    }

    /**
     * The release a version belongs to: {@code 0.1.0-SNAPSHOT} is the development of {@code 0.1.0},
     * so for the purpose of "is there anything newer" it counts as {@code 0.1.0}.
     *
     * <p>Only releases are ever offered — {@link #parseLatest} discards drafts and pre-releases — so
     * the same has to hold for the version being compared against, and a snapshot is not a release.
     * Left to the plain semver rule a snapshot sorts <em>below</em> the version it names, so every
     * development build off {@code master} would be told to update to the release it is already
     * ahead of, once a day, forever.
     *
     * <p>Deliberately only {@code -SNAPSHOT}: an {@code -rc1} really is a distinct thing that
     * precedes its release, and should still be told when that release lands.
     */
    static String releaseOf(String version) {
        String normalized = normalizeVersion(version);
        int suffix = normalized.toUpperCase(java.util.Locale.ROOT).indexOf("-SNAPSHOT");
        return suffix < 0 ? normalized : normalized.substring(0, suffix);
    }

    /**
     * Compares dotted versions numerically, so 0.10.0 is newer than 0.9.0 — which a string compare
     * gets backwards.
     *
     * <p>A pre-release suffix ({@code 1.2.0-rc1}) sorts <em>below</em> the release it precedes,
     * matching semver, and any non-numeric segment sorts below a numeric one rather than throwing.
     */
    public static int compareVersions(String a, String b) {
        String[] left = splitNumeric(a);
        String[] right = splitNumeric(b);
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int cmp = compareSegment(i < left.length ? left[i] : "0", i < right.length ? right[i] : "0");
            if (cmp != 0) return cmp;
        }
        // Same numbers: a build carrying a suffix (-SNAPSHOT, -rc1) precedes the plain release.
        return Boolean.compare(suffix(a).isEmpty(), suffix(b).isEmpty());
    }

    private static String[] splitNumeric(String version) {
        String core = version;
        int dash = core.indexOf('-');
        if (dash >= 0) core = core.substring(0, dash);
        return core.isEmpty() ? new String[] {"0"} : core.split("\\.");
    }

    private static String suffix(String version) {
        int dash = version.indexOf('-');
        return dash >= 0 ? version.substring(dash) : "";
    }

    private static int compareSegment(String a, String b) {
        Integer left = numeric(a);
        Integer right = numeric(b);
        if (left != null && right != null) return Integer.compare(left, right);
        // A segment that is not a number is not a version we understand, and the safe direction is
        // down: a malformed tag then fails to be offered as an update rather than being offered to
        // everybody forever. A plain string compare puts "x" above "0" and does the opposite.
        if (left != null) return 1;
        if (right != null) return -1;
        return a.compareTo(b);
    }

    private static Integer numeric(String segment) {
        try {
            return Integer.valueOf(segment.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Whether enough time has passed since the last check.
     *
     * <p>A timestamp in the future counts as due: that means the clock moved backwards, and the
     * alternative is never checking again until it catches up.
     */
    public static boolean isDue(long lastCheckMs, long nowMs, long intervalMs) {
        if (lastCheckMs <= 0) return true;
        if (lastCheckMs > nowMs) return true;
        return nowMs - lastCheckMs >= intervalMs;
    }
}
