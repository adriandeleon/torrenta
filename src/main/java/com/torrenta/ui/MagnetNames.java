package com.torrenta.ui;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** What a magnet link says about itself, decoded: what the dialog can show before the metadata. */
public final class MagnetNames {

    private MagnetNames() {}

    public static String displayName(String magnet) {
        int q = magnet.indexOf('?');
        if (q < 0) return "";
        for (String pair : magnet.substring(q + 1).split("&")) {
            if (pair.startsWith("dn=")) {
                try {
                    return URLDecoder.decode(pair.substring(3), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    return pair.substring(3);
                }
            }
        }
        return "";
    }

    /** How many distinct trackers ({@code tr=}) the link names. */
    public static int trackers(String magnet) {
        int q = magnet.indexOf('?');
        if (q < 0) return 0;
        return (int) java.util.Arrays.stream(magnet.substring(q + 1).split("&"))
                .filter(pair -> pair.startsWith("tr="))
                .distinct()
                .count();
    }
}
