package com.torrenta.update;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.torrenta.AppInfo;

/**
 * Asks GitHub whether there is a newer release.
 *
 * <p>Off the FX thread, with the answer delivered back on it. Contacts one HTTPS endpoint and sends
 * nothing about the user or the machine beyond what any HTTP request carries.
 */
public final class UpdateService {

    /** What came back. {@code latest} is null unless there is something newer. */
    public record Outcome(boolean available, ReleaseInfo latest, String error) {
        public static Outcome none() {
            return new Outcome(false, null, null);
        }

        public static Outcome found(ReleaseInfo release) {
            return new Outcome(true, release, null);
        }

        public static Outcome failed(String error) {
            return new Outcome(false, null, error);
        }
    }

    /** The response is a few kilobytes; the cap is there so a wrong URL cannot stream forever. */
    private static final int MAX_BYTES = 1024 * 1024;

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "torrenta-update-check");
        thread.setDaemon(true);
        return thread;
    });

    private final String endpoint;

    /** The endpoint, or the one named by {@code -Dtorrenta.updateEndpoint} for a capture run. */
    public UpdateService() {
        this(System.getProperty("torrenta.updateEndpoint", AppInfo.LATEST_RELEASE_API));
    }

    UpdateService(String endpoint) {
        this.endpoint = endpoint;
    }

    /** Checks in the background and calls back on the FX thread. */
    public void check(String currentVersion, Consumer<Outcome> onResult) {
        executor.execute(() -> {
            Outcome outcome = checkNow(currentVersion);
            Platform.runLater(() -> onResult.accept(outcome));
        });
    }

    /** The check itself, on the calling thread; package-private for the test. */
    Outcome checkNow(String currentVersion) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(TIMEOUT)
                    .header("Accept", "application/vnd.github+json")
                    // GitHub rejects a request with no User-Agent outright.
                    .header("User-Agent", AppInfo.NAME + "/" + AppInfo.VERSION)
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                // No releases published yet, or the repository is private. Not an error worth
                // showing: there is simply nothing newer.
                return Outcome.none();
            }
            if (response.statusCode() != 200) {
                return Outcome.failed("HTTP " + response.statusCode());
            }
            String body = response.body();
            if (body != null && body.length() > MAX_BYTES) body = body.substring(0, MAX_BYTES);

            ReleaseInfo latest = UpdateCheck.parseLatest(body);
            if (latest == null) return Outcome.none();
            return UpdateCheck.isNewer(currentVersion, latest.version()) ? Outcome.found(latest) : Outcome.none();
        } catch (Exception e) {
            // Offline, DNS failure, timeout, TLS problem. A background check that fails is not the
            // user's problem; only a check they asked for reports anything.
            return Outcome.failed(e.getClass().getSimpleName());
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
