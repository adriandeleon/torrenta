package com.torrenta.http;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The plumbing's pure parts: forms, multipart bodies, cookies, ranges. */
class ParsersTest {

    @Test
    void aFormKeepsRepeatedNamesInOrderAndDecodesUtf8() {
        Form f = Form.parse("hashes=a%7Cb&tags=x&tags=y&empty&name=caf%C3%A9+cr%C3%A8me");
        assertEquals(Optional.of("a|b"), f.first("hashes"));
        assertEquals(List.of("x", "y"), f.all("tags"));
        assertEquals(Optional.of(""), f.first("empty"), "a name without = is an empty value");
        assertEquals(Optional.of("café crème"), f.first("name"));
        assertTrue(Form.parse(null).values().isEmpty());
        HttpProblem bad = assertThrows(HttpProblem.class, () -> Form.parse("a=%ZZ"));
        assertEquals(400, bad.status());
    }

    private static byte[] multipart(String boundary, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts)
            sb.append("--").append(boundary).append("\r\n").append(part).append("\r\n");
        sb.append("--").append(boundary).append("--\r\n");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /** As Sonarr posts a .torrent: a file part with its name and type, fields beside it. */
    @Test
    void aMultipartBodyIsItsPartsWithTheirNamesAndBytes() {
        String boundary = "----Boundary7MA4YWxk";
        assertEquals(Optional.of(boundary), Multipart.boundary("multipart/form-data; boundary=\"" + boundary + "\""));
        assertEquals(Optional.empty(), Multipart.boundary("application/json"));
        byte[] body = multipart(
                boundary,
                "Content-Disposition: form-data; name=\"category\"\r\n\r\ntv",
                "Content-Disposition: form-data; name=\"torrents\"; filename=\"Show.S01E02.torrent\"\r\n"
                        + "Content-Type: application/x-bittorrent\r\n\r\nd8:announce\r\n3:xyze");
        List<Multipart.Part> parts = Multipart.parse(body, boundary, Limits.DEFAULT);
        assertEquals(2, parts.size());
        assertEquals("category", parts.get(0).name());
        assertFalse(parts.get(0).isFile());
        assertEquals("tv", parts.get(0).text());
        Multipart.Part file = parts.get(1);
        assertEquals("Show.S01E02.torrent", file.fileName());
        assertEquals("application/x-bittorrent", file.contentType());
        assertArrayEquals(
                "d8:announce\r\n3:xyze".getBytes(StandardCharsets.ISO_8859_1),
                file.bytes(),
                "a CRLF inside is content");
    }

    @Test
    void aMultipartBodyPastThePartLimitOrOutOfShapeIsRefused() {
        String b = "B";
        byte[] three = multipart(
                b,
                "Content-Disposition: form-data; name=\"a\"\r\n\r\n1",
                "Content-Disposition: form-data; name=\"b\"\r\n\r\n2",
                "Content-Disposition: form-data; name=\"c\"\r\n\r\n3");
        assertEquals(
                413,
                assertThrows(HttpProblem.class, () -> Multipart.parse(three, b, new Limits(1000, 2)))
                        .status());
        assertEquals(
                400,
                assertThrows(HttpProblem.class, () -> Multipart.parse("nothing".getBytes(), b, Limits.DEFAULT))
                        .status());
        byte[] unnamed = multipart(b, "Content-Disposition: form-data\r\n\r\nx");
        assertEquals(
                400,
                assertThrows(HttpProblem.class, () -> Multipart.parse(unnamed, b, Limits.DEFAULT))
                        .status());
        byte[] unended = "--B\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nno end".getBytes();
        assertEquals(
                400,
                assertThrows(HttpProblem.class, () -> Multipart.parse(unended, b, Limits.DEFAULT))
                        .status());
    }

    @Test
    void cookiesAreReadAndASessionCookieIsAlwaysHttpOnlyAndStrict() {
        assertEquals(Map.of("SID", "abc", "theme", "dark"), Cookies.parse("SID=abc; theme=\"dark\"; junk; SID=second"));
        assertEquals("SID=abc; Path=/; HttpOnly; SameSite=Strict", Cookies.set("SID", "abc", -1, false));
        assertEquals("SID=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0; Secure", Cookies.set("SID", "", 0, true));
        assertThrows(
                IllegalArgumentException.class,
                () -> Cookies.set("SID", "a;b", -1, false),
                "nothing that ends the value");
    }

    @Test
    void oneRangeIsAPartAndAnythingElseIsTheWholeFile() {
        assertEquals(new Ranges.Part(0, 99), Ranges.plan("bytes=0-99", 1000));
        assertEquals(new Ranges.Part(900, 999), Ranges.plan("bytes=900-", 1000));
        assertEquals(new Ranges.Part(900, 999), Ranges.plan("bytes=-100", 1000), "the last hundred");
        assertEquals(new Ranges.Part(0, 999), Ranges.plan("bytes=-5000", 1000), "more than there is");
        assertEquals(new Ranges.Part(500, 999), Ranges.plan("bytes=500-5000", 1000), "cut to the end");
        assertEquals(new Ranges.Unsatisfiable(), Ranges.plan("bytes=1000-", 1000));
        assertEquals("bytes */1000", new Ranges.Unsatisfiable().contentRange(1000));
        assertEquals("bytes 0-99/1000", new Ranges.Part(0, 99).contentRange(1000));
        for (String whole : new String[] {null, "items=0-1", "bytes=0-1,5-6", "bytes=x-y", "bytes=5-2", "bytes=7"})
            assertEquals(new Ranges.Whole(), Ranges.plan(whole, 1000), String.valueOf(whole));
    }
}
