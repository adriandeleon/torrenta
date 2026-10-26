package com.torrenta.engine;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * An updatable torrent's address (BEP 46): {@code magnet:?xs=urn:btpk:<public key>&s=<salt>}, the
 * key 32 bytes and the salt optional, both in hex. The DHT keeps an item signed with the key under
 * it, whose {@code ih} names the torrent's current version and whose {@code seq} goes up with each
 * new one; libtorrent checks the signature before it hands an item over. Pure.
 *
 * @param publicKey the Ed25519 public key, 32 bytes
 * @param salt the salt, empty for none
 */
public record MutableSource(byte[] publicKey, byte[] salt) {

    public MutableSource {
        publicKey = publicKey.clone();
        salt = salt == null ? new byte[0] : salt.clone();
        if (publicKey.length != 32) throw new IllegalArgumentException("a public key is 32 bytes");
    }

    /** The link, if {@code text} is one: a magnet with an {@code xs=urn:btpk:} of 64 hex digits. */
    public static Optional<MutableSource> parse(String text) {
        if (text == null) return Optional.empty();
        String s = text.trim();
        if (!s.toLowerCase(Locale.ROOT).startsWith("magnet:?")) return Optional.empty();
        String key = null;
        String salt = "";
        for (String pair : s.substring("magnet:?".length()).split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String name = pair.substring(0, eq).toLowerCase(Locale.ROOT);
            String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            if (name.equals("xs") && value.toLowerCase(Locale.ROOT).startsWith("urn:btpk:"))
                key = value.substring("urn:btpk:".length());
            else if (name.equals("s")) salt = value;
        }
        if (key == null || !key.matches("[0-9a-fA-F]{64}") || !salt.matches("([0-9a-fA-F]{2})*"))
            return Optional.empty();
        return Optional.of(
                new MutableSource(HexFormat.of().parseHex(key), HexFormat.of().parseHex(salt)));
    }

    /** The link, canonical: lower-case hex, the salt only when there is one. */
    public String uri() {
        String key = HexFormat.of().formatHex(publicKey);
        return "magnet:?xs=urn:btpk:" + key
                + (salt.length == 0 ? "" : "&s=" + HexFormat.of().formatHex(salt));
    }

    /**
     * The info-hash an item's value names: its {@code ih}, 20 bytes (a v1 or a hybrid torrent's v1
     * hash) or 32 (a v2 one's), in hex; empty for anything else. The value as {@code Bencode}
     * decodes it: a map whose strings are bytes.
     */
    public static Optional<String> infoHash(Object value) {
        if (!(value instanceof Map<?, ?> item) || !(item.get("ih") instanceof byte[] ih)) return Optional.empty();
        if (ih.length != 20 && ih.length != 32) return Optional.empty();
        return Optional.of(HexFormat.of().formatHex(ih));
    }

    /** The magnet that adds the version {@code infoHash} names: v1 or v2 by its length. */
    public static String magnetOf(String infoHash) {
        return infoHash.length() == 64 ? "magnet:?xt=urn:btmh:1220" + infoHash : "magnet:?xt=urn:btih:" + infoHash;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MutableSource m
                && java.util.Arrays.equals(publicKey, m.publicKey)
                && java.util.Arrays.equals(salt, m.salt);
    }

    @Override
    public int hashCode() {
        return 31 * java.util.Arrays.hashCode(publicKey) + java.util.Arrays.hashCode(salt);
    }

    @Override
    public String toString() {
        return uri();
    }
}
