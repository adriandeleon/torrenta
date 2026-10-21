/*
 * A stand-in site for a definition file: serves the files of one folder over HTTP, the query
 * string ignored, so a recorded listing answers any search. Run as a single-file program:
 *
 *   java scripts/search-stub.java 9797 src/test/resources/com/torrenta/search/declarative
 *
 * then a definition whose search.url is http://127.0.0.1:9797/linuxtracker.html?q={query} (or
 * archive-org.json, academictorrents.xml, messy-table.html) searches it. Stops on Ctrl+C.
 */
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import com.sun.net.httpserver.HttpServer;

public class SearchStub {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: java scripts/search-stub.java <port> <folder>");
            System.exit(2);
        }
        int port = Integer.parseInt(args[0]);
        Path folder = Path.of(args[1]).toAbsolutePath().normalize();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", exchange -> {
            String name = Path.of(exchange.getRequestURI().getPath()).getFileName() == null
                    ? ""
                    : Path.of(exchange.getRequestURI().getPath()).getFileName().toString();
            Path file = folder.resolve(name).normalize();
            System.out.println(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            if (name.isEmpty() || !file.startsWith(folder) || !Files.isRegularFile(file)) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            byte[] body = Files.readAllBytes(file);
            String type = name.endsWith(".json") ? "application/json" : name.endsWith(".xml") ? "application/xml" : "text/html";
            exchange.getResponseHeaders().add("Content-Type", type + "; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        System.out.println("search stub on http://127.0.0.1:" + port + "/ serving " + folder);
    }
}
