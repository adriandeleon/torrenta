package com.torrenta.engine.transmission;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A stand-in transmission-daemon on a loopback port: the 409 session-id handshake, optional basic
 * auth, one JSON answer per method, every request recorded for the test to look at.
 */
final class StubRpcServer implements AutoCloseable {

    /** One request as the stub saw it. */
    record Call(String method, Map<String, Object> arguments, boolean hadSessionId, String authorization) {}

    private final HttpServer server;
    private final String sessionId = "stub-session-" + System.nanoTime();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final Map<String, Function<Map<String, Object>, String>> answers =
            new java.util.concurrent.ConcurrentHashMap<>();
    private volatile String expectedAuthorization = "";
    private volatile int forcedStatus;
    private volatile String forcedBody = "";

    StubRpcServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/transmission/rpc", this::handle);
        server.start();
    }

    URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/transmission/rpc");
    }

    /** The host:port alone, the way a person would type it. */
    String hostUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void requireAuth(String username, String password) {
        expectedAuthorization = "Basic "
                + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    /** Every request answered with this status and body, whatever the method (for error paths). */
    void force(int status, String body) {
        forcedStatus = status;
        forcedBody = body;
    }

    /** The answer for a method, as a JSON string; the arguments are the request's. */
    void answer(String method, Function<Map<String, Object>, String> answer) {
        answers.put(method, answer);
    }

    void answer(String method, String json) {
        answers.put(method, args -> json);
    }

    /** {@code {"arguments":…,"result":"success"}} around an arguments object. */
    static String success(Map<String, Object> arguments) {
        return Json.write(Map.of("arguments", arguments, "result", "success"));
    }

    static String success() {
        return "{\"arguments\":{},\"result\":\"success\"}";
    }

    List<Call> calls() {
        return List.copyOf(calls);
    }

    List<Call> calls(String method) {
        List<Call> out = new ArrayList<>();
        for (Call c : calls) if (c.method().equals(method)) out.add(c);
        return out;
    }

    Call last(String method) {
        List<Call> of = calls(method);
        if (of.isEmpty())
            throw new AssertionError("no " + method + " call; saw "
                    + calls.stream().map(Call::method).toList());
        return of.get(of.size() - 1);
    }

    void clearCalls() {
        calls.clear();
    }

    private void handle(HttpExchange x) throws IOException {
        byte[] body;
        try (InputStream in = x.getRequestBody()) {
            body = in.readAllBytes();
        }
        if (forcedStatus != 0) {
            reply(x, forcedStatus, forcedBody);
            return;
        }
        String auth = x.getRequestHeaders().getFirst("Authorization");
        if (!expectedAuthorization.isEmpty() && !expectedAuthorization.equals(auth)) {
            x.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"Transmission\"");
            reply(x, 401, "<h1>401: Unauthorized User</h1>");
            return;
        }
        String given = x.getRequestHeaders().getFirst(RpcClient.SESSION_HEADER);
        Map<String, Object> request = Json.obj(Json.parse(new String(body, StandardCharsets.UTF_8)));
        String method = Json.str(request, "method", "");
        if (!sessionId.equals(given)) {
            calls.add(new Call(method, Json.obj(request, "arguments"), false, auth == null ? "" : auth));
            x.getResponseHeaders().add(RpcClient.SESSION_HEADER, sessionId);
            reply(x, 409, "<h1>409: Conflict</h1><p>Your request had an invalid session-id header.</p>");
            return;
        }
        Map<String, Object> arguments = Json.obj(request, "arguments");
        calls.add(new Call(method, arguments, true, auth == null ? "" : auth));
        Function<Map<String, Object>, String> answer = answers.get(method);
        String json = answer == null ? "{\"arguments\":0,\"result\":\"no method name\"}" : answer.apply(arguments);
        reply(x, 200, json);
    }

    private static void reply(HttpExchange x, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.sendResponseHeaders(status, bytes.length);
        x.getResponseBody().write(bytes);
        x.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
