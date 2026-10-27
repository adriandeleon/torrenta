package com.torrenta.ui;

import java.util.List;
import java.util.Optional;

import com.torrenta.engine.Connectivity;
import com.torrenta.engine.SessionStats;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentState;
import com.torrenta.net.InterfaceWatch;

import static com.torrenta.i18n.Messages.tr;

/**
 * The status bar's network half (the redesign's §7): the port as a dot with words, the DHT's
 * nodes, and the free space where downloads go. Pure: the window hands in what the engine and the
 * file system said, and gets back text, a tooltip and a style.
 *
 * <p>"Open" needs proof, a peer from outside this network connecting in, and there is nothing to
 * prove without a running torrent, so the port is "checking" until either a peer comes or {@link
 * #BLOCKED_AFTER_SECONDS} of running torrents pass without one, when it is "blocked".
 */
final class NetworkStatus {

    /** Five minutes of running torrents with no peer from outside: the port is not reachable. */
    static final long BLOCKED_AFTER_SECONDS = 300;

    /** Under this much free space where downloads go, the figure turns to a warning. */
    static final long LOW_SPACE_BYTES = 2L * 1024 * 1024 * 1024;

    /** The dot's colour, as a style class on it. */
    enum Dot {
        OPEN("port-open"),
        CHECKING("port-checking"),
        BLOCKED("port-blocked"),
        DOWN("port-down");

        final String styleClass;

        Dot(String styleClass) {
            this.styleClass = styleClass;
        }
    }

    /** The port's entry. */
    record Port(String text, String tooltip, Dot dot) {}

    /** The free-space entry; {@code low} colours it. */
    record Free(String text, String tooltip, boolean low) {}

    private NetworkStatus() {}

    /** The port's words, or empty before the engine has opened (or failed to open) one. */
    static Optional<Port> port(Connectivity c, long runningSeconds) {
        return port(c, runningSeconds, InterfaceWatch.State.UNBOUND);
    }

    /**
     * The same with the binding: an interface that is down or gone is the port's whole story (it
     * reaches no one, and nothing leaks around it), said before whether the port is open. A
     * binding that works is named in the tooltip with its addresses, and so is this machine's
     * address as the swarm sees it, once libtorrent has settled on one.
     */
    static Optional<Port> port(Connectivity c, long runningSeconds, InterfaceWatch.State bound) {
        // Said even with no port: libtorrent opens nothing on an interface that is not there, and
        // sends no listen alert either way.
        if (bound.reachesNoOne()) {
            boolean down = bound.presence() == InterfaceWatch.Presence.DOWN;
            return Optional.of(new Port(
                    tr(down ? "status.port.boundDown" : "status.port.boundMissing", bound.label()),
                    tr(down ? "status.port.boundDown.tip" : "status.port.boundMissing.tip", bound.label()),
                    Dot.DOWN));
        }
        if (c.port() <= 0) return Optional.empty();
        Dot dot;
        String tip;
        if (!c.listening()) {
            dot = Dot.DOWN;
            tip = tr("status.port.down.tip");
        } else if (c.incoming()) {
            dot = Dot.OPEN;
            tip = tr("status.port.open.tip");
        } else if (runningSeconds >= BLOCKED_AFTER_SECONDS) {
            dot = Dot.BLOCKED;
            tip = tr("status.port.blocked.tip", runningSeconds / 60);
        } else {
            dot = Dot.CHECKING;
            tip = tr("status.port.checking.tip");
        }
        String text =
                switch (dot) {
                    case OPEN -> tr("status.port.open", c.port());
                    case CHECKING -> tr("status.port.checking", c.port());
                    case BLOCKED -> tr("status.port.blocked", c.port());
                    case DOWN -> tr("status.port.down", c.port());
                };
        if (c.listening()) {
            switch (c.mapping()) {
                case MAPPED -> tip += "\n" + tr("status.port.mapped");
                case FAILED -> tip += "\n" + tr("status.port.mapFailed");
                case NONE -> {}
            }
        }
        if (bound.presence() == InterfaceWatch.Presence.UP)
            tip += "\n" + tr("status.port.boundTo", bound.label(), String.join(", ", bound.addresses()));
        if (!c.externalAddress().isEmpty()) tip += "\n" + tr("status.port.seenAs", c.externalAddress());
        return Optional.of(new Port(text, tip, dot));
    }

    /**
     * "DHT 38 nodes, bootstrapping", "DHT 412 nodes", "DHT off"; empty before the engine is up.
     * {@code nodes} is the stats counter, {@link SessionStats#UNKNOWN} when the library has none.
     */
    static Optional<String> dht(Connectivity c, long nodes) {
        if (c.port() <= 0 && !c.listenFailed()) return Optional.empty();
        if (!c.dhtEnabled()) return Optional.of(tr("status.dht.off"));
        String count = nodes < 0 ? tr("status.dht.on") : tr("status.dht.nodes", nodes);
        return Optional.of(c.dhtBootstrapped() ? count : tr("status.dht.bootstrapping", count));
    }

    /** "412 GB free on /home", warning under {@link #LOW_SPACE_BYTES}. */
    static Free free(FreeSpace.Disk disk, String folder) {
        return new Free(FreeSpace.sentence(disk, -1), tr("status.free.tip", folder), disk.free() < LOW_SPACE_BYTES);
    }

    /**
     * A torrent the port can be reached for: in the session, not paused, not queued, not stopped
     * by an error. A checking one is not yet talking to peers.
     */
    static boolean anyRunning(List<Torrent> torrents) {
        for (Torrent t : torrents) {
            if (t.paused() || t.queued() || !t.error().isEmpty()) continue;
            if (t.state() == TorrentState.CHECKING || t.state() == TorrentState.UNKNOWN) continue;
            return true;
        }
        return false;
    }

    /**
     * How long torrents have run with the port listening and unproven: the evidence for "blocked".
     * Started over by a new port. Fed once a poll from the poll thread.
     */
    static final class Clock {
        private int port;
        private long seconds;
        private long last = -1;

        long tick(Connectivity c, boolean running, long nowSeconds) {
            if (c.port() != port) {
                port = c.port();
                seconds = 0;
                last = nowSeconds;
                return 0;
            }
            if (last >= 0 && running && c.listening() && !c.incoming())
                seconds += Math.max(0, Math.min(nowSeconds - last, 5));
            last = nowSeconds;
            return seconds;
        }
    }
}
