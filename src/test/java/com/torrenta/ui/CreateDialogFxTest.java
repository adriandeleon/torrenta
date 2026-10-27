package com.torrenta.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.event.Event;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

import com.torrenta.engine.CreateRequest;
import com.torrenta.engine.CreatedTorrent;
import com.torrenta.engine.EngineException;
import com.torrenta.engine.FakeEngine;
import com.torrenta.engine.MutableKey;
import com.torrenta.engine.Publication;
import com.torrenta.session.Session;
import com.torrenta.store.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static com.torrenta.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Create Torrent dialog on a fake engine whose {@code create} the test answers: the source
 * sized live, a run hashed, saved and shown, a failure, a cancel, and publishing as updatable.
 */
@ExtendWith(FxToolkit.class)
class CreateDialogFxTest {

    private static final byte[] BYTES = {'d', '4', ':', 'i', 'n', 'f', 'o', 'd', 'e', 'e'};

    private static Stage owner() {
        return Fx.call(() -> {
            Stage s = new Stage();
            s.setScene(new javafx.scene.Scene(new javafx.scene.layout.Pane(), 200, 100));
            s.show();
            return s;
        });
    }

    private static Path content(Path dir) throws IOException {
        Path folder = Files.createDirectories(dir.resolve("Holiday"));
        Files.write(folder.resolve("a.jpg"), new byte[40_000]);
        Files.write(Files.createDirectories(folder.resolve("more")).resolve("b.jpg"), new byte[20_000]);
        return folder;
    }

    private static CreatedTorrent made(CreateRequest request, String v2) {
        return new CreatedTorrent(
                BYTES,
                "eeee",
                request.path().getFileName().toString(),
                "e".repeat(40),
                v2,
                4,
                16384,
                60_000,
                "magnet:?xt=urn:btih:" + "e".repeat(40),
                request.startSeeding() ? Optional.of(FakeEngine.torrent("eeee", "Holiday")) : Optional.empty(),
                "");
    }

    private static Parent root(CreateDialog dialog) {
        return dialog.scene().getRoot();
    }

    private static String state(CreateDialog dialog) {
        return Fx.call(dialog::linesForCapture).get(0);
    }

    private static Publication publication(String name) {
        return new Publication(
                name, new MutableKey(new byte[32], new byte[64]), new byte[0], "e".repeat(40), 7, "eeee");
    }

    /** A publisher that records what it was asked and answers as told. */
    private static final class TestPublisher implements CreateDialog.Publisher {
        final List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile Session.Published answer;
        volatile IOException failure;
        final Path keys;

        TestPublisher(Path keys) {
            this.keys = keys;
        }

        @Override
        public String problem(String name) {
            return name.isBlank() ? "A key needs a name." : "";
        }

        @Override
        public Path keysDir() {
            return keys;
        }

        @Override
        public Session.Published publish(String name, CreatedTorrent made) throws IOException {
            asked.add("publish " + name);
            if (failure != null) throw failure;
            return answer;
        }

        @Override
        public Session.Published newVersion(String name, CreatedTorrent made) throws IOException {
            asked.add("newVersion " + name);
            if (failure != null) throw failure;
            return answer;
        }
    }

    @Test
    void theSourceIsSizedAndARunIsSavedAndShown(@TempDir Path dir) throws Exception {
        Path folder = content(dir);
        Settings settings = Fx.quietSettings(dir);
        FakeEngine engine = new FakeEngine().withCreate((request, progress) -> {
            progress.hashed(0, 0);
            progress.hashed(2, 4);
            progress.hashed(4, 4);
            return made(request, "");
        });
        Stage owner = owner();
        CreateDialog dialog = Fx.call(() -> new CreateDialog(owner, engine, settings, null));
        Fx.run(dialog::show);
        assertEquals(tr("dialog.create.title"), Fx.call(dialog::title));
        assertTrue(state(dialog).startsWith("create=idle | " + tr("dialog.create.chooseSource")), state(dialog));
        // Shown again: brought to the front, not opened twice.
        Fx.run(dialog::show);

        // Nothing chosen, then a folder that is not there: said, nothing created.
        Fx.run(() -> dialog.createForCapture(dir.resolve("x.torrent")));
        assertTrue(state(dialog).endsWith(tr("dialog.create.problem.noSource")), state(dialog));
        Fx.run(() -> dialog.setSource(dir.resolve("missing")));
        assertTrue(state(dialog).contains(tr("dialog.create.empty")), state(dialog));

        Fx.run(() -> dialog.setSource(folder));
        Fx.waitUntil(
                "the source to be sized", () -> !dialog.linesForCapture().get(0).contains(tr("dialog.create.sizing")));
        assertTrue(state(dialog).contains("Holiday"), state(dialog));
        // The format and piece size move the summary; v1 lets the alignment be chosen.
        Fx.run(() -> {
            @SuppressWarnings("unchecked")
            ChoiceBox<CreateRequest.Format> format =
                    Dialogs.all(root(dialog), ChoiceBox.class).get(0);
            @SuppressWarnings("unchecked")
            ChoiceBox<Integer> pieceSize =
                    Dialogs.all(root(dialog), ChoiceBox.class).get(1);
            format.setValue(CreateRequest.Format.V1);
            pieceSize.setValue(pieceSize.getItems().get(1));
            CheckBox align = Dialogs.labeled(root(dialog), CheckBox.class, tr("dialog.create.align"));
            assertFalse(align.isDisabled());
            align.setSelected(false);
            List<TextArea> areas = Dialogs.all(root(dialog), TextArea.class);
            areas.get(0).setText("https://a.example/announce\n\nudp://b.example:1");
            areas.get(1).setText("https://seed.example/files/");
            List<TextField> fields = Dialogs.all(root(dialog), TextField.class);
            fields.stream()
                    .filter(f -> tr("dialog.create.comment.prompt").equals(f.getPromptText()))
                    .findFirst()
                    .orElseThrow()
                    .setText(" holiday photos ");
            fields.stream()
                    .filter(f -> tr("dialog.create.sourceTag.prompt").equals(f.getPromptText()))
                    .findFirst()
                    .orElseThrow()
                    .setText("TAG");
            Dialogs.labeled(root(dialog), CheckBox.class, tr("dialog.create.private"))
                    .setSelected(true);
        });
        CreateRequest request = Fx.call(() -> dialog.request(folder));
        assertEquals(CreateRequest.Format.V1, request.format());
        assertTrue(request.isPrivate());
        assertFalse(request.alignFiles());
        assertEquals("TAG", request.source());
        assertEquals(List.of(List.of("https://a.example/announce"), List.of("udp://b.example:1")), request.trackers());
        assertEquals("holiday photos", request.comment());
        assertEquals(List.of("https://seed.example/files/"), request.webSeeds());

        Path target = dir.resolve("out/Holiday.torrent");
        Fx.run(() -> dialog.createForCapture(target));
        Fx.waitUntil("the run to end", () -> dialog.linesForCapture().get(0).startsWith("create=created"));
        assertArrayEquals(BYTES, Files.readAllBytes(target));
        List<String> lines = Fx.call(dialog::linesForCapture);
        assertTrue(lines.contains("create-v1=" + "e".repeat(40)), lines.toString());
        assertTrue(lines.contains("create-file=" + target), lines.toString());
        assertTrue(engine.calls().contains("create " + folder), engine.calls().toString());
        // The copy buttons: the v2 one is off (none), the others copy.
        Fx.run(() -> {
            List<Button> copies = Dialogs.all(root(dialog), Button.class).stream()
                    .filter(b -> tr("common.copy").equals(b.getText()))
                    .toList();
            assertTrue(copies.get(1).isDisabled(), "no v2 hash to copy");
            copies.get(0).fire();
            assertEquals(
                    "e".repeat(40),
                    javafx.scene.input.Clipboard.getSystemClipboard().getString());
        });
        // Escape closes it.
        Fx.run(() -> Event.fireEvent(
                root(dialog), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false)));
        assertFalse(Fx.call(dialog::isShowing));
    }

    @Test
    void aFailedRunSaysWhyAndACancelledOneSaysSo(@TempDir Path dir) throws Exception {
        Path folder = content(dir);
        Settings settings = Fx.quietSettings(dir);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean goOn = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicInteger mode = new java.util.concurrent.atomic.AtomicInteger();
        FakeEngine engine = new FakeEngine().withCreate((request, progress) -> {
            if (mode.get() == 0) throw new EngineException("the disk is full");
            if (mode.get() == 1) throw new IllegalStateException();
            started.countDown();
            try {
                release.await(Fx.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            goOn.set(progress.hashed(1, 4));
            // As the engine does: a false from the progress ends the hashing as cancelled.
            if (!goOn.get()) throw new EngineException("cancelled");
            return made(request, "f".repeat(64));
        });
        Stage owner = owner();
        CreateDialog dialog = Fx.call(() -> new CreateDialog(owner, engine, settings, null));
        Fx.run(() -> {
            dialog.show();
            dialog.setSource(folder);
        });
        Fx.run(() -> dialog.createForCapture(dir.resolve("a.torrent")));
        Fx.waitUntil("the failure", () -> dialog.linesForCapture().get(0).endsWith("the disk is full"));
        mode.set(1);
        Fx.run(() -> dialog.createForCapture(dir.resolve("a.torrent")));
        Fx.waitUntil("the failure", () -> dialog.linesForCapture().get(0).endsWith("IllegalStateException"));

        mode.set(2);
        Fx.run(() -> dialog.createForCapture(dir.resolve("b.torrent")));
        assertTrue(started.await(Fx.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        assertTrue(state(dialog).startsWith("create=hashing"), state(dialog));
        Fx.run(() -> {
            Button cancel = Dialogs.labeled(root(dialog), Button.class, tr("common.cancel"));
            cancel.fire();
            cancel.fire(); // nothing running any more: nothing to cancel
        });
        assertTrue(state(dialog).endsWith(tr("dialog.create.cancelled")), state(dialog));
        release.countDown();
        Fx.waitUntil("the stale run to stop at its next piece", () -> !goOn.get());
        Fx.pulse();
        assertTrue(state(dialog).startsWith("create=idle"), "a stale run's answer is dropped: " + state(dialog));
        assertFalse(Files.exists(dir.resolve("b.torrent.part")));
    }

    @Test
    void theChoosersAndCreateAskThePlatformWhichHeadlessHasNone(@TempDir Path dir) throws Exception {
        Path folder = content(dir);
        Settings settings = Fx.quietSettings(dir);
        Stage owner = owner();
        CreateDialog dialog = Fx.call(() -> new CreateDialog(owner, new FakeEngine(), settings, null));
        Fx.run(dialog::show);
        Fx.run(() -> {
            Button create = Dialogs.labeled(root(dialog), Button.class, tr("dialog.create.create"));
            create.fire(); // no source: said, no chooser
            assertTrue(dialog.linesForCapture().get(0).endsWith(tr("dialog.create.problem.noSource")));
            for (String which : List.of("dialog.create.file", "dialog.create.folder")) {
                Button b = Dialogs.labeled(root(dialog), Button.class, tr(which));
                assertThrows(UnsupportedOperationException.class, b::fire);
            }
            dialog.setSource(folder.resolve("a.jpg"));
            for (String which : List.of("dialog.create.file", "dialog.create.folder"))
                assertThrows(
                        UnsupportedOperationException.class,
                        Dialogs.labeled(root(dialog), Button.class, tr(which))::fire);
            assertThrows(UnsupportedOperationException.class, create::fire);
        });
    }

    @Test
    void publishingAsUpdatableShowsTheLinkOrWhyNot(@TempDir Path dir) throws Exception {
        Path folder = content(dir);
        Settings settings = Fx.quietSettings(dir);
        FakeEngine engine = new FakeEngine().withCreate((request, progress) -> made(request, "f".repeat(64)));
        TestPublisher publisher = new TestPublisher(dir.resolve("keys"));
        publisher.answer = new Session.Published(publication("photos"), 12, true);
        Stage owner = owner();
        CreateDialog dialog = Fx.call(() -> new CreateDialog(owner, engine, settings, publisher));
        Fx.run(() -> {
            dialog.show();
            dialog.setSource(folder);
            // Publishing needs a name.
            dialog.publishForCapture("");
            assertTrue(Dialogs.labeled(root(dialog), CheckBox.class, tr("dialog.create.seed"))
                    .isDisabled());
            dialog.createForCapture(dir.resolve("p.torrent"));
            assertTrue(dialog.linesForCapture().get(0).endsWith("A key needs a name."));
            Dialogs.labeled(root(dialog), Button.class, tr("dialog.create.create"))
                    .fire();
            assertTrue(dialog.linesForCapture().get(0).endsWith("A key needs a name."));
            dialog.publishForCapture("photos");
            dialog.createForCapture(dir.resolve("p.torrent"));
        });
        Fx.waitUntil(
                "the publication",
                () -> dialog.linesForCapture().stream().anyMatch(l -> l.startsWith("create-publish=photos | magnet:")));
        assertEquals(List.of("publish photos"), publisher.asked);
        assertTrue(Fx.call(dialog::linesForCapture).toString().contains("create-v2=" + "f".repeat(64)));

        // Not reached yet, then failing: the torrent is made either way.
        publisher.answer = new Session.Published(publication("photos"), 0, false);
        Fx.run(() -> dialog.createForCapture(dir.resolve("p.torrent")));
        Fx.waitUntil(
                "the second", () -> dialog.linesForCapture().toString().contains(tr("dialog.create.notPublishedYet")));
        publisher.failure = new IOException("no DHT");
        Fx.run(() -> dialog.createForCapture(dir.resolve("p.torrent")));
        Fx.waitUntil("the third", () -> dialog.linesForCapture().toString().contains("no DHT"));

        // The next version of a publication: its key, fixed.
        publisher.failure = null;
        publisher.answer = new Session.Published(publication("photos"), -1, true);
        Fx.run(() -> {
            dialog.forNewVersion(publication("photos"), folder);
            dialog.createForCapture(dir.resolve("p2.torrent"));
        });
        Fx.waitUntil("the new version", () -> publisher.asked.contains("newVersion photos"));
        Fx.waitUntil(
                "the line",
                () -> dialog.linesForCapture().stream().anyMatch(l -> l.startsWith("create-publish=next of photos")));
        Fx.run(dialog::close);
    }

    @Test
    void theNextVersionWaitsForARunInProgress(@TempDir Path dir) throws Exception {
        Path folder = content(dir);
        CountDownLatch release = new CountDownLatch(1);
        FakeEngine engine = new FakeEngine().withCreate((request, progress) -> {
            try {
                release.await(Fx.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return made(request, "");
        });
        TestPublisher publisher = new TestPublisher(dir.resolve("keys"));
        Stage owner = owner();
        CreateDialog dialog = Fx.call(() -> new CreateDialog(owner, engine, Fx.quietSettings(dir), publisher));
        Fx.run(() -> {
            dialog.show();
            dialog.setSource(folder);
            dialog.createForCapture(dir.resolve("q.torrent"));
            dialog.forNewVersion(publication("photos"), folder.resolve("more"));
            assertFalse(dialog.linesForCapture().toString().contains("next of"), "ignored while hashing");
            dialog.close(); // a run in progress goes with the dialog
        });
        release.countDown();
        Fx.pulse();
        assertTrue(state(dialog).startsWith("create=hashing"), "the closed dialog's run was dropped: " + state(dialog));
    }

    @Test
    void aTorrentIsWrittenBesideItsNameAndMovedIntoPlace(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("deep/er/x.torrent");
        CreateDialog.write(target, BYTES);
        assertArrayEquals(BYTES, Files.readAllBytes(target));
        CreateDialog.write(target, new byte[] {1});
        assertArrayEquals(new byte[] {1}, Files.readAllBytes(target));
        try (var left = Files.list(target.getParent())) {
            assertEquals(1, left.count(), "no part file left behind");
        }
        assertThrows(IOException.class, () -> CreateDialog.write(target.getRoot(), BYTES));
    }
}
