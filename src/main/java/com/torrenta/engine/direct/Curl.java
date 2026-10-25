package com.torrenta.engine.direct;

import java.nio.file.Path;
import java.util.List;

import static com.torrenta.i18n.Messages.tr;

/**
 * How curl is run for one download, and what its exit means. Pure.
 *
 * <p>Everything about the transfer goes to curl as a config file on its standard input ({@code
 * --config -}), never on the command line: the link may carry a token and the proxy a password,
 * and a process's arguments are anyone's to read. {@code --disable} comes first so a person's own
 * {@code ~/.curlrc} cannot change what the app asked for.
 */
public final class Curl {

    /** Protocols a download may use, and may be redirected to: never {@code file:} or the rest. */
    static final String PROTOCOLS = "=http,https,ftp,ftps,sftp";

    static final String REDIRECT_PROTOCOLS = "=http,https,ftp,ftps";

    /** curl's exit codes the outcome turns on. */
    static final int UNSUPPORTED_PROTOCOL = 1,
            COULD_NOT_RESOLVE = 6,
            COULD_NOT_CONNECT = 7,
            REMOTE_ACCESS_DENIED = 9,
            HTTP_ERROR = 22,
            WRITE_ERROR = 23,
            TIMED_OUT = 28,
            RANGE_ERROR = 33,
            BAD_RESUME = 36,
            TOO_MANY_REDIRECTS = 47,
            LOGIN_DENIED = 67,
            REMOTE_FILE_NOT_FOUND = 78;

    private static final java.util.Set<Integer> TLS =
            java.util.Set.of(35, 51, 53, 54, 58, 59, 60, 64, 66, 77, 80, 83, 90, 91);

    private Curl() {}

    /**
     * One transfer.
     *
     * @param output the {@code .part} file; appended to when {@code resume}
     * @param headers where the answers' headers are dumped as they arrive
     * @param resume carry on from the end of {@code output}
     * @param rateLimit bytes per second, 0 for none
     */
    public record Request(
            String url, Path output, Path headers, boolean resume, long rateLimit, Proxy proxy, String userAgent) {}

    /**
     * A proxy, as curl names it: {@code socks5h} (names resolved through the proxy, as the
     * engine's {@code proxy_hostnames} does) or {@code http}.
     */
    public record Proxy(String scheme, String host, int port, String user, String password) {

        public static final Proxy NONE = new Proxy("", "", 0, "", "");

        public boolean isNone() {
            return scheme.isEmpty() || host.isBlank();
        }
    }

    /** The command line: the program and nothing a person could not see harmlessly. */
    public static List<String> command(String program) {
        String curl = program == null || program.isBlank() ? "curl" : program.strip();
        return List.of(curl, "--disable", "--config", "-");
    }

    /** The config file for {@code request}, one option a line. */
    public static String config(Request r) {
        StringBuilder sb = new StringBuilder();
        option(sb, "url", r.url());
        option(sb, "output", r.output().toString());
        option(sb, "dump-header", r.headers().toString());
        flag(sb, "location");
        flag(sb, "fail");
        flag(sb, "globoff");
        flag(sb, "silent");
        flag(sb, "show-error");
        flag(sb, "remote-time");
        option(sb, "proto", PROTOCOLS);
        option(sb, "proto-redir", REDIRECT_PROTOCOLS);
        option(sb, "max-redirs", "20");
        option(sb, "retry", "5");
        option(sb, "retry-delay", "5");
        flag(sb, "retry-connrefused");
        option(sb, "connect-timeout", "30");
        // Nothing at all for two minutes ends the transfer (exit 28); --retry then tries again.
        option(sb, "speed-limit", "1");
        option(sb, "speed-time", "120");
        if (r.resume()) option(sb, "continue-at", "-");
        if (r.rateLimit() > 0) option(sb, "limit-rate", String.valueOf(r.rateLimit()));
        if (r.userAgent() != null && !r.userAgent().isBlank()) option(sb, "user-agent", r.userAgent());
        Proxy p = r.proxy() == null ? Proxy.NONE : r.proxy();
        if (!p.isNone()) {
            option(sb, "proxy", p.scheme() + "://" + bracketed(p.host()) + ":" + p.port());
            if (!p.user().isEmpty()) option(sb, "proxy-user", p.user() + ":" + p.password());
        }
        return sb.toString();
    }

    // ---- by hand -----------------------------------------------------------------------------------

    /**
     * The shell a command line is written for: POSIX's single quotes, or Windows' double quotes
     * with {@code curl.exe}, since PowerShell's own {@code curl} is another program.
     */
    public enum Shell {
        POSIX,
        WINDOWS;

        public static Shell of(String osName) {
            return osName != null && osName.toLowerCase(java.util.Locale.ROOT).startsWith("windows") ? WINDOWS : POSIX;
        }

        public static Shell current() {
            return of(System.getProperty("os.name", ""));
        }

        /** Characters this shell does nothing with outside quotes; a comma splits words in cmd. */
        String safe() {
            return this == WINDOWS ? "-_./:\\" : "-_./:@+,";
        }
    }

    /**
     * A command line a person can paste to fetch the same file by hand: the link, the options a
     * download runs with, the file it ends as. Not what runs (that takes its options on stdin, and
     * its progress, headers and resume are the app's): a person sees their own link here, and the
     * proxy's password is left out, so curl asks for it.
     */
    public static String shellCommand(
            String program, String url, Path output, String userAgent, Proxy proxy, Shell shell) {
        List<String> args = new java.util.ArrayList<>();
        args.add(
                program == null || program.isBlank()
                        ? (shell == Shell.WINDOWS ? "curl.exe" : "curl")
                        : program.strip());
        args.addAll(List.of("--location", "--fail", "--globoff", "--remote-time"));
        args.addAll(List.of("--proto", PROTOCOLS, "--proto-redir", REDIRECT_PROTOCOLS));
        args.addAll(List.of("--retry", "5", "--retry-connrefused"));
        if (userAgent != null && !userAgent.isBlank()) args.addAll(List.of("--user-agent", userAgent));
        Proxy p = proxy == null ? Proxy.NONE : proxy;
        if (!p.isNone()) {
            args.addAll(List.of("--proxy", p.scheme() + "://" + bracketed(p.host()) + ":" + p.port()));
            if (!p.user().isEmpty()) args.addAll(List.of("--proxy-user", p.user()));
        }
        args.addAll(List.of("--output", output.toString(), url));
        return String.join(" ", args.stream().map(a -> shellQuote(a, shell)).toList());
    }

    /** One argument as {@code shell} reads it, quoted only when it has to be. */
    static String shellQuote(String arg, Shell shell) {
        String safe = shell.safe();
        if (!arg.isEmpty()
                && arg.chars().allMatch(c -> safe.indexOf(c) >= 0 || (c < 128 && Character.isLetterOrDigit(c)))) {
            return arg;
        }
        return shell == Shell.WINDOWS ? "\"" + arg.replace("\"", "\\\"") + "\"" : "'" + arg.replace("'", "'\\''") + "'";
    }

    /** A value in curl's config syntax: in double quotes, with backslash escapes. */
    static String quote(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static String bracketed(String host) {
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }

    private static void option(StringBuilder sb, String name, String value) {
        sb.append(name).append(" = ").append(quote(value)).append('\n');
    }

    private static void flag(StringBuilder sb, String name) {
        sb.append(name).append('\n');
    }

    // ---- the outcome ------------------------------------------------------------------------------

    /** What to do once curl has exited by itself. */
    public sealed interface Outcome {
        record Finished() implements Outcome {}

        /** The server would not resume; start the file again from nothing. */
        record Restart() implements Outcome {}

        record Failed(String message) implements Outcome {}
    }

    /**
     * @param partSize the {@code .part} file's size now
     * @param restarted this run already started from nothing once, so a refusal to resume is
     *     not tried a second time
     * @param stderr what curl wrote to its error stream
     */
    public static Outcome outcome(
            int exit, CurlHeaders headers, long partSize, boolean restarted, String host, String stderr) {
        if (exit == 0) return new Outcome.Finished();
        boolean refusedRange =
                exit == RANGE_ERROR || exit == BAD_RESUME || (exit == HTTP_ERROR && headers.status() == 416);
        if (refusedRange) {
            // 416 to a resume that asked from the end of a complete file: it is all there.
            if (headers.status() == 416 && headers.total() >= 0 && partSize == headers.total()) {
                return new Outcome.Finished();
            }
            if (!restarted) return new Outcome.Restart();
        }
        return new Outcome.Failed(message(exit, headers.status(), host, stderr));
    }

    /** The reason in the interface language; curl's own words only where nothing better fits. */
    static String message(int exit, int status, String host, String stderr) {
        String said = said(stderr);
        return switch (exit) {
            case UNSUPPORTED_PROTOCOL -> tr("direct.error.protocol");
            case COULD_NOT_RESOLVE -> tr("direct.error.host", host);
            case COULD_NOT_CONNECT -> tr("direct.error.connect", host);
            case REMOTE_ACCESS_DENIED, REMOTE_FILE_NOT_FOUND -> tr("direct.error.notThere");
            case HTTP_ERROR ->
                status > 0 ? tr("direct.error.http", String.valueOf(status)) : tr("direct.error.curl", exit, said);
            case WRITE_ERROR -> tr("direct.error.write");
            case TIMED_OUT -> tr("direct.error.stalled");
            case TOO_MANY_REDIRECTS -> tr("direct.error.redirects");
            case LOGIN_DENIED -> tr("direct.error.login");
            default -> TLS.contains(exit) ? tr("direct.error.tls", said) : tr("direct.error.curl", exit, said);
        };
    }

    /**
     * curl's own words: the text of the last {@code curl: (22) The requested URL returned
     * error: 404} line, which a hint may follow, else the last line.
     */
    static String said(String stderr) {
        if (stderr == null) return "";
        String last = "";
        String error = "";
        for (String line : stderr.split("\r?\n")) {
            String s = line.strip();
            if (s.isEmpty()) continue;
            last = s;
            int close = s.indexOf(')');
            if (s.startsWith("curl: (") && close > 0)
                error = s.substring(close + 1).strip();
        }
        return error.isEmpty() ? last : error;
    }
}
