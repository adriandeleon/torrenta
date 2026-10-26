package com.torrenta.stream;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineException;
import com.torrenta.engine.StreamFile;
import com.torrenta.http.Limits;
import com.torrenta.http.Request;
import com.torrenta.http.Response;
import com.torrenta.http.Router;
import com.torrenta.http.WebServer;

/**
 * A torrent's file served to a player on this machine while it downloads: on loopback only, on a
 * port the system picks, started the first time a URL is asked for. A URL is {@code
 * /s/<token>/<id>/<fileIndex>/<name>}: the token is new for each URL handed out and lives as long
 * as the app, so what reaches the server is only what the app gave a player; anything else, a
 * listing included, is 404. A {@code Range} is answered with 206 and its {@code Content-Range}
 * (HTTP's own plumbing, {@link Response.Ranged}); each piece a range needs is asked for and
 * waited on before its bytes are read from the file on disk, so a player that seeks ahead waits
 * for that piece rather than reads zeros.
 */
public final class StreamServer implements AutoCloseable {

    /** How long a piece is waited on before the response is cut short; the player asks again. */
    static final Duration PIECE_TIMEOUT = Duration.ofSeconds(60);

    /** A wait is sliced this fine, so a close ends it without waiting out the timeout. */
    private static final Duration SLICE = Duration.ofMillis(250);

    /** What a token opens. */
    record Target(String id, int fileIndex) {}

    private final Supplier<Engine> engine;
    private final Map<String, Target> tokens = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private WebServer server;
    private volatile boolean closed;

    /** @param engine the engine in use now; null while there is none */
    public StreamServer(Supplier<Engine> engine) {
        this(engine, PIECE_TIMEOUT);
    }

    /** With another wait for a piece: a test's. */
    StreamServer(Supplier<Engine> engine, Duration pieceTimeout) {
        this.engine = engine;
        this.pieceTimeout = pieceTimeout;
    }

    private final Duration pieceTimeout;

    /** A URL a player can open for file {@code fileIndex} of torrent {@code id}; starts the server once. */
    public synchronized String url(String id, int fileIndex, String name) throws IOException {
        if (closed) throw new IOException("the stream server is closed");
        if (server == null)
            server = WebServer.start(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), router(), Limits.DEFAULT, null);
        byte[] bytes = new byte[18];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.put(token, new Target(id, fileIndex));
        InetSocketAddress at = server.address();
        return "http://" + at.getAddress().getHostAddress() + ":" + at.getPort() + "/s/" + token + "/" + id + "/"
                + fileIndex + "/"
                + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    }

    Router router() {
        Router router = new Router();
        router.prefix("GET", "/s/", this::serve);
        router.prefix("HEAD", "/s/", this::serve);
        return router;
    }

    /** The file a URL names, if its token opens exactly that file. */
    Response serve(Request request) {
        String[] parts = request.path().substring("/s/".length()).split("/", -1);
        if (parts.length != 4) return notFound();
        Target target = tokens.get(parts[0]);
        if (target == null
                || !target.id().equals(parts[1])
                || !Integer.toString(target.fileIndex()).equals(parts[2])) return notFound();
        Engine e = engine.get();
        if (e == null) return Response.text(503, "Service Unavailable");
        StreamFile file;
        try {
            file = e.stream(target.id(), target.fileIndex());
        } catch (EngineException ex) {
            return notFound();
        }
        return Response.ranged(contentType(file.name()), new Body(file)).withHeader("Cache-Control", "no-store");
    }

    private static Response notFound() {
        return Response.text(404, "Not Found");
    }

    /** The bytes of a range, a piece's worth at a time, each waited for before it is read. */
    private final class Body implements Response.Ranged {
        private final StreamFile file;

        Body(StreamFile file) {
            this.file = file;
        }

        @Override
        public long size() {
            return file.size();
        }

        @Override
        public void write(long first, long length, OutputStream out) throws IOException {
            long position = first;
            long last = first + length - 1;
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            while (position <= last) {
                long chunkEnd = Math.min(last, file.chunkEnd(position));
                waitFor(position, chunkEnd);
                // Opened for each chunk: the name loses its .part and the file moves on finish.
                try (FileChannel channel = FileChannel.open(path(), StandardOpenOption.READ)) {
                    long at = position;
                    while (at <= chunkEnd) {
                        buffer.clear();
                        buffer.limit((int) Math.min(buffer.capacity(), chunkEnd - at + 1));
                        int n = channel.read(buffer, at);
                        if (n < 0) throw new IOException("the file ends before its piece does");
                        out.write(buffer.array(), 0, n);
                        at += n;
                    }
                }
                position = chunkEnd + 1;
            }
        }

        /** In slices ({@link #SLICE}), so a close or an engine's stop ends the wait at once. */
        private void waitFor(long from, long to) throws IOException {
            long giveUp = System.nanoTime() + pieceTimeout.toNanos();
            try {
                while (!file.await(from, to, SLICE)) {
                    if (closed) throw new IOException("the stream server is closed");
                    if (System.nanoTime() > giveUp) throw new IOException("no piece for bytes " + from + "–" + to);
                }
            } catch (EngineException e) {
                throw new IOException(e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }

        private java.nio.file.Path path() throws IOException {
            try {
                return file.path();
            } catch (EngineException e) {
                throw new IOException(e.getMessage(), e);
            }
        }
    }

    /** What a player is told the bytes are, by the name's extension. Pure. */
    static String contentType(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "mkv" -> "video/x-matroska";
            case "mp4", "m4v" -> "video/mp4";
            case "webm" -> "video/webm";
            case "avi" -> "video/x-msvideo";
            case "mov" -> "video/quicktime";
            case "ts" -> "video/mp2t";
            case "mp3" -> "audio/mpeg";
            case "flac" -> "audio/flac";
            case "ogg", "oga" -> "audio/ogg";
            case "opus" -> "audio/opus";
            case "m4a" -> "audio/mp4";
            case "wav" -> "audio/wav";
            default -> "application/octet-stream";
        };
    }

    /** The port it listens on; 0 before the first URL. */
    public synchronized int port() {
        return server == null ? 0 : server.address().getPort();
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (server != null) server.close();
        server = null;
    }
}
