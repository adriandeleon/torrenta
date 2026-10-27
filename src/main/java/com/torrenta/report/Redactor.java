package com.torrenta.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strips what a problem report must not carry off the machine. Editora's, with what a torrent
 * client adds.
 *
 * <p>Editora's rules: the home folder and user name (they name the person), e-mail addresses,
 * credentials in URLs, authentication headers, and anything shaped like an API key or token.
 * Torrenta's: a private tracker's passkey (in an announce URL's path or its query, and in an RSS
 * feed's link), magnet links and info-hashes (they say what is being shared), the names of the
 * torrents in the list (which also sit in every download path), and public IPv4 addresses (peers
 * are other people; a loopback, private or unspecified address says how the network is set up and
 * stays). IPv6 addresses are left as they are.
 *
 * <p>Best-effort by nature — a secret with no recognisable shape gets through — which is why the
 * report is always shown, editable, before it leaves Torrenta. Pure; {@link #redact} counts what it
 * replaced so the preview can say so.
 */
public final class Redactor {

    /** Replacement text for a value that was removed. */
    static final String REDACTED = "<redacted>";

    /** A user name shorter than this is too likely to be an ordinary word or log fragment to replace. */
    private static final int MIN_USER_NAME = 3;

    /** A torrent name shorter than this ("a", "tv") would eat ordinary words of the log. */
    static final int MIN_TORRENT_NAME = 4;

    /** Each rule rewrites its matches to {@code replacement} ({@code $1}…: groups kept from the match). */
    private record Rule(Pattern pattern, String replacement) {}

    private static final List<Rule> RULES = List.of(
            // A private key block, start to end.
            new Rule(
                    Pattern.compile(
                            "-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*?-----END [A-Z0-9 ]*PRIVATE KEY-----",
                            Pattern.DOTALL),
                    "<redacted private key>"),
            // A magnet link names the content (its hash, its dn=) and may carry a private tracker's
            // passkey in its tr=: all of it goes, the scheme stays so the line still reads.
            new Rule(Pattern.compile("(?i)\\bmagnet:\\?[^\\s\"'<>]+"), "magnet:" + REDACTED),
            // user:password@ in a URL (a Transmission RPC URL, a proxy, sftp): keep the scheme, drop the credentials.
            new Rule(Pattern.compile("([a-zA-Z][a-zA-Z0-9+.-]*://)[^\\s/@:]+:[^\\s/@]*@"), "$1" + REDACTED + "@"),
            // A passkey in a tracker's path: /<32 hex>/announce, and /announce/<key>.
            new Rule(Pattern.compile("(?i)(?<=/)[0-9a-f]{24,}(?=/(?:announce|scrape)\\b)"), REDACTED),
            new Rule(Pattern.compile("(?i)(/(?:announce|scrape)(?:\\.php)?/)[A-Za-z0-9]{16,}"), "$1" + REDACTED),
            // An authentication header, to the end of the line. A log line has a timestamp in front,
            // so the header name is matched anywhere, not only at the start of a line.
            new Rule(
                    Pattern.compile(
                            "(?im)\\b((?:proxy-)?(?:authorization|cookie|set-cookie|x-api-key|x-auth-token|api-key)"
                                    + "\\s*:\\s*)(?!<)\\S.*$"),
                    "$1" + REDACTED),
            new Rule(Pattern.compile("(?i)\\b(bearer)\\s+[A-Za-z0-9._~+/=-]{8,}"), "$1 " + REDACTED),
            // key=value with a secret-sounding key: a query string (a tracker's ?passkey=, a feed's
            // &rsskey=), a property line, a JSON-ish pair. A bare "key" is not one.
            new Rule(
                    Pattern.compile(
                            "(?i)\\b((?:access_?|refresh_?|id_?|auth_?|client_?)?(?:token|secret|password|passwd"
                                    + "|pwd|apikey|api_key|private_key|passkey|authkey|torrent_pass|rsskey)\"?\\s*[=:]"
                                    + "\\s*\"?)(?!<)[^\\s\"&,;}]{4,}"),
                    "$1" + REDACTED),
            // Token shapes: GitHub, OpenAI/Anthropic-style sk-, Slack, AWS access key, a JWT.
            new Rule(Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})"), REDACTED),
            new Rule(Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,}"), REDACTED),
            new Rule(Pattern.compile("\\bxox[abposr]-[A-Za-z0-9-]{10,}"), REDACTED),
            new Rule(Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"), REDACTED),
            new Rule(Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"), REDACTED),
            // An info-hash: v1 (40 hex), v2 (64 hex). A torrent's id is one, and so is a resume file's name.
            new Rule(Pattern.compile("(?i)(?<![0-9a-f])(?:[0-9a-f]{64}|[0-9a-f]{40})(?![0-9a-f])"), "<hash>"),
            // E-mail addresses. git@github.com:owner/repo is a remote, not a person: kept.
            new Rule(
                    Pattern.compile(
                            "(?<![A-Za-z0-9._%+-])(?!git@)[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}"),
                    "<email>"));

    /** Four dotted numbers that are not part of a longer dotted run (a version such as 1.2.3.4.5). */
    private static final Pattern IPV4 =
            Pattern.compile("(?<![0-9.])(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?![0-9]|\\.[0-9])");

    /** The redacted text and how many values were replaced. */
    public record Result(String text, int replaced) {}

    private final String home;
    private final String userName;
    private final List<String> torrentNames;

    /**
     * @param home the user's home folder ({@code user.home}); its paths become {@code ~}
     * @param userName the account name ({@code user.name}); other mentions of it become {@code <user>}
     * @param torrentNames the names in the list; each becomes {@code <torrent N>}, numbered in this
     *     order, so two lines about the same torrent still read as the same one
     */
    public Redactor(String home, String userName, List<String> torrentNames) {
        this.home = home == null || home.isBlank() ? null : stripTrailingSeparator(home.strip());
        this.userName = userName == null || userName.strip().length() < MIN_USER_NAME ? null : userName.strip();
        Set<String> names = new LinkedHashSet<>();
        for (String name : torrentNames) {
            if (name != null && name.strip().length() >= MIN_TORRENT_NAME) names.add(name.strip());
        }
        this.torrentNames = List.copyOf(names);
    }

    /** A redactor for this machine's home folder and user, and these torrents. */
    public static Redactor forThisMachine(List<String> torrentNames) {
        return new Redactor(System.getProperty("user.home"), System.getProperty("user.name"), torrentNames);
    }

    public Result redact(String text) {
        if (text == null || text.isEmpty()) return new Result("", 0);
        int[] count = {0};
        String out = text;
        // Names first: a name can hold a hash or a dotted number that a later rule would cut in half.
        // Longest first, so a name that contains another is replaced whole.
        List<String> byLength = new ArrayList<>(torrentNames);
        byLength.sort(Comparator.comparingInt(String::length).reversed());
        for (String name : byLength) {
            String label = "<torrent " + (torrentNames.indexOf(name) + 1) + ">";
            out = replace(out, Pattern.compile(Pattern.quote(name)), Matcher.quoteReplacement(label), count);
        }
        for (Rule rule : RULES) out = replace(out, rule.pattern(), rule.replacement(), count);
        out = replace(out, IPV4, m -> isPublic(m) ? "<ip>" : null, count);
        if (home != null) {
            // Both separators: a Windows home appears as C:\Users\me in paths and C:/Users/me in URIs.
            out = replaceLiteral(out, home, "~", count);
            if (home.indexOf('\\') >= 0) out = replaceLiteral(out, home.replace('\\', '/'), "~", count);
        }
        if (userName != null) {
            out = replace(
                    out,
                    Pattern.compile("(?<![A-Za-z0-9_.-])" + Pattern.quote(userName) + "(?![A-Za-z0-9_-])"),
                    "<user>",
                    count);
        }
        return new Result(out, count[0]);
    }

    /**
     * Whether a dotted quad is an address on the internet. Loopback, private (RFC 1918), link-local,
     * carrier-grade NAT, unspecified and multicast and above are kept: they describe this setup, not
     * a person. Not an address at all (a part above 255) is kept too.
     */
    static boolean isPublic(Matcher quad) {
        int[] p = new int[4];
        for (int i = 0; i < 4; i++) {
            p[i] = Integer.parseInt(quad.group(i + 1));
            if (p[i] > 255) return false;
        }
        if (p[0] == 0 || p[0] == 10 || p[0] == 127 || p[0] >= 224) return false;
        if (p[0] == 169 && p[1] == 254) return false;
        if (p[0] == 172 && p[1] >= 16 && p[1] <= 31) return false;
        if (p[0] == 192 && p[1] == 168) return false;
        return !(p[0] == 100 && p[1] >= 64 && p[1] <= 127);
    }

    private static String replace(String text, Pattern pattern, String replacement, int[] count) {
        return replace(text, pattern, m -> replacement, count);
    }

    /** {@code replacement} gives the text for a match (with {@code $n} groups), or null to keep it. */
    private static String replace(String text, Pattern pattern, Function<Matcher, String> replacement, int[] count) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) return text;
        StringBuilder sb = new StringBuilder(text.length());
        do {
            String with = replacement.apply(m);
            if (with == null) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            } else {
                count[0]++;
                m.appendReplacement(sb, with);
            }
        } while (m.find());
        m.appendTail(sb);
        return sb.toString();
    }

    /** Replaces {@code literal} wherever a path separator or the end of a word follows it ({@code /home/me2} stays). */
    private static String replaceLiteral(String text, String literal, String replacement, int[] count) {
        return replace(text, Pattern.compile(Pattern.quote(literal) + "(?![A-Za-z0-9_.-])"), replacement, count);
    }

    private static String stripTrailingSeparator(String path) {
        return path.length() > 1 && (path.endsWith("/") || path.endsWith("\\"))
                ? path.substring(0, path.length() - 1)
                : path;
    }
}
