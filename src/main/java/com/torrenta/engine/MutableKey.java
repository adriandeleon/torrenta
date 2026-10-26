package com.torrenta.engine;

import java.util.Arrays;
import java.util.HexFormat;

/**
 * An Ed25519 key pair as libtorrent signs with it (BEP 44): the public key, 32 bytes, and the
 * secret key in its expanded form, 64. Whoever holds the secret can publish versions under the
 * address; {@link #toString()} never prints it.
 */
public record MutableKey(byte[] publicKey, byte[] secretKey) {

    public MutableKey {
        publicKey = publicKey.clone();
        secretKey = secretKey.clone();
        if (publicKey.length != 32) throw new IllegalArgumentException("a public key is 32 bytes");
        if (secretKey.length != 64) throw new IllegalArgumentException("a secret key is 64 bytes");
    }

    @Override
    public byte[] publicKey() {
        return publicKey.clone();
    }

    @Override
    public byte[] secretKey() {
        return secretKey.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MutableKey k
                && Arrays.equals(publicKey, k.publicKey)
                && Arrays.equals(secretKey, k.secretKey);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(publicKey);
    }

    @Override
    public String toString() {
        return "MutableKey[" + HexFormat.of().formatHex(publicKey) + "]";
    }
}
