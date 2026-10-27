# Torrenta roadmap

A keyboard-first, cross-platform BitTorrent client on JavaFX, as complete as Transmission and
qBittorrent, with built-in search. This is the plan from the 0.1 scaffold to a 1.0 that can replace
either of them for daily use, and after it four milestones (1.1 to 1.4, planned 2026-10-24) of
things neither of them does well. One PR per feature, each line below is one PR unless it says
otherwise, and each milestone is a tagged release so that macOS and Windows get exercised early.

## Principles

- **Keyboard first.** Every action has a chord and a command-palette entry; the mouse is optional.
  Chords are `Shortcut+key`, declared once on the menu item.
- **One window.** Transfer list, sidebar filters, detail pane, status bar. Dialogs for add, settings
  and torrent creation; nothing else pops up.
- **The engine is libtorrent, behind `Engine`.** qBittorrent is also libtorrent, so parity with it is
  a matter of exposing what the engine already does, not of writing protocol code. Nothing above
  `com.torrenta.engine` may import a libtorrent type, so a second engine (Transmission over RPC, for
  a seedbox) stays possible.
- **Nothing on the FX thread talks to the engine.** Polling and actions run on workers; the FX thread
  receives immutable snapshots.
- **Settings apply live** and are clamped on read as well as write, as in Termina.
- **Extract the decision, then test it.** Rules (which torrents a filter shows, what a share-limit
  action does, how a search result is ranked) are pure classes with tests; UI classes stay thin.
- **Verify by driving it.** The capture hook renders the real window; the CI smoke test asserts the
  engine started inside it.

## Where it stands: 0.1

Window, theme, menu bar, torrent table polled off the FX thread, `--help`/`--version`, inputs from
the command line, libtorrent4j 2.1.0-39 with native unpacking, CI on three platforms with a
rendered smoke test. No persistence, dialogs, settings, limits, detail or packaging.

## Parity matrix

Transmission 4.1 (T) and qBittorrent 5 (Q), feature by feature, with the Torrenta milestone that
delivers it. ✓ has it, ◐ partial, ✗ does not. Rows marked **later** are deliberately after 1.0.

### Adding torrents

| Feature | T | Q | Torrenta |
|---|---|---|---|
| Add `.torrent` file, magnet, HTTP(S) URL to a `.torrent` | ✓ | ✓ | 0.3 |
| Add dialog: save path, file selection and priorities, start paused, sequential, skip hash check (category/labels in 0.5) | ◐ | ✓ | 0.3 |
| Stop condition on add (after metadata, after files checked) | ✗ | ✓ | 0.3 |
| Content layout on add (original, subfolder, flat) and rename on add | ✗ | ✓ | 0.4 |
| Drag and drop files, magnets and URLs onto the window | ✓ | ✓ | 0.3 |
| Clipboard watch: offer a magnet or URL found on the clipboard | ✗ | ✗ | 0.3 |
| Watch folders, one save path per folder | ✓ | ✓ | 0.5 |
| Register as the OS handler for `.torrent` and `magnet:` (single instance, hand off to the running app) | ✓ | ✓ | 0.8 |
| Default trackers added to every torrent | ✓ | ✓ | 0.4 |
| Web seeds (HTTP sources) shown and editable | ✓ | ✓ | 0.4 |
| Content check before adding: executables in a media torrent, double extensions, shortcut files, bidi names; encrypted archives once their first piece is in | ✗ | ✗ | 1.1 |
| Swarm preview before adding: seeds, peers, availability of the ticked files, a verdict | ✗ | ✗ | 1.1 |
| Import torrents from qBittorrent, Deluge and Transmission, without a recheck where the resume data allows | ✗ | ✗ | 1.3 |
| Reuse a file already on disk in another torrent (same v2 file root): link or copy instead of downloading | ✗ | ✗ | 1.3 |

### Transfer list

| Feature | T | Q | Torrenta |
|---|---|---|---|
| Columns: name, size, done, progress, status, seeds, peers, down, up, ETA, ratio, added, completed, tracker, category, tags, save path; choose and reorder, remembered | ◐ | ✓ | 0.2 |
| Stable rows: update in place, selection and scroll survive a tick | ✓ | ✓ | 0.2 |
| Sort by any column; filter box on name | ✓ | ✓ | 0.2 |
| Sidebar filters: status (all, downloading, seeding, completed, paused, active, inactive, stalled, checking, errored), categories, tags, trackers | ◐ | ✓ | 0.5 |
| Multi-select with bulk actions | ✓ | ✓ | 0.2 |
| Context menu and chords for every per-torrent action | ✓ | ✓ | 0.2 |
| Command palette (Ctrl+Shift+P) over every command | ✗ | ✗ | 0.2 |
| Status bar: session rates, DHT nodes, free space on the download disk, alt-speed toggle, port state | ✓ | ✓ | 0.4 |
| Saved filters as sidebar views, with live counts | ✗ | ✗ | 1.1 |
| Commands with arguments from the filter box, over every match | ✗ | ✗ | 1.1 |
| Undo: remove, Set Location, category and tags, rename, priorities, queue moves | ✗ | ✗ | 1.1 |

### Per-torrent actions

| Feature | T | Q | Torrenta |
|---|---|---|---|
| Pause, resume, force resume (ignore queue) | ✓ | ✓ | 0.2 |
| Remove, remove with data, with confirmation | ✓ | ✓ | 0.2 |
| Force recheck, force reannounce | ✓ | ✓ | 0.2 |
| Set location (move storage), rename torrent, rename files and folders | ✓ | ✓ | 0.4 |
| Queue: move to top, up, down, bottom | ✓ | ✓ | 0.4 |
| Per-torrent download and upload limits | ✓ | ✓ | 0.4 |
| Per-torrent share limits: ratio, seeding time, inactive time; action on reaching them | ✓ | ✓ | 0.4 |
| Sequential download | ✓ | ✓ | 0.2 |
| First and last piece first | ✓ | ✓ | 0.4 |
| Super seeding | ✗ | ✓ | 0.4 |
| Copy name, info-hash (v1 and v2), magnet link | ◐ | ✓ | 0.2 |
| Open destination folder, open file | ◐ | ✓ | 0.2 |
| Export `.torrent` | ✗ | ✓ | 0.4 |
| Stream a file to a player while it downloads (local HTTP, piece deadlines near the playhead) | ✗ | ✗ | 1.4 |
| Updatable torrents (BEP 46): follow one, publish one | ✗ | ✗ | 1.4 |

### Detail pane

| Feature | T | Q | Torrenta |
|---|---|---|---|
| General: hash, save path, pieces, availability, times, counters (limits in 0.4; comment/creator are in the Add dialog, not readable back from a handle) | ✓ | ✓ | 0.3 |
| Files: tree with progress, priority per file, open, rename (0.4, with the content layout) | ✓ | ✓ | 0.3 |
| Peers: IP, client, flags, progress, rates (country via offline GeoIP: 0.8) | ✓ | ✓ | 0.3 |
| Trackers: status, message; add, remove, reannounce (scrape counts not exposed by the binding) | ✓ | ✓ | 0.3 |
| Pieces map: downloaded and in-progress pieces | ✓ | ✓ | 0.3 |
| Speed graph per torrent and session | ✗ | ✓ | 0.7 |

### Network

| Feature | T | Q | Torrenta |
|---|---|---|---|
| Listen port, random port | ✓ | ✓ | 0.4 |
| Port test | ✓ | ✓ | later |
| UPnP and NAT-PMP forwarding | ✓ | ✓ | 0.4 |
| DHT, PEX, LSD, uTP toggles | ✓ | ✓ | 0.4 |
| Encryption mode: require, prefer, allow | ✓ | ✓ | 0.4 |
| IPv6 | ✓ | ✓ | 0.4 |
| Proxy: SOCKS5/HTTP, with auth, for peers and trackers | ✗ | ✓ | 0.4 |
| IP filter: blocklist URL, eMule `.dat` and PeerGuardian `.p2p`, manual bans | ✓ | ✓ | 0.4 |
| Bind to a network interface or address (the peers it dials too since 0.3.0, #92) | ✓ | ✓ | 0.4 |
| Leak check: the bound interface's state, the address the swarm sees, where traffic exits | ✗ | ✗ | 1.1 |
| Anonymous mode (no client identification) | ✗ | ✓ | 0.4 |
| Connection limits: global, per torrent, upload slots | ✓ | ✓ | 0.4 |

### Bandwidth, queue, seeding

| Feature | T | Q | Torrenta |
|---|---|---|---|
| Global download and upload limits | ✓ | ✓ | 0.4 |
| Alternative limits, toggle, weekly schedule | ✓ | ✓ | 0.4 |
| Alternative limits on battery or on a metered network | ✗ | ✗ | 1.4 |
| Queue: max active downloads, uploads, total; slow-torrent threshold does not count | ✓ | ✓ | 0.4 |
| Global share limits with action: pause, remove, remove with data, super seed | ✓ | ✓ | 0.4 |
| Rate limit counts protocol overhead (the uTP-only option no longer exists in libtorrent 2) | ✓ | ✓ | 0.4 |

### Organisation

| Feature | T | Q | Torrenta |
|---|---|---|---|
| Labels / tags | ✓ | ✓ | 0.5 |
| Categories with save path; automatic torrent management (move when category changes) | ✗ | ✓ | 0.5 |
| Incomplete-files folder, `.!tt` suffix while downloading | ✓ | ✓ | 0.5 |
| Pre-allocate disk space | ✓ | ✓ | 0.5 |
| Resume data across restarts; fast resume, no re-check | ✓ | ✓ | 0.2 |
| Session statistics: all-time up/down, ratio, time active, per-session | ✓ | ✓ | 0.7 |

### Automation and tools

| Feature | T | Q | Torrenta |
|---|---|---|---|
| Run a program on added, on finished, on seeding done; with placeholders | ✓ | ✓ | 0.5 |
| Desktop notifications on finished and on error | ✓ | ✓ | 0.5 |
| Email on finished | ✗ | ✓ | later |
| Torrent creation: pieces size, private, trackers, web seeds, v1/v2/hybrid, start seeding | ✓ | ✓ | 0.7 |
| RSS: feeds, refresh, articles; auto-download rules with regex, category, save path, episode filter | ✗ | ✓ | 0.7 |
| Event log with level filters, copy, clear; error count in the status bar | ◐ | ✓ | 0.2 |
| Built-in tracker | ✗ | ✓ | no |

### Search

| Feature | T | Q | Torrenta |
|---|---|---|---|
| Search tab across providers, merged results, sort by seeds/size/date, category filter, add from result | ✗ | ✓ | 0.6 |
| Provider plugins | ✗ | ✓ (Python) | ✓ 0.6, as definition files (see below) |
| Torznab indexers (Jackett, Prowlarr) | ✗ | ✗ | ✓ 0.6 |

### Remote and app

| Feature | T | Q | Torrenta |
|---|---|---|---|
| Native packages: macOS `.dmg`/`.app`, Windows `.msi`, Linux `.deb`/`.rpm`/tarball; Homebrew and winget | ✓ | ✓ | 0.3 |
| Update check against GitHub releases | ✓ | ✓ | 0.3 |
| Settings window, live-applying, searchable | ✓ | ✓ | 0.4 |
| Light and dark themes, UI font | ✓ | ✓ | 0.8 |
| Interface languages | ✓ | ✓ | 0.8 |
| Control a remote daemon (Transmission RPC as a second `Engine`) | ✓ (remote GUI) | ✗ | ✓ 0.9 |
| An RPC of its own: qBittorrent's WebUI API (`/api/v2`), so Sonarr, Radarr, Prowlarr and remote apps connect | ✓ (its own) | ✓ | 1.2 |
| A web UI: an alternative qBittorrent WebUI (VueTorrent) served from a folder, not one of Torrenta's own | ✓ | ✓ | 1.2 |
| Headless daemon mode | ✓ | ✓ (`-nox`) | 1.2 |

## Milestones

Each is a release. Lines are PRs, in order within the milestone; a PR lands green on all three
platforms before the next starts.

### 0.2 — Usable
The point at which Torrenta can be left running.

1. **Persistence** — done. Per-torrent resume data (libtorrent `save_resume_data` with the info
   dict, written on add, metadata, finish, periodically and on exit, loaded at start); a settings
   file; `TorrentStore` as the one owner of what is on disk; `--config-dir`.
2. **Stable table** — done. Rows keyed by info-hash and updated in place; selection, sort and
   scroll survive a tick. Column chooser, column order and widths remembered. Filter box.
3. **Actions and chords** — done. First/last piece first has no libtorrent flag; it landed with
   0.4's per-torrent limits dialog (#24), raising each file's edge pieces as qBittorrent does. Pause, resume, force resume, remove, remove with data, recheck,
   reannounce, sequential, copy name/hash/magnet, open folder. Context menu, menu bar, command
   palette. Multi-select.
4. **Engine alerts** — done. libtorrent alerts become engine-neutral `EngineEvent`s: added,
   metadata received, checked, finished, removed, torrent/file errors, tracker errors and
   warnings, listen succeeded/failed, port mapping. They drive the status bar, the Event Log window
   and later the notifications; the app's own warnings land in the same log.

**0.2 is feature-complete** as planned; tagging it waits for 0.3's app image, since a release
with nothing to download is not one.

### 0.3 — Add, inspect, install
What a first public release needs.

1. **Add dialog** — done; the content layout came in 0.4. Files, magnets and URLs; save path with
   free-space readout; start paused, sequential, skip check, stop condition; file tree with
   checkboxes and priorities; drag and drop; clipboard detection with `Ctrl+V`; the command line
   goes through it. **Content layout (no root folder) moved to 0.4**: libtorrent4j 2.1.0-39 has no
   `torrent_info::rename_file`, and renaming through the handle after adding is confirmed by
   libtorrent's alerts yet a recheck afterwards never found the files under their new names; it
   needs either a binding change or a different sequence, and was not worth holding the dialog.
   (It was the different sequence: `renamed_files` in the add parameters, see 0.4's item 10.)
2. **Detail pane** — done: General, Files, Peers, Trackers, Pieces; collapsible, remembered. Not
   in it: file rename (same binding limitation as the content layout: names read back through
   `torrentFile()` do not reflect renames in 2.1.0-39; in since 0.4, from the engine's own record),
   peers' country (needs an offline GeoIP
   database, 0.8 with OS integration), per-torrent limits in General (0.4), and comment/creator in
   General (libtorrent 2.1 keeps them outside `torrent_info`; the Add dialog shows them).
3. **Native app image and installers** — done: jlink plus jpackage, the native unpacked and
   placed beside the image as app content with `libtorrent4j.jni.path` set by the launcher;
   macOS `.app`/`.dmg`, Windows `.msi`, Linux `.deb`/`.rpm`; CI builds, starts and checks the
   image on all three platforms. Release workflow, JReleaser, Homebrew cask and winget — done, from
   Termina's; untested until the first dry run and tag. No AOT cache training yet (Termina has it;
   a measured follow-up).
4. **Update check** — done, from Termina's `update` package: daily after startup, manual from
   Help, Download and Skip Version items, no draft or pre-release offered, snapshots count as
   their release.

**0.3 is feature-complete.** The first release is 0.1.0, cut with everything through 0.9 in it:
the numbers here name milestones, not tags. See docs/release.md.

### 0.4 — Network, bandwidth, queue
Everything in Settings that the engine already knows how to do.

1. **Settings window** — done as the shell plus the General and Adding pages (download folder,
   update check, Add dialog defaults): sidebar, search across rows with dimmed pages, live apply,
   Reset. The Connection, Speed, BitTorrent and Advanced pages arrive with their settings in the
   PRs below, each mapping onto libtorrent's `settings_pack`.
2. **Connection** — done, except the port test (libtorrent has none; it needs an outside service
   and goes with 0.8's OS integration or stays out): port, random port, UPnP/NAT-PMP, interface
   binding, IPv6, connection limits, proxy. `EngineSettings` maps onto `settings_pack`, applied at
   start and live; the per-torrent limit is set on each add. The binding covered only the
   listening sockets until 0.3.0 (#92): `outgoing_interfaces` binds the peers the session dials.
3. **Protocol** — done: DHT, PEX (a per-torrent flag, set on add and carried to existing
   torrents), LSD, uTP, encryption mode, anonymous mode, default trackers (appended on add and to
   existing torrents).
4. **IP filter** — done: blocklist URL with scheduled refresh (gzip by magic number, cached as
   `blocklist.txt` beside the settings), `.dat`/`.p2p`/CIDR/plain parsing, a local list file,
   manual bans with Ban Peer in the Peers tab; the count of ranges and of refused peers is on the
   settings page and in the status bar on each apply.
5. **Speed** — done: global limits, alternative limits, a weekly schedule checked every minute,
   the overhead option, and the status-bar toggle. The uTP-only rate option is gone from
   libtorrent 2.x and is not offered.
6. **Queue and share limits** — done: active downloads, seeds and total (0 for none; libtorrent's
   -1), the slow-torrent rule, and a global ratio and seeding-time limit with its action (pause or
   remove). libtorrent reads `share_ratio_limit`/`seed_time_limit` only to rank seeds for the queue,
   so the window applies the rule from its poll (`ShareLimit.decide`, pure). Queue positions are in
   (Torrent → Queue, the Queue column). The action also takes remove with data and super seed
   (once, then left alone; libtorrent's only).
7. **Per-torrent limits dialog** — done (Torrent → Limits…, `Ctrl+L`): rate caps, connections,
   upload slots, first and last pieces first (edge-piece priorities, in the resume data), and a
   share limit of the torrent's own kept as `torrent.<hash>.share`. Set location is in (Torrent →
   Set Location…).
8. ✓ **Super seeding**: Torrent → Super Seeding on the complete torrents of the selection, kept in
   the resume data. Transmission's RPC has none (`Capability.SUPER_SEEDING`).
9. ✓ **Export `.torrent`** (Shortcut+Shift+E): the info dictionary as loaded (same hash, renames
   never reach it), the trackers in their tiers, the web seeds; one file or a folder of them.
   Transmission hands out the file's path on its machine, never the bytes (`EXPORT_TORRENT`).
10. ✓ **Rename**: a torrent's name (F2; the list, the log and run-on-event, kept in `.labels`,
   qBittorrent's way: the files keep theirs), files and folders from the Files tab, and the content
   layout on add (as the torrent has it, in a folder of its own, without its root folder; default
   in Settings → Adding). libtorrent 2.1 reads renamed files back from the resume data relative
   to the wrong folder and never shows them through `torrent_file()`, so the engine records every
   name in the `.storage` sidecar and puts them back itself; the layout is `renamed_files` in the
   add parameters, which the add's own check already honours. Transmission renames files and
   folders (`torrent-rename-path`); a name of its own and the layout are libtorrent's
   (`RENAME_TORRENT`, `CONTENT_LAYOUT`).

11. ✓ **Web seeds** in the Trackers tab, under the trackers: listed for both engines, added and
   removed on libtorrent's (`Capability.WEB_SEEDS`; Transmission's `torrent-set` cannot change
   them). The change is in the resume data, so a seed removed from a `.torrent`'s `url-list`
   stays removed, and Export writes the list as it is now.

**0.4 is feature-complete.**

### 0.5 — Organisation and automation
**0.5 is feature-complete.**

1. ✓ **Categories and tags** with sidebar filters and automatic torrent management.
2. ✓ **Sidebar** status and tracker filters, counts, remembered selection.
3. ✓ **Incomplete folder, suffix, pre-allocation.** The suffix goes on after the check and off
   per file, through `rename_file`; libtorrent 2.1 writes a renamed file into the resume data
   relative to the torrent's folder but reads it back relative to the save path, so the engine
   puts the full names back from its `.storage` sidecar before re-adding. Allocation is on each
   file's first write in libtorrent 2.x, not at add.
4. ✓ **Watch folders.** A `WatchService` thread plus a poll every minute as the safety net; a
   file is read once its size has held for two looks; the add is the engine's own path, with no
   dialog. The list is `watch-folders.json`, this machine's, never synced.
5. ✓ **Run on event and desktop notifications** (Settings → Automation). One command line per
   event (added, finished, seeding done) with qBittorrent's placeholders, split like a shell
   would and substituted per argument, run with no shell, output and exit code in the Event Log,
   killed after ten minutes; the lines are this machine's and never sync. Notifications per event
   (finished on by default, plus errors) through `notify-send`, `osascript` or a PowerShell toast
   of ours, each a short program with the text as arguments; said once in the log when the
   machine has none. The seeding-done event is the share-limit action, raised by the window.

### 0.6 — Search
**0.6 is feature-complete.** See "Search design" below.

1. ✓ **Provider API and Torznab client**, with a provider settings page: add an indexer by URL and API
   key, test it. (`search.SearchProvider`, `TorznabProvider`, `SearchService`, Settings → Search.)
2. ✓ **Search tab**: query, category, provider selection; results merged and deduplicated by
   info-hash; sort; add to the Add dialog. (`ui.SearchPane`, `Ctrl+Shift+F`; `SearchService.Progress`
   streams each provider's answer in.)
3. ✓ **Declarative providers**: a definition file format for sites without an API, loaded from the
   config dir; a handful of definitions for public, non-infringing indexes shipped as examples.
   (`search.Definition` + `DeclarativeProvider`, `HtmlParser`/`Selector`/`JsonExtractor`,
   `store.DefinitionStore`, Settings → Search → Definition files; `docs/search-providers.md`.)

### 0.7 — RSS, creation, statistics
**0.7 is feature-complete.**

1. ✓ **RSS reader**: feeds, refresh interval, article list with read state. (`com.torrenta.rss`,
   `ui.RssPane`, `Ctrl+R`; Settings → RSS.)
2. ✓ **RSS auto-download rules** with regex, episode filter, category and save path. (`rss.Rule`,
   `EpisodeFilter`, `RuleMatcher`, `RuleEngine`, `RssService.runRulesNow`; `ui.RuleDialog`,
   Settings → RSS → Rules; `rss/rules.json`.)
3. ✓ **Torrent creator**: v1/v2/hybrid, piece size, private, trackers, web seeds, seed after creating.
   (`Engine.create`, `CreateRequest`/`CreatedTorrent`, `PieceSize`; `ui.CreateDialog`, `Ctrl+N`.)
4. ✓ **Statistics** window and per-torrent and session speed graphs. (`Engine.stats` →
   `SessionStats` from libtorrent's `session_stats` counters, `store.StatsStore` for the all-time
   totals; `ui.SpeedHistory`, `SpeedPlot`, `SpeedGraph`, `StatisticsWindow`, `Ctrl+Shift+T`;
   the detail pane's Speed tab.)

### 0.8 — App polish
**0.8 is feature-complete.**

Asked for by adl on 2026-10-20, each its own PR, taken from Editora so the family looks the same:

1. ✓ **Themes, from Editora** (#18): Editora Light and Dark, Cupertino Light and Dark, Nord Light and
   Dark, switched live from Settings → General → Appearance. AtlantaFX is a dependency (adl's call);
   Nord and Cupertino are its own classes, the Editora pair stays authored in-house.
2. ✓ **Icon toolbar with Editora's icons** (#19): add file, add magnet, pause/resume, force resume,
   recheck, remove, filter, event log, settings, palette; the same glyphs Editora draws; View → Toolbar.
3. ✓ **About box, from Editora** (#20): name, version, tagline, copyright, licence, homepage, releases,
   settings file, the update line, the environment block with Copy details.
4. ✓ **Settings sync through a private GitHub repository** (#22), as Editora's: the settings file
   key by key (bans and default trackers are keys in it), a Git repo as the store, optional, automatic
   and manual triggers, this machine wins on a same-key conflict, setup by pasted URL plus "Create on
   GitHub"; the clone pins the `gh` account that can read the repo.
5. ✓ **OS integration**: `.torrent` and `magnet:` handler (the desktop entry's `MimeType`, the
   bundle's document and URL types, the installer's association; `xdg-mime` or the registry from
   Settings → General → Operating system), single instance with hand-off over a loopback socket
   (`os.SingleInstance`), the tray icon with the rates (#42: Nux's and Modula's `tray` package, SNI
   over D-Bus on Linux, AWT elsewhere, close-to-tray once an icon has appeared), and a badge: the
   download rate on the macOS dock, the downloading count on a Linux launcher that reads Unity's
   signal; none on Windows, where AWT badges only its own windows.
6. ✓ **Interface languages**, from Termina's `i18n` package, with the same six to start: English,
   Deutsch, Español, Français, Italiano, Português (`com.torrenta.i18n.Messages`, the catalogues
   under `resources/com/torrenta/i18n/`); Settings → General → Language, System by default, applied
   on restart; `ui.language` syncs. Every text of the window, the dialogs, the Settings pages, the
   tabs, the palette, the status bar and the tray goes through `tr()`; a sweep test keeps it so.

The interface font (Inter, as Editora's, #14) and the command palette's look (Editora's, #23) are in.

### 0.9 — Remote
**0.9 is feature-complete.**

1. ✓ **Transmission RPC `Engine`**: the same window driving a transmission-daemon on a seedbox or NAS;
   session profiles in Settings; capabilities the daemon lacks are hidden, not faked.
   (`engine.transmission.TransmissionEngine` + `RpcClient` + `Bencode` for `inspect`;
   `Engine.Capabilities` read by the window through `ui.CapabilityRules`; `EngineProfile`,
   `store.ProfileStore` (`profiles.json`, owner-only, never synced), `engine.profile`; Settings →
   Engine with Test connection, Use at next start and Switch now; File → Switch engine profile…;
   `TransmissionDaemonTest` against the real daemon where it is installed. Not mapped, since the
   RPC has no word for them: torrent creation, the metadata fetch before adding, an IP filter from
   ranges, first-and-last pieces, seed mode and stop conditions on add, upload slots, the proxy,
   interface binding, anonymous mode, overhead counting, per-torrent storage choices, the total
   active limit; the daemon's own session settings are shown as this machine's file and only a
   changed key is sent.)

### 1.0 — Criteria

Every ✓ row for 0.2 through 0.9 shipped on all three platforms; a week of daily use on each
without data loss; resume data proven across upgrades; installers signed where the platform
requires it; the README's "Not done" list empty.

### After 1.0

Asked for by adl on 2026-10-24 and planned the same day; the open questions were decided then
and are recorded where they apply. The plan's first item, binding the peers the session dials to
the chosen interface, was a bug and shipped at once (#92, in 0.3.0). Every API below was checked
against libtorrent4j 2.1.0-39: the v2 file roots (`file_storage.root`), mutable DHT items
(`dhtGetItem`/`dhtPutItem`, `Ed25519`), piece deadlines, `ExternalIpAlert` and `ScrapeReplyAlert`
are all there, and `jdk.httpserver` is in the JDK, so nothing here adds a native dependency.

### 1.1 — Trust and keyboard
Small and local, no new attack surface. Undo comes before bulk commands on purpose: a command over
every match is where a mistake is largest.

1. **Content check before adding** (2 PRs). `engine.ContentCheck.of(preview, wanted)`, pure, over
   the file list the Add dialog already has (a magnet's too, once fetched): executables (high when
   most of the bytes are media by `FileKind`, info in a software torrent), a media extension before
   an executable one (`x.mkv.exe`, space-padded too), bidi controls in a name, shortcut files
   (`.lnk .url .desktop .webloc .scf`), archives in a media torrent, bait names from a word list in
   the catalogues. A card above the file tree with "Skip these files"; adds without a dialog
   (watch folders, RSS rules, Add without asking) follow `add.unsafeContent` = `add | skip |
   refuse`, `skip` by default, and say what they did in the log. An archive's password is in its
   data, not in the file list, so the second PR's `engine.ArchiveProbe` (ZIP's bit 0, RAR5's
   encryption header, RAR4's flags, 7z's AES coder `06F10701`) reads a wanted archive's first
   piece, fetched first with a deadline, and raises a `Diagnosis` card. The static check is on
   both engines; the probe needs `LOCAL_FILES`.
2. **Filter keys**: `seeding>`, `active<`, `seeds<`, `eta>`, `uploaded>` in `FilterQuery`, which
   the views below want.
3. **Saved views**: `ui.SavedView(name, query)`, the query kept as the omnibox's text and parsed
   each time; one key per view (`ui.view.<slug>.name`/`.query`, `ui.views.order`) so sync merges
   view by view. A Views section above Categories with live counts from the same pass as the
   others; `SidebarFilter.View`, AND with the omnibox; a query that no longer parses is struck
   through with its error, never silently empty. `Ctrl+S` in the omnibox, `>save-view <name>`, the
   palette; `Ctrl+Alt+1…9` for the first nine.
4. **Undo, the engine's half**: `Engine.detach(id)` → `Detached` (fresh resume data, awaited as
   `stop()` awaits it, since the periodic blob can be two minutes stale; labels, `.storage`, queue
   position, the `torrent.<hash>.*` keys) and `Engine.reattach`, through the restore path so
   run-on-added does not fire. Transmission can only re-add by magnet and verify:
   `Capability.UNDO_REMOVE`.
5. **Undo, the window's half**: `ui.UndoStack`, the last 10 actions. The status bar offers
   "… · Undo" for 10 s; `Ctrl+Z` works for 10 minutes, or until the inverse is no longer possible
   (decided). A scene filter that stands aside in a text field, as Space and Delete do. Inverses for
   remove without data (held in memory, so a quit ends it rather than a crash bringing the torrent
   back), Set Location (a move back once `storage_moved_alert` came; a copy across volumes, and
   said so), category, tags, rename, file priorities, queue moves, a bulk pause. Remove with data
   is not undone, but its files go to the desktop's trash (`remove.toTrash`, on by default) and
   come back from the file manager: `os.Trash` without AWT, since `Desktop.moveToTrash` needs it
   headful — the freedesktop.org spec in Java on Linux (the home trash, a volume's `.Trash/$uid` or
   `.Trash-$uid`, never a copy), `~/.Trash` or `.Trashes/<uid>` on macOS, the Recycle Bin through
   a PowerShell script on Windows. `Engine.onDisk` says where the files are before the remove,
   `session.TrashPlan` makes the torrent's folder one item.
6. **Bulk commands from the omnibox** (1–2 PRs): `ui.OmniCommand.parse` gives a command and typed
   arguments: a category or tag (completed from `LabelStore`), a path, a rate (`500k`, as
   `FilterQuery` reads sizes), a ratio, `on|off`, a queue move. `Actions.targets`: typed while the
   omnibox holds chips, a command acts on every match, otherwise on the selection; the palette row
   names the target first ("Pause: 14 matching torrents"); destructive ones and anything over 20
   confirm through `RemoveConfirm`; `Actions.torrentsOnly` still applies and the skipped are
   counted; each is one undo entry. Up recalls the last ten (`ui.omnibox.history`, never synced).
   The first PR is in: the verbs, their arguments, the targets, the confirm and the undo. Still to
   come: completion of names and paths, a ratio verb, the count of what was skipped, and history.
7. **Swarm preview** (2 PRs): every add gets a hidden upload-mode probe (`MetadataFetch` becomes
   `SwarmProbe`; a `.torrent` gets one as a magnet does), off by default for a private torrent,
   where a probe's announce counts. `SwarmPreview` (the scrape counts, the highest per tracker
   since each sees part of the swarm; connected peers; availability whole and over the ticked
   files) and `ui.SwarmVerdict` (Healthy, Thin, Incomplete, Dead), in `FreeSpace.sentence`'s style
   beside the free-space line. **No time estimate** (decided): the probe downloads nothing, so
   there is no rate to measure, and a guess shown as a number reads as a measurement. Transmission
   has no hidden torrents: `Capability.SWARM_PREVIEW`.
   Done in two PRs: the fetch stays in the swarm past its metadata, `SwarmPreview`,
   `ui.SwarmVerdict` and the Add dialog's Swarm line; then `MetadataFetch` became `SwarmProbe`
   (`Engine.probe(source, privateToo)`), a `.torrent` got its own probe, `Capability.SWARM_PREVIEW`,
   and `add.probePrivate` (Settings → Adding torrents, off).
8. **Leak check** (2 PRs): `net.InterfaceWatch` reads the bound interface every 5 s on the poll
   thread (present, up, addresses; a change re-applies the settings), `Connectivity` keeps the
   swarm's view of this machine from `ExternalIpAlert`. Then an opt-in exit check, since it asks a
   third party (the URL is the user's, `https://api.ipify.org` offered): one request through
   `HttpClient.localAddress` set to the bound address, one without. `net.LeakCheck.verdict`
   (Protected, Exposed, Halted, Unbound, Unknown), pure, as a shield pill beside the port dot with
   a card of the three addresses; `connection.pauseWhenExposed`. Tracker names still resolve
   through the system's resolver unless a proxy is set; the card says so. Hidden for a remote
   engine, whose network is not this machine's.
   The first PR is in: `net.InterfaceWatch` in the poll (a binding down or missing is the port's
   red entry and a log line, the same binding changing calls `Engine.rebind()`), and
   `Connectivity.externalAddress` from `external_ip_alert`, in the port's tooltip and the log.
   The second PR finished it: the opt-in exit check (`net.ExitCheck`), `net.LeakCheck.verdict`,
   the shield pill and its card (`ui.LeakView`, `LeakCard`), and `connection.pauseWhenExposed`
   (`ui.ExposurePause`).

### 1.2 — Headless and the qBittorrent API
The largest milestone; see "qBittorrent API design" below. Most of what the API needs lives in
`MainWindow` today, which a server in the window's process could borrow and a headless one cannot,
so the order is extract, then serve.

1. **Session core** (2–3 PRs, nothing visible): `com.torrenta.session.Session` takes the engine's
   lifecycle, the poll and its snapshots, share limits, the speed refresh, watch folders, RSS
   rules, the IP filter, automation, statistics, the instance hand-off and the input queue out of
   `MainWindow`, which then listens and calls. A test fails on any JavaFX import under `session`,
   as `UiStringSweepTest` fails on a literal. Order: poll and share limits; watch folders, RSS and
   the IP filter; `switchProfile`. The first step is in: the poll, the share limits and the speed
   refresh, with `SessionTest`'s JavaFX guard. So is the second: the watch folders, the RSS service
   and its rules, the IP filter. And the third: the engine's start, switch and stop. And the
   fourth: the add pipeline (`Session.add`, the content check's pending magnets, `AddDefaults`),
   with the content rules in `com.torrenta.content`. And the fifth: automation, the speed
   histories and the input queue (`Session.input`, `resolve`, `addWithoutAsking`). Item 1 is done:
   headless has a session to run.
2. **Headless mode**: `torrenta --headless` runs `Session` without the toolkit; inputs are added
   without a dialog (`add.unsafeContent` applies), events go to stdout and the session log,
   SIGTERM goes through `stop()` and its resume-data wait. A `torrenta-nox` launcher in the same
   image (jpackage `--add-launcher`), a systemd user unit in docs. Done: `Headless`, `Startup`,
   `torrenta-nox` known by its path, `docs/headless.md`.
3. **HTTP plumbing**: `com.torrenta.http` on `jdk.httpserver` with virtual threads: routes, form
   and multipart bodies (the *arr apps post `.torrent` files multipart), cookies, `Range` (for
   1.4's streaming), HTTPS from a PKCS#12 file. Done, with body and part caps and filters for the
   security checks to come.
4. **API Tier 1, the *arr apps** (Sonarr, Radarr, Lidarr, Readarr, Prowlarr), in three PRs:
   the doors and `app/*`; the reading endpoints (`torrents/info`, `properties`, `files`,
   `categories`, `transfer/info`, `QbitState`, `QbitTorrent`); the actions (`torrents/add` with
   `savepath` inside the allowed roots, delete, pause/resume/stop/start, recheck, categories,
   tags, share limits, `topPrio`, `setForceStart`). The first is in: `com.torrenta.webapi`
   (`Passwords`, `LoginGuard`, `Sessions`, `Hosts`, `WebApi`, `WebApiService`), Settings → Remote
   access, headless's `--set-remote-password`; every security decision below but the allowed
   roots, which come with `torrents/add`. So is the second: `QbitState` (qBittorrent's rule,
   stalled as soon as nothing arrives, not the list's minute), `QbitTorrent` (`hash` is the v1
   hash, `content_path` the file or root folder), `TorrentQuery`, `Reads`, the facts read once
   through the detail and kept (`FactsCache`), `torrents/tags` beside the list's endpoints. And the
   third: `Writes` (`torrents/add`: `urls` magnets and http(s) only, uploads through
   `Bencode.check` and a 10 MiB cap, the screened `Session.add`; the actions), `SavePaths` (the
   allowed roots on real paths, links followed), `webapi.roots`. Item 4 is done: Sonarr and Radarr
   against a headless Torrenta is item 6's manual pass.
5. **API Tier 2, remote apps and alternative web UIs**, and the static root folder, in three PRs:
   what a web UI needs to run at all (`sync/maindata` with `MainData.diff`, `sync/torrentPeers`,
   `webapi.rootFolder` served as qBittorrent serves an alternative WebUI: `public/` to anyone,
   `private/` to a session); the torrent edits (`filePrio`, `setLocation`, `rename`,
   `renameFile`/`renameFolder`, the trackers, per-torrent limits, the other queue moves, category
   and tag editing); the session's (`transfer/speedLimitsMode`, the global limits,
   `app/setPreferences`, `log/main`). The first is in: `MainData` (the diff, pure; one snapshot
   per session, as qBittorrent keeps, not a history), `sync/maindata` with `server_state`,
   `sync/torrentPeers`, `StaticFiles` (`webapi.rootFolder`), unknown API paths 404 for every
   method. So is the second: `Edits` (the torrent, file, tracker, limit, queue, category and tag
   edits; `torrents/trackers`, `webseeds` and `pieceStates` beside them), `QueueOrder` moved to
   `engine` so the API orders a selection's moves as the window does. And the third: `SessionEdits`
   (the speed mode and global limits, `app/setPreferences` through `Preferences`, `log/main` from
   `session.SessionLog`), `Session.applySettings`. Item 5 is done.
6. **Compatibility** (done, `docs/webapi.md`): `QbittorrentContractTest` against a real qBittorrent
   5.1.0's answers recorded by `scripts/webapi-record.py` (no qBittorrent needed to run it; the
   recordings found differences, every one fixed, and `scripts/webapi-compare.py` then found none
   against a live headless Torrenta); Sonarr 4.0 and Radarr 6.4 through grab, import and removal
   after seeding. Planned as: contract tests from a real qBittorrent 5.0's recorded answers;
   `QbittorrentContractTest`, `@EnabledIf` `qbittorrent-nox` is on the PATH as
   `TransmissionDaemonTest` is, sends the same requests to both and compares the shapes; a manual
   pass with Sonarr and Radarr against a headless Torrenta (grab, import, remove after seeding),
   then one mobile app, written down in `docs/webapi.md`.

Tier 3 (qBittorrent's `search/*` and `rss/*` over Torrenta's own) comes after 1.2, once Tiers 1
and 2 are in use (decided): the *arr apps never call it, and search makes outbound requests on a
remote caller's behalf.

### 1.3 — Storage

1. **Import from qBittorrent**, with the dialog. `com.torrenta.migrate`: one reader per client
   producing `ImportCandidate` (name, hash, metainfo, resume, save path, category, tags, paused);
   `ImportPlan.of(candidates, existingIds, fileProbe)`, pure: skip what Torrenta has, flag missing
   data, decide trust or recheck. qBittorrent is libtorrent: `BT_backup/<hash>.fastresume` is
   libtorrent's resume format plus `qBt-*` keys (category, tags, name, ratio and seeding limits,
   stopped, content layout) with `<hash>.torrent` beside it, read as it is with the info dict put
   back; categories from `categories.json`; the paths per platform, Flatpak's included. Its
   optional SQLite storage (`torrents.db`) is not read (decided): the dialog says how to switch
   qBittorrent to fastresume files (Advanced → Resume data storage type) and look again, rather
   than ship a second native library for an opt-in mode. File → Import from another client…:
   the sources found on this machine or a chosen folder, a table with a verdict per torrent, a
   summary in the event log. In two PRs: the import (`migrate.QbittorrentReader`, `ImportPlan`,
   `Engine.adopt`, `Session.importTorrents`, proven on what a real qBittorrent 5.1.0 wrote), then
   the dialog. Both are in (item 1 is done): `ui.ImportDialog` and `ImportText`, the folders found
   or one chosen, a tick per torrent as the plan advises (what Torrenta has, or qBittorrent is
   still writing, cannot be ticked), the SQLite and running notes. Found on the way: libtorrent 2 keeps no file sizes in resume
   data and believes a truncated file whole, so the plan compares sizes for the files whose pieces
   are claimed and a torrent that does not match is adopted without its pieces, checked on the add.
2. **Import from Deluge**, also libtorrent (done: `migrate.DelugeReader`, fixtures from a real
   Deluge 2.2.0 in a container with no network; `torrents.state` is read after all, through
   `migrate.Pickle`, which builds nothing, since a magnet without metadata has no resume data and
   only its link is there, and the paused flag, the ratio and the name a person gave live there
   too; renamed files from `mapped_files` into the `.storage` sidecar, qBittorrent's as well,
   since libtorrent 2.1 showed them under the old name): `state/torrents.fastresume` is one
   bencoded dict of hash to resume bytes, `state/<hash>.torrent` beside it, labels from
   `label.conf` (Deluge's two concatenated JSON objects).
3. **Import from Transmission**, which is not (done before item 2, since a real Transmission was
   at hand to write the fixtures and Deluge was not: `migrate.TransmissionReader`, its resume data
   converted into libtorrent's and adopted like qBittorrent's, renames into the `.storage` sidecar,
   the dialog listing both clients' folders; found: Transmission 4.1 keeps the progress in
   `progress.blocks`, and its `pieces` there is the check state, `all` for a stopped half-downloaded
   torrent): `torrents/<hash>.torrent` (3.x:
   `<name>.<hash16>.torrent`) and `resume/*.resume`, bencoded (`destination`, `name`, `paused`,
   `labels`, dates, totals, per-file `priority`/`dnd`, renamed names, and `progress` as
   `have: all`, a 4.x `pieces` bitfield or 3.x 16 KiB `blocks`, a piece only when all its blocks
   are there), converted to add parameters; `have: all` becomes seed mode; labels come back through
   `TransmissionLabels`' `category:` convention.
   For all three: an engine test per client from fixtures real clients made, which must reach
   their progress without checking, and one with a truncated file, which must come up short.
   "Verify in the background afterwards" is an option, off, since `force_recheck` is where
   libtorrent's picker crash lives. An incomplete torrent is not imported while the other client
   runs (qBittorrent's `lockfile`, a daemon's RPC port): two writers on one file is corruption.
   libtorrent only; into a Transmission profile it would be a full verify, which is not the promise.
4. **Dedup, the index** (done: `TorrentPreview.FileEntry.root`, `Engine.contentIndex()`, kept from
   the alerts; found: libtorrent posts a file-completed alert for every whole file as it loads a
   torrent, so a start rebuilds the index without a pass of its own, and a recheck is the one
   thing that says a file is no longer whole; `DedupPlan` takes the skipped files and a `Disk`
   for sizes and volumes, never overwrites a target, and prefers a source it can link):
   `engine.ContentIndex`, v2 Merkle root → (torrent, path on disk, size) for
   complete files only, built at start from torrents with v2 metadata and kept on finish, rename,
   move and remove; never persisted, since the metadata is already there. `DedupPlan.of(preview,
   index, layout, savePath, renames)`, pure, maps each wanted file with a known root to (source,
   target, method), the target derived from `FileRenames` exactly where libtorrent will look at
   add: content layout, incomplete folder, `.part`.
5. **Dedup, the add** (done: `DedupFiles.place` before the add, off the FX thread and the engine's
   lock, through `Session.placeFound` from both the dialog's add and `Session.add`;
   `AddRequest.Storage.found` into the `.storage` sidecar's `found`, `TorrentDetail.FileStatus.found`
   for the Files tab; `add.dedup` is `DedupMode` OFF, LINK, ASK (the default) or COPY; a clone is
   `cp --reflink=always` on Linux and `cp -c` on macOS, else a full copy; a magnet added without a
   dialog has no files to look for at the add and downloads as before): "3 files (4.2 GB) are
   already on disk in 'Debian 13.1' · link them", ticked
   by default; a hardlink on the same file system, a reflink where there is one (`cp
   --reflink=auto`, `clonefile`), else a copy with its size shown first (`link | copy | ask`). The
   files are in place before `add_torrent`, whose own check verifies them: v2 pieces are per file,
   and a hybrid's padding keeps its v1 pieces file-aligned. Watch folders and rules dedup without
   asking; the Files tab marks a linked file. `Capability.DEDUP`. v1 files (done after: a
   `ContentKeys.v1` key from the metadata, SHA-256 over the piece length, the size and the file's
   piece SHA-1s, so no file is read to match; only for a file whose pieces are its own, a single
   file or one padding aligns, since a piece shared with a neighbour says nothing of this file
   alone and a re-download of it would write into a linked file; two torrents cut in different
   piece lengths do not match).

### 1.4 — Protocol extras

1. **Battery and metered networks** (done, in one PR: `os.Conditions` and `os.SystemConditions`,
   the decision moved into the session, which tells the engines the pair through
   `Engine.refreshLimits(alternative)`; the person's switch and the decision share `speed.altOn`,
   flipped by the decision only when it changes (`speed.autoWas`), which also catches up a change
   made while the app was closed; a condition's pause is `session.ConditionPause`, which pauses at
   the start only, so a torrent resumed by hand stays resumed; `speed.altOn` became this
   machine's): `os.PowerState` and `os.NetworkCost` behind a pure
   `Conditions(onBattery, metered)`: UPower's `OnBattery` and NetworkManager's `Metered` on the
   system bus through dbus-java, with their `PropertiesChanged`; `pmset -g batt` on macOS, which
   has no command for Low Data Mode, so that row is hidden there rather than faked;
   `GetSystemPowerStatus` through FFM and a PowerShell query of the connection cost on Windows.
   `Speed.alternativeActive(now)` becomes `Speed.mode(now, schedule, conditions, override)`:
   alternative when the schedule says so or a condition's rule is on, or paused for the stronger
   choice; a manual toggle wins until the next change, as qBittorrent's schedule override does.
   The pill says why ("Alternative speeds: on battery"). Hidden for a remote engine.
2. **Streaming, the server** (done: `StreamServer` owned by the session, `Session.streamUrl`;
   `Response.Ranged` is the plumbing's third body, written range by range; `Engine.stream` hands a
   `StreamFile` whose `await` registers a future per missing piece before its second look and sets
   the deadline, the `piece_finished_alert` completing it; found: a skipped file's pieces go into
   the part file, not the file, so streaming a skipped file makes it wanted, at low priority; and
   Windows' "measured": the file read zeros right after the piece's alert, the bytes still in
   libtorrent's write buffer, so every read is `read_piece`, the roadmap's fallback, on every
   platform): `com.torrenta.stream.StreamServer` on 1.2's plumbing, loopback only,
   a random port, `/s/<token>/<id>/<fileIndex>/<name>` with a token per file and launch, no
   listing, `206` and `Content-Range`. `PieceMath.span`, pure, gives the pieces a range needs; a
   piece the torrent has is read from the file under its recorded name (`onDisk`), a missing one
   gets a deadline and a wait on `PieceFinishedAlert` first. Reading beside libtorrent's writes is
   fine on POSIX; Windows' share mode is measured, with `readPiece` as the fallback. An engine test
   reads a range from a loopback torrent that has to wait for its piece. `Capability.STREAMING`.
3. **Streaming, the player** (done: `Readahead.plan(playhead, geometry, have, bitrate, starting)`,
   the download rate left out, since the deadlines already say what comes first; the playhead is
   the server's, each range's first byte and then each piece written, planned again once a second
   or at once on a seek, by a `Playback` per file that counts its connections; the bitrate is a
   megabyte a second until 30 s of serving measure it; `stream.Player`, `stream.player` this
   machine's, on the Adding page): `Readahead.plan(playhead, have, rate, bitrate)`, pure: deadlines
   tightening towards the playhead over about 30 s of playback, the file's first and last pieces at
   start (container indexes sit at the end), sequential over the file's range alone, all cleared
   when the last connection closes. Files tab → Stream in player runs `stream.player` (mpv or VLC
   found on the PATH, the app bundles, the registry) with the URL as an argument and no shell;
   Copy stream URL beside it; the Overview's strip marks the window.
4. **BEP 46, following** (done: `engine.MutableSource` is the address, read in `Session.resolve`
   before `Inputs.classify`, so the dialog, the command line, a second instance and headless all
   take it, and so do a watch folder's `.magnet` lines, RSS (rules, Download, Add) and search
   results, through `Session.resolve(AddRequest.Source)` before the add. `Engine.lookupMutable` is
   libtorrent's `dht_get_item`, the highest `seq` kept until the authoritative answer or 30 s;
   `Following` (address, `seq`, the id it `replaces`) rides in the `.labels` sidecar. The follower
   is the session's, not a class of its own: every ten minutes it looks up whatever has not been
   looked up for `follow.hours` (6; 0 never), and `FollowRules.step` decides; a new version's file
   list is fetched first so the add goes through the dedup. Every thirty seconds a complete new
   version lets go of the old one, removed (files kept) only with `follow.removeOld`. A hybrid
   named by its v1 hash is matched by either hash. The loopback DHT needed libtorrent's five
   address rules off (routing, search, node id, verified ids, dark internet) and a put only once
   both tables know a node: `MutableDhtTest`, about 45 s, in the normal run): `Inputs` reads `magnet:?xs=urn:btpk:<key>&s=<salt>` as a
   `MutableSource`, `dhtGetItem` resolves its `ih`, which is added as a magnet with the source in
   the `.labels` sidecar. A `MutableFollower` asks again every 6 h (a setting); a higher `seq` adds
   the new torrent into the same save path (1.3's dedup makes an update cost the changed files) and
   keeps or removes the old one when the new one is complete; `EngineEvent.Kind.UPDATED` for
   automation. The URI, the item and the follower's decision are pure and tested; the DHT round
   trip is two loopback sessions bootstrapped to each other, nightly if it proves flaky.
5. **BEP 46, publishing** (done: `store.KeyStore` keeps `keys/<name>.key`, owner-only, never
   synced, the warning in the file and in the dialog; the key pair is libtorrent's own Ed25519
   (`Engine.newMutableKey`), so the secret is in the form its signing takes. libtorrent4j's put
   signs one above the highest `seq` it finds and cannot be told a number, so `Publication.seq` is
   the record of the highest published, and `session.PublishRules` climbs back above it with
   repeated puts once the DHT has forgotten the item, at most 64 a round, a put no node takes
   ending the round; an item still found as published is not put again, which keeps the number
   from growing every hour and the climb short. Republished every hour, the first a minute after a
   start. The item names the v1 hash when there is one. New Version… and Copy Updatable Link in
   the Torrent menu, for the current version of a publication, `Capability.MUTABLE`): the Create
   dialog's "Publish as updatable": a key pair
   (`Ed25519.createKeypair`) in `keys/<name>.key`, owner-only, never synced, with the warning that
   losing it ends the updates; `dhtPutItem` with the next `seq`, republished hourly while the app
   runs since items expire from the DHT in about two hours; New version… on a published torrent.
   `Capability.MUTABLE`.

## qBittorrent API design

Sonarr, Radarr, Lidarr, Readarr and Prowlarr, and most remote apps for phones, already speak
qBittorrent's WebUI API. Speaking it at `/api/v2` plugs Torrenta into all of them at once, gives
phone control through apps that exist, and, over a Transmission profile, makes Torrenta a
qBittorrent front for a Transmission daemon. The handlers are thin over `Session`; the mapping is
pure and tested:

- `QbitState.of(Torrent, RowState)` → `downloading`, `stalledDL`, `metaDL`, `queuedDL`,
  `checkingDL`, `pausedDL`/`stoppedDL`, `forcedDL`, `uploading`, `stalledUP`, …, `error`,
  `missingFiles`, `moving`. The *arr apps decide imports by it, so every case has a test.
- `QbitTorrent.write(…)` fills `torrents/info`'s fields; `content_path`, which the *arr apps import
  from, is the file of a single-file torrent and the root folder otherwise.
- `MainData.diff(previous, current, rid)` → `sync/maindata`'s partial updates, the last few
  snapshots kept by `rid`, `full_update` for an unknown one.

**Tier 1** (the *arr apps): `auth/login`, `auth/logout`, `app/version`, `app/webapiVersion` (the
qBittorrent 5.0 level, 2.11), `app/preferences` (the keys they read: `save_path`,
`queueing_enabled`, `max_ratio_enabled`, `max_ratio`, `max_seeding_time_enabled`, `dht`),
`app/defaultSavePath`; `torrents/info` (`filter`, `category`, `tag`, `hashes`, `sort`, `limit`),
`properties`, `files`, `add` (`urls`, multipart `torrents`, `savepath`, `category`, `tags`,
`paused`/`stopped`, `skip_checking`, `contentLayout`, `sequentialDownload`, `firstLastPiecePrio`,
`rename`, limits), `delete`, `pause`/`resume` and 5.0's `stop`/`start`, `recheck`, `setCategory`,
`categories`, `createCategory`, `addTags`, `setShareLimits`, `topPrio`, `setForceStart`;
`transfer/info`.

**Tier 2** (remote apps, VueTorrent and the like): `sync/maindata`, `sync/torrentPeers`,
`torrents/filePrio`, `setLocation`, `rename`, `renameFile`/`renameFolder`,
`addTrackers`/`removeTrackers`, per-torrent limits, the other queue moves, category and tag
editing, `transfer/speedLimitsMode`/`toggleSpeedLimitsMode` and the global limits,
`app/setPreferences` for the keys Torrenta has (onto `Settings`, live and saved), `log/main` from
`EventLog`. `webapi.rootFolder` serves static files, as qBittorrent's alternative WebUI does, so
unpacking VueTorrent there is a phone interface Torrenta does not have to write.

**Security**, decided before Tier 1 ships, since this is the widest door the app will have: off
by default and on `127.0.0.1` until an address is chosen, with a warning for one that is neither
loopback nor HTTPS; authentication always, the localhost bypass off; the password as salted
PBKDF2-SHA256 in `webapi.*`, machine-local and never synced; five failed logins ban an address for
an hour; `Origin`/`Referer` checked against `Host`, the `Host` header validated against DNS
rebinding, cookies `HttpOnly` and `SameSite=Strict`; caps on body size and multipart parts, and
`.torrent` uploads through `Bencode`'s bounds; `savepath` resolved inside the allowed roots (the
download folder, the categories' folders, roots added by hand), or anyone with the password could
write anywhere the user can.

**Port 8080** by default (decided), qBittorrent's, so the *arr apps and the phone apps connect with
no edits; when it is taken, the Settings page says so and offers a free one. Settings → Remote
access holds the switch, address, port, user, password, HTTPS file, allowed roots, root folder and
bans. An endpoint whose capability the engine lacks answers as qBittorrent does for a feature it
does not have.

## Search design

qBittorrent's search is a set of Python scraper plugins, one per site, which break when a site
changes and which make the client the party doing the scraping. Torrenta does it in three layers,
so that the app itself ships no site-specific code:

1. **`SearchProvider`** — `search(query, category) → List<SearchResult>`, where a result is name,
   size, seeds, leechers, date, magnet or `.torrent` URL, provider, and a detail URL. Providers run
   in parallel on a worker with a timeout each; results are merged, deduplicated by info-hash where
   one is known, and ranked by a pure `ResultRanker` that is tested.
2. **Torznab client.** Jackett and Prowlarr expose hundreds of indexers behind one API (`/api?t=search`,
   `apikey`, Newznab categories). Point Torrenta at one, pick which indexers to query, done. This is
   how Sonarr and Radarr search, and it keeps site-specific scraping in a tool built for it.
3. **Declarative providers.** For sites with a JSON API or a stable HTML listing: a definition file
   (URL template, pagination, a selector or JSON path per field) interpreted by one generic
   provider. Users drop definitions into the config dir; the app ships examples for public,
   non-infringing indexes (Internet Archive, academic and Linux-distribution trackers). Definitions
   are data, so a site change is a file edit rather than a release.

Search is a tab beside the transfer list: a query field (Ctrl+Shift+F), category and provider chips, a
results table (name, size, seeds, leechers, date, provider), Enter opens the Add dialog with the
result pre-filled. Searches are rate-limited per provider and cached for the session.

## Architecture notes

- Packages: `engine` (API), `engine.libtorrent`, `engine.transmission` (0.9), `store` (resume data,
  settings), `search`, `rss`, `ui` (one window), `ui.detail`, `ui.settings`, `cli`, `i18n`,
  `update`; after 1.0 `session` (everything but the window, 1.2), `http` and `webapi` (1.2),
  `migrate` (1.3), `stream` (1.4).
- Threads: FX thread for the scene only; one engine poll thread; one alert thread; virtual threads
  for actions, search and RSS; every engine call off the FX thread, enforced by an assertion in
  `LibtorrentEngine`.
- Persistence: resume data as libtorrent's bencoded files under `<config>/torrents/<hash>.fastresume`,
  with the info dict inside so the file alone re-adds the torrent; settings as one properties file;
  categories, tags, RSS rules, providers as JSON. Written atomically (temp file and move).
- Config dir: `~/.torrenta` on every platform, as Termina uses `~/.termina`; `--config-dir` and
  `TORRENTA_CONFIG_DIR` override it for tests and captures.
- Tests: pure rules in JUnit; the engine in a real session on all three platforms; the window by
  capture, with CI asserting what the capture prints.

## Risks

- **Natives in the packaged app.** jlink has no jars; the `.so`/`.dylib`/`.dll` has to be placed in
  the image and found. First PR of 0.3 settles it; until then every release claim is unproven.
- **libtorrent4j tracks libtorrent master.** Pin the version, bump deliberately, and run the engine
  test on all three platforms before merging a bump.
- **Platforms the engine does not ship for**: Intel macOS and ARM Linux. The app says so and stays
  up. FrostWire's jlibtorrent fork covers both, if demand appears.
- **Resume data compatibility across libtorrent versions** is the one place data loss can happen:
  the resume file carries the info dict, so the worst case is a recheck, never a lost torrent;
  run the restart test on every libtorrent4j bump.
- **Wayland.** JavaFX runs through XWayland; fractional scaling misreports geometry, as Termina
  found. Capture and check on a Wayland desktop before each release.
- **Search providers** change and go away. Definitions as data, Torznab for the long tail, no
  site-specific code in the app.
- **The WebUI API is a door into the machine** (1.2): anyone with the password adds torrents and
  chooses where files go. Off by default, loopback first, the allowed roots, and the security list
  above settled before the first endpoint ships.
- **Imported resume data is trusted** (1.3). Another client's word that a piece is there skips the
  check, so each reader is tested on fixtures made by the real client, a truncated file included,
  and a torrent still downloading is not taken while that client runs.
- **Extracting `Session` from `MainWindow`** (1.2) moves every background rule at once. Done in
  small PRs with nothing visible changing, each verified by capture as well as by the tests.

## Out of scope

For 1.0: a web UI or RPC server of Torrenta's own and headless mode, which come in 1.2 as
qBittorrent's API (with an alternative WebUI served from a folder, not one Torrenta writes) and
`--headless`. At all: a built-in tracker, email notifications, plugin loading of arbitrary code,
reading qBittorrent's SQLite resume storage (1.3 asks for its file storage instead).
