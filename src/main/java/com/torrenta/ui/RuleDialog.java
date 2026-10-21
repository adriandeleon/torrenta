package com.torrenta.ui;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import javafx.util.StringConverter;

import com.torrenta.engine.Category;
import com.torrenta.rss.Article;
import com.torrenta.rss.EpisodeFilter;
import com.torrenta.rss.Feed;
import com.torrenta.rss.Rule;
import com.torrenta.rss.RuleMatcher;

import static com.torrenta.i18n.Messages.tr;

/**
 * One auto-download rule, added or edited: the form on the left (name, what the title must and
 * must not contain, regex or phrases, the episode filter with its help line, the smart filter,
 * how old is too old, which feeds, paused or not, category, save path) and, on the right, the
 * articles the rule matches right now, re-evaluated a moment after every change, the matching
 * off the FX thread. Shaped as the Add dialog: its own stage, the app's stylesheet and font,
 * Enter saves, Escape cancels. Save is held back while the rule has a problem (no name, a name
 * another rule has, a broken regex or episode filter), which is said under the form.
 */
final class RuleDialog {

    /** Where the preview's articles come from: the service, or a list in a capture. */
    interface Articles {
        List<Article> all();

        Map<String, String> namesById();
    }

    private static final Duration SETTLE = Duration.millis(250);

    private final Stage stage = new Stage();
    private final TextField name = new TextField();
    private final CheckBox enabled = new CheckBox(tr("common.enabled"));
    private final TextField mustContain = new TextField();
    private final TextField mustNotContain = new TextField();
    private final CheckBox useRegex = new CheckBox(tr("dialog.rule.useRegex"));
    private final TextField episodeFilter = new TextField();
    private final CheckBox smartFilter = new CheckBox(tr("dialog.rule.smartFilter"));
    private final Spinner<Integer> ignoreDays = new Spinner<>();
    private final List<CheckBox> feedBoxes = new ArrayList<>();
    private final ChoiceBox<Rule.AddPaused> addPaused = new ChoiceBox<>();
    private final ChoiceBox<String> category = new ChoiceBox<>();
    private final TextField savePath = new TextField();
    private final Label problem = new Label();
    private final Label matchesHeading = new Label();
    private final ListView<String> matches = new ListView<>();
    private final Button save = new Button(tr("common.save"));
    private final PauseTransition settle = new PauseTransition(SETTLE);
    private final AtomicInteger generation = new AtomicInteger();
    private final Articles articles;
    private final Set<String> otherNames;
    private final Rule existing;
    private Rule result;
    private int lastMatched = -1;
    private int lastTotal;

    /**
     * @param existing the rule being edited, or null for a new one
     * @param feeds the feeds that exist, offered as the rule's
     * @param categories the categories that exist, offered for the torrents
     * @param otherNames the names the other rules have, which this one may not take
     * @param articles the articles the preview runs over
     */
    RuleDialog(
            Window owner,
            Rule existing,
            List<Feed> feeds,
            List<Category> categories,
            Set<String> otherNames,
            Articles articles) {
        this.existing = existing;
        this.otherNames = otherNames;
        this.articles = articles;
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle(existing == null ? tr("dialog.rule.title.add") : tr("dialog.rule.title.edit"));

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(8);
        ColumnConstraints labels = new ColumnConstraints();
        labels.setHalignment(javafx.geometry.HPos.RIGHT);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(labels, fields);

        int row = 0;
        name.setPromptText(tr("dialog.rule.name.prompt"));
        HBox.setHgrow(name, Priority.ALWAYS);
        HBox nameRow = new HBox(10, name, enabled);
        nameRow.setAlignment(Pos.CENTER_LEFT);
        form.addRow(row++, muted(tr("common.name")), nameRow);
        mustContain.setPromptText(tr("dialog.rule.mustContain.prompt"));
        form.addRow(row++, muted(tr("dialog.rule.mustContain")), mustContain);
        mustNotContain.setPromptText(tr("dialog.rule.mustNotContain.prompt"));
        form.addRow(row++, muted(tr("dialog.rule.mustNotContain")), mustNotContain);
        form.add(useRegex, 1, row++);
        episodeFilter.setPromptText(tr("dialog.rule.episodeFilter.prompt"));
        form.addRow(row++, muted(tr("dialog.rule.episodeFilter")), episodeFilter);
        Label episodeHelp = hint(EpisodeFilter.help());
        form.add(episodeHelp, 1, row++);
        smartFilter.setWrapText(true);
        smartFilter.setMinHeight(Region.USE_PREF_SIZE);
        form.add(smartFilter, 1, row++);
        ignoreDays.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, Rule.MAX_IGNORE_DAYS, 0, 1));
        ignoreDays.setEditable(true);
        ignoreDays.setPrefWidth(90);
        ignoreDays.setMinWidth(90);
        Label daysHint = hint(tr("dialog.rule.days.hint"));
        HBox.setHgrow(daysHint, Priority.ALWAYS);
        HBox daysRow = new HBox(8, ignoreDays, daysHint);
        daysRow.setAlignment(Pos.CENTER_LEFT);
        form.addRow(row++, muted(tr("dialog.rule.ignoreOlderThan")), daysRow);

        VBox feedList = new VBox(4);
        for (Feed f : feeds) {
            CheckBox box = new CheckBox(f.displayName());
            box.setUserData(f.id());
            feedBoxes.add(box);
            feedList.getChildren().add(box);
        }
        Label feedsHint = hint(feeds.isEmpty() ? tr("dialog.rule.feeds.none") : tr("dialog.rule.feeds.hint"));
        feedList.getChildren().add(feedsHint);
        ScrollPane feedScroll = new ScrollPane(feedList);
        feedScroll.setFitToWidth(true);
        feedScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        feedScroll.getStyleClass().add("rule-feeds");
        feedScroll.setPrefHeight(Math.clamp(26 * (feeds.size() + 1) + 12, 70, 110));
        feedScroll.setMinHeight(70);
        form.addRow(row++, muted(tr("dialog.rule.feeds")), feedScroll);

        addPaused.getItems().addAll(Rule.AddPaused.values());
        addPaused.setConverter(new StringConverter<>() {
            @Override
            public String toString(Rule.AddPaused p) {
                return p == null ? "" : p.label();
            }

            @Override
            public Rule.AddPaused fromString(String s) {
                return null;
            }
        });
        form.addRow(row++, muted(tr("dialog.rule.start")), addPaused);

        category.getItems().add("");
        for (Category c : categories) category.getItems().add(c.name());
        category.setConverter(new StringConverter<>() {
            @Override
            public String toString(String n) {
                return n == null || n.isEmpty() ? tr("common.none") : n;
            }

            @Override
            public String fromString(String s) {
                return null;
            }
        });
        form.addRow(row++, muted(tr("dialog.common.category")), category);

        savePath.setPromptText(tr("dialog.rule.savePath.prompt"));
        HBox.setHgrow(savePath, Priority.ALWAYS);
        Button browse = new Button(tr("common.browse"));
        browse.setOnAction(e -> browse());
        form.addRow(row++, muted(tr("dialog.common.saveTo")), new HBox(6, savePath, browse));

        problem.getStyleClass().add("dialog-problem");
        problem.setWrapText(true);
        problem.setMaxWidth(Double.MAX_VALUE);
        VBox left = new VBox(10, form, problem);
        left.setMinWidth(470);

        matchesHeading.getStyleClass().add("rule-matches-heading");
        matches.getStyleClass().add("rule-matches");
        matches.setPlaceholder(new Label(tr("dialog.rule.matches.placeholder")));
        matches.setFocusTraversable(false);
        VBox.setVgrow(matches, Priority.ALWAYS);
        Label matchesHint = hint(tr("dialog.rule.matches.hint"));
        VBox right = new VBox(6, matchesHeading, matches, matchesHint);
        right.setMinWidth(220);
        SplitPane split = new SplitPane(left, right);
        split.setDividerPositions(0.58);
        SplitPane.setResizableWithParent(left, false);
        VBox.setVgrow(split, Priority.ALWAYS);

        save.setDefaultButton(true);
        save.setOnAction(e -> confirm());
        Button cancel = new Button(tr("common.cancel"));
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> cancel());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, spacer, cancel, save);

        VBox content = new VBox(12, split, buttons);
        content.setPadding(new Insets(14));
        Scene scene = new Scene(content, 960, 640);
        scene.getStylesheets()
                .add(RuleDialog.class
                        .getResource("/com/torrenta/styles/app.css")
                        .toExternalForm());
        Fonts.installUiFont(scene);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) cancel();
        });
        stage.setScene(scene);

        if (existing == null) {
            enabled.setSelected(true);
            addPaused.setValue(Rule.AddPaused.DEFAULT);
            category.setValue("");
        } else {
            name.setText(existing.name());
            enabled.setSelected(existing.enabled());
            mustContain.setText(existing.mustContain());
            mustNotContain.setText(existing.mustNotContain());
            useRegex.setSelected(existing.useRegex());
            episodeFilter.setText(existing.episodeFilter());
            smartFilter.setSelected(existing.smartEpisodeFilter());
            ignoreDays.getValueFactory().setValue(existing.ignoreDays());
            for (CheckBox box : feedBoxes) box.setSelected(existing.feeds().contains((String) box.getUserData()));
            addPaused.setValue(existing.addPaused());
            category.setValue(category.getItems().contains(existing.category()) ? existing.category() : "");
            savePath.setText(existing.savePath());
        }

        // Every field feeds the problem line at once and the preview after a pause.
        problem.textProperty()
                .bind(Bindings.createStringBinding(
                        () -> problemText(),
                        name.textProperty(),
                        mustContain.textProperty(),
                        mustNotContain.textProperty(),
                        useRegex.selectedProperty(),
                        episodeFilter.textProperty()));
        save.disableProperty().bind(problem.textProperty().isNotEmpty());
        settle.setOnFinished(e -> evaluate());
        Runnable poke = this::poke;
        for (TextField f : List.of(name, mustContain, mustNotContain, episodeFilter)) {
            f.textProperty().addListener(o -> poke.run());
        }
        useRegex.selectedProperty().addListener(o -> poke.run());
        smartFilter.selectedProperty().addListener(o -> poke.run());
        ignoreDays.valueProperty().addListener(o -> poke.run());
        for (CheckBox box : feedBoxes) box.selectedProperty().addListener(o -> poke.run());
        evaluate();
    }

    /** Blocks on a nested event loop, as a dialog does; empty when cancelled. */
    Optional<Rule> showAndWait() {
        (name.getText().isBlank() ? name : mustContain).requestFocus();
        stage.showAndWait();
        return Optional.ofNullable(result);
    }

    Scene scene() {
        return stage.getScene();
    }

    boolean isShowing() {
        return stage.isShowing();
    }

    String title() {
        return stage.getTitle();
    }

    /** Types into the fields, as a person would; for "create rule from title" and a capture. */
    void prefill(String ruleName, String mustContainText) {
        name.setText(ruleName);
        mustContain.setText(mustContainText);
    }

    /** The problem line as it reads now; empty when the rule is fine. */
    String problemText() {
        Rule r = rule();
        if (otherNames.contains(r.name())) return tr("dialog.rule.problem.nameTaken", r.name());
        return RuleMatcher.problem(r).orElse("");
    }

    /** The Matches heading and lines, for a capture: "Matches — 2 of 7 articles | title · feed | …". */
    String matchesForCapture() {
        return matchesHeading.getText()
                + (matches.getItems().isEmpty() ? "" : " | " + String.join(" | ", matches.getItems()));
    }

    /** Whether a preview is still being worked out: a capture waits for it. */
    boolean evaluating() {
        return settle.getStatus() == javafx.animation.Animation.Status.RUNNING || lastMatched < 0;
    }

    void cancel() {
        result = null;
        stage.hide();
    }

    /** Presses Save: the rule as the fields say, with what the one being edited remembered. */
    void confirm() {
        if (save.isDisabled()) return;
        result = rule();
        stage.hide();
    }

    /** The rule as the fields read now; the memory and last match are the edited rule's. */
    Rule rule() {
        List<String> feeds = new ArrayList<>();
        for (CheckBox box : feedBoxes) if (box.isSelected()) feeds.add((String) box.getUserData());
        Integer days = ignoreDays.getValue();
        return new Rule(
                name.getText(),
                enabled.isSelected(),
                mustContain.getText(),
                mustNotContain.getText(),
                useRegex.isSelected(),
                episodeFilter.getText(),
                smartFilter.isSelected(),
                feeds,
                days == null ? 0 : days,
                addPaused.getValue(),
                category.getValue(),
                savePath.getText(),
                existing == null ? null : existing.lastMatch(),
                existing == null ? List.of() : existing.previouslyMatched());
    }

    private void poke() {
        matchesHeading.setText(RuleText.matchesHeading(Math.max(lastMatched, 0), lastTotal, true));
        settle.playFromStart();
    }

    /** FX thread: snapshots the rule and the articles, matches on a virtual thread, shows the answer if still wanted. */
    private void evaluate() {
        Rule r = rule();
        int gen = generation.incrementAndGet();
        List<Article> all = articles.all();
        Map<String, String> names = articles.namesById();
        if (RuleMatcher.problem(r).isPresent()) {
            show(gen, List.of(), all.size());
            return;
        }
        Thread.ofVirtual().name("rule-preview").start(() -> {
            RuleMatcher matcher = RuleMatcher.compile(r);
            Instant now = Instant.now();
            List<String> lines = new ArrayList<>();
            for (Article a : all) {
                if (matcher.match(a, now) != null) lines.add(RuleText.matchLine(a, names.getOrDefault(a.feedId(), "")));
            }
            Platform.runLater(() -> show(gen, lines, all.size()));
        });
    }

    private void show(int gen, List<String> lines, int total) {
        if (gen != generation.get()) return;
        lastMatched = lines.size();
        lastTotal = total;
        matches.getItems().setAll(lines);
        matchesHeading.setText(RuleText.matchesHeading(lines.size(), total, false));
    }

    private void browse() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(tr("dialog.common.savePathChooser"));
        File current = new File(savePath.getText().trim());
        while (current != null && !current.isDirectory()) current = current.getParentFile();
        if (current != null) chooser.setInitialDirectory(current);
        File chosen = chooser.showDialog(stage);
        if (chosen != null) savePath.setText(chosen.getAbsolutePath());
    }

    private static Label hint(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("dialog-hint");
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    private static Label muted(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("dialog-label");
        label.setMinWidth(Region.USE_PREF_SIZE);
        return label;
    }
}
