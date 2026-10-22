package com.torrenta.engine;

import java.net.URI;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The profile record's rules: the RPC path, the host line, what makes a URL unusable. */
class EngineProfileTest {

    private static EngineProfile remote(String url) {
        return new EngineProfile("x", "Box", EngineProfile.Kind.REMOTE, url, "", "");
    }

    @Test
    void theRpcPathIsAddedToAHostAndKeptWhenGiven() {
        assertEquals(
                URI.create("http://box:9091/transmission/rpc"),
                remote("http://box:9091").rpcUri());
        assertEquals(
                URI.create("http://box:9091/transmission/rpc"),
                remote("http://box:9091/").rpcUri());
        assertEquals(
                URI.create("https://box/tr/rpc"), remote("https://box/tr/rpc").rpcUri(), "a path of its own is kept");
        assertEquals(
                URI.create("http://box:9091/transmission/rpc"),
                remote("http://box:9091/transmission/rpc").rpcUri());
        assertEquals("box:9091", remote("http://box:9091").hostLine());
        assertEquals("box", remote("https://box/tr").hostLine());
    }

    @Test
    void aUrlNeedsASchemeAndAHost() {
        assertTrue(EngineProfile.problem("").isPresent());
        assertTrue(EngineProfile.problem("   ").isPresent());
        assertTrue(EngineProfile.problem("box:9091").isPresent(), "no scheme");
        assertTrue(EngineProfile.problem("ftp://box").isPresent());
        assertTrue(EngineProfile.problem("http://").isPresent(), "no host");
        assertTrue(EngineProfile.problem("http://bad host/").isPresent());
        assertTrue(EngineProfile.problem("http://box:9091").isEmpty());
        assertTrue(EngineProfile.problem("HTTPS://seedbox.example.net/transmission/rpc")
                .isEmpty());
        assertTrue(EngineProfile.problem("http://[::1]:9091").isEmpty());
    }

    @Test
    void theLocalProfileIsFixedAndNamedByTheCatalogue() {
        assertTrue(EngineProfile.LOCAL.isLocal());
        assertEquals("local", EngineProfile.LOCAL.id());
        assertFalse(EngineProfile.LOCAL.displayName().isBlank());
        assertEquals("Box", remote("http://b").displayName());
        assertEquals(
                "http://b",
                new EngineProfile("y", "", EngineProfile.Kind.REMOTE, "http://b", "", "").displayName(),
                "a nameless remote shows its URL");
        assertEquals("p", remote("http://b").withPassword("p").password());
        assertThrows(
                IllegalArgumentException.class,
                () -> new EngineProfile("", "n", EngineProfile.Kind.REMOTE, "http://b", "", ""));
        assertEquals(
                "  trimmed  ".trim(),
                new EngineProfile("i", "  trimmed  ", EngineProfile.Kind.REMOTE, " http://b ", " u ", " p ").name());
        EngineProfile p = new EngineProfile("i", "n", EngineProfile.Kind.REMOTE, " http://b ", " u ", " p ");
        assertEquals("http://b", p.url());
        assertEquals("u", p.username());
        assertEquals(" p ", p.password(), "a password is never trimmed");
        assertEquals(EngineProfile.Kind.REMOTE, new EngineProfile("i", "n", null, "http://b", "", "").kind());
        assertFalse(EngineProfile.newId().equals(EngineProfile.newId()));
    }
}
