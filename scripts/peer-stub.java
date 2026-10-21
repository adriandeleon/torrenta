/*
 * A stand-in peer for captures and manual runs: downloads one .torrent from a peer on this
 * machine, at a limited rate, finding it by address rather than by tracker, DHT or local
 * discovery, so the app being captured gets a steady upload for its speed graphs. Needs
 * libtorrent4j and its native on the path, which scripts/dev-run.sh builds (target/deps):
 *
 *   java --module-path target/deps:target/classes --add-modules ALL-MODULE-PATH \
 *        -Dlibtorrent4j.jni.path=<config dir>/native/<version>/libtorrent4j.so \
 *        scripts/peer-stub.java <file.torrent> <save dir> <peer port> [bytes per second] [local address]
 *
 * The local address is 127.0.0.1 unless given: a seeder keeps a dead connection from an address
 * for a while and refuses another from the same one, so a second run against the same app uses
 * 127.0.0.2, a third 127.0.0.3, and so on (every 127.x address is the loopback on Linux and macOS).
 *
 * Prints the progress once a second and exits when the download is complete. Stops on Ctrl+C.
 */
import java.io.File;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.SessionParams;
import org.libtorrent4j.SettingsPack;
import org.libtorrent4j.TcpEndpoint;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;
import org.libtorrent4j.swig.settings_pack;

public class PeerStub {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println(
                    "usage: java scripts/peer-stub.java <file.torrent> <save dir> <peer port> [bytes/s] [local address]");
            System.exit(2);
        }
        TorrentInfo info = new TorrentInfo(new File(args[0]));
        File saveDir = new File(args[1]);
        int port = Integer.parseInt(args[2]);
        int limit = args.length > 3 ? Integer.parseInt(args[3]) : 1024 * 1024;
        String local = args.length > 4 ? args[4] : "127.0.0.1";

        SettingsPack pack = new SettingsPack();
        pack.listenInterfaces(local + ":0");
        pack.setString(settings_pack.string_types.outgoing_interfaces.swigValue(), local);
        pack.setBoolean(settings_pack.bool_types.enable_dht.swigValue(), false);
        pack.setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), false);
        pack.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), false);
        pack.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), false);
        SessionManager session = new SessionManager();
        session.start(new SessionParams(pack));
        session.download(info, saveDir);
        TorrentHandle handle = session.find(info.infoHash());
        // The session's limit spares peers on the local network; the torrent's own does not.
        if (handle != null && limit > 0) handle.setDownloadLimit(limit);
        long deadline = System.currentTimeMillis() + 10 * 60_000;
        long nextDial = 0;
        while (handle != null && handle.isValid() && System.currentTimeMillis() < deadline) {
            TorrentStatus status = handle.status();
            System.out.printf("peer-stub: %.1f%% at %d B/s from %d peer(s), %s %s%n",
                    status.progress() * 100, status.downloadPayloadRate(), status.numPeers(),
                    status.state(), status.errorCode().getMessage());
            if (status.isFinished()) break;
            // Dial when alone, then give the handshake a few seconds: a second connection from the
            // same address makes the seeder drop one as a duplicate, and the transfer never starts.
            if (status.numPeers() == 0 && System.currentTimeMillis() >= nextDial) {
                handle.swig().connect_peer(new TcpEndpoint("127.0.0.1", port).swig());
                nextDial = System.currentTimeMillis() + 4000;
            }
            Thread.sleep(1000);
        }
        session.stop();
        System.out.println("peer-stub: done");
    }
}
