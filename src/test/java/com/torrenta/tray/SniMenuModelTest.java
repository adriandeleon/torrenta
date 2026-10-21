package com.torrenta.tray;

import java.util.Map;

import org.freedesktop.dbus.types.Variant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SniMenuModelTest {

    private static Object prop(int id, String name) {
        Map<String, Variant<?>> props = SniMenuModel.propsFor(id, "↓ 0 B/s  ↑ 0 B/s · No torrents");
        Variant<?> value = props.get(name);
        return value == null ? null : value.getValue();
    }

    /** GNOME shows SNI menus but never SNI tooltips, so the line lives in the menu, unclickable. */
    @Test
    void theStatusLineIsShownAndNotClickable() {
        assertEquals("↓ 0 B/s  ↑ 0 B/s · No torrents", prop(SniMenuModel.STATUS, "label"));
        assertEquals(false, prop(SniMenuModel.STATUS, "enabled"));
    }

    @Test
    void theItemsNameTheProgram() {
        assertEquals("Show Torrenta", prop(SniMenuModel.OPEN, "label"));
        assertEquals("Quit", prop(SniMenuModel.QUIT, "label"));
        assertEquals("separator", prop(SniMenuModel.SEPARATOR, "type"));
    }

    /** Ids are wire identifiers: a duplicate silently merges two items into one. */
    @Test
    void everyItemHasItsOwnIdAndAppearsInTheLayout() {
        assertEquals(
                SniMenuModel.ITEM_IDS.size(),
                SniMenuModel.ITEM_IDS.stream().distinct().count());
        assertFalse(SniMenuModel.ITEM_IDS.contains(SniMenuModel.ROOT));
        assertEquals(
                SniMenuModel.ITEM_IDS.size(),
                SniMenuModel.layout("line").children.size());
        assertEquals(
                "submenu",
                SniMenuModel.layout("line").properties.get("children-display").getValue());
    }

    /** Every item must carry something renderable, or the host draws a blank row. */
    @Test
    void everyItemHasALabelOrIsASeparator() {
        for (int id : SniMenuModel.ITEM_IDS) {
            Map<String, Variant<?>> props = SniMenuModel.propsFor(id, "line");
            boolean separator = "separator"
                    .equals(props.containsKey("type") ? props.get("type").getValue() : null);
            assertTrue(separator || props.containsKey("label"), "item " + id + " has neither label nor type");
            if (props.containsKey("label")) {
                assertNotNull(props.get("label").getValue());
                assertFalse(props.get("label").getValue().toString().isBlank());
            }
        }
    }

    /** The revision is what makes a panel re-read the labels: an unchanged line must not bump it. */
    @Test
    void theRevisionMovesOnlyWhenTheLineChanges() {
        SniMenu.Impl menu = new SniMenu.Impl(() -> {}, () -> {});
        assertEquals(-1, menu.setStatusLine("Starting…"), "the initial line");
        int first = menu.setStatusLine("↓ 1.0 MiB/s  ↑ 0 B/s · 1 downloading");
        assertTrue(first > 1);
        assertEquals(-1, menu.setStatusLine("↓ 1.0 MiB/s  ↑ 0 B/s · 1 downloading"));
        assertEquals(first + 1, menu.setStatusLine("↓ 2.0 MiB/s  ↑ 0 B/s · 1 downloading"));
    }

    /** Clicks reach the window's callbacks by id; anything but a click is ignored. */
    @Test
    void clicksDispatchByItem() {
        int[] opened = {0};
        int[] quit = {0};
        SniMenu.Impl menu = new SniMenu.Impl(() -> opened[0]++, () -> quit[0]++);
        menu.Event(SniMenuModel.OPEN, "clicked", new Variant<>(0), new org.freedesktop.dbus.types.UInt32(0));
        menu.Event(SniMenuModel.STATUS, "clicked", new Variant<>(0), new org.freedesktop.dbus.types.UInt32(0));
        menu.Event(SniMenuModel.QUIT, "hovered", new Variant<>(0), new org.freedesktop.dbus.types.UInt32(0));
        assertEquals(2, opened[0], "the status line opens too: it is the obvious thing to click");
        assertEquals(0, quit[0]);
        menu.Event(SniMenuModel.QUIT, "clicked", new Variant<>(0), new org.freedesktop.dbus.types.UInt32(0));
        assertEquals(1, quit[0]);
    }
}
