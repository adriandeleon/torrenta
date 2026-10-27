package com.torrenta.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import javafx.event.Event;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

import com.torrenta.engine.Engine;
import com.torrenta.engine.LinkRequest;
import com.torrenta.engine.ShareLimit;
import com.torrenta.engine.TorrentLimits;
import com.torrenta.migrate.ImportCandidate;
import com.torrenta.migrate.ImportPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static com.torrenta.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The smaller torrent dialogs, each shown on its nested loop and answered as a person would:
 * Download from a link, Limits, Edit Tags, Set Location and Import from another client.
 */
@ExtendWith(FxToolkit.class)
class TorrentDialogsFxTest {

    private static Stage owner() {
        return Fx.call(() -> {
            Stage s = new Stage();
            s.setScene(new Scene(new javafx.scene.layout.Pane(), 200, 100));
            s.show();
            return s;
        });
    }

    /** Shows a dialog on the FX thread without waiting; the answer arrives when it closes. */
    private static <T> CompletableFuture<Optional<T>> show(Supplier<Optional<T>> showAndWait, BooleanSupplier showing) {
        CompletableFuture<Optional<T>> answer = new CompletableFuture<>();
        Dialogs.later(() -> answer.complete(showAndWait.get()));
        Fx.waitUntil("the dialog to show", showing);
        return answer;
    }

    private static <T> Optional<T> await(CompletableFuture<Optional<T>> answer) {
        try {
            return answer.get(Fx.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void escape(Scene scene) {
        Event.fireEvent(
                scene.getRoot(),
                new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
    }

    private static void key(Scene scene, KeyCode code) {
        Event.fireEvent(scene.getRoot(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
    }

    // ---- Download from a link -------------------------------------------------------------------

    @Test
    void aLinksNameAndFolderAreCheckedAndHandedOn(@TempDir Path dir) {
        Stage owner = owner();
        LinkDialog dialog = Fx.call(() -> new LinkDialog(owner, " https://example.org/files/disk.iso ", dir, false));
        var answer = show(dialog::showAndWait, dialog::isShowing);
        assertEquals(tr("dialog.link.title"), Fx.call(dialog::title));
        String described = Fx.call(dialog::describe);
        assertTrue(described.startsWith("folder=" + dir + " name=disk.iso paused=false hint="), described);
        Parent root = Fx.call(() -> dialog.scene().getRoot());
        Fx.run(() -> {
            // A name that would change on disk, then none: said, Download off.
            dialog.setNameForCapture("a/b");
            assertTrue(dialog.describe().endsWith("problem=" + tr("dialog.link.badName")), dialog.describe());
            dialog.confirm();
            dialog.setNameForCapture(" ");
            assertTrue(dialog.describe().endsWith("problem=" + tr("dialog.link.noName")));
            TextField folder = Dialogs.all(root, TextField.class).get(0);
            folder.setText("");
            assertTrue(dialog.describe().contains("hint= "), dialog.describe());
            folder.setText("relative/folder");
            folder.setText(dir.toString());
            dialog.setNameForCapture("my disk.iso");
            Dialogs.labeled(root, CheckBox.class, tr("dialog.link.paused")).setSelected(true);
            // Browse asks the platform for a chooser, which the headless one has not.
            assertThrows(
                    UnsupportedOperationException.class,
                    Dialogs.labeled(root, Button.class, tr("common.browse"))::fire);
            Dialogs.labeled(root, Button.class, tr("dialog.link.download")).fire();
        });
        LinkRequest request = await(answer).orElseThrow();
        assertEquals("https://example.org/files/disk.iso", request.url());
        assertEquals(dir, request.savePath());
        assertEquals("my disk.iso", request.fileName());
        assertTrue(request.startPaused());
    }

    @Test
    void aLinkLeftAsItWasHasNoNameOfItsOwnAndEscapeCancels(@TempDir Path dir) {
        Stage owner = owner();
        LinkDialog dialog = Fx.call(() -> new LinkDialog(owner, "https://example.org/a.zip", dir, true));
        var answer = show(dialog::showAndWait, dialog::isShowing);
        Fx.run(dialog::confirm);
        LinkRequest request = await(answer).orElseThrow();
        assertNull(request.fileName(), "the link's own name may still be replaced by the server's");
        assertTrue(request.startPaused());

        LinkDialog other = Fx.call(() -> new LinkDialog(owner, "https://example.org/b.zip", dir, false));
        var cancelled = show(other::showAndWait, other::isShowing);
        Fx.run(() -> {
            key(other.scene(), KeyCode.A);
            escape(other.scene());
        });
        assertTrue(await(cancelled).isEmpty());
        LinkDialog third = Fx.call(() -> new LinkDialog(owner, "https://example.org/c.zip", dir, false));
        var cancelledToo = show(third::showAndWait, third::isShowing);
        Fx.run(() -> Dialogs.labeled(third.scene().getRoot(), Button.class, tr("common.cancel"))
                .fire());
        assertTrue(await(cancelledToo).isEmpty());
    }

    // ---- Limits ---------------------------------------------------------------------------------

    @Test
    void theLimitsAreReadBackWithTheTorrentsOwnShareLimit() {
        Stage owner = owner();
        ShareLimit global = new ShareLimit(2.0, 0, ShareLimit.Action.PAUSE);
        LimitsDialog dialog = Fx.call(() -> new LimitsDialog(
                owner,
                "Film",
                new TorrentLimits(500 * 1024, 64 * 1024, 80, 4, true),
                new ShareLimit(1.5, 600, ShareLimit.Action.REMOVE),
                global,
                true));
        Fx.run(() -> dialog.applyCapabilities(Engine.Capabilities.ALL));
        var answer = show(dialog::showAndWait, dialog::isShowing);
        assertEquals(tr("dialog.limits.title", "Film"), Fx.call(dialog::title));
        Fx.run(() -> {
            @SuppressWarnings("unchecked")
            Spinner<Integer> download =
                    Dialogs.all(dialog.scene().getRoot(), Spinner.class).get(0);
            // Typed and not entered: Apply commits it first.
            download.getEditor().setText("800");
            Dialogs.labeled(dialog.scene().getRoot(), Button.class, tr("dialog.common.apply"))
                    .fire();
        });
        LimitsDialog.Result result = await(answer).orElseThrow();
        assertEquals(new TorrentLimits(800 * 1024, 64 * 1024, 80, 4, true), result.limits());
        assertEquals(new ShareLimit(1.5, 600, ShareLimit.Action.REMOVE), result.shareOverride());
    }

    @Test
    void theGlobalShareLimitIsNoOverrideAndAMagnetHasNoPieceOrderYet() {
        Stage owner = owner();
        LimitsDialog dialog = Fx.call(() -> new LimitsDialog(
                owner, "3 torrents", TorrentLimits.NONE, null, new ShareLimit(0, 120, ShareLimit.Action.PAUSE), false));
        Fx.run(() -> dialog.applyCapabilities(
                Engine.Capabilities.ALL.without(Engine.Capability.UPLOAD_SLOTS, Engine.Capability.FIRST_LAST_PIECES)));
        var answer = show(dialog::showAndWait, dialog::isShowing);
        Fx.run(() -> {
            Parent root = dialog.scene().getRoot();
            assertTrue(Dialogs.labeled(root, CheckBox.class, tr("dialog.limits.firstAndLast"))
                    .isDisabled());
            // Own limits enable the fields, global disables them again.
            @SuppressWarnings("unchecked")
            ChoiceBox<Object> share = Dialogs.all(root, ChoiceBox.class).get(0);
            Object global = share.getValue();
            share.setValue(share.getItems().get(1));
            share.setValue(global);
            dialog.confirm();
        });
        LimitsDialog.Result result = await(answer).orElseThrow();
        assertEquals(TorrentLimits.NONE, result.limits());
        assertNull(result.shareOverride());

        LimitsDialog other =
                Fx.call(() -> new LimitsDialog(owner, "x", TorrentLimits.NONE, null, ShareLimit.NONE, true));
        var cancelled = show(other::showAndWait, other::isShowing);
        Fx.run(() -> {
            key(other.scene(), KeyCode.A);
            escape(other.scene());
        });
        assertTrue(await(cancelled).isEmpty());
        LimitsDialog third =
                Fx.call(() -> new LimitsDialog(owner, "x", TorrentLimits.NONE, null, ShareLimit.NONE, true));
        var cancelledToo = show(third::showAndWait, third::isShowing);
        Fx.run(() -> Dialogs.labeled(third.scene().getRoot(), Button.class, tr("common.cancel"))
                .fire());
        assertTrue(await(cancelledToo).isEmpty());
    }

    // ---- Edit Tags ------------------------------------------------------------------------------

    @Test
    void tagsCheckedAddClearedRemoveAndMixedStayAsTheyAre() {
        Stage owner = owner();
        TagsDialog dialog = Fx.call(() -> new TagsDialog(
                owner,
                "2 torrents",
                List.of("keep", "hd", "old"),
                List.of(List.of("keep", "hd"), List.of("keep", "new"))));
        var answer = show(dialog::showAndWait, dialog::isShowing);
        assertEquals(tr("dialog.tags.title", "2 torrents"), Fx.call(dialog::title));
        Fx.run(() -> {
            Parent root = dialog.scene().getRoot();
            assertTrue(Dialogs.labeled(root, CheckBox.class, "keep").isSelected(), "every torrent has it");
            assertTrue(Dialogs.labeled(root, CheckBox.class, "hd").isIndeterminate(), "one of the two has it");
            Dialogs.labeled(root, CheckBox.class, "old").setSelected(true);
            Dialogs.labeled(root, CheckBox.class, "keep").setSelected(false);
            Dialogs.all(root, TextField.class).get(0).setText("fresh, Fresh");
            Dialogs.labeled(root, Button.class, tr("dialog.common.apply")).fire();
        });
        TagsDialog.Result result = await(answer).orElseThrow();
        assertTrue(result.add().contains("old"), result.toString());
        assertTrue(result.add().contains("fresh"), result.toString());
        assertTrue(result.remove().contains("keep"), result.toString());
        assertFalse(result.add().contains("hd") || result.remove().contains("hd"), result.toString());
        assertFalse(result.add().contains("new") || result.remove().contains("new"), result.toString());

        TagsDialog none = Fx.call(() -> new TagsDialog(owner, "Film", List.of(), List.of(List.of())));
        var cancelled = show(none::showAndWait, none::isShowing);
        Fx.run(() -> {
            assertTrue(Dialogs.all(none.scene().getRoot(), javafx.scene.control.Label.class).stream()
                    .anyMatch(l -> tr("dialog.tags.noTags").equals(l.getText())));
            key(none.scene(), KeyCode.A);
            escape(none.scene());
        });
        assertTrue(await(cancelled).isEmpty());
        TagsDialog third = Fx.call(() -> new TagsDialog(owner, "Film", List.of("a"), List.of(List.of())));
        var cancelledToo = show(third::showAndWait, third::isShowing);
        Fx.run(() -> Dialogs.labeled(third.scene().getRoot(), Button.class, tr("common.cancel"))
                .fire());
        assertTrue(await(cancelledToo).isEmpty());
    }

    // ---- Set Location ---------------------------------------------------------------------------

    @Test
    void aNewLocationSaysItsFreeSpaceAndMovesTheFiles(@TempDir Path dir) {
        Stage owner = owner();
        LocationDialog dialog = Fx.call(() -> new LocationDialog(owner, "Film", dir.toString(), 1L << 20));
        Fx.run(() -> dialog.applyCapabilities(Engine.Capabilities.ALL));
        var answer = show(dialog::showAndWait, dialog::isShowing);
        assertEquals(tr("dialog.location.title", "Film"), Fx.call(dialog::title));
        assertFalse(Fx.call(dialog::hint).isEmpty());
        Fx.run(() -> {
            Parent root = dialog.scene().getRoot();
            dialog.setPathForCapture("");
            assertEquals("", dialog.hint());
            dialog.confirm(); // nothing typed: nothing moved
            dialog.setPathForCapture(dir.resolve("new/place").toString());
            assertThrows(
                    UnsupportedOperationException.class,
                    Dialogs.labeled(root, Button.class, tr("common.browse"))::fire);
            Dialogs.labeled(root, CheckBox.class, tr("dialog.location.moveFiles"))
                    .setSelected(false);
            Dialogs.labeled(root, Button.class, tr("dialog.location.move")).fire();
        });
        LocationDialog.Result result = await(answer).orElseThrow();
        assertEquals(dir.resolve("new/place").toString(), result.path());
        assertFalse(result.moveFiles());
    }

    @Test
    void aDaemonsLocationHasNoBrowseAndNoFreeSpace() {
        Stage owner = owner();
        LocationDialog dialog = Fx.call(() -> new LocationDialog(owner, "Film", "/srv/torrents", -1));
        Fx.run(() -> dialog.applyCapabilities(Engine.Capabilities.ALL.without(Engine.Capability.LOCAL_FILES)));
        var answer = show(dialog::showAndWait, dialog::isShowing);
        Fx.run(() -> {
            assertEquals("", dialog.hint());
            dialog.setPathForCapture("/srv/other");
            assertEquals("", dialog.hint());
            key(dialog.scene(), KeyCode.A);
            escape(dialog.scene());
        });
        assertTrue(await(answer).isEmpty());
        LocationDialog other = Fx.call(() -> new LocationDialog(owner, "Film", "/srv/torrents", -1));
        var cancelled = show(other::showAndWait, other::isShowing);
        Fx.run(() -> Dialogs.labeled(other.scene().getRoot(), Button.class, tr("common.cancel"))
                .fire());
        assertTrue(await(cancelled).isEmpty());
    }

    // ---- Import ---------------------------------------------------------------------------------

    private static ImportPlan.Item item(String name, ImportPlan.Verdict verdict) {
        ImportCandidate candidate = new ImportCandidate(
                "qBittorrent",
                name,
                name,
                "",
                new byte[0],
                new byte[] {1},
                "/x",
                "",
                "",
                List.of(),
                false,
                List.of(),
                1,
                2,
                -2,
                -2);
        return new ImportPlan.Item(candidate, verdict, verdict.name().toLowerCase(java.util.Locale.ROOT));
    }

    @Test
    void aFoundFolderIsReadAndTheTickedTorrentsImported(@TempDir Path dir) {
        Stage owner = owner();
        Path first = dir.resolve("qBittorrent/BT_backup");
        Path second = dir.resolve("other/BT_backup");
        List<Path> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        ImportDialog dialog = Fx.call(() -> new ImportDialog(owner, List.of(first, second), folder -> {
            asked.add(folder);
            if (folder.equals(second))
                return new ImportText.Loaded(folder, List.of(), true, false, List.of("broken.fastresume"));
            return new ImportText.Loaded(
                    folder,
                    List.of(
                            item("Film", ImportPlan.Verdict.IMPORT),
                            item("Album", ImportPlan.Verdict.CHECK),
                            item("Here", ImportPlan.Verdict.ALREADY_HERE)),
                    false,
                    true,
                    List.of());
        }));
        var answer = show(dialog::showAndWait, dialog::isShowing);
        assertEquals(tr("dialog.import.title"), Fx.call(dialog::title));
        Fx.waitUntil(
                "the first folder to be read", () -> dialog.linesForCapture().size() == 4);
        List<String> lines = Fx.call(dialog::linesForCapture);
        assertTrue(lines.get(0).startsWith("import-source=" + first + " rows=3"), lines.get(0));
        assertTrue(lines.get(3).startsWith("import=[ ] Here"), lines.get(3));

        // The other folder: nothing to import, its notes said; then back to the first.
        Fx.run(() -> dialog.showFolder(second));
        Fx.waitUntil(
                "the second folder",
                () -> dialog.linesForCapture().stream().anyMatch(l -> l.startsWith("import-note=")));
        assertTrue(Fx.call(dialog::linesForCapture).get(0).endsWith("enabled=false"));
        Fx.run(() -> {
            dialog.confirm(); // nothing chosen: nothing imported
            dialog.showFolder(dir.resolve("chosen/BT_backup"));
            dialog.showFolder(first);
        });
        Fx.waitUntil("the first folder again", () -> dialog.linesForCapture().size() == 4);
        Fx.pulse();
        // Unticking a row in the table's own checkbox.
        Fx.run(() -> {
            TableView<?> table =
                    Dialogs.all(dialog.scene().getRoot(), TableView.class).get(0);
            CheckBox box = Dialogs.all(table, CheckBox.class).stream()
                    .filter(c -> c.isSelected() && !c.isDisabled())
                    .findFirst()
                    .orElseThrow();
            box.setSelected(false);
            box.fire(); // ticked again, through its handler
            box.fire();
        });
        assertTrue(Fx.call(dialog::linesForCapture).get(0).contains("enabled=true"));
        Fx.run(dialog::confirmWhenLoaded);
        List<ImportPlan.Item> items = await(answer).orElseThrow();
        assertEquals(1, items.size(), items.toString());
        assertTrue(asked.contains(dir.resolve("chosen/BT_backup")));
    }

    @Test
    void noFolderFoundSaysSoAndImportWaitsForTheRead(@TempDir Path dir) throws Exception {
        Stage owner = owner();
        CountDownLatch release = new CountDownLatch(1);
        ImportDialog dialog = Fx.call(() -> new ImportDialog(owner, List.of(), folder -> {
            try {
                release.await(Fx.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ImportText.Loaded(
                    folder, List.of(item("Film", ImportPlan.Verdict.IMPORT)), false, false, List.of());
        }));
        var answer = show(dialog::showAndWait, dialog::isShowing);
        assertTrue(Fx.call(dialog::linesForCapture).get(0).startsWith("import-source= rows=0"));
        Fx.run(() -> {
            assertTrue(Dialogs.all(dialog.scene().getRoot(), javafx.scene.control.Label.class).stream()
                    .anyMatch(l -> tr("dialog.import.none").equals(l.getText())));
            // The picker's last entry asks the platform for a chooser, which the headless one has not.
            @SuppressWarnings("unchecked")
            ComboBox<Path> source =
                    Dialogs.all(dialog.scene().getRoot(), ComboBox.class).get(0);
            assertEquals(1, source.getItems().size());
            dialog.showFolder(dir);
            dialog.confirmWhenLoaded();
        });
        release.countDown();
        assertEquals(1, await(answer).orElseThrow().size());

        ImportDialog other = Fx.call(() -> new ImportDialog(owner, List.of(), folder -> null));
        var cancelled = show(other::showAndWait, other::isShowing);
        Fx.run(() -> {
            key(other.scene(), KeyCode.A);
            escape(other.scene());
        });
        assertTrue(await(cancelled).isEmpty());
        ImportDialog third = Fx.call(() -> new ImportDialog(owner, List.of(), folder -> null));
        var cancelledToo = show(third::showAndWait, third::isShowing);
        Fx.run(() -> Dialogs.labeled(third.scene().getRoot(), Button.class, tr("common.cancel"))
                .fire());
        assertTrue(await(cancelledToo).isEmpty());
    }
}
