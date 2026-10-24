package com.torrenta.ui;

import com.torrenta.engine.ShareLimit;
import com.torrenta.engine.Torrent;

import static com.torrenta.i18n.Messages.tr;

/**
 * The sentence a row ends in, saying what the torrent is doing in words: "62% · 38 peers, 12
 * seeds", "Seeding to 2 peers · ratio 1.84 of 2", "Queued #1 · starts when a download slot frees".
 * The lead is the part that takes the state's colour; the rest is muted.
 *
 * <p>Pure, and tested, like {@link Format}: the window only lays the two parts out.
 */
public record StatusLine(RowState state, String lead, String rest) implements Comparable<StatusLine> {

    /** The bullet between the sentence's clauses; punctuation, the same in every language. */
    private static final String SEP = " · ";

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
        if (t.direct()
                && state != RowState.ERROR
                && state != RowState.MOVING
                && state != RowState.QUEUED
                && state != RowState.PAUSED) return direct(t, state, quietSeconds);
        return switch (state) {
            case ERROR -> new StatusLine(state, tr("statusLine.error"), SEP + t.error());
            case MOVING -> new StatusLine(state, tr("statusLine.movingFiles"), SEP + percent);
            case CHECKING ->
                new StatusLine(state, tr("statusLine.checkingFiles"), SEP + tr("statusLine.verified", percent));
            case METADATA ->
                new StatusLine(
                        state,
                        tr("statusLine.fetchingMetadata"),
                        SEP + (t.peers() == 0 ? tr("statusLine.lookingForPeers") : peers(t.peers())));
            case QUEUED ->
                new StatusLine(
                        state,
                        t.queuePosition() >= 0
                                ? tr("statusLine.queuedAt", t.queuePosition() + 1)
                                : tr("statusLine.queued"),
                        SEP
                                + (RowState.complete(t)
                                        ? tr("statusLine.seedsWhenSlotFrees")
                                        : tr("statusLine.startsWhenSlotFrees")));
            case PAUSED -> new StatusLine(state, tr("statusLine.pausedByYou"), SEP + percent);
            case DONE -> new StatusLine(state, tr("statusLine.done"), doneRest(t, limit));
            case STALLED ->
                new StatusLine(
                        state,
                        tr("statusLine.stalled", Format.coarse(quietSeconds)),
                        SEP
                                + percent
                                + SEP
                                + (t.peers() == 0
                                        ? tr("statusLine.noPeers")
                                        : tr("statusLine.peersNoneSending", t.peers())));
            case DOWNLOADING ->
                new StatusLine(state, percent, SEP + swarm(t) + (t.sequential() ? SEP + tr("statusLine.inOrder") : ""));
            case SEEDING -> new StatusLine(state, tr("statusLine.seeding"), seedingRest(t, limit));
        };
    }

    /**
     * A download from a link: its server where a torrent has its swarm, the bytes where the size
     * is not known, and no seeding or ratio after it.
     */
    private static StatusLine direct(Torrent t, RowState state, long quietSeconds) {
        String host = t.trackers().isEmpty() ? "" : t.trackers().getFirst();
        String got = t.size() > 0 ? Format.wholePercent(t.progress()) : Bytes.human(t.done());
        return switch (state) {
            case DONE -> new StatusLine(state, tr("statusLine.done"), SEP + tr("statusLine.directFrom", host));
            case STALLED ->
                new StatusLine(
                        state,
                        tr("statusLine.stalled", Format.coarse(quietSeconds)),
                        SEP + got + SEP + tr("statusLine.directQuiet", host));
            default -> new StatusLine(state, got, SEP + tr("statusLine.directFrom", host));
        };
    }

    private static String seedingRest(Torrent t, ShareLimit limit) {
        String who = t.peers() == 0 ? SEP + tr("statusLine.noOneAsking") : " " + tr("statusLine.seedingTo", t.peers());
        if (limit.ratio() > 0) {
            return who + SEP + tr("statusLine.ratioOf", Format.ratio(ratio(t)), ShareLimit.formatRatio(limit.ratio()));
        }
        return who;
    }

    private static String doneRest(Torrent t, ShareLimit limit) {
        if (limit.limited() && limit.reached(t.uploaded(), t.downloaded(), t.size(), t.seedingTime())) {
            return limit.ratio() > 0 && limit.reached(t.uploaded(), t.downloaded(), t.size(), 0)
                    ? SEP + tr("statusLine.ratioGoalReached", ShareLimit.formatRatio(limit.ratio()))
                    : SEP + tr("statusLine.seedingTimeGoalReached");
        }
        return SEP + tr("statusLine.stopped");
    }

    /** The ratio as {@link ShareLimit} measures it: over what was downloaded, else over the size. */
    private static double ratio(Torrent t) {
        long base = t.downloaded() > 0 ? t.downloaded() : t.size();
        return base > 0 ? (double) t.uploaded() / base : (t.uploaded() > 0 ? Double.POSITIVE_INFINITY : 0);
    }

    private static String swarm(Torrent t) {
        if (t.peers() == 0) return tr("statusLine.noPeersYet");
        if (t.seeds() == 0) return peers(t.peers());
        return tr("statusLine.peersSeeds", t.peers(), t.seeds());
    }

    private static String peers(int n) {
        return tr("statusLine.peers", n);
    }
}
