package com.torrenta.webapi;

import java.security.SecureRandom;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The API's pure security parts: the password, the login guard, the sessions, the host checks. */
class SecurityTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Test
    void aPasswordIsKeptOnlyAsASaltedHashAndCheckedAgainstIt() {
        String stored = Passwords.hash("correct horse".toCharArray(), RANDOM);
        assertTrue(stored.startsWith("pbkdf2-sha256$600000$"), stored);
        assertFalse(stored.contains("correct horse"));
        assertTrue(Passwords.verify("correct horse".toCharArray(), stored));
        assertFalse(Passwords.verify("correct horsf".toCharArray(), stored));
        assertNotEquals(stored, Passwords.hash("correct horse".toCharArray(), RANDOM), "a salt of its own each time");
        assertTrue(Passwords.isSet(stored));
        assertFalse(Passwords.isSet(""));
        for (String junk :
                new String[] {null, "", "plain", "md5$1$a$b", "pbkdf2-sha256$x$a$b", "pbkdf2-sha256$0$AA==$AA=="})
            assertFalse(Passwords.verify("x".toCharArray(), junk), String.valueOf(junk));
    }

    @Test
    void theFifthFailedLoginBansTheAddressForAnHour() {
        LoginGuard guard = new LoginGuard();
        long t = 1_000_000;
        for (int i = 1; i < LoginGuard.MAX_FAILURES; i++) assertFalse(guard.failed("203.0.113.9", t), "failure " + i);
        assertTrue(guard.allowed("203.0.113.9", t));
        assertTrue(guard.failed("203.0.113.9", t), "the fifth bans");
        assertFalse(guard.allowed("203.0.113.9", t + 1));
        assertTrue(guard.allowed("198.51.100.1", t + 1), "another address is not touched");
        assertFalse(guard.allowed("203.0.113.9", t + LoginGuard.BAN_MILLIS - 1));
        assertTrue(guard.allowed("203.0.113.9", t + LoginGuard.BAN_MILLIS), "an hour later");
        for (int i = 1; i < LoginGuard.MAX_FAILURES; i++) guard.failed("192.0.2.5", t);
        guard.succeeded("192.0.2.5");
        assertFalse(guard.failed("192.0.2.5", t), "a good login starts the count over");
    }

    @Test
    void aSessionLastsWhileItIsUsedAndAnHourWithout() {
        Sessions sessions = new Sessions(RANDOM);
        long t = 5_000;
        String sid = sessions.open(t);
        assertTrue(sid.length() >= 43, "32 random bytes");
        assertTrue(sessions.valid(sid, t + Sessions.IDLE_MILLIS - 1), "used within the hour");
        assertTrue(sessions.valid(sid, t + 2 * Sessions.IDLE_MILLIS - 2), "and the hour starts again from there");
        assertFalse(sessions.valid(sid, t + 4 * Sessions.IDLE_MILLIS), "idle for longer: gone");
        assertFalse(sessions.valid(sid, t + 4 * Sessions.IDLE_MILLIS + 1), "for good");
        String other = sessions.open(t);
        sessions.close(other);
        assertFalse(sessions.valid(other, t));
        assertFalse(sessions.valid(null, t));
        String a = sessions.open(t);
        sessions.closeAll();
        assertFalse(sessions.valid(a, t), "a password change logs everyone out");
    }

    /** DNS rebinding: a page on another name that resolves here sends that name, and is refused. */
    @Test
    void theHostMustNameThisServer() {
        Set<String> none = Set.of();
        for (String ok : new String[] {"127.0.0.1:8080", "localhost:8080", "[::1]:8080", "192.168.1.68", "::1"})
            assertTrue(Hosts.hostAllowed(ok, none), ok);
        assertFalse(Hosts.hostAllowed("evil.example:8080", none));
        assertFalse(Hosts.hostAllowed(null, none));
        assertFalse(Hosts.hostAllowed("", none));
        assertTrue(Hosts.hostAllowed("nas.lan:8080", Set.of("nas.lan")), "a domain the person listed");
        assertTrue(Hosts.hostAllowed("anything:8080", Set.of("*")), "or any, when they said so");
    }

    /** A browser page elsewhere cannot post with the person's cookie; a client that is no browser is fine. */
    @Test
    void aBrowsersOriginMustBeThisServer() {
        assertTrue(Hosts.sameOrigin(Optional.empty(), Optional.empty(), "127.0.0.1:8080"), "Sonarr sends neither");
        assertTrue(Hosts.sameOrigin(Optional.of("http://127.0.0.1:8080"), Optional.empty(), "127.0.0.1:8080"));
        assertTrue(Hosts.sameOrigin(Optional.of("https://nas.lan"), Optional.empty(), "nas.lan"), "default ports");
        assertTrue(Hosts.sameOrigin(Optional.empty(), Optional.of("http://[::1]:8080/index.html"), "[::1]:8080"));
        assertFalse(Hosts.sameOrigin(Optional.of("http://evil.example"), Optional.empty(), "127.0.0.1:8080"));
        assertFalse(
                Hosts.sameOrigin(Optional.of("http://127.0.0.1:9999"), Optional.empty(), "127.0.0.1:8080"),
                "another port");
        assertFalse(Hosts.sameOrigin(Optional.of("null"), Optional.empty(), "127.0.0.1:8080"), "a sandboxed page");
        assertEquals("::1", Hosts.hostOf("[::1]:8080"));
        assertEquals(8080, Hosts.portOf("[::1]:8080", 80));
        assertEquals(80, Hosts.portOf("::1", 80), "a bare IPv6 address has no port");
    }
}
