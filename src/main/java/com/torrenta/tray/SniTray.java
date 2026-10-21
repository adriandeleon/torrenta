package com.torrenta.tray;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBus;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.types.UInt32;
import org.freedesktop.dbus.types.Variant;

/**
 * The Linux tray backend: a StatusNotifierItem exported on the session bus. Works wherever an
 * org.kde.StatusNotifierWatcher exists: KDE natively, GNOME via the AppIndicator extension, X11 and
 * Wayland alike. Construction fails fast (no watcher, no bus) so {@link Trays} can say why.
 *
 * <p>Found against the live bus, in Nux: {@code Properties.Get} must return the stored
 * {@code Variant} itself, never its value (a raw list of structs cannot be re-wrapped once the
 * generics are erased); a dbus-java {@code Tuple} subclass must be generic ({@link MenuPair}); and
 * every exported method's return type must be exportable or the whole object silently is not.
 */
public final class SniTray implements TorrentaTray {

    private static final System.Logger LOG = System.getLogger(SniTray.class.getName());
    private static final String PATH = "/StatusNotifierItem";
    /** Host panels commonly pick the nearest of these; both cover 1x and 2x scales. */
    private static final int[] ICON_SIZES = {24, 48};

    private final DBusConnection conn;
    private final Item item;
    private final SniMenu.Impl menu;
    private final String busName;

    public SniTray(Runnable onActivate, Runnable onQuit) throws DBusException {
        conn = DBusConnectionBuilder.forSessionBus().build();
        try {
            DBus dbus = conn.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus.class);
            if (!dbus.NameHasOwner(StatusNotifierWatcher.BUS_NAME)) {
                throw new DBusException("no StatusNotifierWatcher on the session bus"
                        + " (KDE has one; GNOME needs the AppIndicator extension)");
            }
            item = new Item(onActivate);
            conn.exportObject(PATH, item);
            menu = new SniMenu.Impl(onActivate, onQuit);
            conn.exportObject(SniMenu.PATH, menu);
            busName = "org.kde.StatusNotifierItem-" + ProcessHandle.current().pid() + "-1";
            conn.requestBusName(busName);
            register();
            // gnome-shell restarts drop all items; re-register when the watcher returns.
            conn.addSigHandler(DBus.NameOwnerChanged.class, signal -> {
                if (StatusNotifierWatcher.BUS_NAME.equals(signal.name) && !signal.newOwner.isEmpty()) {
                    register();
                }
            });
        } catch (DBusException | RuntimeException e) {
            conn.disconnect();
            throw e;
        }
    }

    private void register() {
        try {
            StatusNotifierWatcher watcher = conn.getRemoteObject(
                    StatusNotifierWatcher.BUS_NAME, StatusNotifierWatcher.OBJECT_PATH, StatusNotifierWatcher.class);
            watcher.RegisterStatusNotifierItem(busName);
        } catch (DBusException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "SNI registration failed: " + e.getMessage());
        }
    }

    @Override
    public void update(TrayDisplay display, String line) {
        SniPixmap[] pixmaps = new SniPixmap[ICON_SIZES.length];
        for (int i = 0; i < ICON_SIZES.length; i++) {
            BufferedImage image = TrayIconRenderer.render(display, ICON_SIZES[i]);
            pixmaps[i] = SniPixmap.of(image);
        }
        String status = display.attention() ? "NeedsAttention" : "Active";
        boolean statusChanged = item.setState(List.of(pixmaps), line, status);
        int menuRevision = menu.setStatusLine(line);
        try {
            conn.sendMessage(new StatusNotifierItem.NewIcon(PATH));
            conn.sendMessage(new StatusNotifierItem.NewToolTip(PATH));
            if (statusChanged) conn.sendMessage(new StatusNotifierItem.NewStatus(PATH, status));
            if (menuRevision >= 0) {
                conn.sendMessage(new SniMenu.LayoutUpdated(SniMenu.PATH, new UInt32(menuRevision), SniMenuModel.ROOT));
            }
        } catch (DBusException e) {
            LOG.log(System.Logger.Level.WARNING, "SNI update failed: " + e.getMessage());
        }
    }

    @Override
    public void dispose() {
        conn.disconnect();
    }

    @Override
    public String backend() {
        return "sni";
    }

    /** The exported D-Bus object: SNI methods + the Properties the host reads. */
    public static final class Item implements StatusNotifierItem, Properties {

        private final Runnable onActivate;
        private volatile List<SniPixmap> pixmaps = List.of();
        private volatile SniTooltip tooltip = new SniTooltip("", List.of(), "Torrenta", "");
        private volatile String status = "Active";

        Item(Runnable onActivate) {
            this.onActivate = onActivate;
        }

        /** Returns whether the status value changed (the caller then signals NewStatus). */
        boolean setState(List<SniPixmap> pixmaps, String tooltipText, String status) {
            this.pixmaps = pixmaps;
            this.tooltip = new SniTooltip("", List.of(), "Torrenta", tooltipText);
            boolean changed = !this.status.equals(status);
            this.status = status;
            return changed;
        }

        @Override
        public String getObjectPath() {
            return PATH;
        }

        @Override
        public void Activate(int x, int y) {
            onActivate.run();
        }

        @Override
        public void SecondaryActivate(int x, int y) {
            onActivate.run();
        }

        @Override
        public void ContextMenu(int x, int y) {
            // The host pulls the menu through the Menu property instead.
        }

        @Override
        public void Scroll(int delta, String orientation) {}

        @SuppressWarnings("unchecked")
        @Override
        public <A> A Get(String interfaceName, String propertyName) {
            // Return the Variant itself: it carries an explicit signature, which dbus-java
            // needs for struct lists (a raw List can't be wrapped — generics are erased).
            return (A) GetAll(interfaceName).get(propertyName);
        }

        @Override
        public <A> void Set(String interfaceName, String propertyName, A value) {
            // all properties are read-only
        }

        @Override
        public Map<String, Variant<?>> GetAll(String interfaceName) {
            return Map.ofEntries(
                    Map.entry("Category", new Variant<>("ApplicationStatus")),
                    Map.entry("Id", new Variant<>("torrenta")),
                    Map.entry("Title", new Variant<>("Torrenta")),
                    Map.entry("Status", new Variant<>(status)),
                    Map.entry("WindowId", new Variant<>(new UInt32(0))),
                    Map.entry("IconName", new Variant<>("")),
                    Map.entry("IconPixmap", new Variant<>(pixmaps, "a(iiay)")),
                    Map.entry("AttentionIconName", new Variant<>("")),
                    Map.entry("AttentionIconPixmap", new Variant<>(List.<SniPixmap>of(), "a(iiay)")),
                    Map.entry("OverlayIconName", new Variant<>("")),
                    Map.entry("OverlayIconPixmap", new Variant<>(List.<SniPixmap>of(), "a(iiay)")),
                    Map.entry("ToolTip", new Variant<>(tooltip, "(sa(iiay)ss)")),
                    Map.entry("ItemIsMenu", new Variant<>(false)),
                    Map.entry("IconThemePath", new Variant<>("")),
                    Map.entry("Menu", new Variant<>(new DBusPath(SniMenu.PATH))));
        }
    }
}
