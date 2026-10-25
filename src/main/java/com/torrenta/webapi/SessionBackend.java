package com.torrenta.webapi;

import java.nio.file.Path;

import com.torrenta.engine.EngineSettings;
import com.torrenta.session.Session;
import com.torrenta.store.Settings;

/** The API over the running {@link Session} and this machine's {@link Settings}: the window's and headless's. */
public final class SessionBackend implements WebApi.Backend {

    private final Session session;
    private final Settings settings;

    public SessionBackend(Session session, Settings settings) {
        this.session = session;
        this.settings = settings;
    }

    Session session() {
        return session;
    }

    @Override
    public EngineSettings engineSettings() {
        return settings.engineSettings();
    }

    @Override
    public Path defaultSavePath() {
        return settings.downloadDir();
    }
}
