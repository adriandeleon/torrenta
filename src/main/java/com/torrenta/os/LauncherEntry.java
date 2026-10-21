package com.torrenta.os;

import java.util.Map;

import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.messages.DBusSignal;
import org.freedesktop.dbus.types.Variant;

/**
 * Unity's launcher API, a broadcast signal with no object behind it: {@code Update(app_uri,
 * properties)} on the session bus, where {@code app_uri} is {@code application://<entry>.desktop}
 * and the properties carry {@code count} (int64) with {@code count-visible}. KDE Plasma's task
 * manager, GNOME's Dash to Dock and Ubuntu's dock all listen; anything else ignores it.
 */
@DBusInterfaceName("com.canonical.Unity.LauncherEntry")
public interface LauncherEntry extends DBusInterface {

    class Update extends DBusSignal {
        private final String appUri;
        private final Map<String, Variant<?>> properties;

        public Update(String path, String appUri, Map<String, Variant<?>> properties) throws DBusException {
            super(path, appUri, properties);
            this.appUri = appUri;
            this.properties = properties;
        }

        public String getAppUri() {
            return appUri;
        }

        public Map<String, Variant<?>> getProperties() {
            return properties;
        }
    }
}
