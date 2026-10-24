package com.torrenta.engine.direct;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurlTest {

    private static Curl.Request request(boolean resume, long limit, Curl.Proxy proxy) {
        return new Curl.Request(
                "https://x.org/a?token=s3cr\"t",
                Path.of("/dl/a.iso.part"),
                Path.of("/cfg/downloads/url-1.headers"),
                resume,
                limit,
                proxy,
                "Torrenta/1.0");
    }

    @Test
    void theCommandLineCarriesNothingButTheProgram() {
        assertEquals(List.of("curl", "--disable", "--config", "-"), Curl.command(""));
        assertEquals(List.of("/opt/curl/bin/curl", "--disable", "--config", "-"), Curl.command(" /opt/curl/bin/curl "));
    }

    @Test
    void theConfigHasTheTransferAndQuotesWhatItMust() {
        String config = Curl.config(request(false, 0, Curl.Proxy.NONE));
        assertTrue(config.contains("url = \"https://x.org/a?token=s3cr\\\"t\"\n"), config);
        // Quoted as curl reads it: on Windows the path's backslashes are escaped.
        assertTrue(
                config.contains(
                        "output = " + Curl.quote(Path.of("/dl/a.iso.part").toString()) + "\n"),
                config);
        assertTrue(config.contains("\nlocation\n"));
        assertTrue(config.contains("\nfail\n"));
        assertTrue(config.contains("proto = \"=http,https,ftp,ftps,sftp\""));
        assertTrue(config.contains("user-agent = \"Torrenta/1.0\""));
        assertFalse(config.contains("continue-at"));
        assertFalse(config.contains("limit-rate"));
        assertFalse(config.contains("proxy"));
    }

    @Test
    void resumeLimitAndProxyAreAddedWhenAsked() {
        String config = Curl.config(request(true, 51200, new Curl.Proxy("socks5h", "::1", 1080, "me", "p\\w")));
        assertTrue(config.contains("continue-at = \"-\""));
        assertTrue(config.contains("limit-rate = \"51200\""));
        assertTrue(config.contains("proxy = \"socks5h://[::1]:1080\""), config);
        assertTrue(config.contains("proxy-user = \"me:p\\\\w\""), config);
    }

    @Test
    void quotingEscapesBackslashesQuotesAndLineBreaks() {
        assertEquals("\"C:\\\\dl\\\\a\\\"b\\n\"", Curl.quote("C:\\dl\\a\"b\n"));
    }

    @Test
    void aCleanExitIsFinished() {
        assertInstanceOf(Curl.Outcome.Finished.class, Curl.outcome(0, CurlHeaders.NONE, 10, false, "x.org", ""));
    }

    @Test
    void aRefusedResumeStartsAgainOnceThenFails() {
        CurlHeaders refused = CurlHeaders.parse("HTTP/1.1 416 Range Not Satisfiable\n\n");
        assertInstanceOf(Curl.Outcome.Restart.class, Curl.outcome(22, refused, 10, false, "x.org", ""));
        assertInstanceOf(Curl.Outcome.Restart.class, Curl.outcome(33, CurlHeaders.NONE, 10, false, "x.org", ""));
        assertInstanceOf(Curl.Outcome.Failed.class, Curl.outcome(33, CurlHeaders.NONE, 10, true, "x.org", ""));
    }

    @Test
    void aResumeOfACompleteFileIsFinished() {
        CurlHeaders all = CurlHeaders.parse("HTTP/1.1 416 Range Not Satisfiable\nContent-Range: bytes */1000\n\n");
        assertInstanceOf(Curl.Outcome.Finished.class, Curl.outcome(22, all, 1000, false, "x.org", ""));
        assertInstanceOf(Curl.Outcome.Restart.class, Curl.outcome(22, all, 999, false, "x.org", ""));
    }

    @Test
    void failuresSayWhatWentWrong() {
        CurlHeaders missing = CurlHeaders.parse("HTTP/1.1 404 Not Found\n\n");
        Curl.Outcome.Failed f = (Curl.Outcome.Failed)
                Curl.outcome(22, missing, 0, false, "x.org", "curl: (22) The requested URL returned error: 404\n");
        assertEquals("The server answered HTTP 404", f.message());
        assertEquals("Could not find the server x.org", Curl.message(6, 0, "x.org", ""));
        assertEquals(
                "The secure connection failed: SSL certificate problem",
                Curl.message(
                        60,
                        0,
                        "x.org",
                        "curl: (60) SSL certificate problem\nMore details here: https://curl.se/docs\n"));
        assertEquals("curl stopped with code 99: odd", Curl.message(99, 0, "x.org", "curl: (99) odd"));
    }

    @Test
    void curlsOwnWordsAreItsLastErrorLine() {
        assertEquals("Failed to connect", Curl.said("\ncurl: (7) Failed to connect\n\n"));
        assertEquals("plain text", Curl.said("plain text"));
        assertEquals("", Curl.said(null));
    }
}
