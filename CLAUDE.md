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
  `pause-resume`, `menu:<File|Torrent|View|Help>` or `context-menu` or `peer-menu` (the Peers tab's; `peer-ban` presses Ban Peer on the first peer) (opens that menu; the PNG is
  then of the popup and `menu=` lines list its items, `[icon]` after those with a glyph, a
  submenu's items indented under it; `menu:Torrent>Queue` opens the submenu itself, from the
  keyboard), `set-location[:<folder>]` (the Set Location dialog, the folder typed in; a
  `location-hint=` line has its free-space sentence),
  `view:<transfers|search|rss>` (shows that view; a `view=` line names it and where the focus
  landed, `view-strip=` reads the strip with the showing one starred), `focus-key:<MOD+…+KEY>`
  (a key pressed where the focus is, through the scene's filters: `focus-key:CTRL+TAB`),
  `dialog-add` to press Add on an open Add dialog (`dialog-press:now|wait|paused`
for the others, `dialog-key:<KeyCode>` for a key in its file tree), `choose-files` for the
selected torrent, `select-first`, `message-log` / `message-log:<query>` (the status bar's history card; its visible
  lines are printed), `debug-log` (the Debug Log window; `-Dtorrenta.captureLogProbe=true` pushes a warning and an
  uncaught exception through the real path before the shot and prints what the log caught),
  `detail:<tab>` to show the detail pane on a tab (`detail:overview` prints `overview-header=`,
  `overview-diagnosis=`, `overview-pieces=`, `overview-graph=`, `overview-facts=` after the shot;
  every capture prints `status-bar=`), `remedy` / `remedy:<NAME>` to press the diagnosis card's first remedy or a named one (`SKIP_FILE`
  …), waiting for a card that offers it (prints `remedy-pressed=`),
  `export-to:<folder>` for Export .torrent past its native chooser, `rename:<name>` (`~` for a
  space) to fill and confirm the Rename dialog, `files-rename:<row>:<name>` for a Files-tab row,
  `web-seeds-add:<urls>` (`~` between URLs) and `web-seed-remove:<row>` for the Trackers tab's web
  seeds (a `web-seeds=` line, with `editable=`, while that tab shows),
  `files-menu[:<row>]` for the Files tab's context menu on that visible row (it waits for the
  rows, which arrive with the first detail poll; on the Files tab a `file=` line per row shown
  names its `FileKind`, the glyph's rule by extension), `welcome-clipboard:<text>` (the welcome's
  clipboard card as it would read with that text, the real clipboard untouched; a `welcome=` line
  is printed), `search` / `search:<query>` / `search-keys:<KEY+KEY>` / `search-clear` (Clear Search Results, once the search has ended)
  for the Search tab (the keys go where the focus is, once the search has ended; a stand-in server is
  `java scripts/torznab-stub.java 9696 <search.xml> [caps.xml]`), `stats` for the Statistics window
  (the PNG is of it; `stats=` and `stats-graph=` lines after the shot), `detail:speed` for the Speed
  tab (a `speed-tab=` line), `window:<w>x<h>+<x>+<y>` / `window:maximize` to move the main window
  (a `window=` line after every shot has its bounds, what is saved and whether the list's
  horizontal scrollbar shows; a `statusbar-fit=` line has each status-bar entry as width/needed),
  or `settings` / `settings:<query>` /
  `settings-page:<category>` to open the Settings window (the PNG is then of it) and print the
  visible rows and a `settings-search=` line (query, page), `settings-key:<KeyCode>` for a key in it,
  `settings-close`, `settings-layout:<left|right|hidden>/<bottom|right|hidden>` to choose the sidebar's
  and the detail pane's places on the General page (a `layout=` line after every action); the palette's ranking, the
  open dialog's title and the log window's state are printed, and when a dialog is open the PNG is
  of the dialog.

Run Maven from the project root with a JDK 25; the enforcer plugin refuses any other (`-Denforcer.skip`
to try one on purpose).

## Architecture

| Layer | What | Where |
|---|---|---|
| Entry | `--help`/`--version`, toolkit start, theme | `com.torrenta.App`, `com.torrenta.cli` |
| Events | `EngineEvent` (level, torrent, message), raised by the engine from libtorrent alerts and by its own start/stop/remove; `Engine.addListener` | `com.torrenta.engine` |
| Add | `AddRequest` (source, save path, options, priorities), `TorrentPreview`, `Engine.inspect`/`add(AddRequest)`, `Engine.fetchMetadata` (a magnet's file list while the dialog is open), `Inputs` (what a string is), `net.TorrentFetch` (a .torrent from a URL), `FileRenames.Layout` (the content layout, `add.contentLayout`); `ui.AddDialog` (rules as statics), `FileTree` (wanted + priority ladder), `FreeSpace` (disk bar and sentence) | `com.torrenta.engine`, `com.torrenta.net`, `ui` |
| Create | `CreateRequest` (path, `Format` v1/v2/hybrid, piece size or 0, private, tracker tiers, web seeds, comment, `source` tag, align, seed), `CreatedTorrent` (bytes, engine id, both hashes, counts, magnet, the seeding torrent or the add's problem), `PieceSize` (the automatic rule: ~1,500 pieces, 16 KiB … 16 MiB; `count` packed or aligned), `Engine.create(request, CreateProgress)` blocking on the caller's worker, cancellable at the next piece; `libtorrent.PieceHasher` (v1 pieces and v2 piece layers in Java, bit for bit libtorrent's); `ui.CreateDialog` (modeless, sizes the source live, Save as…, atomic write), `CreateText` (tier parser, summary, outcome) | `com.torrenta.engine`, `.libtorrent`, `ui` |
| Labels | `Category` (name, optional save path), `TorrentLabels` (one category, sorted tags, the name a person gave; JSON for the `<hash>.labels` sidecar), `Engine.rename`, `Engine.category/setCategory/tags/setTags`, `Torrent.category/tags`; `store.LabelStore` (`categories.json`, `tags.json`); `ui.SidebarFilter` (the pure rule, `format`/`parse` for `ui.sidebar.filter`), `ui.FilterQuery` (the filter box's `key:value` language → predicate + chips), `Sidebar` (a tree: All, Categories, Tags; Status and Tracker sections later), `TagsDialog` | `com.torrenta.engine`, `store`, `ui` |
| Detail | `TorrentDetail` (general, files with their piece range, peers, trackers, pieces, per-piece availability, web seeds) from `Engine.detail(id)`; `ui.Diagnosis` (why it is not moving + remedies), `OverviewText`, `PieceStrip`, `Sparkline`; `setFilePriority`, `renamePath` (`FileRenames.plan`/`problem`), `addTrackers`, `removeTracker`, `webSeeds`/`addWebSeeds`/`removeWebSeed` (`WebSeeds`: an http(s) URL with a host, `fresh` against what the torrent has; `Capability.WEB_SEEDS` to edit); `PeerFlags` letters from libtorrent's own constants | `com.torrenta.engine`, `.libtorrent` |
| Engine settings | `EngineSettings` (engine-neutral record; `listenInterfaces()` is the tested string), `Settings.engineSettings()` clamps the `connection.*` and `proxy.*` keys, `LibtorrentEngine.toPack` maps onto `settings_pack`; applied at start (`SessionParams`) and live (`applySettings`) | `com.torrenta.engine`, `store`, `.libtorrent` |
| IP filter | `IpRange` (literals only, never resolves), `Blocklist` (pure parser for .p2p/.dat/CIDR/plain), `net.BlocklistFetch` (gzip by magic), `ui.IpFilterController` (cache, schedule, bans → `Engine.setIpFilter`), `peer_blocked` alerts counted | `com.torrenta.engine`, `net`, `ui` |
| Watch folders | `WatchFolder` (entry + pure rules: destination, request, names, `.magnet` lines, magnet id, `Growth`), `WatchFolders` (the service: `configure`, `start`, `stop`, `poll`), `store.WatchFolderStore` (`watch-folders.json`), `ui.WatchFolderDialog`, Settings → Watch folders | `com.torrenta.engine`, `store`, `ui` |
| Automation | `automation.EventCommand` (pure: `tokens` splits like a shell, `substitute` fills qBittorrent's `%N %L %G %F %R %D %C %Z %T %I %J %K %%` per token, `subject` from a `Torrent` + `TorrentDetail`), `Automation` (the service: `plan` is the rule, `handle(event)` runs on a virtual thread, `Subprocess` with a 10-min ceiling, outcomes back as events), `Notifier` + `Notifiers.forPlatform` (`notify-send` / `osascript` / `toast.ps1` through PowerShell, text as arguments), `AutomationSettings` from `Settings.automation()`; `EngineEvent.Kind` says which events count | `com.torrenta.automation` |
| Search | `search.SearchProvider` (name + blocking `search(query, category)` throwing `SearchException`), `SearchResult` (sealed `Link`: `Magnet`/`TorrentUrl`; the hash is read off a magnet; `identity()` is hash else link), `SearchCategory` (Newznab numbers), `ResultRanker` (pure: dedupe keeps the better-seeded copy, rank by seeds → query coverage → date → name), `SearchService` (virtual thread per provider, 20 s each, one request per 2 s per provider through a `Ticker`, answers cached for 5 min (`fresh`), `SearchOutcome` with per-provider failures), `TorznabEndpoint` (+ `problem(url)`, the dialog's rule), `TorznabProvider` (`t=caps`/`t=search`, StAX, no entities); `store.SearchProviderStore` (`search-providers.json`, never synced); `ui.SearchProviderDialog`, Settings → Search. Declarative: `Definition` (the file, parsed and validated with the key at fault in `DefinitionException`), `DeclarativeProvider` (the one generic provider: `url`, `extract`, pure), `JsonExtractor` (tree + JSON-pointer paths), `HtmlParser` + `HtmlNode` + `Selector` (the lenient parser and the small CSS subset, no jsoup), `SizeText`/`DateText`, `SearchHttp` (the shared GET); `store.DefinitionStore` (`search-providers/<id>.json`, `refresh()` by size+mtime, `installExamples()` from `search/examples/index.txt`); `search.disabled` in settings (synced); `docs/search-providers.md` is the format | `com.torrenta.search`, `store`, `ui` |
| Search | `search.SearchProvider`/`SearchResult`/`SearchCategory`, `SearchService` (parallel providers, dedupe through `ResultRanker`, rate limit, a 5-minute cache; `Progress` reports each provider as it answers, in arrival order, on the searching thread), `TorznabProvider` + `TorznabEndpoint`, `store.SearchProviderStore` (`search-providers.json`); `ui.SearchPane` (the tab: query, chips, status line, results table remembered as `search.columns`), `SearchRows` (in-place reconcile by identity), `SearchStatus` (the line), `SearchChips` (which providers are on), `SearchAdd` (result → `AddRequest.Source`) | `com.torrenta.search`, `store`, `ui` |
| RSS rules | `rss.Rule` (the record; `recordMatch` caps the memory at 1000), `EpisodeFilter` (qBittorrent's `1x2;1x3-5;2x1-` parsed with the term at fault; `find` reads `S01E02`/`1x02`/`Season 1 Episode 2` off a title), `RuleMatcher` (compiled once per rule: feed, phrases or regex, episode filter, age, smart memory; `problem(rule)` is the dialog's rule), `RuleEngine` (`apply`: first enabled rule wins, one copy of an episode per batch; `record`: the memory after the adds that went through; the log lines), `RssService.setDownloader`/`runRulesNow`/`markDownloaded`, `Article.downloaded`; `RssStore` `rules.json`; `ui.RuleDialog` (live Matches on a 250 ms pause, matched on a virtual thread), `RuleAdd` (rule → `AddRequest`), `RuleText` (create-rule-from-title and the table's words) | `com.torrenta.rss`, `ui` |
| Statistics | `engine.SessionStats` (the record; `UNKNOWN` = -1 for a counter the library lacks), `Engine.stats()` (from the last `session_stats` alert, no session round trip), `store.StatsStore` (`stats.properties`: all-time down/up/active, pure `encode`/`decode`, atomic save), `ui.SpeedHistory` (ring buffers: a second for 10 min, a minute for 24 h), `SpeedPlot` (the pure point mapping and the binary ceiling ladder), `SpeedGraph` (the canvas, theme colours through zero-sized swatches), `StatsText` (the rows), `StatisticsWindow` (`Ctrl+Shift+T`), the detail pane's Speed tab | `com.torrenta.engine`, `store`, `ui` |
| Direct downloads | `direct.WithDownloads` (an `Engine` around the torrent engine: one list, every id-call routed by `DirectDownload.isId`, `Inputs.isDirectLink` adds, torrent-only calls refused with the reason, a finished `.torrent` handed over), `DirectDownloads` (the service: one curl process per running download, a `direct-downloads` thread ticking each second for sizes, rates and the queue, `downloads.json` + `downloads/<id>.headers/.err`), `DirectDownload` (the record + its JSON; id `url-<sha1 of the link>`), `Curl` (pure: argv, the `--config -` text, `outcome` of an exit), `CurlHeaders` (the last block of `--dump-header`), `DirectNames` (link → safe name, `unused`); `Torrent.direct()`, `Settings.directOptions()` (`download.curl`, `download.linksAtOnce`) | `com.torrenta.engine.direct` |
| Update | `update.UpdateCheck` (pure: payload, version rules, due), `UpdateService` (GitHub, off the FX thread, `-Dtorrenta.updateEndpoint` for a capture) — Termina's, verbatim | `com.torrenta.update` |
| Desktop | `os.SingleInstance` (one per config dir: a `FileLock` on `instance.lock` decides, a loopback `ServerSocket` whose port and token are `instance.port`/`instance.token`, owner-only; `claim` hands off or binds, `deliverTo` flushes what arrived first), `InstanceMessage` (pure: token line, one input per line, blank line ends, caps, `absolute` against the sender's cwd), `Handlers` (pure argv: `xdgMimeDefault`, `windowsRegister/Unregister`, `desktopId` by `StartupWMClass`, `apply` with a `Runner`), `MacHandlers` (AWT `Desktop` open handlers), `Badge` + `BadgeState` (pure count + rate + short text) + `Badges.create` (`AwtBadge` dock text on macOS, `UnityBadge` LauncherEntry signal on Linux, none on Windows), `AwtUse.headful` (the one rule for `java.awt.headless`); `Settings.osHandler/osBadge` (`os.*`, never synced); `MainWindow.openFromOutside` | `com.torrenta.os`, `store`, `ui` |
| Tray | Nux's and Modula's package, free of JavaFX: `TorrentaTray` (update + dispose), `Trays.create` picks `SniTray` (a StatusNotifierItem + `com.canonical.dbusmenu` on the session bus through dbus-java; Linux, no fallback) or `AwtTray` (Windows, macOS) and says why when neither; `TrayDisplay` (pure: what the arrow's colour means), `TrayIconRenderer` (the SVG mark's geometry in Java2D), `SniMenuModel` (pure: ids and properties); `ui.TrayStatus` (pure: snapshot + rates → display + line); `Settings.tray/closeToTray` (`ui.tray`, `ui.closeToTray`); `MainWindow.installTray/hideToTray/quit` | `com.torrenta.tray`, `ui` |
| UI | The window: menu bar, filter, torrent list, status bar, palette, `EventLog` + `LogWindow`, `MessageLog` + `MessageLogPopup` (the status bar's history: every `setStatus` with its level, a card over the bar in the palette's chrome, Editora's), `DebugLog` + `DebugLogWindow` (Termina's: j.u.l WARNING and above from every logger plus uncaught exceptions, mirrored to `torrenta-session.log`; View → Debug Log and Settings → General → Diagnostics), `AddDialog` (+ `FileTree`, `FreeSpace`, `MagnetNames`), `DetailPane` (+ `FileRows`) in a `SplitPane` under or beside the list (`DetailPlace`; the sidebar's side is `SidebarPlace`, each a shown key plus a side key so hiding keeps the place, each detail place its own divider), the Help menu's update items, `SettingsWindow` (Termina's shell: `Category`/`Group` sidebar, `row()` with keywords, `filter()`, `reload()` under a `loading` guard); `TorrentRows` reconciles snapshots into stable rows; `ColumnLayout` + `ColumnMemory` remember columns; `Format` is the tested column text; `NetworkStatus` (the status bar's port dot and words, DHT nodes, free space where downloads go; pure, from `Engine.connectivity()` + `stats().dhtNodes()`) + its `Clock`; `Welcome` + `WelcomeText` (the window before the first torrent: drop zone, the four keys from their commands, the clipboard's magnet or link offered on each focus, the download folder with a "change"); `Command` is one action used by menu, context menu and palette; `Chords` is every chord the window binds, by command id, in one table (`ChordsTest`: no key twice, neither palette key taken); `PaletteKey` (the chord that opens it, `ui.paletteKey`, Alt+X by default, re-applied live by `applyPaletteKey`); `View` (Transfers, Search, RSS; the id is the command that shows it) + `ViewSwitch` (pure: next/previous with wrap, by place, `parse`) + `ViewStrip` (the row above the centre: name, `Ctrl+1/2/3` badge, accent line under the one showing, a click switches; not focus-traversable), `MainWindow.show(View)` the one way a view changes; `Actions` holds the tested rules | `com.torrenta.ui` |
| Engine API | `Engine`, `Torrent`, `TorrentState`: start, add, list, rates, stop. No libtorrent types. | `com.torrenta.engine` |
| Engine | libtorrent through libtorrent4j; `NativeLibrary` unpacks and points at the native | `com.torrenta.engine.libtorrent` |
| Remote engine | `TransmissionEngine` over Transmission's RPC (`RpcClient`: POST JSON, the 409 session-id handshake, basic auth; `Json` tree over jackson-core streaming; `Bencode` reads a `.torrent` for `inspect`; `TransmissionLabels` puts the category in a `category:` label); `Engine.Capability`/`Capabilities` say what an engine can do, `ui.CapabilityRules` is the pure rule the window hides by; `EngineProfile` (local or remote, URL, user, password) in `store.ProfileStore` (`profiles.json`, owner-only, never synced), `engine.profile` picks the next start's; Settings → Engine, `ui.EngineProfileDialog`; `MainWindow.startEngine/switchProfile` build the engine through App's `EngineFactory` | `com.torrenta.engine.transmission`, `engine`, `store`, `ui` |
| Store | `ConfigDir` (`~/.torrenta`), `Settings` (properties), `TorrentStore` (one opaque blob per torrent) | `com.torrenta.store` |
| i18n | Termina's: `Messages.tr(key)` / `tr(key, args…)` (MessageFormat in the active locale), English `messages.properties` as base and fallback, `messages_<de|es|fr|it|pt>.properties` as overlays, UTF-8 through `getResourceAsStream`; `ui.language` (`system` by default) chosen in `App.start` after the settings load, before any window, applied on restart; a missing key shows itself and is logged once | `com.torrenta.i18n` |

**Persistence is the engine's job, the store's format is not the engine's business.** `TorrentStore`
holds bytes by info-hash and never looks inside; `LibtorrentEngine` decides that the bytes are
resume data (with the info dict), writes them on add, on metadata, on finish, every two minutes if
modified, and on stop, and re-adds them on start. Removing a torrent deletes its blob.

**Nothing on the FX thread calls the engine.** `App` starts it on a worker after the window is
shown; `MainWindow` polls it once a second on its own thread and hands the FX thread an immutable
list; `addInput` runs on a virtual thread. Inputs that arrive before the engine is up are queued.

## Releasing

`docs/release.md` is the checklist. `release.yml` on a version-shaped tag (`v[0-9]+.[0-9]+.[0-9]+*`):
a preflight (tag = pom, no snapshot, a `## X.Y.Z` section in CHANGELOG.md, CI passed on the commit,
started on the tag and waited for when there is none), then three targets only (linux-x64,
macos-arm64, windows-x64 — the engine's native exists for no others), each smoke-testing its
packaged app before uploading, then JReleaser 1.26.0 (pinned, like every action: SHAs, Dependabot).
A manual run is a dry run that runs `full-release --dry-run`, so jreleaser.yml, the cask and the
winget manifests are exercised before a tag ever is. The body is `changelog.external`:
`scripts/release/ReleaseNotes.java` writes `.github/release-notes.md` plus the version's changelog
section, summarised to its bold leads when it passes JReleaser's 10,000-character body limit (the
commit list was mostly merge lines, and the first one ran past it). `immutableRelease` (Editora's)
creates a draft, uploads, publishes. The cask and winget templates under `src/jreleaser/distributions/`
override JReleaser's stock ones (`src/jreleaser/NOTES.md` says why, and is outside the template
directory because JReleaser copies every file there into the tap). JReleaser also copies the
project LICENSE into each packager's repository (`skipLicenseFile` on winget) and JGit renormalises
winget-pkgs' CRLF files: docs/release.md has the checks before `PACKAGES_TOKEN` is added. JReleaser's
CLI cannot read a git worktree; dry-run it in a clone. Termina's process with Editora's hardening.

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
- **The menu bar in the title bar is `StageStyle.EXTENDED`** (`ui.ExtendedWindow`, Editora's, with
  JavaFX 27's `HeaderBar`), never `UNDECORATED`: the platform keeps its buttons, drag, double-click
  and edge resize. `initStyle` throws once a stage has been shown, so `MainWindow`'s constructor
  reads `ui.extendedWindow` first and the setting applies on restart; the top of the window asks
  `stage.getStyle()`, never the setting, so the two cannot disagree. The menu bar is
  `HeaderDragType.NONE` (a header drags by default, and a menu that drags cannot open) and the title
  `DRAGGABLE_SUBTREE` (`DRAGGABLE` misses the label's text node). The buttons' glyph colour follows
  the desktop unless told: `matchButtonsToTheme` on every appearance change. macOS is gated out in
  `supportedOn` (the menu belongs to the system bar there) and the row is disabled, not hidden.
- **The interface font is Inter, bundled, loaded before any stylesheet** (`Fonts.load()` in
  `App.start`), and attached per scene and per dialog pane (`Fonts.installUiFont`), never in
  app.css: every window and dialog is its own scene, and a scene sheet survives a theme switch.
  A new Stage or Dialog without the call shows the system face.
- **Nothing in the chrome may be focus-traversable** (Termina's rule holds here). The filter box
  took initial focus on a fresh launch because the table is hidden until the first torrent, so it
  was the first traversable node. It is `setFocusTraversable(false)`; Ctrl+K (and Ctrl+F) reach it.
- **Space and Delete are handled on the table, never as scene accelerators.** An accelerator fires
  on any unconsumed key press, and a text field does not consume Space — so a space typed into the
  filter would pause the selection. `Command.hint` carries the key's name for the menus, where it
  is the item's accelerator as a `HintCombination`: drawn in the accelerator column, never
  matching a key, equal only to itself (KeyCombination's own equality compares modifiers alone).
- **"Paused" means paused by a person.** libtorrent's queue pauses auto-managed torrents too;
  `Torrent.paused` is `PAUSED && !AUTO_MANAGED`, and `pause()` clears `AUTO_MANAGED` first so the
  queue does not resume what the user stopped.
- **A hybrid torrent has padding files.** `TorrentBuilder` makes v1+v2 torrents whose file list
  interleaves `.pad/NNN` entries; `inspect` leaves them out but keeps the torrent's own indices, so
  a test that wants "file 1" must read the index off the preview. `totalSize()` counts padding;
  the preview sums the real files.
- **`add_torrent` checks the files at once, paused or not, and a `force_recheck` issued during that
  check is dropped.** So the content layout is never renames through the handle after the add: it
  is `renamed_files` in the add parameters (`FileRenames.layout`), which the add's own check
  already looks under. Needs the file list: a magnet added before its metadata keeps its own.
- **libtorrent 2.1.0-39 keeps file renames badly, so the engine keeps them itself.** Measured: a
  `rename_file` through the handle renames on disk and a recheck in the same session finds the
  file, but `torrent_file()` keeps showing the old name, and the resume data writes the new one
  relative to the root folder while reading it back relative to the save path, so a restart lost
  the file (33 %, re-downloading). `TorrentStorage.renamed` (the `.storage` sidecar) holds every
  renamed file's full path; `restoreNames` puts them all back before the add, `detail()` shows
  them, and the `.part` suffix goes on and off the recorded name (`onDisk`), never the original.
  `Engine.renamePath` does a file or a folder (`FileRenames.plan`/`problem`, tested); the
  `file_renamed_alert` handler removes the folders a rename emptied, never the save path. A
  name given back as the original is no longer a rename, and an empty record drops the sidecar.
  Transmission renames through `torrent-rename-path` (checked by the same `FileRenames.problem`
  first), so the Files tab's Rename is on both engines; the layout on add is
  `Capability.CONTENT_LAYOUT`, libtorrent's only, and the hidden choice falls back to the torrent's own.
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
- **Web seeds live in the resume data once added**, so a seed removed from a `.torrent`'s
  `url-list` stays removed across a restart. `addUrlSeed`/`removeUrlSeed` are posted to the
  network thread (read them back with `await`), and the engine asks for resume data right after,
  queued behind the change. A batch with one non-web-seed is refused whole. Transmission lists
  `webseeds` but its `torrent-set` cannot change them, hence the capability.
- **Durations from libtorrent4j are milliseconds** (`activeDuration`, `seedingDuration`,
  `nextAnnounce`), **and so are its times** (`addedTime`, `completedTime` go through
  `time2millis`); the engine divides, the records promise seconds. The Added and Completed columns
  and the omnibox's `added<` read years in the tens of thousands until that was caught.
  `TorrentStatus.numPieces()` is how many pieces it *has*; the total is `TorrentInfo.numPieces()`.
- **A CSS syntax error drops the rest of the sheet without a word to the user.** JavaFX logs one
  `CssParser` WARNING ("Expected RBRACE at [line,col]") and ignores every rule after it; a missing
  `}` in app.css hid the speed graphs', the Statistics window's and the RSS glyph's styles for a
  while. Any capture's stderr shows the warning: grep it after touching a stylesheet.
- **The detail pane is the redesign's inspector** (`ui.DetailPane`): a header (state disc, name,
  `OverviewText.subtitle`, Pause/Reannounce/Open folder/··· run the window's commands by id, so the
  chords shown are the commands' own), then Overview, Files, Peers, Trackers, Log, Speed. General
  and Pieces were folded into the Overview (a remembered `general`/`pieces` tab opens it). The
  Overview is `Diagnosis.of` (pure, first rule wins: error, queued, no peers, pieces nobody has
  with skip-that-file when they fall in one wanted file, peers choking, quiet) as an amber card,
  the bucketed `PieceStrip` (amber where a missing piece has no copy: `TorrentDetail.availability`,
  libtorrent's `piece_availability`, empty for a seed), a `Sparkline` whose window shrinks to the
  history the selection has (`Sparkline.window`), then the facts. Two columns at `WIDE` (760 px)
  and up, one below. The Log tab is the event log filtered by torrent id.
- **The status bar's right-hand group keeps its width; the message gives way.** Rates with a
  minute's `Sparkline` each (zero before the session's first sample: it starts idle), the
  alternative-speed pill, free space on the download folder's volume (read every ten polls on the
  poll thread, `FreeSpace.sentence(disk, -1)`), the error pill.
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
- **The libtorrent inside libtorrent4j 2.1.0-39 (pinned 2026-01-25) can crash on its own thread.**
  `torrent::on_piece_verified` calls `m_picker->completed_hash_job()` without checking the picker,
  and the picker can go while a piece's hash job is in flight: it is dropped when the torrent
  becomes a seed and by `handle_inconsistent_hashes`, and a `force_recheck` (which a `move_storage`
  that finds files issues by itself) leaves stale jobs behind. The completion then segfaults on
  libtorrent's network thread (`Current thread is native thread`, `libtorrent4j.so+0x41e71a`,
  `si_addr` 0) and surefire reports exit 134 with no failing test. No call of ours is on the stack,
  so no lock or fence of ours can prevent it. Upstream guarded it after the pin (ff0ebd76c5, then the
  picker generation in 63c45f16bd); no libtorrent4j release has either. It killed
  `LibtorrentEngineTest` once in about 125 CI runs (PR #65's first, 2026-10-22). When a fork dies,
  `target/surefire-reports/test-progress.log` (`TestProgress`) names the test that was running, and
  CI prints it, the crash report and the core's stacks.
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
  tree for a torrent in the list and clears the mark. Torrent → Choose Files… (`choose-files`) is
  live for one marked torrent (`Actions.canChooseFiles`); the settings are not observable, so
  setting or clearing a mark bumps `pickFilesMarks`. Nothing shows the mark on the row yet.
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
- **A remote engine is a second `Engine`, and the window hides what it lacks.** `Engine.capabilities()`
  is valid after `start()` (a daemon's depends on its RPC version); `MainWindow.applyCapabilities`
  runs in `engineReady` and sets one `BooleanProperty` per capability that the gated commands'
  `enabled` is and-ed with (`gate(id, enabled)`), so a hidden command's chord and palette entry are
  dead too, not only its menu item; every menu item is made through `menuItem(id)` and every toolbar
  button through `toolButton` so they can be found. `CapabilityRules` is the one map (command id →
  capability, page → capabilities); Settings rows carry theirs in `row(…, capability)`, dialogs get
  `applyCapabilities(caps)` before showing. Add a capability by adding the enum value, its
  `capability.*` key in six catalogues, and the places that need it; never grey out, never imitate.
- **The Transmission engine owns no store and pushes no settings at start.** The daemon keeps its
  torrents, resume data and labels; `applySettings` sends `sessionDiff(lastApplied, next)`, the keys
  that differ from the record it was built with (this machine's file), so a page edit sets one key
  and the daemon's port and limits are never overwritten by local defaults. Speeds are KB (1000) on
  the daemon and KiB on the pages; `kibToKb`/`kbps` convert. The poll is one `torrent-get` with
  `LIST_FIELDS` (plus `sequential_download` from RPC 18, which is the field's name; `sequentialDownload`
  is silently ignored by 4.1.0-beta.2); `detail()` alone asks for files, peers, `trackerStats` and
  the base64 `pieces` bitfield (first piece in the high bit). Events are `diff(before, after,
  announced, removedByUs)`: the engine's own add and remove are announced by the action, the poll
  announces what another client did. A save path equal to this machine's default download folder
  means "the daemon's own" and is not sent (`addArguments`); the Add dialog defaults to
  `engine.downloadDir()` when `LOCAL_FILES` is missing. `sizeWhenDone` counts pieces the daemon
  already has even when unwanted, so a complete torrent with a file skipped is a seed to it.
  `TransmissionDaemonTest` starts the real daemon on free ports with `--auth` and destroys it in
  `@AfterAll`; it is `@EnabledIf` the daemon and `transmission-create` are on the PATH.
- **Profiles are machine-local secrets.** `profiles.json` is written owner-only where POSIX modes
  exist, `EngineProfile.toString()` never prints the password, `engine.*` is in `SyncKeys`'s local
  prefixes. `App.createEngine` is the one place an engine is built; `MainWindow.switchProfile` stops
  the poll, the watcher and the old engine (on a worker), empties the list and runs `startEngine`,
  which ends in `engineReady` again; `engineUp` stays counted down, so a rule's add during the gap
  sees `engine == null` and fails plainly.
- **"Open" means a peer from outside reached the port, and nothing less.** `engine.Connectivity`
  is kept on the alert thread from `listen_succeeded`/`listen_failed` (TCP only), `portmap`/
  `portmap_error` (either transport mapping is enough), `incoming_connection` (an address on this
  network, CGNAT included, proves nothing) and `dht_bootstrap`; a new port forgets the rest.
  Without a running torrent no peer comes, so the port reads "checking" until
  `NetworkStatus.Clock` has counted five minutes of running torrents with none, then "blocked".
  The capture prints `statusbar=` (port, dot, DHT, free space); a loopback peer cannot show "open". `Engine.connectivity()` defaults to `NONE` (the
  Transmission engine), which hides the port and the DHT; free space needs `LOCAL_FILES`.
- **A removed torrent's alert carries only the hash.** `remove()` reads the name first and raises
  the event itself; the `TORRENT_REMOVED` alert is not listened to.
- **Ctrl+Tab is a scene event filter, not an accelerator**: focus traversal owns Tab, and a
  control would take it first. It is `Ctrl` on every platform (`Cmd+Tab` never reaches the window
  on macOS). `Shortcut+1/2/3` are scene accelerators beside the view commands' own chords (a
  command has one accelerator, and the menus keep showing Ctrl+Shift+F and Ctrl+R), so the detail
  pane's tabs cannot have the digits; `docs/UI-REDESIGN.md` says they need other keys. The view
  commands keep the ids `transfers`, `search` and `rss`, so the menus, palette and capture actions
  that named them still do; the strip is built with the centre and gets its badges afterwards
  (`showChords`).
- **The omnibox is a value, `ui.Omnibox`, and the field is drawn from it.** Every keystroke goes
  `omnibox.typed(text, now)` → `setOmnibox`, which rewrites the field under a `settingFilter`
  guard, redraws the chips and re-applies the filter; the rules (a term chips on the space after
  it, Backspace on empty hands it back, `>` means a command, a magnet means an add, Enter selects
  the matches) are tested there, not in the window. Escape on the list is `Actions.escape`: the
  filter first, then the selection. The capture hook takes `omnibox:<text>` (`~` for a space, since
  the action list is trimmed), `omnibox-key:<KeyCode>` and `table-key:<KeyCode>`, and prints an
  `omnibox=` line. Ctrl+K is the omnibox's; the Search tab moved to Ctrl+Shift+F.
- **A row's state is computed, not stored**: `RowState.of(torrent, quietSeconds)` and
  `StatusLine.of(…)` are pure; `TorrentRow` keeps only the stall clock, set *before* its torrent
  property fires so every cell of a tick reads the same number. The row's `st-*` pseudo-class
  (`MainWindow.StateRow`) listens by **invalidation**: two equal snapshots fire no change event,
  and a stalled torrent's snapshots are often equal while its clock moves. State colours are
  scoped under `.state-colors` on the scene root so the setting can turn them off. The redesign
  these come from is `docs/UI-REDESIGN.md`.
- **The chrome's look is app.css over the theme, and a scene sheet always wins.** A scene
  stylesheet outranks the user-agent stylesheet whatever the selector's specificity, which is why
  app.css can restyle AtlantaFX's tables (no vertical lines, 28 px small-cap headers, faint row
  rules) with short selectors. The base size is 13 px, set in `ui-font.css` per scene like the
  family, not in the theme files. JavaFX CSS has no `text-transform` or `letter-spacing`:
  `Headers.uppercase(table)` rewrites the column titles after the columns are in place. The
  state colours have chip tints (`-torrenta-*-chip`), the selection, the sidebar's chosen row
  and the toolbar glyph colour are `ladder()` tokens on `.root`, so every theme gets them.
- **Row density swaps column layouts and must renew the cells.** Comfortable rows (two lines, the
  default) and compact rows each keep their own layout (`table.columns.comfortable`,
  `table.columns`); `applyDensity` saves the one being left and applies the other. `refresh()`
  reuses cells, and a progress cell built at the other row height kept its bar at the top of the
  row: re-setting each column's cell factory makes new ones. The Name column's value is a
  `RowView` (name, sentence, progress), not the name, so the comfortable cell redraws when its
  second line changes.
- **Every glyph is `Icons.Glyph`, a Group that knows its box**: made at the toolbar's 17 px,
  `at(Icons.MENU_SIZE)` for a menu item or a tab. `Icons.forCommand(id)` is the rule for which
  command shows which glyph; `MainWindow.menuItem(id)` applies it, so a command added to a menu
  through `command(id).menuItem()` directly would be the one item without an icon (`IconsTest`
  lists the menus' ids). macOS's system menu bar takes only `ImageView` graphics, so it shows none.
- **Rows are `TorrentRow` objects, never `Torrent` snapshots.** The list has an extractor on the
  row's torrent property, which is what makes the `SortedList` re-sort on an in-place update.
- **Surefire runs on the classpath** and points `torrenta.nativeDir` into `target/`, so tests never
  write under `~/.torrenta`. `LibtorrentEngineTest` starts a real session on purpose: it is the one
  test that proves the native loads on each CI platform.
- **On macOS the test JVM ends with `_exit`, because libtorrent4j's OpenSSL can abort at `exit()`.**
  libtorrent4j 2.1.0-39 links OpenSSL 3.5.5 statically, and OpenSSL registers its cleanup with
  `atexit`. About one macOS CI run in twenty, that cleanup freed a pointer that was not allocated
  and libmalloc aborted the fork *after every test had reported*: `Abort trap: 6`, no hs_err, nothing
  on stderr, and surefire's "Error occurred in starting fork" (the store tests' expected exceptions
  were only the last lines printed). macOS's crash report named it: the VM thread in `exit()` →
  `__cxa_finalize_ranges` → libtorrent4j's OpenSSL code → `___BUG_IN_CLIENT_OF_LIBMALLOC_POINTER_BEING_FREED_WAS_NOT_ALLOCATED`,
  with no libtorrent thread alive. `com.torrenta.NativeTeardownSkip` (a `LauncherSessionListener`
  in `META-INF/services`, macOS only) waits for the other shutdown hooks (JaCoCo's) once the test
  session closed and calls `_exit(0)` through FFM, so the C exit handlers never run; hence
  `--add-opens java.base/java.lang` in the argLine. The JDK starts the hooks one by one and
  `join` returns at once on a thread not yet started, so `awaitOthers` waits for each to start
  first: joining straight away cut `jacoco.exec` short (`EOFException` in the report) in eight
  of twelve shards of a 72-run macOS loop. The packaged app quits through the same `exit()`
  and has not been seen to crash there. A failing macOS job copies `java*.ips` from
  `~/Library/Logs/DiagnosticReports` into the uploaded reports and prints the crashed thread
  (`.github/scripts/ips-summary.py`); surefire drops a fork's stderr once it has said goodbye, so the
  crash report is the only witness to an exit-time crash.
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
- **The tray decides `java.awt.headless` in `App.main`, from the settings file, before launch.**
  AWT stays headless (the macOS AppKit run-loop contention Termina and Editora met) except where
  the tray is AWT's and wanted (`Trays.usesAwt`: Windows, macOS); Linux's tray is pure D-Bus plus
  Java2D and runs headless, with no XEmbed fallback on purpose. So the switch applies on restart
  on Windows and macOS, and `Trays.create` says "restart" rather than "unsupported" when it finds
  AWT headless there. `Platform.setImplicitExit(false)` is set only once an icon really exists:
  with no icon a hidden window is an unreachable process. Closing then hides (`hiddenToTray`, and
  `onHidden` tears nothing down); `quit()` is the one way out, from File, the tray menu, or a close
  with no tray; `App.stop` ends with `System.exit(0)` because both backends leave non-daemon threads
  (the AWT event thread, D-Bus workers). The tray is pushed from the poll thread, only when
  `TrayStatus` changed, since a repaint crosses the bus. Found against the live bus, in Nux:
  `Properties.Get` returns the stored `Variant`, a dbus-java `Tuple` subclass must be generic
  (`MenuPair`), and GNOME renders SNI menus but not SNI tooltips, hence the disabled status line as
  the menu's first item. The capture prints `tray=` (`sni`, `awt`, or the reason), `trayLine=` and
  `showing=`; `-Dtorrenta.captureAction=close-request` sends the window a real close request
  (`close-window` is `stage.close()`, which skips the handler), so `showing=false` with `tray=sni`
  is close-to-tray working. `busctl --user` can read the item (`org.kde.StatusNotifierItem-<pid>-1`:
  `ToolTip`, `/MenuBar GetLayout`) and click its items (`Event isvu <id> clicked s "" 0`).

- **Downloads from links are curl processes, and everything about them goes in on stdin.**
  `Curl.command` is `curl --disable --config -` and nothing else (no `~/.curlrc`, and a token in
  the link or the proxy's password never shows in a process list); `Curl.config` writes the rest.
  Progress is the `.part` file's size, read each second; the total is the last answer's
  `Content-Length` or a `206`'s `Content-Range`, read from the `--dump-header` file as it is
  written; the name the server gives is applied at the end, since the part is open while it runs.
  Pause and remove end the process (`Run.stopping`, so its exit is not an outcome) and wait for it
  so Windows lets go of the file; resume is a new process with `continue-at -`; a server that
  refuses the range gets the file started again once, and a `416` to a resume of a complete file
  is finished (`Curl.outcome`, tested). The window tells a download from a torrent by its id
  (`Torrent.direct()`), so `RowState` has it Done where a torrent would be Seeding, `StatusLine`
  names the server where it names peers, `Diagnosis` offers only the error card, the Name cell
  puts `Icons.link()` before it, `SidebarStatus.LINKS` ("From links") and `is:link` pick them, and
  the torrent-only commands are enabled by `Actions.anyTorrent` and act on `Actions.torrentsOnly`.
  A link goes through `ui.LinkDialog` (folder, name, paused; `problem`/`request` are the pure
  rules) unless "Add without asking", then `Engine.addLink(LinkRequest)`; a name typed there is
  `DirectDownload.named` and survives the server's. Links that arrive while it is open wait in
  `linksWaiting`. The capture hook takes `link-name:<name>` (`~` for a space) and `link-download`,
  and prints `link-dialog=`. The download
  limit in force (`Speed.effectiveDownloadBytes`, the alternative pair included, from
  `WithDownloads.refreshLimits`/`applySettings`) is shared by the downloads from links among
  themselves, apart from the torrents': each curl gets `DirectDownloads.share` of it (an equal part
  among `meantToRun`, the queue's target rather than the runs of this instant, capped by its own
  limit from the Limits dialog) as `limit-rate`, and since curl takes it only at start the tick
  restarts a run whose share moved by more than a tenth (`reshare`), carrying on with
  `continue-at`. Upload limits mean nothing to curl. `DirectDownloadsTest` runs the real curl against a loopback server and is
  `@EnabledIf` curl is on the PATH.
- **The config folder is locked by the first instance, and the port file is not the arbiter.**
  `SingleInstance.claim` runs in `App.main` before the toolkit: `tryLock` on `instance.lock`
  (an `OverlappingFileLockException` means this JVM holds it already, which is the test's second
  object), and only the loser reads `instance.port`/`instance.token` and connects, retrying for
  up to 900 ms because the winner may still be binding (two processes launched by one
  double-click). A stale pair left by a crash is taken over because the OS released the lock; a
  listener that never answers `ok` (a reused port, the wrong token) counts as nobody. The accept
  loop refuses silently on a bad token (the sender treats silence as nobody home) and queues
  batches until `deliverTo`, since the window is built after the claim. `--config-dir` scopes
  it all. The hand-off is proven headless by two `dev-run.sh` processes on one config dir, the
  first with a 15 s capture delay and `dialog-add`, the second with a magnet: the first's
  `event=` lines show "Received 1 item from another Torrenta" and the magnet added.
- **`java.awt.headless` is one rule, `AwtUse.headful`**, which replaced `Trays.usesAwt` in
  `App.main`: Linux never (D-Bus tray and badge, desktop-entry handlers); macOS when the tray,
  the badge or the handler is wanted (`Taskbar` and `Desktop` both throw headless); Windows for
  the tray alone (the badge is skipped there: `Taskbar.setWindowIconImage` wants an AWT window,
  which the stage is not). Every AWT initialisation runs on a daemon thread (`tray-init`,
  `badge-init`, `open-handlers`), never the FX thread, for the AppKit reason. jpackage takes
  `--file-associations` for installer types only (the app-image step refuses it) and the dmg
  wraps the image as it is, so on macOS `scripts/add-mac-types.sh` patches
  `CFBundleDocumentTypes` and `CFBundleURLTypes` (`magnet`, which jpackage cannot declare at all)
  into `Info.plist` with PlistBuddy and re-signs, as the version fix does. The capture prints `instance=`, `badge=`, `handler=` and `openHandlers=`.

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
- **Every removal asks, through `RemoveConfirm`** (a `Kind` with its `confirm.remove.*` header and
  question, or the free form the torrent list uses), and its button says Remove. **Every editable
  `Spinner` gets `Spinners.commitOnBlur`**, or a number typed before Tab is dropped.
- **A new setting is a key, two accessors, a row with keywords, a line in `reload()` and in
  `OWNED_KEYS`** (for Reset). Keywords are what the search box finds; the title leads them.
- **Version comes from the pom** through `build-info.properties`; nothing else states it.
- **Every string a user reads goes through `tr()`**, including enum display names (`label()` /
  `toString()` read the catalogue at call time, never in a static field: the language is chosen
  after classes load). `UiStringSweepTest` fails on a literal in a text position, on a `tr` key the
  English catalogue lacks, and on a dead key; `MessagesTest` keeps the six catalogues to one key
  set with matching placeholders. In a choice limb that carries a `{0}` an apostrophe needs four
  quotes (`''''`), in one without a brace two; a new key goes into all six files.

## Not done

0.2 through 0.9 are feature-complete; 0.4's Settings pages (Connection, Protocol, IP filter, Speed,
Queue and share limits), the per-torrent Limits dialog, queue moves, Set Location, super seeding,
export `.torrent` and rename (a torrent's own name, files and folders, the content layout on add)
are in. Transmission maps the file rename (`torrent-rename-path`); super seeding, export, a name of
its own and the layout are capabilities it lacks (`SUPER_SEEDING`, `EXPORT_TORRENT`,
`RENAME_TORRENT`, `CONTENT_LAYOUT`), hidden for it; web seeds are listed on both and edited on
libtorrent's alone (`WEB_SEEDS`, the Trackers tab). 0.4 is feature-complete. **0.1.0 is the first release** (the
roadmap's numbers are milestones, not tags), after a green `workflow_dispatch` dry run of
release.yml on 2026-10-23; master goes back to `0.1.1-SNAPSHOT` after the tag. The Homebrew/winget
packagers need a `PACKAGES_TOKEN` secret (a PAT with `repo` scope; Termina has none either) before
they do anything.
