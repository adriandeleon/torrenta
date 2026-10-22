package com.torrenta.ui;

import java.util.ArrayList;
import java.util.List;

import com.torrenta.engine.SessionStats;

import static com.torrenta.i18n.Messages.tr;

/** The Statistics window's rows: label and value, in sections. Pure, so the wording is tested. */
public final class StatsText {

    private static final String NONE = "—";

    private StatsText() {}

    /** One row of the table; a row with an empty label is a section heading. */
    public record Row(String section, String label, String value) {}

    /**
     * The section titles in display order, in the UI language. A method, not a constant: the
     * language is chosen after this class may have loaded.
     */
    public static List<String> sections() {
        return List.of(
                tr("stats.section.session"),
                tr("stats.section.allTime"),
                tr("stats.section.network"),
                tr("stats.section.disk"),
                tr("stats.section.torrents"));
    }

    /**
     * Which sections share a column, left to right, as indices into {@link #sections()}: the two
     * about bytes on the left, the three gauges on the right. A column is one grid, so its values
     * start at one x whatever section they are in.
     */
    public static List<List<Integer>> columns() {
        return List.of(List.of(0, 1), List.of(2, 3, 4));
    }

    /** Every row, in display order; the same labels in the same order for every snapshot. */
    public static List<Row> rows(SessionStats s) {
        List<Row> out = new ArrayList<>();
        List<String> sections = sections();
        String session = sections.get(0);
        out.add(new Row(session, tr("stats.downloaded"), bytes(s.sessionDownload())));
        out.add(new Row(session, tr("stats.uploaded"), bytes(s.sessionUpload())));
        out.add(new Row(session, tr("stats.shareRatio"), Format.ratio(s.sessionRatio())));
        out.add(new Row(session, tr("stats.active"), Format.duration(Math.max(0, s.sessionSeconds()))));
        out.add(new Row(session, tr("stats.receivedWithOverhead"), bytes(s.totalReceived())));
        out.add(new Row(session, tr("stats.sentWithOverhead"), bytes(s.totalSent())));
        out.add(new Row(session, tr("stats.wasted"), bytes(s.wasted())));
        String all = sections.get(1);
        out.add(new Row(all, tr("stats.downloaded"), bytes(s.allTimeDownload())));
        out.add(new Row(all, tr("stats.uploaded"), bytes(s.allTimeUpload())));
        out.add(new Row(all, tr("stats.shareRatio"), Format.ratio(s.allTimeRatio())));
        out.add(new Row(all, tr("stats.active"), Format.duration(Math.max(0, s.allTimeSeconds()))));
        String network = sections.get(2);
        out.add(new Row(network, tr("stats.connectedPeers"), count(s.peersConnected())));
        out.add(new Row(network, tr("stats.connecting"), count(s.peersHalfOpen())));
        out.add(new Row(network, tr("stats.dhtNodes"), count(s.dhtNodes())));
        String disk = sections.get(3);
        out.add(new Row(disk, tr("stats.read"), blocks(s.diskBlocksRead())));
        out.add(new Row(disk, tr("stats.written"), blocks(s.diskBlocksWritten())));
        out.add(new Row(disk, tr("stats.buffersInUse"), blocks(s.diskBlocksInUse())));
        out.add(new Row(disk, tr("stats.queuedJobs"), count(s.queuedDiskJobs())));
        String torrents = sections.get(4);
        SessionStats.Torrents t = s.torrents();
        out.add(new Row(torrents, tr("stats.torrents.downloading"), count(t.downloading())));
        out.add(new Row(torrents, tr("stats.torrents.seeding"), count(t.seeding())));
        out.add(new Row(torrents, tr("stats.torrents.checking"), count(t.checking())));
        out.add(new Row(torrents, tr("stats.torrents.queued"), count(t.queued())));
        out.add(new Row(torrents, tr("stats.torrents.stopped"), count(t.stopped())));
        out.add(new Row(torrents, tr("stats.torrents.errored"), count(t.error())));
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
        return tr("stats.legend", arrow, Bytes.rate(now), Bytes.rate(peak));
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
