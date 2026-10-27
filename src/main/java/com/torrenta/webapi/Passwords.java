package com.torrenta.webapi;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * The Remote access password as it is kept: never itself, only a salted PBKDF2-SHA256 of it, as
 * {@code pbkdf2-sha256$<iterations>$<salt>$<hash>} (base64), in {@code webapi.password}, which is
 * this machine's and never synced. A check takes the same time whatever it compares.
 */
public final class Passwords {

    private Passwords() {}

    /** OWASP's figure for PBKDF2-HMAC-SHA256 (2023). A login is rare; the cost is the point. */
    static final int ITERATIONS = 600_000;

    private static final String SCHEME = "pbkdf2-sha256";
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;

    public static String hash(char[] password, SecureRandom random) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        return encode(ITERATIONS, salt, derive(password, salt, ITERATIONS));
    }

    /** Whether {@code password} is the one {@code stored} was made from; false for anything unreadable. */
    public static boolean verify(char[] password, String stored) {
        if (stored == null) return false;
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !parts[0].equals(SCHEME)) return false;
        try {
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 1 || iterations > 10_000_000) return false;
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            return MessageDigest.isEqual(expected, derive(password, salt, iterations));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Whether a password has been set at all. */
    public static boolean isSet(String stored) {
        return stored != null && stored.startsWith(SCHEME + "$");
    }

    static String encode(int iterations, byte[] salt, byte[] hash) {
        Base64.Encoder b64 = Base64.getEncoder();
        return SCHEME + "$" + iterations + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(hash);
    }

    static byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 is part of every JDK", e);
        } finally {
            spec.clearPassword();
        }
    }
}
