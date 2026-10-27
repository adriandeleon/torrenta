package com.torrenta.engine;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * An inclusive range of addresses, both ends as literals, for the engine's IP filter.
 *
 * <p>Pure and strict: only IP literals are accepted, never host names, so nothing here resolves
 * anything. Both ends must be the same family and in order.
 *
 * <p>A dotted quad is read by hand into a {@code long}: a blocklist is hundreds of thousands of
 * them, and the regex plus {@link InetAddress} plus {@link BigInteger} per end cost most of a
 * second. IPv6, rare in those lists, keeps the general path.
 */
public record IpRange(String first, String last) {

    private static final Pattern V6_CHARS = Pattern.compile("[0-9A-Fa-f:.]+");

    public IpRange {
        long a4 = v4(first, false);
        long b4 = v4(last, false);
        if (a4 >= 0 && b4 >= 0) {
            if (a4 > b4) throw new IllegalArgumentException("range out of order: " + first + " - " + last);
            first = v4Text(a4);
            last = v4Text(b4);
        } else {
            String firstText = first;
            String lastText = last;
            BigInteger a =
                    value(first).orElseThrow(() -> new IllegalArgumentException("not an IP literal: " + firstText));
            BigInteger b =
                    value(last).orElseThrow(() -> new IllegalArgumentException("not an IP literal: " + lastText));
            if (isV6(first) != isV6(last))
                throw new IllegalArgumentException("mixed families: " + first + " - " + last);
            if (a.compareTo(b) > 0) throw new IllegalArgumentException("range out of order: " + first + " - " + last);
            // One spelling, so a range read from a list equals the same range built from a CIDR.
            int bits = isV6(first) ? 128 : 32;
            first = literal(a, bits);
            last = literal(b, bits);
        }
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
        long base4 = v4(ip, false);
        if (base4 >= 0) {
            long hostMask = (1L << (32 - prefix)) - 1;
            long start = base4 & ~hostMask;
            return new IpRange(v4Text(start), v4Text(start | hostMask));
        }
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

    /**
     * A dotted quad as a number from 0 to 2³²-1, or -1 when the text is not one. Spaces around it
     * are ignored, as {@link String#trim()} would; the digits are ASCII and decimal ({@code 010}
     * is ten, as {@link InetAddress} reads it).
     *
     * @param padded eMule's spelling: any number of leading zeros on an octet ({@code 0001}), and
     *     dots after the fourth (spaces before them too), which the {@code split} that used to do
     *     the unpadding let through
     */
    static long v4(String text, boolean padded) {
        if (text == null) return -1;
        int end = text.length();
        int i = 0;
        while (i < end && text.charAt(i) <= ' ') i++;
        while (end > i && text.charAt(end - 1) <= ' ') end--;
        if (padded && end > i && text.charAt(end - 1) == '.') {
            while (end > i && text.charAt(end - 1) == '.') end--;
            while (end > i && text.charAt(end - 1) <= ' ') end--;
        }
        long value = 0;
        for (int octet = 0; octet < 4; octet++) {
            if (octet > 0) {
                if (i >= end || text.charAt(i) != '.') return -1;
                i++;
            }
            int start = i;
            int n = 0;
            while (i < end) {
                char c = text.charAt(i);
                if (c < '0' || c > '9') break;
                n = n * 10 + (c - '0');
                if (n > 255) return -1;
                i++;
            }
            int digits = i - start;
            if (digits == 0 || (!padded && digits > 3)) return -1;
            value = (value << 8) | n;
        }
        return i == end ? value : -1;
    }

    static String v4Text(long value) {
        return ((value >>> 24) & 0xff) + "." + ((value >>> 16) & 0xff) + "." + ((value >>> 8) & 0xff) + "."
                + (value & 0xff);
    }

    /** The address as a number, or empty when the text is not a literal. */
    static Optional<BigInteger> value(String text) {
        if (text == null) return Optional.empty();
        String s = text.trim();
        if (s.isEmpty()) return Optional.empty();
        if (!s.contains(":")) {
            long v4 = v4(s, false);
            return v4 < 0 ? Optional.empty() : Optional.of(BigInteger.valueOf(v4));
        }
        if (!V6_CHARS.matcher(s).matches()) return Optional.empty();
        try {
            // Only ever given a literal (checked above: a colon and hex digits), so this never
            // touches DNS.
            byte[] bytes = InetAddress.getByName(s).getAddress();
            if (bytes.length != 16) return Optional.empty();
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
