package com.torrenta.ui;

import com.torrenta.engine.ShareLimit;
import com.torrenta.engine.Torrent;

/**
 * The sentence a row ends in, saying what the torrent is doing in words: "62% · 38 peers, 12
 * seeds", "Seeding to 2 peers · ratio 1.84 of 2", "Queued #1 · starts when a download slot frees".
 * The lead is the part that takes the state's colour; the rest is muted.
 *
 * <p>Pure, and tested, like {@link Format}: the window only lays the two parts out.
 */
public record StatusLine(RowState state, String lead, String rest) implements Comparable<StatusLine> {

    public String text() {
        return lead + rest;
    }

    /** By state (in {@link RowState}'s order), then by the sentence, so equal states stay grouped. */
    @Override
    public int compareTo(StatusLine other) {
        int byState = state.compareTo(other.state);
        return byState != 0 ? byState : text().compareTo(other.text());
    }

    /**
     * @param quietSeconds how long it has been downloading without receiving; 0 when receiving
     * @param limit the share limit in force for it (its own, else the global one); never null
     */
    public static StatusLine of(Torrent t, long quietSeconds, ShareLimit limit) {
        RowState state = RowState.of(t, quietSeconds);
        String percent = Format.wholePercent(t.progress());
        return switch (state) {
            case ERROR -> new StatusLine(state, "Error", " · " + t.error());
            case MOVING -> new StatusLine(state, "Moving files", " · " + percent);
            case CHECKING -> new StatusLine(state, "Checking files", " · " + percent + " verified");
            case METADATA ->
                new StatusLine(
                        state, "Fetching metadata", t.peers() == 0 ? " · looking for peers" : " · " + peers(t.peers()));
            case QUEUED ->
                new StatusLine(
                        state,
                        t.queuePosition() >= 0 ? "Queued #" + (t.queuePosition() + 1) : "Queued",
                        RowState.complete(t) ? " · seeds when a slot frees" : " · starts when a download slot frees");
            case PAUSED -> new StatusLine(state, "Paused by you", " · " + percent);
            case DONE -> new StatusLine(state, "Done", doneRest(t, limit));
            case STALLED ->
                new StatusLine(
                        state,
                        "Stalled " + Format.coarse(quietSeconds),
                        " · " + percent
                                + (t.peers() == 0 ? " · no peers" : " · " + peers(t.peers()) + ", none sending"));
            case DOWNLOADING ->
                new StatusLine(state, percent, " · " + swarm(t) + (t.sequential() ? " · in order" : ""));
            case SEEDING -> new StatusLine(state, "Seeding", seedingRest(t, limit));
        };
    }

    private static String seedingRest(Torrent t, ShareLimit limit) {
        String who = t.peers() == 0 ? " · no one asking" : " to " + peers(t.peers());
        if (limit.ratio() > 0) {
            return who + " · ratio " + Format.ratio(ratio(t)) + " of " + ShareLimit.formatRatio(limit.ratio());
        }
        return who;
    }

    private static String doneRest(Torrent t, ShareLimit limit) {
        if (limit.limited() && limit.reached(t.uploaded(), t.downloaded(), t.size(), t.seedingTime())) {
            return limit.ratio() > 0 && limit.reached(t.uploaded(), t.downloaded(), t.size(), 0)
                    ? " · ratio goal " + ShareLimit.formatRatio(limit.ratio()) + " reached, stopped"
                    : " · seeding time goal reached, stopped";
        }
        return " · stopped";
    }

    /** The ratio as {@link ShareLimit} measures it: over what was downloaded, else over the size. */
    private static double ratio(Torrent t) {
        long base = t.downloaded() > 0 ? t.downloaded() : t.size();
        return base > 0 ? (double) t.uploaded() / base : (t.uploaded() > 0 ? Double.POSITIVE_INFINITY : 0);
    }

    private static String swarm(Torrent t) {
        if (t.peers() == 0) return "no peers yet";
        if (t.seeds() == 0) return peers(t.peers());
        return peers(t.peers()) + ", " + t.seeds() + (t.seeds() == 1 ? " seed" : " seeds");
    }

    private static String peers(int n) {
        return n + (n == 1 ? " peer" : " peers");
    }
}
