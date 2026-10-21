package com.torrenta.tray;

import java.util.List;
import java.util.Map;

import org.freedesktop.dbus.Struct;
import org.freedesktop.dbus.annotations.Position;
import org.freedesktop.dbus.types.Variant;

/** One dbusmenu layout node: D-Bus type {@code (ia{sv}av)} — id, properties, child nodes. */
public final class MenuLayout extends Struct {

    /** The signature hosts expect for a child node wrapped in a Variant. */
    public static final String SIGNATURE = "(ia{sv}av)";

    @Position(0)
    public final int id;

    @Position(1)
    public final Map<String, Variant<?>> properties;

    @Position(2)
    public final List<Variant<?>> children;

    public MenuLayout(int id, Map<String, Variant<?>> properties, List<Variant<?>> children) {
        this.id = id;
        this.properties = properties;
        this.children = children;
    }
}
