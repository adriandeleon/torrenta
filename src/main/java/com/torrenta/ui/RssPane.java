package com.torrenta.ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.torrenta.rss.Article;
import com.torrenta.rss.Feed;
import com.torrenta.rss.RssService;
import com.torrenta.store.Settings;

/**
 * The RSS tab: the feeds on the left (All feeds first; name, unread count, a glyph while
 * refreshing or after a failure), the articles of the chosen feed on the right (title in bold
 * while unread, date, feed) under a filter box and an Unread-only toggle, and the chosen
 * article's text and link below them. Choosing an article marks it read.
 *
 * <p>Driveable without the mouse: arrows move, {@code Enter} on an article with a torrent opens
 * the Add dialog for it, {@code M} toggles read, {@code Shift+M} marks the feed read, {@code N}
 * or {@code Ctrl+N} adds a feed, {@code F5} refreshes the feed ({@code Shift+F5} every feed),
 * {@code Delete} in the feed list removes one after a confirm, {@code Ctrl+C} copies the link,
 * {@code Esc} leaves. The context menus mirror the keys. Hidden while another view shows, so its
 * traversable controls never take the first keystroke of a fresh launch.
 */
final class RssPane extends VBox {

    /** What the pane needs from the window; every call on the FX thread. */
    interface Host {
        /** Enter, a double-click or the menu's Add: open the Add dialog for the article's torrent. */
        void add(Article article);

        /** The menu's Download: add the torrent at once with the defaults and mark the article downloaded. */
        void download(Article article);

        /** The menu's Create rule from title: the rule dialog with the title's words filled in. */
        void createRule(Article article);

        /** The article's page, in the browser. */
        void openLink(String url);

        /** Text onto the clipboard. */
        void copy(String text);

        /** A sentence for the status bar. */
        void say(String text);

        /** Escape: back to the transfers. */
        void leave();
    }

    /** One line of the feed list; the empty id is "All feeds". */
    record FeedEntry(String id, String name, int unread, boolean failed, boolean refreshing) {
        boolean all() {
            return id.isEmpty();
        }
    }

    private static final PseudoClass UNREAD = PseudoClass.getPseudoClass("unread");

    private final Host host;
    private final RssService service;
    private final Settings settings;
    private final ListView<FeedEntry> feedList = new ListView<>();
    private final TextField filter = new TextField();
    private final ToggleButton unreadOnly = new ToggleButton("Unread only");
    private final Label status = new Label();
    private final ArticleRows rows = new ArticleRows();
    private final FilteredList<ArticleRow> filtered = new FilteredList<>(rows.list());
    private final TableView<ArticleRow> table;
    private final Label detailTitle = new Label();
    private final Label detailMeta = new Label();
    private final Hyperlink detailLink = new Hyperlink();
    private final Label detailText = new Label();
    private Map<String, String> names = Map.of();
    private FeedDialog dialog;
    /** The chosen article's key: it stays shown under Unread-only even once it is read. */
    private String selectedKey;

    private boolean reloading;
    /** Whether the split dividers have been placed; see the constructor. */
    private boolean placed;
    /** Run once no feed is refreshing; the capture's keys wait for the articles. */
    private Runnable whenIdle;

    RssPane(Host host, RssService service, Settings settings) {
        this.host = host;
        this.service = service;
        this.settings = settings;
        getStyleClass().add("rss-pane");
        table = buildTable();

        Button add = new Button("Add feed…");
        add.setOnAction(e -> addFeed(""));
        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> refreshSelected());
        filter.setPromptText("Filter articles — Esc returns to the transfers");
        filter.textProperty().addListener(o -> refilter());
        filter.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DOWN) {
                focusArticles();
                e.consume();
            }
        });
        HBox.setHgrow(filter, Priority.ALWAYS);
        unreadOnly.getStyleClass().add("search-chip");
        unreadOnly.selectedProperty().addListener(o -> refilter());
        HBox controls = new HBox(8, add, refresh, filter, unreadOnly);
        controls.setAlignment(Pos.CENTER_LEFT);
        status.getStyleClass().add("search-status");
        status.setWrapText(true);
        VBox head = new VBox(8, controls, status);
        head.setPadding(new Insets(10, 12, 8, 12));
        head.getStyleClass().add("search-head");

        buildFeedList();
        VBox detail = buildDetail();
        SplitPane right = new SplitPane(table, detail);
        right.setOrientation(Orientation.VERTICAL);
        // The table takes what the window gives; the article below keeps its height.
        SplitPane.setResizableWithParent(detail, false);
        SplitPane split = new SplitPane(feedList, right);
        SplitPane.setResizableWithParent(feedList, false);
        VBox.setVgrow(split, Priority.ALWAYS);
        getChildren().addAll(head, split);
        setVisible(false);
        setManaged(false);
        // The dividers are placed once the pane has a size: set while it is hidden and unmanaged
        // (zero by zero), they are clamped and lost.
        visibleProperty().addListener((o, was, now) -> {
            if (now && !placed) {
                placed = true;
                Platform.runLater(() -> {
                    split.setDividerPositions(0.24);
                    right.setDividerPositions(0.66);
                });
            }
        });

        addEventHandler(KeyEvent.KEY_PRESSED, this::onKey);
        service.addListener(this::onChanged);
        reload();
    }

    // ---- the lists ------------------------------------------------------------------------------

    private void buildFeedList() {
        feedList.getStyleClass().add("rss-feeds");
        feedList.setMinWidth(150);
        feedList.setPrefWidth(220);
        feedList.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        feedList.setCellFactory(v -> new FeedCell());
        feedList.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            if (!reloading) applyArticles();
        });
        feedList.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.RIGHT) {
                focusArticles();
                e.consume();
            }
        });
        feedList.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                FeedEntry entry = feedList.getSelectionModel().getSelectedItem();
                if (entry != null && !entry.all()) editFeed();
            }
        });
        feedList.setContextMenu(buildFeedMenu());
    }

    /** Title on the left, the count on the right in the muted ink, a glyph before a feed in trouble. */
    private static final class FeedCell extends ListCell<FeedEntry> {
        private final Label glyph = new Label();
        private final Label title = new Label();
        private final Label count = new Label();
        private final HBox box;

        FeedCell() {
            glyph.getStyleClass().add("rss-feed-glyph");
            title.getStyleClass().add("rss-feed-title");
            count.getStyleClass().add("rss-feed-count");
            // The title gives way (an ellipsis) so the count on the right always shows.
            title.setMaxWidth(Double.MAX_VALUE);
            title.setMinWidth(0);
            HBox.setHgrow(title, Priority.ALWAYS);
            box = new HBox(6, glyph, title, count);
            box.setAlignment(Pos.CENTER_LEFT);
            box.setMinWidth(0);
            // A cell sized to its graphic would widen the list to the longest name.
            setPrefWidth(0);
        }

        @Override
        protected void updateItem(FeedEntry e, boolean empty) {
            super.updateItem(e, empty);
            if (empty || e == null) {
                setGraphic(null);
                pseudoClassStateChanged(UNREAD, false);
                return;
            }
            glyph.setText(e.failed() ? "⚠" : e.refreshing() ? "↻" : "");
            glyph.setVisible(!glyph.getText().isEmpty());
            glyph.setManaged(glyph.isVisible());
            title.setText(e.name());
            count.setText(e.unread() == 0 ? "" : Integer.toString(e.unread()));
            pseudoClassStateChanged(UNREAD, e.unread() > 0);
            setGraphic(box);
        }
    }

    private TableView<ArticleRow> buildTable() {
        SortedList<ArticleRow> sorted = new SortedList<>(filtered);
        TableView<ArticleRow> t = new TableView<>(sorted);
        sorted.comparatorProperty().bind(t.comparatorProperty());
        t.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        t.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        t.setTableMenuButtonVisible(true);
        t.setPlaceholder(new Label("No articles"));
        t.getStyleClass().addAll("torrent-table", "rss-table");

        TableColumn<ArticleRow, String> title = column("title", "Title", 400, Article::displayTitle, s -> s);
        title.setComparator(String.CASE_INSENSITIVE_ORDER);
        // A glyph before the title of an article whose torrent has been added.
        title.setCellFactory(c -> new TableCell<>() {
            private final Label glyph = new Label("⤓");

            {
                glyph.getStyleClass().add("rss-downloaded-glyph");
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(item);
                ArticleRow row = getTableRow() == null ? null : getTableRow().getItem();
                setGraphic(row != null && row.article().downloaded() ? glyph : null);
            }
        });
        t.getColumns()
                .addAll(List.of(
                        title,
                        column(
                                "date",
                                "Date",
                                150,
                                a -> a.published() == null ? -1L : a.published().getEpochSecond(),
                                Format::dateTime),
                        column("feed", "Feed", 160, a -> names.getOrDefault(a.feedId(), ""), s -> s)));
        ColumnMemory.apply(t, ColumnLayout.parse(settings.get(Settings.RSS_COLUMNS, "")));
        ColumnMemory.watch(t, () -> {
            settings.set(Settings.RSS_COLUMNS, ColumnLayout.format(ColumnMemory.capture(t)));
            settings.saveQuietly();
        });

        t.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            selectedKey = now == null ? null : now.key();
            showDetail(now == null ? null : now.article());
            if (now != null && !now.article().read()) {
                Article a = now.article();
                service.markRead(a.feedId(), a.identity(), true);
            }
        });
        t.setOnKeyPressed(e -> {
            ArticleRow selected = t.getSelectionModel().getSelectedItem();
            if (e.getCode() == KeyCode.ENTER) {
                if (selected != null) addArticle(selected.article());
                e.consume();
            } else if (e.getCode() == KeyCode.C && e.isShortcutDown()) {
                if (selected != null) host.copy(RssAdd.copyText(selected.article()));
                e.consume();
            } else if (e.getCode() == KeyCode.LEFT) {
                focusFeeds();
                e.consume();
            }
        });
        t.setRowFactory(v -> {
            UnreadRow row = new UnreadRow();
            row.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !row.isEmpty())
                    addArticle(row.getItem().article());
            });
            return row;
        });
        t.setContextMenu(buildArticleMenu(t));
        return t;
    }

    /** A row whose {@code :unread} pseudo-class follows its article, flipping in place when it is read. */
    private static final class UnreadRow extends TableRow<ArticleRow> {
        private final InvalidationListener relisten = o -> apply();
        private ArticleRow watched;

        UnreadRow() {
            itemProperty().addListener((o, was, now) -> {
                if (watched != null) watched.articleProperty().removeListener(relisten);
                watched = now;
                if (now != null) now.articleProperty().addListener(relisten);
                apply();
            });
        }

        private void apply() {
            pseudoClassStateChanged(
                    UNREAD, watched != null && !watched.article().read());
        }
    }

    private VBox buildDetail() {
        detailTitle.getStyleClass().add("rss-detail-title");
        detailTitle.setWrapText(true);
        detailMeta.getStyleClass().add("search-status");
        detailLink.getStyleClass().add("rss-detail-link");
        detailLink.setOnAction(e -> {
            if (!detailLink.getText().isEmpty()) host.openLink(detailLink.getText());
        });
        detailLink.managedProperty().bind(detailLink.visibleProperty());
        detailText.setWrapText(true);
        detailText.getStyleClass().add("rss-detail-text");
        ScrollPane scroll = new ScrollPane(detailText);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("rss-detail-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        VBox box = new VBox(4, detailTitle, detailMeta, detailLink, scroll);
        box.setPadding(new Insets(8, 12, 8, 12));
        box.getStyleClass().add("rss-detail");
        box.setMinHeight(90);
        box.setPrefHeight(170);
        showDetail(null);
        return box;
    }

    private void showDetail(Article a) {
        if (a == null) {
            detailTitle.setText("");
            detailMeta.setText("Choose an article to read it here");
            detailLink.setText("");
            detailLink.setVisible(false);
            detailText.setText("");
            return;
        }
        detailTitle.setText(a.displayTitle());
        String date = a.published() == null ? "" : Format.dateTime(a.published().getEpochSecond());
        detailMeta.setText(RssText.meta(names.getOrDefault(a.feedId(), ""), date, a));
        detailLink.setText(a.link());
        detailLink.setVisible(!a.link().isEmpty());
        detailText.setText(a.description());
    }

    private ContextMenu buildArticleMenu(TableView<ArticleRow> t) {
        MenuItem add = new MenuItem("Add…");
        MenuItem download = new MenuItem("Download");
        MenuItem open = new MenuItem("Open Link");
        MenuItem copy = new MenuItem("Copy Link");
        MenuItem rule = new MenuItem("Create Rule from Title…");
        MenuItem toggle = new MenuItem("Mark Unread");
        MenuItem feedRead = new MenuItem("Mark Feed Read");
        add.setOnAction(e -> selected(t).ifPresent(this::addArticle));
        download.setOnAction(e -> selected(t).ifPresent(this::downloadArticle));
        open.setOnAction(e -> selected(t).ifPresent(a -> host.openLink(a.link())));
        copy.setOnAction(e -> selected(t).ifPresent(a -> host.copy(RssAdd.copyText(a))));
        rule.setOnAction(e -> selected(t).ifPresent(host::createRule));
        toggle.setOnAction(e -> toggleRead());
        feedRead.setOnAction(e -> markFeedRead());
        ContextMenu menu = new ContextMenu(
                add, download, open, copy, new SeparatorMenuItem(), rule, new SeparatorMenuItem(), toggle, feedRead);
        menu.setOnShowing(e -> {
            Optional<Article> a = selected(t);
            add.setDisable(a.map(x -> !x.hasTorrent()).orElse(true));
            download.setDisable(a.map(x -> !x.hasTorrent()).orElse(true));
            download.setText(
                    a.map(x -> x.downloaded() ? "Download Again" : "Download").orElse("Download"));
            rule.setDisable(a.isEmpty());
            open.setDisable(a.map(x -> x.link().isEmpty()).orElse(true));
            copy.setDisable(a.map(x -> RssAdd.copyText(x).isEmpty()).orElse(true));
            copy.setText(a.map(RssAdd::copyLabel).orElse("Copy Link"));
            toggle.setDisable(a.isEmpty());
            toggle.setText(a.map(x -> x.read() ? "Mark Unread" : "Mark Read").orElse("Mark Read"));
        });
        return menu;
    }

    private ContextMenu buildFeedMenu() {
        MenuItem refresh = new MenuItem("Refresh");
        MenuItem read = new MenuItem("Mark Read");
        MenuItem edit = new MenuItem("Edit…");
        MenuItem remove = new MenuItem("Remove…");
        MenuItem add = new MenuItem("Add Feed…");
        refresh.setOnAction(e -> refreshSelected());
        read.setOnAction(e -> markFeedRead());
        edit.setOnAction(e -> editFeed());
        remove.setOnAction(e -> removeFeed());
        add.setOnAction(e -> addFeed(""));
        ContextMenu menu =
                new ContextMenu(refresh, read, new SeparatorMenuItem(), edit, remove, new SeparatorMenuItem(), add);
        menu.setOnShowing(e -> {
            FeedEntry entry = feedList.getSelectionModel().getSelectedItem();
            boolean one = entry != null && !entry.all();
            refresh.setText(one ? "Refresh" : "Refresh All");
            read.setText(one ? "Mark Feed Read" : "Mark All Read");
            edit.setDisable(!one);
            remove.setDisable(!one);
        });
        return menu;
    }

    private static Optional<Article> selected(TableView<ArticleRow> t) {
        ArticleRow row = t.getSelectionModel().getSelectedItem();
        return row == null ? Optional.empty() : Optional.of(row.article());
    }

    /** A typed, comparable column over the row's article property, rendered through {@code text}. */
    private static <T extends Comparable<? super T>> TableColumn<ArticleRow, T> column(
            String id, String title, double width, Function<Article, T> value, Function<T, String> text) {
        TableColumn<ArticleRow, T> column = new TableColumn<>(title);
        column.setId(id);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> cell.getValue().articleProperty().map(value));
        column.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : text.apply(item));
            }
        });
        return column;
    }

    /** The article columns' ids, in their default order; the layout test holds them. */
    static List<String> columnIds() {
        return List.of("title", "date", "feed");
    }

    // ---- what the service says ------------------------------------------------------------------

    /** FX thread: the service changed something; the lists follow. */
    private void onChanged(String feedId) {
        reload();
        if (whenIdle != null && idle()) {
            Runnable then = whenIdle;
            whenIdle = null;
            then.run();
        }
    }

    private boolean idle() {
        for (Feed f : service.feeds()) if (service.status(f.id()).refreshing()) return false;
        return true;
    }

    /** Everything from the service: the feed entries (selection kept by id), the articles, the status. */
    void reload() {
        reloading = true;
        try {
            String keep = selectedFeedId();
            List<Feed> feeds = service.feeds();
            Map<String, String> newNames = service.namesById();
            boolean renamed = !newNames.equals(names);
            names = newNames;
            List<FeedEntry> entries = new ArrayList<>();
            int unreadTotal = 0;
            int refreshing = 0;
            List<String> failed = new ArrayList<>();
            for (Feed f : feeds) {
                RssService.Status s = service.status(f.id());
                entries.add(new FeedEntry(f.id(), f.displayName(), s.unread(), s.failed(), s.refreshing()));
                unreadTotal += s.unread();
                if (s.refreshing()) refreshing++;
                if (s.failed()) failed.add(f.displayName() + ": " + s.error());
            }
            entries.add(0, new FeedEntry("", "All feeds", unreadTotal, false, false));
            feedList.getItems().setAll(entries);
            int at = 0;
            for (int i = 0; i < entries.size(); i++) if (entries.get(i).id().equals(keep)) at = i;
            feedList.getSelectionModel().select(at);
            status.setText(RssText.statusLine(feeds.size(), unreadTotal, refreshing, failed));
            if (renamed) table.refresh();
        } finally {
            reloading = false;
        }
        applyArticles();
    }

    private String selectedFeedId() {
        FeedEntry entry = feedList.getSelectionModel().getSelectedItem();
        return entry == null ? "" : entry.id();
    }

    private void applyArticles() {
        String id = selectedFeedId();
        rows.apply(id.isEmpty() ? service.allArticles() : service.articles(id));
        refilter();
        ArticleRow selected = table.getSelectionModel().getSelectedItem();
        showDetail(selected == null ? null : selected.article());
    }

    private void refilter() {
        String q = filter.getText();
        boolean unread = unreadOnly.isSelected();
        String keep = selectedKey;
        filtered.setPredicate(
                row -> RssText.matches(row.article(), q, unread) || row.key().equals(keep));
    }

    // ---- actions --------------------------------------------------------------------------------

    private void onKey(KeyEvent e) {
        if (e.isConsumed()) return;
        boolean typing = e.getTarget() instanceof TextInputControl;
        switch (e.getCode()) {
            case ESCAPE -> {
                if (typing && !filter.getText().isEmpty()) filter.clear();
                else host.leave();
                e.consume();
            }
            case F5 -> {
                if (e.isShiftDown()) service.refreshAll();
                else refreshSelected();
                e.consume();
            }
            case N -> {
                if (e.isShortcutDown() || !typing) {
                    addFeed("");
                    e.consume();
                }
            }
            case M -> {
                if (!typing && !e.isShortcutDown()) {
                    if (e.isShiftDown()) markFeedRead();
                    else toggleRead();
                    e.consume();
                }
            }
            case DELETE, BACK_SPACE -> {
                if (!typing && feedList.isFocused()) {
                    removeFeed();
                    e.consume();
                }
            }
            default -> {}
        }
    }

    private void addArticle(Article a) {
        if (a.hasTorrent()) host.add(a);
        else host.say("No torrent in this article" + (a.link().isEmpty() ? "" : "; its link is in the pane below"));
    }

    private void downloadArticle(Article a) {
        if (a.hasTorrent()) host.download(a);
        else host.say("No torrent in this article" + (a.link().isEmpty() ? "" : "; its link is in the pane below"));
    }

    private void toggleRead() {
        ArticleRow row = table.getSelectionModel().getSelectedItem();
        if (row == null) return;
        Article a = row.article();
        service.markRead(a.feedId(), a.identity(), !a.read());
    }

    private void markFeedRead() {
        String id = selectedFeedId();
        if (id.isEmpty()) service.markAllRead();
        else service.markFeedRead(id);
    }

    private void refreshSelected() {
        String id = selectedFeedId();
        if (id.isEmpty()) service.refreshAll();
        else service.refresh(id);
    }

    /** The Add Feed dialog, with {@code prefill} typed into its URL (a capture); the feed is selected once added. */
    void addFeed(String prefill) {
        if (dialog != null && dialog.isShowing()) return;
        dialog = new FeedDialog(getScene().getWindow(), null, service.source(), settings.rssRefreshMinutes());
        if (!prefill.isEmpty()) dialog.prefillUrl(prefill);
        Optional<Feed> made = dialog.showAndWait();
        dialog = null;
        if (made.isEmpty()) {
            focusArticles();
            return;
        }
        try {
            service.addFeed(made.get());
        } catch (IOException e) {
            host.say("Could not save the feed: " + e.getMessage());
            return;
        }
        selectFeed(made.get().id());
        focusArticles();
    }

    private void editFeed() {
        String id = selectedFeedId();
        Feed existing = id.isEmpty() ? null : service.feed(id);
        if (existing == null || (dialog != null && dialog.isShowing())) return;
        dialog = new FeedDialog(getScene().getWindow(), existing, service.source(), settings.rssRefreshMinutes());
        Optional<Feed> edited = dialog.showAndWait();
        dialog = null;
        if (edited.isPresent()) {
            try {
                service.updateFeed(edited.get());
            } catch (IOException e) {
                host.say("Could not save the feed: " + e.getMessage());
            }
        }
        focusFeeds();
    }

    private void removeFeed() {
        String id = selectedFeedId();
        Feed feed = id.isEmpty() ? null : service.feed(id);
        if (feed == null) return;
        Alert confirm = new Alert(
                Alert.AlertType.CONFIRMATION,
                "Remove the feed “" + feed.displayName() + "” and its articles? The torrents already added stay.");
        confirm.setHeaderText("Remove feed");
        confirm.initOwner(getScene().getWindow());
        Fonts.installUiFont(confirm.getDialogPane());
        ButtonType yes = new ButtonType("Remove", ButtonType.OK.getButtonData());
        confirm.getButtonTypes().setAll(yes, ButtonType.CANCEL);
        Optional<ButtonType> answer = confirm.showAndWait();
        if (answer.isPresent() && answer.get() == yes) {
            try {
                service.removeFeed(id);
            } catch (IOException e) {
                host.say("Could not remove the feed: " + e.getMessage());
            }
        }
        focusFeeds();
    }

    private void selectFeed(String id) {
        for (int i = 0; i < feedList.getItems().size(); i++) {
            if (feedList.getItems().get(i).id().equals(id)) {
                feedList.getSelectionModel().select(i);
                return;
            }
        }
    }

    // ---- focus ----------------------------------------------------------------------------------

    /**
     * The table takes the focus with no row chosen: choosing one marks it read, which is for a
     * keystroke (Down selects the first row) or a click to do, not for opening the tab.
     */
    void focusArticles() {
        table.requestFocus();
        // With no focused row, Down lands on the first one rather than the second.
        if (table.getSelectionModel().isEmpty()) table.getFocusModel().focus(-1);
    }

    void focusFeeds() {
        feedList.requestFocus();
        if (feedList.getSelectionModel().isEmpty()) feedList.getSelectionModel().select(0);
    }

    /** What the tab focuses when it opens: the articles, else the feed list when there are none. */
    void focusDefault() {
        if (table.getItems().isEmpty()) focusFeeds();
        else focusArticles();
    }

    // ---- for the capture ------------------------------------------------------------------------

    String statusText() {
        return status.getText();
    }

    /** The feed list, top to bottom: "All feeds (3)", "!Tracker (2)", the chosen one starred. */
    List<String> feedsForCapture() {
        List<String> out = new ArrayList<>();
        FeedEntry chosen = feedList.getSelectionModel().getSelectedItem();
        for (FeedEntry e : feedList.getItems()) {
            out.add(RssText.feedLine(e.name(), e.unread(), e.failed(), e.refreshing()) + (e == chosen ? "*" : ""));
        }
        return out;
    }

    /** The article rows as shown: "Title | date | feed | unread | magnet". */
    List<String> rowsForCapture() {
        List<String> out = new ArrayList<>();
        ArticleRow chosen = table.getSelectionModel().getSelectedItem();
        for (ArticleRow row : table.getItems()) {
            Article a = row.article();
            String date =
                    a.published() == null ? "—" : Format.dateTime(a.published().getEpochSecond());
            out.add(a.displayTitle() + " | " + date + " | " + names.getOrDefault(a.feedId(), "") + " | "
                    + (a.read() ? "read" : "unread") + (a.downloaded() ? " downloaded" : "") + " | "
                    + (!a.hasTorrent() ? "-" : a.isMagnet() ? "magnet" : "torrent") + (row == chosen ? " *" : ""));
        }
        return out;
    }

    /** The detail pane's title and second line. */
    String detailForCapture() {
        return detailTitle.getText() + " | " + detailMeta.getText();
    }

    void selectFeedForCapture(String name) {
        for (int i = 0; i < feedList.getItems().size(); i++) {
            if (feedList.getItems().get(i).name().equalsIgnoreCase(name))
                feedList.getSelectionModel().select(i);
        }
    }

    /**
     * Keys pressed where the focus is, once no feed is refreshing, so the rows are there to walk.
     */
    void pressForCapture(List<KeyCode> codes) {
        Runnable press = () -> {
            for (KeyCode code : codes) {
                javafx.scene.Node owner = getScene() == null ? null : getScene().getFocusOwner();
                javafx.scene.Node target = owner;
                while (target != null && target != this) target = target.getParent();
                if (target == null) {
                    focusDefault();
                    owner = getScene().getFocusOwner();
                }
                owner.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
            }
        };
        if (idle()) press.run();
        else whenIdle = press;
    }

    /**
     * The context menu's Download or Create rule from title on the chosen article (the first row
     * when none is chosen), once no feed is refreshing; for a capture.
     */
    void actOnArticleForCapture(boolean download) {
        Runnable act = () -> {
            if (table.getSelectionModel().isEmpty() && !table.getItems().isEmpty())
                table.getSelectionModel().select(0);
            selected(table).ifPresent(a -> {
                if (download) downloadArticle(a);
                else host.createRule(a);
            });
        };
        if (idle()) act.run();
        else whenIdle = act;
    }

    /** Opens the Add Feed dialog on the next pulse, with the URL typed in; for a capture. */
    void openDialogForCapture(String url) {
        Platform.runLater(() -> addFeed(url));
    }

    javafx.scene.Scene dialogScene() {
        return dialog != null && dialog.isShowing() ? dialog.scene() : null;
    }

    String dialogTitle() {
        return dialog != null && dialog.isShowing() ? dialog.title() : "";
    }

    String dialogStatus() {
        return dialog != null && dialog.isShowing() ? dialog.statusText() : "";
    }

    void closeDialog() {
        if (dialog != null && dialog.isShowing()) dialog.cancel();
    }
}
