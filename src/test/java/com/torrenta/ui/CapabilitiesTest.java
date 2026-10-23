package com.torrenta.ui;

import java.util.List;
import java.util.Set;

import javafx.beans.property.SimpleBooleanProperty;

import com.torrenta.engine.Engine;
import com.torrenta.engine.Engine.Capabilities;
import com.torrenta.engine.Engine.Capability;
import com.torrenta.engine.ShareLimit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The record an engine answers with, and the window's one rule over what it hides. */
class CapabilitiesTest {

    private static Command command(String id) {
        return new Command(id, id, null, null, new SimpleBooleanProperty(true), () -> {});
    }

    @Test
    void theRecordIsASetWithNoSurprises() {
        assertTrue(Capabilities.ALL.has(Capability.CREATE_TORRENT));
        assertTrue(Capabilities.ALL.missing().isEmpty());
        Capabilities few = Capabilities.of(Capability.BLOCKLIST_URL, Capability.SEQUENTIAL_DOWNLOAD);
        assertTrue(few.has(Capability.BLOCKLIST_URL));
        assertTrue(few.lacks(Capability.CREATE_TORRENT));
        assertEquals(Capability.values().length - 2, few.missing().size());
        assertEquals(Capability.CREATE_TORRENT, few.missing().get(0), "in declaration order");
        Capabilities none = new Capabilities(Set.of());
        assertTrue(none.lacks(Capability.LOCAL_FILES));
        assertEquals(Capability.values().length, none.missing().size());
        Capabilities less = Capabilities.ALL.without(Capability.PROXY, Capability.SEED_MODE);
        assertTrue(less.lacks(Capability.PROXY));
        assertTrue(less.has(Capability.CREATE_TORRENT));
        assertEquals(Capabilities.of(Capability.PROXY), new Capabilities(Set.of(Capability.PROXY)));
        assertEquals(
                "create_torrent proxy",
                CapabilityRules.missingLine(Capabilities.ALL.without(Capability.PROXY, Capability.CREATE_TORRENT)));
        assertEquals("", CapabilityRules.missingLine(Capabilities.ALL));
    }

    @Test
    void queueMovesAndSetLocationHaveTheirOwnCapabilities() {
        for (String id : List.of("queue-top", "queue-up", "queue-down", "queue-bottom")) {
            assertEquals(Capability.QUEUE_ORDER, CapabilityRules.forCommand(id).orElseThrow(), id);
            assertFalse(CapabilityRules.commandShown(id, Capabilities.ALL.without(Capability.QUEUE_ORDER)), id);
        }
        assertEquals(
                Capability.MOVE_STORAGE,
                CapabilityRules.forCommand("set-location").orElseThrow());
        assertFalse(CapabilityRules.commandShown("set-location", Capabilities.ALL.without(Capability.MOVE_STORAGE)));
        assertTrue(CapabilityRules.commandShown("set-location", Capabilities.ALL));
    }

    @Test
    void superSeedingIsOfferedOnlyWhereTheEngineHasIt() {
        assertEquals(
                Capability.SUPER_SEEDING,
                CapabilityRules.forCommand("super-seed").orElseThrow());
        Capabilities daemon = Capabilities.ALL.without(Capability.SUPER_SEEDING);
        assertFalse(CapabilityRules.commandShown("super-seed", daemon));
        assertFalse(
                CapabilityRules.commandShown("export-torrent", Capabilities.ALL.without(Capability.EXPORT_TORRENT)));
        assertEquals(List.of(ShareLimit.Action.values()), CapabilityRules.shareActions(Capabilities.ALL));
        assertEquals(
                List.of(ShareLimit.Action.PAUSE, ShareLimit.Action.REMOVE, ShareLimit.Action.REMOVE_DATA),
                CapabilityRules.shareActions(daemon));
    }

    @Test
    void commandsBehindAMissingCapabilityAreNotOffered() {
        List<Command> all = List.of(
                command("create"),
                command("add-file"),
                command("sequential"),
                command("open-folder"),
                command("remove"));
        assertEquals(all, CapabilityRules.commands(all, Capabilities.ALL), "libtorrent shows everything");

        Capabilities transmission = Capabilities.of(Capability.BLOCKLIST_URL);
        assertEquals(
                List.of("add-file", "remove"),
                CapabilityRules.commands(all, transmission).stream()
                        .map(Command::id)
                        .toList(),
                "no creation, no sequential on an old daemon, no folder on another machine");

        Capabilities localDaemon =
                Capabilities.of(Capability.BLOCKLIST_URL, Capability.LOCAL_FILES, Capability.SEQUENTIAL_DOWNLOAD);
        assertEquals(
                List.of("add-file", "sequential", "open-folder", "remove"),
                CapabilityRules.commands(all, localDaemon).stream()
                        .map(Command::id)
                        .toList());

        assertEquals(
                Capability.CREATE_TORRENT, CapabilityRules.forCommand("create").orElseThrow());
        assertEquals(
                Capability.LOCAL_FILES,
                CapabilityRules.forCommand("open-folder").orElseThrow());
        assertEquals(
                Capability.SEQUENTIAL_DOWNLOAD,
                CapabilityRules.forCommand("sequential").orElseThrow());
        assertTrue(CapabilityRules.forCommand("pause-resume").isEmpty(), "every engine pauses");
        assertTrue(CapabilityRules.commandShown("settings", new Capabilities(Set.of())));
        assertFalse(CapabilityRules.commandShown("create", new Capabilities(Set.of())));
    }

    @Test
    void theIpFilterPageNeedsEitherKindOfFilterAndTheRestAreForEveryEngine() {
        assertTrue(CapabilityRules.pageShown("IP_FILTER", Capabilities.ALL));
        assertTrue(
                CapabilityRules.pageShown("IP_FILTER", Capabilities.of(Capability.BLOCKLIST_URL)),
                "a daemon with a blocklist URL keeps the page, with fewer rows");
        assertTrue(CapabilityRules.pageShown("IP_FILTER", Capabilities.of(Capability.IP_FILTER)));
        assertFalse(CapabilityRules.pageShown("IP_FILTER", Capabilities.of(Capability.PROXY)));
        for (SettingsWindow.Category c : SettingsWindow.Category.values()) {
            if (c != SettingsWindow.Category.IP_FILTER)
                assertTrue(CapabilityRules.pageShown(c.name(), new Capabilities(Set.of())), c.name());
        }
        assertTrue(
                CapabilityRules.shown(null, new Capabilities(Set.of())),
                "a row with no capability is for every engine");
        assertTrue(CapabilityRules.shown(Capability.PROXY, Capabilities.ALL));
        assertFalse(CapabilityRules.shown(Capability.PROXY, Capabilities.of(Capability.BLOCKLIST_URL)));
    }

    @Test
    void theEnginePageIsFirstInTheEngineGroupAndEveryCapabilityHasAName() {
        List<SettingsWindow.Category> engine = java.util.Arrays.stream(SettingsWindow.Category.values())
                .filter(c -> c.group == SettingsWindow.Group.ENGINE)
                .toList();
        assertEquals(SettingsWindow.Category.ENGINE_PROFILES, engine.get(0));
        for (Capability c : Capability.values()) {
            assertFalse(c.label().isBlank(), c.name());
            assertFalse(c.label().startsWith("capability."), c.name() + " has no catalogue entry");
        }
        assertEquals(Engine.Capabilities.ALL, Capabilities.ALL);
    }
}
