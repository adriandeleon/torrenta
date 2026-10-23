package com.torrenta.os;

import java.awt.GraphicsEnvironment;
import java.awt.Taskbar;
import java.util.Optional;

import com.torrenta.AppInfo;

import static com.torrenta.i18n.Messages.tr;

/**
 * Backend chooser, by {@code os.name}: the dock badge through AWT's {@link Taskbar} on macOS, the
 * Unity LauncherEntry signal on the session bus on Linux (KDE's task manager, GNOME's Dash to
 * Dock and Ubuntu's dock read it), and nothing on Windows, where AWT can only badge a window of
 * its own, which the JavaFX stage is not. An unavailable badge is a normal outcome and the reason
 * is kept for the capture hook.
 */
public final class Badges {

    /** A badge, or the one-line reason there is none. */
    public record Attempt(Optional<Badge> badge, String reason) {
        static Attempt of(Badge badge) {
            return new Attempt(Optional.of(badge), "");
        }

        static Attempt none(String reason) {
            return new Attempt(Optional.empty(), reason);
        }
    }

    private static final System.Logger LOG = System.getLogger(Badges.class.getName());

    private Badges() {}

    /** May block briefly (D-Bus or AWT init): call from a background thread. */
    public static Attempt create(String osName, String desktopId) {
        switch (Handlers.platform(osName)) {
            case LINUX -> {
                try {
                    return Attempt.of(new UnityBadge(desktopId));
                } catch (Exception | LinkageError e) {
                    LOG.log(System.Logger.Level.INFO, "Launcher badge unavailable: " + e.getMessage());
                    return Attempt.none(e.getMessage() == null ? e.toString() : e.getMessage());
                }
            }
            case MAC -> {
                if (GraphicsEnvironment.isHeadless()) return Attempt.none(tr("os.badge.restart", AppInfo.NAME));
                try {
                    if (!Taskbar.isTaskbarSupported()) return Attempt.none(tr("os.badge.noTaskbar"));
                    Taskbar taskbar = Taskbar.getTaskbar();
                    if (!taskbar.isSupported(Taskbar.Feature.ICON_BADGE_TEXT)) {
                        return Attempt.none(tr("os.badge.noText"));
                    }
                    return Attempt.of(new AwtBadge(taskbar));
                } catch (Throwable t) {
                    LOG.log(System.Logger.Level.INFO, "Dock badge unavailable: " + t.getMessage());
                    return Attempt.none(t.getMessage() == null ? t.toString() : t.getMessage());
                }
            }
            default -> {
                return Attempt.none(tr("os.badge.windows"));
            }
        }
    }
}
