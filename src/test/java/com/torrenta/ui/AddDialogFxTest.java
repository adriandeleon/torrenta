package com.torrenta.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javafx.event.Event;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeTableView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

import com.torrenta.engine.AddRequest;
import com.torrenta.engine.Category;
import com.torrenta.engine.ContentIndex;
import com.torrenta.engine.DedupMode;
import com.torrenta.engine.Engine;
import com.torrenta.engine.FileRenames;
import com.torrenta.engine.Priority;
import com.torrenta.engine.SwarmPreview;
import com.torrenta.engine.TorrentPreview;
import com.torrenta.engine.TorrentPreview.FileEntry;
import com.torrenta.session.AddDefaults;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static com.torrenta.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Add dialog itself, shown on its nested loop and answered as a person would: the tree's
 * ticks and keys, the content card, the folder and its category, the buttons for a magnet on its
 * way, the files found on disk, and the choice it hands back.
 */
@ExtendWith(FxToolkit.class)
class AddDialogFxTest {

    private static final String ROOT_A = "a".repeat(64);
    private static final String MAGNET = "magnet:?xt=urn:btih:" + "c".repeat(40)
            + "&dn=Film.2024.1080p&tr=udp%3A%2F%2Fa.example%3A1&tr=udp%3A%2F%2Fb.example%3A1";

    /** A media torrent with a program in it, which the content card flags. */
    private static TorrentPreview preview() {
        return new TorrentPreview(
                "dddd",
                "Film.2024.1080p",
                3_000_100L,
                List.of(
                        new FileEntry(0, "Film.2024.1080p/Film.mkv", 3_000_000L, ROOT_A),
                        new FileEntry(1, "Film.2024.1080p/Subs/en.srt", 50L),
                        new FileEntry(3, "Film.2024.1080p/setup.exe", 50L)),
                true,
                List.of("udp://a.example:1"),
                "Encoded by someone",
                "",
                0,
                true,
                true);
    }

    private static AddDefaults defaults(Path dir, boolean startPaused) {
        return new AddDefaults(
                dir.resolve("downloads"),
                startPaused,
                true,
                true,
                dir.resolve("incomplete"),
                false,
                false,
                FileRenames.Layout.ORIGINAL);
    }

    private static Stage owner() {
        return Fx.call(() -> {
            Stage s = new Stage();
            s.setScene(new javafx.scene.Scene(new javafx.scene.layout.Pane(), 200, 100));
            s.show();
            return s;
        });
    }

    private static CompletableFuture<Optional<AddDialog.Choice>> show(AddDialog dialog) {
        CompletableFuture<Optional<AddDialog.Choice>> answer = new CompletableFuture<>();
        Dialogs.later(() -> answer.complete(dialog.showAndWait()));
        Fx.waitUntil("the Add dialog to show", dialog::isShowing);
        return answer;
    }

    private static Optional<AddDialog.Choice> await(CompletableFuture<Optional<AddDialog.Choice>> answer) {
        try {
            return answer.get(Fx.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static Parent root(AddDialog dialog) {
        return dialog.scene().getRoot();
    }

    private static List<TextField> fields(AddDialog dialog) {
        return Dialogs.all(root(dialog), TextField.class);
    }

    @SuppressWarnings("unchecked")
    private static TreeTableView<FileTree.Node> tree(AddDialog dialog) {
        return (TreeTableView<FileTree.Node>)
                Dialogs.all(root(dialog), TreeTableView.class).get(0);
    }

    private static void key(AddDialog dialog, KeyCode code, boolean shift) {
        Event.fireEvent(
                dialog.scene().getFocusOwner() == null
                        ? root(dialog)
                        : dialog.scene().getFocusOwner(),
                new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, false, false, false));
    }

    @Test
    void aTorrentsFilesAreTickedFiledAndAdded(@TempDir Path dir) throws Exception {
        Stage owner = owner();
        Path films = dir.resolve("films");
        AddDialog dialog = Fx.call(() -> new AddDialog(
                owner,
                new AddRequest.TorrentFile(new byte[] {1}, "film.torrent"),
                preview(),
                AddDialog.Via.FILE,
                defaults(dir, false),
                List.of(new Category("Films", films.toString()), new Category("Books"))));
        Fx.run(() -> dialog.applyCapabilities(Engine.Capabilities.ALL));
        var answer = show(dialog);
        assertEquals(tr("dialog.add.title.torrent"), Fx.call(dialog::title));
        String summary = Fx.call(dialog::summaryForCapture);
        assertTrue(summary.contains("3 files"), summary);
        assertTrue(summary.contains("dedup=none"), summary);
        assertFalse(Fx.call(dialog::awaitingFiles));

        // The program is flagged; Skip these files unticks it and the card goes.
        assertTrue(Fx.call(dialog::contentForCapture).startsWith("DANGER"), Fx.call(dialog::contentForCapture));
        Fx.run(dialog::skipFlaggedForCapture);
        assertEquals("none", Fx.call(dialog::contentForCapture));
        assertFalse(Fx.call(dialog::wantedFiles).contains(3));

        // The tree's keys: Space unticks the selection and ticks it again, + and − step the priority.
        Fx.run(() -> {
            TreeTableView<FileTree.Node> tree = tree(dialog);
            tree.getSelectionModel().clearSelection();
            tree.getSelectionModel()
                    .select(tree.getRoot().getChildren().stream()
                            .filter(i -> i.getValue().name().equals("Film.mkv"))
                            .findFirst()
                            .orElseThrow());
        });
        String file = Fx.call(() ->
                tree(dialog).getSelectionModel().getSelectedItem().getValue().name());
        Fx.run(() -> dialog.keyForCapture(KeyCode.SPACE));
        assertEquals(1, Fx.call(dialog::wantedFiles).size(), "only the subtitles are left: " + file);
        Fx.run(() -> {
            dialog.keyForCapture(KeyCode.SPACE);
            dialog.keyForCapture(KeyCode.PLUS);
            dialog.keyForCapture(KeyCode.MINUS);
            dialog.keyForCapture(KeyCode.ADD);
            dialog.keyForCapture(KeyCode.A);
        });
        // The menu sets a priority on the selection; Skip unticks it.
        Fx.run(() -> {
            var items = tree(dialog).getContextMenu().getItems();
            items.get(3).fire(); // Skip
            items.get(0).fire(); // High: ticked again
        });
        assertEquals(2, Fx.call(dialog::wantedFiles).size());
        // The header's box: everything, then everything again.
        Fx.run(() -> {
            CheckBox all = Dialogs.all(root(dialog), CheckBox.class).stream()
                    .filter(c -> c.getText() == null || c.getText().isEmpty())
                    .findFirst()
                    .orElseThrow();
            all.setSelected(false);
            all.fire();
        });
        assertEquals(3, Fx.call(dialog::wantedFiles).size());
        Fx.run(dialog::skipFlaggedForCapture);

        // Browse asks the platform for a chooser, which the headless one has not.
        Fx.run(() -> assertThrows(
                UnsupportedOperationException.class,
                Dialogs.labeled(root(dialog), Button.class, tr("common.browse"))::fire));

        // The category brings its folder; the free-space lines say where the files start.
        Fx.run(() -> {
            dialog.selectCategoryForCapture("Nonexistent");
            dialog.selectCategoryForCapture("Books");
            assertEquals(
                    dir.resolve("downloads").toString(), fields(dialog).get(0).getText());
            dialog.selectCategoryForCapture("Films");
            assertEquals(films.toString(), fields(dialog).get(0).getText());
            fields(dialog).get(1).setText("keep, hd");
        });
        assertTrue(Fx.call(dialog::hint).contains(" / "), "the incomplete folder's line: " + Fx.call(dialog::hint));

        // The swarm's line follows the fetch, and goes with it.
        Fx.run(() -> {
            dialog.showSwarm(
                    Optional.of(new SwarmPreview(System.currentTimeMillis() - 5000, 0, 3, 5, 2, 10, 10, 8, 8)));
            assertTrue(dialog.summaryForCapture().contains("swarm="), dialog.summaryForCapture());
            dialog.showSwarm(Optional.empty());
            assertFalse(dialog.summaryForCapture().contains("swarm="));
            dialog.applySimple(true);
            dialog.applySimple(false);
            dialog.refreshFromDisk();
        });

        Fx.run(dialog::confirm);
        AddDialog.Choice choice = await(answer).orElseThrow();
        AddRequest request = choice.request();
        assertEquals(films, request.savePath());
        assertFalse(request.startPaused());
        assertTrue(request.sequential());
        assertEquals("Films", request.labels().category());
        assertEquals(List.of("hd", "keep"), request.labels().tags());
        assertEquals(Priority.IGNORE, request.filePriorities().get(3));
        assertEquals(Priority.HIGH, request.filePriorities().get(0));
        assertFalse(choice.filesLater());
        assertEquals(preview(), choice.preview());
        assertFalse(Fx.call(dialog::isShowing));
        // Closed: a late file list changes nothing.
        Fx.run(() -> dialog.showFiles(new AddRequest.Magnet(MAGNET), preview()));
    }

    @Test
    void theTagsThatExistAreChipsAndSuggestionsAsTheFieldIsTyped(@TempDir Path dir) {
        Stage owner = owner();
        AddDialog dialog = Fx.call(() -> new AddDialog(
                owner,
                new AddRequest.TorrentFile(new byte[] {1}, "film.torrent"),
                preview(),
                AddDialog.Via.FILE,
                defaults(dir, false),
                List.of()));
        TagField tagField = Fx.call(dialog::tagFieldForCapture);
        // No tag yet: no chips.
        assertFalse(Fx.call(() -> tagField.chips().isVisible()));
        Fx.run(() -> dialog.offerTags(List.of("linux", "hd", "4k-hdr", "keep")));
        assertTrue(Fx.call(() -> tagField.chips().isVisible()));
        var answer = show(dialog);
        TextField tags = Fx.call(() -> fields(dialog).get(1));
        Fx.run(tags::requestFocus);
        Fx.waitUntil("the tags field to take the focus", tags::isFocused);

        // Typing offers what it starts with, then what contains it.
        // setText leaves the caret where it was, at the start: the list follows the caret once it moves.
        Fx.run(() -> {
            tags.setText("hd, h");
            tags.end();
        });
        assertEquals(List.of("4k-hdr"), Fx.call(tagField::suggestions));
        Fx.run(() -> {
            tags.setText("h");
            tags.end();
        });
        assertEquals(List.of("hd", "4k-hdr"), Fx.call(tagField::suggestions));
        // Down then Enter takes the second; Enter does not add while the list is open.
        Fx.run(() -> {
            fieldKey(tags, KeyCode.DOWN);
            fieldKey(tags, KeyCode.ENTER);
        });
        assertTrue(Fx.call(dialog::isShowing));
        assertEquals("4k-hdr, ", Fx.call(tags::getText));
        assertEquals(List.of("4k-hdr"), Fx.call(tagField::litChips));

        // Down on an empty tag lists every tag not in the field; Escape closes the list, not the dialog.
        Fx.run(() -> fieldKey(tags, KeyCode.DOWN));
        assertEquals(List.of("hd", "keep", "linux"), Fx.call(tagField::suggestions));
        Fx.run(() -> fieldKey(tags, KeyCode.ESCAPE));
        assertEquals(List.of(), Fx.call(tagField::suggestions));
        assertTrue(Fx.call(dialog::isShowing));

        // A chip toggles its tag; the field's own text lights the chips.
        Fx.run(() -> {
            Dialogs.all(root(dialog), javafx.scene.control.ToggleButton.class).stream()
                    .filter(b -> b.getText().equals("keep"))
                    .findFirst()
                    .orElseThrow()
                    .fire();
        });
        assertEquals("4k-hdr, keep", Fx.call(tags::getText));
        assertEquals(List.of("4k-hdr", "keep"), Fx.call(tagField::litChips));
        assertEquals(List.of(), Fx.call(tagField::suggestions), "a chip does not open the list");
        Fx.run(() -> tags.setText("4k-hdr, keep, new"));

        // Simple mode hides the chips with the field.
        Fx.run(() -> dialog.applySimple(true));
        assertFalse(Fx.call(() -> tagField.chips().isVisible()));
        Fx.run(() -> dialog.applySimple(false));
        assertTrue(Fx.call(() -> tagField.chips().isVisible()));

        Fx.run(dialog::confirm);
        AddDialog.Choice choice = await(answer).orElseThrow();
        assertEquals(List.of("4k-hdr", "keep", "new"), choice.request().labels().tags());
    }

    private static void fieldKey(TextField field, KeyCode code) {
        Event.fireEvent(field, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
    }

    @Test
    void aMagnetWaitsForItsFilesThenAddsThem(@TempDir Path dir) {
        Stage owner = owner();
        AddDialog dialog = Fx.call(() -> new AddDialog(
                owner, new AddRequest.Magnet(MAGNET), null, AddDialog.Via.MAGNET, defaults(dir, false), List.of()));
        var answer = show(dialog);
        assertEquals(tr("dialog.add.title.magnet"), Fx.call(dialog::title));
        assertTrue(Fx.call(dialog::awaitingFiles));
        Fx.run(() -> dialog.showFetching(3));
        String summary = Fx.call(dialog::summaryForCapture);
        assertTrue(summary.contains("Film.2024.1080p"), summary);
        assertTrue(summary.contains("placeholder: "), summary);
        assertTrue(summary.contains(tr("dialog.add.addNow")), summary);

        // Wait here: Add takes over, off until the files come.
        Fx.run(() -> {
            dialog.pressForCapture("wait");
            dialog.pressForCapture("nonsense");
            dialog.confirm(); // Add is off: nothing happens
        });
        assertTrue(Fx.call(dialog::isShowing));
        Fx.run(() -> {
            dialog.showSwarm(Optional.of(SwarmPreview.nothingYet(0))); // no files yet: hidden
            dialog.showFiles(new AddRequest.TorrentFile(new byte[] {2}, "fetched"), preview());
            dialog.showFetching(4); // ignored once the files are in
            dialog.noFetch("ignored");
            dialog.fetchFailed("ignored");
        });
        assertFalse(Fx.call(dialog::awaitingFiles));
        // Shift+Enter adds paused; Enter would start.
        Fx.run(() -> dialog.scene()
                .getAccelerators()
                .get(new javafx.scene.input.KeyCodeCombination(
                        KeyCode.ENTER, javafx.scene.input.KeyCombination.SHIFT_DOWN))
                .run());
        AddDialog.Choice choice = await(answer).orElseThrow();
        assertTrue(choice.request().startPaused());
        assertTrue(choice.request().source() instanceof AddRequest.TorrentFile);
    }

    @Test
    void addNowAddsTheMagnetToStopAtItsMetadata(@TempDir Path dir) {
        Stage owner = owner();
        AddDialog dialog = Fx.call(() -> new AddDialog(
                owner, new AddRequest.Magnet(MAGNET), null, AddDialog.Via.CLIPBOARD, defaults(dir, false), List.of()));
        var answer = show(dialog);
        Fx.run(() -> {
            dialog.fetchFailed("already in the list");
            assertTrue(dialog.summaryForCapture().contains("already in the list"));
            dialog.pressForCapture("now");
        });
        AddDialog.Choice choice = await(answer).orElseThrow();
        assertTrue(choice.filesLater());
        assertEquals(
                AddRequest.StopCondition.METADATA_RECEIVED, choice.request().stopCondition());
        assertTrue(choice.request().filePriorities().isEmpty());
    }

    @Test
    void anEngineThatCannotStopAddsAMagnetBlind(@TempDir Path dir) {
        Stage owner = owner();
        AddDialog dialog = Fx.call(() -> new AddDialog(
                owner,
                new AddRequest.Magnet("magnet:?xt=urn:btih:" + "e".repeat(40)),
                null,
                AddDialog.Via.SEARCH,
                defaults(dir, false),
                List.of()));
        Fx.run(() -> {
            dialog.applyCapabilities(Engine.Capabilities.of(Engine.Capability.LOCAL_FILES));
            dialog.noFetch("the daemon lists the files once it has them");
        });
        var answer = show(dialog);
        String summary = Fx.call(dialog::summaryForCapture);
        assertTrue(summary.contains(tr("dialog.add.nameUnknown")), summary);
        assertFalse(summary.contains(tr("dialog.add.addNow")), summary);
        Fx.run(() -> key(dialog, KeyCode.ENTER, false));
        Fx.run(dialog::confirm);
        AddDialog.Choice choice = await(answer).orElseThrow();
        assertEquals(FileRenames.Layout.ORIGINAL, choice.request().storage().layout());
        assertTrue(choice.request().filePriorities().isEmpty());
    }

    @Test
    void escapeCancelsAndAnEmptyFolderAddsNothing(@TempDir Path dir) {
        Stage owner = owner();
        AddDialog dialog = Fx.call(() -> new AddDialog(
                owner,
                new AddRequest.TorrentFile(new byte[] {1}, "x"),
                preview(),
                AddDialog.Via.RSS,
                defaults(dir, true),
                List.of()));
        var answer = show(dialog);
        Fx.run(() -> {
            // No folder: no free-space line, Add off.
            fields(dialog).get(0).setText("");
            assertEquals("", dialog.hint());
            dialog.confirm();
            dialog.pressForCapture("paused");
            assertTrue(dialog.isShowing());
            key(dialog, KeyCode.ESCAPE, false);
        });
        assertTrue(await(answer).isEmpty());
    }

    @Test
    void startPausedSwapsTheButtonsAndAddsPaused(@TempDir Path dir) {
        Stage owner = owner();
        AddDialog dialog = Fx.call(() -> new AddDialog(
                owner,
                new AddRequest.TorrentFile(new byte[] {1}, "x"),
                preview(),
                AddDialog.Via.URL,
                defaults(dir, true),
                List.of()));
        var answer = show(dialog);
        String summary = Fx.call(dialog::summaryForCapture);
        assertTrue(summary.contains(tr("dialog.add.addAndStart")), summary);
        Fx.run(() -> dialog.pressForCapture("paused"));
        assertTrue(await(answer).orElseThrow().request().startPaused());
    }

    @Test
    void choosingTheFilesOfATorrentInTheListStartsWithItsPriorities(@TempDir Path dir) {
        Stage owner = owner();
        AddDialog dialog =
                Fx.call(() -> AddDialog.chooseFiles(owner, preview(), Map.of(1, Priority.IGNORE, 0, Priority.HIGH)));
        var answer = show(dialog);
        assertEquals(tr("dialog.add.title.chooseFiles"), Fx.call(dialog::title));
        assertEquals(2, Fx.call(dialog::wantedFiles).size());
        Fx.run(() -> {
            // Nothing selected: the tree's keys take nothing.
            tree(dialog).getSelectionModel().clearSelection();
            tree(dialog).getSelectionModel().clearSelection();
            Event.fireEvent(
                    tree(dialog),
                    new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.SPACE, false, false, false, false));
            Dialogs.labeled(root(dialog), Button.class, tr("dialog.add.start")).fire();
        });
        AddDialog.Choice choice = await(answer).orElseThrow();
        assertFalse(choice.request().startPaused());
        assertEquals(Priority.IGNORE, choice.request().filePriorities().get(1));
        assertEquals(Priority.HIGH, choice.request().filePriorities().get(0));
        assertEquals(AddRequest.Storage.DEFAULT, choice.request().storage());
    }

    @Test
    void keepPausedForAnExistingTorrentWithStartPaused(@TempDir Path dir) {
        Stage owner = owner();
        AddDialog dialog = Fx.call(() -> AddDialog.chooseFiles(owner, preview(), Map.of()));
        var answer = show(dialog);
        Fx.run(() -> {
            dialog.keyForCapture(KeyCode.SPACE);
            Dialogs.labeled(root(dialog), Button.class, tr("dialog.add.keepPaused"))
                    .fire();
        });
        assertTrue(await(answer).orElseThrow().request().startPaused());
    }

    @Test
    void aFileAlreadyOnDiskIsOfferedAsALink(@TempDir Path dir) throws Exception {
        Path elsewhere = Files.createDirectories(dir.resolve("other"));
        Path film = Files.write(elsewhere.resolve("Film.mkv"), new byte[3_000_000]);
        ContentIndex index = new ContentIndex();
        index.put("ffff", List.of(new ContentIndex.Entry(ROOT_A, "ffff", 0, film, 3_000_000L)));
        Stage owner = owner();
        AddDialog dialog = Fx.call(() -> new AddDialog(
                owner,
                new AddRequest.TorrentFile(new byte[] {1}, "x"),
                preview(),
                AddDialog.Via.FILE,
                new AddDefaults(dir.resolve("downloads"), false, false, false, dir, false, false),
                List.of()));
        Fx.run(() -> dialog.offerFromDisk(index, DedupMode.ASK, id -> "Other film"));
        var answer = show(dialog);
        String dedup = Fx.call(dialog::dedupForCapture);
        assertTrue(dedup.startsWith("[x] "), dedup);
        assertTrue(dedup.contains("Other film"), dedup);
        Fx.run(() -> {
            dialog.refreshFromDisk();
            // No folder: nothing to plan against.
            fields(dialog).get(0).setText("");
            assertEquals("none", dialog.dedupForCapture());
            fields(dialog).get(0).setText(dir.resolve("downloads").toString());
            dialog.confirm();
        });
        AddDialog.Choice choice = await(answer).orElseThrow();
        assertTrue(choice.dedupLinks());
        assertFalse(choice.dedupCopies());
    }
}
