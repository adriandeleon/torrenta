package com.torrenta.migrate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One value pickled by Python 3.13 at every protocol ({@code pickle/make.py} wrote the fixtures),
 * read the same each time, then the opcodes only Python 2 writes, built by hand.
 */
class PickleProtocolsTest {

    private static Object fixture(String name) throws Exception {
        return Pickle.read(Files.readAllBytes(Path.of(PickleProtocolsTest.class
                .getResource("/com/torrenta/migrate/pickle/" + name)
                .toURI())));
    }

    @SuppressWarnings("unchecked")
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void everyProtocolReadsTheSameValues(int protocol) throws Exception {
        Map<String, Object> v = (Map<String, Object>) fixture("protocol" + protocol + ".pickle");
        assertEquals(5L, v.get("small"));
        assertEquals(300L, v.get("medium"));
        assertEquals(-70000L, v.get("int"));
        assertEquals(BigInteger.ONE.shiftLeft(70), v.get("big"));
        assertEquals(-1L, v.get("neg"));
        assertEquals(0L, v.get("zero"));
        assertEquals(2.5, v.get("float"));
        assertEquals("héllo", v.get("str"));
        assertEquals("y".repeat(300), v.get("long_str"));
        assertEquals(List.of(1L), v.get("t1"));
        assertEquals(List.of(1L, 2L), v.get("t2"));
        assertEquals(List.of(1L, 2L, 3L), v.get("t3"));
        assertEquals(List.of(1L, 2L, 3L, 4L), v.get("t4"));
        assertEquals(List.of(), v.get("empty_t"));
        assertTrue(v.containsKey("none"));
        assertNull(v.get("none"));
        assertEquals(Boolean.TRUE, v.get("yes"));
        assertEquals(Boolean.FALSE, v.get("no"));
        assertEquals(List.of(1L, List.of(2L, 3L)), v.get("list"));
        Map<String, Object> none = new java.util.HashMap<>();
        none.put("n", null);
        assertEquals(Map.of("k", none), v.get("dict"));

        Pickle.Obj plain = (Pickle.Obj) v.get("plain");
        assertEquals("__main__.Plain", plain.type());
        assertEquals(Map.of("a", 1L, "b", "x"), plain.state());
        Pickle.Obj slotted = (Pickle.Obj) v.get("slotted");
        assertEquals("__main__.Slotted", slotted.type());
        assertEquals(Map.of("s", 5L), slotted.state(), "a (None, slots) pair's dict");

        if (protocol >= 3) {
            assertArrayEquals("ab".getBytes(StandardCharsets.US_ASCII), (byte[]) v.get("bytes"));
            assertArrayEquals("z".repeat(300).getBytes(StandardCharsets.US_ASCII), (byte[]) v.get("long_bytes"));
        } else {
            // Before protocol 3, bytes are a call to _codecs.encode, kept as its name and arguments.
            assertEquals("_codecs.encode", ((Pickle.Obj) v.get("bytes")).type());
        }
        if (protocol >= 4) {
            assertEquals(List.of(9L), v.get("set"));
            assertEquals(List.of(8L), v.get("fset"));
        } else {
            // Python 3 names Python 2's module below protocol 3, so either can read it.
            assertEquals(protocol < 3 ? "__builtin__.set" : "builtins.set", ((Pickle.Obj) v.get("set")).type());
        }
        if (protocol == 5) assertArrayEquals("qq".getBytes(StandardCharsets.US_ASCII), (byte[]) v.get("bytearray"));
    }

    @Test
    void newObjExKeepsThePositionalArgumentsAndDropsTheKeywords() throws Exception {
        Pickle.Obj kw = (Pickle.Obj) fixture("kw.pickle");
        assertEquals("__main__.Kw", kw.type());
        assertEquals(List.of(7L), kw.args());
        assertEquals(Map.of("n", 7L), kw.state());
    }

    /** Builds a pickle from opcodes and their operands: a String is its bytes, an int one byte. */
    private static byte[] ops(Object... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Object p : parts) {
            if (p instanceof String s) out.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
            else if (p instanceof Integer i) out.write(i);
            else if (p instanceof byte[] b) out.writeBytes(b);
        }
        return out.toByteArray();
    }

    private static byte[] le(long v, int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (v >>> (8 * i));
        return b;
    }

    @Test
    void pythonTwosOpcodesReadAsValues() throws IOException {
        // BINSTRING, SHORT_BINSTRING, BINUNICODE8, BINBYTES8, LONG4, BININT inside a tuple.
        Object v = Pickle.read(ops(
                0x80, 2, "(", "T", le(3, 4), "abc", "U", 2, "de", 0x8d, le(1, 8), "f", 0x8e, le(1, 8), "g", 0x8b,
                le(1, 4), 0xff, "J", le(-2, 4), "t", "."));
        List<?> t = (List<?>) v;
        assertEquals("abc", t.get(0));
        assertEquals("de", t.get(1));
        assertEquals("f", t.get(2));
        assertArrayEquals(new byte[] {'g'}, (byte[]) t.get(3));
        assertEquals(-1L, t.get(4));
        assertEquals(-2L, t.get(5));

        // LONG1 of no bytes is zero; FLOAT, a quoted STRING and an octal escape; L-suffixed LONG.
        assertEquals(
                List.of(0L, 1.25, "A", 12L),
                Pickle.read(ops("(", 0x8a, 0, "F1.25\n", "S'\\101'\n", "L12L\n", "l", ".")));
        assertEquals(BigInteger.TEN.pow(30), Pickle.read(ops("I" + BigInteger.TEN.pow(30) + "\n.")));
        assertEquals(List.of("x"), Pickle.read(ops("(V\\u0078\nl.")));
    }

    @Test
    void theStackOpsAndTheLongMemoWork() throws IOException {
        // DUP, POP, POP_MARK, LONG_BINPUT/LONG_BINGET, PUT/GET by line.
        Object v = Pickle.read(ops(
                "(",
                "K",
                1,
                "K",
                2,
                "1", // a mark and two values, all gone
                "K",
                7,
                "2",
                "0", // seven, duplicated, the copy popped
                "r",
                le(1000, 4),
                "0",
                "j",
                le(1000, 4), // stored at 1000, popped, got back
                "p5\n",
                "g5\n",
                0x86, // stored as 5 by line and got back: a pair
                "."));
        assertEquals(List.of(7L, 7L), v);
    }

    @Test
    void instAndObjAreObjectsWithTheirArguments() throws IOException {
        Pickle.Obj inst =
                (Pickle.Obj) Pickle.read(ops("(K", 4, "i__main__\nPoint\n", "}", "S'x'\n", "K", 1, "s", "b."));
        assertEquals("__main__.Point", inst.type());
        assertEquals(List.of(4L), inst.args());
        assertEquals(Map.of("x", 1L), inst.state());

        Pickle.Obj obj = (Pickle.Obj) Pickle.read(ops("(c__main__\nPoint\nK", 2, "o."));
        assertEquals("__main__.Point", obj.type());
        assertEquals(List.of(2L), obj.args());

        Pickle.Obj made = (Pickle.Obj) Pickle.read(ops(0x80, 4, 0x8c, 1, "m", 0x8c, 1, "n", 0x93, ")", 0x81, "."));
        assertEquals("m.n", made.type());

        // A call whose argument list is a copyreg._reconstructor's is the object itself.
        Pickle.Obj copied = (Pickle.Obj) Pickle.read(ops("ccopy_reg\n_reconstructor\n(c__main__\nP\nt", "R."));
        assertEquals("__main__.P", copied.type());
        assertEquals(List.of(), copied.args());
        // BUILD on something that is not an object leaves it be.
        assertEquals(List.of(), Pickle.read(ops("]", "}", "b.")));
        // A pair whose parts are not dicts gives no state.
        assertEquals(Map.of(), ((Pickle.Obj) Pickle.read(ops("cm\nn\n)R", "(K", 1, "K", 2, "t", "b."))).state());
    }

    @Test
    void aMalformedPickleIsRefusedWithAReason() {
        List<byte[]> bad = List.of(
                ops("K", 1, "K", 2, "a."), // APPEND to an int
                ops("K", 1, "K", 2, "K", 3, "s."), // SETITEM on an int
                ops("0."), // POP of nothing
                ops("(."), // STOP on a mark
                ops("e."), // APPENDS with no mark
                ops("I12"), // a line without its end
                ops("Sabc\n."), // a STRING not quoted
                ops(0x82, "."), // EXT1: an extension code
                ops("S'"), // a quote and no end
                ops("I1x\n."), // not a number
                ops(0x95, le(1, 4))); // FRAME past the end
        for (byte[] b : bad)
            assertThrows(IOException.class, () -> Pickle.read(b), new String(b, StandardCharsets.ISO_8859_1));
        assertThrows(IOException.class, () -> Pickle.pythonString("'a\""));
        assertThrows(IOException.class, () -> Pickle.pythonString("x"));
        assertEquals("\\", new String(assertDoesNotThrow("'\\'"), StandardCharsets.ISO_8859_1));
        assertEquals("\r", new String(assertDoesNotThrow("'\\r'"), StandardCharsets.ISO_8859_1));
        assertEquals("q", new String(assertDoesNotThrow("'\\q'"), StandardCharsets.ISO_8859_1));
        assertEquals("\\uZZZZ\\u12", Pickle.rawUnicodeEscape("\\uZZZZ\\u12".getBytes(StandardCharsets.ISO_8859_1)));
    }

    private static byte[] assertDoesNotThrow(String repr) {
        try {
            return Pickle.pythonString(repr);
        } catch (IOException e) {
            throw new AssertionError(repr, e);
        }
    }
}
