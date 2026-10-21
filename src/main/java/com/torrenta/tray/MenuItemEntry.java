package com.torrenta.tray;

import java.util.Map;

import org.freedesktop.dbus.Struct;
import org.freedesktop.dbus.annotations.Position;
import org.freedesktop.dbus.types.Variant;

/** A (id, properties) pair as returned by dbusmenu GetGroupProperties: {@code (ia{sv})}. */
public final class MenuItemEntry extends Struct {

    @Position(0)
    public final int id;

    @Position(1)
    public final Map<String, Variant<?>> properties;

    public MenuItemEntry(int id, Map<String, Variant<?>> properties) {
        this.id = id;
        this.properties = properties;
    }
}
