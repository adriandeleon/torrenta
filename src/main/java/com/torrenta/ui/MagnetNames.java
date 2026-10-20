package com.torrenta.ui;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** The {@code dn} of a magnet link, decoded; what the dialog can show before the metadata. */
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
}
