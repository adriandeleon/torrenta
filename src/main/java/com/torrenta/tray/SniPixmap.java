package com.torrenta.tray;

import java.awt.image.BufferedImage;

import org.freedesktop.dbus.Struct;
import org.freedesktop.dbus.annotations.Position;

/** One SNI icon pixmap: D-Bus type {@code (iiay)} — width, height, ARGB32 in network byte order. */
public final class SniPixmap extends Struct {

    @Position(0)
    public final int width;

    @Position(1)
    public final int height;

    @Position(2)
    public final byte[] data;

    public SniPixmap(int width, int height, byte[] data) {
        this.width = width;
        this.height = height;
        this.data = data;
    }

    public static SniPixmap of(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        byte[] data = new byte[w * h * 4];
        int i = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = image.getRGB(x, y);
                data[i++] = (byte) (argb >>> 24);
                data[i++] = (byte) (argb >>> 16);
                data[i++] = (byte) (argb >>> 8);
                data[i++] = (byte) argb;
            }
        }
        return new SniPixmap(w, h, data);
    }
}
