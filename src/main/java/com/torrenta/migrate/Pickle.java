package com.torrenta.migrate;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Python's pickle, read as data and nothing else: Deluge keeps its torrents' state as one ({@code
 * torrents.state}). A pickle is a program for a stack machine that may name any class and call
 * it; this reader runs none of it. A class is only its name ({@link Global}), an object made from
 * one is that name and a map of its state ({@link Obj}), and everything else is a plain value:
 * {@code dict} a {@link Map}, {@code list} and {@code tuple} a {@link List}, {@code str} a {@link
 * String}, {@code bytes} a {@code byte[]}, {@code int} a {@link Long} (a {@link BigInteger} past
 * it), {@code float} a {@link Double}, {@code bool} a {@link Boolean}, {@code None} null.
 *
 * <p>Protocols 0 to 5 are read, which covers Deluge 1.3's protocol 0 (Python 2's {@code cPickle}
 * default) and Deluge 2's protocol 2. Persistent ids, extension codes and out-of-band buffers mean
 * nothing without the program that wrote them and refuse the whole file. Every length is checked
 * against the bytes left before anything is allocated.
 */
public final class Pickle {

    /** A class or function the pickle named: {@code module.name}. Never looked up. */
    public record Global(String module, String name) {
        @Override
        public String toString() {
            return module + "." + name;
        }
    }

    /**
     * An object the pickle built: its class's name, the arguments it was made with, and the state
     * {@code BUILD} gave it (its {@code __dict__}).
     */
    public record Obj(String type, List<Object> args, Map<String, Object> state) {}

    private static final Object MARK = new Object();
    private static final Object NONE = new Object();

    private final byte[] data;
    private int pos;
    private final List<Object> stack = new ArrayList<>();
    private final Map<Long, Object> memo = new HashMap<>();

    private Pickle(byte[] data) {
        this.data = data;
    }

    /** The one value {@code data} pickles. */
    public static Object read(byte[] data) throws IOException {
        try {
            return new Pickle(data).run();
        } catch (IndexOutOfBoundsException | ClassCastException | NumberFormatException e) {
            throw new IOException("not a pickle this reader understands: " + e.getMessage(), e);
        }
    }

    private Object run() throws IOException {
        while (pos < data.length) {
            int op = data[pos++] & 0xff;
            switch (op) {
                case 0x80 -> pos++; // PROTO
                case 0x95 -> skip(8); // FRAME: its length is only a hint
                case '.' -> {
                    Object v = pop();
                    return v == NONE ? null : v;
                }
                case '(' -> push(MARK);
                case '0' -> pop();
                case '1' -> popMark();
                case '2' -> push(stack.getLast());
                case 'N' -> push(NONE);
                case 0x88 -> push(Boolean.TRUE);
                case 0x89 -> push(Boolean.FALSE);
                case 'I' -> {
                    String s = line();
                    if (s.equals("01")) push(Boolean.TRUE);
                    else if (s.equals("00")) push(Boolean.FALSE);
                    else push(integer(s));
                }
                case 'L' -> push(integer(line().replaceFirst("L$", "")));
                case 'J' -> push((long) (int) littleEndian(4));
                case 'K' -> push(littleEndian(1));
                case 'M' -> push(littleEndian(2));
                case 0x8a -> push(longOf(length(littleEndian(1))));
                case 0x8b -> push(longOf(length(littleEndian(4))));
                case 'F' -> push(Double.parseDouble(line()));
                case 'G' -> {
                    long bits = 0;
                    for (int i = 0; i < 8; i++) bits = (bits << 8) | (data[pos++] & 0xff);
                    push(Double.longBitsToDouble(bits));
                }
                case 'S' -> push(new String(pythonString(line()), StandardCharsets.UTF_8));
                case 'T' -> push(new String(take(length((int) littleEndian(4))), StandardCharsets.UTF_8));
                case 'U' -> push(new String(take(length(littleEndian(1))), StandardCharsets.UTF_8));
                case 'V' -> push(rawUnicodeEscape(lineBytes()));
                case 'X' -> push(new String(take(length(littleEndian(4))), StandardCharsets.UTF_8));
                case 0x8c -> push(new String(take(length(littleEndian(1))), StandardCharsets.UTF_8));
                case 0x8d -> push(new String(take(length(littleEndian(8))), StandardCharsets.UTF_8));
                case 'B' -> push(take(length(littleEndian(4))));
                case 'C' -> push(take(length(littleEndian(1))));
                case 0x8e, 0x96 -> push(take(length(littleEndian(8))));
                case '}' -> push(new LinkedHashMap<>());
                case ']' -> push(new ArrayList<>());
                case ')' -> push(List.of());
                case 0x8f -> push(new ArrayList<>()); // EMPTY_SET, kept as a list
                case 'd' -> {
                    List<Object> items = popMark();
                    Map<Object, Object> m = new LinkedHashMap<>();
                    for (int i = 0; i + 1 < items.size(); i += 2) m.put(value(items.get(i)), value(items.get(i + 1)));
                    push(m);
                }
                case 'l' -> push(values(popMark()));
                case 't' -> push(Collections.unmodifiableList(values(popMark())));
                case 0x91 -> push(values(popMark())); // FROZENSET
                case 0x85 -> push(Collections.unmodifiableList(values(List.of(pop()))));
                case 0x86 -> {
                    Object b = pop(), a = pop();
                    push(Collections.unmodifiableList(values(List.of(a, b))));
                }
                case 0x87 -> {
                    Object c = pop(), b = pop(), a = pop();
                    push(Collections.unmodifiableList(values(List.of(a, b, c))));
                }
                case 'a' -> {
                    Object v = pop();
                    list(stack.getLast()).add(value(v));
                }
                case 'e', 0x90 -> {
                    List<Object> items = popMark();
                    list(stack.getLast()).addAll(values(items));
                }
                case 's' -> {
                    Object v = pop(), k = pop();
                    map(stack.getLast()).put(value(k), value(v));
                }
                case 'u' -> {
                    List<Object> items = popMark();
                    Map<Object, Object> m = map(stack.getLast());
                    for (int i = 0; i + 1 < items.size(); i += 2) m.put(value(items.get(i)), value(items.get(i + 1)));
                }
                case 'p' -> memo.put(Long.parseLong(line()), stack.getLast());
                case 'q' -> memo.put(littleEndian(1), stack.getLast());
                case 'r' -> memo.put(littleEndian(4), stack.getLast());
                case 0x94 -> memo.put((long) memo.size(), stack.getLast());
                case 'g' -> push(remembered(Long.parseLong(line())));
                case 'h' -> push(remembered(littleEndian(1)));
                case 'j' -> push(remembered(littleEndian(4)));
                case 'c' -> push(new Global(line(), line()));
                case 0x93 -> {
                    Object name = pop(), module = pop();
                    push(new Global(String.valueOf(value(module)), String.valueOf(value(name))));
                }
                case 'R' -> {
                    Object args = pop(), callable = pop();
                    push(reduce(callable, list(args)));
                }
                case 0x81 -> {
                    Object args = pop(), cls = pop();
                    push(new Obj(String.valueOf(cls), values(list(args)), new LinkedHashMap<>()));
                }
                case 0x92 -> {
                    pop(); // the keyword arguments
                    Object args = pop(), cls = pop();
                    push(new Obj(String.valueOf(cls), values(list(args)), new LinkedHashMap<>()));
                }
                case 'i' -> {
                    Global cls = new Global(line(), line());
                    push(new Obj(cls.toString(), values(popMark()), new LinkedHashMap<>()));
                }
                case 'o' -> {
                    List<Object> items = popMark();
                    push(new Obj(
                            String.valueOf(value(items.getFirst())),
                            values(items.subList(1, items.size())),
                            new LinkedHashMap<>()));
                }
                case 'b' -> {
                    Object state = value(pop());
                    if (stack.getLast() instanceof Obj o) build(o, state);
                }
                default -> throw new IOException(String.format("unsupported pickle opcode 0x%02x at %d", op, pos - 1));
            }
        }
        throw new IOException("pickle ends without STOP");
    }

    /**
     * {@code copyreg._reconstructor(cls, base, state)} ({@code copy_reg} in Python 2, and in what
     * Python 3 writes at protocol 0), what protocols 0 and 1 make a plain object with, is that object; any other call is its name and its arguments, never made.
     */
    private static Object reduce(Object callable, List<Object> args) {
        if (callable instanceof Global g
                && (g.module().equals("copyreg") || g.module().equals("copy_reg"))
                && g.name().equals("_reconstructor")
                && !args.isEmpty()) {
            return new Obj(String.valueOf(value(args.getFirst())), List.of(), new LinkedHashMap<>());
        }
        return new Obj(String.valueOf(value(callable)), values(args), new LinkedHashMap<>());
    }

    /** {@code BUILD}: a dict is the object's {@code __dict__}; a pair is that and its slots. */
    private static void build(Obj o, Object state) {
        if (state instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) o.state().put(String.valueOf(e.getKey()), e.getValue());
        } else if (state instanceof List<?> pair && pair.size() == 2) {
            for (Object part : pair) if (part instanceof Map<?, ?>) build(o, part);
        }
    }

    private Object remembered(long key) throws IOException {
        if (!memo.containsKey(key)) throw new IOException("pickle refers to " + key + ", never stored");
        return memo.get(key);
    }

    private void push(Object v) {
        stack.add(v);
    }

    private Object pop() throws IOException {
        if (stack.isEmpty()) throw new IOException("pickle stack underflow");
        Object v = stack.removeLast();
        if (v == MARK) throw new IOException("pickle mark where a value was expected");
        return v;
    }

    private List<Object> popMark() throws IOException {
        int mark = stack.lastIndexOf(MARK);
        if (mark < 0) throw new IOException("pickle has no mark");
        List<Object> items = new ArrayList<>(stack.subList(mark + 1, stack.size()));
        stack.subList(mark, stack.size()).clear();
        return items;
    }

    private static Object value(Object v) {
        return v == NONE ? null : v;
    }

    private static List<Object> values(List<?> items) {
        List<Object> out = new ArrayList<>(items.size());
        for (Object i : items) out.add(value(i));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object v) throws IOException {
        if (v instanceof List<?> l) return (List<Object>) l;
        throw new IOException("pickle appends to something that is not a list");
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> map(Object v) throws IOException {
        if (v instanceof Map<?, ?> m) return (Map<Object, Object>) m;
        throw new IOException("pickle sets an item on something that is not a dict");
    }

    /** {@code n} bytes, little-endian, unsigned; {@code n} up to 8. */
    private long littleEndian(int n) {
        long v = 0;
        for (int i = 0; i < n; i++) v |= (long) (data[pos++] & 0xff) << (8 * i);
        return v;
    }

    /** A length that fits in what is left, or the file is refused before anything is allocated. */
    private int length(long n) throws IOException {
        if (n < 0 || n > data.length - pos) throw new IOException("pickle length " + n + " past its end");
        return (int) n;
    }

    private byte[] take(int n) {
        byte[] out = java.util.Arrays.copyOfRange(data, pos, pos + n);
        pos += n;
        return out;
    }

    private void skip(int n) throws IOException {
        pos += length(n);
    }

    /** LONG1/LONG4: two's complement, little-endian. */
    private Object longOf(int n) {
        if (n == 0) return 0L;
        byte[] big = new byte[n];
        for (int i = 0; i < n; i++) big[n - 1 - i] = data[pos + i];
        pos += n;
        BigInteger v = new BigInteger(big);
        return v.bitLength() < 64 ? (Object) v.longValue() : v;
    }

    private static Object integer(String s) {
        BigInteger v = new BigInteger(s.trim());
        return v.bitLength() < 64 ? (Object) v.longValue() : v;
    }

    private byte[] lineBytes() throws IOException {
        int start = pos;
        while (pos < data.length && data[pos] != '\n') pos++;
        if (pos >= data.length) throw new IOException("pickle line without its end");
        return java.util.Arrays.copyOfRange(data, start, pos++);
    }

    private String line() throws IOException {
        return new String(lineBytes(), StandardCharsets.UTF_8);
    }

    /** Protocol 0's {@code STRING}: Python's quoted {@code repr} of a byte string. */
    static byte[] pythonString(String repr) throws IOException {
        if (repr.length() < 2
                || repr.charAt(0) != repr.charAt(repr.length() - 1)
                || (repr.charAt(0) != '\'' && repr.charAt(0) != '"')) throw new IOException("pickle string not quoted");
        String s = repr.substring(1, repr.length() - 1);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                out.write(c);
                continue;
            }
            char e = s.charAt(++i);
            switch (e) {
                case 'n' -> out.write('\n');
                case 't' -> out.write('\t');
                case 'r' -> out.write('\r');
                case 'x' -> {
                    out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                    i += 2;
                }
                case '0', '1', '2', '3', '4', '5', '6', '7' -> {
                    int end = i;
                    while (end < s.length() && end < i + 3 && s.charAt(end) >= '0' && s.charAt(end) <= '7') end++;
                    out.write(Integer.parseInt(s.substring(i, end), 8));
                    i = end - 1;
                }
                default -> out.write(e);
            }
        }
        return out.toByteArray();
    }

    /** Protocol 0's {@code UNICODE}: Latin-1 bytes with {@code \}{@code uXXXX} and {@code \}{@code UXXXXXXXX} escapes. */
    static String rawUnicodeEscape(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length);
        for (int i = 0; i < bytes.length; i++) {
            int c = bytes[i] & 0xff;
            if (c == '\\' && i + 1 < bytes.length && (bytes[i + 1] == 'u' || bytes[i + 1] == 'U')) {
                int digits = bytes[i + 1] == 'u' ? 4 : 8;
                if (i + 2 + digits <= bytes.length) {
                    String hex = new String(bytes, i + 2, digits, StandardCharsets.US_ASCII);
                    try {
                        out.appendCodePoint(Integer.parseInt(hex, 16));
                        i += 1 + digits;
                        continue;
                    } catch (IllegalArgumentException e) {
                        // not an escape after all: the backslash as it is
                    }
                }
            }
            out.append((char) c);
        }
        return out.toString();
    }
}
