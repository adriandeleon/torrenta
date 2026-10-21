package com.torrenta.engine.transmission;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.torrenta.engine.EngineException;
import com.torrenta.engine.EngineProfile;

/**
 * Transmission's RPC: one JSON object per request, {@code POST}ed to {@code /transmission/rpc},
 * one JSON object back.
 *
 * <p>The daemon guards against cross-site requests with a session id: the first call answers
 * {@code 409 Conflict} with an {@code X-Transmission-Session-Id} header, which every later call
 * carries; the id changes when the daemon restarts, so a 409 at any time is taken as a new id and
 * the call is sent once more. Credentials go as HTTP basic auth. An answer whose {@code result}
 * is not {@code success} is an {@link EngineException} with the daemon's own text, which is
 * written for people ({@code "Couldn't fetch blocklist: Not Found (404)"}).
 *
 * <p>Synchronized on the session id only; calls run in parallel. Nothing here is on the FX
 * thread: every caller is a worker.
 */
final class RpcClient {

    private static final Logger LOG = Logger.getLogger(RpcClient.class.getName());
    static final String SESSION_HEADER = "X-Transmission-Session-Id";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final URI endpoint;
    private final String authorization;
    private final HttpClient http;
    private volatile String sessionId = "";

    RpcClient(EngineProfile profile) {
        this(profile, defaultClient());
    }

    RpcClient(EngineProfile profile, HttpClient http) {
        this(profile.rpcUri(), profile.username(), profile.password(), http);
    }

    RpcClient(URI endpoint, String username, String password, HttpClient http) {
        this.endpoint = endpoint;
        this.http = http;
        this.authorization = username == null || username.isEmpty()
                ? ""
                : "Basic "
                        + Base64.getEncoder()
                                .encodeToString((username + ":" + (password == null ? "" : password))
                                        .getBytes(StandardCharsets.UTF_8));
    }

    static HttpClient defaultClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    URI endpoint() {
        return endpoint;
    }

    /** What the daemon says about itself: version and RPC version, from {@code session-get}. */
    record Daemon(String version, int rpcVersion, int rpcVersionMinimum, Map<String, Object> session) {}

    /** {@code session-get}, read for the Test button and at start. */
    Daemon probe() throws EngineException {
        Map<String, Object> session = call("session-get", Map.of());
        return new Daemon(
                Json.str(session, "version", "?"),
                Json.integer(session, "rpc-version", 0),
                Json.integer(session, "rpc-version-minimum", 0),
                session);
    }

    /** One call; the {@code arguments} object of the answer, empty when the daemon sent none. */
    Map<String, Object> call(String method, Map<String, Object> arguments) throws EngineException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("method", method);
        if (arguments != null && !arguments.isEmpty()) body.put("arguments", arguments);
        String json = Json.write(body);
        for (int attempt = 0; ; attempt++) {
            HttpResponse<InputStream> response;
            try {
                response = http.send(request(json), HttpResponse.BodyHandlers.ofInputStream());
            } catch (ConnectException e) {
                throw new EngineException("Cannot reach the daemon at " + hostLine() + ": " + reason(e), e);
            } catch (HttpTimeoutException e) {
                throw new EngineException("The daemon at " + hostLine() + " did not answer in time", e);
            } catch (IOException e) {
                throw new EngineException("Cannot reach the daemon at " + hostLine() + ": " + reason(e), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new EngineException("Interrupted while calling the daemon", e);
            }
            int status = response.statusCode();
            if (status == 409) {
                String fresh = response.headers().firstValue(SESSION_HEADER).orElse("");
                drain(response);
                if (fresh.isEmpty() || attempt > 0) {
                    throw new EngineException("The daemon at " + hostLine() + " keeps refusing the session id");
                }
                sessionId = fresh;
                continue;
            }
            if (status == 401 || status == 403) {
                drain(response);
                throw new EngineException(
                        authorization.isEmpty()
                                ? "The daemon at " + hostLine() + " wants a username and password"
                                : "The daemon at " + hostLine() + " refused the username or password");
            }
            if (status / 100 != 2) {
                drain(response);
                throw new EngineException("The daemon at " + hostLine() + " answered HTTP " + status);
            }
            Map<String, Object> answer;
            try (InputStream in = response.body()) {
                answer = Json.obj(Json.parse(in));
            } catch (IOException e) {
                throw new EngineException("The daemon's answer to " + method + " was not JSON: " + e.getMessage(), e);
            }
            String result = Json.str(answer, "result", "");
            if (!result.equals("success")) {
                throw new EngineException(result.isEmpty() ? "The daemon gave no result for " + method : result);
            }
            return Json.obj(answer, "arguments");
        }
    }

    private HttpRequest request(String json) {
        HttpRequest.Builder b = HttpRequest.newBuilder(endpoint)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        String id = sessionId;
        if (!id.isEmpty()) b.header(SESSION_HEADER, id);
        if (!authorization.isEmpty()) b.header("Authorization", authorization);
        return b.build();
    }

    private static void drain(HttpResponse<InputStream> response) {
        try (InputStream in = response.body()) {
            in.readAllBytes();
        } catch (IOException e) {
            LOG.log(Level.FINE, "Could not drain an answer", e);
        }
    }

    private String hostLine() {
        return endpoint.getPort() < 0 ? endpoint.getHost() : endpoint.getHost() + ":" + endpoint.getPort();
    }

    private static String reason(IOException e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
