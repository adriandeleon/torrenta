package com.torrenta.ui;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.torrenta.engine.Inputs;

/**
 * The first-run screen's rules ({@link Welcome}): what the clipboard offer says, how the download
 * folder is written, and how a translated sentence is cut around the nodes set into it.
 */
final class WelcomeText {

    /** Stands in for a node inside a translated sentence; never typed by a person or a translator. */
    static final String MARK = "\u0000";

    /** What the clipboard holds, as the card names it: a magnet or a link, and its name if it has one. */
    record Offer(Inputs.Kind kind, String name) {}

    private WelcomeText() {}

    /**
     * The offer for the clipboard's text, or empty when it is nothing to add. A magnet is named by
     * its {@code dn=}; a link by its last path segment, else its host.
     */
    static Optional<Offer> offer(String clipboard) {
        if (clipboard == null || !Inputs.looksAddable(clipboard)) return Optional.empty();
        String text = clipboard.trim();
        return switch (Inputs.classify(text)) {
            case MAGNET ->
                Optional.of(new Offer(
                        Inputs.Kind.MAGNET, MagnetNames.displayName(text).strip()));
            case URL -> Optional.of(new Offer(Inputs.Kind.URL, linkName(text)));
            case FILE -> Optional.empty();
        };
    }

    /** The file a link names: its last path segment, decoded, else its host, else nothing. */
    static String linkName(String link) {
        try {
            URI uri = URI.create(link.trim());
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            int slash = path.lastIndexOf('/');
            String last = slash < 0 ? path : path.substring(slash + 1);
            if (!last.isBlank()) return URLDecoder.decode(last, StandardCharsets.UTF_8);
            return uri.getHost() == null ? "" : uri.getHost();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /**
     * The download folder as the footer writes it: under the home folder it starts with {@code ~}
     * where that is the custom (not Windows), with forward slashes; anywhere else, as it is.
     */
    static String folder(Path dir, Path home, boolean tilde) {
        Path abs = dir.toAbsolutePath().normalize();
        if (tilde && home != null) {
            Path h = home.toAbsolutePath().normalize();
            if (abs.equals(h)) return "~";
            if (abs.startsWith(h)) return "~/" + h.relativize(abs).toString().replace('\\', '/');
        }
        return abs.toString();
    }

    /**
     * A sentence formatted with {@link #MARK} for each argument, cut at the marks: the text before
     * the first node, between the nodes and after the last. A translation may move the nodes, so
     * the window never assumes which piece is empty.
     */
    static List<String> parts(String formatted) {
        return List.of(formatted.split(MARK, -1));
    }
}
