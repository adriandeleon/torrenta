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
        return Response.ranged(contentType(file.name()), new Body(target, file))
                .withHeader("Cache-Control", "no-store");
    }

    private static Response notFound() {
        return Response.text(404, "Not Found");
    }

    /** The bytes of a range, a piece's worth at a time, each waited for before it is read. */
    private final class Body implements Response.Ranged {
        private final Target target;
        private final StreamFile file;

        Body(Target target, StreamFile file) {
            this.target = target;
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
            Playback playback = playbacks.computeIfAbsent(target, t -> new Playback(t, file));
            playback.open();
            try {
                playback.at(first, true);
                send(position, last, out, buffer, playback);
            } finally {
                playback.close();
            }
        }

        private void send(long position, long last, OutputStream out, ByteBuffer buffer, Playback playback)
                throws IOException {
            while (position <= last) {
                playback.at(position, false);
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
                        playback.served(n);
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

    /** The files being played now, each with its read-ahead: one per file, however many connections. */
    private final Map<Target, Playback> playbacks = new ConcurrentHashMap<>();

    /**
     * The pieces the read-ahead covers for torrent {@code id} now, first to last over every file
     * of it being played; empty when none is. The Overview's strip marks them.
     */
    public java.util.Optional<com.torrenta.engine.PieceMath.Span> window(String id) {
        int first = Integer.MAX_VALUE;
        int last = -1;
        for (Playback p : playbacks.values()) {
            if (!p.target.id().equals(id)) continue;
            com.torrenta.engine.PieceMath.Span w = p.window();
            if (w == null) continue;
            first = Math.min(first, w.first());
            last = Math.max(last, w.last());
        }
        return last < 0
                ? java.util.Optional.empty()
                : java.util.Optional.of(new com.torrenta.engine.PieceMath.Span(first, last));
    }

    /**
     * One file being played: its connections (a player opens several), where it plays, and the
     * read-ahead ({@link Readahead}), planned again at most once a second or at once on a seek,
     * cleared when the last connection closes. The playback rate is a megabyte a second until 30
     * seconds of serving say otherwise, held between 256 KiB/s and 8 MiB/s.
     */
    private final class Playback {
        private final Target target;
        private final StreamFile file;
        private final Readahead.Geometry geometry;
        private int connections;
        private boolean started;
        private long playhead = -1;
        private long plannedAt;
        private long servingSince;
        private long served;
        private volatile com.torrenta.engine.PieceMath.Span window;

        Playback(Target target, StreamFile file) {
            this.target = target;
            this.file = file;
            this.geometry = new Readahead.Geometry(file.offset(), file.size(), file.pieceLength());
        }

        com.torrenta.engine.PieceMath.Span window() {
            return window;
        }

        synchronized void open() {
            connections++;
            if (servingSince == 0) servingSince = System.nanoTime();
        }

        synchronized void served(long n) {
            served += n;
        }

        /** The player is at {@code position}: a new request ({@code seek}) plans at once, else once a second. */
        synchronized void at(long position, boolean seek) {
            boolean moved = seek && position != playhead;
            playhead = position;
            if (!moved && System.nanoTime() - plannedAt < java.util.concurrent.TimeUnit.SECONDS.toNanos(1)) return;
            plannedAt = System.nanoTime();
            long rate = bitrate();
            try {
                com.torrenta.engine.PieceMath.Span next = Readahead.window(position, geometry, rate);
                java.util.BitSet have = file.have(
                        Math.min(next.first(), geometry.firstPiece()), Math.max(next.last(), geometry.lastPiece()));
                Map<Integer, Integer> deadlines = new java.util.LinkedHashMap<>();
                for (Readahead.Deadline d : Readahead.plan(position, geometry, have, rate, !started))
                    deadlines.put(d.piece(), d.millis());
                file.deadlines(deadlines);
                started = true;
                window = next;
            } catch (EngineException e) {
                // The torrent went: the response's own wait says so.
            }
        }

        /** Served bytes over time, once there is enough of it to say; else the default. */
        private long bitrate() {
            double seconds = (System.nanoTime() - servingSince) / 1e9;
            if (seconds < Readahead.WINDOW_SECONDS) return Readahead.DEFAULT_BITRATE;
            long measured = (long) (served / seconds);
            return Math.max(256L * 1024, Math.min(8L * 1024 * 1024, measured));
        }

        synchronized void close() {
            if (--connections > 0) return;
            playbacks.remove(target, this);
            com.torrenta.engine.PieceMath.Span w = window;
            window = null;
            try {
                if (w != null)
                    file.clearDeadlines(
                            Math.min(w.first(), geometry.firstPiece()), Math.max(w.last(), geometry.lastPiece()));
            } catch (EngineException e) {
                // Gone already: nothing left to clear.
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
