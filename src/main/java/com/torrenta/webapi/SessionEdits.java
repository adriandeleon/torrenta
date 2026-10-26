package com.torrenta.webapi;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.torrenta.engine.EngineEvent;
import com.torrenta.engine.EngineException;
import com.torrenta.engine.EngineSettings;
import com.torrenta.http.Form;
import com.torrenta.http.Request;
import com.torrenta.http.Response;
import com.torrenta.session.SessionLog;
import com.torrenta.store.Settings;

/**
 * Tier 2's session-wide endpoints: the alternative speed limits' mode, the global limits in force,
 * {@code app/setPreferences} ({@link Preferences}) and {@code log/main}. A change is written to the
 * settings, saved, and applied to the engine at once, as a change on the Settings page is.
 */
final class SessionEdits {

    private static final Logger LOG = Logger.getLogger(SessionEdits.class.getName());

    private final WebApi.Backend backend;

    SessionEdits(WebApi.Backend backend) {
        this.backend = backend;
    }

    /** Saved and applied; the engine's refusal is the answer when it refuses. */
    private Response changed() {
        try {
            backend.settingsChanged();
            return Response.text(200, "");
        } catch (EngineException e) {
            LOG.log(Level.INFO, "Remote access: the engine refused the settings", e);
            return Response.text(409, e.getMessage() == null ? "Conflict" : e.getMessage());
        }
    }

    Response preferences(Request request) {
        return Response.json(JsonOut.write(Preferences.read(backend.settings(), backend.defaultSavePath())));
    }

    Response setPreferences(Request request) {
        String json = Reads.params(request).first("json").orElse("");
        Map<String, Object> values;
        try {
            values = Preferences.parse(json);
        } catch (IOException e) {
            return Response.text(400, "Bad Request");
        }
        Preferences.Applied applied =
                Preferences.apply(values, backend.settings(), backend.allowedRoots(), backend.defaultSavePath());
        if (applied.refused()) return Response.text(400, applied.refusal());
        return applied.applied().isEmpty() ? Response.text(200, "") : changed();
    }

    private boolean alternative() {
        return backend.alternativeInForce();
    }

    /** {@code 1} while the alternative pair is in force, by hand, schedule or condition; {@code 0} else. */
    Response speedLimitsMode(Request request) {
        return Response.text(200, alternative() ? "1" : "0");
    }

    /** As the status bar's pill does: the hand switch, opposite to what is in force now. */
    Response toggleSpeedLimitsMode(Request request) {
        backend.settings().set(Settings.SPEED_ALT_ON, String.valueOf(!alternative()));
        return changed();
    }

    /** 5.0's {@code mode}: 1 the alternative pair, 0 the normal one. */
    Response setSpeedLimitsMode(Request request) {
        String mode = Reads.params(request).first("mode").orElse("").trim();
        if (!mode.equals("0") && !mode.equals("1")) return Response.text(400, "Bad Request");
        backend.settings().set(Settings.SPEED_ALT_ON, String.valueOf(mode.equals("1")));
        return changed();
    }

    /** The limit in force, bytes per second, 0 for none. */
    Response downloadLimit(Request request) {
        EngineSettings.Speed s = backend.settings().engineSettings().speed();
        return Response.text(200, String.valueOf(s.downloadBytes(alternative())));
    }

    Response uploadLimit(Request request) {
        EngineSettings.Speed s = backend.settings().engineSettings().speed();
        return Response.text(200, String.valueOf(s.uploadBytes(alternative())));
    }

    /** Sets the limit of the pair in force, as qBittorrent does; bytes onto KiB, rounded up. */
    Response setDownloadLimit(Request request) {
        return setLimit(request, Settings.SPEED_DOWNLOAD, Settings.SPEED_ALT_DOWNLOAD);
    }

    Response setUploadLimit(Request request) {
        return setLimit(request, Settings.SPEED_UPLOAD, Settings.SPEED_ALT_UPLOAD);
    }

    private Response setLimit(Request request, String normal, String alt) {
        long bytes;
        try {
            bytes = Long.parseLong(
                    Reads.params(request).first("limit").orElse("").trim());
        } catch (NumberFormatException e) {
            return Response.text(400, "Bad Request");
        }
        long kib = bytes <= 0 ? 0 : Math.min(10_000_000, (bytes + 1023) / 1024);
        backend.settings().set(alternative() ? alt : normal, String.valueOf(kib));
        return changed();
    }

    /**
     * {@code log/main}: the session's events after {@code last_known_id}, by qBittorrent's types (1
     * normal, 4 warning, 8 critical; Torrenta has no "info" between normal and warning), the
     * {@code normal}/{@code warning}/{@code critical} switches each on unless set false.
     */
    Response log(Request request) {
        Form p = Reads.params(request);
        long after;
        try {
            after = Long.parseLong(p.first("last_known_id").orElse("-1").trim());
        } catch (NumberFormatException e) {
            after = -1;
        }
        boolean normal = !p.first("normal").orElse("true").equalsIgnoreCase("false");
        boolean warning = !p.first("warning").orElse("true").equalsIgnoreCase("false");
        boolean critical = !p.first("critical").orElse("true").equalsIgnoreCase("false");
        List<Map<String, Object>> out = new ArrayList<>();
        for (SessionLog.Entry e : backend.log(after)) {
            int type = type(e.event().level());
            if ((type == 1 && !normal) || (type == 4 && !warning) || (type == 8 && !critical)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("message", message(e.event()));
            m.put("timestamp", e.event().at().getEpochSecond());
            m.put("type", type);
            out.add(m);
        }
        return Response.json(JsonOut.write(out));
    }

    static int type(EngineEvent.Level level) {
        return switch (level) {
            case INFO -> 1;
            case WARNING -> 4;
            case ERROR -> 8;
        };
    }

    /** The line with the torrent's name before it, unless the line names the torrent already. */
    static String message(EngineEvent e) {
        String torrent = e.torrent() == null ? "" : e.torrent();
        if (torrent.isEmpty() || e.message().contains(torrent)) return e.message();
        return torrent + ": " + e.message();
    }
}
