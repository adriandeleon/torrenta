package com.torrenta.ui;

import java.util.List;

import com.torrenta.engine.Torrent;

import static com.torrenta.i18n.Messages.tr;

/** Rules behind the torrent actions. Pure, so they are tested rather than assumed. */
public final class Actions {

    private Actions() {}

    /**
     * Space on a selection: resume if every selected torrent is paused, otherwise pause. Mixed
     * selections pause, because a person who presses Space on a running torrent expects it to
     * stop, and a paused one in the same selection staying paused costs nothing.
     */
    public static boolean shouldResume(List<Torrent> selected) {
        return !selected.isEmpty() && selected.stream().allMatch(Torrent::paused);
    }

    /** Sequential on a selection: turn it on unless every selected torrent already has it. */
    public static boolean shouldEnableSequential(List<Torrent> selected) {
        return !selected.stream().allMatch(Torrent::sequential);
    }

    /**
     * {@code Paused 1 torrent}, {@code Paused 3 torrents}, {@code Paused 2 torrents, 1 failed: …}.
     * The verb arrives translated ({@code actions.verb.*}); the count and the failures are the
     * catalogue's own sentence.
     */
    public static String outcome(String verb, int done, List<String> failures) {
        if (failures.isEmpty()) return tr("actions.outcome", verb, done);
        return tr("actions.outcomeFailed", verb, done, failures.size(), failures.get(0));
    }

    /** The confirmation for removal, worded by what it will do. */
    public static String removeQuestion(int count, boolean deleteFiles) {
        return deleteFiles ? tr("actions.removeQuestion.delete", count) : tr("actions.removeQuestion.keep", count);
    }
}
