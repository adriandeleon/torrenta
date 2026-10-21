package com.torrenta.os;

import java.util.Map;

import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.types.Variant;

/**
 * The Linux launcher badge: the count of downloading torrents, as the Unity LauncherEntry signal.
 * Construction fails fast with no session bus, so {@link Badges} can say why. The path is a
 * well-formed one of ours; the launcher matches on the URI, not the path.
 */
final class UnityBadge implements Badge {

    /** libunity hashes the URI into the path; any valid, stable path serves. */
    static final String PATH = "/com/canonical/unity/launcherentry/torrenta";

    private final DBusConnection conn;
    private final String appUri;

    UnityBadge(String desktopId) throws DBusException {
        conn = DBusConnectionBuilder.forSessionBus().build();
        appUri = appUri(desktopId);
    }

    /** {@code application://torrenta-Torrenta.desktop}: the entry's file name, whatever the package called it. */
    static String appUri(String desktopId) {
        return "application://" + desktopId;
    }

    /** The signal's properties: the count, shown only while there is one. */
    static Map<String, Variant<?>> properties(BadgeState state) {
        return Map.of(
                "count", new Variant<>((long) state.downloading()),
                "count-visible", new Variant<>(state.active()));
    }

    @Override
    public void update(BadgeState state) {
        send(state);
    }

    @Override
    public void dispose() {
        send(BadgeState.idle());
        conn.disconnect();
    }

    private void send(BadgeState state) {
        try {
            conn.sendMessage(new LauncherEntry.Update(PATH, appUri, properties(state)));
        } catch (DBusException | RuntimeException e) {
            System.getLogger(UnityBadge.class.getName())
                    .log(System.Logger.Level.INFO, "Launcher badge not sent: " + e.getMessage());
        }
    }

    @Override
    public String backend() {
        return "launcher";
    }
}
