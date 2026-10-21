package com.torrenta.tray;

import org.freedesktop.dbus.Tuple;
import org.freedesktop.dbus.annotations.Position;

/**
 * A two-value dbusmenu return. Deliberately generic: dbus-java derives the D-Bus
 * signature from the exporting method's generic return type, so a non-generic Tuple
 * subclass makes the whole object non-exportable ("non-exportable type: interface
 * java.util.List" at exportObject time).
 */
public final class MenuPair<A, B> extends Tuple {

    @Position(0)
    public final A first;

    @Position(1)
    public final B second;

    public MenuPair(A first, B second) {
        this.first = first;
        this.second = second;
    }
}
