package com.torrenta.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Labeled;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableView;
import javafx.scene.input.ClipboardContent;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.torrenta.engine.Engine;
import com.torrenta.engine.FakeEngine;
import com.torrenta.engine.Priority;
import com.torrenta.engine.ShareLimit;
import com.torrenta.engine.TorrentDetail;
import com.torrenta.engine.TorrentPreview;
import com.torrenta.store.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static com.torrenta.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The main window's less travelled paths on a fake engine: an engine that lacks what the window
 * offers elsewhere, refusals, the clipboard, saved views edited from the sidebar, and the detail
 * pane's own buttons and menus.
 */
@ExtendWith(FxToolkit.class)
class MainWindowEdgesTest {

    private static final String MAGNET = "magnet:?xt=urn:btih:" + "3".repeat(40) + "&dn=Three";

    @AfterEach
    void noAnswerLeftOver() {
        RemoveConfirm.answerForCapture = null;
        Fx.run(() -> javafx.scene.input.Clipboard.getSystemClipboard().clear());
    }

    private static FakeEngine oneTorrent() {
        return new FakeEngine().put(FakeEngine.torrent("aaaa", "Film"));
    }

    private static boolean anyCall(FakeEngine engine, String prefix) {
        return engine.calls().stream().anyMatch(c -> c.startsWith(prefix));
    }

    private static void clipboard(String text) {
        Fx.run(() -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(text);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        });
    }

    /** The first node under the main window's root that is a button with this text. */
    private static Button button(WindowFixture f, String text) {
        return Fx.call(() -> {
            for (Node n : f.stage.getScene().getRoot().lookupAll(".button")) {
                if (n instanceof Button b && text.equals(b.getText()) && b.isVisible()) return b;
            }
            throw new AssertionError("no button " + text);
        });
    }

    /** The item of a showing popup menu with this text, fired. */
    private static void fireShownItem(String text) {
        Fx.run(() -> {
            for (Window w : List.copyOf(Window.getWindows())) {
                if (!(w instanceof ContextMenu menu) || !menu.isShowing()) continue;
                for (MenuItem item : menu.getItems()) {
                    if (text.equals(item.getText()) && item.isVisible()) {
                        item.fire();
                        menu.hide();
                        return;
                    }
                }
            }
            throw new AssertionError("no item " + text + " in a menu showing");
        });
        Fx.pulse();
    }

    @Test
    void theAddMagnetPromptAddsWhatIsTyped(@TempDir Path dir) {
        FakeEngine engine = oneTorrent();
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            Fx.run(() -> f.settings.setAddWithoutAsking(true));
            f.actionLater("add-magnet");
            f.pressDefault(tr("window.addMagnet.title"), MAGNET);
            Fx.waitUntil("the add", () -> anyCall(engine, "add "));
        }
    }

    @Test
    void theClipboardIsOfferedAndAddedFrom(@TempDir Path dir) {
        FakeEngine engine = oneTorrent();
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            Fx.run(() -> javafx.scene.input.Clipboard.getSystemClipboard().clear());
            f.action("add-clipboard");
            assertEquals(tr("status.clipboardEmpty"), f.status());
            clipboard(MAGNET);
            // The window coming to the front offers what the clipboard holds, once.
            Fx.run(() -> {
                f.stage.hide();
                f.stage.show();
                f.stage.requestFocus();
            });
            f.action("add-clipboard");
            f.awaitWindow(tr("dialog.add.title.magnet"));
            // While a dialog is open the chord does nothing more.
            f.action("add-clipboard");
            f.cancelDialogs();
            clipboard("https://example.org/files/disc.iso");
            Fx.run(() -> f.stage.requestFocus());
            f.action("welcome-clipboard:" + MAGNET);
        }
    }

    @Test
    void theAddDialogsKeysButtonsAndAskNoMore(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("three.torrent");
        Files.write(file, new byte[] {'d', 'e'});
        TorrentPreview preview = new TorrentPreview(
                "3".repeat(40),
                "Three",
                300,
                List.of(
                        new TorrentPreview.FileEntry(0, "Three/film.mkv", 200),
                        new TorrentPreview.FileEntry(1, "Three/setup.exe", 100)),
                false,
                List.of(),
                "",
                "",
                0,
                true,
                false);
        FakeEngine engine = oneTorrent().withPreview(preview);
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            Fx.run(() -> f.window.addInput(file.toString()));
            Stage dialog = f.awaitWindow(tr("dialog.add.title.torrent"));
            f.action("dialog-key:DOWN");
            f.action("dialog-key:SPACE");
            f.action("content-skip");
            f.action("dialog-category:");
            assertTrue(Fx.call(f.window::lateLinesForCapture).stream().anyMatch(l -> l.startsWith("dialog-fit=")));
            Fx.run(() -> {
                for (Node n : dialog.getScene().getRoot().lookupAll(".check-box")) {
                    if (n instanceof CheckBox c
                            && tr("dialog.add.withoutAsking").equals(c.getText())) c.setSelected(true);
                }
            });
            f.action("dialog-press:paused");
            Fx.waitUntil("the add", () -> anyCall(engine, "add TorrentFile"));
            Fx.waitUntil("adding without asking from now on", () -> f.settings.addWithoutAsking());
            assertTrue(Fx.call(f.window::eventLines).stream().anyMatch(l -> l.contains(tr("log.addWithoutAsking"))));
        }
    }

    @Test
    void aProbeThatArrivesAfterTheDialogClosedIsLetGo(@TempDir Path dir) {
        FakeEngine.Probe probe = new FakeEngine.Probe(null, 0);
        java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
        FakeEngine engine = oneTorrent().withProbes(source -> {
            try {
                closed.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return probe;
        });
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            Fx.run(() -> f.window.addInput(MAGNET));
            f.awaitWindow(tr("dialog.add.title.magnet"));
            f.cancelDialogs();
            closed.countDown();
            Fx.waitUntil("the late probe cancelled", probe::cancelled);
        }
    }

    @Test
    void aMagnetsFailedMetadataIsSaid(@TempDir Path dir) {
        FakeEngine.Probe probe = new FakeEngine.Probe(null, 0);
        FakeEngine engine = oneTorrent().withProbes(source -> probe);
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            Fx.run(() -> f.window.addInput(MAGNET));
            f.awaitWindow(tr("dialog.add.title.magnet"));
            Fx.waitUntil("the probe asked", () -> anyCall(engine, "probe "));
            probe.torrent().completeExceptionally(new IllegalStateException("no metadata today"));
            Fx.pulse();
            // The fake reads no .torrent: metadata that came is then said not to read.
            f.cancelDialogs();
        }
    }

    @Test
    void anEngineWithoutTheExtrasHidesThemAndRemovesForGood(@TempDir Path dir) {
        FakeEngine engine = oneTorrent()
                .withCapabilities(Engine.Capabilities.ALL.without(
                        Engine.Capability.LOCAL_FILES,
                        Engine.Capability.UNDO_REMOVE,
                        Engine.Capability.METADATA_FETCH,
                        Engine.Capability.IP_FILTER,
                        Engine.Capability.SEQUENTIAL_DOWNLOAD,
                        Engine.Capability.LISTEN_DETAILS));
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            assertFalse(Fx.call(f.window::capabilitiesLine).isEmpty());
            f.action("select-first");
            f.action("omnibox:>sequential~on");
            f.action("focus-key:ENTER");
            assertTrue(f.status().contains("sequential"), f.status());
            f.action("settings-page:IP_FILTER");
            f.action("settings-close");
            Fx.run(() -> f.window.addInput(MAGNET));
            f.awaitWindow(tr("dialog.add.title.magnet"));
            f.cancelDialogs();
            f.action("select-first");
            f.action("remove-confirmed");
            f.awaitCall("remove aaaa false");
            Fx.waitUntil("the list empty", () -> f.rows() == 0);
        }
    }

    @Test
    void aTypedCommandOverTwentyTorrentsAsksFirst(@TempDir Path dir) {
        FakeEngine engine = new FakeEngine();
        for (int i = 0; i < 21; i++) engine.put(FakeEngine.torrent("t%03d".formatted(i), "Torrent " + i));
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            f.action("select-all");
            RemoveConfirm.answerForCapture = false;
            f.action("omnibox:>pause");
            f.action("focus-key:ENTER");
            assertFalse(anyCall(engine, "pause "), "a no leaves them");
            f.action("select-all");
            RemoveConfirm.answerForCapture = true;
            f.action("omnibox:>pause");
            f.action("focus-key:ENTER");
            Fx.waitUntil(
                    "all paused",
                    () -> engine.calls().stream()
                                    .filter(c -> c.startsWith("pause "))
                                    .count()
                            == 21);
        }
    }

    @Test
    void aRemovalForgetsTheTorrentsOwnShareLimitAndUndoBringsItBack(@TempDir Path dir) {
        FakeEngine engine = oneTorrent();
        ShareLimit own = new ShareLimit(2.0, 60, ShareLimit.Action.PAUSE);
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            Fx.run(() -> f.settings.setShareOverride("aaaa", own));
            f.action("select-first");
            f.action("remove-confirmed");
            f.awaitCall("detach aaaa");
            Fx.waitUntil(
                    "its limit forgotten",
                    () -> f.settings.shareOverride("aaaa").isEmpty());
            f.action("undo-after:10");
            f.awaitCall("reattach aaaa");
            Fx.waitUntil(
                    "its limit back", () -> f.settings.shareOverride("aaaa").isPresent());
        }
    }

    @Test
    void anUndoTheEngineRefusesIsSaid(@TempDir Path dir) {
        FakeEngine engine =
                oneTorrent().put(FakeEngine.torrent("bbbb", "Album")).refuse("reattach");
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            f.action("select-first");
            f.action("remove-confirmed");
            Fx.waitUntil("a detach", () -> anyCall(engine, "detach "));
            f.action("undo");
            f.awaitStatus("refuses reattach");
            Fx.waitUntil("the other one alone in the list", () -> f.rows() == 1);
            // A refused part of an undoable action is counted out of it.
            engine.allow("reattach").refuse("setCategory");
            f.action("select-first");
            f.action("omnibox:>set-category~Films");
            f.action("focus-key:ENTER");
            f.awaitStatus("refuses setCategory");
        }
    }

    @Test
    void chooseFilesWithNoFileListOrARefusalSaysSo(@TempDir Path dir) {
        FakeEngine engine = oneTorrent();
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            Fx.run(() -> f.window.chooseFiles("aaaa"));
            f.awaitStatus(tr("status.noFileList"));
            engine.refuse("detail");
            Fx.run(() -> f.window.chooseFiles("aaaa"));
            f.awaitStatus("refuses detail");
        }
    }

    @Test
    void aSavedViewIsRenamedRepointedAndDeletedFromTheSidebar(@TempDir Path dir) {
        try (WindowFixture f = WindowFixture.open(dir, MainWindowCommandsTest.twoTorrents())) {
            f.action("omnibox:state:seeding");
            f.action("omnibox:state:seeding~");
            f.action("save-view:Seeds");
            Fx.waitUntil("the view saved", () -> f.settings.savedViews().size() == 1);
            fireViewItem(f, tr("sidebar.view.rename"));
            f.pressDefault(tr("window.renameView.title"), "Finished");
            Fx.waitUntil(
                    "renamed", () -> f.settings.savedViews().getFirst().name().equals("Finished"));
            f.action("omnibox:state:downloading");
            f.action("omnibox:state:downloading~");
            fireViewItem(f, tr("sidebar.view.useFilter"));
            Fx.waitUntil(
                    "re-pointed",
                    () -> f.settings.savedViews().getFirst().query().contains("downloading"));
            RemoveConfirm.answerForCapture = true;
            fireViewItem(f, tr("sidebar.view.delete"));
            Fx.waitUntil("deleted", () -> f.settings.savedViews().isEmpty());
            Fx.waitUntil("both rows again", () -> f.rows() == 2);
        }
    }

    /** The saved view row's context menu item with this text, fired as a click on it does. */
    private static void fireViewItem(WindowFixture f, String text) {
        Fx.run(() -> {
            for (Node n : f.stage.getScene().getRoot().lookupAll(".cell")) {
                if (n instanceof javafx.scene.control.Cell<?> cell && cell.getContextMenu() != null) {
                    ContextMenu menu = cell.getContextMenu();
                    if (menu.getOnShowing() != null) menu.getOnShowing().handle(null);
                    for (MenuItem item : menu.getItems()) {
                        if (text.equals(item.getText())) {
                            item.fire();
                            return;
                        }
                    }
                }
            }
            throw new AssertionError("no saved view row with " + text);
        });
        Fx.pulse();
    }

    @Test
    void theDetailPanesButtonsAndMenusReachTheEngine(@TempDir Path dir) throws Exception {
        FakeEngine engine = oneTorrent()
                .withFiles(
                        "aaaa",
                        List.of(
                                new TorrentDetail.FileStatus(0, "Film/film.mkv", 200, 100, Priority.NORMAL, 0, 40),
                                new TorrentDetail.FileStatus(1, "Film/extras.mkv", 100, 0, Priority.NORMAL, 41, 63)))
                .withPeers(
                        "aaaa",
                        List.of(new TorrentDetail.Peer("203.0.113.9:6881", "Deluge", "D", 0.5, 1000, 0, 5000, 0)));
        String player = Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        System.getProperty("os.name", "")
                                        .toLowerCase(java.util.Locale.ROOT)
                                        .startsWith("windows")
                                ? "java.exe"
                                : "java")
                .toString();
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            // The player is this JVM's own java: it takes the URL as a class name and leaves.
            Fx.run(() -> f.settings.set(Settings.STREAM_PLAYER, player));
            f.action("select-first");
            f.action("detail:files");
            Fx.waitUntil(
                    "the files shown",
                    () -> f.window.lateLinesForCapture().stream().anyMatch(l -> l.startsWith("file=")));
            f.action("files-menu:1");
            fireShownItem(tr("detail.files.openFile"));
            assertTrue(f.opened.stream().anyMatch(u -> u.endsWith("film.mkv")), f.opened.toString());
            f.action("files-menu:1");
            fireShownItem(tr("detail.files.openContainingFolder"));
            Fx.waitUntil("the folder opened", () -> f.opened.size() >= 2);
            f.action("files-menu:1");
            fireShownItem(tr("detail.files.copyStream"));
            f.awaitStatus(tr("status.streamCopied", "film.mkv"));
            f.action("files-stream:1");
            // The copied URL's line names the file too: wait for the player's own, which is found off
            // the FX thread (slowly on Windows) and would otherwise land over a later line.
            String copied = tr("status.streamCopied", "film.mkv");
            Fx.waitUntil("the player run", () -> {
                String now = f.window.statusText();
                return now.contains("film.mkv") && !now.equals(copied);
            });
            // Trackers: reannounce, remove the selected one, add more through the prompt.
            f.action("detail:trackers");
            Fx.waitUntil("the trackers listed", () -> trackerTable(f) != null);
            Fx.run(() -> button(f, tr("detail.trackers.reannounce")).fire());
            f.awaitCall("reannounce aaaa");
            Fx.run(() -> trackerTable(f).getSelectionModel().select(0));
            Fx.run(() -> button(f, tr("common.remove")).fire());
            f.awaitCall("removeTracker aaaa https://tracker.example.org/announce");
            Button add = button(f, tr("detail.trackers.add"));
            Platform.runLater(add::fire);
            f.pressDefault(tr("detail.trackers.addDialog.title"), "https://t2.example.org/announce");
            f.awaitCall("addTrackers aaaa [https://t2.example.org/announce]");
            f.awaitStatus(tr("status.trackersAdded", "Film"));
            // Peers: copy an address from the peer's menu.
            f.action("peer-menu");
            Fx.waitUntil("the peer menu", () -> !f.window.menuLines().isEmpty());
            fireShownItem(tr("detail.peers.copyAddress"));
            assertEquals(tr("status.copied.address", "203.0.113.9:6881"), f.status());
            // A refused detail call is said.
            engine.refuse("renamePath");
            f.action("files-rename:1:other.mkv");
            f.awaitStatus("refuses rename");
        }
    }

    private static TableView<?> trackerTable(WindowFixture f) {
        for (Node n : f.stage.getScene().getRoot().lookupAll(".table-view")) {
            if (n instanceof TableView<?> t
                    && t.isVisible()
                    && !t.getItems().isEmpty()
                    && t.getItems().get(0) instanceof TorrentDetail.Tracker) return t;
        }
        return null;
    }

    @Test
    void theOpenDialogsAreDescribedForTheCapture(@TempDir Path dir) {
        FakeEngine engine = oneTorrent();
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            f.action("select-first");
            f.action("set-location:" + dir);
            f.awaitWindow(tr("dialog.location.title", "Film"));
            assertTrue(Fx.call(f.window::lateLinesForCapture).stream().anyMatch(l -> l.startsWith("location-hint=")));
            assertTrue(Fx.call(() -> f.window.captureScene() != f.stage.getScene()));
            f.cancelDialogs();
            Fx.run(() -> f.window.addInput("https://example.org/files/disc.iso"));
            f.awaitWindow(tr("dialog.link.title"));
            assertTrue(Fx.call(f.window::lateLinesForCapture).stream().anyMatch(l -> l.startsWith("link-dialog=")));
            assertTrue(Fx.call(() -> f.window.captureScene() != f.stage.getScene()));
            f.cancelDialogs();
            f.action("limits");
            f.awaitWindow(tr("dialog.limits.title", "Film"));
            f.action("select-first");
            assertTrue(Fx.call(() -> f.window.captureScene() != f.stage.getScene()));
            f.cancelDialogs();
            f.action("edit-tags");
            f.awaitWindow(tr("dialog.tags.title", "Film"));
            assertTrue(Fx.call(() -> f.window.captureScene() != f.stage.getScene()));
            f.cancelDialogs();
            f.action("set-category");
            f.awaitWindow(tr("window.category.title"));
            f.action("select-first");
            assertTrue(Fx.call(() -> f.window.captureScene() != f.stage.getScene()));
            f.cancelDialogs();
            f.action("import-from:" + dir);
            f.awaitWindow(tr("dialog.import.title"));
            assertTrue(Fx.call(() -> f.window.captureScene() != f.stage.getScene()));
            f.cancelDialogs();
            f.action("create");
            assertTrue(Fx.call(() -> f.window.captureScene() != f.stage.getScene()));
            f.cancelDialogs();
            f.action("context-menu");
            assertTrue(Fx.call(() -> f.window.captureScene() != f.stage.getScene()));
            Fx.closeAll();
            Fx.run(() -> f.stage.show());
        }
    }

    @Test
    void anOmniboxLinkTypedAKeyAtATimeIsAddedOnEnter(@TempDir Path dir) {
        FakeEngine engine = oneTorrent();
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            f.action("omnibox:" + MAGNET.substring(0, MAGNET.length() - 1));
            f.action("omnibox:" + MAGNET);
            f.action("omnibox-key:ENTER");
            f.awaitWindow(tr("dialog.add.title.magnet"));
            f.cancelDialogs();
            // Escape on the list clears the filter first.
            f.action("omnibox:film");
            f.action("table-key:ESCAPE");
            assertTrue(Fx.call(() -> f.window.lateLinesForCapture().stream()
                    .anyMatch(l -> l.startsWith("table=") && l.contains("shown=1"))));
        }
    }

    @Test
    void theStatisticsWindowAndAnIconifiedWindowStillCount(@TempDir Path dir) {
        try (WindowFixture f = WindowFixture.open(dir, oneTorrent())) {
            f.action("statistics");
            f.action("iconify");
            Fx.waitUntil("a tick skipped", () -> !f.window.pollDescription().contains("skipped=0"));
            f.action("deiconify");
            assertTrue(Fx.call(() -> labelled(f)));
        }
    }

    private static boolean labelled(WindowFixture f) {
        for (Node n : f.stage.getScene().getRoot().lookupAll(".label")) if (n instanceof Labeled) return true;
        return false;
    }
}
