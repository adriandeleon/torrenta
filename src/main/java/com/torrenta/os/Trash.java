package com.torrenta.os;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * The desktop's trash, where Remove with files puts a torrent's data so a mistake can be taken
 * back from the file manager. No AWT: {@code Desktop.moveToTrash} needs it headful, which {@link
 * AwtUse} keeps off on Linux and off on Windows and macOS unless the tray wants it.
 */
public interface Trash {

    /** Moves a file or a whole folder into the trash; a path that is not there is left alone. Blocks. */
    void trash(Path path) throws IOException;

    /** This machine's trash. */
    static Trash forPlatform() {
        return forPlatform(
                System.getProperty("os.name", ""),
                Path.of(System.getProperty("user.home")),
                System.getenv("XDG_DATA_HOME"));
    }

    /**
     * The trash for an {@code os.name}: the Recycle Bin on Windows, Finder's on macOS, the
     * freedesktop.org one (GNOME's, KDE's, every other desktop's) on the rest.
     */
    static Trash forPlatform(String osName, Path home, String xdgDataHome) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) return new WindowsTrash();
        if (os.contains("mac") || os.contains("darwin")) return new MacTrash(home);
        Path dataHome = xdgDataHome == null
                        || xdgDataHome.isBlank()
                        || !Path.of(xdgDataHome).isAbsolute()
                ? home.resolve(".local/share")
                : Path.of(xdgDataHome);
        return new FreedesktopTrash(dataHome.resolve("Trash"));
    }
}
