package com.torrenta.ui;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.torrenta.engine.Torrent;
import com.torrenta.net.LeakCheck;

/**
 * {@code connection.pauseWhenExposed}: while the leak check says Exposed, every torrent that is
 * not paused by the person is paused and held; once it says Protected again, or nothing is bound,
 * or the switch is turned off, the held ones are resumed, and only those. Halted and Unknown keep
 * what is held as it is: nothing leaks while halted, and unknown is not proof either way. Downloads
 * from links are curl's, not the swarm's, and are left alone. Pure; the window applies a step.
 */
final class ExposurePause {

    private ExposurePause() {}

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

    static Step next(LeakCheck.Verdict verdict, boolean enabled, List<Torrent> torrents, Set<String> held) {
        boolean release = !enabled || verdict == LeakCheck.Verdict.PROTECTED || verdict == LeakCheck.Verdict.UNBOUND;
        if (release) {
            // A held torrent removed in the meantime is not resumed: it is no longer there.
            Set<String> present = new LinkedHashSet<>();
            for (Torrent t : torrents) present.add(t.id());
            return new Step(List.of(), held.stream().filter(present::contains).toList(), Set.of());
        }
        if (verdict != LeakCheck.Verdict.EXPOSED) return new Step(List.of(), List.of(), held);
        Set<String> after = new LinkedHashSet<>(held);
        List<String> pause = torrents.stream()
                .filter(t -> !t.direct() && !t.paused())
                .map(Torrent::id)
                .toList();
        after.addAll(pause);
        return new Step(pause, List.of(), Set.copyOf(after));
    }
}
