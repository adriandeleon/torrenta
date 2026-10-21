package com.torrenta.search;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonExtractorTest {

    private static final String DOC =
            "{\"response\":{\"numFound\":2,\"docs\":[{\"title\":\"a\",\"size\":1314439,\"ok\":true},"
                    + "{\"title\":\"b\",\"size\":1.5e3,\"tags\":[\"x\",\"y\"],\"none\":null}],\"a/b\":\"slash\",\"t~e\":\"tilde\"}}";

    @Test
    void pathsWalkObjectsAndLists() throws IOException {
        Object root = JsonExtractor.read(DOC);
        assertEquals("a", JsonExtractor.text(root, "/response/docs/0/title"));
        assertEquals("b", JsonExtractor.text(root, "response/docs/1/title"), "the leading slash is optional");
        assertEquals("1314439", JsonExtractor.text(root, "/response/docs/0/size"), "an integer plainly");
        assertEquals("1500", JsonExtractor.text(root, "/response/docs/1/size"), "a whole float plainly");
        assertEquals("true", JsonExtractor.text(root, "/response/docs/0/ok"));
        assertEquals("y", JsonExtractor.text(root, "/response/docs/1/tags/1"));
        assertEquals("2", JsonExtractor.text(root, "/response/numFound"));
        assertEquals("slash", JsonExtractor.text(root, "/response/a~1b"), "~1 is a slash in a key");
        assertEquals("tilde", JsonExtractor.text(root, "/response/t~0e"), "~0 is a tilde");
        assertSame(root, JsonExtractor.at(root, ""), "the empty path is the root");
        assertSame(root, JsonExtractor.at(root, "/"));
    }

    @Test
    void missingIsNullAndSoAreContainersAndNulls() throws IOException {
        Object root = JsonExtractor.read(DOC);
        assertNull(JsonExtractor.text(root, "/response/docs/0/nothing"));
        assertNull(JsonExtractor.text(root, "/response/docs/9/title"), "index past the end");
        assertNull(JsonExtractor.text(root, "/response/docs/x/title"), "a word where an index goes");
        assertNull(JsonExtractor.text(root, "/response/docs/0/title/deeper"), "a step into a string");
        assertNull(JsonExtractor.text(root, "/response/docs"), "a list has no text");
        assertNull(JsonExtractor.text(root, "/response/docs/1/none"), "a JSON null has no text");
        assertSame(JsonExtractor.NULL, JsonExtractor.at(root, "/response/docs/1/none"), "but it is there");
        assertNull(JsonExtractor.at(root, "/response/docs/1/absent"), "unlike what is not");
    }

    @Test
    void rowsAreAListsElementsOrAnObjectsValues() throws IOException {
        Object root = JsonExtractor.read(DOC);
        List<Object> rows = JsonExtractor.rows(root, "/response/docs");
        assertEquals(2, rows.size());
        assertEquals("a", JsonExtractor.text(rows.get(0), "title"));
        List<Object> byId = JsonExtractor.rows(
                JsonExtractor.read("{\"items\":{\"1\":{\"n\":\"x\"},\"2\":{\"n\":\"y\"}}}"), "/items");
        assertEquals(
                List.of("x", "y"),
                byId.stream().map(r -> JsonExtractor.text(r, "n")).toList());
        assertTrue(JsonExtractor.rows(root, "/response/numFound").isEmpty(), "a scalar has no rows");
        assertTrue(JsonExtractor.rows(root, "/nowhere").isEmpty());
        assertEquals(
                2,
                JsonExtractor.rows(JsonExtractor.read("[{\"a\":1},{\"a\":2}]"), "")
                        .size(),
                "a bare list");
    }

    @Test
    void theTreeIsPlainJavaAndBadJsonThrows() throws IOException {
        Object root = JsonExtractor.read("{\"a\":[1,2.5,\"s\",true,null,{}]}");
        assertTrue(root instanceof Map);
        List<?> a = (List<?>) ((Map<?, ?>) root).get("a");
        assertEquals(1L, a.get(0));
        assertEquals(2.5, a.get(1));
        assertEquals("s", a.get(2));
        assertEquals(Boolean.TRUE, a.get(3));
        assertSame(JsonExtractor.NULL, a.get(4));
        assertTrue(a.get(5) instanceof Map);
        assertThrows(IOException.class, () -> JsonExtractor.read("{not json"));
        assertThrows(IOException.class, () -> JsonExtractor.read("[1,2"));
        assertThrows(IOException.class, () -> JsonExtractor.read(""));
        assertThrows(IOException.class, () -> JsonExtractor.read("<html>"));
    }
}
