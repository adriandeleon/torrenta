package com.torrenta.engine;

import java.math.BigInteger;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The hand-read dotted quad: what InetAddress accepted, and nothing it would have resolved. */
class IpRangeTest {

    @Test
    void theEndsOfTheRangeOfAnOctet() {
        assertEquals(0L, IpRange.v4("0.0.0.0", false));
        assertEquals(0xffffffffL, IpRange.v4("255.255.255.255", false));
        assertEquals(0x010203ffL, IpRange.v4("1.2.3.255", false));
        assertEquals(-1, IpRange.v4("1.2.3.256", false));
        assertEquals(-1, IpRange.v4("256.1.1.1", false));
        assertEquals(-1, IpRange.v4("999.1.1.1", false));
    }

    @Test
    void leadingZerosAreDecimalAndOnlyPaddedTextMayHaveMoreThanThreeDigits() {
        // InetAddress reads 010 as ten, not as octal eight.
        assertEquals(IpRange.v4("10.0.0.1", false), IpRange.v4("010.000.000.001", false));
        assertEquals(IpRange.single("10.0.0.1"), IpRange.single("010.0.0.1"));
        assertEquals(-1, IpRange.v4("0010.0.0.1", false), "four digits is not a quad");
        assertEquals(IpRange.v4("10.0.0.1", false), IpRange.v4("0010.0000.0.00001", true), "eMule's padding");
        assertEquals(-1, IpRange.v4("0256.0.0.1", true), "zeros do not hide a value over 255");
    }

    @Test
    void missingOrExtraOctetsAndStrayCharactersAreNotAQuad() {
        for (String text : new String[] {
            "",
            " ",
            "1.2.3",
            "1.2.3.",
            "1.2..4",
            ".1.2.3",
            "1.2.3.4.5",
            "1.2.3.4.",
            "1 .2.3.4",
            "1.2.3.4x",
            "+1.2.3.4",
            "-1.2.3.4",
            "1.2.3.4/24",
            "١.٢.٣.٤",
            "example.org"
        }) {
            assertEquals(-1, IpRange.v4(text, false), text);
        }
        assertEquals(-1, IpRange.v4(null, false));
    }

    @Test
    void spacesAroundAreIgnoredAndPaddedTextMayEndInDots() {
        assertEquals(0x01020304L, IpRange.v4("  1.2.3.4\t", false));
        assertEquals(0x01020304L, IpRange.v4("1.2.3.4.", true));
        assertEquals(0x01020304L, IpRange.v4("1.2.3.4 ...", true));
        assertEquals(-1, IpRange.v4("1.2.3.4. .", true));
        assertEquals(-1, IpRange.v4("1.2.3", true));
    }

    @Test
    void anOutOfRangeOctetIsRefusedWithoutAskingTheResolver() {
        // InetAddress.getByName("1.2.3.256") went to DNS ("Name or service not known").
        assertEquals(Optional.empty(), IpRange.value("1.2.3.256"));
        assertFalse(IpRange.isLiteral("1.2.3.256"));
        assertThrows(IllegalArgumentException.class, () -> IpRange.single("1.2.3.256"));
    }

    @Test
    void theNumberIsTheAddress() {
        assertEquals(Optional.of(BigInteger.valueOf(0xc0a80101L)), IpRange.value("192.168.1.1"));
        assertEquals("192.168.1.1", IpRange.v4Text(0xc0a80101L));
        assertEquals("0.0.0.0", IpRange.v4Text(0));
        assertEquals("255.255.255.255", IpRange.v4Text(0xffffffffL));
    }

    @Test
    void cidrAtTheWidestAndTheNarrowest() {
        assertEquals(new IpRange("0.0.0.0", "255.255.255.255"), IpRange.cidr("0.0.0.0/0"));
        assertEquals(new IpRange("0.0.0.0", "255.255.255.255"), IpRange.cidr("200.1.2.3/0"), "every bit is host");
        assertEquals(IpRange.single("200.1.2.3"), IpRange.cidr("200.1.2.3/32"));
        assertEquals(new IpRange("200.1.2.2", "200.1.2.3"), IpRange.cidr("200.1.2.3/31"));
        assertEquals(new IpRange("128.0.0.0", "255.255.255.255"), IpRange.cidr("200.1.2.3/1"));
        assertThrows(IllegalArgumentException.class, () -> IpRange.cidr("1.2.3.4/33"));
        assertThrows(IllegalArgumentException.class, () -> IpRange.cidr("1.2.3.4/-1"));
        assertThrows(IllegalArgumentException.class, () -> IpRange.cidr("1.2.3/8"));
    }

    @Test
    void ipv6KeepsItsSpellingAndFamiliesStillDoNotMix() {
        assertEquals(new IpRange("::1", "::1"), IpRange.single("0:0:0:0:0:0:0:1"));
        assertEquals("0:0:0:0:0:0:0:1", IpRange.single("::1").first());
        assertTrue(IpRange.isLiteral("2001:DB8::1"));
        assertFalse(IpRange.isLiteral("::ffff:1.2.3.4"), "a mapped v4 comes back as four bytes");
        assertThrows(IllegalArgumentException.class, () -> new IpRange("::1", "1.2.3.4"));
        assertThrows(IllegalArgumentException.class, () -> new IpRange("10.0.0.0", "9.255.255.255"), "out of order");
    }
}
