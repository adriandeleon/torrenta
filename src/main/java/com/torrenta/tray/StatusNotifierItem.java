package com.torrenta.tray;

import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.messages.DBusSignal;

/**
 * The freedesktop StatusNotifierItem D-Bus interface (the modern Linux "tray icon"):
 * methods the host calls on us, and the change-notification signals we emit.
 * Properties (Category, IconPixmap, ToolTip, ...) go through org.freedesktop.DBus.Properties.
 */
@DBusInterfaceName("org.kde.StatusNotifierItem")
public interface StatusNotifierItem extends DBusInterface {

    void Activate(int x, int y);

    void SecondaryActivate(int x, int y);

    void ContextMenu(int x, int y);

    void Scroll(int delta, String orientation);

    class NewIcon extends DBusSignal {
        public NewIcon(String path) throws DBusException {
            super(path);
        }
    }

    class NewToolTip extends DBusSignal {
        public NewToolTip(String path) throws DBusException {
            super(path);
        }
    }

    class NewStatus extends DBusSignal {
        private final String status;

        public NewStatus(String path, String status) throws DBusException {
            super(path, status);
            this.status = status;
        }

        public String getStatus() {
            return status;
        }
    }
}
