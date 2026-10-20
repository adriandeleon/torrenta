module com.torrenta {
    requires javafx.controls;
    requires javafx.graphics;

    // The engine. An automatic module (no module-info, no Automatic-Module-Name), so the name is
    // derived from the jar file name — renaming or shading the jar silently breaks the build. The
    // per-OS native jar (libtorrent4j-linux etc.) is NOT required here: it holds no classes, only
    // lib/<arch>/libtorrent4j.{so,dylib,dll}, which NativeLibrary reads as a class-path or
    // module-path resource. It therefore has to be on the path by other means: ALL-MODULE-PATH in
    // scripts/dev-run.sh, the classpath under the javafx plugin, addModules in a future jlink.
    requires libtorrent4j;

    // Settings, the store and the engine log through j.u.l; nothing is lost without a handler.
    requires java.logging;

    // Fetching a .torrent from a URL for the Add dialog, and the update check.
    requires java.net.http;
    // The update check's release payload gets a real parser.
    requires com.fasterxml.jackson.core;

    // ImageIO, for the development screen capture only. Nothing else here touches AWT, and
    // App.main forces it headless before any AWT class can load.
    requires java.desktop;

    exports com.torrenta;
}
