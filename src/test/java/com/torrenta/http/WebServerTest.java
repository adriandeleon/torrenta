package com.torrenta.http;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Optional;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The plumbing against a real server on loopback, driven by the JDK's own client. */
class WebServerTest {

    private WebServer server;

    @AfterEach
    void stop() {
        if (server != null) server.close();
    }

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private String base() {
        InetSocketAddress a = server.address();
        return (server.secure() ? "https" : "http") + "://127.0.0.1:" + a.getPort();
    }

    private Router router(Path files) {
        return new Router()
                .route("GET", "/api/v2/app/version", r -> Response.text(200, "v5.0.0"))
                .route("POST", "/api/v2/auth/login", r -> {
                    String user = r.form().first("username").orElse("");
                    return Response.text(200, "Ok.").withCookie(Cookies.set("SID", "s-" + user, -1, r.secure()));
                })
                .route("GET", "/whoami", r -> Response.text(200, r.cookies().getOrDefault("SID", "nobody")))
                .route("POST", "/api/v2/torrents/add", r -> {
                    StringBuilder sb =
                            new StringBuilder(r.form().first("category").orElse("-"));
                    for (Multipart.Part p : r.parts())
                        if (p.isFile())
                            sb.append(' ').append(p.fileName()).append('=').append(p.bytes().length);
                    return Response.text(200, sb.toString());
                })
                .route("GET", "/boom", r -> {
                    throw new IllegalStateException("a handler's own failure");
                })
                .route(
                        "GET",
                        "/query",
                        r -> Response.text(200, String.join(",", r.query().all("hashes"))))
                .prefix(
                        "GET",
                        "/files/",
                        r -> Response.file(
                                files.resolve(r.path().substring("/files/".length())), "application/octet-stream"))
                .filter(r ->
                        r.path().equals("/private") ? Optional.of(Response.text(403, "Forbidden")) : Optional.empty());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder at(String path) {
        return HttpRequest.newBuilder(URI.create(base() + path));
    }

    @Test
    void routesFormsCookiesAndTheStatusesOfWhatHasNoRoute(@TempDir Path dir) throws Exception {
        server = WebServer.start(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), router(dir), Limits.DEFAULT, null);
        assertEquals("v5.0.0", send(at("/api/v2/app/version").GET().build()).body());

        HttpResponse<String> login = send(at("/api/v2/auth/login")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=admin&password=x"))
                .build());
        String cookie = login.headers().firstValue("Set-Cookie").orElseThrow();
        assertEquals("SID=s-admin; Path=/; HttpOnly; SameSite=Strict", cookie);
        assertEquals(
                "s-admin",
                send(at("/whoami").header("Cookie", "SID=s-admin").GET().build())
                        .body());

        assertEquals(
                "a|b,c", send(at("/query?hashes=a%7Cb&hashes=c").GET().build()).body(), "the query, decoded");
        HttpResponse<String> wrongMethod = send(at("/api/v2/app/version")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
        assertEquals(405, wrongMethod.statusCode());
        assertEquals(Optional.of("GET"), wrongMethod.headers().firstValue("Allow"));
        assertEquals(404, send(at("/api/v2/nothing").GET().build()).statusCode());
        assertEquals(403, send(at("/private").GET().build()).statusCode(), "a filter answers first");
        assertEquals(500, send(at("/boom").GET().build()).statusCode(), "a handler's failure is 500, logged");
    }

    /** As the *arr apps add: multipart, a field and a .torrent; a body past the limit is refused unread. */
    @Test
    void aMultipartUploadAndABodyOverTheLimit(@TempDir Path dir) throws Exception {
        server = WebServer.start(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), router(dir), new Limits(4096, 8), null);
        String boundary = "XyZ123";
        byte[] torrent = new byte[1000];
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"category\"\r\n\r\ntv\r\n"
                        + "--" + boundary
                        + "\r\nContent-Disposition: form-data; name=\"torrents\"; filename=\"a.torrent\"\r\n"
                        + "Content-Type: application/x-bittorrent\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        body.writeBytes(torrent);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1));
        HttpResponse<String> added = send(at("/api/v2/torrents/add")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build());
        assertEquals("tv a.torrent=1000", added.body());

        HttpResponse<String> big = send(at("/api/v2/torrents/add")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[5000]))
                .build());
        assertEquals(413, big.statusCode());
    }

    /** A file whole, a range of it (206), a range past it (416), and a file that is not there. */
    @Test
    void aFileIsSentWholeOrTheRangeAsked(@TempDir Path dir) throws Exception {
        byte[] data = new byte[1000];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;
        Files.write(dir.resolve("film.mkv"), data);
        server = WebServer.start(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), router(dir), Limits.DEFAULT, null);

        HttpResponse<byte[]> whole =
                CLIENT.send(at("/files/film.mkv").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, whole.statusCode());
        assertEquals(1000, whole.body().length);
        assertEquals(Optional.of("bytes"), whole.headers().firstValue("Accept-Ranges"));

        HttpResponse<byte[]> part = CLIENT.send(
                at("/files/film.mkv").header("Range", "bytes=100-199").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(206, part.statusCode());
        assertEquals(Optional.of("bytes 100-199/1000"), part.headers().firstValue("Content-Range"));
        assertEquals(100, part.body().length);
        assertEquals((byte) 100, part.body()[0]);

        HttpResponse<byte[]> past = CLIENT.send(
                at("/files/film.mkv").header("Range", "bytes=5000-").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(416, past.statusCode());
        assertEquals(Optional.of("bytes */1000"), past.headers().firstValue("Content-Range"));
        assertEquals(404, send(at("/files/nothing.mkv").GET().build()).statusCode());
    }

    /** HTTPS from a PKCS#12 file, as keytool makes one; the cookie then says Secure. */
    @Test
    void httpsFromAPkcs12File(@TempDir Path dir) throws Exception {
        Path p12 = dir.resolve("server.p12");
        char[] password = "changeit".toCharArray();
        Path keytool = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "keytool.exe" : "keytool");
        Process gen = new ProcessBuilder(
                        keytool.toString(),
                        "-genkeypair",
                        "-alias",
                        "torrenta",
                        "-keyalg",
                        "RSA",
                        "-keysize",
                        "2048",
                        "-validity",
                        "2",
                        "-dname",
                        "CN=localhost",
                        "-ext",
                        "SAN=ip:127.0.0.1",
                        "-storetype",
                        "PKCS12",
                        "-keystore",
                        p12.toString(),
                        "-storepass",
                        "changeit",
                        "-keypass",
                        "changeit")
                .redirectErrorStream(true)
                .start();
        String out;
        try (InputStream in = gen.getInputStream()) {
            out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertEquals(0, gen.waitFor(), out);

        server = WebServer.start(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                router(dir),
                Limits.DEFAULT,
                Tls.fromPkcs12(p12, password));
        assertTrue(server.secure());

        KeyStore trust = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(p12)) {
            trust.load(in, password);
        }
        TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tm.init(trust);
        SSLContext client = SSLContext.getInstance("TLS");
        client.init(null, tm.getTrustManagers(), null);
        HttpClient https = HttpClient.newBuilder().sslContext(client).build();

        assertEquals(
                "v5.0.0",
                https.send(at("/api/v2/app/version").GET().build(), HttpResponse.BodyHandlers.ofString())
                        .body());
        HttpResponse<String> login = https.send(
                at("/api/v2/auth/login")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("username=admin"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(
                login.headers().firstValue("Set-Cookie").orElseThrow().endsWith("; Secure"),
                "over HTTPS the cookie says so");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .startsWith("windows");
    }
}
