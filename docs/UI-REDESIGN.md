# Torrenta UI redesign

A proposal, 2026-10-20. Mockups: the "Torrenta UI Redesign" canvas
(https://claude.ai/artifact/4Kgt66s58FyJH5fiq21nfM, private to adl): main window dark and light,
omnibox, Add dialog, first run, state language. Nothing here is built yet; each section below is
meant to become one PR, in the order at the end.

## What the others do (October 2026)

| Client | Shape | Strength | Weakness |
|---|---|---|---|
| qBittorrent 5.3 | µTorrent's: toolbar, sidebar (status, categories, tags, trackers), table, tabs below, status bar | Complete; 5.2–5.3 added free space in the status bar, a tracker-status filter, state colours with an off switch | Dense, utilitarian; keyboard is Ctrl+O/Del/Ctrl+A; a magnet's file choice waits on metadata |
| Transmission 4.1 | Two-line rows ending in a status sentence; details in a separate Inspector window | Calm, set-and-forget; 4.1 colours bars by state and dims inactive rows; the web client got single-key verbs and Enter-submits | Thin on power; three clients that look different |
| Deluge 2.2 | qBittorrent's shape, GTK3, daemon + thin client | Plugins | Dated; the connection manager confuses |
| BiglyBT 4.1, Tixati | Every view: swarm, piece maps, per-peer logs, protocol vs payload graphs, rule-driven tags | Diagnostics | Overwhelming |
| Fragments 3, Motrix/Rayburst | One list grouped by status, cards, clipboard magnets noticed | Pretty, friendly | Too thin past a dozen torrents |
| VueTorrent, Flood, qui (web) | Sidebar with speed graph, cards or table, density setting | Layered Esc, chosen card fields, smooth at 10k rows | Browser-bound |

Table stakes Torrenta already has or plans: column table, sidebar filters, detail tabs with pieces
map, Add dialog with free space, drag and drop, status bar, themes.

**What nobody does:** a command palette in a desktop client; saying *why* a torrent is stalled;
an Add flow that does not block on a magnet's metadata; tracker trouble shown as a sentence on the
row rather than a filter to go looking with. Those four are the redesign's spine.

## The design

### 1. State language (board 6)

Every row carries one glyph, one colour and one sentence. Ten states: Downloading ↓ (teal),
Fetching metadata ◌, Checking ⟳, Queued …, Paused ‖ (all neutral), Stalled ! (amber), Seeding ↑
(periwinkle, the icon's tray colour), Done ✓ (green), Error × (red), Moving →. Colour is never the
only signal; a setting turns state colours off (qBittorrent 5.2 found people want that).

- `StatusLine.of(Torrent)` → lead + rest, pure and tested: "62% · from 38 of 112 peers",
  "Seeding to 2 peers · ratio 1.84 of 2.00, about 40 min", "Paused by you", "Queued #1 · starts
  when a download slot frees".
- Colours are looked-up CSS colours (`-torrenta-state-down` …) defined in every theme sheet,
  Editora, Cupertino and Nord alike, so a theme switch carries them.

### 2. Rows: comfortable and compact (boards 1, 2)

- **Comfortable** (default): two lines. Name; below it a thin state-coloured bar and the sentence.
  Columns right of it: size, down, up, ETA, ratio. Zero rates are a muted dash.
- **Compact**: one 28 px line with progress and status as their own columns, which is today's
  table plus the glyph. View → Density, remembered.
- **Group by status** (View → Group, off by default): section headers *Downloading · Needs
  attention · Seeding · Paused* with counts and rates. A TableView has no groups, so the list
  becomes a sealed `ListRow` (Header | TorrentRow) with a comparator that sorts within groups;
  the grouping rule is pure.

### 3. Sidebar (board 1; roadmap 0.5 item 2)

Status views with counts **and live rates** (Downloading 2 · ↓ 7.3 MB/s), categories, saved
views, trackers with a failing count. Alt+1…7 jumps to a status view. Ctrl+B collapses it to a
rail of dots with counts (board 2).

### 4. Inspector and diagnosis (board 1)

The detail pane gains an **Overview** tab first: action buttons with their keys, the diagnosis
card when there is one, the pieces map, a 30-minute sparkline, then the facts. Ctrl+J hides the pane.
The tabs need keys of their own, to be decided: Ctrl+1, 2 and 3 went to the window's views
(Transfers, Search, RSS; adl, 2026-10-21), so Ctrl+1…5 no longer picks a tab.

**Where it docks** (decided, adl, 2026-10-20): to the right on a wide window, below on a narrow
one, configurable. The setting is `detail.position` = `auto` (default) | `right` | `bottom`. It is
offered in View → Detail Position and in Settings → General → Appearance, and it syncs.
- `auto` docks right when the window can hold the sidebar, the list at its minimum width and the
  inspector side by side (about 1260 px with the sidebar, 1040 px with it collapsed). Otherwise it
  docks below.
- `auto` re-decides live on resize, with a hysteresis band of about 80 px so dragging the window
  edge across the line does not flip it back and forth.
- The decision is a pure `DetailDock.of(windowWidth, sidebarShown, setting, current)`, tested at
  the edges of the band.
- Each position remembers its own divider, as a fraction of the window, so switching between them
  never inherits a width that only made sense for the other.

`Diagnosis.of(TorrentDetail, SessionState)` is the new pure class, first matching rule wins:
storage error → "Cannot write · path is read-only"; all trackers failing and no peers →
"No tracker answers and DHT found no one"; availability < 1 → "N pieces are on no peer", and
when they fall in one file, offer **Skip that file and finish the rest**; peers all choking →
"23 peers, none sending to you yet" (and say there is nothing to do); port unreachable with few
peers → "Incoming connections are blocked"; queued with slots full → "Waiting for a download
slot". It feeds the Stalled sentence and the amber card; at most two remedies, each a command.

### 5. Omnibox (board 3)

The filter box and the palette become one field, Ctrl+K (Ctrl+F and Ctrl+Shift+P open it in
filter and command mode). Plain words filter by name, as today. `key:value` terms become chips:
`state: cat: tag: tracker: ratio< size> added< is:private hash:`, `-` negates, terms AND. `›`
restricts to commands. A pasted magnet, URL or path opens Add. Below the matches: commands that
apply to all of them ("Pause", "Set ratio goal…", "Save as view…").

- `FilterQuery.parse(String)` → predicate plus chips, pure, tested like `Actions`.
- A saved view is a name and a query string in settings (`views.N.name`, `views.N.query`), so
  settings sync carries it.
- **Layered Esc** (VueTorrent): dialog → omnibox → detail pane → selection.

### 6. Add dialog (board 4)

Two columns: the file tree on the left (Space toggles, +/− priority, ←/→ fold); save path with a
disk bar showing what the torrent will use, category, stop condition, tags, options on the right.
Enter adds, Shift+Enter adds paused, Esc cancels. "Next time, add with these settings without
asking" (qBittorrent's skip-dialog, opt-in).

For a magnet without metadata the dialog stays usable: everything but the files can be set, the
tree fills in when metadata arrives, and **Add now, pick files when ready** adds it with the
existing stop condition "metadata received". Its row then reads *Choose files*, and Enter reopens
the dialog.

### 7. Chrome

- **Header**: the menu bar, then one row that is the icon toolbar (#19), **on by default** (adl,
  2026-10-20): add file, add magnet | pause, resume, force resume, recheck, remove | event log,
  settings, then the omnibox centred in the remaining width and the palette icon at the end. The
  toolbar's filter icon goes, since the omnibox sits on the same row. Merging the toolbar and the
  filter box saves a row over today's window. Actions that do not apply are disabled, not hidden
  (qBittorrent 5.3's lesson), so the icons never move. View → Toolbar hides the icons and leaves
  the omnibox.
- **Status bar**: port state as a dot with words (open, blocked, checking), DHT nodes, down/up
  with 60 s sparklines, the alt-speed toggle, free space, and an error count that opens the log.
- **Selection bar** (board 2): with two or more rows selected, a floating bar with count, size and
  the bulk verbs plus their keys. "Esc clears."
- **First run** (board 5): a drop zone with the four keys that matter, and the clipboard magnet
  offered when there is one. The empty window still gives the filter no focus.

## Order of work

1. State language: glyph column, `StatusLine`, state colour tokens in every theme, the off switch.
2. Comfortable rows and the density switch.
3. Sidebar with counts, rates, collapse (roadmap 0.5.2, pulled forward).
4. `Diagnosis` and the Overview tab; detail position auto/right/bottom.
5. Omnibox and `FilterQuery`; layered Esc; saved views.
6. Add dialog relayout; non-blocking magnet add.
7. Status bar additions, selection bar, first-run screen.
8. Group by status.

Each lands with capture-hook checks (`-Dtorrenta.captureAction=`) for the new view, as the
existing ones do.
