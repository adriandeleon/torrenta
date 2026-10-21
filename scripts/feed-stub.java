/*
 * A stand-in feed server for captures and manual runs: serves one file as /feed.xml with an ETag,
 * answers 304 when the ETag comes back, anything else with 404. Run as a single-file program:
 *
 *   java scripts/feed-stub.java 9898 src/test/resources/com/torrenta/rss/rss2.xml
 *
 * then add http://127.0.0.1:9898/feed.xml as a feed. Stops on Ctrl+C.
 */
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import com.sun.net.httpserver.HttpServer;

public class FeedStub {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: java scripts/feed-stub.java <port> <feed.xml>");
            System.exit(2);
        }
        int port = Integer.parseInt(args[0]);
        byte[] feed = Files.readAllBytes(Path.of(args[1]));
        String etag = "\"" + Integer.toHexString(java.util.Arrays.hashCode(feed)) + "\"";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", exchange -> {
            System.out.println(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " If-None-Match="
                    + exchange.getRequestHeaders().getFirst("If-None-Match"));
            if (!exchange.getRequestURI().getPath().equals("/feed.xml")) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                exchange.sendResponseHeaders(304, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "application/rss+xml; charset=utf-8");
            exchange.getResponseHeaders().add("ETag", etag);
            exchange.sendResponseHeaders(200, feed.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(feed);
            }
        });
        server.start();
        System.out.println("feed stub on http://127.0.0.1:" + port + "/feed.xml");
    }
}
