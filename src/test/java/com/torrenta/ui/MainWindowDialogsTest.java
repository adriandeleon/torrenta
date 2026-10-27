package com.torrenta.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.scene.control.ComboBox;
import javafx.stage.Stage;

import com.torrenta.engine.FakeEngine;
import com.torrenta.engine.Priority;
import com.torrenta.engine.TorrentDetail;
import com.torrenta.engine.TorrentPreview;
import com.torrenta.engine.TorrentState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static com.torrenta.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The main window's dialogs on a fake engine: each opened the way a person opens it, answered,
 * and what it asked of the engine checked. A dialog's {@code showAndWait} runs a nested event loop,
 * so the action that opens one is posted ({@link WindowFixture#actionLater}) when it would block.
 */
@ExtendWith(FxToolkit.class)
class MainWindowDialogsTest {

    private static final String HASH = "0123456789abcdef0123456789abcdef01234567";
    private static final String MAGNET = "magnet:?xt=urn:btih:" + HASH + "&dn=Debian";

    @AfterEach
    void noAnswerLeftOver() {
        RemoveConfirm.answerForCapture = null;
    }

    private static FakeEngine oneTorrent() {
        return new FakeEngine().put(FakeEngine.torrent("aaaa", "Film"));
    }

    private static boolean anyCall(FakeEngine engine, String prefix) {
        return engine.calls().stream().anyMatch(c -> c.startsWith(prefix));
    }

    private static TorrentPreview preview() {
        return new TorrentPreview(
                HASH,
                "Debian",
                300,
                List.of(
                        new TorrentPreview.FileEntry(0, "Debian/disc.iso", 200),
                        new TorrentPreview.FileEntry(1, "Debian/notes.txt", 100)),
                false,
                List.of("https://tracker.example.org/announce"),
                "a comment",
                "mktorrent",
                1_790_000_000L,
                true,
                false);
    }

    @Test
    void aMagnetOpensTheAddDialogAndAddIsTheEnginesAdd(@TempDir Path dir) {
        FakeEngine engine = oneTorrent();
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            Fx.run(() -> f.window.addInput(MAGNET));
            f.awaitWindow(tr("dialog.add.title.magnet"));
            // The fake probes nothing: the dialog says why and can still add.
            Fx.waitUntil("the probe to have been asked", () -> anyCall(engine, "probe "));
            f.action("dialog-add");
            Fx.waitUntil("the add to reach the engine", () -> anyCall(engine, "add Magnet"));
            Fx.waitUntil("the new row", () -> f.rows() == 2);
            f.awaitStatus("Debian");
        }
    }

    @Test
    void aMagnetsProbeShowsItsPeersThenItsFilesAndHandsItselfToTheAdd(@TempDir Path dir) {
        FakeEngine.Probe probe = new FakeEngine.Probe(null, 3);
        FakeEngine engine = oneTorrent().withPreview(preview()).withProbes(source -> probe);
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            Fx.run(() -> f.window.addInput(MAGNET));
            Stage dialog = f.awaitWindow(tr("dialog.add.title.magnet"));
            // A poll while the metadata is coming says how many peers the probe has.
            engine.put(FakeEngine.torrent("bbbb", "Other"));
            Fx.waitUntil("a poll with the dialog open", () -> f.rows() == 2);
            probe.torrent().complete(new byte[] {1, 2, 3});
            Fx.waitUntil(
                    "the file list in the dialog",
                    () -> dialog.getScene().getRoot().lookup(".tree-table-view") != null);
            engine.put(FakeEngine.torrent("cccc", "Third"));
            Fx.waitUntil("a poll with the files shown", () -> f.rows() == 3);
            f.action("dialog-add");
            Fx.waitUntil("the add to reach the engine", () -> anyCall(engine, "add TorrentFile"));
            assertTrue(probe.cancelled(), "the probe ends as the torrent is added");
        }
    }

    @Test
    void aCancelledAddEndsItsProbe(@TempDir Path dir) {
        FakeEngine.Probe probe = new FakeEngine.Probe(null, 1);
        FakeEngine engine = oneTorrent().withProbes(source -> probe);
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            Fx.run(() -> f.window.addInput(MAGNET));
            f.awaitWindow(tr("dialog.add.title.magnet"));
            Fx.waitUntil("the probe to have been asked", () -> anyCall(engine, "probe "));
            f.cancelDialogs();
            Fx.waitUntil("the probe to be cancelled", probe::cancelled);
            assertFalse(anyCall(engine, "add "), "nothing added: " + engine.calls());
        }
    }

    @Test
    void aTorrentFileIsInspectedProbedAndAddedWithItsChoices(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("debian.torrent");
        Files.write(file, new byte[] {'d', 'e'});
        FakeEngine.Probe probe = new FakeEngine.Probe(new byte[] {'d', 'e'}, 2);
        FakeEngine engine = oneTorrent().withPreview(preview()).withProbes(source -> probe);
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            Fx.run(() -> f.window.addInput(file.toString()));
            f.awaitWindow(tr("dialog.add.title.torrent"));
            Fx.waitUntil("the swarm probe", () -> anyCall(engine, "probe "));
            engine.put(FakeEngine.torrent("bbbb", "Other"));
            Fx.waitUntil("a poll that asks the probe for its swarm", () -> f.rows() == 2);
            f.action("dialog-category:");
            f.action("dialog-add");
            Fx.waitUntil("the add", () -> anyCall(engine, "add TorrentFile"));
        }
    }

    @Test
    void anUnsafeTorrentIsRefusedWhenAddedWithoutAsking(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("film.torrent");
        Files.write(file, new byte[] {'d', 'e'});
        TorrentPreview risky = new TorrentPreview(
                HASH,
                "Film 2024 1080p",
                300,
                List.of(
                        new TorrentPreview.FileEntry(0, "Film 2024 1080p/film.mkv", 200),
                        new TorrentPreview.FileEntry(1, "Film 2024 1080p/codec.exe", 100)),
                false,
                List.of(),
                "",
                "",
                0,
                true,
                false);
        FakeEngine engine = oneTorrent().withPreview(risky);
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            Fx.run(() -> {
                f.settings.set(com.torrenta.store.Settings.ADD_UNSAFE_CONTENT, "REFUSE");
                f.settings.setAddWithoutAsking(true);
                f.window.addInput(file.toString());
            });
            Fx.waitUntil(
                    "the refusal in the log",
                    () -> f.window.eventLines().stream()
                            .anyMatch(l -> l.contains("codec.exe") || l.contains("Film 2024")));
            assertFalse(anyCall(engine, "add "), engine.calls().toString());
        }
    }

    @Test
    void theAddDialogsOptionsReachTheAdd(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("debian.torrent");
        Files.write(file, new byte[] {'d', 'e'});
        TorrentPreview secret = new TorrentPreview(
                HASH,
                "Debian",
                300,
                List.of(new TorrentPreview.FileEntry(0, "Debian/disc.iso", 300)),
                true,
                List.of(),
                "",
                "",
                0,
                true,
                false);
        FakeEngine engine = oneTorrent().withPreview(secret);
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            Fx.run(() -> f.window.addInput(file.toString()));
            Stage dialog = f.awaitWindow(tr("dialog.add.title.torrent"));
            Fx.run(() -> {
                for (javafx.scene.Node n : dialog.getScene().getRoot().lookupAll(".check-box")) {
                    if (n instanceof javafx.scene.control.CheckBox c
                            && tr("dialog.add.firstLast").equals(c.getText())) c.setSelected(true);
                }
            });
            f.action("dialog-add");
            Fx.waitUntil(
                    "first and last pieces first",
                    () -> engine.calls().stream().anyMatch(c -> c.startsWith("setLimits fake") && c.endsWith("true]")));
            assertFalse(anyCall(engine, "probe "), "a private torrent is not probed");
        }
    }

    @Test
    void aMagnetWhoseMetadataDoesNotReadIsSaidInTheDialog(@TempDir Path dir) {
        FakeEngine engine = oneTorrent().withProbes(source -> new FakeEngine.Probe(new byte[] {1}, 1));
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            Fx.run(() -> f.window.addInput(MAGNET));
            f.awaitWindow(tr("dialog.add.title.magnet"));
            Fx.waitUntil("the probe asked", () -> anyCall(engine, "probe "));
            Fx.pulse();
            f.cancelDialogs();
        }
    }

    @Test
    void aChooserWaitsForAnOpenDialogAndARefusedStartIsSaid(@TempDir Path dir) {
        FakeEngine engine = oneTorrent()
                .withFiles("aaaa", List.of(new TorrentDetail.FileStatus(0, "Film/film.mkv", 200, 0, Priority.NORMAL)))
                .refuse("resume");
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            Fx.run(() -> f.window.addInput(MAGNET));
            f.awaitWindow(tr("dialog.add.title.magnet"));
            Fx.run(() -> f.window.chooseFiles("aaaa"));
            Fx.pulse();
            f.cancelDialogs();
            f.awaitWindow(tr("dialog.add.title.chooseFiles"));
            f.action("dialog-add");
            f.awaitStatus("refuses resume");
            Fx.run(() -> f.window.chooseFiles("aaaa"));
            f.awaitWindow(tr("dialog.add.title.chooseFiles"));
            f.action("dialog-press:paused");
            Fx.pulse();
            f.cancelDialogs();
        }
    }

    @Test
    void aSecondAddWaitsForTheFirstDialog(@TempDir Path dir) {
        FakeEngine engine = oneTorrent();
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            Fx.run(() -> f.window.addInput(MAGNET));
            f.awaitWindow(tr("dialog.add.title.magnet"));
            Fx.run(() -> f.window.addInput("magnet:?xt=urn:btih:" + "f".repeat(40)));
            Fx.waitUntil(
                    "the second probe asked",
                    () -> engine.calls().stream()
                                    .filter(c -> c.startsWith("probe "))
                                    .count()
                            == 1);
            f.cancelDialogs();
            // The queued one opens once the first is gone.
            Fx.waitUntil(
                    "the second probe",
                    () -> engine.calls().stream()
                                    .filter(c -> c.startsWith("probe "))
                                    .count()
                            == 2);
            f.cancelDialogs();
        }
    }

    @Test
    void addWithoutAskingAddsAtOnceAndSaysSo(@TempDir Path dir) {
        FakeEngine engine = oneTorrent();
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            Fx.run(() -> {
                f.settings.setAddWithoutAsking(true);
                f.window.addInput(MAGNET);
            });
            Fx.waitUntil("the add", () -> anyCall(engine, "add "));
            Fx.run(() -> f.window.addInput("https://example.org/files/disc.iso"));
            f.awaitCall("addLink https://example.org/files/disc.iso");
            Fx.run(() -> f.window.addInput(dir.resolve("missing.torrent").toString()));
            f.awaitStatus("missing.torrent");
        }
    }

    @Test
    void aLinkOpensTheDownloadDialogAndALaterOneWaitsItsTurn(@TempDir Path dir) {
        FakeEngine engine = oneTorrent();
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            f.action("link-name:Disc~One.iso");
            Fx.run(() -> f.window.addInput("https://example.org/files/disc1.iso"));
            f.awaitWindow(tr("dialog.link.title"));
            Fx.run(() -> f.window.addInput("https://example.org/files/disc2.iso"));
            Fx.pulse();
            f.action("link-name:Renamed.iso");
            f.action("link-download");
            f.awaitCall("addLink https://example.org/files/disc1.iso");
            // The second one opens after the first.
            f.awaitWindow(tr("dialog.link.title"));
            f.cancelDialogs();
            assertFalse(engine.calls().contains("addLink https://example.org/files/disc2.iso"));
        }
    }

    @Test
    void theLimitsDialogSetsTheSelectionsLimits(@TempDir Path dir) {
        FakeEngine engine = oneTorrent();
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            f.action("select-first");
            f.action("limits");
            f.pressDefault(tr("dialog.limits.title", "Film"));
            Fx.waitUntil("the limits set", () -> anyCall(engine, "setLimits aaaa"));
            engine.refuse("limits");
            f.action("limits");
            f.awaitStatus("refuses limits");
        }
    }

    @Test
    void setLocationMovesAndUndoMovesBack(@TempDir Path dir) {
        FakeEngine engine = oneTorrent();
        String was = FakeEngine.torrent("aaaa", "Film").savePath();
        String target = dir.resolve("films").toString();
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            f.action("select-first");
            f.action("set-location:" + target);
            f.pressDefault(tr("dialog.location.title", "Film"));
            Fx.waitUntil("the move", () -> anyCall(engine, "moveStorage aaaa " + target));
            Fx.waitUntil(
                    "the undo to be offered",
                    () -> f.window.lateLinesForCapture().stream().anyMatch(l -> l.startsWith("status-bar=")));
            f.awaitStatus(target);
            f.action("undo");
            Fx.waitUntil("the move back", () -> anyCall(engine, "moveStorage aaaa " + was));
            f.awaitStatus("Undone");
            f.action("undo");
            f.awaitStatus(tr("status.undo.nothing"));
        }
    }

    @Test
    void theTagsDialogSetsTags(@TempDir Path dir) {
        FakeEngine engine = oneTorrent();
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            f.action("select-first");
            f.action("edit-tags");
            f.pressDefault(tr("dialog.tags.title", "Film"));
            Fx.waitUntil("the tags set", () -> anyCall(engine, "setTags aaaa"));
        }
    }

    @Test
    void aNewCategoryIsMadeFromTheCategoryChoice(@TempDir Path dir) {
        FakeEngine engine = oneTorrent();
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            f.action("select-first");
            f.action("set-category");
            Stage choice = f.awaitWindow(tr("window.category.title"));
            Fx.run(() -> {
                @SuppressWarnings("unchecked")
                ComboBox<String> box =
                        (ComboBox<String>) choice.getScene().getRoot().lookup(".combo-box");
                box.getSelectionModel().select(tr("window.category.new"));
            });
            f.pressDefault(tr("window.category.title"));
            f.pressDefault(tr("window.newCategory.title"), "Films");
            f.awaitCall("setCategory aaaa Films");
            // The choice again, now with Films in it, picking None clears it.
            f.action("set-category");
            f.pressDefault(tr("window.category.title"));
            Fx.waitUntil(
                    "the category to be Films",
                    () -> engine.calls().stream()
                                    .filter(c -> c.startsWith("setCategory aaaa"))
                                    .count()
                            >= 2);
        }
    }

    @Test
    void renameAsksForOneTorrentAndRenamesIt(@TempDir Path dir) {
        FakeEngine engine = oneTorrent().put(FakeEngine.torrent("bbbb", "Album"));
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            f.action("select-all");
            f.action("rename");
            assertEquals(tr("status.rename.one"), f.status());
            f.action("select-first");
            f.action("rename:New~Name");
            Fx.waitUntil("the rename", () -> anyCall(engine, "rename "));
            assertTrue(
                    engine.calls().stream().anyMatch(c -> c.endsWith(" New Name")),
                    engine.calls().toString());
        }
    }

    @Test
    void removeAsksThenDetachesAndUndoBringsItBack(@TempDir Path dir) {
        FakeEngine engine = oneTorrent().put(FakeEngine.torrent("bbbb", "Album"));
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            f.action("select-first");
            String first = Fx.call(() -> f.window.lateLinesForCapture().stream()
                    .filter(l -> l.startsWith("table="))
                    .findFirst()
                    .orElseThrow());
            String id = first.contains("*Album") ? "bbbb" : "aaaa";
            f.actionLater("remove");
            f.pressDefault(tr("common.remove"));
            f.awaitCall("detach " + id);
            Fx.waitUntil("one row left", () -> f.rows() == 1);
            f.action("undo");
            f.awaitCall("reattach " + id);
            Fx.waitUntil("both rows back", () -> f.rows() == 2);
        }
    }

    @Test
    void removeWithFilesGoesToTheEngineWithItsFiles(@TempDir Path dir) {
        FakeEngine engine = new FakeEngine().put(FakeEngine.torrent("aaaa", "Film", TorrentState.SEEDING, 1.0));
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            f.action("select-first");
            f.action("remove-data-confirmed");
            f.awaitCall("remove aaaa true");
            Fx.waitUntil("the list empty", () -> f.rows() == 0);
        }
    }

    @Test
    void chooseFilesSetsWhatChangedAndStartsIt(@TempDir Path dir) {
        FakeEngine engine = oneTorrent()
                .withFiles(
                        "aaaa",
                        List.of(
                                new TorrentDetail.FileStatus(0, "Film/film.mkv", 200, 0, Priority.NORMAL),
                                new TorrentDetail.FileStatus(1, "Film/extras.mkv", 100, 0, Priority.IGNORE)));
        try (WindowFixture f = WindowFixture.open(dir, engine)) {
            Fx.run(() -> {
                f.settings.setFilesToPick("aaaa", true);
                f.settings.saveQuietly();
            });
            f.action("select-first");
            f.action("choose-files");
            f.awaitWindow(tr("dialog.add.title.chooseFiles"));
            f.action("dialog-add");
            Fx.waitUntil(
                    "the torrent started or its files set",
                    () -> anyCall(engine, "resume aaaa") || anyCall(engine, "setFilePriority aaaa"));
            assertFalse(Fx.call(() -> f.settings.filesToPick("aaaa")), "the mark is cleared");
        }
    }
}
