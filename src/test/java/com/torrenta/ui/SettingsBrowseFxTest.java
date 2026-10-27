package com.torrenta.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javafx.scene.control.Button;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static com.torrenta.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every Browse… on the Settings pages starts from the folder its field names and opens the
 * platform's chooser. The headless toolkit has none to show and refuses, so what a choice would
 * write is out of reach here; what is checked is that nothing is written before the chooser.
 */
@ExtendWith(FxToolkit.class)
class SettingsBrowseFxTest {

    @Test
    void everyBrowseButtonReachesThePlatformsChooser(@TempDir Path dir) {
        SettingsFixture f = SettingsFixture.open(dir);
        // The incomplete folder's Browse waits for its switch.
        Fx.run(() -> {
            f.settings.setIncompleteEnabled(true);
            f.window.reloadFromSettings();
        });
        int pressed = 0;
        for (SettingsWindow.Category page : SettingsWindow.Category.values()) {
            f.page(page);
            List<Button> browse = Fx.call(
                    () -> Modals.all(f.root(), Button.class, b -> b.getText().equals(tr("common.browse"))));
            for (Button b : browse) {
                Map<String, String> before = f.saved();
                if (Fx.call(b::isDisabled)) continue;
                // The headless platform has no chooser to show, and says so.
                assertThrows(UnsupportedOperationException.class, () -> Fx.run(b::fire), page + "'s chooser");
                assertEquals(before, f.saved(), page + ": a cancelled chooser writes nothing");
                pressed++;
            }
        }
        assertTrue(pressed >= 6, pressed + " Browse buttons");

        // Export to zip asks where, through the same kind of chooser.
        com.torrenta.store.DefinitionStore definitions = new com.torrenta.store.DefinitionStore(dir);
        assertThrows(Exception.class, () -> {
            definitions.installExamples();
            definitions.load();
            Fx.run(() -> f.window.setSearchDefinitions(definitions, () -> {}));
            f.page(SettingsWindow.Category.SEARCH);
            Fx.run(() -> f.button("settings.search.export").fire());
        });
    }

    @Test
    void theDialogsBrowseButtonsReachThePlatformsChooserToo(@TempDir Path dir) {
        javafx.stage.Stage owner = Fx.call(javafx.stage.Stage::new);
        com.torrenta.engine.WatchFolder existing = new com.torrenta.engine.WatchFolder(
                dir.resolve("inbox").toString(),
                com.torrenta.engine.WatchFolder.AfterAdd.RENAME,
                com.torrenta.engine.WatchFolder.SaveTo.FOLDER,
                dir.toString(),
                "Films",
                false);
        WatchFolderDialog watch = Fx.call(
                () -> new WatchFolderDialog(owner, existing, List.of(new com.torrenta.engine.Category("Films", ""))));
        List<Button> buttons = Fx.call(() -> Modals.all(
                watch.scene().getRoot(), Button.class, b -> b.getText().equals(tr("common.browse"))));
        assertEquals(2, buttons.size());
        for (Button b : buttons) assertThrows(UnsupportedOperationException.class, () -> Fx.run(b::fire));

        RuleDialog rule = Fx.call(() ->
                new RuleDialog(owner, null, List.of(), List.of(), java.util.Set.of(), new RuleDialog.Articles() {
                    @Override
                    public List<com.torrenta.rss.Article> all() {
                        return List.of();
                    }

                    @Override
                    public Map<String, String> namesById() {
                        return Map.of();
                    }
                }));
        Button browse = Fx.call(() -> Modals.button(rule.scene().getRoot(), tr("common.browse")));
        assertThrows(UnsupportedOperationException.class, () -> Fx.run(browse::fire));
    }
}
