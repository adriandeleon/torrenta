package com.torrenta.engine;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Whether an archive asks for a password, read off its first bytes (and, for 7z, its last): the
 * check the content card cannot make from a file list, since the answer is in the data. A
 * torrent that wants an archive gets its first piece early, and the engine hands the bytes here.
 *
 * <ul>
 *   <li><b>ZIP</b>: the local file headers in reach; bit 0 of the general-purpose flag is set for
 *       ZipCrypto and for AES alike.
 *   <li><b>RAR 5</b>: an archive encryption header (the names are hidden: {@code -hp}), or a file
 *       header carrying the encryption extra record ({@code -p}).
 *   <li><b>RAR 4</b>: the main header's password flag ({@code -hp}) or a file header's ({@code -p}).
 *   <li><b>7z</b>: the start header says where the header is, at the end; the AES coder's id in it
 *       is encryption, of the files or of the header itself. A header 7-Zip compressed without
 *       encrypting hides the files' coders, and that one reads as unknown, not clear.
 * </ul>
 *
 * <p>Pure, and tested against archives made by zip, 7z and (for RAR, built to the format and
 * checked by unrar) the fixtures in the test resources.
 */
public final class ArchiveProbe {

    private ArchiveProbe() {}

    /** What the bytes say. */
    public enum Verdict {
        /** A password is needed: for the files, or for the names too. */
        ENCRYPTED,
        /** What could be read is not encrypted. */
        CLEAR,
        /** Not an archive this knows, or the part that would tell is out of reach. */
        UNKNOWN
    }

    /** How much of an archive's start is read: enough for the first headers of any of the four. */
    public static final int HEAD = 64 * 1024;

    /** How much of a 7z's end is read: where its header is. */
    public static final int TAIL = 64 * 1024;

    private static final Pattern PART = Pattern.compile("\\.part0*(\\d+)\\.rar$");
    private static final Set<String> ARCHIVES = Set.of("zip", "rar", "7z", "001");

    /**
     * The file a set of volumes is opened by, and the only one worth reading: a {@code .rar} (but
     * of {@code .partN.rar} only part 1), a {@code .zip}, a {@code .7z}, a split's {@code .001}.
     * {@code .r00} and later parts carry no headers of their own worth reading.
     */
    public static boolean firstVolume(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || !ARCHIVES.contains(name.substring(dot + 1))) return false;
        Matcher part = PART.matcher(name);
        return !part.find() || part.group(1).equals("1");
    }

    /** A 7z keeps its header at the end, so its tail is read as well as its head. */
    public static boolean needsTail(String path) {
        return path.toLowerCase(Locale.ROOT).endsWith(".7z");
    }

    /**
     * @param head the archive's first bytes, up to {@link #HEAD}
     * @param tail its last bytes, up to {@link #TAIL}; empty when not read
     * @param size the whole file's size, to place the tail
     */
    public static Verdict of(byte[] head, byte[] tail, long size) {
        if (startsWith(head, RAR5)) return rar5(head);
        if (startsWith(head, RAR4)) return rar4(head);
        if (startsWith(head, SEVEN_ZIP)) return sevenZip(head, tail, size);
        if (startsWith(head, ZIP_LOCAL)) return zip(head);
        return Verdict.UNKNOWN;
    }

    // ---- ZIP ------------------------------------------------------------------------------------

    private static final byte[] ZIP_LOCAL = {0x50, 0x4b, 0x03, 0x04};

    private static Verdict zip(byte[] b) {
        boolean sawEntry = false;
        int pos = 0;
        while (pos + 30 <= b.length && startsWith(b, pos, ZIP_LOCAL)) {
            int flags = u16(b, pos + 6);
            if ((flags & 0x0001) != 0) return Verdict.ENCRYPTED;
            sawEntry = true;
            // With a data descriptor the sizes come after the data: the next header is not findable.
            if ((flags & 0x0008) != 0) break;
            long compressed = u32(b, pos + 18);
            int nameLength = u16(b, pos + 26);
            int extraLength = u16(b, pos + 28);
            long next = pos + 30L + nameLength + extraLength + compressed;
            if (next > Integer.MAX_VALUE) break;
            pos = (int) next;
        }
        return sawEntry ? Verdict.CLEAR : Verdict.UNKNOWN;
    }

    // ---- RAR 5 ----------------------------------------------------------------------------------

    private static final byte[] RAR5 = {0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x01, 0x00};

    private static Verdict rar5(byte[] b) {
        boolean sawFile = false;
        int pos = RAR5.length;
        while (pos + 4 < b.length) {
            long[] headerSize = vint(b, pos + 4);
            if (headerSize == null) break;
            int start = pos + 4 + (int) headerSize[1];
            long end = start + headerSize[0];
            long[] type = vint(b, start);
            if (type == null) break;
            if (type[0] == 4) return Verdict.ENCRYPTED; // archive encryption header: names hidden
            long[] flags = vint(b, start + (int) type[1]);
            if (flags == null) break;
            int at = start + (int) type[1] + (int) flags[1];
            long extraSize = 0;
            long dataSize = 0;
            if ((flags[0] & 0x0001) != 0) {
                long[] v = vint(b, at);
                if (v == null) break;
                extraSize = v[0];
                at += (int) v[1];
            }
            if ((flags[0] & 0x0002) != 0) {
                long[] v = vint(b, at);
                if (v == null) break;
                dataSize = v[0];
            }
            // The bytes are a stranger's: a size past the header, or past the buffer, ends the reading.
            if (end > b.length || extraSize < 0 || extraSize > end - start || dataSize < 0) break;
            if (type[0] == 2) {
                sawFile = true;
                if (extraSize > 0 && encryptionRecord(b, (int) (end - extraSize), (int) end)) return Verdict.ENCRYPTED;
            }
            if (type[0] == 5) break; // end of archive
            long next = end + dataSize;
            if (next > Integer.MAX_VALUE || next <= pos) break;
            pos = (int) next;
        }
        return sawFile ? Verdict.CLEAR : Verdict.UNKNOWN;
    }

    /** A file header's extra area holds an encryption record (type 1). */
    private static boolean encryptionRecord(byte[] b, int from, int to) {
        int pos = from;
        while (pos < to) {
            long[] size = vint(b, pos);
            if (size == null || size[0] <= 0 || size[0] > to - pos) return false;
            int recordStart = pos + (int) size[1];
            long[] type = vint(b, recordStart);
            if (type == null) return false;
            if (type[0] == 1) return true;
            pos = recordStart + (int) size[0];
        }
        return false;
    }

    /** RAR 5's variable-length integer at {@code pos}: {value, bytes taken}, or null past the end. */
    private static long @Nullable [] vint(byte[] b, int pos) {
        long value = 0;
        for (int i = 0; i < 10 && pos + i < b.length; i++) {
            int v = b[pos + i] & 0xff;
            value |= (long) (v & 0x7f) << (7 * i);
            if ((v & 0x80) == 0) return value < 0 ? null : new long[] {value, i + 1};
        }
        return null;
    }

    // ---- RAR 4 ----------------------------------------------------------------------------------

    private static final byte[] RAR4 = {0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x00};

    private static Verdict rar4(byte[] b) {
        boolean sawFile = false;
        int pos = RAR4.length;
        while (pos + 7 <= b.length) {
            int type = b[pos + 2] & 0xff;
            int flags = u16(b, pos + 3);
            int size = u16(b, pos + 5);
            if (size < 7) break;
            long add = 0;
            if (type == 0x73 && (flags & 0x0080) != 0) return Verdict.ENCRYPTED; // names hidden
            if (type == 0x74) {
                if ((flags & 0x0004) != 0) return Verdict.ENCRYPTED;
                sawFile = true;
                if (pos + 11 > b.length) break;
                add = u32(b, pos + 7);
                if ((flags & 0x0100) != 0 && pos + 36 <= b.length) add += u32(b, pos + 32) << 32;
            } else if ((flags & 0x8000) != 0) {
                if (pos + 11 > b.length) break;
                add = u32(b, pos + 7);
            }
            if (type == 0x7b) break; // end of archive
            long next = pos + size + add;
            // A stranger's 64-bit size can wrap below the position: forward only, or stop.
            if (next > Integer.MAX_VALUE || next <= pos) break;
            pos = (int) next;
        }
        return sawFile ? Verdict.CLEAR : Verdict.UNKNOWN;
    }

    // ---- 7z -------------------------------------------------------------------------------------

    private static final byte[] SEVEN_ZIP = {0x37, 0x7a, (byte) 0xbc, (byte) 0xaf, 0x27, 0x1c};
    private static final byte[] AES = {0x06, (byte) 0xf1, 0x07, 0x01};

    private static Verdict sevenZip(byte[] head, byte[] tail, long size) {
        if (head.length < 32) return Verdict.UNKNOWN;
        long offset = u64(head, 12);
        long length = u64(head, 20);
        long at = 32 + offset;
        if (offset < 0 || length <= 0 || at + length != size) return Verdict.UNKNOWN;
        long tailStart = size - tail.length;
        if (at < tailStart) return Verdict.UNKNOWN; // a header bigger than what was read
        int from = (int) (at - tailStart);
        int to = from + (int) length;
        boolean aes = indexOf(tail, from, to, AES) >= 0;
        if (aes) return Verdict.ENCRYPTED;
        // 0x01 is a plain header, whose coders are all in view; 0x17 an encoded (compressed) one.
        return tail[from] == 0x01 ? Verdict.CLEAR : Verdict.UNKNOWN;
    }

    // ---- reading pieces -------------------------------------------------------------------------

    /**
     * One archive being read out of a torrent's pieces: which pieces cover its head (and tail),
     * and the bytes gathered as each arrives. Not thread-safe; the engine's alert thread owns it.
     *
     * @param fileIndex the file's index in the torrent
     * @param offset where the file starts in the torrent's byte stream
     * @param size the file's size
     */
    public static final class Reading {
        private final int fileIndex;
        private final long offset;
        private final long size;
        private final byte[] head;
        private final byte[] tail;
        private final Set<Integer> missing = new TreeSet<>();

        public Reading(int fileIndex, String path, long offset, long size, int pieceLength) {
            this.fileIndex = fileIndex;
            this.offset = offset;
            this.size = size;
            this.head = new byte[(int) Math.min(size, HEAD)];
            this.tail = needsTail(path) ? new byte[(int) Math.min(size, TAIL)] : new byte[0];
            addPieces(offset, head.length, pieceLength);
            addPieces(offset + size - tail.length, tail.length, pieceLength);
        }

        private void addPieces(long from, int length, int pieceLength) {
            if (length <= 0) return;
            for (long p = from / pieceLength; p <= (from + length - 1) / pieceLength; p++) missing.add((int) p);
        }

        public int fileIndex() {
            return fileIndex;
        }

        /** The pieces still to arrive; the engine sets a deadline on each. */
        public Set<Integer> missing() {
            return Set.copyOf(missing);
        }

        /** A piece's bytes: whatever part of the head or tail it covers is copied. True once all are in. */
        public boolean accept(int piece, byte[] data, int pieceLength) {
            if (!missing.remove(piece)) return missing.isEmpty();
            long pieceStart = (long) piece * pieceLength;
            copy(data, pieceStart, head, offset);
            copy(data, pieceStart, tail, offset + size - tail.length);
            return missing.isEmpty();
        }

        /** The overlap of a piece (at {@code pieceStart}) and a buffer (at {@code bufferStart}). */
        private static void copy(byte[] data, long pieceStart, byte[] buffer, long bufferStart) {
            long from = Math.max(pieceStart, bufferStart);
            long to = Math.min(pieceStart + data.length, bufferStart + buffer.length);
            if (from >= to) return;
            System.arraycopy(data, (int) (from - pieceStart), buffer, (int) (from - bufferStart), (int) (to - from));
        }

        public Verdict verdict() {
            return of(head, tail, size);
        }
    }

    // ---- bytes ----------------------------------------------------------------------------------

    private static boolean startsWith(byte[] b, byte[] prefix) {
        return startsWith(b, 0, prefix);
    }

    private static boolean startsWith(byte[] b, int at, byte[] prefix) {
        if (at < 0 || at + prefix.length > b.length) return false;
        for (int i = 0; i < prefix.length; i++) if (b[at + i] != prefix[i]) return false;
        return true;
    }

    private static int indexOf(byte[] b, int from, int to, byte[] needle) {
        for (int i = from; i + needle.length <= to; i++) if (startsWith(b, i, needle)) return i;
        return -1;
    }

    private static int u16(byte[] b, int at) {
        return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8;
    }

    private static long u32(byte[] b, int at) {
        return (long) u16(b, at) | (long) u16(b, at + 2) << 16;
    }

    private static long u64(byte[] b, int at) {
        return u32(b, at) | u32(b, at + 4) << 32;
    }
}
