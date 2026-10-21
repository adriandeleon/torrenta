package com.torrenta.tray;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.freedesktop.dbus.types.Variant;

import static com.torrenta.i18n.Messages.tr;

/**
 * The pure model of the tray menu: fixed ids and each item's dbusmenu properties. A disabled live
 * status line (rates and counts), "Show Torrenta", a separator, "Quit".
 *
 * <p>The status line doubles as the tooltip GNOME never shows: its AppIndicator extension renders
 * SNI menus but not SNI tooltips, so the line is the one place the rates are sure to appear.
 */
final class SniMenuModel {

    static final int ROOT = 0;
    static final int STATUS = 1;
    static final int OPEN = 2;
    static final int SEPARATOR = 3;
    static final int QUIT = 4;

    static final List<Integer> ITEM_IDS = List.of(STATUS, OPEN, SEPARATOR, QUIT);

    private SniMenuModel() {}

    static Map<String, Variant<?>> propsFor(int id, String statusLine) {
        return switch (id) {
            case ROOT -> Map.of("children-display", new Variant<>("submenu"));
            case STATUS ->
                Map.of(
                        "label", new Variant<>(statusLine),
                        "enabled", new Variant<>(false));
            case OPEN -> Map.of("label", new Variant<>(tr("tray.menu.show")));
            case SEPARATOR -> Map.of("type", new Variant<>("separator"));
            case QUIT -> Map.of("label", new Variant<>(tr("tray.menu.quit")));
            default -> Map.of();
        };
    }

    /** The full layout tree (root + flat items) for GetLayout. */
    static MenuLayout layout(String statusLine) {
        List<Variant<?>> children = new ArrayList<>();
        for (int id : ITEM_IDS) {
            children.add(new Variant<>(item(id, statusLine), MenuLayout.SIGNATURE));
        }
        return new MenuLayout(ROOT, propsFor(ROOT, statusLine), List.copyOf(children));
    }

    static MenuLayout item(int id, String statusLine) {
        return new MenuLayout(id, propsFor(id, statusLine), List.of());
    }
}
