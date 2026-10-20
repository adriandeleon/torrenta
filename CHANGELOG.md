# Changelog

Notable changes, newest first. Versions follow [semantic versioning](https://semver.org); until
1.0.0 the minor number moves for anything user-visible.

## Unreleased

### Added

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
