package com.torrenta.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Ranges kept small between uses: an IPv4 range as one {@code long} (first address in the high
 * half), the rare IPv6 one as itself. A blocklist of 300,000 ranges is about 3 MiB this way and
 * 41 MiB as {@link IpRange}s, which is what a list held for the process's life would cost.
 *
 * <p>The ranges come back IPv4 first, then IPv6; the filter is their union, so order means
 * nothing to it.
 */
public final class PackedRanges {

    public static final PackedRanges EMPTY = new PackedRanges(new long[0], List.of());

    private final long[] v4;
    private final List<IpRange> v6;

    private PackedRanges(long[] v4, List<IpRange> v6) {
        this.v4 = v4;
        this.v6 = v6;
    }

    public static PackedRanges of(List<IpRange> ranges) {
        long[] v4 = new long[ranges.size()];
        int n = 0;
        List<IpRange> v6 = new ArrayList<>();
        for (IpRange r : ranges) {
            long first = IpRange.v4(r.first(), false);
            if (first < 0) v6.add(r);
            else v4[n++] = first << 32 | IpRange.v4(r.last(), false);
        }
        return new PackedRanges(Arrays.copyOf(v4, n), List.copyOf(v6));
    }

    public int size() {
        return v4.length + v6.size();
    }

    /** Every range, made afresh, onto {@code out}. */
    public void addTo(Collection<? super IpRange> out) {
        for (long packed : v4)
            out.add(new IpRange(IpRange.v4Text(packed >>> 32), IpRange.v4Text(packed & 0xffffffffL)));
        out.addAll(v6);
    }

    public List<IpRange> unpack() {
        List<IpRange> out = new ArrayList<>(size());
        addTo(out);
        return out;
    }
}
