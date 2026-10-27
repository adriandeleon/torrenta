package com.torrenta.ui;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTablePosition;
import javafx.scene.control.TreeTableView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

import com.torrenta.engine.Engine;
import com.torrenta.engine.EngineEvent;
import com.torrenta.engine.FakeEngine;
import com.torrenta.engine.FoundFile;
import com.torrenta.engine.Priority;
import com.torrenta.engine.Torrent;
import com.torrenta.engine.TorrentDetail;
import com.torrenta.engine.TorrentState;
import com.torrenta.session.SpeedHistory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.torrenta.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The detail pane on its own, in a stage of its own, fed built details: what each tab draws, the
 * Overview's diagnosis cards and what their remedies do, and what the menus and dialogs hand the
 * window.
 */
@ExtendWith(FxToolkit.class)
class DetailPaneFxTest {

    private static final String ID = "aaaa000000000000000000000000000000000000";
    private static final long NOW = 1_790_010_000L;

    /** What the pane handed its owner, as {@code "<what> <argument>"}. */
    private final List<String> heard = new CopyOnWriteArrayList<>();

    private DetailPane mount(double width) {
        return Fx.call(() -> {
            DetailPane pane = new DetailPane();
            pane.setCommands(id -> Optional.of(new Command(
                    id,
                    id,
                    null,
                    "Ctrl+" + id.charAt(0),
                    new SimpleBooleanProperty(true),
                    () -> heard.add("run " + id))));
            pane.setOnFilePriority((i, p) -> heard.add("priority " + i + " " + p));
            pane.setOnOpenFile(r -> heard.add("open-file " + r.path()));
            pane.setOnOpenFolder(r -> heard.add("open-folder " + r.path()));
            pane.setOnStream(r -> heard.add("stream " + r.path()));
            pane.setOnCopyStream(r -> heard.add("copy-stream " + r.path()));
            pane.setOnRename((r, name) -> heard.add("rename " + r.path() + " " + name));
            pane.setOnAddTrackers(urls -> heard.add("add-trackers " + urls));
            pane.setOnAddWebSeeds(urls -> heard.add("add-web-seeds " + urls));
            pane.setOnRemoveWebSeed(url -> heard.add("remove-web-seed " + url));
            pane.setOnRemoveTracker(url -> heard.add("remove-tracker " + url));
            pane.setOnReannounce(() -> heard.add("reannounce"));
            pane.setOnBanPeer(a -> heard.add("ban " + a));
            pane.setOnCopyPeer(a -> heard.add("copy-peer " + a));
            pane.setOnCopyCommand(c -> heard.add("copy-command " + c));
            pane.setOnTabShown(t -> heard.add("tab " + t));
            Stage stage = new Stage();
            stage.setScene(new Scene(pane, width, 560));
            stage.show();
            return pane;
        });
    }

    private static Torrent stalled(String id) {
        return FakeEngine.torrent(id, "Album", TorrentState.DOWNLOADING, 0.5);
    }

    private static TorrentDetail.General general(String id, int pieces, int have) {
        return new TorrentDetail.General(
                id,
                "/data/music",
                true,
                pieces,
                1 << 20,
                have,
                0.5,
                1 << 29,
                1 << 27,
                4096,
                3600,
                0,
                90,
                3,
                "https://tracker.example.org/announce",
                id,
                "");
    }

    /** Two wanted files over eight pieces; the second one's last pieces are on no peer. */
    private static List<TorrentDetail.FileStatus> files() {
        return List.of(
                new TorrentDetail.FileStatus(0, "Album/01 Song.flac", 4 << 20, 4 << 20, Priority.NORMAL, 0, 3),
                new TorrentDetail.FileStatus(1, "Album/cd2/02 Song.flac", 4 << 20, 1 << 20, Priority.HIGH, 4, 7),
                new TorrentDetail.FileStatus(
                        2,
                        "Album/cover.jpg",
                        0,
                        0,
                        Priority.NORMAL,
                        -1,
                        -1,
                        new FoundFile(FoundFile.How.LINK, "Other")));
    }

    private static boolean[] have(int n, int upTo) {
        boolean[] have = new boolean[n];
        for (int i = 0; i < upTo; i++) have[i] = true;
        return have;
    }

    private static TorrentDetail detail(
            String id, List<TorrentDetail.Peer> peers, List<TorrentDetail.Tracker> trackers, int[] availability) {
        return new TorrentDetail(
                general(id, 8, 5),
                files(),
                peers,
                trackers,
                have(8, 5),
                availability,
                List.of("https://seed.example.org/files/"));
    }

    private static TorrentDetail.Peer peer(String address, String flags, long down) {
        return new TorrentDetail.Peer(address, "qBittorrent 5.1", flags, 0.4, down, 0, 1 << 20, 0);
    }

    private static TorrentDetail.Tracker tracker(String url, String status) {
        return new TorrentDetail.Tracker(url, 0, status, "", 0);
    }

    private static List<Node> remedies(DetailPane pane) {
        return Fx.call(() -> List.copyOf(pane.lookupAll(".remedy")));
    }

    private void click(Node remedy) {
        Dialogs.later(() -> remedy.getOnMouseClicked().handle(null));
    }

    @Test
    void anEmptyPaneSaysSoAndKeepsNoTorrent() {
        DetailPane pane = mount(500);
        Fx.run(() -> {
            assertEquals(List.of("empty"), pane.overviewLines());
            assertEquals("overview", pane.activeTab());
            assertTrue(pane.summary().startsWith("overview files=0 peers=0"), pane.summary());
            assertEquals(tr("detail.empty"), ((javafx.scene.control.Label) pane.placeholder()).getText());
            pane.setBeside(true);
            assertTrue(pane.getPseudoClassStates().stream()
                    .anyMatch(p -> p.getPseudoClassName().equals("right")));
        });
    }

    @Test
    void noPeersAndNoTrackersOffersTrackersWhichTheDialogHandsOn() {
        DetailPane pane = mount(500);
        Fx.run(() -> pane.show(detail(ID, List.of(), List.of(), new int[0]), stalled(ID), 300, NOW));
        Fx.pulse();
        List<String> lines = Fx.call(pane::overviewLines);
        assertTrue(lines.get(0).contains("Album"), lines.get(0));
        assertTrue(lines.get(0).endsWith("wide=false"), lines.get(0));
        assertTrue(lines.get(1).contains(tr("diagnosis.noTrackers")), lines.get(1));
        assertTrue(lines.get(2).startsWith("pieces="), lines.get(2));

        List<Node> rows = remedies(pane);
        assertEquals(1, rows.size());
        click(rows.get(0));
        Dialogs.answer(Dialogs.awaitPane("the Add Trackers dialog"), "udp://a.example:1 https://b.example/announce");
        Fx.waitUntil("the trackers to be handed on", () -> heard.stream().anyMatch(h -> h.startsWith("add-trackers")));
        assertTrue(heard.contains("add-trackers [udp://a.example:1, https://b.example/announce]"), heard.toString());
    }

    @Test
    void piecesOnNobodyInOneFileOfferToSkipThatFile() {
        DetailPane pane = mount(900);
        int[] availability = {1, 1, 1, 1, 1, 0, 0, 0};
        Fx.run(() -> pane.show(
                detail(ID, List.of(peer("10.0.0.2:6881", "D", 1000)), List.of(), availability), stalled(ID), 300, NOW));
        Fx.pulse();
        List<String> lines = Fx.call(pane::overviewLines);
        assertTrue(lines.get(0).endsWith("wide=true"), lines.get(0));
        assertTrue(lines.get(1).contains("SKIP_FILE"), lines.get(1));
        assertTrue(lines.get(1).contains("02 Song.flac"), lines.get(1));
        List<Node> rows = remedies(pane);
        assertEquals(2, rows.size());
        Fx.run(() -> rows.get(0).getOnMouseClicked().handle(null));
        assertTrue(heard.contains("priority 1 " + Priority.IGNORE), heard.toString());
    }

    @Test
    void anErrorOffersRetryAndOpenFolderWhichRunTheirCommands() {
        DetailPane pane = mount(500);
        Torrent broken = new Torrent(
                ID,
                "Album",
                1L << 30,
                1L << 29,
                0.5,
                TorrentState.DOWNLOADING,
                0,
                0,
                0,
                0,
                0,
                0,
                1_790_000_000L,
                0,
                "",
                "/data",
                false,
                false,
                0,
                "",
                List.of(),
                "disk full",
                List.of(),
                false);
        Fx.run(() -> pane.show(detail(ID, List.of(), List.of(), new int[0]), broken, 0, NOW));
        Fx.pulse();
        List<Node> rows = remedies(pane);
        assertEquals(2, rows.size());
        Fx.run(() -> {
            rows.get(0).getOnMouseClicked().handle(null);
            rows.get(1).getOnMouseClicked().handle(null);
        });
        assertEquals(List.of("run force-resume", "run open-folder"), commandsRun());

        // A daemon's folder is on its machine: the card leaves Open Folder out.
        Fx.run(() -> {
            pane.applyCapabilities(Engine.Capabilities.ALL.without(Engine.Capability.LOCAL_FILES));
            pane.clear();
            pane.show(detail(ID, List.of(), List.of(), new int[0]), broken, 0, NOW);
        });
        Fx.pulse();
        assertEquals(1, remedies(pane).size());
    }

    @Test
    void theOtherCardsAndAPendingRemedyPressedWhenOneOffersIt() {
        DetailPane pane = mount(500);
        // Asked before any card: pressed when the first one shows.
        Fx.run(() -> pane.pressRemedyForCapture(Diagnosis.Remedy.REANNOUNCE));
        // Peers, none sending: no remedy at all.
        Fx.run(() -> pane.show(
                detail(ID, List.of(peer("10.0.0.2:6881", "", 0)), List.of(), new int[0]), stalled(ID), 300, NOW));
        Fx.pulse();
        assertTrue(Fx.call(pane::overviewLines).get(1).contains("remedies=[]"));
        // A peer sending: quiet, Reannounce, which the pending press takes.
        Fx.run(() -> pane.show(
                detail(ID, List.of(peer("10.0.0.2:6881", "D", 10)), List.of(), new int[0]), stalled(ID), 300, NOW));
        Fx.waitUntil("the pending remedy to run", () -> commandsRun().contains("run reannounce"));

        // Any remedy: the first of the next card.
        Fx.run(() -> pane.pressRemedyForCapture(null));
        Fx.run(() -> pane.show(
                detail(ID, List.of(), List.of(tracker("https://t.example/a", "working")), new int[0]),
                stalled(ID),
                300,
                NOW));
        Fx.pulse();
        assertTrue(Fx.call(pane::overviewLines).get(1).contains(tr("diagnosis.nobodyListed")));
        // Queued, then waiting for its files to be chosen, then a locked archive.
        Torrent queued = new Torrent(
                ID,
                "Album",
                1L << 30,
                0,
                0,
                TorrentState.DOWNLOADING,
                0,
                0,
                0,
                0,
                0,
                0,
                1_790_000_000L,
                0,
                "",
                "/d",
                false,
                false,
                0,
                "",
                List.of(),
                "",
                List.of(),
                false,
                true,
                2,
                false,
                false);
        Fx.run(() -> pane.show(detail(ID, List.of(), List.of(), new int[0]), queued, 0, NOW));
        Fx.pulse();
        assertTrue(Fx.call(pane::overviewLines).get(1).contains("FORCE_RESUME"));
        Fx.run(() -> {
            pane.setFilesToChoose(id -> true);
            pane.show(detail(ID, List.of(), List.of(), new int[0]), stalled(ID), 0, NOW);
        });
        Fx.pulse();
        assertTrue(Fx.call(pane::overviewLines).get(1).contains("CHOOSE_FILES"));
        TorrentDetail locked = new TorrentDetail(
                general(ID, 8, 5), files(), List.of(), List.of(), have(8, 5), new int[0], List.of(), Set.of(1));
        Fx.run(() -> {
            pane.setFilesToChoose(id -> false);
            pane.show(locked, stalled(ID), 0, NOW);
        });
        Fx.pulse();
        assertTrue(Fx.call(pane::overviewLines).get(1).contains("REMOVE"));
    }

    @Test
    void theHeaderButtonsRunTheWindowsCommands() {
        DetailPane pane = mount(500);
        ContextMenu more = Fx.call(() -> new ContextMenu(new MenuItem("Pause")));
        Fx.run(() -> {
            pane.setMoreMenu(more);
            pane.show(detail(ID, List.of(), List.of(), new int[0]), FakeEngine.torrent(ID, "Album"), 0, NOW);
        });
        Fx.pulse();
        Fx.run(() -> {
            Dialogs.labeled(pane, Button.class, tr("detail.action.pause")).fire();
            Dialogs.labeled(pane, Button.class, tr("detail.action.reannounce")).fire();
            Dialogs.labeled(pane, Button.class, tr("detail.action.openFolder")).fire();
            Dialogs.labeled(pane, Button.class, "···").fire();
            Dialogs.all(pane, Hyperlink.class).stream()
                    .filter(h -> h.getParent() instanceof javafx.scene.layout.HBox)
                    .findFirst()
                    .orElseThrow()
                    .fire();
        });
        assertEquals(List.of("run pause-resume", "run reannounce", "run open-folder", "run copy-hash"), commandsRun());
        assertTrue(Fx.call(more::isShowing), "··· opens the Torrent menu");
        Fx.run(more::hide);

        // Paused, the first button resumes and the glyph swaps.
        Torrent paused = new Torrent(
                ID,
                "Album",
                1L << 30,
                1L << 29,
                0.5,
                TorrentState.DOWNLOADING,
                0,
                0,
                0,
                0,
                0,
                0,
                1_790_000_000L,
                0,
                "",
                "/data",
                true,
                false,
                0,
                "",
                List.of(),
                "",
                List.of(),
                false);
        Fx.run(() -> pane.show(detail(ID, List.of(), List.of(), new int[0]), paused, 0, NOW));
        assertTrue(Fx.call(pane::overviewLines).get(0).contains(tr("detail.action.resume")));
    }

    @Test
    void theFilesTabListsTheTreeAndItsMenuActsOnTheRow() {
        DetailPane pane = mount(700);
        Fx.run(() -> {
            pane.selectTab("files");
            pane.show(detail(ID, List.of(), List.of(), new int[0]), stalled(ID), 0, NOW);
        });
        Fx.pulse();
        List<String> lines = Fx.call(pane::fileLinesForCapture);
        assertEquals("Album [folder]", lines.get(0));
        assertTrue(lines.stream().anyMatch(l -> l.contains("cover.jpg") && l.contains("found=")), lines.toString());
        assertTrue(Fx.call(pane::summary).startsWith("files files=1"), Fx.call(pane::summary));

        TreeTableView<FileRows.Row> files = Fx.call(() -> treeTable(pane));
        // Row 1 is the first file, row 2 the folder cd2.
        Fx.run(() -> pane.showFilesMenuForCapture(1));
        Fx.pulse();
        ContextMenu menu = Fx.call(files::getContextMenu);
        Fx.run(() -> {
            assertTrue(menu.isShowing());
            for (MenuItem item : menu.getItems())
                if (item.isVisible()
                        && item.getText() != null
                        && !item.getText().equals(tr("detail.files.rename"))) item.fire();
            menu.hide();
        });
        assertTrue(heard.contains("open-file Album/01 Song.flac"), heard.toString());
        assertTrue(heard.contains("open-folder Album/01 Song.flac"), heard.toString());
        assertTrue(heard.contains("stream Album/01 Song.flac"), heard.toString());
        assertTrue(heard.contains("copy-stream Album/01 Song.flac"), heard.toString());

        // A folder: no Open File, no streaming; and with neither capability, only Rename.
        Fx.run(() -> pane.showFilesMenuForCapture(2));
        Fx.run(() -> {
            assertEquals(
                    List.of(tr("detail.files.openFolder"), tr("detail.files.rename")),
                    menu.getItems().stream()
                            .filter(i -> i.isVisible() && i.getText() != null)
                            .map(MenuItem::getText)
                            .toList());
            menu.hide();
            pane.applyCapabilities(Engine.Capabilities.ALL
                    .without(Engine.Capability.LOCAL_FILES)
                    .without(Engine.Capability.STREAMING));
        });
        Fx.run(() -> pane.showFilesMenuForCapture(1));
        Fx.run(() -> {
            assertEquals(
                    List.of(tr("detail.files.rename")),
                    menu.getItems().stream()
                            .filter(i -> i.isVisible() && i.getText() != null)
                            .map(MenuItem::getText)
                            .toList());
            menu.hide();
        });

        // Rename from the menu: the name typed, then handed on.
        Fx.run(() -> menu.getItems().stream()
                .filter(i -> tr("detail.files.rename").equals(i.getText()))
                .findFirst()
                .orElseThrow()
                .fire());
        Dialogs.answer(Dialogs.awaitPane("the Rename File dialog"), "01 Intro.flac");
        Fx.waitUntil("the rename", () -> heard.stream().anyMatch(h -> h.startsWith("rename ")));
        assertTrue(heard.contains("rename Album/01 Song.flac 01 Intro.flac"), heard.toString());

        // F2 on a folder; the name left as it was renames nothing.
        Fx.run(() -> {
            files.getSelectionModel().clearAndSelect(2);
            Event.fireEvent(files, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F2, false, false, false, false));
        });
        javafx.scene.control.DialogPane prompt = Dialogs.awaitPane("the Rename Folder dialog");
        Fx.run(() -> Dialogs.press(prompt, javafx.scene.control.ButtonBar.ButtonData.OK_DONE));
        Fx.waitUntil("the dialog to close", Dialogs::noPane);
        assertEquals(1, heard.stream().filter(h -> h.startsWith("rename ")).count());

        // The priority column's edit commit sets the file's priority.
        Fx.run(() -> {
            @SuppressWarnings("unchecked")
            TreeTableColumn<FileRows.Row, Priority> priority =
                    (TreeTableColumn<FileRows.Row, Priority>) files.getColumns().get(3);
            Event.fireEvent(
                    priority,
                    new TreeTableColumn.CellEditEvent<>(
                            files,
                            new TreeTablePosition<>(files, 1, priority),
                            TreeTableColumn.editCommitEvent(),
                            Priority.LOW));
        });
        assertTrue(heard.contains("priority 0 " + Priority.LOW), heard.toString());
    }

    @Test
    void theCaptureHooksWaitForTheFilesToArrive() {
        DetailPane pane = mount(700);
        // Before any rows: each waits for the tree to be rooted.
        Fx.run(() -> {
            pane.streamFileForCapture(1);
            pane.showFilesMenuForCapture(1);
        });
        Fx.run(() -> pane.show(detail(ID, List.of(), List.of(), new int[0]), stalled(ID), 0, NOW));
        Fx.pulse();
        assertTrue(heard.contains("stream Album/01 Song.flac"), heard.toString());
        Fx.run(() -> treeTable(pane).getContextMenu().hide());

        DetailPane other = mount(700);
        Fx.run(() -> other.renameFileForCapture(1, "renamed.flac"));
        Fx.run(() -> other.show(detail(ID, List.of(), List.of(), new int[0]), stalled(ID), 0, NOW));
        Fx.waitUntil("the capture's rename", () -> heard.contains("rename Album/01 Song.flac renamed.flac"));
    }

    @Test
    void thePeersTabsMenuBansAndCopiesThePeer() {
        DetailPane pane = mount(700);
        Fx.run(() -> {
            pane.show(
                    detail(
                            ID,
                            List.of(peer("10.0.0.2:6881", "D", 1000), peer("10.0.0.3:6881", "u", 0)),
                            List.of(),
                            new int[0]),
                    stalled(ID),
                    0,
                    NOW);
            pane.showPeerMenuForCapture();
        });
        Fx.pulse();
        assertEquals(
                List.of(tr("detail.peers.ban") + " [icon]", tr("detail.peers.copyAddress") + " [icon]"),
                Fx.call(pane::peerMenuLines));
        Fx.run(() -> {
            ContextMenu menu = peerTable(pane).getContextMenu();
            for (MenuItem item : menu.getItems()) item.fire();
            menu.hide();
            assertTrue(pane.banFirstPeerForCapture());
        });
        assertEquals(
                List.of("ban 10.0.0.2:6881", "copy-peer 10.0.0.2:6881", "ban 10.0.0.2:6881"),
                heardOf("ban", "copy-peer"));

        // No IP filter: Ban Peer goes; and with no peers there is no one to ban.
        Fx.run(() -> {
            pane.applyCapabilities(Engine.Capabilities.ALL.without(Engine.Capability.IP_FILTER));
            assertEquals(List.of(tr("detail.peers.copyAddress") + " [icon]"), pane.peerMenuLines());
            assertFalse(pane.banFirstPeerForCapture());
            peerTable(pane).getSelectionModel().clearSelection();
            pane.showPeerMenuForCapture();
            peerTable(pane).getContextMenu().hide();
        });
    }

    @Test
    void theTrackersTabAddsRemovesAndReannounces() {
        DetailPane pane = mount(700);
        List<TorrentDetail.Tracker> trackers = List.of(
                tracker("https://a.example/announce", "working"),
                tracker("https://b.example/announce", "updating"),
                tracker("udp://c.example:1", "not contacted"),
                tracker("udp://d.example:1", "error"),
                tracker("udp://e.example:1", "disabled"));
        Fx.run(() -> {
            pane.selectTab("trackers");
            pane.show(detail(ID, List.of(), trackers, new int[0]), stalled(ID), 0, NOW);
        });
        Fx.pulse();
        assertTrue(Fx.call(pane::summary).contains("trackers=5"));
        assertEquals("disabled", DetailPane.trackerStatus("disabled"));
        assertEquals(tr("detail.trackers.status.error"), DetailPane.trackerStatus("error"));

        // The same trackers with one changed: updated in place.
        List<TorrentDetail.Tracker> changed = new ArrayList<>(trackers);
        changed.set(0, new TorrentDetail.Tracker("https://a.example/announce", 0, "error", "timed out", 1));
        Fx.run(() -> pane.show(detail(ID, List.of(), changed, new int[0]), stalled(ID), 0, NOW));
        Fx.pulse();

        Fx.run(() -> {
            @SuppressWarnings("unchecked")
            TableView<TorrentDetail.Tracker> table =
                    (TableView<TorrentDetail.Tracker>) tables(pane).get(0);
            assertEquals("timed out", table.getItems().get(0).message());
            table.getSelectionModel().clearAndSelect(3);
            List<Button> buttons = Dialogs.all(pane, Button.class);
            buttons.stream()
                    .filter(b -> b.getText().equals(tr("common.remove")))
                    .findFirst()
                    .orElseThrow()
                    .fire();
            Dialogs.labeled(trackersTab(pane), Button.class, tr("detail.trackers.reannounce"))
                    .fire();
        });
        assertTrue(heard.contains("remove-tracker udp://d.example:1"), heard.toString());
        assertTrue(heard.contains("reannounce"), heard.toString());

        Button add = Fx.call(() -> Dialogs.labeled(pane, Button.class, tr("detail.trackers.add")));
        Dialogs.later(add::fire);
        Dialogs.answer(Dialogs.awaitPane("the Add Trackers dialog"), "https://f.example/announce");
        Fx.waitUntil("the tracker", () -> heard.contains("add-trackers [https://f.example/announce]"));

        // Cancelled, or nothing typed: nothing handed on.
        Dialogs.later(add::fire);
        javafx.scene.control.DialogPane cancelled = Dialogs.awaitPane("the Add Trackers dialog");
        Fx.run(() -> Dialogs.press(cancelled, javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE));
        Fx.waitUntil("the dialog to close", Dialogs::noPane);
        assertEquals(1, heard.stream().filter(h -> h.startsWith("add-trackers")).count());
    }

    @Test
    void theWebSeedsAreListedAddedAndRemoved() {
        DetailPane pane = mount(700);
        // Asked before the list arrives: removed once it has the row.
        Fx.run(() -> pane.removeWebSeedForCapture(0));
        Fx.run(() -> pane.show(detail(ID, List.of(), List.of(), new int[0]), stalled(ID), 0, NOW));
        Fx.pulse();
        assertTrue(heard.contains("remove-web-seed https://seed.example.org/files/"), heard.toString());
        assertEquals("[https://seed.example.org/files/] editable=true", Fx.call(pane::webSeedLineForCapture));
        Fx.run(() -> pane.removeWebSeedForCapture(0));
        assertEquals(
                2, heard.stream().filter(h -> h.startsWith("remove-web-seed")).count());

        // Changed seeds replace the list; Remove under it takes the selected one.
        TorrentDetail more = new TorrentDetail(
                general(ID, 8, 5),
                files(),
                List.of(),
                List.of(),
                have(8, 5),
                new int[0],
                List.of("https://one.example/", "https://two.example/"));
        Fx.run(() -> {
            pane.show(more, stalled(ID), 0, NOW);
            @SuppressWarnings("unchecked")
            TableView<String> seeds = (TableView<String>) tables(pane).get(1);
            seeds.getSelectionModel().clearAndSelect(1);
            Dialogs.all(pane, Button.class).stream()
                    .filter(b -> b.getText().equals(tr("common.remove")) && !b.isDisabled())
                    .reduce((a, b) -> b)
                    .orElseThrow()
                    .fire();
        });
        assertTrue(heard.contains("remove-web-seed https://two.example/"), heard.toString());

        Fx.run(() -> pane.addWebSeedsForCapture("https://three.example/ https://four.example/"));
        Fx.waitUntil(
                "the seeds typed into the dialog",
                () -> heard.contains("add-web-seeds [https://three.example/, https://four.example/]"));
        Fx.waitUntil("the dialog to close", Dialogs::noPane);

        Button add = Fx.call(() -> Dialogs.labeled(pane, Button.class, tr("detail.webSeeds.add")));
        Dialogs.later(add::fire);
        Dialogs.answer(Dialogs.awaitPane("the Add Web Seeds dialog"), "https://five.example/");
        Fx.waitUntil("the seed", () -> heard.contains("add-web-seeds [https://five.example/]"));

        Fx.run(() -> {
            pane.applyCapabilities(Engine.Capabilities.ALL.without(Engine.Capability.WEB_SEEDS));
            assertTrue(pane.webSeedLineForCapture().endsWith("editable=false"));
        });
    }

    @Test
    void theLogTabShowsTheTorrentsLinesByLevel() {
        DetailPane pane = mount(700);
        ObservableList<EventLog.Line> events = FXCollections.observableArrayList();
        Fx.run(() -> {
            EventLog log = new EventLog();
            log.add(EngineEvent.info(ID, "Album", "Added"));
            log.add(EngineEvent.warning(ID, "Album", "Tracker slow"));
            log.add(EngineEvent.error(ID, "Album", "Disk full"));
            log.add(EngineEvent.info("bbbb", "Other", "Someone else's"));
            events.setAll(log.events());
            pane.setEvents(events);
            pane.selectTab("log");
            pane.show(detail(ID, List.of(), List.of(), new int[0]), stalled(ID), 0, NOW);
        });
        Fx.pulse();
        assertTrue(Fx.call(pane::overviewLines).contains("log=3"));
        Fx.run(() -> {
            ListView<?> list = Dialogs.all(pane, ListView.class).get(0);
            assertEquals(3, list.getItems().size());
            assertEquals(1, list.lookupAll(".log-error").size(), "the error line is styled as one");
            assertEquals(1, list.lookupAll(".log-warning").size());
        });
        Fx.run(pane::clear);
        Fx.pulse();
        assertEquals(List.of("empty"), Fx.call(pane::overviewLines));
    }

    @Test
    void theSpeedTabAndTheOverviewDrawTheHistory() {
        DetailPane pane = mount(900);
        SpeedHistory history = new SpeedHistory();
        for (int i = 0; i < 120; i++) history.add(NOW - 120 + i, 1000L * i, 100L * i);
        List<Integer> windows = new CopyOnWriteArrayList<>();
        Fx.run(() -> {
            pane.setOnSpeedWindowChanged(windows::add);
            pane.show(detail(ID, List.of(), List.of(), new int[0]), FakeEngine.torrent(ID, "Album"), 0, NOW);
            pane.showSpeed(history, NOW);
            pane.selectTab("speed");
            pane.showSpeed(history, NOW);
        });
        Fx.pulse();
        String summary = Fx.call(pane::speedSummaryForCapture);
        assertTrue(summary.startsWith("window=600 points="), summary);
        Fx.run(() -> pane.setSpeedWindow(60));
        assertEquals(List.of(60), windows);
        assertTrue(Fx.call(pane::speedSummaryForCapture).startsWith("window=60 "));
        Fx.run(() -> {
            pane.selectTab("overview");
            pane.showSpeed(null, NOW);
        });
        Fx.pulse();
    }

    @Test
    void aDownloadFromALinkShowsItsSourceAndCommandInPlaceOfTheSwarm() {
        DetailPane pane = mount(700);
        String id = "url-0123456789abcdef0123456789abcdef01234567";
        Torrent link = FakeEngine.torrent(id, "file.iso");
        TorrentDetail detail = new TorrentDetail(
                new TorrentDetail.General(
                        id, "/data", false, 0, 0, 0, -1, 0, 0, 0, 10, 0, 0, 0, "https://example.org/file.iso", "", ""),
                List.of(new TorrentDetail.FileStatus(0, "file.iso", 100, 50, Priority.NORMAL)),
                List.of(),
                List.of(),
                new boolean[0],
                new int[0],
                List.of(),
                Set.of(),
                "curl -O https://example.org/file.iso");
        Fx.run(() -> {
            pane.selectTab("trackers");
            pane.show(detail, link, 0, NOW);
        });
        Fx.pulse();
        assertEquals("overview", Fx.call(pane::activeTab));
        assertEquals("trackers", Fx.call(pane::rememberedTab), "the person's choice is kept");
        assertTrue(Fx.call(pane::summary).endsWith("peersTrackers=off"));
        assertTrue(Fx.call(pane::overviewLines).contains("command=curl -O https://example.org/file.iso"));
        Fx.run(() -> Dialogs.all(pane, Hyperlink.class).stream()
                .filter(Node::isVisible)
                .filter(h -> h.getParent() instanceof javafx.scene.layout.VBox)
                .findFirst()
                .orElseThrow()
                .fire());
        assertTrue(heard.contains("copy-command curl -O https://example.org/file.iso"), heard.toString());
        assertEquals("overview", DetailPane.tabFor("peers", true));
        assertEquals("peers", DetailPane.tabFor("peers", false));
    }

    @Test
    void simpleModeKeepsTheOverviewAndFilesAndBringsTheChoiceBack() {
        DetailPane pane = mount(700);
        Fx.run(() -> {
            pane.selectTab("peers");
            pane.setSimple(true);
            pane.setSimple(true);
            assertEquals(SimpleMode.detailTabs(DetailPane.TABS, true), pane.shownTabs());
            assertEquals("overview", pane.activeTab());
            pane.selectTab("trackers");
            assertEquals("overview", pane.activeTab(), "a tab out of the strip cannot be chosen");
            pane.setSimple(false);
            assertEquals(DetailPane.TABS, pane.shownTabs());
            assertEquals("peers", pane.activeTab());
            pane.selectTab("pieces");
            assertEquals("overview", pane.activeTab(), "a remembered Pieces tab opens the Overview");
            pane.selectTab("nonsense");
        });
    }

    @Test
    void aDetailWithoutItsPartsKeepsWhatShowedOrBlanksAnotherTorrent() {
        DetailPane pane = mount(700);
        Fx.run(() ->
                pane.show(detail(ID, List.of(peer("10.0.0.2:1", "D", 1)), List.of(), new int[0]), stalled(ID), 0, NOW));
        TorrentDetail.General g = general(ID, 8, 5);
        TorrentDetail bare = new TorrentDetail(
                g,
                List.of(),
                List.of(),
                List.of(),
                new boolean[0],
                new int[0],
                List.of(),
                Set.of(),
                "",
                EnumSet.noneOf(TorrentDetail.Part.class),
                3);
        Fx.run(() -> pane.show(bare, stalled(ID), 0, NOW));
        assertTrue(Fx.call(pane::summary).contains("files=1 peers=1"), Fx.call(pane::summary));

        // Another torrent with nothing read: the lists empty, the counts from its row.
        String other = "bbbb000000000000000000000000000000000000";
        TorrentDetail.General og = general(other, 0, 0);
        Fx.run(() -> {
            pane.show(
                    new TorrentDetail(
                            og,
                            List.of(),
                            List.of(),
                            List.of(),
                            new boolean[0],
                            new int[0],
                            List.of(),
                            Set.of(),
                            "",
                            Set.of(),
                            -1),
                    FakeEngine.torrent(other, "Other"),
                    0,
                    NOW);
            assertTrue(pane.summary().contains("files=0 peers=0 trackers=0 pieces=0/0"), pane.summary());
            assertTrue(
                    pane.overviewLines().get(2).startsWith("pieces=—"),
                    pane.overviewLines().get(2));
            // No row yet: the header waits a tick, the counts say nothing.
            pane.show(
                    new TorrentDetail(
                            general(ID, 0, 0),
                            List.of(),
                            List.of(),
                            List.of(),
                            new boolean[0],
                            new int[0],
                            List.of(),
                            Set.of(),
                            "",
                            Set.of(),
                            -1),
                    null,
                    0,
                    NOW);
        });
        // The Overview's own parts read with no pieces: dashes.
        TorrentDetail noPieces = new TorrentDetail(
                general(other, 0, 0),
                files(),
                List.of(),
                List.of(),
                new boolean[0],
                new int[0],
                List.of(),
                Set.of(),
                "",
                DetailParts.OVERVIEW,
                -1);
        Fx.run(() -> pane.show(noPieces, FakeEngine.torrent(other, "Other", TorrentState.SEEDING, 1), 0, NOW));
        assertTrue(Fx.call(pane::overviewLines).get(1).equals("diagnosis=none"));
    }

    private List<String> commandsRun() {
        return heard.stream().filter(h -> h.startsWith("run ")).toList();
    }

    private List<String> heardOf(String... verbs) {
        return heard.stream()
                .filter(h -> java.util.Arrays.stream(verbs).anyMatch(v -> h.startsWith(v + " ")))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static TreeTableView<FileRows.Row> treeTable(DetailPane pane) {
        return (TreeTableView<FileRows.Row>)
                Dialogs.all(pane, TreeTableView.class).get(0);
    }

    @SuppressWarnings("unchecked")
    private static TableView<PeerRows.Row> peerTable(DetailPane pane) {
        return (TableView<PeerRows.Row>) tables(pane).stream()
                .filter(t -> t.getContextMenu() != null)
                .findFirst()
                .orElseThrow();
    }

    private static javafx.scene.Parent trackersTab(DetailPane pane) {
        return (javafx.scene.Parent)
                Dialogs.all(pane, TabPane.class).get(0).getTabs().get(3).getContent();
    }

    /** The Trackers tab's two tables, then the Peers table: by what they hold. */
    private static List<TableView<?>> tables(DetailPane pane) {
        TabPane tabs = Dialogs.all(pane, TabPane.class).get(0);
        List<TableView<?>> found = new ArrayList<>();
        Node trackers = tabs.getTabs().get(3).getContent();
        for (TableView<?> t : Dialogs.all((javafx.scene.Parent) trackers, TableView.class))
            if (!found.contains(t)) found.add(t);
        for (TableView<?> t :
                Dialogs.all((javafx.scene.Parent) tabs.getTabs().get(2).getContent(), TableView.class))
            if (!found.contains(t)) found.add(t);
        return found;
    }
}
