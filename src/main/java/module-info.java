module com.torrenta {
    requires javafx.controls;
    requires javafx.graphics;
    // The Nord and Cupertino control themes; the module opens its theme package, so the
    // stylesheets resolve to URLs from here.
    requires atlantafx.base;

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
    // The Torznab client reads RSS with StAX.
    requires java.xml;
    // The update check's release payload gets a real parser.
    requires com.fasterxml.jackson.core;

    // Java2D for the tray icon and the development screen capture, and the AWT system tray on
    // Windows and macOS. App.main forces AWT headless before any AWT class can load, except where
    // that tray is wanted (tray.Trays.usesAwt).
    requires java.desktop;

    // The Linux tray backend: a StatusNotifierItem on the session bus. The transport and the
    // slf4j->JUL bridge are service providers, required explicitly so jlink includes them.
    requires org.freedesktop.dbus;
    requires org.freedesktop.dbus.transport.jre;
    requires org.slf4j.jul;

    exports com.torrenta;

    // dbus-java reflects over the exported StatusNotifierItem object and its structs.
    exports com.torrenta.tray to
            org.freedesktop.dbus;

    opens com.torrenta.tray to
            org.freedesktop.dbus;
}
