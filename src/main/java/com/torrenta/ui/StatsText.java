package com.torrenta.ui;

import java.util.ArrayList;
import java.util.List;

import com.torrenta.engine.SessionStats;

/** The Statistics window's rows: label and value, in sections. Pure, so the wording is tested. */
public final class StatsText {

    private static final String NONE = "—";

    private StatsText() {}

    /** One row of the table; a row with an empty label is a section heading. */
    public record Row(String section, String label, String value) {}

    public static final List<String> SECTIONS = List.of("This session", "All time", "Network", "Disk", "Torrents");

    /** Every row, in display order; the same labels in the same order for every snapshot. */
    public static List<Row> rows(SessionStats s) {
        List<Row> out = new ArrayList<>();
        String session = SECTIONS.get(0);
        out.add(new Row(session, "Downloaded", bytes(s.sessionDownload())));
        out.add(new Row(session, "Uploaded", bytes(s.sessionUpload())));
        out.add(new Row(session, "Share ratio", Format.ratio(s.sessionRatio())));
        out.add(new Row(session, "Active", Format.duration(Math.max(0, s.sessionSeconds()))));
        out.add(new Row(session, "Received, with overhead", bytes(s.totalReceived())));
        out.add(new Row(session, "Sent, with overhead", bytes(s.totalSent())));
        out.add(new Row(session, "Wasted", bytes(s.wasted())));
        String all = SECTIONS.get(1);
        out.add(new Row(all, "Downloaded", bytes(s.allTimeDownload())));
        out.add(new Row(all, "Uploaded", bytes(s.allTimeUpload())));
        out.add(new Row(all, "Share ratio", Format.ratio(s.allTimeRatio())));
        out.add(new Row(all, "Active", Format.duration(Math.max(0, s.allTimeSeconds()))));
        String network = SECTIONS.get(2);
        out.add(new Row(network, "Connected peers", count(s.peersConnected())));
        out.add(new Row(network, "Connecting", count(s.peersHalfOpen())));
        out.add(new Row(network, "DHT nodes", count(s.dhtNodes())));
        String disk = SECTIONS.get(3);
        out.add(new Row(disk, "Read", blocks(s.diskBlocksRead())));
        out.add(new Row(disk, "Written", blocks(s.diskBlocksWritten())));
        out.add(new Row(disk, "Buffers in use", blocks(s.diskBlocksInUse())));
        out.add(new Row(disk, "Queued jobs", count(s.queuedDiskJobs())));
        String torrents = SECTIONS.get(4);
        SessionStats.Torrents t = s.torrents();
        out.add(new Row(torrents, "Downloading", count(t.downloading())));
        out.add(new Row(torrents, "Seeding", count(t.seeding())));
        out.add(new Row(torrents, "Checking", count(t.checking())));
        out.add(new Row(torrents, "Queued", count(t.queued())));
        out.add(new Row(torrents, "Stopped", count(t.stopped())));
        out.add(new Row(torrents, "Errored", count(t.error())));
        return out;
    }

    /** A byte count, or a dash for a counter the engine does not have. */
    static String bytes(long n) {
        return n < 0 ? NONE : Bytes.human(n);
    }

    /** A block count as the bytes it stands for. */
    static String blocks(long n) {
        return n < 0 ? NONE : Bytes.human(n * SessionStats.BLOCK_BYTES);
    }

    static String count(long n) {
        return n < 0 ? NONE : String.valueOf(n);
    }

    /** The legend under a graph: the rate now and the window's peak. */
    public static String legend(String arrow, long now, long peak) {
        return arrow + " " + Bytes.rate(now) + "  (peak " + Bytes.rate(peak) + ")";
    }

    /** One line for the capture hook: the rows that say something, {@code label=value}. */
    public static String summary(SessionStats s) {
        StringBuilder sb = new StringBuilder();
        for (Row r : rows(s)) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(r.label()).append('=').append(r.value());
        }
        return sb.toString();
    }
}
