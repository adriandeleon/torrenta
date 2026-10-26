package com.torrenta.stream;

import java.nio.file.Path;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.torrenta.engine.PieceMath;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadaheadTest {

    /** A 100 MiB file 3 MiB into a torrent of 1 MiB pieces: pieces 3 to 102. */
    static final Readahead.Geometry FILM = new Readahead.Geometry(3L << 20, 100L << 20, 1L << 20);

    @Test
    void theWindowIsThirtySecondsOfPlaybackTighteningTowardsThePlayhead() {
        List<Readahead.Deadline> plan = Readahead.plan(10L << 20, FILM, new BitSet(), 1L << 20, false);
        assertEquals(31, plan.size(), "pieces 13 to 43: thirty seconds at a megabyte a second, both ends in");
        assertEquals(new Readahead.Deadline(13, 0), plan.getFirst(), "the playhead's piece: now");
        assertEquals(new Readahead.Deadline(14, 1000), plan.get(1), "the next: in a second");
        assertEquals(43, plan.getLast().piece());
        for (int i = 1; i < plan.size(); i++)
            assertTrue(plan.get(i).millis() >= plan.get(i - 1).millis(), "nearest first");
        assertEquals(new PieceMath.Span(13, 43), Readahead.window(10L << 20, FILM, 1L << 20));
    }

    @Test
    void whatIsThereIsLeftOutAndAFasterFilmLooksFurther() {
        BitSet have = new BitSet();
        have.set(13, 20);
        List<Readahead.Deadline> plan = Readahead.plan(10L << 20, FILM, have, 1L << 20, false);
        assertEquals(20, plan.getFirst().piece());
        assertEquals(7000, plan.getFirst().millis(), "seven seconds of what is there before it");
        assertEquals(new PieceMath.Span(13, 73), Readahead.window(10L << 20, FILM, 2L << 20));
        assertEquals(new PieceMath.Span(13, 43), Readahead.window(10L << 20, FILM, 0), "unknown: a megabyte a second");
    }

    @Test
    void atTheStartTheEndsComeFirstAndTheWindowStaysInTheFile() {
        List<Readahead.Deadline> start = Readahead.plan(0, FILM, new BitSet(), 1L << 20, true);
        assertEquals(new Readahead.Deadline(3, 0), start.get(0), "the first piece");
        assertEquals(new Readahead.Deadline(102, 0), start.get(1), "the last: a container's index");
        assertEquals(1, start.stream().filter(d -> d.piece() == 3).count(), "once");
        List<Readahead.Deadline> end = Readahead.plan(99L << 20, FILM, new BitSet(), 1L << 20, false);
        assertEquals(List.of(new Readahead.Deadline(102, 0)), end, "never past the file's last piece");
        assertTrue(Readahead.plan(0, new Readahead.Geometry(0, 0, 1 << 20), new BitSet(), 0, true)
                .isEmpty());
    }

    @Test
    void thePlayerIsTheChosenOneElseMpvOrVlcWhereThePlatformKeepsThem() {
        Path home = Path.of("/home/someone");
        List<Path> linux = Player.candidates("Linux", Map.of("PATH", "/opt/bin:/usr/local/bin"), home, "");
        assertEquals(Path.of("/opt/bin/mpv"), linux.getFirst(), "mpv first, on the PATH");
        assertTrue(linux.contains(Path.of("/usr/local/bin/vlc")));
        assertTrue(linux.contains(Path.of("/var/lib/flatpak/exports/bin/org.videolan.VLC")));
        List<Path> mac = Player.candidates("Mac OS X", Map.of("PATH", "/usr/bin"), home, "");
        assertTrue(mac.contains(Path.of("/Applications/VLC.app/Contents/MacOS/VLC")));
        assertTrue(mac.contains(home.resolve("Applications/mpv.app/Contents/MacOS/mpv")));
        List<Path> windows = Player.candidates(
                "Windows 11", Map.of("Path", "C:\\tools", "ProgramFiles", "C:\\Program Files"), home, "D:\\VLC");
        assertTrue(windows.contains(Path.of("C:\\tools", "mpv.exe")), "Path, whatever its case");
        assertTrue(
                windows.indexOf(Path.of("D:\\VLC", "vlc.exe"))
                        < windows.indexOf(Path.of("C:\\Program Files", "VideoLAN", "VLC", "vlc.exe")),
                "the registry first");

        assertEquals(
                Optional.of(Path.of("/opt/bin/vlc")),
                Player.find("", linux, p -> p.endsWith("vlc")),
                "the first that is a program, the PATH before the usual places");
        assertEquals(Optional.of(Path.of("/my/player")), Player.find("/my/player", linux, p -> true));
        assertEquals(
                Optional.empty(),
                Player.find("/gone", linux, p -> false),
                "a choice that is not there is not replaced");
        assertEquals(Optional.empty(), Player.find(null, linux, p -> false));
        assertEquals(
                "C:\\Program Files\\VideoLAN\\VLC",
                Player.vlcInstallDir("\r\nHKEY_LOCAL_MACHINE\\SOFTWARE\\VideoLAN\\VLC\r\n"
                        + "    InstallDir    REG_SZ    C:\\Program Files\\VideoLAN\\VLC\r\n"));
        assertEquals("", Player.vlcInstallDir("ERROR: The system was unable to find the specified registry key"));
        assertEquals("VLC", Player.nameOf(Path.of("/Applications/VLC.app/Contents/MacOS/VLC")));
        assertEquals("mpv", Player.nameOf(Path.of("/usr/bin/mpv")));
        assertEquals("celluloid", Player.nameOf(Path.of("/usr/bin/celluloid")));
    }
}
