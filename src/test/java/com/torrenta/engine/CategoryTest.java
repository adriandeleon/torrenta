package com.torrenta.engine;

import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CategoryTest {

    @Test
    void nameIsRequiredAndTrimmed() {
        assertEquals("Movies", new Category("  Movies ", null).name());
        assertEquals("", new Category("Movies").savePath());
        assertThrows(IllegalArgumentException.class, () -> new Category("  ", "/x"));
        assertThrows(IllegalArgumentException.class, () -> new Category(null));
    }

    @Test
    void pathIsOptionalAndAbsolute() {
        assertEquals(Optional.empty(), new Category("a", " ").path());
        assertEquals(Optional.of(Path.of("/tmp/x").toAbsolutePath()), new Category("a", "/tmp/x").path());
    }
}
