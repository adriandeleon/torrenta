package com.torrenta.engine;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackedRangesTest {

    @Test
    void everyRangeComesBackIpv4First() {
        List<IpRange> ranges = List.of(
                new IpRange("2001:db8::", "2001:db8::ff"),
                IpRange.cidr("0.0.0.0/0"),
                IpRange.single("255.255.255.255"),
                new IpRange("1.2.3.0", "1.2.3.255"),
                IpRange.single("0.0.0.0"));
        PackedRanges packed = PackedRanges.of(ranges);
        assertEquals(5, packed.size());
        assertEquals(
                List.of(
                        IpRange.cidr("0.0.0.0/0"),
                        IpRange.single("255.255.255.255"),
                        new IpRange("1.2.3.0", "1.2.3.255"),
                        IpRange.single("0.0.0.0"),
                        new IpRange("2001:db8::", "2001:db8::ff")),
                packed.unpack());
    }

    @Test
    void emptyIsEmpty() {
        assertEquals(0, PackedRanges.of(List.of()).size());
        assertTrue(PackedRanges.EMPTY.unpack().isEmpty());
    }
}
