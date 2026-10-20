package com.torrenta.ui;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreeSpaceTest {

    private static final long GIB = 1L << 30;

    @Test
    void theSentenceSaysWhatIsFreeAndWhatTheTorrentLeaves() {
        FreeSpace.Disk disk = new FreeSpace.Disk(1000 * GIB, 412 * GIB, "/data");
        assertEquals(
                "412.0 GiB free on /data · 5.9 GiB leaves 406.1 GiB", FreeSpace.sentence(disk, (long) (5.9 * GIB)));
        assertEquals("412.0 GiB free on /data", FreeSpace.sentence(disk, -1), "a magnet's size is unknown");
        assertEquals(
                "412.0 GiB free on /data · 500.0 GiB is 88.0 GiB more than that", FreeSpace.sentence(disk, 500 * GIB));
    }

    @Test
    void theBarIsWhatIsUsedThenWhatTheTorrentTakes() {
        FreeSpace.Disk disk = new FreeSpace.Disk(1000, 400, "/");
        FreeSpace.Bar bar = FreeSpace.bar(disk, 100);
        assertEquals(0.6, bar.used(), 1e-9);
        assertEquals(0.1, bar.torrent(), 1e-9);
        assertTrue(bar.fits());

        FreeSpace.Bar over = FreeSpace.bar(disk, 900);
        assertEquals(0.4, over.torrent(), 1e-9, "cut at what is free");
        assertFalse(over.fits());

        assertEquals(0, FreeSpace.bar(disk, -1).torrent(), "unknown size: no segment");
        assertEquals(
                new FreeSpace.Bar(0, 0, true), FreeSpace.bar(new FreeSpace.Disk(0, 0, "/"), 5), "no division by 0");
    }

    @Test
    void aDiskIsNeverMoreFreeThanItIsBig() {
        FreeSpace.Disk disk = new FreeSpace.Disk(100, 200, "/");
        assertEquals(100, disk.free());
        assertEquals(0, new FreeSpace.Disk(-1, -1, "/").total());
    }

    @Test
    void theVolumeIsTheMountPointOrTheDrive() {
        assertEquals("/data", FreeSpace.volume("/data (/dev/sdb1)", Path.of("/data/films")));
        assertEquals("/", FreeSpace.volume("/ (/dev/nvme0n1p2)", Path.of("/home")));
        Path probe = Path.of("anything").toAbsolutePath();
        assertEquals(probe.getRoot().toString(), FreeSpace.volume("Data (D:)", probe), "the path's root otherwise");
    }

    @Test
    void freeSpaceOfAPathThatDoesNotExistYetUsesItsNearestParent() {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        assertTrue(FreeSpace.disk(tmp.resolve("torrenta-does-not-exist").resolve("deeper"))
                .isPresent());
        assertTrue(
                FreeSpace.disk(tmp).get().total() >= FreeSpace.disk(tmp).get().free());
        assertEquals(
                FreeSpace.of(tmp).getAsLong() / GIB,
                FreeSpace.of(tmp.resolve("nope")).getAsLong() / GIB,
                "same volume, to the GiB");
    }
}
