package com.torrenta.webapi;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLContext;

import com.torrenta.http.Limits;
import com.torrenta.http.Tls;
import com.torrenta.http.WebServer;

import static com.torrenta.i18n.Messages.tr;

/**
 * The API's server through its life: {@link #configure} with each new {@link WebApiConfig} starts
 * it, stops it, rebinds it when the address, port or certificate changed, and otherwise hands the
 * running routes the new names and credentials. The window and the headless run each own one;
 * neither toolkit nor engine is needed to run it, only a {@link WebApi.Backend}. Thread-safe.
 */
public final class WebApiService implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(WebApiService.class.getName());

    public enum State {
        OFF,
        RUNNING,
        FAILED
    }

    /**
     * What the Settings page and the log say.
     *
     * @param freePort for a port in use, one that was free on that address a moment ago; else 0
     */
    public record Status(State state, String text, int freePort) {
        public static Status off() {
            return new Status(State.OFF, tr("webapi.status.off"), 0);
        }
    }

    private final WebApi.Backend backend;
    private final LongSupplier clock;
    private final Sessions sessions = new Sessions(new SecureRandom());
    private WebApiConfig config;
    private WebServer server;
    private WebApi api;
    private volatile Status status = Status.off();

    public WebApiService(WebApi.Backend backend, LongSupplier clock) {
        this.backend = backend;
        this.clock = clock;
    }

    public Status status() {
        return status;
    }

    /** Where it listens, while it runs: port 0 in a config becomes the port found here. */
    public synchronized java.util.Optional<InetSocketAddress> address() {
        return server == null ? java.util.Optional.empty() : java.util.Optional.of(server.address());
    }

    /** Brings the server in line with {@code next}; the status it ends in. */
    public synchronized Status configure(WebApiConfig next) {
        WebApiConfig before = config;
        config = next;
        if (!next.enabled()) {
            stopServer();
            return status = Status.off();
        }
        java.util.Optional<String> problem = next.problem();
        if (problem.isPresent()) {
            stopServer();
            return status = new Status(State.FAILED, problem.get(), 0);
        }
        if (server != null && !next.rebinds(before)) {
            api.update(next);
            return status = running(next);
        }
        stopServer();
        InetAddress bind = next.bindAddress().orElseThrow();
        try {
            SSLContext tls = next.https()
                    ? Tls.fromPkcs12(next.httpsFile(), next.httpsPassword().toCharArray())
                    : null;
            WebApi fresh = new WebApi(backend, next, sessions, clock);
            // Whoever was logged in through the old socket stays so only if the credentials did.
            if (next.credentialsChanged(before)) sessions.closeAll();
            server = WebServer.start(new InetSocketAddress(bind, next.port()), fresh.router(), Limits.DEFAULT, tls);
            api = fresh;
            return status = running(next);
        } catch (BindException e) {
            return status = new Status(
                    State.FAILED,
                    tr("webapi.status.portInUse", String.valueOf(next.port())),
                    freePort(bind, next.port()));
        } catch (GeneralSecurityException | IOException e) {
            LOG.log(Level.WARNING, "Remote access did not start on " + next.url(), e);
            String reason = e.getMessage() == null ? e.toString() : e.getMessage();
            return status = new Status(State.FAILED, tr("webapi.status.failed", reason), 0);
        }
    }

    private Status running(WebApiConfig c) {
        int port = server.address().getPort();
        String url = c.url().replace(":" + c.port() + "/", ":" + port + "/");
        return new Status(State.RUNNING, tr("webapi.status.running", url), 0);
    }

    private void stopServer() {
        if (server != null) server.close();
        server = null;
        api = null;
    }

    @Override
    public synchronized void close() {
        stopServer();
        sessions.closeAll();
        status = Status.off();
    }

    /**
     * A port free on {@code address} now: the next few after {@code taken} first, so 8080 becomes
     * 8081 rather than a number nobody remembers; else any; 0 if none could be found.
     */
    static int freePort(InetAddress address, int taken) {
        for (int port = taken + 1; port <= Math.min(65535, taken + 20); port++) if (free(address, port)) return port;
        try (ServerSocket socket = new ServerSocket(0, 1, address)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            return 0;
        }
    }

    private static boolean free(InetAddress address, int port) {
        try (ServerSocket socket = new ServerSocket(port, 1, address)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
