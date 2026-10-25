package com.torrenta.engine;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Against real archives: zip's and 7z's own, and RAR 4 and RAR 5 built to the format and checked
 * by unrar (it lists them, tests the plain ones' CRCs, and calls the others encrypted).
 */
class ArchiveProbeTest {

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = ArchiveProbeTest.class.getResourceAsStream("archives/" + name)) {
            if (in == null) throw new IOException("no fixture " + name);
            return in.readAllBytes();
        }
    }

    /** The probe as the engine runs it: the whole file in view, tail and all. */
    private static ArchiveProbe.Verdict verdict(String name) throws IOException {
        byte[] b = fixture(name);
        return ArchiveProbe.of(b, ArchiveProbe.needsTail(name) ? b : new byte[0], b.length);
    }

    @Test
    void zipTellsZipCryptoAndAesFromPlain() throws IOException {
        assertEquals(ArchiveProbe.Verdict.CLEAR, verdict("zip-plain.zip"));
        assertEquals(ArchiveProbe.Verdict.ENCRYPTED, verdict("zip-encrypted.zip"));
        assertEquals(ArchiveProbe.Verdict.ENCRYPTED, verdict("zip-aes.zip"));
    }

    @Test
    void rar5TellsEncryptedFilesAndHiddenNamesFromPlain() throws IOException {
        assertEquals(ArchiveProbe.Verdict.CLEAR, verdict("rar5-plain.rar"));
        assertEquals(ArchiveProbe.Verdict.ENCRYPTED, verdict("rar5-encrypted.rar"));
        assertEquals(ArchiveProbe.Verdict.ENCRYPTED, verdict("rar5-headers-encrypted.rar"));
    }

    @Test
    void rar4TellsEncryptedFilesAndHiddenNamesFromPlain() throws IOException {
        assertEquals(ArchiveProbe.Verdict.CLEAR, verdict("rar4-plain.rar"));
        assertEquals(ArchiveProbe.Verdict.ENCRYPTED, verdict("rar4-encrypted.rar"));
        assertEquals(ArchiveProbe.Verdict.ENCRYPTED, verdict("rar4-headers-encrypted.rar"));
    }

    @Test
    void sevenZipReadsItsHeaderAtTheEnd() throws IOException {
        assertEquals(ArchiveProbe.Verdict.CLEAR, verdict("7z-plain.7z"));
        assertEquals(ArchiveProbe.Verdict.ENCRYPTED, verdict("7z-encrypted.7z"));
        assertEquals(ArchiveProbe.Verdict.ENCRYPTED, verdict("7z-headers-encrypted.7z"));
        byte[] b = fixture("7z-encrypted.7z");
        assertEquals(
                ArchiveProbe.Verdict.UNKNOWN,
                ArchiveProbe.of(b, new byte[0], b.length),
                "without its tail a 7z says nothing");
    }

    @Test
    void whatIsNotAnArchiveOrIsCutShortIsUnknownAndNeverThrows() throws IOException {
        assertEquals(ArchiveProbe.Verdict.UNKNOWN, ArchiveProbe.of("plain text".getBytes(), new byte[0], 10));
        assertEquals(ArchiveProbe.Verdict.UNKNOWN, ArchiveProbe.of(new byte[0], new byte[0], 0));
        for (String name : List.of("zip-plain.zip", "rar5-plain.rar", "rar4-plain.rar", "7z-plain.7z")) {
            byte[] b = fixture(name);
            for (int cut = 0; cut < b.length; cut++) {
                byte[] head = java.util.Arrays.copyOf(b, cut);
                ArchiveProbe.of(head, head, b.length); // must not throw
            }
        }
        Random random = new Random(42);
        for (int i = 0; i < 2000; i++) {
            byte[] noise = new byte[random.nextInt(200)];
            random.nextBytes(noise);
            byte[] prefix = List.of(
                            new byte[] {0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x01, 0x00},
                            new byte[] {0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x00},
                            new byte[] {0x50, 0x4b, 0x03, 0x04},
                            new byte[] {0x37, 0x7a, (byte) 0xbc, (byte) 0xaf, 0x27, 0x1c})
                    .get(i % 4);
            System.arraycopy(prefix, 0, noise, 0, Math.min(prefix.length, noise.length));
            ArchiveProbe.of(noise, noise, noise.length); // garbage after a signature: no exception
        }
    }

    @Test
    void onlyTheVolumeASetOpensWithIsRead() {
        assertTrue(ArchiveProbe.firstVolume("Film/Film.rar"));
        assertTrue(ArchiveProbe.firstVolume("Film/Film.part1.rar"));
        assertTrue(ArchiveProbe.firstVolume("Film/Film.part01.rar"));
        assertFalse(ArchiveProbe.firstVolume("Film/Film.part02.rar"));
        assertFalse(ArchiveProbe.firstVolume("Film/Film.r00"));
        assertTrue(ArchiveProbe.firstVolume("Pack.ZIP"));
        assertTrue(ArchiveProbe.firstVolume("Pack.7z.001"));
        assertFalse(ArchiveProbe.firstVolume("Pack.7z.002"));
        assertFalse(ArchiveProbe.firstVolume("Film.mkv"));
        assertTrue(ArchiveProbe.needsTail("x/Pack.7z"));
        assertFalse(ArchiveProbe.needsTail("x/Pack.rar"));
    }

    @Test
    void aReadingGathersHeadAndTailFromPiecesInAnyOrder() throws IOException {
        byte[] archive = fixture("7z-encrypted.7z");
        // The archive sits 100 bytes into a torrent of 64-byte pieces, after some other file.
        int pieceLength = 64;
        byte[] stream = new byte[100 + archive.length + 50];
        System.arraycopy(archive, 0, stream, 100, archive.length);
        ArchiveProbe.Reading reading = new ArchiveProbe.Reading(3, "Pack.7z", 100, archive.length, pieceLength);
        List<Integer> pieces = new ArrayList<>(reading.missing());
        assertEquals(List.of(1, 2, 3, 4), pieces.stream().sorted().toList(), "bytes 100..285 in pieces 1–4");
        Collections.shuffle(pieces, new Random(7));
        for (int i = 0; i < pieces.size(); i++) {
            int p = pieces.get(i);
            byte[] data = java.util.Arrays.copyOfRange(
                    stream, p * pieceLength, Math.min(stream.length, (p + 1) * pieceLength));
            assertEquals(i == pieces.size() - 1, reading.accept(p, data, pieceLength), "complete with the last");
        }
        assertTrue(reading.accept(9, new byte[pieceLength], pieceLength), "a piece it did not ask for changes nothing");
        assertEquals(ArchiveProbe.Verdict.ENCRYPTED, reading.verdict());
        assertEquals(3, reading.fileIndex());
    }

    @Test
    void aBigArchiveIsReadOnlyAtItsHead() {
        long size = 10L * 1024 * 1024 * 1024; // 10 GiB, at offset 0, 4 MiB pieces
        ArchiveProbe.Reading reading = new ArchiveProbe.Reading(0, "Film.rar", 0, size, 4 * 1024 * 1024);
        assertEquals(java.util.Set.of(0), reading.missing(), "the head is in the first piece; a .rar has no tail read");
        ArchiveProbe.Reading sevenZip = new ArchiveProbe.Reading(0, "Film.7z", 0, size, 4 * 1024 * 1024);
        assertEquals(java.util.Set.of(0, 2559), sevenZip.missing(), "a .7z's last piece too");
        assertArrayEquals(new byte[0], new byte[0]);
    }
}
