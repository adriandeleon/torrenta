package com.torrenta.tray;

import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.interfaces.DBusInterface;

/** The host-side registry every SNI item announces itself to. */
@DBusInterfaceName("org.kde.StatusNotifierWatcher")
public interface StatusNotifierWatcher extends DBusInterface {

    String BUS_NAME = "org.kde.StatusNotifierWatcher";
    String OBJECT_PATH = "/StatusNotifierWatcher";

    void RegisterStatusNotifierItem(String service);
}
