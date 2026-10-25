package com.torrenta.ui;

import java.util.List;

import com.torrenta.net.ExitCheck.Exits;
import com.torrenta.net.InterfaceWatch;
import com.torrenta.net.LeakCheck.Exit;
import com.torrenta.net.LeakCheck.Verdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pure: no toolkit, English catalogue. */
class LeakViewTest {

    private static final InterfaceWatch.State TUN =
            InterfaceWatch.of("tun0", List.of(new InterfaceWatch.Nic("tun0", true, List.of("10.8.0.2"))));

    @Test
    void protectedNamesTheExitAndTheCardHasTheThreeAddresses() {
        Exits exits = new Exits(Exit.seen("198.51.100.7"), Exit.seen("203.0.113.20"), 1);
        LeakView view = LeakView.of(Verdict.PROTECTED, TUN, "198.51.100.7", exits, true);
        assertEquals("Protected", view.pill());
        assertEquals("leak-protected", view.styleClass());
        assertEquals(
                "Traffic through tun0 leaves as 198.51.100.7, and nothing the check can see goes out another way.",
                view.sentence());
        assertEquals(
                List.of(
                        new LeakView.Row("Through tun0", "198.51.100.7", true),
                        new LeakView.Row("By the default route", "203.0.113.20", true),
                        new LeakView.Row("Peers see", "198.51.100.7", true)),
                view.rows());
    }

    @Test
    void exposedSaysWhatThePeersSeeAndWhatItIsNot() {
        Exits exits = new Exits(Exit.seen("198.51.100.7"), Exit.seen("203.0.113.20"), 1);
        LeakView view = LeakView.of(Verdict.EXPOSED, TUN, "203.0.113.20", exits, true);
        assertEquals("Exposed", view.pill());
        assertEquals(
                "Peers see this machine as 203.0.113.20, the address of the default route, not of tun0: something leaves around the binding.",
                view.sentence());
    }

    @Test
    void anUncheckedBindingPointsAtTheSettingAndSaysWhatIsMissing() {
        LeakView off = LeakView.of(Verdict.UNKNOWN, TUN, "", Exits.NONE, false);
        assertEquals("Unchecked", off.pill());
        assertEquals(
                "Turn on the exit check in Settings → Connection to know where traffic through tun0 leaves.",
                off.sentence());
        assertEquals(
                List.of("not checked", "not checked", "nobody has said yet"),
                off.rows().stream().map(LeakView.Row::value).toList());
        LeakView failed = LeakView.of(
                Verdict.UNKNOWN, TUN, "", new Exits(Exit.failed("timed out"), Exit.seen("203.0.113.20"), 1), true);
        assertEquals("failed: timed out", failed.rows().get(0).value());
    }
}
