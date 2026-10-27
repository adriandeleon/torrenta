package com.torrenta.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

/**
 * Reads the blocklist formats in the wild into {@link IpRange}s. Pure, and tested on each shape:
 *
 * <ul>
 *   <li>PeerGuardian {@code .p2p}: {@code some name:1.2.3.0-1.2.3.255}
 *   <li>eMule {@code .dat}: {@code 001.002.003.000 - 001.002.003.255 , 000 , some name}
 *   <li>plain: {@code 1.2.3.0-1.2.3.255}, {@code 1.2.3.0/24}, {@code 1.2.3.4}
 *   <li>comments on {@code #} or {@code ;}, blank lines, a UTF-8 BOM
 * </ul>
 *
 * <p>A line that cannot be read is skipped and counted, never fatal: one odd entry in a list of
 * two hundred thousand must not cost the other ones.
 */
public final class Blocklist {

    /** An eMule line without the spaced comma: a range, then a comma. Compiled once, not per line. */
    private static final Pattern DAT = Pattern.compile(".*\\d\\s*-\\s*\\d.*,.*");

    /** What came of a parse: the ranges, and how many lines were not understood. */
    public record Parsed(List<IpRange> ranges, int skipped) {}

    private Blocklist() {}

    public static Parsed parse(Stream<String> lines) {
        List<IpRange> ranges = new ArrayList<>();
        int[] skipped = {0};
        lines.forEach(raw -> {
            String line = strip(raw);
            if (line.isEmpty()) return;
            IpRange range = parseLine(line);
            if (range == null) skipped[0]++;
            else ranges.add(range);
        });
        return new Parsed(List.copyOf(ranges), skipped[0]);
    }

    /** One line, or null; also how a manual ban is read. */
    public static @Nullable IpRange parseLine(String line) {
        try {
            String s = line;
            // eMule .dat: "first - last , access , name". Access 0 blocks; anything else is allowed.
            if (s.indexOf(',') >= 0 && (s.contains(" , ") || DAT.matcher(s).matches())) {
                String[] parts = s.split(",");
                if (parts.length >= 2) {
                    int access = Integer.parseInt(parts[1].trim());
                    if (access != 0) return null;
                    s = parts[0];
                }
            }
            // PeerGuardian .p2p: "name:first-last". The name may itself contain colons, but an
            // IPv6 range would too, so the split is at the last colon that precedes a dash-range.
            int colon = s.lastIndexOf(':');
            if (colon > 0
                    && s.indexOf('-') > colon
                    && !IpRange.isV6(s.substring(colon + 1).trim())) {
                s = s.substring(colon + 1);
            }
            s = s.trim();
            if (s.contains("/")) return IpRange.cidr(s);
            int dash = s.indexOf('-');
            if (dash > 0) {
                String first = s.substring(0, dash);
                String last = s.substring(dash + 1);
                long a = IpRange.v4(first, true);
                long b = IpRange.v4(last, true);
                if (a >= 0 && b >= 0) return v4(a, b);
                return new IpRange(unpad(first.trim()), unpad(last.trim()));
            }
            long single = IpRange.v4(s, true);
            if (single >= 0) return v4(single, single);
            return IpRange.single(unpad(s));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static IpRange v4(long first, long last) {
        if (first > last) throw new IllegalArgumentException("range out of order");
        return new IpRange(IpRange.v4Text(first), IpRange.v4Text(last));
    }

    /**
     * {@code 001.002.003.004} is how eMule writes {@code 1.2.3.4}. A quad is read padded by
     * {@link IpRange#v4} before it gets here; what is left is IPv6, lowercased, or not an address.
     */
    static String unpad(String ip) {
        return ip.contains(":") ? ip.toLowerCase(Locale.ROOT) : ip;
    }

    private static String strip(String raw) {
        String s = raw;
        if (!s.isEmpty() && s.charAt(0) == '﻿') s = s.substring(1);
        int hash = s.indexOf('#');
        if (hash >= 0) s = s.substring(0, hash);
        int semi = s.indexOf(';');
        if (semi >= 0) s = s.substring(0, semi);
        return s.trim();
    }
}
