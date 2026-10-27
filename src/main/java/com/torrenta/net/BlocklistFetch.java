package com.torrenta.net;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.zip.GZIPInputStream;

import static com.torrenta.i18n.Messages.tr;

/** Downloads a blocklist, plain or gzip-compressed, bounded in time and size. */
public final class BlocklistFetch {

    /** The largest lists in the wild are tens of megabytes gzipped; this is well above them. */
    static final long MAX_BYTES = 200L << 20;

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient client;

    public BlocklistFetch() {
        this(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(TIMEOUT)
                .build());
    }

    BlocklistFetch(HttpClient client) {
        this.client = client;
    }

    /** The list's text, decompressed if it came gzipped (by magic number, not by extension). */
    public byte[] fetch(URI url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(url).timeout(TIMEOUT).GET().build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(tr("net.fetchInterrupted", url), e);
        } catch (IOException e) {
            throw new IOException(tr("net.fetchFailed", url, TorrentFetch.reason(e, TIMEOUT)), e);
        }
        if (response.statusCode() / 100 != 2)
            throw new IOException(tr("net.fetchStatus", String.valueOf(response.statusCode()), url));
        try (InputStream in = response.body()) {
            byte[] bytes = in.readNBytes((int) MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException(tr("net.blocklistTooLarge"));
            return gunzipIfNeeded(bytes);
        }
    }

    /** Gzip's magic number is {@code 1f 8b}; a list served without a {@code .gz} name still has it. */
    public static byte[] gunzipIfNeeded(byte[] bytes) throws IOException {
        if (bytes.length < 2 || (bytes[0] & 0xff) != 0x1f || (bytes[1] & 0xff) != 0x8b) return bytes;
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            byte[] out = in.readNBytes((int) MAX_BYTES + 1);
            if (out.length > MAX_BYTES) throw new IOException(tr("net.blocklistTooLarge"));
            return out;
        }
    }

    /**
     * The same, as a stream, for a list read line by line rather than whole: a 300,000-line list
     * held as bytes and then as a String was three copies of it at once. The decompressed text is
     * bounded as above; plain text is not, as a file read whole was not.
     */
    public static InputStream gunzipIfNeeded(InputStream in) throws IOException {
        return gunzipIfNeeded(in, MAX_BYTES);
    }

    static InputStream gunzipIfNeeded(InputStream in, long max) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(in, 1 << 16);
        buffered.mark(2);
        int first = buffered.read();
        int second = buffered.read();
        buffered.reset();
        if (first != 0x1f || second != 0x8b) return buffered;
        return new Bounded(new GZIPInputStream(buffered, 1 << 16), max);
    }

    /** Refuses to read past {@code max} bytes, as {@code readNBytes(max + 1)} did. */
    private static final class Bounded extends FilterInputStream {
        private final long max;
        private long count;

        Bounded(InputStream in, long max) {
            super(in);
            this.max = max;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) counted(1);
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = super.read(buffer, offset, length);
            if (n > 0) counted(n);
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            counted(skipped);
            return skipped;
        }

        private void counted(long n) throws IOException {
            count += n;
            if (count > max) throw new IOException(tr("net.blocklistTooLarge"));
        }
    }
}
