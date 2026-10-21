package com.torrenta.tray;

import java.awt.GraphicsEnvironment;
import java.util.Locale;
import java.util.Optional;

/**
 * Backend chooser. Linux speaks StatusNotifierItem over D-Bus and nothing else: the legacy AWT tray
 * is XEmbed, which GNOME dropped in 3.26 and Wayland never had, so there is no fallback worth the
 * name there. Windows and macOS use AWT. An unavailable tray is a normal outcome (stock GNOME
 * without the AppIndicator extension), and the application is fully usable without it; what
 * matters is that the reason is said, since a person whose "close to tray" box is ticked will
 * otherwise call the window quitting a bug.
 */
public final class Trays {

    /** A tray, or the one-line reason there is none. */
    public record Attempt(Optional<TorrentaTray> tray, String reason) {
        static Attempt of(TorrentaTray tray) {
            return new Attempt(Optional.of(tray), "");
        }

        static Attempt none(String reason) {
            return new Attempt(Optional.empty(), reason);
        }
    }

    private static final System.Logger LOG = System.getLogger(Trays.class.getName());

    private Trays() {}

    /**
     * Whether the tray on this platform is AWT's, which needs AWT headful: everywhere but Linux.
     * {@code os.AwtUse} folds this into the one rule {@code App.main} decides
     * {@code java.awt.headless} from.
     */
    public static boolean usesAwt(boolean trayWanted, String osName) {
        return trayWanted && !isLinux(osName);
    }

    private static boolean isLinux(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("linux");
    }

    /** May block briefly (D-Bus/AWT init): call from a background thread. */
    public static Attempt create(Runnable onActivate, Runnable onQuit) {
        String os = System.getProperty("os.name", "");
        if (isLinux(os)) {
            try {
                return Attempt.of(new SniTray(onActivate, onQuit));
            } catch (Exception | LinkageError e) {
                LOG.log(System.Logger.Level.INFO, "SNI tray unavailable: " + e.getMessage());
                return Attempt.none(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }
        // Turned on while running: the headless guard was latched at launch, so only a restart can
        // lift it. Said as such, not as "unsupported".
        if (GraphicsEnvironment.isHeadless()) {
            return Attempt.none("restart Torrenta to show the tray icon");
        }
        try {
            return Attempt.of(new AwtTray(onActivate, onQuit));
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.INFO, "AWT tray unavailable: " + t.getMessage());
            return Attempt.none(t.getMessage() == null ? t.toString() : t.getMessage());
        }
    }
}
