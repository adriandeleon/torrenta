package com.torrenta.migrate;

import java.nio.file.Path;
import java.util.List;

import com.torrenta.engine.EngineSettings;
import com.torrenta.engine.libtorrent.LibtorrentEngine;
import com.torrenta.store.TorrentStore;

/**
 * A libtorrent session for the import tests that finds no one and no one finds: loopback, any
 * port, no UPnP, no DHT, no local discovery, no peer exchange. Left at the defaults, these tests
 * listened on every interface at 6881 and announced the fixtures' info-hashes to the public DHT and
 * the LAN, where another run of the suite (or the other client's test, whose fixtures are the same
 * torrents) could come asking for pieces; an import is about what is on disk, not about peers.
 */
final class QuietEngine {

    private QuietEngine() {}

    static LibtorrentEngine open(Path downloads, TorrentStore store) {
        EngineSettings d = EngineSettings.defaults();
        EngineSettings.Protocol p = d.protocol();
        EngineSettings quiet = new EngineSettings(
                0,
                false,
                "127.0.0.1",
                d.ipv6(),
                false,
                d.maxConnections(),
                d.maxConnectionsPerTorrent(),
                d.uploadSlots(),
                d.proxy(),
                "",
                1080,
                "",
                "",
                false,
                new EngineSettings.Protocol(false, false, false, false, p.encryption(), false, List.of()),
                d.speed(),
                d.queue());
        return new LibtorrentEngine(() -> downloads, store, quiet);
    }
}
