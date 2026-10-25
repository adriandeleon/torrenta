package com.torrenta;

import java.nio.file.Path;
import java.util.Locale;

import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineProfile;
import com.torrenta.engine.direct.DirectDownloads;
import com.torrenta.engine.direct.WithDownloads;
import com.torrenta.engine.libtorrent.LibtorrentEngine;
import com.torrenta.engine.transmission.TransmissionEngine;
import com.torrenta.i18n.Messages;
import com.torrenta.store.ProfileStore;
import com.torrenta.store.Settings;
import com.torrenta.store.StatsStore;
import com.torrenta.store.TorrentStore;

/**
 * What the window and a headless run do the same way on the way up: the interface language, where
 * the engine's native library is unpacked, which profile starts, and how an engine is built. No
 * JavaFX here: {@code torrenta --headless} must never load a toolkit class.
 */
final class Startup {

    private Startup() {}

    /**
     * The catalogue, before any text is built: what is built reads it as it is made, so a change in
     * Settings applies on restart (Termina's rule). Only an explicit choice moves the JVM's default
     * locale, so the toolkit's own OK/Cancel and the number formats follow; "System" keeps the OS
     * locale whole, country and all.
     */
    static void language(Settings settings) {
        String language = Messages.resolve(
                Messages.isAutomatic(settings.uiLanguage()) ? null : settings.uiLanguage(),
                Messages.available().keySet(),
                Locale.getDefault().getLanguage());
        Messages.init(language);
        Locale.setDefault(Messages.defaultLocale(settings.uiLanguage(), language, Locale.getDefault()));
    }

    /** The unpacked engine lives beside the settings unless a -D says otherwise (the tests do). */
    static void nativeDir(Path configDir) {
        if (System.getProperty("torrenta.nativeDir") == null) {
            System.setProperty("torrenta.nativeDir", configDir.resolve("native").toString());
        }
    }

    /**
     * Which engine starts: the settings' profile (Settings → Engine); one gone from the file falls
     * back to the local engine.
     */
    static EngineProfile profile(Path configDir, Settings settings) {
        ProfileStore profiles = new ProfileStore(configDir);
        profiles.load();
        return profiles.byId(settings.engineProfile()).orElse(EngineProfile.LOCAL);
    }

    /** libtorrent in this process with its stores, or a Transmission daemon reached as the profile says. */
    static Engine createEngine(EngineProfile profile, Settings settings, Path configDir) {
        Engine torrents = profile.isLocal()
                ? new LibtorrentEngine(
                        settings::downloadDir,
                        new TorrentStore(configDir.resolve(TorrentStore.FOLDER)),
                        settings.engineSettings(),
                        new StatsStore(configDir.resolve(StatsStore.FILE_NAME)))
                : new TransmissionEngine(profile, settings::downloadDir, settings.engineSettings());
        // Links that are not torrents go to curl, on this machine, whichever engine has the torrents.
        DirectDownloads links = new DirectDownloads(
                configDir,
                () -> settings.downloadDir().toString(),
                settings::directOptions,
                AppInfo.NAME + "/" + AppInfo.VERSION);
        return new WithDownloads(torrents, links, settings.engineSettings());
    }
}
