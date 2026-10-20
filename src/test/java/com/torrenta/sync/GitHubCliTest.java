package com.torrenta.sync;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GitHubCliTest {

    private static final String STATUS = """
            {"hosts":{"github.com":[
              {"login":"work","active":false,"state":"success"},
              {"login":"adriandeleon","active":true,"state":"success"}],
             "ghe.example.com":[{"login":"other","active":true,"state":"timeout"}]}}
            """;

    @Test
    void loginsActiveFirstPerHost() {
        assertEquals(List.of("adriandeleon", "work"), GitHubCli.logins(STATUS, "github.com"));
        assertEquals(List.of("adriandeleon", "work"), GitHubCli.logins(STATUS, "GitHub.com"));
        assertEquals(List.of("other"), GitHubCli.logins(STATUS, "ghe.example.com"));
        assertEquals(List.of(), GitHubCli.logins(STATUS, "gitlab.com"));
        assertEquals(List.of(), GitHubCli.logins("not json", "github.com"), "an older gh prints text");
        assertEquals(List.of(), GitHubCli.logins("", "github.com"));
    }

    @Test
    void remoteUrlFromWhatRepoCreatePrinted() {
        String out =
                "✓ Created repository adriandeleon/torrenta-settings on GitHub\n  https://github.com/adriandeleon/torrenta-settings\n";
        assertEquals("https://github.com/adriandeleon/torrenta-settings.git", GitHubCli.remoteUrl(out, "https"));
        assertEquals("git@github.com:adriandeleon/torrenta-settings.git", GitHubCli.remoteUrl(out, "ssh"));
        assertEquals("https://github.com/adriandeleon/torrenta-settings.git", GitHubCli.remoteUrl(out, ""));
        assertEquals("", GitHubCli.remoteUrl("nothing here", "ssh"));
        assertEquals("", GitHubCli.remoteUrl(null, "ssh"));
    }
}
