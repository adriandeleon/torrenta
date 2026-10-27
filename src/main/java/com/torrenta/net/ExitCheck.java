package com.torrenta.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.torrenta.net.LeakCheck.Exit;

import static com.torrenta.i18n.Messages.tr;

/**
 * Asks a server of the person's choosing which address a request reached it from: once from the
 * bound address ({@link HttpClient.Builder#localAddress}), once by the default route. Opt-in, since
 * it tells a third party this machine's addresses; the URL is the person's, an address-echo service
 * such as {@link #DEFAULT_URL} that answers with the address alone. Blocking; never on the FX
 * thread. No proxy: the point is where this machine's own traffic leaves.
 */
public final class ExitCheck {

    private ExitCheck() {}

    /** Offered in the settings: plain text, the address and nothing else. */
    public static final String DEFAULT_URL = "https://api.ipify.org";

    /** How long one request may take. */
    static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** How often it is asked again while nothing changes. */
    public static final long INTERVAL_SECONDS = 10 * 60;

    /** An answer longer than this is not an address. */
    static final int MAX_BODY = 100;

    /** Both exits, and when they were asked (epoch seconds). */
    public record Exits(Exit bound, Exit around, long at) {
        public static final Exits NONE = new Exits(Exit.NOT_ASKED, Exit.NOT_ASKED, 0);
    }

    /** Asks for both: through the bound address, then by the default route. */
    public static Exits ask(String url, Optional<InetAddress> bound, long nowSeconds) {
        Exit through = bound.map(local -> ask(url, Optional.of(local)))
                .orElseGet(() -> Exit.failed(tr("leak.exit.noAddress")));
        return new Exits(through, ask(url, Optional.empty()), nowSeconds);
    }

    /** One request: from {@code local} when given, else wherever the system sends it. */
    static Exit ask(String url, Optional<InetAddress> local) {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return Exit.failed(tr("leak.exit.badUrl", url));
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))
            return Exit.failed(tr("leak.exit.badUrl", url));
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(HttpClient.Builder.NO_PROXY);
        local.ifPresent(builder::localAddress);
        try (HttpClient client = builder.build()) {
            HttpResponse<byte[]> response = client.send(
                    HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            return answer(response.statusCode(), response.body());
        } catch (IOException e) {
            return Exit.failed(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Exit.failed(e.getClass().getSimpleName());
        }
    }

    /** The server's answer: a 200 whose body is an address literal and nothing else. */
    static Exit answer(int status, byte[] body) {
        if (status != 200) return Exit.failed(tr("leak.exit.status", status));
        if (body == null || body.length > MAX_BODY) return Exit.failed(tr("leak.exit.notAnAddress"));
        String text = new String(body, StandardCharsets.UTF_8).trim();
        Optional<InetAddress> address = InterfaceWatch.literal(text);
        if (address.isEmpty()) return Exit.failed(tr("leak.exit.notAnAddress"));
        return Exit.seen(InterfaceWatch.compact(address.get().getHostAddress()));
    }

    /**
     * The address requests through the binding leave from: the binding itself when it is an
     * address, else the interface's first IPv4 address that is not link-local (an echo service is
     * often IPv4 only), else its first address of any kind.
     */
    public static Optional<InetAddress> localAddress(InterfaceWatch.State bound) {
        if (bound.presence() != InterfaceWatch.Presence.UP) return Optional.empty();
        Optional<InetAddress> literal = InterfaceWatch.literal(bound.bound());
        if (literal.isPresent()) return literal;
        List<InetAddress> addresses = bound.addresses().stream()
                .map(InterfaceWatch::literal)
                .flatMap(Optional::stream)
                .toList();
        return addresses.stream()
                .filter(a -> a.getAddress().length == 4 && !a.isLinkLocalAddress())
                .findFirst()
                .or(() -> addresses.stream().findFirst());
    }

    /** Asked again after {@link #INTERVAL_SECONDS}, or at once when something it depends on moved. */
    public static boolean due(Exits last, long nowSeconds, boolean somethingMoved) {
        return somethingMoved || last.at() == 0 || nowSeconds - last.at() >= INTERVAL_SECONDS;
    }
}
