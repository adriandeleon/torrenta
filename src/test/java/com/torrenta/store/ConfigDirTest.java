package com.torrenta.store;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigDirTest {

    @Test
    void defaultIsADotFolderInHome() {
        assertEquals(Path.of("/home/x", ".torrenta"), ConfigDir.resolve(null, Map.of(), "/home/x"));
        assertEquals(Path.of("/home/x", ".torrenta"), ConfigDir.resolve("  ", Map.of(), "/home/x"));
    }

    @Test
    void environmentBeatsTheDefaultAndTheCommandLineBeatsBoth() {
        Map<String, String> env = Map.of(ConfigDir.ENV, "/tmp/from-env");
        assertEquals(Path.of("/tmp/from-env").toAbsolutePath(), ConfigDir.resolve(null, env, "/home/x"));
        assertEquals(Path.of("/tmp/from-cli").toAbsolutePath(), ConfigDir.resolve("/tmp/from-cli", env, "/home/x"));
    }
}
