package com.torrenta.session;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.torrenta.engine.Torrent;

/**
 * A condition's "pause" rule ({@link SpeedMode.Rule#PAUSE}): when the condition starts, every
 * torrent that runs is paused and held; when it ends, the held ones still here and still paused
 * are resumed, and only those. Unlike the leak check's pause, nothing is paused again while it
 * holds: a person who resumes one on battery means it. Downloads from links are curl's and are
 * left alone. Pure; the session applies a step.
 */
final class ConditionPause {

    private ConditionPause() {}

    /**
     * @param pause ids to pause now
     * @param resume ids to resume now
     * @param held what is held after the step
     */
    record Step(List<String> pause, List<String> resume, Set<String> held) {
        boolean nothing() {
            return pause.isEmpty() && resume.isEmpty();
        }
    }

    static Step next(boolean pauseNow, boolean pausedBefore, List<Torrent> torrents, Set<String> held) {
        if (pauseNow && !pausedBefore) {
            List<String> pause = torrents.stream()
                    .filter(t -> !t.direct() && !t.paused())
                    .map(Torrent::id)
                    .toList();
            Set<String> after = new LinkedHashSet<>(held);
            after.addAll(pause);
            return new Step(pause, List.of(), Set.copyOf(after));
        }
        if (!pauseNow && pausedBefore) {
            List<String> resume = torrents.stream()
                    .filter(t -> held.contains(t.id()) && t.paused())
                    .map(Torrent::id)
                    .toList();
            return new Step(List.of(), resume, Set.of());
        }
        return new Step(List.of(), List.of(), held);
    }
}
