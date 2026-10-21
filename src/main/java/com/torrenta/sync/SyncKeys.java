package com.torrenta.sync;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Which settings travel between machines, and the one shape they travel in.
 *
 * <p>Everything in the settings file syncs except what describes one installation: the sync
 * connection itself, folders, interfaces and programs of this machine, the clocks of the last
 * checks, the per-torrent entries and the sidebar's choices and folds (the torrents are this
 * machine's), and the proxy password, which does not belong in a repository even a private one. Pure.
 */
public final class SyncKeys {

    /**
     * {@code torrent.<hash>.*} describes a torrent of this machine, which another has not got;
     * {@code ui.sidebar.*} is what the sidebar has chosen and folded (whether it shows,
     * {@code ui.sidebar} without the dot, travels); {@code automation.*} names programs on this
     * machine (the {@code notify.*} switches travel); {@code create.*} is the Create Torrent
     * dialog's last folder and trackers, this machine's; {@code os.*} is this machine's desktop
     * (whether it is registered as the handler, whether its dock shows a badge).
     */
    static final List<String> LOCAL_PREFIXES =
            List.of("sync.", "torrent.", "ui.sidebar.", "automation.", "create.", "os.");

    static final List<String> LOCAL_KEYS = List.of(
            "download.dir",
            "download.incompleteDir",
            "connection.interface",
            "ipfilter.file",
            "ipfilter.lastRefreshEpochMs",
            "updates.lastCheckEpochMs",
            "updates.dismissedVersion",
            "proxy.password");

    private SyncKeys() {}

    public static boolean isSynced(String key) {
        if (key == null || key.isBlank()) return false;
        for (String prefix : LOCAL_PREFIXES) if (key.startsWith(prefix)) return false;
        return !LOCAL_KEYS.contains(key);
    }

    /** The synced entries of {@code properties}, sorted. */
    public static Map<String, String> synced(Properties properties) {
        Map<String, String> out = new TreeMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (isSynced(key)) out.put(key, properties.getProperty(key));
        }
        return out;
    }

    /**
     * The repository file: one {@code key=value} line per entry, sorted, escaped exactly as
     * {@link Properties#store} does (one entry at a time, so the order is ours and there is no date
     * comment), so equal data is equal bytes on every machine.
     */
    public static String render(Map<String, String> entries) {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(entries).forEach((key, value) -> {
            Properties one = new Properties();
            one.setProperty(key, value == null ? "" : value);
            StringWriter w = new StringWriter();
            try {
                one.store(w, null);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            for (String line : w.toString().split("\\R")) {
                if (!line.startsWith("#") && !line.isBlank()) sb.append(line).append('\n');
            }
        });
        return sb.toString();
    }

    /** The entries of a repository file, synced keys only; an unreadable text is empty. */
    public static Map<String, String> parse(String text) {
        Properties p = new Properties();
        try {
            p.load(new StringReader(text == null ? "" : text));
        } catch (IOException | IllegalArgumentException e) {
            return Map.of();
        }
        return synced(p);
    }
}
