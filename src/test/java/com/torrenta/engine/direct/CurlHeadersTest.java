package com.torrenta.engine.direct;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurlHeadersTest {

    @Test
    void onlyTheLastAnswerCountsAfterRedirects() {
        String dump = "HTTP/1.1 302 Found\r\nLocation: /real\r\nContent-Length: 0\r\n\r\n"
                + "HTTP/2 200\r\ncontent-type: application/octet-stream\r\ncontent-length: 1234\r\n"
                + "content-disposition: attachment; filename=\"report 2026.pdf\"\r\n\r\n";
        CurlHeaders h = CurlHeaders.parse(dump);
        assertEquals(200, h.status());
        assertEquals(1234, h.total());
        assertEquals("report 2026.pdf", h.fileName());
        assertEquals("application/octet-stream", h.contentType());
        assertFalse(h.isTorrent());
    }

    @Test
    void aResumedAnswerHasTheWholeSizeInItsRange() {
        CurlHeaders h = CurlHeaders.parse(
                "HTTP/1.1 206 Partial Content\nContent-Range: bytes 500-999/1000\n" + "Content-Length: 500\n\n");
        assertEquals(206, h.status());
        assertEquals(1000, h.total());
        assertEquals(
                1000,
                CurlHeaders.parse("HTTP/1.1 416 Range Not Satisfiable\nContent-Range: bytes */1000\n\n")
                        .total());
        assertEquals(
                -1,
                CurlHeaders.parse("HTTP/1.1 206 Partial\nContent-Range: bytes 0-1/*\n\n")
                        .total());
    }

    @Test
    void theExtendedNameWinsAndAPathIsCutToItsName() {
        assertEquals(
                "€ rates.csv",
                CurlHeaders.dispositionName(
                        "attachment; filename=\"rates.csv\"; filename*=UTF-8''%E2%82%AC%20rates.csv"));
        assertEquals("evil.sh", CurlHeaders.dispositionName("attachment; filename=\"../../evil.sh\""));
        assertEquals("a;b.txt", CurlHeaders.dispositionName("attachment; filename=\"a;b.txt\""));
        assertEquals("plain.bin", CurlHeaders.dispositionName("inline; filename=plain.bin"));
        assertEquals("", CurlHeaders.dispositionName("attachment"));
    }

    @Test
    void aTorrentIsKnownByItsTypeOrName() {
        assertTrue(CurlHeaders.parse("HTTP/1.1 200 OK\nContent-Type: application/x-bittorrent; charset=x\n\n")
                .isTorrent());
        assertTrue(CurlHeaders.parse("HTTP/1.1 200 OK\nContent-Disposition: attachment; filename=a.TORRENT\n\n")
                .isTorrent());
    }

    @Test
    void nothingOrAnFtpDialogueSaysNothing() {
        assertEquals(CurlHeaders.NONE, CurlHeaders.parse(""));
        assertEquals(CurlHeaders.NONE, CurlHeaders.parse(null));
        assertEquals(CurlHeaders.NONE, CurlHeaders.parse("220 Welcome\n331 Password required\n"));
        assertEquals(
                -1,
                CurlHeaders.parse("HTTP/1.1 404 Not Found\nContent-Length: 9\n\n")
                        .total());
    }
}
