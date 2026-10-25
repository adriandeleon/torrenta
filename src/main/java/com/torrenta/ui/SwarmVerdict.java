package com.torrenta.ui;

import com.torrenta.engine.SwarmPreview;

import static com.torrenta.i18n.Messages.tr;

/**
 * The Add dialog's line about the swarm, from what the probe has seen: a kind, which colours its
 * dot, and the sentence. First rule wins. Seeds decide first, since a seed holds every piece; with
 * none, the pieces the peers were seen to hold decide, once they have had time to show them.
 * Never a time estimate: the probe downloads nothing, so there is no rate to measure.
 */
record SwarmVerdict(Kind kind, String sentence) {

    enum Kind {
        /** Not enough seen yet to say. */
        LOOKING,
        /** Seeds enough that one leaving changes little. */
        HEALTHY,
        /** A seed or two, or no seed but every piece among the peers: it can finish, perhaps slowly. */
        THIN,
        /** No seed, and pieces of the chosen files that no peer met holds. */
        INCOMPLETE,
        /** No one at all, after long enough to have found someone. */
        DEAD
    }

    /** Seeds from which a swarm is healthy. */
    static final int HEALTHY_SEEDS = 3;

    /** How long after the file list the peers' pieces are trusted to have shown. */
    static final long SETTLE_MS = 20_000;

    /** How long without anyone before the swarm is called dead. */
    static final long DEAD_MS = 45_000;

    static SwarmVerdict of(SwarmPreview p, long now) {
        if (p.seeds() >= HEALTHY_SEEDS)
            return new SwarmVerdict(Kind.HEALTHY, tr("swarm.healthy", p.seeds(), p.peers()));
        if (p.seeds() > 0) return new SwarmVerdict(Kind.THIN, tr("swarm.thin", p.seeds(), p.peers()));
        // Nothing ticked reads as the whole torrent, which is what the person will tick next.
        int total = p.wanted() > 0 ? p.wanted() : p.pieces();
        int held = p.wanted() > 0 ? p.wantedSeen() : p.seen();
        if (p.hasPieces() && total > 0 && held >= total) return new SwarmVerdict(Kind.THIN, tr("swarm.thinNoSeed"));
        boolean anyone = p.peers() > 0 || p.connected() > 0;
        if (anyone && p.hasPieces() && total > 0 && now - p.metadataAt() >= SETTLE_MS) {
            // Floored, so 99.6 % never reads as all of it.
            double share = Math.floor(100.0 * held / total) / 100.0;
            return new SwarmVerdict(Kind.INCOMPLETE, tr("swarm.incomplete", share));
        }
        if (!anyone && now - p.started() >= DEAD_MS) {
            return new SwarmVerdict(Kind.DEAD, tr("swarm.dead", AddDialog.elapsed((now - p.started()) / 1000)));
        }
        return new SwarmVerdict(Kind.LOOKING, tr("swarm.looking", Math.max(p.peers(), p.connected())));
    }

    /** The style class of the line's dot. */
    String styleClass() {
        return "swarm-" + kind.name().toLowerCase(java.util.Locale.ROOT);
    }
}
