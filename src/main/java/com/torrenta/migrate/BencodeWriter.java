package com.torrenta.migrate;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bencode out: what a converted resume file is written as. Integers, byte strings (text as UTF-8),
 * lists and dictionaries, the keys sorted by their bytes as the format requires. Pure.
 */
final class BencodeWriter {

    private BencodeWriter() {}

    static byte[] encode(Object value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, value);
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, Object value) {
        switch (value) {
            case Long l -> out.writeBytes(("i" + l + "e").getBytes(StandardCharsets.US_ASCII));
            case Integer i -> out.writeBytes(("i" + i + "e").getBytes(StandardCharsets.US_ASCII));
            case Boolean b -> out.writeBytes((b ? "i1e" : "i0e").getBytes(StandardCharsets.US_ASCII));
            case String s -> bytes(out, s.getBytes(StandardCharsets.UTF_8));
            case byte[] b -> bytes(out, b);
            case List<?> list -> {
                out.write('l');
                for (Object item : list) write(out, item);
                out.write('e');
            }
            case Map<?, ?> map -> {
                List<byte[]> keys = new ArrayList<>();
                for (Object k : map.keySet()) keys.add(String.valueOf(k).getBytes(StandardCharsets.UTF_8));
                keys.sort(Arrays::compareUnsigned);
                out.write('d');
                for (byte[] k : keys) {
                    bytes(out, k);
                    write(
                            out,
                            Objects.requireNonNull(
                                    map.get(new String(k, StandardCharsets.UTF_8)), "not bencodable: null"));
                }
                out.write('e');
            }
            default -> throw new IllegalArgumentException("not bencodable: " + value.getClass());
        }
    }

    private static void bytes(ByteArrayOutputStream out, byte[] b) {
        out.writeBytes((b.length + ":").getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(b);
    }
}
