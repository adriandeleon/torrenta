package com.torrenta.engine;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrackerHostTest {

    @Test
    void hostOfAnAnnounceUrl() {
        assertEquals("tracker.example.org", TrackerHost.of("udp://tracker.example.org:6969/announce"));
        assertEquals("t.example.com", TrackerHost.of("https://t.example.com/announce?x=1"));
        assertEquals(
                "tracker.example.org", TrackerHost.of("  HTTP://Tracker.Example.ORG/announce "), "lower case, trimmed");
        assertEquals("[::1]", TrackerHost.of("udp://[::1]:6969/announce"), "an IPv6 literal keeps its brackets");
        assertEquals("10.0.0.1", TrackerHost.of("http://10.0.0.1:8080/announce"));
        assertEquals("", TrackerHost.of(""));
        assertEquals("", TrackerHost.of("   "));
        assertEquals("", TrackerHost.of(null));
    }

    @Test
    void anUnparseableUrlIsCutByHand() {
        assertEquals("not a url", TrackerHost.of("not a url"));
        assertEquals(
                "host_with_underscore.example", TrackerHost.of("udp://host_with_underscore.example:1337/announce"));
        assertEquals("tracker.example.org", TrackerHost.of("udp://user:pw@tracker.example.org:6969/a b"));
        assertEquals("example.org", TrackerHost.of("example.org/announce"));
    }

    @Test
    void hostsAreEachOnceSortedWithoutBlanks() {
        assertEquals(
                List.of("a.example.org", "b.example.org"),
                TrackerHost.hosts(List.of(
                        "udp://b.example.org:1/announce",
                        "http://a.example.org/announce",
                        "https://B.EXAMPLE.ORG/announce",
                        "")));
        assertEquals(List.of(), TrackerHost.hosts(List.of()));
    }
}
