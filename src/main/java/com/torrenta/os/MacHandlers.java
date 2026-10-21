package com.torrenta.os;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Where macOS hands a running application the files and links it was asked to open: the Apple
 * events behind "Open with", a double-clicked {@code .torrent} and a {@code magnet:} link in a
 * browser, which the JDK surfaces through {@link Desktop}'s open-file and open-URI handlers. At
 * launch the bundle's launcher passes them as arguments instead, which {@code App.main} handles
 * like any command line.
 *
 * <p>Needs AWT headful ({@link AwtUse}); anywhere else, or on another platform, this installs
 * nothing and says why. The handlers are called on AWT's thread; the consumer hops.
 */
public final class MacHandlers {

    private MacHandlers() {}

    /** Installs what the platform supports and describes it: {@code files, links}, or the reason for none. */
    public static String install(Consumer<List<String>> onInputs) {
        if (GraphicsEnvironment.isHeadless()) return "none (AWT is headless)";
        if (!Desktop.isDesktopSupported()) return "none (no desktop integration here)";
        Desktop desktop;
        try {
            desktop = Desktop.getDesktop();
        } catch (RuntimeException | LinkageError e) {
            return "none (" + e.getMessage() + ")";
        }
        List<String> installed = new ArrayList<>();
        if (desktop.isSupported(Desktop.Action.APP_OPEN_FILE)) {
            desktop.setOpenFileHandler(event -> onInputs.accept(
                    event.getFiles().stream().map(File::getAbsolutePath).toList()));
            installed.add("files");
        }
        if (desktop.isSupported(Desktop.Action.APP_OPEN_URI)) {
            desktop.setOpenURIHandler(
                    event -> onInputs.accept(List.of(event.getURI().toString())));
            installed.add("links");
        }
        return installed.isEmpty() ? "none (not supported on this platform)" : String.join(", ", installed);
    }
}
