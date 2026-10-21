package com.torrenta.ui;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.torrenta.engine.Engine;
import com.torrenta.engine.Engine.Capabilities;
import com.torrenta.engine.Engine.Capability;
import com.torrenta.engine.ShareLimit;

/**
 * What the window hides for an engine that lacks a capability: the one rule, pure, so the menus,
 * the toolbar, the palette, the Settings pages and the dialogs cannot disagree about it.
 *
 * <p>Hidden, never greyed out and never imitated: a command the engine cannot run is not offered,
 * a Settings page whose every row is for another engine is not listed, a dialog choice with no
 * effect is not shown.
 */
final class CapabilityRules {

    /** Commands that need a capability; any other command is for every engine. */
    private static final Map<String, Capability> COMMANDS = Map.ofEntries(
            Map.entry("create", Capability.CREATE_TORRENT),
            Map.entry("open-folder", Capability.LOCAL_FILES),
            Map.entry("sequential", Capability.SEQUENTIAL_DOWNLOAD),
            Map.entry("super-seed", Capability.SUPER_SEEDING),
            Map.entry("queue-top", Capability.QUEUE_ORDER),
            Map.entry("queue-up", Capability.QUEUE_ORDER),
            Map.entry("queue-down", Capability.QUEUE_ORDER),
            Map.entry("queue-bottom", Capability.QUEUE_ORDER),
            Map.entry("set-location", Capability.MOVE_STORAGE),
            Map.entry("export-torrent", Capability.EXPORT_TORRENT),
            Map.entry("rename", Capability.RENAME_TORRENT));

    /** Settings pages that need one of a set of capabilities; the rest are for every engine. */
    private static final Map<String, Set<Capability>> PAGES =
            Map.of("IP_FILTER", Set.of(Capability.IP_FILTER, Capability.BLOCKLIST_URL));

    private CapabilityRules() {}

    /** The capability a command needs, or empty when it works on every engine. */
    static Optional<Capability> forCommand(String id) {
        return Optional.ofNullable(COMMANDS.get(id));
    }

    static boolean commandShown(String id, Capabilities caps) {
        return forCommand(id).map(caps::has).orElse(true);
    }

    /** The commands this engine can run, in the given order. */
    static List<Command> commands(List<Command> all, Capabilities caps) {
        return all.stream().filter(c -> commandShown(c.id(), caps)).toList();
    }

    /** Whether a Settings page is listed at all; a page's rows have their own capability. */
    static boolean pageShown(String category, Capabilities caps) {
        Set<Capability> any = PAGES.get(category);
        if (any == null) return true;
        return any.stream().anyMatch(caps::has);
    }

    /** A row, tab or dialog choice tagged with a capability shows when the engine has it. */
    static boolean shown(Capability needed, Capabilities caps) {
        return needed == null || caps.has(needed);
    }

    /** The share-limit actions to offer: super seeding only where the engine can do it. */
    static List<ShareLimit.Action> shareActions(Capabilities caps) {
        return java.util.Arrays.stream(ShareLimit.Action.values())
                .filter(a -> a != ShareLimit.Action.SUPER_SEED || caps.has(Capability.SUPER_SEEDING))
                .toList();
    }

    /** The missing capabilities as one line, for the capture hook and the status line. */
    static String missingLine(Engine.Capabilities caps) {
        return caps.missing().stream()
                .map(Capability::name)
                .map(String::toLowerCase)
                .reduce((a, b) -> a + " " + b)
                .orElse("");
    }
}
