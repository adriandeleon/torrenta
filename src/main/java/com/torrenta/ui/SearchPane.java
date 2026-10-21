package com.torrenta.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import javafx.application.Platform;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.torrenta.search.ResultRanker;
import com.torrenta.search.SearchCategory;
import com.torrenta.search.SearchOutcome;
import com.torrenta.search.SearchProvider;
import com.torrenta.search.SearchResult;
import com.torrenta.search.SearchService;
import com.torrenta.store.Settings;

/**
 * The Search tab: a query field, a row of category chips (one chosen, All by default), a row of
 * provider chips (every enabled provider, on unless switched off), a status line with one clause
 * per provider, and the results table. Enter in the query runs the search on a worker, each
 * provider's answer landing in the table as it arrives; a new search cancels the running one.
 * Enter or a double-click on a result hands it to the host, which opens the Add dialog.
 *
 * <p>Driveable without the mouse: Tab walks the query, the chips and the table; Space toggles a
 * chip; Escape in the query or on a chip leaves the tab, Escape in the table returns to the
 * query, Down from the query enters the table. The pane is hidden while Transfers shows, so its
 * traversable controls never take the first keystroke of a fresh launch.
 */
final class SearchPane extends VBox {

    /** What the pane needs from the window; every call on the FX thread. */
    interface Host {
        /**
         * The enabled providers, built: the Torznab endpoints in the store's order, then the
         * definition files in the folder's, each on unless disabled in Settings.
         */
        List<SearchProvider> enabledProviders();

        /** Settings → Search, for the "no provider yet" link. */
        void openProviderSettings();

        /** Enter, a double-click or the menu's Add: open the Add dialog for it. */
        void add(SearchResult result);

        /** The result's detail page, in the browser. */
        void openDetail(String url);

        /** Text onto the clipboard. */
        void copy(String text);

        /** Escape: back to the transfers. */
        void leave();
    }

    private final Host host;
    private final Settings settings;
    private final TextField query = new TextField();
    private final ToggleGroup categoryGroup = new ToggleGroup();
    private final FlowPane categoryChips = new FlowPane(6, 6);
    private final FlowPane providerChips = new FlowPane(6, 6);
    private final Hyperlink noProviders = new Hyperlink("No search provider yet: add one in Settings → Search");
    private final Label status = new Label();
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final SearchRows rows = new SearchRows();
    private final TableView<SearchRow> table;
    private final SearchService service = new SearchService();
    /** The providers switched off by hand, by name; see {@link SearchChips}. */
    private Set<String> providersOff = new LinkedHashSet<>();

    private Thread running;
    private int generation;
    private boolean searched;
    /** Run when the running search ends; the capture's keys wait for the rows. */
    private Runnable whenDone;

    SearchPane(Host host, Settings settings) {
        this.host = host;
        this.settings = settings;
        getStyleClass().add("search-pane");
        table = buildTable();
        VBox.setVgrow(table, Priority.ALWAYS);

        query.setPromptText("Search every provider — Enter searches, Esc returns to the transfers");
        query.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                runSearch();
                e.consume();
            } else if (e.getCode() == KeyCode.DOWN && !table.getItems().isEmpty()) {
                focusResults();
                e.consume();
            }
        });
        HBox.setHgrow(query, Priority.ALWAYS);
        HBox queryRow = new HBox(8, query);
        queryRow.setAlignment(Pos.CENTER_LEFT);

        for (SearchCategory c : SearchCategory.values()) {
            ToggleButton chip = chip(c.label());
            chip.setUserData(c);
            chip.setToggleGroup(categoryGroup);
            chip.setSelected(c == SearchCategory.ALL);
            categoryChips.getChildren().add(chip);
        }
        // A group lets its one toggle be clicked off; the category is then All again.
        categoryGroup.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null && was != null) {
                Platform.runLater(() -> categoryGroup.selectToggle(categoryChips.getChildren().stream()
                        .map(n -> (ToggleButton) n)
                        .filter(b -> b.getUserData() == SearchCategory.ALL)
                        .findFirst()
                        .orElse(null)));
            }
        });

        noProviders.setOnAction(e -> host.openProviderSettings());
        noProviders.getStyleClass().add("search-hint");
        noProviders.setFocusTraversable(true);

        spinner.setPrefSize(14, 14);
        spinner.setMinSize(14, 14);
        spinner.setMaxSize(14, 14);
        spinner.setVisible(false);
        spinner.managedProperty().bind(spinner.visibleProperty());
        status.getStyleClass().add("search-status");
        status.setWrapText(true);
        HBox.setHgrow(status, Priority.ALWAYS);
        HBox statusRow = new HBox(8, spinner, status);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        VBox top = new VBox(
                8, queryRow, row("Category", categoryChips), row("Providers", providerChips), noProviders, statusRow);
        top.setPadding(new Insets(10, 12, 8, 12));
        top.getStyleClass().add("search-head");
        getChildren().addAll(top, table);
        setVisible(false);
        setManaged(false);

        // Escape from anything but the table (which handles its own) leaves the tab.
        addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE && !e.isConsumed()) {
                host.leave();
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER && !e.isConsumed() && e.getTarget() instanceof ToggleButton) {
                runSearch();
                e.consume();
            }
        });
        reloadProviders();
    }

    private static HBox row(String title, FlowPane chips) {
        // A flow pane sizes its height for its wrap length (400 by default), which would reserve
        // two lines for nine chips; one line is the preference, wrapping only when the window is
        // narrower than the chips.
        chips.setPrefWrapLength(4000);
        chips.setAlignment(Pos.CENTER_LEFT);
        Label label = new Label(title);
        label.getStyleClass().add("search-row-title");
        label.setMinWidth(70);
        HBox.setHgrow(chips, Priority.ALWAYS);
        HBox row = new HBox(8, label, chips);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static ToggleButton chip(String text) {
        ToggleButton chip = new ToggleButton(text);
        chip.getStyleClass().add("search-chip");
        chip.setFocusTraversable(true);
        return chip;
    }

    /** The provider chips from the store: every enabled provider, on unless switched off before. */
    void reloadProviders() {
        List<SearchProvider> enabled = host.enabledProviders();
        providersOff = SearchChips.keep(providersOff, enabled);
        providerChips.getChildren().clear();
        for (SearchProvider e : enabled) {
            ToggleButton chip = chip(e.name());
            chip.setSelected(!providersOff.contains(e.name()));
            chip.selectedProperty().addListener((o, was, now) -> {
                if (now) providersOff.remove(e.name());
                else providersOff.add(e.name());
            });
            providerChips.getChildren().add(chip);
        }
        boolean none = enabled.isEmpty();
        noProviders.setVisible(none);
        noProviders.setManaged(none);
        providerChips.getParent().setVisible(!none);
        providerChips.getParent().setManaged(!none);
        if (!searched)
            showProblem(SearchStatus.cannotSearch(
                    !none, !SearchChips.chosen(enabled, providersOff).isEmpty(), false));
    }

    /** The link above already says there is no provider; the status line does not say it twice. */
    private void showProblem(String problem) {
        status.setText(noProviders.isVisible() ? "" : problem);
    }

    SearchCategory category() {
        Toggle t = categoryGroup.getSelectedToggle();
        return t == null ? SearchCategory.ALL : (SearchCategory) t.getUserData();
    }

    /** The providers the chips leave on. */
    List<SearchProvider> chosenProviders() {
        return SearchChips.chosen(host.enabledProviders(), providersOff);
    }

    void focusQuery() {
        query.requestFocus();
        query.selectAll();
    }

    void focusResults() {
        table.requestFocus();
        if (table.getSelectionModel().isEmpty() && !table.getItems().isEmpty())
            table.getSelectionModel().select(0);
    }

    void setQuery(String text) {
        query.setText(text);
    }

    String queryText() {
        return query.getText();
    }

    /** The status line, for the capture. */
    String statusText() {
        return status.getText();
    }

    /** Whether a search is still being waited for. */
    boolean searching() {
        return spinner.isVisible();
    }

    /** The chips as text, for the capture: "All* Movies … | Prowlarr* Jackett". */
    String chipsForCapture() {
        StringBuilder sb = new StringBuilder();
        for (var n : categoryChips.getChildren()) {
            ToggleButton b = (ToggleButton) n;
            if (sb.length() > 0) sb.append(' ');
            sb.append(b.getText()).append(b.isSelected() ? "*" : "");
        }
        sb.append(" |");
        for (var n : providerChips.getChildren()) {
            ToggleButton b = (ToggleButton) n;
            sb.append(' ').append(b.getText()).append(b.isSelected() ? "*" : "");
        }
        return sb.toString();
    }

    /** The rows as shown, top to bottom, for the capture. */
    List<String> rowsForCapture() {
        List<String> out = new ArrayList<>();
        for (SearchRow row : table.getItems()) {
            SearchResult r = row.result();
            out.add(r.name() + " | " + sizeText(r.size()) + " | " + r.seeds() + "/" + r.leechers() + " | "
                    + r.provider());
        }
        return out;
    }

    /**
     * Keys pressed where the focus is (the query field at first, the table after a Down), for the
     * capture, once the running search (if any) has ended, so the rows are there to walk.
     */
    void pressForCapture(List<KeyCode> codes) {
        Runnable press = () -> {
            for (KeyCode code : codes) {
                javafx.scene.Node owner = getScene() == null ? null : getScene().getFocusOwner();
                javafx.scene.Node target = owner;
                while (target != null && target != this) target = target.getParent();
                if (target == null) owner = query;
                owner.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
            }
        };
        if (searching()) whenDone = press;
        else press.run();
    }

    /** Runs the query as typed: Enter, or the capture. */
    void runSearch() {
        String q = query.getText().trim();
        List<SearchProvider> enabled = host.enabledProviders();
        List<SearchProvider> chosen = SearchChips.chosen(enabled, providersOff);
        String problem = SearchStatus.cannotSearch(!enabled.isEmpty(), !chosen.isEmpty(), !q.isEmpty());
        if (!problem.isEmpty()) {
            showProblem(problem);
            return;
        }
        cancelRunning();
        int gen = ++generation;
        searched = true;
        List<SearchProvider> providers = List.copyOf(chosen);
        List<String> names = providers.stream().map(SearchProvider::name).toList();
        SearchCategory cat = category();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> failures = new LinkedHashMap<>();
        status.setText(SearchStatus.line(names, counts, failures, false));
        spinner.setVisible(true);
        table.setPlaceholder(new Label("Searching…"));

        running = Thread.ofVirtual().name("search").start(() -> {
            Map<String, List<SearchResult>> answers = new LinkedHashMap<>();
            SearchService.Progress progress = new SearchService.Progress() {
                @Override
                public void answered(String provider, List<SearchResult> results) {
                    answers.put(provider, results);
                    List<SearchResult> merged = ResultRanker.merge(new ArrayList<>(answers.values()), q);
                    Platform.runLater(() -> {
                        if (gen != generation) return;
                        counts.put(provider, results.size());
                        rows.apply(merged);
                        status.setText(SearchStatus.line(names, counts, failures, false));
                    });
                }

                @Override
                public void failed(String provider, String reason) {
                    Platform.runLater(() -> {
                        if (gen != generation) return;
                        failures.put(provider, reason);
                        status.setText(SearchStatus.line(names, counts, failures, false));
                    });
                }
            };
            SearchOutcome outcome = service.search(q, cat, providers, progress);
            Platform.runLater(() -> {
                if (gen != generation) return;
                rows.apply(outcome.results());
                spinner.setVisible(false);
                status.setText(SearchStatus.line(names, counts, failures, true));
                table.setPlaceholder(new Label("Nothing found"));
                running = null;
                Runnable then = whenDone;
                whenDone = null;
                if (then != null) then.run();
            });
        });
    }

    /** A running search is dropped: its thread interrupted, its late answers ignored. */
    void cancelRunning() {
        generation++;
        Thread t = running;
        running = null;
        if (t != null) t.interrupt();
        spinner.setVisible(false);
    }

    // ---- the table ------------------------------------------------------------------------------

    private TableView<SearchRow> buildTable() {
        SortedList<SearchRow> sorted = new SortedList<>(rows.list());
        TableView<SearchRow> t = new TableView<>(sorted);
        sorted.comparatorProperty().bind(t.comparatorProperty());
        t.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        t.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        t.setTableMenuButtonVisible(true);
        t.setPlaceholder(new Label("No results yet"));
        t.getStyleClass().addAll("torrent-table", "search-table");

        TableColumn<SearchRow, String> name = column("name", "Name", 420, SearchResult::name, s -> s);
        name.setComparator(String.CASE_INSENSITIVE_ORDER);
        TableColumn<SearchRow, Integer> seeds =
                column("seeds", "Seeds", 70, SearchResult::seeds, SearchPane::countText);
        t.getColumns()
                .addAll(List.of(
                        name,
                        column("size", "Size", 90, SearchResult::size, SearchPane::sizeText),
                        seeds,
                        column("leechers", "Leechers", 80, SearchResult::leechers, SearchPane::countText),
                        column(
                                "date",
                                "Date",
                                150,
                                r -> r.published() == null ? -1L : r.published().getEpochSecond(),
                                Format::dateTime),
                        column("provider", "Provider", 120, SearchResult::provider, s -> s)));
        Headers.uppercase(t);
        // Seeds first by default; a click cycles the column, and no sort at all is the ranked order.
        seeds.setSortType(TableColumn.SortType.DESCENDING);
        t.getSortOrder().add(seeds);

        ColumnMemory.apply(t, ColumnLayout.parse(settings.get(Settings.SEARCH_COLUMNS, "")));
        ColumnMemory.watch(t, () -> {
            settings.set(Settings.SEARCH_COLUMNS, ColumnLayout.format(ColumnMemory.capture(t)));
            settings.saveQuietly();
        });

        t.setOnKeyPressed(e -> {
            SearchRow selected = t.getSelectionModel().getSelectedItem();
            if (e.getCode() == KeyCode.ENTER) {
                if (selected != null) host.add(selected.result());
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                focusQuery();
                e.consume();
            } else if (e.getCode() == KeyCode.C && e.isShortcutDown()) {
                if (selected != null) host.copy(selected.result().link().value());
                e.consume();
            }
        });
        t.setRowFactory(v -> {
            javafx.scene.control.TableRow<SearchRow> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !row.isEmpty())
                    host.add(row.getItem().result());
            });
            return row;
        });
        t.setContextMenu(buildContextMenu(t));
        return t;
    }

    private ContextMenu buildContextMenu(TableView<SearchRow> t) {
        MenuItem add = new MenuItem("Add…");
        add.setGraphic(Icons.plus().at(Icons.MENU_SIZE));
        MenuItem copy = new MenuItem("Copy Link");
        copy.setGraphic(Icons.copy().at(Icons.MENU_SIZE));
        MenuItem detail = new MenuItem("Open Detail Page");
        detail.setGraphic(Icons.external().at(Icons.MENU_SIZE));
        add.setOnAction(e -> selected(t).ifPresent(host::add));
        copy.setOnAction(e -> selected(t).ifPresent(r -> host.copy(r.link().value())));
        detail.setOnAction(e -> selected(t).ifPresent(r -> host.openDetail(r.detailUrl())));
        ContextMenu menu = new ContextMenu(add, copy, new SeparatorMenuItem(), detail);
        menu.setOnShowing(e -> {
            var r = selected(t);
            add.setDisable(r.isEmpty());
            copy.setDisable(r.isEmpty());
            copy.setText(r.map(SearchAdd::copyLabel).orElse("Copy Link"));
            detail.setDisable(r.map(x -> x.detailUrl().isEmpty()).orElse(true));
        });
        return menu;
    }

    private static java.util.Optional<SearchResult> selected(TableView<SearchRow> t) {
        SearchRow row = t.getSelectionModel().getSelectedItem();
        return row == null ? java.util.Optional.empty() : java.util.Optional.of(row.result());
    }

    /** A typed, comparable column over the row's result property, rendered through {@code text}. */
    private static <T extends Comparable<? super T>> TableColumn<SearchRow, T> column(
            String id, String title, double width, Function<SearchResult, T> value, Function<T, String> text) {
        TableColumn<SearchRow, T> column = new TableColumn<>(title);
        column.setId(id);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> cell.getValue().resultProperty().map(value));
        column.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : text.apply(item));
            }
        });
        return column;
    }

    static String sizeText(long size) {
        return size < 0 ? "—" : Bytes.human(size);
    }

    static String countText(int count) {
        return count < 0 ? "—" : Integer.toString(count);
    }

    /** The results' column ids, in their default order; the layout test holds them. */
    static List<String> columnIds() {
        return List.of("name", "size", "seeds", "leechers", "date", "provider");
    }
}
