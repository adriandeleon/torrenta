package com.torrenta.os;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

/**
 * One Torrenta per config folder. The first to start takes a lock on {@code instance.lock} there,
 * listens on a loopback port it writes to {@code instance.port}, and keeps a random token in
 * {@code instance.token} (both files readable by the owner alone where the file system can say so).
 * A second one finds the lock held, reads port and token, sends its command line as an
 * {@link InstanceMessage}, and once the first has answered {@value InstanceMessage#OK} it is done,
 * with nothing printed, well inside a second. The first opens what it was sent through its usual
 * add path and brings its window to the front.
 *
 * <p>The lock is the arbiter, not the port file: a crashed instance's lock is released by the
 * operating system, so its leftover port file is taken over without a word, and two instances
 * launched in the same instant (a file manager opening two files) settle it without a race: the
 * loser retries the hand-off for a moment while the winner is still binding. A listener that
 * never answers (something else on a reused port, a token that does not match) is treated the
 * same as none. {@code --config-dir} scopes all of it, since the files live in the folder.
 *
 * <p>The accept loop is a daemon thread; messages that arrive before the window is up are kept
 * and delivered when {@link #deliverTo} is called. {@link #close} releases the lock and deletes
 * the port and token files.
 */
public final class SingleInstance implements AutoCloseable {

    public static final String LOCK_FILE = "instance.lock";
    public static final String PORT_FILE = "instance.port";
    public static final String TOKEN_FILE = "instance.token";

    /** How long a second instance gives the first to answer, in all. */
    static final int HANDOFF_TIMEOUT_MS = 900;
    /** One attempt's connect and read ceiling; the attempts repeat inside the hand-off timeout. */
    static final int ATTEMPT_TIMEOUT_MS = 300;

    private static final System.Logger LOG = System.getLogger(SingleInstance.class.getName());
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    /** What claiming the folder came to. */
    public sealed interface Outcome {
        /** This process is the instance: keep it for the life of the process, close it at the end. */
        record Primary(SingleInstance instance) implements Outcome {}

        /** The running instance took the inputs ({@code count} of them); this process has nothing to do. */
        record HandedOff(int count) implements Outcome {}
    }

    private final Path configDir;
    private final @Nullable FileChannel lockChannel;
    private final @Nullable FileLock lock;
    private final ServerSocket server;
    private final String token;
    private final Thread acceptor;
    private final Object pending = new Object();
    private final List<List<String>> queued = new ArrayList<>();
    private volatile @Nullable Consumer<List<String>> handler;
    private volatile boolean closed;

    private SingleInstance(
            Path configDir,
            @Nullable FileChannel lockChannel,
            @Nullable FileLock lock,
            ServerSocket server,
            String token) {
        this.configDir = configDir;
        this.lockChannel = lockChannel;
        this.lock = lock;
        this.server = server;
        this.token = token;
        acceptor = Thread.ofPlatform().daemon().name("single-instance").unstarted(this::accept);
    }

    /**
     * Becomes the instance for {@code configDir}, or hands {@code inputs} to the one already
     * running. Blocks for at most about a second, on the caller's thread, before the toolkit.
     *
     * @param inputs the command line's torrents, magnets and URLs, as given; file names are made
     *     absolute against {@code cwd} before they travel
     */
    public static Outcome claim(Path configDir, List<String> inputs, Path cwd) throws IOException {
        Files.createDirectories(configDir);
        List<String> absolute = InstanceMessage.absolute(inputs, cwd);
        Optional<Locked> locked = tryLock(configDir.resolve(LOCK_FILE));
        if (locked.isEmpty()) {
            // Held by a live process. Give it a moment: it may be between the lock and the bind.
            long deadline = System.currentTimeMillis() + HANDOFF_TIMEOUT_MS;
            do {
                if (handOff(configDir, absolute)) return new Outcome.HandedOff(absolute.size());
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } while (System.currentTimeMillis() < deadline);
            // The lock holder never answered: a process that is hung, or one that is not Torrenta
            // at all. A second window beats no window; said in the log, since it is unusual.
            LOG.log(
                    System.Logger.Level.WARNING,
                    "Another Torrenta holds the lock on " + configDir + " but did not answer");
            return new Outcome.Primary(bind(configDir, null, null));
        }
        Locked held = locked.get();
        try {
            return new Outcome.Primary(bind(configDir, held.channel(), held.lock()));
        } catch (IOException | RuntimeException e) {
            held.lock().release();
            held.channel().close();
            throw e;
        }
    }

    private record Locked(FileChannel channel, FileLock lock) {}

    /**
     * Whether a Torrenta holds {@code configDir} now, for a command that writes its files and
     * leaves ({@code --set-remote-password}): the lock is taken and let go at once.
     */
    public static boolean inUse(Path configDir) {
        try {
            Files.createDirectories(configDir);
            Optional<Locked> locked = tryLock(configDir.resolve(LOCK_FILE));
            if (locked.isEmpty()) return true;
            locked.get().lock().release();
            locked.get().channel().close();
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    /** Empty when another process (or this one, in a test) holds the lock. */
    private static Optional<Locked> tryLock(Path file) throws IOException {
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock != null) return Optional.of(new Locked(channel, lock));
        } catch (OverlappingFileLockException e) {
            // This JVM holds it already: the test's first instance.
        } catch (IOException e) {
            // A file system without locks (some network mounts): the port file alone decides.
            channel.close();
            LOG.log(System.Logger.Level.INFO, "No lock on " + file + ": " + e.getMessage());
            return Optional.empty();
        }
        channel.close();
        return Optional.empty();
    }

    /** True once the running instance has answered OK; false on any failure, which the caller may retry. */
    private static boolean handOff(Path configDir, List<String> inputs) {
        int port;
        String token;
        try {
            port = Integer.parseInt(
                    Files.readString(configDir.resolve(PORT_FILE)).trim());
            token = Files.readString(configDir.resolve(TOKEN_FILE)).trim();
        } catch (IOException | NumberFormatException e) {
            return false;
        }
        if (port <= 0 || port > 65535 || token.isEmpty()) return false;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), ATTEMPT_TIMEOUT_MS);
            socket.setSoTimeout(ATTEMPT_TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            out.write(InstanceMessage.encode(token, inputs).getBytes(StandardCharsets.UTF_8));
            out.flush();
            BufferedReader in =
                    new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            return InstanceMessage.OK.equals(in.readLine());
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Binds a port, writes the files, starts accepting. {@code channel} and {@code lock} may be null when no lock could be taken. */
    private static SingleInstance bind(Path configDir, @Nullable FileChannel channel, @Nullable FileLock lock)
            throws IOException {
        ServerSocket server = new ServerSocket();
        try {
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            String token = HexFormat.of().formatHex(random);
            writePrivate(configDir.resolve(TOKEN_FILE), token);
            writePrivate(configDir.resolve(PORT_FILE), String.valueOf(server.getLocalPort()));
        } catch (IOException | RuntimeException e) {
            server.close();
            throw e;
        }
        SingleInstance instance = new SingleInstance(configDir, channel, lock, server, readToken(configDir));
        instance.acceptor.start();
        return instance;
    }

    private static String readToken(Path configDir) throws IOException {
        return Files.readString(configDir.resolve(TOKEN_FILE)).trim();
    }

    /** Owner-only where the file system has modes; elsewhere (Windows) the folder's ACL is what there is. */
    static void writePrivate(Path file, String text) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text + "\n", StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(tmp, OWNER_ONLY);
        } catch (UnsupportedOperationException e) {
            // no POSIX modes here
        }
        Files.move(
                tmp,
                file,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private void accept() {
        while (!closed) {
            Socket socket;
            try {
                socket = server.accept();
            } catch (IOException e) {
                if (!closed) LOG.log(System.Logger.Level.WARNING, "Instance socket: " + e.getMessage());
                return;
            }
            // One client at a time, bounded: a stranger who connects and says nothing holds
            // nobody up for long, and a real hand-off is a few lines.
            try (socket) {
                socket.setSoTimeout(ATTEMPT_TIMEOUT_MS * 2);
                BufferedReader in =
                        new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                Optional<InstanceMessage> message = InstanceMessage.read(in);
                if (message.isEmpty() || !message.get().accepts(token)) {
                    LOG.log(System.Logger.Level.INFO, "Refused a connection on the instance port");
                    continue; // no answer: the sender treats silence as nobody home
                }
                // Queued (or handed to the window) before the answer goes out: the sender returns
                // on the answer, and whoever drains the queue right after must find the batch.
                deliver(message.get().inputs());
                OutputStream out = socket.getOutputStream();
                out.write((InstanceMessage.OK + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.INFO, "Instance hand-off failed: " + e.getMessage());
            }
        }
    }

    private void deliver(List<String> inputs) {
        @Nullable Consumer<List<String>> target;
        synchronized (pending) {
            target = handler;
            if (target == null) {
                queued.add(inputs);
                return;
            }
        }
        target.accept(inputs);
    }

    /**
     * Where received batches go, from the accept thread; what arrived before this call is handed
     * over now, in order. The window hops to the FX thread itself.
     */
    public void deliverTo(Consumer<List<String>> handler) {
        List<List<String>> backlog;
        synchronized (pending) {
            this.handler = handler;
            backlog = List.copyOf(queued);
            queued.clear();
        }
        backlog.forEach(handler);
    }

    /** The loopback port this instance listens on. */
    public int port() {
        return server.getLocalPort();
    }

    /** Where the lock and the files are. */
    public Path configDir() {
        return configDir;
    }

    /** Stops listening, deletes the port and token files, releases the lock. Idempotent. */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            server.close();
        } catch (IOException e) {
            // closing
        }
        try {
            Files.deleteIfExists(configDir.resolve(PORT_FILE));
            Files.deleteIfExists(configDir.resolve(TOKEN_FILE));
        } catch (IOException e) {
            LOG.log(System.Logger.Level.INFO, "Instance files not removed: " + e.getMessage());
        }
        try {
            if (lock != null) lock.release();
            if (lockChannel != null) lockChannel.close();
        } catch (IOException e) {
            // the OS drops it with the process anyway
        }
    }
}
