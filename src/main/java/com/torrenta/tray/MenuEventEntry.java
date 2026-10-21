package com.torrenta.tray;

import org.freedesktop.dbus.Struct;
import org.freedesktop.dbus.annotations.Position;
import org.freedesktop.dbus.types.UInt32;
import org.freedesktop.dbus.types.Variant;

/** One batched dbusmenu event (EventGroup): {@code (isvu)} — id, eventId, data, timestamp. */
public final class MenuEventEntry extends Struct {

    @Position(0)
    public final int id;

    @Position(1)
    public final String eventId;

    @Position(2)
    public final Variant<?> data;

    @Position(3)
    public final UInt32 timestamp;

    public MenuEventEntry(int id, String eventId, Variant<?> data, UInt32 timestamp) {
        this.id = id;
        this.eventId = eventId;
        this.data = data;
        this.timestamp = timestamp;
    }
}
