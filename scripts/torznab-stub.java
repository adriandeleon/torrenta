/*
 * A stand-in Torznab server for captures and manual runs: answers t=caps with one fixture and
 * t=search with another, anything else with 404. Run as a single-file program:
 *
 *   java scripts/torznab-stub.java 9696 src/test/resources/com/torrenta/search/prowlarr-search.xml \
 *       src/test/resources/com/torrenta/search/prowlarr-caps.xml
 *
 * then point a provider at http://127.0.0.1:9696/api (any key). Stops on Ctrl+C.
 */
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import com.sun.net.httpserver.HttpServer;

public class TorznabStub {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: java scripts/torznab-stub.java <port> <search.xml> [caps.xml]");
            System.exit(2);
        }
        int port = Integer.parseInt(args[0]);
        byte[] search = Files.readAllBytes(Path.of(args[1]));
        byte[] caps = args.length > 2 ? Files.readAllBytes(Path.of(args[2])) : search;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            query = query == null ? "" : query;
            byte[] body = query.contains("t=caps") ? caps : query.contains("t=search") ? search : null;
            System.out.println(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "application/rss+xml; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        System.out.println("torznab stub on http://127.0.0.1:" + port + "/api");
    }
}
