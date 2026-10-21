# Torrenta

A keyboard-first, cross-platform BitTorrent client. JDK 25 + JavaFX 27 + Maven, modular (JPMS,
module `com.torrenta`). Same author, layout and conventions as Termina and Editora.

## Commands

- Run: `./mvnw javafx:run`
- Test + format check: `./mvnw verify` (Spotless/Palantir runs at `verify`; `./mvnw spotless:apply`
  fixes formatting)
- Native app image: **`./mvnw clean -Pdist -DskipTests package`** ⇒ `target/dist/Torrenta{.app}`;
  add `-Dskip.installers=false` for the installers. `clean` is **required**: jlink's input is built
  from `target/classes`, and an incremental compile can leave that inconsistent.
- Run with ad-hoc options: **`scripts/dev-run.sh -Dfoo=bar -- <app args>`**. `mvn javafx:run`
  cannot take them — the plugin's `<options>` are fixed in the pom.
- Smoke: `scripts/dev-run.sh -Dtorrenta.capture=/tmp/shot.png -- "magnet:?…"` (or
  `TORRENTA_CAPTURE=/tmp/shot.png ./mvnw javafx:run`) renders the real window, writes a PNG, prints
  `capture:` lines (title, focus, engine, status bar) and exits. `-Dtorrenta.captureAction=` runs
  window actions first: `select-all`, `palette`, `palette:<query>`, or a command id such as
  `pause-resume`, `dialog-add` to press Add on an open Add dialog (`dialog-press:now|wait|paused`
for the others, `dialog-key:<KeyCode>` for a key in its file tree), `choose-files` for the
selected torrent, `select-first`,
  `detail:<tab>` to show the detail pane on a tab, `search` / `search:<query>` / `search-keys:<KEY+KEY>`
  for the Search tab (the keys go where the focus is, once the search has ended; a stand-in server is
  `java scripts/torznab-stub.java 9696 <search.xml> [caps.xml]`), `stats` for the Statistics window
  (the PNG is of it; `stats=` and `stats-graph=` lines after the shot), `detail:speed` for the Speed
  tab (a `speed-tab=` line), or `settings` / `settings:<query>` /
  `settings-page:<category>` to open the Settings window (the PNG is then of it) and print the
  visible rows; the palette's ranking, the
  open dialog's title and the log window's state are printed, and when a dialog is open the PNG is
  of the dialog.

Run Maven from the project root with a JDK 25; the enforcer plugin refuses any other (`-Denforcer.skip`
to try one on purpose).

## Architecture

| Layer | What | Where |
|---|---|---|
| Entry | `--help`/`--version`, toolkit start, theme | `com.torrenta.App`, `com.torrenta.cli` |
| Events | `EngineEvent` (level, torrent, message), raised by the engine from libtorrent alerts and by its own start/stop/remove; `Engine.addListener` | `com.torrenta.engine` |
| Add | `AddRequest` (source, save path, options, priorities), `TorrentPreview`, `Engine.inspect`/`add(AddRequest)`, `Engine.fetchMetadata` (a magnet's file list while the dialog is open), `Inputs` (what a string is), `net.TorrentFetch` (a .torrent from a URL); `ui.AddDialog` (rules as statics), `FileTree` (wanted + priority ladder), `FreeSpace` (disk bar and sentence) | `com.torrenta.engine`, `com.torrenta.net`, `ui` |
| Create | `CreateRequest` (path, `Format` v1/v2/hybrid, piece size or 0, private, tracker tiers, web seeds, comment, `source` tag, align, seed), `CreatedTorrent` (bytes, engine id, both hashes, counts, magnet, the seeding torrent or the add's problem), `PieceSize` (the automatic rule: ~1,500 pieces, 16 KiB … 16 MiB; `count` packed or aligned), `Engine.create(request, CreateProgress)` blocking on the caller's worker, cancellable at the next piece; `libtorrent.PieceHasher` (v1 pieces and v2 piece layers in Java, bit for bit libtorrent's); `ui.CreateDialog` (modeless, sizes the source live, Save as…, atomic write), `CreateText` (tier parser, summary, outcome) | `com.torrenta.engine`, `.libtorrent`, `ui` |
| Labels | `Category` (name, optional save path), `TorrentLabels` (one category, sorted tags; JSON for the `<hash>.labels` sidecar), `Engine.category/setCategory/tags/setTags`, `Torrent.category/tags`; `store.LabelStore` (`categories.json`, `tags.json`); `ui.SidebarFilter` (the pure rule, `format`/`parse` for `ui.sidebar.filter`), `ui.FilterQuery` (the filter box's `key:value` language → predicate + chips), `Sidebar` (a tree: All, Categories, Tags; Status and Tracker sections later), `TagsDialog` | `com.torrenta.engine`, `store`, `ui` |
| Detail | `TorrentDetail` (general, files, peers, trackers, pieces) from `Engine.detail(id)`; `setFilePriority`, `addTrackers`, `removeTracker`; `PeerFlags` letters from libtorrent's own constants | `com.torrenta.engine`, `.libtorrent` |
| Engine settings | `EngineSettings` (engine-neutral record; `listenInterfaces()` is the tested string), `Settings.engineSettings()` clamps the `connection.*` and `proxy.*` keys, `LibtorrentEngine.toPack` maps onto `settings_pack`; applied at start (`SessionParams`) and live (`applySettings`) | `com.torrenta.engine`, `store`, `.libtorrent` |
| IP filter | `IpRange` (literals only, never resolves), `Blocklist` (pure parser for .p2p/.dat/CIDR/plain), `net.BlocklistFetch` (gzip by magic), `ui.IpFilterController` (cache, schedule, bans → `Engine.setIpFilter`), `peer_blocked` alerts counted | `com.torrenta.engine`, `net`, `ui` |
| Watch folders | `WatchFolder` (entry + pure rules: destination, request, names, `.magnet` lines, magnet id, `Growth`), `WatchFolders` (the service: `configure`, `start`, `stop`, `poll`), `store.WatchFolderStore` (`watch-folders.json`), `ui.WatchFolderDialog`, Settings → Watch folders | `com.torrenta.engine`, `store`, `ui` |
| Automation | `automation.EventCommand` (pure: `tokens` splits like a shell, `substitute` fills qBittorrent's `%N %L %G %F %R %D %C %Z %T %I %J %K %%` per token, `subject` from a `Torrent` + `TorrentDetail`), `Automation` (the service: `plan` is the rule, `handle(event)` runs on a virtual thread, `Subprocess` with a 10-min ceiling, outcomes back as events), `Notifier` + `Notifiers.forPlatform` (`notify-send` / `osascript` / `toast.ps1` through PowerShell, text as arguments), `AutomationSettings` from `Settings.automation()`; `EngineEvent.Kind` says which events count | `com.torrenta.automation` |
| Search | `search.SearchProvider` (name + blocking `search(query, category)` throwing `SearchException`), `SearchResult` (sealed `Link`: `Magnet`/`TorrentUrl`; the hash is read off a magnet; `identity()` is hash else link), `SearchCategory` (Newznab numbers), `ResultRanker` (pure: dedupe keeps the better-seeded copy, rank by seeds → query coverage → date → name), `SearchService` (virtual thread per provider, 20 s each, one request per 2 s per provider through a `Ticker`, session cache, `SearchOutcome` with per-provider failures), `TorznabEndpoint` (+ `problem(url)`, the dialog's rule), `TorznabProvider` (`t=caps`/`t=search`, StAX, no entities); `store.SearchProviderStore` (`search-providers.json`, never synced); `ui.SearchProviderDialog`, Settings → Search. Declarative: `Definition` (the file, parsed and validated with the key at fault in `DefinitionException`), `DeclarativeProvider` (the one generic provider: `url`, `extract`, pure), `JsonExtractor` (tree + JSON-pointer paths), `HtmlParser` + `HtmlNode` + `Selector` (the lenient parser and the small CSS subset, no jsoup), `SizeText`/`DateText`, `SearchHttp` (the shared GET); `store.DefinitionStore` (`search-providers/<id>.json`, `refresh()` by size+mtime, `installExamples()` from `search/examples/index.txt`); `search.disabled` in settings (synced); `docs/search-providers.md` is the format | `com.torrenta.search`, `store`, `ui` |
| Search | `search.SearchProvider`/`SearchResult`/`SearchCategory`, `SearchService` (parallel providers, dedupe through `ResultRanker`, rate limit, session cache; `Progress` reports each provider as it answers, in arrival order, on the searching thread), `TorznabProvider` + `TorznabEndpoint`, `store.SearchProviderStore` (`search-providers.json`); `ui.SearchPane` (the tab: query, chips, status line, results table remembered as `search.columns`), `SearchRows` (in-place reconcile by identity), `SearchStatus` (the line), `SearchChips` (which providers are on), `SearchAdd` (result → `AddRequest.Source`) | `com.torrenta.search`, `store`, `ui` |
| RSS rules | `rss.Rule` (the record; `recordMatch` caps the memory at 1000), `EpisodeFilter` (qBittorrent's `1x2;1x3-5;2x1-` parsed with the term at fault; `find` reads `S01E02`/`1x02`/`Season 1 Episode 2` off a title), `RuleMatcher` (compiled once per rule: feed, phrases or regex, episode filter, age, smart memory; `problem(rule)` is the dialog's rule), `RuleEngine` (`apply`: first enabled rule wins, one copy of an episode per batch; `record`: the memory after the adds that went through; the log lines), `RssService.setDownloader`/`runRulesNow`/`markDownloaded`, `Article.downloaded`; `RssStore` `rules.json`; `ui.RuleDialog` (live Matches on a 250 ms pause, matched on a virtual thread), `RuleAdd` (rule → `AddRequest`), `RuleText` (create-rule-from-title and the table's words) | `com.torrenta.rss`, `ui` |
| Statistics | `engine.SessionStats` (the record; `UNKNOWN` = -1 for a counter the library lacks), `Engine.stats()` (from the last `session_stats` alert, no session round trip), `store.StatsStore` (`stats.properties`: all-time down/up/active, pure `encode`/`decode`, atomic save), `ui.SpeedHistory` (ring buffers: a second for 10 min, a minute for 24 h), `SpeedPlot` (the pure point mapping and the binary ceiling ladder), `SpeedGraph` (the canvas, theme colours through zero-sized swatches), `StatsText` (the rows), `StatisticsWindow` (`Ctrl+Shift+T`), the detail pane's Speed tab | `com.torrenta.engine`, `store`, `ui` |
| Update | `update.UpdateCheck` (pure: payload, version rules, due), `UpdateService` (GitHub, off the FX thread, `-Dtorrenta.updateEndpoint` for a capture) — Termina's, verbatim | `com.torrenta.update` |
| UI | The window: menu bar, filter, torrent list, status bar, palette, `EventLog` + `LogWindow`, `AddDialog` (+ `FileTree`, `FreeSpace`, `MagnetNames`), `DetailPane` (+ `FileRows`) in a vertical `SplitPane`, the Help menu's update items, `SettingsWindow` (Termina's shell: `Category`/`Group` sidebar, `row()` with keywords, `filter()`, `reload()` under a `loading` guard); `TorrentRows` reconciles snapshots into stable rows; `ColumnLayout` + `ColumnMemory` remember columns; `Format` is the tested column text; `Command` is one action used by menu, context menu and palette; `Actions` holds the tested rules | `com.torrenta.ui` |
| Engine API | `Engine`, `Torrent`, `TorrentState`: start, add, list, rates, stop. No libtorrent types. | `com.torrenta.engine` |
| Engine | libtorrent through libtorrent4j; `NativeLibrary` unpacks and points at the native | `com.torrenta.engine.libtorrent` |
| Store | `ConfigDir` (`~/.torrenta`), `Settings` (properties), `TorrentStore` (one opaque blob per torrent) | `com.torrenta.store` |

**Persistence is the engine's job, the store's format is not the engine's business.** `TorrentStore`
holds bytes by info-hash and never looks inside; `LibtorrentEngine` decides that the bytes are
resume data (with the info dict), writes them on add, on metadata, on finish, every two minutes if
modified, and on stop, and re-adds them on start. Removing a torrent deletes its blob.

**Nothing on the FX thread calls the engine.** `App` starts it on a worker after the window is
shown; `MainWindow` polls it once a second on its own thread and hands the FX thread an immutable
list; `addInput` runs on a virtual thread. Inputs that arrive before the engine is up are queued.

## Releasing

`release.yml` on a `v*` tag: three targets only (linux-x64, macos-arm64, windows-x64 — the engine's
native exists for no others), each smoke-tests its packaged app before uploading. `jreleaser.yml`
publishes the release with `.github/release-notes.tpl` ahead of the changelog; the cask and winget
templates under `src/jreleaser/distributions/` override JReleaser's stock ones (see the NOTES.md
there: the stock cask names a `binary` that a bundle does not have). Everything here is Termina's
release process with the names changed; Termina's README and memory hold the war stories.

## Things that will bite you

- **libtorrent4j does not unpack its native library.** Its JNI class's static initialiser
  `System.load`s `-Dlibtorrent4j.jni.path`, else `System.loadLibrary("torrent4j")`. The per-OS jar
  is resources only: `lib/<arch>/libtorrent4j.{so,dylib,dll}`. `NativeLibrary.ensureAvailable()`
  copies it out and sets the property, and **must run before any `org.libtorrent4j` class that
  touches native code is loaded** — `LibtorrentEngine.start()` is the only entry point for that
  reason. The failure otherwise is a `LinkageError` whose message is a link to the project page;
  the cause has the real reason.
- **No static field in the engine package may touch a libtorrent4j constant.** `TorrentHandle.QUERY_*`
  and friends are SWIG objects; a `static final` built from one runs at class load, before the
  native is unpacked, and the JNI class then fails to initialise for the life of the JVM
  (`NoClassDefFoundError: Could not initialize class ... libtorrent_jni` in every later test). Use a
  nested holder class (`StatusQuery`) or compute inside the method.
- **`System.load` needs an absolute path.** A relative `-Dtorrenta.nativeDir` produced "Expecting
  an absolute path of the library"; `NativeLibrary` absolutises.
- **The per-OS jar cannot be `requires`d.** Its automatic module name depends on the OS
  (`libtorrent4j.linux` …), so `module-info` names only the core. It has to reach the path by other
  means: `ALL-MODULE-PATH` in `dev-run.sh`, the classpath under the javafx plugin, `addModules`
  in a future jlink. The resource is found through `ClassLoader.getResourceAsStream`, which works
  for both because automatic modules open every package.
- **`TorrentHandle.status()` leaves the name out.** Ask with `status(TorrentHandle.QUERY_NAME)`,
  or every magnet shows as its hash.
- **Resume data is asynchronous.** `saveResumeData(flags)` is a request; the answer is a
  `save_resume_data_alert` (or `..._failed_alert`, which is also the normal reply to an
  only-if-modified request that found nothing modified) on libtorrent4j's alert thread. That is
  where the store gets written. `stop()` counts outstanding requests and waits, bounded to 5 s, so
  the last state lands before the session goes.
- **The add alert's params are enough to bring a magnet back.** `add_torrent_alert.params()` written
  as resume data holds the hashes, name, trackers and save path, so a magnet survives a crash before
  its metadata arrives; the real resume data replaces the blob later.
- **The id is `getInfoHashes().getBest().toHex()`**, not `infoHash()`: a v2-only torrent has an
  all-zero v1 hash, and two of them would collide.
- **Alerts arrive on their own thread; tests `await`, bounded, never sleep-and-hope.**
- **The interface font is Inter, bundled, loaded before any stylesheet** (`Fonts.load()` in
  `App.start`), and attached per scene and per dialog pane (`Fonts.installUiFont`), never in
  app.css: every window and dialog is its own scene, and a scene sheet survives a theme switch.
  A new Stage or Dialog without the call shows the system face.
- **Nothing in the chrome may be focus-traversable** (Termina's rule holds here). The filter box
  took initial focus on a fresh launch because the table is hidden until the first torrent, so it
  was the first traversable node. It is `setFocusTraversable(false)`; Ctrl+F reaches it.
- **Space and Delete are handled on the table, never as scene accelerators.** An accelerator fires
  on any unconsumed key press, and a text field does not consume Space — so a space typed into the
  filter would pause the selection. `Command.hint` carries the key's name for the menus.
- **"Paused" means paused by a person.** libtorrent's queue pauses auto-managed torrents too;
  `Torrent.paused` is `PAUSED && !AUTO_MANAGED`, and `pause()` clears `AUTO_MANAGED` first so the
  queue does not resume what the user stopped.
- **A hybrid torrent has padding files.** `TorrentBuilder` makes v1+v2 torrents whose file list
  interleaves `.pad/NNN` entries; `inspect` leaves them out but keeps the torrent's own indices, so
  a test that wants "file 1" must read the index off the preview. `totalSize()` counts padding;
  the preview sums the real files.
- **`add_torrent` checks the files at once, paused or not, and a `force_recheck` issued during that
  check is dropped.** Renames through the handle are confirmed by `file_renamed_alert` but a
  recheck afterwards still did not find the files under the new names in 2.1.0-39. That is why the
  "no root folder" layout is not in the Add dialog; see the roadmap before trying again.
- **Test compilation patches the module**, so a test that needs a JDK module the main code does
  not (`jdk.httpserver`, for the fetch test's local server) gets `--add-modules` and `--add-reads`
  in the compiler plugin's test execution. At run time the tests are on the classpath.
- **The detail pane is polled only for the one selected torrent**, on the poll thread, with the
  pieces bitfield (`QUERY_PIECES`): a status copy per piece, which is why it is not fetched for
  every row. `detailId` and `detailWanted` are volatile hand-offs from the FX thread.
- **libtorrent 2.1 keeps trackers, comment, creator and web seeds outside `torrent_info`.** A
  `.torrent` is read with `load_torrent_parsed` (via `BDecodeNode.bdecode`), which fills them into
  the add parameters; `new TorrentInfo(bytes)` reads the info dictionary alone and the torrent is
  added **without its trackers**, silently. The Add dialog shows comment and creator from the
  preview; General cannot read them back (the handle has no accessor), and `AnnounceInfohash` has
  no scrape counts.
- **Durations from libtorrent4j are milliseconds** (`activeDuration`, `seedingDuration`,
  `nextAnnounce`); the engine divides, the records promise seconds.
- **A resume-data answer can arrive after the torrent was removed.** The engine marks removed ids
  and ignores late writes for them; without that, the remove test saw the blob come back.
- **The image does not contain the native library; it sits beside it.** The per-OS jar is
  resources only, and a resource in a named module is encapsulated unless its package is opened,
  so `-Pdist` unpacks `lib/<arch>/libtorrent4j.*` and hands it to jpackage as app content;
  the launcher sets `-Dlibtorrent4j.jni.path=$APPDIR/../torrenta-native/...` and `NativeLibrary`
  does nothing when the property is set. `$APPDIR` is `lib/app` (Linux), `Contents/app` (macOS),
  `app` (Windows); the content directory is one level up on all three.
- **Only the core libtorrent4j jar gets a moditect descriptor** (every package exported); the
  native jars are excluded from the module path altogether.
- **Exiting while a modal dialog's nested event loop runs aborts the JVM** (`SIGSEGV` in
  `libglassgtk3` `destroy_and_delete_ctx` during `Application.terminate`). The capture hook closes
  any dialog and exits on the next pulse; a Quit while the Add dialog is open goes the same way and
  is a known gap until Quit is intercepted before `Platform.exit`.
- **`settings_pack` enum constants are SWIG objects**, like every other libtorrent4j constant:
  `toPack` runs only after the native has loaded. Keys are set by `swigValue()` through the pack's
  generic `setBoolean`/`setInteger`/`setString`; the typed setters cover only a few.
- **Every public engine method that reaches the session is `synchronized`, like `stop()`.**
  `SessionManager.stop()` deletes the native session; a poll, a flush or an action in flight on
  another thread then reads freed memory, which corrupted the heap and killed the JVM at quit
  (exit 139, no hs_err) every few runs. The lock makes a call in flight finish first and a later
  one find no session. The alert thread never takes it (the listener uses the alert's own handle),
  so `stop()` waiting for resume-data alerts cannot deadlock; keep it that way. The test
  `stopWaitsForACallInFlightAndRefusesTheNext` hammers the race under `MALLOC_PERTURB_`.
- **A SWIG vector element is a pointer into the vector, and the vector's finalizer frees it.**
  `torrent_handle_vector.get(i)`, `announce_entry_vector.get(i)`, `peer_info_vector.get(i)` (and
  libtorrent4j's own `SessionHandle.torrents()`, `TorrentHandle.trackers()`/`peerInfo()`, which are
  built on them) hand out proxies with `swigCMemOwn=false`; once the vector is unreachable the
  collector frees the elements under them, and the next native call on one is a use-after-free that
  killed the CI JVM inside `file_progress`. The engine therefore keeps handles only as the session's
  own copies (`find_torrent`), copies trackers and peers through their SWIG copy constructors, and
  holds every vector with `Reference.reachabilityFence` until the copies are made. Anything that
  points into a parent (`status.pieces()`, `info.files()`) is used while the parent is fenced. CI
  runs the Linux tests with `MALLOC_PERTURB_` so a slip crashes every time, not once in a hundred.
- **Run-on-event is driven by `EngineEvent.Kind`, not by parsing messages.** The engine tags
  `ADDED` (never a torrent restored at start: `restoring.remove(id)` decides), `FINISHED`
  (`torrent_finished_alert`, which also fires after a check that finds every piece), `ERROR`
  (torrent, file and tracker errors; the tracker's is level WARNING); `SEEDING_DONE` is raised by
  the window's share-limit poll, which hands the snapshot along because a REMOVE has already taken
  the torrent out of the session. `Automation.handle` is called on the alert thread and returns at
  once; the command runs on a virtual thread with no shell (`ProcessBuilder` on the substituted
  tokens), stdin from the null device, output capped. `TorrentBuilder` makes hybrids, so the id
  (`getBest()`, the v2 hash cut to 20 bytes) is neither `%I` nor `%J`; `TorrentDetail.General`
  carries both full hashes. Notifier scripts never see the text inline: `osascript` reads
  `argv`, `toast.ps1` its parameters.
- **Watch folders are a thread of the window, not of the engine** (`engine.WatchFolders`, owned by
  `MainWindow`, started in `engineReady`, stopped on hide, restarted by the Settings page): one
  `WatchService` thread plus a poll every minute as the safety net, every engine call on the
  watcher's own scheduler thread. A file is read once its size has held for two looks
  (`WatchFolder.Growth`), a second apart after an event; a file that is not a torrent is renamed
  `.invalid` and said once; a file that failed to be handled is remembered and left alone until it
  changes. Tests drive `configure` + `poll()` directly, never the service's timing. The list is
  `watch-folders.json` (`store.WatchFolderStore`), this machine's, never synced.
- **Settings sync never asks git to merge** (`sync/SyncEngine`, Editora's design): the clone under
  `<configDir>/sync/repo` is reset to the remote, the live settings are three-way merged per key
  against `refs/torrenta/base` (`SyncMerge`, this machine wins), written over the tree, committed
  and pushed; the base moves only after the push, so an interrupted round is simply repeated. What
  travels is decided by `SyncKeys` (everything but `sync.*`, folders, the interface, clocks and the
  proxy password). Automatic rounds run git with no prompt of any kind (`QuietGit`); a `gh` account
  that can read an https repository is pinned in the clone's config (`SyncAccount`, adl has two).
  `Settings.setOnSaved` is how a change reaches the service; its own saves set `applying` first.
- **Speed limits are re-evaluated every minute** (`Engine.refreshLimits(now)`, from the window's
  poller) and after any settings change; the pair in force is `Speed.alternativeActive(now)`,
  a pure rule with the overnight case tested. Limits are KiB/s in settings and bytes/s in the
  pack; 0 is "none" on both sides.
- **libtorrent's share limits pause nothing.** `share_ratio_limit` and `seed_time_limit` only rank
  seeds for the queue (`seed_rank`); the pausing or removing is `ShareLimit.decide` in the window's
  poll, once a second, against `Torrent.seedingTime` (`finished_duration`, so a torrent with files
  left out counts). Active counts are `-1` for "no limit" in the pack; `0` would park every torrent.
  A torrent's own share limit is `torrent.<hash>.share` in the settings file (the `torrent.` prefix
  never syncs); its rate caps and counts are the handle's and travel in the resume data.
- **The IP filter is replaced whole, never merged**: `set_ip_filter` takes a complete
  `ip_filter`, so the controller rebuilds it from cache + local file + bans every time. A filter
  set before `start()` is applied at start, so no peer slips in while the first download runs.
- **PEX has no session switch in libtorrent**, only the per-torrent `disable_pex` flag, and there
  is no session-wide default tracker list: `applySettings` walks the handles for both, and `add`
  sets the flag and appends the trackers on their own tier. `EngineSettings.Protocol` is nested so
  the connection tests did not have to grow seven arguments.
- **A proxy with a username is a different libtorrent type** (`socks5_pw`, `http_pw`); the record
  decides with `proxyAuthenticated()`. `proxy_hostnames` is on, so lookups go through the proxy.
- **Engine events arrive on libtorrent4j's alert thread.** `MainWindow` hops to the FX thread
  before touching the log or the status bar; `EventLog.add` is FX-only. The j.u.l bridge takes
  the hop as a parameter so tests run it inline.
- **libtorrent's `add_torrent_params` has no category or tags** (libtorrent4j 2.1.0-39 exposes
  none), so a torrent's filing is a `<hash>.labels` sidecar beside its `.fastresume`, written on
  the caller's thread the moment it changes (never through the resume-data alert), read back in
  `restore()`, deleted by `TorrentStore.delete`. The engine holds them in a map by id for the
  snapshots. The sidebar is not focus-traversable for the same reason as the filter box.
- **The Add dialog's magnet fetch is a real torrent, hidden.** `fetchMetadata` adds the magnet in
  upload mode, out of the queue, and keeps it in `fetches`: `handles()` skips it (so no snapshot,
  flush or settings walk sees it), its events are dropped, and its add alert is swallowed by id
  (`fetchAddAlerts`) so no blob is written even if it was cancelled before the alert came. On
  metadata it writes the `.torrent` (`write_torrent_file` with the link's trackers,
  `allow_missing_piece_layer` for v2) and leaves the session. The real add goes through
  `Engine.add(request, fetch)`, which ends the fetch first (libtorrent hands back the existing
  handle for a duplicate, silently) and gives the new torrent the peers the fetch dialled, in
  `add_torrent_params.peers`. Those are recorded from `peer_connect_alert` (direction out) as they
  connect, not read at the end: once the metadata is in, the fetch is upload-only and a seed
  drops it at once. A loopback test of this has to look at progress, not at peers: the new
  torrent downloads the test payload in a moment and the two seeds part. A v1 magnet of a hybrid torrent gets a new best hash (the v2 one) when its metadata
  arrives, so the fetch is matched by either hash (`fetchOf`), never by `id()`.
- **"Add now, pick files when ready"** adds with stop-after-metadata and marks
  `torrent.<hash>.pickFiles` in the settings; `MainWindow.chooseFiles(id)` opens the dialog's
  tree for a torrent in the list and clears the mark. Nothing shows the mark on the row yet.
- **libtorrent's mmap disk I/O takes the JVM's SIGSEGV handler.** Once a file of
  `mmap_file_size_cutoff` pieces (40 by default) is mapped, libtorrent installs a SIGSEGV/SIGBUS
  guard of its own in place of the JVM's (`sigaction`, no chaining); a fault that is not its own,
  such as the JVM's next safepoint poll, is reset to the default action and the process dies: exit
  139, no `hs_err` file, "Segmentation fault" from the shell, any time after a big file was
  touched, typically at a GC. `toPack` sets the cutoff to `Integer.MAX_VALUE`, so the same disk
  I/O reads with `pread` and never installs it; `aFileBigEnoughToMapIsSeededWithout…` is the
  shape that died (a 2 MiB file in 16 KiB pieces polled while a thread drives GC). The same guard
  sits under `set_piece_hashes`, which the session settings do not reach, which is why
  `Engine.create` hashes in Java (`PieceHasher`) and only lists, lays out and writes through
  `create_torrent`; libtorrent4j's `TorrentBuilder` is for tests on files under the cutoff only
  (`theJavaHasherMatchesLibtorrentsOwnBitForBit` compares the two). A cancel stops at the next
  piece. `create_torrent`'s and `file_storage`'s constants are SWIG objects (`CreateFlags`
  holder). There is no setter for the info dictionary's `source` key: it is put into the `entry`
  by hand before bencoding, which is why the hashes are read off the final bytes with `load`. A
  duplicate seed add hands back the torrent already in the list, silently.
- **A removed torrent's alert carries only the hash.** `remove()` reads the name first and raises
  the event itself; the `TORRENT_REMOVED` alert is not listened to.
- **A row's state is computed, not stored**: `RowState.of(torrent, quietSeconds)` and
  `StatusLine.of(…)` are pure; `TorrentRow` keeps only the stall clock, set *before* its torrent
  property fires so every cell of a tick reads the same number. The row's `st-*` pseudo-class
  (`MainWindow.StateRow`) listens by **invalidation**: two equal snapshots fire no change event,
  and a stalled torrent's snapshots are often equal while its clock moves. State colours are
  scoped under `.state-colors` on the scene root so the setting can turn them off. The redesign
  these come from is `docs/UI-REDESIGN.md`.
- **Row density swaps column layouts and must renew the cells.** Comfortable rows (two lines, the
  default) and compact rows each keep their own layout (`table.columns.comfortable`,
  `table.columns`); `applyDensity` saves the one being left and applies the other. `refresh()`
  reuses cells, and a progress cell built at the other row height kept its bar at the top of the
  row: re-setting each column's cell factory makes new ones. The Name column's value is a
  `RowView` (name, sentence, progress), not the name, so the comfortable cell redraws when its
  second line changes.
- **Rows are `TorrentRow` objects, never `Torrent` snapshots.** The list has an extractor on the
  row's torrent property, which is what makes the `SortedList` re-sort on an in-place update.
- **Surefire runs on the classpath** and points `torrenta.nativeDir` into `target/`, so tests never
  write under `~/.torrenta`. `LibtorrentEngineTest` starts a real session on purpose: it is the one
  test that proves the native loads on each CI platform.
- **Palantir 2.92 does not run on JDK 27.** Build with a JDK 25. The enforcer rule in the pom says so
  at `validate`, so `mvn javafx:run` under the sdkman default of 27 stops with the reason instead of
  running a mix the project never tests (one such run crashed at quit).
- **A Torznab answer is parsed as a stream with entities and DTDs off** (`TorznabProvider.factory`):
  it comes from the network. `torznab:attr` is matched by local name, since not every server
  declares the namespace the same way; `peers` is seeders plus leechers, so leechers is the
  difference when none is given outright. The capture hook's `settings-search-add[:<url>]`,
  `settings-search-dialog-test:<url>` (Test pressed in the dialog) and `settings-search-test`
  (the page's Test on the first row) drive the page; a stub serving the caps fixture is enough.

- **The RSS rules run on the fetch thread, under their own lock, after the merge and before the
  feed's listeners hear.** `RssService.finish` hands the articles new by identity to `runRules`
  only when the switch is on; the downloader (the window's `downloadForRule`) waits on
  `engineUp` because the feeds due at start are fetched before the engine is up, and a rule's
  failure is reported, not retried: the article is no longer new. The service stamps the feed's
  id on every article a source hands over (a stub's items had none, and the log line then had
  no feed). `markDownloaded` also marks read; the Add dialog's own confirm does not mark an
  article, only Download and the rules do.
- **Session statistics come from libtorrent4j's own stats loop.** `SessionManager` posts
  `session_stats` once a second and hands the `SESSION_STATS` alert to typed listeners; the
  engine copies the counters it names (`LibtorrentEngine.Counter`, strings only, indices from
  `find_metric_idx_ex` at start, since that is native) into a volatile array, and `stats()` reads
  the array, so the Statistics window costs no session call. The all-time totals are
  `stats.properties`, written as loaded + this session every five minutes and at stop, never as
  last write + delta. A canvas cannot read `-color-*`; `SpeedGraph` keeps a zero-sized `Region`
  per colour with a style class app.css paints and reads its background after CSS. A peer on the
  local network ignores the session's rate limits (so a loopback capture cannot throttle the
  seeder from Settings); a torrent's own limit holds, which is what `scripts/peer-stub.java`
  uses, and it dials once per few seconds: a second connection from the same address is dropped
  as a duplicate and the transfer never starts.
- **A definition file's `id` is its file name**, and the examples are found through
  `search/examples/index.txt`, never a directory listing (a jar and a jlink image list nothing).
  `Definition.parse` rejects unknown keys at every level on purpose; the examples are parsed and
  run against recorded fixtures in `ExampleDefinitionsTest`, so edit the fixture with the file. The
  HTML parser treats a `<link>` with no attributes as RSS's (content) and one with attributes as
  HTML's void element; `<x/>` is childless on every tag. Ancestor matching stops at the element a
  selector was asked of (jsoup's rule), so `row.select("table td")` finds nothing inside a row.

## Conventions (carried from Termina)

- **Extract the decision, then test it.** Anything with a rule in it becomes a static method or a
  pure class with its own tests. UI classes stay thin.
- **Verify by driving it.** UI bugs are visual and none of them throw. Use the capture hook, not
  reasoning about dispatch order.
- **Chords are `Shortcut+key`** (Ctrl off macOS, Cmd on it), declared once on the menu item so the
  menu and the accelerator cannot disagree.
- **A settings change applies live**, with no OK button: every control in `SettingsWindow` writes
  through and `saveQuietly()`s; `reload()` sets `loading` so listeners stay quiet while the controls
  are populated. The engine reads the download folder through a `Supplier` for the same reason.
- **A new setting is a key, two accessors, a row with keywords, a line in `reload()` and in
  `OWNED_KEYS`** (for Reset). Keywords are what the search box finds; the title leads them.
- **Version comes from the pom** through `build-info.properties`; nothing else states it.

## Not done

0.2 and 0.3 are feature-complete; 0.4's Settings pages (Connection, Protocol, IP filter, Speed,
Queue and share limits) and the per-torrent Limits dialog are in. Left from 0.4: queue positions,
super seeding, set location, rename, export `.torrent`. The `v0.3.0` tag waits on the release dry run. **No release has been cut yet**: the first tag must be preceded by a `workflow_dispatch` dry
run of release.yml, and the Homebrew/winget packagers need the `PACKAGES_TOKEN` secret copied from
Termina's repository (the same PAT) before they do anything.
