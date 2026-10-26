package com.torrenta.engine;

import java.util.Arrays;

/**
 * An updatable torrent this machine publishes (BEP 46): its key, under a name of the person's
 * choosing, and the version the address names now. Kept in {@code keys/<name>.key}.
 *
 * @param name the file's name, and how the person tells their publications apart
 * @param key the key pair the items are signed with
 * @param salt the address's salt, empty for none
 * @param infoHash the current version's info-hash, hex (v1 when there is one), empty before the first
 * @param seq the highest sequence number published: anyone following has seen at most this, so the
 *     next version has to go above it
 * @param torrentId the current version's id in the list, empty when unknown
 */
public record Publication(String name, MutableKey key, byte[] salt, String infoHash, long seq, String torrentId) {

    public Publication {
        salt = salt == null ? new byte[0] : salt.clone();
        infoHash = infoHash == null ? "" : infoHash.trim().toLowerCase(java.util.Locale.ROOT);
        torrentId = torrentId == null ? "" : torrentId.trim();
        if (seq < 0) throw new IllegalArgumentException("a sequence number is not negative");
    }

    @Override
    public byte[] salt() {
        return salt.clone();
    }

    /** The address followers add. */
    public MutableSource source() {
        return new MutableSource(key.publicKey(), salt);
    }

    public String uri() {
        return source().uri();
    }

    /** A new current version, the number still the last one published. */
    public Publication withVersion(String infoHash, String torrentId) {
        return new Publication(name, key, salt, infoHash, seq, torrentId);
    }

    /** What a put answered: the record only goes up, since followers may have seen the higher one. */
    public Publication published(long seq) {
        return new Publication(name, key, salt, infoHash, Math.max(this.seq, seq), torrentId);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Publication p
                && name.equals(p.name)
                && key.equals(p.key)
                && Arrays.equals(salt, p.salt)
                && infoHash.equals(p.infoHash)
                && seq == p.seq
                && torrentId.equals(p.torrentId);
    }

    @Override
    public int hashCode() {
        return name.hashCode() * 31 + infoHash.hashCode();
    }

    @Override
    public String toString() {
        return "Publication[" + name + ", " + uri() + ", " + infoHash + ", seq " + seq + "]";
    }
}
