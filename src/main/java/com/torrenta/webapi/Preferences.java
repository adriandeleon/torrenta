package com.torrenta.webapi;

import java.io.IOException;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.torrenta.engine.EngineSettings;
import com.torrenta.engine.FileRenames;
import com.torrenta.engine.ShareLimit;
import com.torrenta.store.Settings;

/**
 * {@code app/preferences} and {@code app/setPreferences}: qBittorrent's keys for what Torrenta's
 * Settings hold, both ways, in qBittorrent's units (bytes per second, minutes, its numbers for an
 * encryption mode or a schedule's days). A key Torrenta has no setting for is left out of the
 * reading and ignored in the writing, as qBittorrent ignores a key it does not know. Pure apart
 * from the {@link Settings} it reads and writes.
 *
 * <p>Two refusals, both about the doors: {@code save_path} must be inside the allowed roots like
 * any save path (the download folder is a root itself, so moving it out would widen them), and
 * Remote access's own keys ({@code web_ui_*}, {@code bypass_*}) are read-only here: the password,
 * the address and the bypasses are changed on this machine's Settings page, never through the door
 * they guard.
 */
public final class Preferences {

    private Preferences() {}

    private static final JsonFactory JSON = new JsonFactory();

    /** What a write did: the keys it applied, and why it refused the request when it did. */
    public record Applied(List<String> applied, String refusal) {
        public boolean refused() {
            return !refusal.isEmpty();
        }
    }

    /** The keys a client reads, from the settings as they stand. */
    public static Map<String, Object> read(Settings settings, Path savePath) {
        EngineSettings s = settings.engineSettings();
        EngineSettings.Queue queue = s.queue();
        ShareLimit share = queue.share();
        EngineSettings.Speed speed = s.speed();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("save_path", savePath.toString());
        // 5.0's name; 4.x's start_paused_enabled is still accepted on a write.
        m.put("add_stopped_enabled", settings.addStartPaused());
        m.put("torrent_content_layout", layout(settings.addContentLayout()));
        m.put("queueing_enabled", queue.activeDownloads() > 0 || queue.activeSeeds() > 0 || queue.activeLimit() > 0);
        m.put("max_active_downloads", orNone(queue.activeDownloads()));
        m.put("max_active_uploads", orNone(queue.activeSeeds()));
        m.put("max_active_torrents", orNone(queue.activeLimit()));
        m.put("dont_count_slow_torrents", queue.ignoreSlowTorrents());
        m.put("max_ratio_enabled", share.ratio() > 0);
        m.put("max_ratio", share.ratio() > 0 ? share.ratio() : -1);
        m.put("max_seeding_time_enabled", share.seedingMinutes() > 0);
        m.put("max_seeding_time", share.seedingMinutes() > 0 ? share.seedingMinutes() : -1);
        m.put("max_inactive_seeding_time_enabled", false);
        m.put("max_inactive_seeding_time", -1);
        m.put("max_ratio_act", WebApi.ratioAction(share.action()));
        m.put("dl_limit", speed.download() * 1024L);
        m.put("up_limit", speed.upload() * 1024L);
        m.put("alt_dl_limit", speed.alternativeDownload() * 1024L);
        m.put("alt_up_limit", speed.alternativeUpload() * 1024L);
        m.put("scheduler_enabled", speed.scheduleOn());
        m.put("schedule_from_hour", speed.scheduleFrom() / 60);
        m.put("schedule_from_min", speed.scheduleFrom() % 60);
        m.put("schedule_to_hour", speed.scheduleTo() / 60);
        m.put("schedule_to_min", speed.scheduleTo() % 60);
        m.put("scheduler_days", schedulerDays(speed.scheduleDays()));
        m.put("limit_tcp_overhead", speed.countOverhead());
        m.put("listen_port", s.listenPort());
        m.put("random_port", s.randomPort());
        m.put("upnp", s.upnp());
        m.put("max_connec", s.maxConnections());
        m.put("max_connec_per_torrent", s.maxConnectionsPerTorrent());
        m.put("max_uploads", s.uploadSlots());
        m.put("dht", s.protocol().dht());
        m.put("pex", s.protocol().pex());
        m.put("lsd", s.protocol().lsd());
        m.put("bittorrent_protocol", s.protocol().utp() ? 0 : 1);
        m.put("encryption", encryption(s.protocol().encryption()));
        m.put("anonymous_mode", s.protocol().anonymous());
        m.put("banned_IPs", String.join("\n", settings.ipFilterBans()));
        m.put("add_trackers_enabled", !s.protocol().defaultTrackers().isEmpty());
        m.put("add_trackers", String.join("\n", s.protocol().defaultTrackers()));
        m.put("web_ui_address", settings.webApi().address());
        m.put("web_ui_port", settings.webApi().port());
        m.put("web_ui_username", settings.webApi().username());
        m.put("use_https", settings.webApi().https());
        m.put("bypass_local_auth", false);
        m.put("bypass_auth_subnet_whitelist_enabled", false);
        m.put("alternative_webui_enabled", settings.webApiRootFolder().isPresent());
        m.put(
                "alternative_webui_path",
                settings.webApiRootFolder().map(Path::toString).orElse(""));
        return m;
    }

    private static int orNone(int count) {
        return count > 0 ? count : -1;
    }

    private static String layout(FileRenames.Layout l) {
        return switch (l) {
            case ORIGINAL -> "Original";
            case SUBFOLDER -> "Subfolder";
            case NO_SUBFOLDER -> "NoSubfolder";
        };
    }

    /** qBittorrent's: 0 prefer, 1 require, 2 off. */
    static int encryption(EngineSettings.Protocol.Encryption e) {
        return switch (e) {
            case PREFER -> 0;
            case REQUIRE -> 1;
            case OFF -> 2;
        };
    }

    /** qBittorrent's one number: 0 every day, 1 weekdays, 2 weekends, 3 Monday … 9 Sunday; else every day. */
    static int schedulerDays(Set<DayOfWeek> days) {
        Set<DayOfWeek> weekend = EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
        if (days.size() == 7 || days.isEmpty()) return 0;
        if (days.equals(EnumSet.complementOf(EnumSet.copyOf(weekend)))) return 1;
        if (days.equals(weekend)) return 2;
        if (days.size() == 1) return 2 + days.iterator().next().getValue();
        return 0;
    }

    static Set<DayOfWeek> schedulerDays(int code) {
        return switch (code) {
            case 1 -> EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
            case 2 -> EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
            case 3, 4, 5, 6, 7, 8, 9 -> EnumSet.of(DayOfWeek.of(code - 2));
            default -> EnumSet.allOf(DayOfWeek.class);
        };
    }

    /** The {@code json} form field as a flat map; nested values are left out, as unknown keys are. */
    static Map<String, Object> parse(String json) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        try (JsonParser p = JSON.createParser(json)) {
            if (p.nextToken() != JsonToken.START_OBJECT) throw new IOException("not a JSON object");
            for (JsonToken t = p.nextToken(); t != JsonToken.END_OBJECT; t = p.nextToken()) {
                if (t != JsonToken.FIELD_NAME) throw new IOException("not a JSON object");
                String key = p.currentName();
                JsonToken v = p.nextToken();
                switch (v) {
                    case VALUE_STRING -> out.put(key, p.getText());
                    case VALUE_NUMBER_INT -> out.put(key, p.getLongValue());
                    case VALUE_NUMBER_FLOAT -> out.put(key, p.getDoubleValue());
                    case VALUE_TRUE, VALUE_FALSE -> out.put(key, v == JsonToken.VALUE_TRUE);
                    case START_OBJECT, START_ARRAY -> p.skipChildren();
                    default -> {}
                }
            }
        }
        return out;
    }

    /**
     * Writes the keys Torrenta has into {@code settings} (not saved: the caller saves and applies).
     * {@code save_path} outside {@code roots} refuses the whole request before anything is written.
     */
    public static Applied apply(
            Map<String, Object> json, Settings settings, Collection<Path> roots, Path defaultSavePath) {
        if (json.containsKey("save_path")) {
            SavePaths.Decision where = SavePaths.resolve(String.valueOf(json.get("save_path")), defaultSavePath, roots);
            if (where.path().isEmpty()) return new Applied(List.of(), where.problem());
            json = new LinkedHashMap<>(json);
            json.put("save_path", where.path().get().toString());
        }
        List<String> applied = new ArrayList<>();
        EngineSettings.Speed speed = settings.engineSettings().speed();
        int from = speed.scheduleFrom();
        int to = speed.scheduleTo();
        for (Map.Entry<String, Object> e : json.entrySet()) {
            Object v = e.getValue();
            String key = e.getKey();
            boolean done =
                    switch (key) {
                        case "save_path" -> set(settings, Settings.DOWNLOAD_DIR, String.valueOf(v));
                        case "add_stopped_enabled", "start_paused_enabled" ->
                            bool(settings, Settings.ADD_START_PAUSED, v);
                        case "torrent_content_layout" ->
                            switch (String.valueOf(v)) {
                                case "Original" ->
                                    set(settings, Settings.ADD_CONTENT_LAYOUT, FileRenames.Layout.ORIGINAL.name());
                                case "Subfolder" ->
                                    set(settings, Settings.ADD_CONTENT_LAYOUT, FileRenames.Layout.SUBFOLDER.name());
                                case "NoSubfolder" ->
                                    set(settings, Settings.ADD_CONTENT_LAYOUT, FileRenames.Layout.NO_SUBFOLDER.name());
                                default -> false;
                            };
                        case "queueing_enabled" -> {
                            // Off is no limit on any count; on keeps the counts set, or libtorrent's.
                            if (!(v instanceof Boolean on)) yield false;
                            if (!on) {
                                settings.set(Settings.QUEUE_ACTIVE_DOWNLOADS, "0");
                                settings.set(Settings.QUEUE_ACTIVE_SEEDS, "0");
                                settings.set(Settings.QUEUE_ACTIVE_LIMIT, "0");
                            }
                            yield true;
                        }
                        case "max_active_downloads" -> count(settings, Settings.QUEUE_ACTIVE_DOWNLOADS, v);
                        case "max_active_uploads" -> count(settings, Settings.QUEUE_ACTIVE_SEEDS, v);
                        case "max_active_torrents" -> count(settings, Settings.QUEUE_ACTIVE_LIMIT, v);
                        case "dont_count_slow_torrents" -> bool(settings, Settings.QUEUE_IGNORE_SLOW, v);
                        case "max_ratio_enabled" -> {
                            if (v instanceof Boolean on && !on) settings.set(Settings.SHARE_RATIO, "0");
                            yield v instanceof Boolean;
                        }
                        case "max_ratio" ->
                            number(v) >= 0 && set(settings, Settings.SHARE_RATIO, String.valueOf(number(v)));
                        case "max_seeding_time_enabled" -> {
                            if (v instanceof Boolean on && !on) settings.set(Settings.SHARE_SEED_MINUTES, "0");
                            yield v instanceof Boolean;
                        }
                        case "max_seeding_time" ->
                            number(v) >= 0
                                    && set(settings, Settings.SHARE_SEED_MINUTES, String.valueOf((long) number(v)));
                        case "max_ratio_act" ->
                            switch ((int) number(v)) {
                                case 0 -> set(settings, Settings.SHARE_ACTION, ShareLimit.Action.PAUSE.name());
                                case 1 -> set(settings, Settings.SHARE_ACTION, ShareLimit.Action.REMOVE.name());
                                case 2 -> set(settings, Settings.SHARE_ACTION, ShareLimit.Action.SUPER_SEED.name());
                                case 3 -> set(settings, Settings.SHARE_ACTION, ShareLimit.Action.REMOVE_DATA.name());
                                default -> false;
                            };
                        case "dl_limit" -> rate(settings, Settings.SPEED_DOWNLOAD, v);
                        case "up_limit" -> rate(settings, Settings.SPEED_UPLOAD, v);
                        case "alt_dl_limit" -> rate(settings, Settings.SPEED_ALT_DOWNLOAD, v);
                        case "alt_up_limit" -> rate(settings, Settings.SPEED_ALT_UPLOAD, v);
                        case "scheduler_enabled" -> bool(settings, Settings.SPEED_SCHEDULE_ON, v);
                        case "schedule_from_hour", "schedule_from_min" -> {
                            int next = clock(from, v, key.endsWith("hour"));
                            if (next >= 0) from = next;
                            yield next >= 0;
                        }
                        case "schedule_to_hour", "schedule_to_min" -> {
                            int next = clock(to, v, key.endsWith("hour"));
                            if (next >= 0) to = next;
                            yield next >= 0;
                        }
                        case "scheduler_days" ->
                            set(settings, Settings.SPEED_SCHEDULE_DAYS, Settings.formatDays(schedulerDays((int)
                                    number(v))));
                        case "limit_tcp_overhead" -> bool(settings, Settings.SPEED_COUNT_OVERHEAD, v);
                        case "listen_port" ->
                            number(v) >= 1024
                                    && number(v) <= 65535
                                    && set(settings, Settings.CONN_PORT, String.valueOf((long) number(v)));
                        case "random_port" -> bool(settings, Settings.CONN_RANDOM_PORT, v);
                        case "upnp" -> bool(settings, Settings.CONN_UPNP, v);
                        case "max_connec" ->
                            number(v) >= 2
                                    && set(settings, Settings.CONN_MAX_CONNECTIONS, String.valueOf((long) number(v)));
                        case "max_connec_per_torrent" ->
                            number(v) >= 1
                                    && set(settings, Settings.CONN_MAX_PER_TORRENT, String.valueOf((long) number(v)));
                        case "max_uploads" ->
                            number(v) >= 1
                                    && set(settings, Settings.CONN_UPLOAD_SLOTS, String.valueOf((long) number(v)));
                        case "dht" -> bool(settings, Settings.PROTO_DHT, v);
                        case "pex" -> bool(settings, Settings.PROTO_PEX, v);
                        case "lsd" -> bool(settings, Settings.PROTO_LSD, v);
                        case "bittorrent_protocol" -> set(settings, Settings.PROTO_UTP, String.valueOf(number(v) != 1));
                        case "encryption" ->
                            switch ((int) number(v)) {
                                case 0 -> set(settings, Settings.PROTO_ENCRYPTION, "PREFER");
                                case 1 -> set(settings, Settings.PROTO_ENCRYPTION, "REQUIRE");
                                case 2 -> set(settings, Settings.PROTO_ENCRYPTION, "OFF");
                                default -> false;
                            };
                        case "anonymous_mode" -> bool(settings, Settings.PROTO_ANONYMOUS, v);
                        case "banned_IPs" -> {
                            settings.setIpFilterBans(
                                    java.util.Arrays.stream(String.valueOf(v).split("\\s+"))
                                            .filter(b -> !b.isEmpty())
                                            .distinct()
                                            .toList());
                            yield true;
                        }
                        case "add_trackers" ->
                            set(
                                    settings,
                                    Settings.PROTO_DEFAULT_TRACKERS,
                                    String.valueOf(v).replaceAll("\\s+", " ").trim());
                        default -> false;
                    };
            if (done) applied.add(key);
        }
        if (from != speed.scheduleFrom()) settings.set(Settings.SPEED_SCHEDULE_FROM, hhmm(from));
        if (to != speed.scheduleTo()) settings.set(Settings.SPEED_SCHEDULE_TO, hhmm(to));
        return new Applied(applied, "");
    }

    private static boolean set(Settings settings, String key, String value) {
        settings.set(key, value);
        return true;
    }

    private static boolean bool(Settings settings, String key, Object v) {
        return v instanceof Boolean b && set(settings, key, String.valueOf(b));
    }

    /** A count where qBittorrent's -1 is "none", Torrenta's 0. */
    private static boolean count(Settings settings, String key, Object v) {
        if (!(v instanceof Number)) return false;
        long n = (long) number(v);
        return set(settings, key, String.valueOf(n < 0 ? 0 : n));
    }

    /** Bytes per second onto KiB per second, rounded up so a limit never loosens; 0 or less is none. */
    private static boolean rate(Settings settings, String key, Object v) {
        if (!(v instanceof Number)) return false;
        long bytes = (long) number(v);
        return set(settings, key, String.valueOf(bytes <= 0 ? 0 : (bytes + 1023) / 1024));
    }

    private static double number(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** {@code minutes} with its hour or its minute replaced; -1 when the value does not fit. */
    private static int clock(int minutes, Object v, boolean hour) {
        double n = number(v);
        if (Double.isNaN(n) || n < 0 || n > (hour ? 23 : 59)) return -1;
        return hour ? (int) n * 60 + minutes % 60 : minutes / 60 * 60 + (int) n;
    }

    private static String hhmm(int minutes) {
        return String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }
}
