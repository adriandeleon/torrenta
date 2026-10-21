package com.torrenta.tray;

import java.util.List;

import org.freedesktop.dbus.Struct;
import org.freedesktop.dbus.annotations.Position;

/** The SNI ToolTip property: D-Bus type {@code (sa(iiay)ss)} — icon name, pixmaps, title, text. */
public final class SniTooltip extends Struct {

    @Position(0)
    public final String iconName;

    @Position(1)
    public final List<SniPixmap> iconPixmaps;

    @Position(2)
    public final String title;

    @Position(3)
    public final String text;

    public SniTooltip(String iconName, List<SniPixmap> iconPixmaps, String title, String text) {
        this.iconName = iconName;
        this.iconPixmaps = iconPixmaps;
        this.title = title;
        this.text = text;
    }
}
