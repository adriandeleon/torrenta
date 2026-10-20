package com.torrenta.engine;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlocklistTest {

    @Test
    void peerGuardianAndEmuleAndPlainShapes() {
        Blocklist.Parsed parsed = Blocklist.parse(Stream.of(
                "﻿# a comment",
                "",
                "Some Org:1.2.3.0-1.2.3.255",
                "Other:Org:With:Colons:5.6.7.8-5.6.7.9",
                "001.002.004.000 - 001.002.004.255 , 000 , eMule style",
                "010.000.000.000 - 010.255.255.255 , 255 , allowed, not blocked",
                "9.9.9.0/24",
                "9.9.9.9",
                "8.8.8.8 ; trailing comment",
                "not an address at all",
                "2001:db8::-2001:db8::ffff"));
        List<IpRange> r = parsed.ranges();
        assertEquals(7, r.size(), r.toString());
        assertEquals(new IpRange("1.2.3.0", "1.2.3.255"), r.get(0));
        assertEquals(new IpRange("5.6.7.8", "5.6.7.9"), r.get(1), "the last colon splits name from range");
        assertEquals(new IpRange("1.2.4.0", "1.2.4.255"), r.get(2), "leading zeros stripped");
        assertEquals(new IpRange("9.9.9.0", "9.9.9.255"), r.get(3));
        assertEquals(IpRange.single("9.9.9.9"), r.get(4));
        assertEquals(IpRange.single("8.8.8.8"), r.get(5));
        assertEquals("2001:db8:0:0:0:0:0:0", r.get(6).first());
        assertEquals(2, parsed.skipped(), "the nonsense line and the eMule line that allows rather than blocks");
    }

    @Test
    void emuleAllowedLinesAreNotBlocks() {
        assertNull(Blocklist.parseLine("1.2.3.4 - 1.2.3.5 , 128 , allowed"));
    }

    @Test
    void ipRangeRules() {
        assertThrows(IllegalArgumentException.class, () -> new IpRange("1.2.3.9", "1.2.3.1"), "out of order");
        assertThrows(IllegalArgumentException.class, () -> new IpRange("1.2.3.4", "::1"), "mixed families");
        assertThrows(IllegalArgumentException.class, () -> IpRange.single("example.org"), "names never resolve");
        assertThrows(IllegalArgumentException.class, () -> IpRange.single("1.2.3"), "not a quad");
        assertEquals(new IpRange("10.0.0.0", "10.255.255.255"), IpRange.cidr("10.0.0.0/8"));
        assertEquals(new IpRange("192.168.1.0", "192.168.1.255"), IpRange.cidr("192.168.1.77/24"), "host bits cleared");
        assertEquals(IpRange.single("1.2.3.4"), IpRange.cidr("1.2.3.4/32"));
        assertEquals("2001:db8:0:0:0:0:0:0", IpRange.cidr("2001:db8::/32").first());
        assertEquals(
                "2001:db8:ffff:ffff:ffff:ffff:ffff:ffff",
                IpRange.cidr("2001:db8::/32").last());
        assertTrue(IpRange.isLiteral("255.255.255.255"));
        assertTrue(IpRange.isLiteral("::1"));
        assertFalse(IpRange.isLiteral("localhost"));
        assertFalse(IpRange.isLiteral("1.2.3.256"));
    }
}
