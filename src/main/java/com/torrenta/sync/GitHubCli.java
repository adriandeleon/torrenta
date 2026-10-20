package com.torrenta.sync;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

/** The two things settings sync asks the GitHub CLI for. Blocking; the caller picks the thread. */
public final class GitHubCli {

    public static final List<String> COMMAND = List.of("gh");
    private static final Duration QUICK = Duration.ofSeconds(15);
    private static final Duration NETWORK = Duration.ofMinutes(1);
    private static final JsonFactory JSON = new JsonFactory();

    /** The outcome of creating a repository: the URL git should use, or gh's complaint. */
    public record Created(boolean ok, String url, String error) {}

    private GitHubCli() {}

    /**
     * The logins gh has on {@code host}, the active one first ({@code gh auth status --json hosts},
     * gh 2.81+). Empty when gh is missing, too old to say, or has no account there.
     */
    public static List<String> logins(List<String> command, String host) {
        List<String> argv = new ArrayList<>(command);
        argv.addAll(List.of("auth", "status", "--json", "hosts"));
        Proc.Result json = Proc.run(null, QUICK, argv, Map.of("LC_ALL", "C", "GH_PROMPT_DISABLED", "1"));
        return json.timedOut() ? List.of() : logins(json.out(), host);
    }

    /** Parses the JSON of {@code gh auth status --json hosts} for {@code host}'s logins, active first. Pure. */
    static List<String> logins(String json, String host) {
        if (json == null || json.isBlank() || host == null) return List.of();
        String wanted = host.strip().toLowerCase(Locale.ROOT);
        List<String> active = new ArrayList<>();
        List<String> others = new ArrayList<>();
        try (JsonParser p = JSON.createParser(json)) {
            if (p.nextToken() != JsonToken.START_OBJECT) return List.of();
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String field = p.currentName();
                p.nextToken();
                if (!field.equals("hosts") || p.currentToken() != JsonToken.START_OBJECT) {
                    p.skipChildren();
                    continue;
                }
                while (p.nextToken() == JsonToken.FIELD_NAME) {
                    String h = p.currentName().strip().toLowerCase(Locale.ROOT);
                    p.nextToken();
                    if (!h.equals(wanted) || p.currentToken() != JsonToken.START_ARRAY) {
                        p.skipChildren();
                        continue;
                    }
                    while (p.nextToken() == JsonToken.START_OBJECT) {
                        String login = "";
                        boolean isActive = false;
                        while (p.nextToken() == JsonToken.FIELD_NAME) {
                            String name = p.currentName();
                            p.nextToken();
                            if (name.equals("login"))
                                login = p.getValueAsString("").strip();
                            else if (name.equals("active")) isActive = p.getValueAsBoolean(false);
                            else p.skipChildren();
                        }
                        if (!login.isEmpty()) (isActive ? active : others).add(login);
                    }
                }
            }
        } catch (IOException | RuntimeException malformed) {
            return List.of();
        }
        active.addAll(others);
        return List.copyOf(active);
    }

    /**
     * Creates a private repository called {@code name} under the signed-in account and answers with
     * the URL git should use for it: the SSH form when gh is set to the ssh protocol, HTTPS otherwise.
     */
    public static Created createPrivateRepo(List<String> command, String name, String description) {
        if (name == null || !name.matches("[A-Za-z0-9._-]+")) return new Created(false, "", "not a repository name");
        List<String> argv = new ArrayList<>(command);
        argv.addAll(List.of("repo", "create", name, "--private", "--description", description));
        Proc.Result created = Proc.run(null, NETWORK, argv, Map.of("LC_ALL", "C", "GH_PROMPT_DISABLED", "1"));
        if (!created.ok()) return new Created(false, "", created.transcript());
        List<String> protocol = new ArrayList<>(command);
        protocol.addAll(List.of("config", "get", "git_protocol"));
        Proc.Result p = Proc.run(null, QUICK, protocol, Map.of("LC_ALL", "C", "GH_PROMPT_DISABLED", "1"));
        String url = remoteUrl(created.out(), p.ok() ? p.out() : "");
        return url.isEmpty() ? new Created(false, "", created.transcript()) : new Created(true, url, "");
    }

    private static final Pattern REPO_WEB_URL =
            Pattern.compile("https://([A-Za-z0-9.-]+)/([A-Za-z0-9._-]+)/([A-Za-z0-9._-]+?)(?:\\.git)?/?\\s*$");

    /**
     * The git remote for the repository whose web address {@code gh repo create} printed (the last
     * line of {@code output}): {@code git@host:owner/name.git} for the ssh protocol, else
     * {@code https://host/owner/name.git}. Blank when no address is found. Pure.
     */
    static String remoteUrl(String output, String protocol) {
        if (output == null) return "";
        String found = "";
        for (String line : output.strip().split("\\R")) {
            Matcher m = REPO_WEB_URL.matcher(line.strip());
            if (m.find()) found = line.strip();
        }
        Matcher m = REPO_WEB_URL.matcher(found);
        if (!m.find()) return "";
        String host = m.group(1);
        String owner = m.group(2);
        String name = m.group(3);
        return "ssh".equalsIgnoreCase(protocol == null ? "" : protocol.strip())
                ? "git@" + host + ":" + owner + "/" + name + ".git"
                : "https://" + host + "/" + owner + "/" + name + ".git";
    }
}
