# Changelog

Notable changes, newest first. Versions follow [semantic versioning](https://semver.org); until
1.0.0 the minor number moves for anything user-visible.

## Unreleased

### Added

- **Categories and tags.** A torrent is filed under one category or none and carries any number
  of tags. Torrent → Set Category… (`Ctrl+Shift+G`; a submenu of the categories in the Torrent and
  context menus, None and New category…) and Edit Tags… (`Ctrl+T`: a checkbox per tag, a field for
  new ones) act on the selection; the Add dialog offers the category and takes tags, and a category
  with a save path fills the dialog's folder when chosen, so new torrents in it download there.
  Settings → Categories and tags (also Torrent → Manage Categories and Tags…) adds, repaths and
  deletes them; deleting unfiles the torrents. Category and Tags columns, hidden by default.
  Categories are `categories.json`, tags `tags.json`, a torrent's own filing is
  `torrents/<info-hash>.labels` beside its resume data, written as it changes, restored at start
  and removed with the torrent (libtorrent's resume data has no field for them).
- **A sidebar** on the left of the list: All, then Status, Categories (each with its count, plus
  Uncategorised), Tags (plus Untagged) and Trackers (one entry per tracker host, plus Trackerless
  and, while some torrent's tracker answers with an error, Tracker error). Each section keeps a
  choice of its own, and the list shows what passes all of them together with the name filter;
  every count is live, from the same poll as the table. The Status entries are qBittorrent's:
  Downloading, Seeding, Completed, Paused, Active (moving now), Inactive, Stalled (wanting to move
  and not), Checking, Errored; the palette has a "Show: …" command for each. The choices
  (`ui.sidebar.status`, `.category`, `.tag`, `.tracker`), the folded sections
  (`ui.sidebar.collapsed`) and whether the sidebar shows (View → Sidebar, `Ctrl+Shift+B`,
  `ui.sidebar`) survive a restart; the choices and folds are never synced, they name this
  machine's torrents. `Ctrl+B` focuses it; arrows walk every section, `Space` chooses, `Enter`
  chooses and returns to the list, `Esc` returns. A torrent's tracker hosts come from its add
  parameters and from every tracker answer, so the section costs the poll nothing; a tracker
  error (and the State column's "Error") comes from the alerts too.
- **Queue and share limits** (Settings → Queue and share limits): how many torrents download, seed
  and run at once (0 for no limit), whether a slow torrent holds a slot, and a global share limit:
  a ratio and a seeding time, either of which, once reached by a finished torrent, pauses it or
  removes it. libtorrent ranks its seeds by these limits but pauses nothing by them, so the window
  applies the rule from its poll, once a second, and says so in the log. `queue.*` and `share.*`
  in the settings file.
- **Torrent → Limits…** (`Ctrl+L`, also in the context menu and the palette), for the selection:
  download and upload caps, connections, upload slots, "download the first and last pieces first"
  (the edge pieces of every wanted file go to top priority, so a video plays and seeks early), and
  a share limit of the torrent's own in place of the global one, or none. The caps and counts are
  libtorrent's and survive in the resume data; the share override is `torrent.<hash>.share` in the
  settings file, never synced, forgotten when the torrent is removed.
- **Settings sync** through a Git repository of yours (Settings → Sync), as Editora's: every
  computer that connects to the same repository keeps the same settings, merged one key at a time,
  this machine winning a same-key conflict. Automatic after startup, half a minute after a change
  and on a timer while the window is in front; "Sync now" too. "Create on GitHub" makes a private
  repository through the GitHub CLI; with two gh accounts, the one that can read the repository is
  remembered and used. This machine's folders, interface, clocks and proxy password never travel.
- **The command palette looks and feels as Editora's**: one card with no internal rules, the input
  as its first row led by the chord that opened it, rows that embolden the characters the query
  matched in the accent, the chord in the keyboard's own hue, a tinted selection with an accent bar,
  and a legend at the foot. The matched characters are the ones the ranking reasoned about.
- **An icon toolbar** under the menu bar, with Editora's glyphs: add a torrent file, add a magnet
  link, pause/resume, force resume, recheck, remove, filter, event log, settings, and the command
  palette at the right. Each button is one of the existing commands, so it greys out with them.
  View → Toolbar hides it (`ui.toolbar`). The add actions, the event log and the toolbar toggle
  are commands now, so the palette reaches them too.
- **Help → About**, the panel Editora has: icon, name, version and tagline, the copyright, links to
  the licence, the homepage, the releases and the settings file (opens its folder), the available
  update when one is known, and a monospace block with the build, the engine, Java, JavaFX, the OS
  and the config folder, with "Copy details". Also in the palette.

### Fixed

- **A JVM crash in the engine** (`SIGSEGV` in `libtorrent4j.so` while reading a torrent's detail,
  seen once on CI): libtorrent4j's vector proxies hand out pointers that its finalizer frees. The
  engine now keeps only handles, trackers and peers it owns.

### Changed

- **JavaFX 27**, as Editora; was 26.0.2.

### Added

- **Themes**, in Settings → General → Appearance, applied at once: Editora Light and Dark (Caret &
  Ink, as before), Nord Light and Dark, Cupertino Light and Dark. The last four are AtlantaFX's own,
  now a dependency, as in Editora. Kept as `ui.theme` in the settings file.

- **Torrents survive a restart.** Resume data is kept per torrent under `~/.torrenta/torrents/`,
  written when a torrent is added, when its metadata arrives, when it finishes, periodically and on
  quit, and loaded at start, so a complete torrent comes back seeding without a recheck and a magnet
  comes back even before its metadata ever arrived.
- **A settings file**, `~/.torrenta/settings.properties`, with `download.dir`.
- **`--config-dir DIR`** and `TORRENTA_CONFIG_DIR` move the whole folder.
- `Engine.remove(id, deleteFiles)`, for the window to use next.
- **A stable transfer list.** Rows are updated in place each second, so the selection, the scroll
  position and the sort survive. Fifteen columns (name, size, done, progress, state, seeds, peers,
  down, up, ETA, ratio, added, completed, tracker, save path), typed so sorting compares numbers;
  order, widths and visibility remembered. A name filter on `Ctrl+F`.
- **Torrent actions**, on the selection: pause/resume (`Space`), force resume, force recheck, force
  reannounce, sequential download, copy name/info-hash/magnet link, open the destination folder,
  remove and remove with data (confirmed). In the Torrent menu, the right-click menu, and a
  **command palette** on `Ctrl+Shift+P`.
- **The Add dialog.** `.torrent` files (`Ctrl+O`, a drop, the command line), magnet links and
  links to `.torrent` files (`Ctrl+M`, `Ctrl+V` from the clipboard, a drop, the command line) open
  a dialog with the name and size, the save folder with its free space, start paused, sequential
  download, skip hash check, a stop condition (after the metadata arrives, after the files are
  checked), and for torrents with metadata a file tree with checkboxes and per-file priorities.
  The status bar notices a magnet or torrent link on the clipboard when the window comes back.
- **A detail pane** (`Ctrl+I`) under the list for the selected torrent: General (hashes, save
  path, pieces, availability, counters, times, tracker), Files (a tree with progress and an
  editable priority per file; open a file), Peers (address, client, flags, progress, rates,
  totals), Trackers (status and message; add, remove, reannounce), and a Pieces map. The pane's
  visibility, tab and height are remembered.
- **A native app image and installers** (`-Pdist`): a self-contained Torrenta with its own
  runtime for macOS (`.app`, `.dmg`), Windows (`.msi`) and Linux (`.deb`, `.rpm`), with the
  engine's native library beside the image. CI builds and starts the image on all three platforms.
- **A release pipeline**: a `v*` tag builds and smoke-tests the installers on all three
  platforms and publishes a GitHub release with install notes, a changelog and checksums; a
  Homebrew cask and a winget manifest follow when the token is present.
- **A Settings window** (`Ctrl+,`): a sidebar of pages, a search box that filters every row, and
  controls that apply the moment they change — the download folder, the update check, and the Add
  dialog's starting positions for Start paused and Sequential.
- **Connection settings**: listening port or a random one, UPnP / NAT-PMP, IPv6, binding to an
  address or interface, connection limits (global, per torrent, upload slots), and a SOCKS5 or
  HTTP proxy with optional login, for trackers and optionally peers. Applied at start and live.
- **Protocol settings**: DHT, peer exchange, local peer discovery, uTP, the encryption policy
  (require, prefer, off), anonymous mode, and default trackers added to every torrent, new and
  existing. Applied at start and live.
- **Speed limits**: global download and upload caps, an alternative pair switched by hand from
  the status bar or by a weekly schedule (overnight windows belong to the day they start on), and
  counting protocol overhead in the limit.
- **Inter as the interface font**, bundled, the same face Editora and Termina use.
- **An IP filter**: a PeerGuardian or eMule blocklist by URL (plain or gzipped), downloaded on a
  schedule and kept beside the settings, a local list file, and manual bans — including Ban Peer
  from the Peers tab. The settings page shows how many ranges are loaded and how many peers were
  refused this session.
- **An update check** against GitHub releases, after startup and at most once a day, with a
  manual Check for Updates, a Download item and a Skip Version item in the Help menu.
- **An event log** (`Ctrl+Shift+L`): added, metadata received, checked, finished, removed, torrent
  and disk errors, tracker errors and warnings, listen ports, port mapping, engine start and stop,
  plus the app's own warnings. Filter by level, copy, clear. The status bar shows the latest event
  and an error count that opens the log.

Project scaffold: a themed window with a menu bar, a torrent list and a status bar; the
`--help`/`--version` command line, which also takes `.torrent` paths and magnet links; the Caret &
Ink themes carried over from Editora and Termina; the development screen capture; CI on all three
platforms. The engine is libtorrent through libtorrent4j 2.1.0-39, behind a small `Engine`
interface; torrents from the command line are added and listed with size, progress, rates, peers
and state. No dialogs, resume data, limits or settings yet.
