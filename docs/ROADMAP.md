# Torrenta roadmap

A keyboard-first, cross-platform BitTorrent client on JavaFX, as complete as Transmission and
qBittorrent, with built-in search. This is the plan from the 0.1 scaffold to a 1.0 that can replace
either of them for daily use. One PR per feature, each line below is one PR unless it says
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
| Bind to a network interface or address | ✓ | ✓ | 0.4 |
| Anonymous mode (no client identification) | ✗ | ✓ | 0.4 |
| Connection limits: global, per torrent, upload slots | ✓ | ✓ | 0.4 |

### Bandwidth, queue, seeding

| Feature | T | Q | Torrenta |
|---|---|---|---|
| Global download and upload limits | ✓ | ✓ | 0.4 |
| Alternative limits, toggle, weekly schedule | ✓ | ✓ | 0.4 |
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
| Serve a web UI / RPC of its own | ✓ | ✓ | later |
| Headless daemon mode | ✓ | ✓ (`-nox`) | later |

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

**0.3 is feature-complete.** What is left before the `v0.3.0` tag is the release pipeline's dry
run and the `PACKAGES_TOKEN` secret; see the README's Releasing section.

### 0.4 — Network, bandwidth, queue
Everything in Settings that the engine already knows how to do.

1. **Settings window** — done as the shell plus the General and Adding pages (download folder,
   update check, Add dialog defaults): sidebar, search across rows with dimmed pages, live apply,
   Reset. The Connection, Speed, BitTorrent and Advanced pages arrive with their settings in the
   PRs below, each mapping onto libtorrent's `settings_pack`.
2. **Connection** — done, except the port test (libtorrent has none; it needs an outside service
   and goes with 0.8's OS integration or stays out): port, random port, UPnP/NAT-PMP, interface
   binding, IPv6, connection limits, proxy. `EngineSettings` maps onto `settings_pack`, applied at
   start and live; the per-torrent limit is set on each add.
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
  `update`.
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

## Out of scope for 1.0

A web UI or RPC server of Torrenta's own, headless mode, a built-in tracker, email notifications,
plugin loading of arbitrary code.
