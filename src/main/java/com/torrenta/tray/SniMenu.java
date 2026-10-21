package com.torrenta.tray;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.messages.DBusSignal;
import org.freedesktop.dbus.types.UInt32;
import org.freedesktop.dbus.types.Variant;

/**
 * The exported com.canonical.dbusmenu object backing the SNI tray menu: the protocol KDE and
 * GNOME's AppIndicator extension pull menus through. The layout is {@link SniMenuModel}'s.
 */
@DBusInterfaceName("com.canonical.dbusmenu")
public interface SniMenu extends DBusInterface {

    String PATH = "/MenuBar";

    MenuPair<UInt32, MenuLayout> GetLayout(int parentId, int recursionDepth, List<String> propertyNames);

    List<MenuItemEntry> GetGroupProperties(List<Integer> ids, List<String> propertyNames);

    Variant<?> GetProperty(int id, String name);

    void Event(int id, String eventId, Variant<?> data, UInt32 timestamp);

    List<Integer> EventGroup(List<MenuEventEntry> events);

    boolean AboutToShow(int id);

    MenuPair<List<Integer>, List<Integer>> AboutToShowGroup(List<Integer> ids);

    class LayoutUpdated extends DBusSignal {
        private final UInt32 revision;
        private final int parent;

        public LayoutUpdated(String path, UInt32 revision, int parent) throws DBusException {
            super(path, revision, parent);
            this.revision = revision;
            this.parent = parent;
        }

        public UInt32 getRevision() {
            return revision;
        }

        public int getParent() {
            return parent;
        }
    }

    /** The exported implementation; thread-safe (dbus calls arrive on dbus-java's threads). */
    final class Impl implements SniMenu, Properties {

        private final Runnable onOpen;
        private final Runnable onQuit;
        private final AtomicInteger revision = new AtomicInteger(1);
        private volatile String statusLine = "Starting…";

        Impl(Runnable onOpen, Runnable onQuit) {
            this.onOpen = onOpen;
            this.onQuit = onQuit;
        }

        /**
         * Returns the new revision when the line changed, else -1. The revision is what makes the
         * panel re-read the labels: a changed label behind the same revision is never fetched.
         */
        int setStatusLine(String line) {
            if (statusLine.equals(line)) return -1;
            statusLine = line;
            return revision.incrementAndGet();
        }

        @Override
        public String getObjectPath() {
            return PATH;
        }

        @Override
        public MenuPair<UInt32, MenuLayout> GetLayout(int parentId, int recursionDepth, List<String> propertyNames) {
            MenuLayout layout = parentId == SniMenuModel.ROOT
                    ? SniMenuModel.layout(statusLine)
                    : SniMenuModel.item(parentId, statusLine);
            return new MenuPair<>(new UInt32(revision.get()), layout);
        }

        @Override
        public List<MenuItemEntry> GetGroupProperties(List<Integer> ids, List<String> propertyNames) {
            List<Integer> wanted = ids == null || ids.isEmpty() ? SniMenuModel.ITEM_IDS : ids;
            return wanted.stream()
                    .map(id -> new MenuItemEntry(id, SniMenuModel.propsFor(id, statusLine)))
                    .toList();
        }

        @Override
        public Variant<?> GetProperty(int id, String name) {
            Variant<?> value = SniMenuModel.propsFor(id, statusLine).get(name);
            return value != null ? value : new Variant<>("");
        }

        @Override
        public void Event(int id, String eventId, Variant<?> data, UInt32 timestamp) {
            if (!"clicked".equals(eventId)) return;
            if (id == SniMenuModel.OPEN || id == SniMenuModel.STATUS) onOpen.run();
            else if (id == SniMenuModel.QUIT) onQuit.run();
        }

        @Override
        public List<Integer> EventGroup(List<MenuEventEntry> events) {
            if (events != null) {
                for (MenuEventEntry e : events) Event(e.id, e.eventId, e.data, e.timestamp);
            }
            return List.of();
        }

        @Override
        public boolean AboutToShow(int id) {
            return false;
        }

        @Override
        public MenuPair<List<Integer>, List<Integer>> AboutToShowGroup(List<Integer> ids) {
            return new MenuPair<>(List.of(), List.of());
        }

        @SuppressWarnings("unchecked")
        @Override
        public <A> A Get(String interfaceName, String propertyName) {
            return (A) GetAll(interfaceName).get(propertyName);
        }

        @Override
        public <A> void Set(String interfaceName, String propertyName, A value) {}

        @Override
        public Map<String, Variant<?>> GetAll(String interfaceName) {
            return Map.of(
                    "Version", new Variant<>(new UInt32(3)),
                    "Status", new Variant<>("normal"),
                    "TextDirection", new Variant<>("ltr"),
                    "IconThemePath", new Variant<>(List.<String>of(), "as"));
        }
    }
}
