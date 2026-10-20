package com.torrenta.engine;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Optional;

/**
 * An inclusive range of addresses, both ends as literals, for the engine's IP filter.
 *
 * <p>Pure and strict: only IP literals are accepted, never host names, so nothing here resolves
 * anything. Both ends must be the same family and in order.
 */
public record IpRange(String first, String last) {

    public IpRange {
        String firstText = first;
        String lastText = last;
        BigInteger a = value(first).orElseThrow(() -> new IllegalArgumentException("not an IP literal: " + firstText));
        BigInteger b = value(last).orElseThrow(() -> new IllegalArgumentException("not an IP literal: " + lastText));
        if (isV6(first) != isV6(last)) throw new IllegalArgumentException("mixed families: " + first + " - " + last);
        if (a.compareTo(b) > 0) throw new IllegalArgumentException("range out of order: " + first + " - " + last);
        // One spelling, so a range read from a list equals the same range built from a CIDR.
        int bits = isV6(first) ? 128 : 32;
        first = literal(a, bits);
        last = literal(b, bits);
    }

    public static IpRange single(String ip) {
        return new IpRange(ip, ip);
    }

    /** {@code 10.0.0.0/8} or {@code 2001:db8::/32} as a range. */
    public static IpRange cidr(String cidr) {
        int slash = cidr.indexOf('/');
        if (slash < 0) return single(cidr.trim());
        String ip = cidr.substring(0, slash).trim();
        int prefix = Integer.parseInt(cidr.substring(slash + 1).trim());
        int bits = isV6(ip) ? 128 : 32;
        if (prefix < 0 || prefix > bits) throw new IllegalArgumentException("bad prefix: " + cidr);
        BigInteger base = value(ip).orElseThrow(() -> new IllegalArgumentException("not an IP literal: " + ip));
        BigInteger hostMask = BigInteger.ONE.shiftLeft(bits - prefix).subtract(BigInteger.ONE);
        BigInteger start = base.andNot(hostMask);
        BigInteger end = start.or(hostMask);
        return new IpRange(literal(start, bits), literal(end, bits));
    }

    /** True for an IPv4 dotted quad or an IPv6 literal; false for anything that could be a name. */
    public static boolean isLiteral(String text) {
        return value(text).isPresent();
    }

    static boolean isV6(String ip) {
        return ip.contains(":");
    }

    /** The address as a number, or empty when the text is not a literal. */
    static Optional<BigInteger> value(String text) {
        if (text == null) return Optional.empty();
        String s = text.trim();
        if (s.isEmpty()) return Optional.empty();
        boolean v6 = s.contains(":");
        if (!v6 && !s.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return Optional.empty();
        if (v6 && !s.matches("[0-9A-Fa-f:.]+")) return Optional.empty();
        try {
            // Only ever given a literal (checked above), so this never touches DNS.
            byte[] bytes = InetAddress.getByName(s).getAddress();
            if (v6 && bytes.length != 16) return Optional.empty();
            if (!v6 && bytes.length != 4) return Optional.empty();
            return Optional.of(new BigInteger(1, bytes));
        } catch (UnknownHostException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String literal(BigInteger value, int bits) {
        byte[] raw = value.toByteArray();
        int len = bits / 8;
        byte[] bytes = new byte[len];
        int copy = Math.min(len, raw.length);
        System.arraycopy(raw, raw.length - copy, bytes, len - copy, copy);
        try {
            return InetAddress.getByAddress(bytes).getHostAddress().toLowerCase(Locale.ROOT);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
