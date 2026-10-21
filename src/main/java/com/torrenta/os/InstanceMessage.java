package com.torrenta.os;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.torrenta.engine.Inputs;

/**
 * What a second Torrenta sends the running one over the loopback socket: the token it read from
 * the config folder, then its inputs, one per line, and a blank line to end. UTF-8 throughout. The
 * running instance answers {@value #OK} on its own line once it has accepted the batch.
 *
 * <p>Pure, so the shape is tested without a socket: {@link #encode} and {@link #parse} are each
 * other's inverse, the caps refuse a flood from a stranger on the port, and {@link #absolute}
 * turns the file names a shell handed over into paths the running instance can open from its own
 * working directory.
 *
 * @param token what the sender read from {@code instance.token}; compared in constant time
 * @param inputs {@code .torrent} paths, magnet links and URLs, in the order given; may be empty,
 *     which asks only for the window to come to the front
 */
public record InstanceMessage(String token, List<String> inputs) {

    /** The reply that says the batch was taken. */
    public static final String OK = "ok";

    /** More inputs than anybody opens at once; a stranger's flood stops here. */
    public static final int MAX_INPUTS = 100;

    /** The longest line accepted, in characters: a magnet with a hundred trackers is well under it. */
    public static final int MAX_LINE = 16 * 1024;

    public InstanceMessage {
        inputs = List.copyOf(inputs);
    }

    /** The wire form. An input with a line break in it cannot travel and is refused. */
    public static String encode(String token, List<String> inputs) {
        if (token == null || token.isBlank() || token.contains("\n") || token.contains("\r")) {
            throw new IllegalArgumentException("a token is one non-empty line");
        }
        if (inputs.size() > MAX_INPUTS) throw new IllegalArgumentException("more than " + MAX_INPUTS + " inputs");
        StringBuilder sb = new StringBuilder(token).append('\n');
        for (String input : inputs) {
            if (input.contains("\n") || input.contains("\r")) {
                throw new IllegalArgumentException("an input is one line: " + input);
            }
            if (input.isBlank()) continue; // a blank line would end the message early
            if (input.length() > MAX_LINE) throw new IllegalArgumentException("an input is too long to send");
            sb.append(input).append('\n');
        }
        return sb.append('\n').toString();
    }

    /** The whole text, as {@link #encode} wrote it; empty when it is not a message. */
    public static Optional<InstanceMessage> parse(String text) {
        try {
            return read(new BufferedReader(new java.io.StringReader(text)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Reads one message off a stream: the token line, inputs until a blank line. Empty when the
     * stream ends first, the token is missing, or a cap is passed; the reader is then not to be
     * trusted and the caller closes it.
     */
    public static Optional<InstanceMessage> read(BufferedReader in) throws IOException {
        String token = line(in);
        if (token == null || token.isEmpty()) return Optional.empty();
        List<String> inputs = new ArrayList<>();
        while (true) {
            String line = line(in);
            if (line == null) return Optional.empty();
            if (line.isEmpty()) return Optional.of(new InstanceMessage(token, inputs));
            if (inputs.size() == MAX_INPUTS) return Optional.empty();
            inputs.add(line);
        }
    }

    /** A line of at most {@link #MAX_LINE} characters, the end of line stripped; null past the cap or at the end. */
    private static String line(BufferedReader in) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = in.read();
            if (c < 0) return sb.isEmpty() ? null : sb.toString();
            if (c == '\n') return sb.toString();
            if (c == '\r') continue;
            if (sb.length() == MAX_LINE) return null;
            sb.append((char) c);
        }
    }

    /** Whether this message carries {@code expected}; constant time, so a guess learns nothing from the clock. */
    public boolean accepts(String expected) {
        if (expected == null || expected.isBlank()) return false;
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * File names made absolute against {@code cwd}, the shell's working directory, because the
     * running instance has its own; magnet links and URLs pass through untouched.
     */
    public static List<String> absolute(List<String> inputs, Path cwd) {
        List<String> out = new ArrayList<>(inputs.size());
        for (String input : inputs) {
            String text = input.trim();
            if (text.isEmpty()) continue;
            out.add(
                    Inputs.classify(text) == Inputs.Kind.FILE
                            ? cwd.resolve(text).toAbsolutePath().normalize().toString()
                            : text);
        }
        return List.copyOf(out);
    }
}
