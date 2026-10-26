# Changelog

Notable changes, newest first. Versions follow [semantic versioning](https://semver.org); until
1.0.0 the minor number moves for anything user-visible.

## Unreleased

### Added

- **Updatable torrents.** A `magnet:?xs=urn:btpk:…` link (BEP 46) is looked up in the DHT and
  its current version added; from then on Torrenta looks for a newer version every six hours
  (Settings → Adding torrents → Updatable torrents) and adds it into the same folder, filed the
  same way, with the files that did not change linked rather than downloaded again. Once the new
  version is complete the old one is no longer followed, and leaves the list (its files kept)
  if you choose. Settings → Automation can run a program or notify on a new version.

- **Publish an updatable torrent.** The Create Torrent dialog's Publish as updatable makes a key
  under a name you choose, seeds the torrent and puts it in the DHT; the updatable link it shows
  is what others follow. Torrent → New Version… on it creates the next version under the same key,
  and whoever follows gets it the next time they look; Copy Updatable Link gives the link again.
  The key is kept in `keys/<name>.key` in Torrenta's folder, on this computer only: anyone with it
  can publish versions, and without it there will be no more, so keep a copy. While Torrenta runs
  it puts the link back in the DHT every hour when it is no longer found there.

- **Play a file while it downloads.** The Files tab's Stream in Player opens a file in mpv or VLC
  (wherever they are installed, or the player chosen in Settings → Adding torrents) as it
  downloads; Copy Stream URL gives the address for any other player on this computer. The pieces
  the player is about to reach come first, the file's first and last at the start (where a
  video's index sits), and a jump ahead waits for its piece rather than plays garbage. The
  Overview's pieces map rings the pieces being read ahead.

- **Slower, or paused, on battery and on a metered network.** Settings → Speed → Battery and
  network: while the computer runs on its battery, or the network is one paid by the byte (a
  phone's hotspot), use the alternative speed limits or pause the torrents, resumed when it ends.
  The status bar's pill says why ("Alt speed: on battery"). Turning the alternative limits on or
  off by hand now holds until the schedule, the battery or the network next changes, as in
  qBittorrent, where before the schedule kept them on whatever the pill said. The switch is this
  machine's and no longer travels with Settings sync. Read from UPower and NetworkManager on
  Linux, `pmset` on macOS (which has no way to tell a metered network) and the system on Windows.

- **Files you already have are not downloaded again.** When a new torrent holds a file that
  another torrent in the list has complete (v2 and hybrid torrents, matched by content, whatever
  the names), the Add dialog offers to link it into place: no bytes written, the two torrents
  share the file, and the add's own check verifies every byte. A file on another drive can be
  copied instead (a clone where the file system can make one, so no space is used either); the
  dialog asks first unless Settings → Adding → Files already on disk says to copy. Watch folders,
  RSS rules and adds without a dialog link without asking. The Files tab marks such a file and
  says where it came from.

- **Bring your torrents over from qBittorrent, Transmission or Deluge.** File → Import from
  another client… finds qBittorrent, Transmission (the daemon or the app) and Deluge on this
  machine, Flatpak's too, or reads a folder you choose, and lists its torrents
  with what will happen to each: complete or part done, they come over at their progress without a
  check, stopped ones stopped, with their categories (and qBittorrent's folders for them; Deluge's label is the category), tags,
  the names you gave them and their files, and their own share limits; one whose files do not match is checked as
  it is added and comes up short rather than wrong; one Torrenta has, or one the other client is
  still writing, is left alone. A qBittorrent that keeps its torrents in its SQLite database is told how
  to switch it back to files. The event log has the summary.

- **A phone interface through a web UI written for qBittorrent.** Settings → Remote access → Web
  interface folder serves a folder at the server's address the way qBittorrent serves an
  alternative WebUI: unpack VueTorrent there and a phone's browser runs Torrenta. Its public half
  is open to anyone (the login page), its private half only after signing in; nothing outside the
  folder, hidden in it, or behind a link out of it is served. The API gains what such interfaces
  poll: `sync/maindata` (the whole picture once, then only the fields that changed) and
  `sync/torrentPeers`. And what their detail screens edit: file priorities, the location (inside
  the allowed folders), a torrent's name, a file's or folder's name, trackers, per-torrent limits,
  sequential and first-and-last-piece, super seeding, the queue, and the categories and tags
  themselves; with the trackers, web seeds and piece states to read. And the session's: the
  alternative speed limits switched and the limits in force set, the preferences Torrenta has read
  and written (live, as from the Settings page; the save path inside the allowed folders, and
  Remote access's own password, address and port never changed through it), and the event log.
  Held to a real qBittorrent 5.1.0's recorded answers field by field, and tried with Sonarr and
  Radarr through a whole grab, import and removal after seeding: `docs/webapi.md`.

- **Remote access, the door for Sonarr, Radarr and the phone apps.** Settings → Remote access
  speaks qBittorrent's WebUI API at `/api/v2`, off until switched on and on `127.0.0.1:8080` until
  an address is chosen. Sign-in always (no localhost bypass); the password kept only as a salted
  PBKDF2 hash, on this machine, never synced; five wrong tries ban an address for an hour; the
  `Host` header checked against DNS rebinding and a browser's `Origin` against the server itself;
  the session cookie `HttpOnly` and `SameSite=Strict`; HTTPS from a PKCS#12 file, and a warning for
  an address other machines reach over plain HTTP. A port in use is said, with a free one offered.
  It answers sign-in, the `app/*` endpoints (version, preferences, the default save path) and
  the reading ones: `torrents/info` with qBittorrent's states, filters, sort and paging, a torrent's
  `properties` and `files`, the categories, the tags and `transfer/info`. A torrent is listed by its
  v1 hash where it has one (what Sonarr and Radarr look it up by) and `content_path` is the file or
  the root folder they import from. And the acting ones: `torrents/add` (magnets and http(s) links
  in `urls`, `.torrent` files uploaded, with category, tags, stopped, save path, layout, rename,
  limits and share limits) through the same content check as any add without a dialog, delete,
  stop/start (and 4.x's pause/resume), recheck, categories, tags, share limits, top of the queue and
  force start. A save path outside the allowed folders (the download folder, the categories', and
  any added on the page) is refused, as is a new category's folder outside them. Headless runs it too, and
  `--set-remote-password` sets the password without a window.

- **Torrenta without a window.** `torrenta --headless`, or the `torrenta-nox` launcher beside the
  app's own, runs everything the window does but the window: torrents, watch folders, RSS rules,
  the IP filter, share limits, the speed schedule, run-on-event commands. What arrives (the command
  line, another Torrenta, a watch folder, a rule) is added without asking, through the content
  check; the log is stdout and `torrenta-session.log`; SIGTERM or Ctrl+C stops it cleanly, the last
  resume data written first. `docs/headless.md` has a systemd user unit.

- **Shortcuts you can change.** Settings → Keyboard lists every command under its menu with the
  chord it is on: click one and press the new chord, press a chord another command has twice to
  move it, Backspace to leave a command with none, Reset to put it back. Commands with no chord
  can be given one. Applied at once to the menus, the palette, the toolbar's tooltips and the
  view strip, and synced with the other settings (`keys.<command>=Shortcut+Shift+K`, with
  Shortcut meaning Cmd on a Mac and Ctrl elsewhere). The palette's chord moved here from General;
  a Ctrl+Shift+P chosen there is kept.

- **A leak check you can see.** With a binding set, a shield beside the port says Protected,
  Exposed, Halted (the interface is down, so nothing goes out) or Unchecked; a click shows the
  three addresses it decided from. Settings → Connection → "Check where traffic leaves" (off: it
  tells the server you choose this machine's addresses) asks an address-echo server, by default
  https://api.ipify.org, every ten minutes where traffic through the binding leaves and where the
  default route leaves. Exposed means peers see the default route's address while the binding
  leaves elsewhere; a VPN that carries everything is never called a leak. "Pause torrents when
  exposed" pauses what runs while exposed and resumes exactly those once protected again.

- **A VPN that drops is said at once.** With Settings → Connection bound to an interface or an
  address, Torrenta looks at it every five seconds: when it goes down or disappears, the port in
  the status bar turns red ("tun0 missing · reaching no one") and the log says so; nothing goes
  out another way in the meantime. When it comes back, or its address changes, Torrenta binds to
  it again by itself. The port's tooltip names the interface and its addresses, and the address
  peers see this machine as, once libtorrent has settled on one.

- **Commands for many torrents at once, typed.** In the omnibox, `>` and a command with what it
  takes: `>set-category Films`, `>add-tag keep`, `>remove-tag old`, `>set-location /srv/media`,
  `>limit-down 500k` (or `none`), `>limit-up 1m`, `>sequential on`, `>queue top`, `>pause`,
  `>resume`, `>recheck`, `>reannounce`, `>remove`. While a filter is in the box it acts on every
  torrent the filter shows, otherwise on the selection, and the palette's row says which and how
  many before Enter. A removal asks first, and so does anything on more than 20 torrents; a
  category, tags, a location or a limit set this way can be undone.

- **The Add dialog says how the swarm looks before you add.** For a `.torrent` at once, for a
  magnet once its file list is in, a Swarm line under the free space reads Healthy (three seeds and up), Thin (a seed or two, or no
  seed but every piece among the peers), Incomplete (no seed, and pieces of the chosen files that
  no peer has), or Dead (no one at all), with the counts, and follows the files you tick. It looks
  without downloading anything, from the trackers' counts and the peers it meets, and gives no
  time estimate, since there is nothing to measure one from. A private torrent is not looked at,
  since a private tracker counts every announce, unless Settings → Adding torrents → "Look at a
  private torrent's swarm" is on.

- **Undo.** Remove (keeping the files), Set Location, a category and tags can be taken back: the
  status bar offers Undo for ten seconds after each, and Ctrl+Z (Torrent → Undo) takes back the
  last ten for ten minutes. A removed torrent comes back exactly as it was, with its category,
  tags, place in the queue and share limit, and without a recheck. Removing with the files stays
  final.

- **Search providers sync with the settings.** The Torznab servers in Settings → Search travel to
  your other computers, but their API keys never do. A server that arrives says "needed here"
  until you type its key once on that computer; until then a search names it and asks for the key
  instead of being refused by the server. The definition files you added or changed travel too, a
  file at a time (the shipped examples you left alone stay out of it), and so does a deletion.

- **Saved views.** Type a filter in the omnibox (`state:seeding ratio<1 added<30d`, say), press
  Ctrl+S (View → Save Filter as View…) and name it: it joins a Views section at the top of the
  sidebar with a live count, and choosing it filters the list as the query does, together with
  the box and the other sections. Ctrl+Alt+1 to 9 choose the first nine. A view's menu renames it,
  replaces its query with the current filter, or deletes it. Views sync with the settings, each on
  its own; a view whose query no longer reads is struck through, the reason in its tooltip.

- **Four more filter keys**: `seeding>2d` (time spent seeding, in all), `eta<30m` (the time left
  the ETA column shows), `seeds<3` (connected seeds) and `uploaded>10g` (sent over the torrent's
  life). Durations take m, h, d or w; `added` keeps its h, d and w.

- **The Add dialog says when a torrent's files deserve a look.** A card above the file tree names
  what it found: a program named like a video or a document (`Film.mkv.exe`, spaces before the
  last dot included), a name whose real extension is hidden by right-to-left characters, a
  Windows shortcut, a program or a link to a web page in a film or an album, an archive where the
  name promises a film and nothing else is there, a file named for a password or a codec. Red for
  danger, amber for a caution; Skip these files unticks them and keeps the rest. A program in a
  software torrent is not flagged, and a file you skip stops being reported.
- **Adds without a dialog run the same check.** Watch folders, RSS rules, an article's Download
  and Add without asking follow Settings → Adding torrents → Flagged files when adding without a
  dialog: skip those files (the default), refuse the torrent, or add them anyway; the event log
  says which, and what was found. A magnet has no file list yet, so it stops once its metadata is
  in, is checked, and then starts, starts without the flagged files, or is removed before
  anything was downloaded; a restart in between keeps it waiting.
- **An archive that asks for a password is found within seconds of starting.** A torrent's
  first `.rar`, `.zip` or `.7z` gets its first piece before anything else, and if the archive
  is encrypted (its files, or its names too) the Overview shows a red card saying so, with Skip
  that file and Remove the torrent; the event log says it once. A torrent that hides its content
  behind a password usually sells the password.

- **Settings sync carries the category names and tags.** A category or tag added on one computer
  appears on the others, and one deleted is deleted everywhere (torrents filed under it there let
  go of it, as a deletion in Settings does). Names only: each computer keeps its own folder for a
  category, and one that arrives downloads to the default folder until it is given one. The
  first sync after updating only adds, so nothing that exists on just one computer is lost.

### Fixed

- **Every dialog shows its buttons.** A dialog now grows to fit what it holds, when it opens and
  whenever something appears in it, up to the screen's height, and cannot be dragged shorter than
  that. The torrent Limits dialog cut its Cancel and Apply buttons off the bottom of the window.

- **A finished torrent no longer "finishes" again at every start.** libtorrent reports a complete
  torrent as finished when it loads one, so each seed ran your on-finished command and showed its
  notification again every time Torrenta started (and an import would have done it for every
  complete torrent brought over). Only a download that finishes now counts.

- **Binding to an interface now binds the connections Torrenta makes, not only the ones it
  accepts.** With Settings → Connection → Interface set (a VPN's, say), peers Torrenta dialled
  still left by the system's own route: from the LAN address, and through the default route once
  the VPN interface had gone. Now every dialled peer leaves from the bound address or interface,
  and none is dialled when it is missing.
- **The port's state is no longer missed at startup.** The engine heard libtorrent only once the
  session had started, so the answer to opening the listening port (open, or refused because
  another program has it) could come and go unheard, and the status bar kept checking.

## 0.2.0 — 2026-10-24

### Added

- **Links that are not torrents are downloaded by curl.** Paste, type or drop a link to any file
  (http, https, ftp, ftps, sftp) and it joins the list beside the torrents: progress, rate, pause
  and resume (which carries on from where it stopped), remove with or without the file, a category,
  tags, a name of your own, Set Location, a download limit. The file grows under a `.part` name in
  the download folder and takes the server's name for it when it is done, never over a file that
  is there. A few run at once (Settings → Adding torrents → Links at once, 3 by default), the
  rest wait their turn; the list survives a restart. curl comes from the `PATH` unless the same
  page names another, and it is run with the engine's proxy. A link that turns out to be a
  `.torrent` is added as a torrent when it arrives. The clipboard offers a link to a file as it
  does a magnet; a link to a web page is not offered.
- **Download Link, a dialog for a link that is not a torrent**: the folder (Browse, and the free
  space there), the file's name (kept over the one the server gives, unless left as the link's
  own) and Start paused. "Add without asking" skips it; links that arrive together are asked
  about one after the other.
- **Downloads from links are easy to tell from torrents**: a chain before the name in the list,
  a "From links" entry in the sidebar's Status section (and Show: From links in the palette),
  and `is:link` / `is:torrent` in the filter box. Commands only a torrent understands (Recheck,
  Reannounce, Sequential, Super Seeding, the Queue, Copy Info-Hash, Copy Magnet Link, Export
  .torrent) are off for a selection of downloads and skip the downloads in a mixed one.
- **The download limit holds for downloads from links too.** Settings → Speed's limit, and the
  alternative one while it is on or scheduled, is shared by the downloads from links among
  themselves (the torrents keep theirs): each gets an equal part, or its own limit when that is
  lower, and a change takes within a second.

### Changed

- **The sidebar's sections fold with a click, and the sidebar resizes.** A click on Status,
  Categories, Tags or Trackers folds or opens it (Enter and Space on a heading still do); a
  chevron at the right edge says which. Drag the sidebar's edge that faces the list to make it
  wider or narrower (150 to 480 px); a double-click on the edge goes back to the default. Both
  are remembered.
- **Settings pages have glyphs.** Each page in the Settings window's sidebar leads with a glyph;
  a page that holds what a command does shows that command's own (Search, RSS, Speed, Queue,
  Categories and tags), so the menus and the sidebar agree.
- **The detail header's buttons have coloured glyphs.** Pause in amber (Resume, in green, while
  the torrent is paused), Reannounce in indigo, Open folder in the accent, in both themes.

### Fixed

- **`Ctrl+Shift+D` was both Set Location and the details pane**, so only one of them fired. Set
  Location keeps it; View → Details Pane (and the palette's Toggle Details Pane) is now `Ctrl+I`.
  Every chord the window binds is now one table (`ui.Chords`), and a test fails when two actions
  are given the same key.
- **The detail pane showed the previous torrent for up to a second after a click.** It waited
  for the next once-a-second poll; a selection change now fetches the detail at once (about
  20 ms instead of up to a whole tick).

## 0.1.0 — 2026-10-22

### Added

- **A view strip, and keys that name the view.** Transfers, Search and RSS share the window's
  centre; a thin row above it names the three, marks the one showing with a line of the accent,
  shows the key that reaches each and switches on a click. `Ctrl+1`, `Ctrl+2` and `Ctrl+3` go
  straight to one (`Esc`, `Ctrl+Shift+F` and `Ctrl+R` still work), `Ctrl+Tab` and
  `Ctrl+Shift+Tab` cycle through them (`Ctrl` on macOS too, where `Cmd+Tab` is the
  system's), and the View menu lists all three first, with Next View and Previous View; Search and
  RSS Feeds moved there from the File menu. Each view takes the focus as it opens: the list, the
  query, the articles (the feeds while there are none). Returning to Search after a dialog lands
  in the query when there are no results to land in.

- **Torrent → Choose Files…** (the context menu and the palette too) for a torrent added with
  "Add now, pick files when ready": it opens the file tree to choose the files and starts the
  torrent. Until now nothing in the window led back to it.
- **Queue order and Set Location, for both engines.** Torrent → Queue → Move to Top / Up / Down /
  Bottom (`Ctrl+Shift+Home` / `Up` / `Down` / `End`, the context menu and the palette too) move
  the selection in the download queue, the selected torrents keeping their order among themselves;
  a hidden Queue column shows the positions and sorts by them. Torrent → Set Location…
  (`Ctrl+Shift+D`) moves the selection's files to another folder, typed or browsed, with the
  volume's free space under the field: libtorrent moves with `dont_replace` (a file already at
  the destination is kept, never written over) and the log says when the move landed or why it
  did not, the row reads "Moving files" in between, and a torrent still on its way through the
  incomplete folder gets the new destination in its sidecar instead of a move now; a Transmission
  daemon gets `torrent-set-location` with a path on its machine (no Browse, no free space there).
  Two new capabilities, `QUEUE_ORDER` and `MOVE_STORAGE`, both engines have.

- **A Transmission daemon as the engine** (roadmap 0.9): the same window drives a
  `transmission-daemon` on a seedbox or a NAS over its RPC (`TransmissionEngine`: JSON over HTTP(S),
  the session-id handshake, basic auth; one lean `torrent-get` per poll, the detail fields only for
  the selected torrent; added, finished, removed and error events made by comparing polls, since
  the RPC has no push). Settings → Engine, the first page of the Engine group, holds the profiles:
  Local (libtorrent) and any number of remote daemons (name, URL, user, password, Test connection
  for the version and RPC version), kept in `profiles.json` beside the settings, owner-readable,
  never synced; Use at next start (`engine.profile`, this machine's) or Switch now, also File →
  Switch engine profile… and the palette, which restarts the engine in place. The window title
  names a remote profile; a daemon that cannot be reached leaves the window up with the reason,
  Retry and Switch profile…. What a daemon cannot do is hidden, not greyed out and not imitated
  (`Engine.Capabilities`): the torrent creator, the magnet file list before adding, the IP filter
  from ranges (the blocklist URL stays: the daemon fetches it), first-and-last pieces, skip check
  and stop condition on add, upload slots, the proxy, interface binding and IPv6, anonymous mode,
  overhead in limits, the storage choices, the limit on torrents active in all, sequential download
  before RPC 18, and Open folder/Open file/Browse unless the daemon is on this machine. Categories
  and tags live in the daemon's labels (`category:<name>` plus the tags); a Settings change while a
  daemon is in use sends that one key, never this machine's whole file. Tested against
  transmission-daemon 4.1.0-beta.2; a real-daemon test runs where the daemon is installed.
- **Icons in the menus and on the detail tabs.** Every item of the File, Torrent, View and Help
  menus and of the list's right-click menu shows its glyph before its name, the same line glyph
  the toolbar uses for the same command (`Icons.forCommand` is the one rule), at 16 px; the
  detail pane's General, Files, Peers, Trackers and Pieces tabs carry one each, and so do the
  Files tab's and the Search results' right-click menus.
- **Interface languages: English, Deutsch, Español, Français, Italiano and Português**, Termina's
  `i18n` package ported. Settings → General → Language picks one or follows the operating system
  (the default; English when the system's language is not bundled), in force after a restart;
  `ui.language` is a preference, so it syncs. Every text of the window, the menus and the palette,
  the toolbar, the Settings pages (titles, descriptions and the words the search box finds), every
  dialog, the Search and RSS tabs, the detail pane, the status bar and its history, the event log's
  own lines, the tray and the badge reads from the catalogue; what libtorrent says stays as it is.
  A test keeps the six catalogues in step and sweeps the UI sources for English left behind.
- **Torrenta is the program for `.torrent` files and `magnet:` links, and runs once.** One
  instance per settings folder: a second one started with files, magnets or URLs on its command
  line hands them to the running one over a loopback socket (a lock on `instance.lock`, the port
  and a random token in `instance.port` and `instance.token`, owner-readable; a stale pair from a
  crash is taken over, a wrong token is refused), which comes to the front, out of the tray if
  it was hidden there, and opens the Add dialog for each as for a dropped file; the second prints
  nothing and is gone within a second. The packages declare both types: the Linux desktop entry's
  `MimeType` and `%U` (plus jpackage's MIME XML in the deb and rpm), the macOS bundle's
  `CFBundleDocumentTypes` and `CFBundleURLTypes` (both patched into `Info.plist` after jpackage
  and re-signed), the Windows installer's `.torrent`
  file association. On macOS a running Torrenta takes what the desktop opens through AWT's open
  handlers. Settings → General → Operating system → "Register as handler for .torrent files and
  magnet links" (`os.handler`, on by default, this machine's) makes it the default at each start:
  `xdg-mime default` on Linux, the `magnet:` protocol and the `.torrent` default under
  `HKCU\Software\Classes` on Windows (unticking removes them); on macOS the box explains that the
  bundle declares both and Finder chooses. The fifth step of the app-polish milestone.
- **A badge with the download activity** (`os.badge`, on by default, this machine's): the
  download rate on the macOS dock icon (`1.2M`), the count of downloading torrents on a Linux
  launcher that honours Unity's LauncherEntry signal (KDE's task manager, Dash to Dock), pushed
  from the poll only when it changed and cleared when nothing downloads. Windows gets none: AWT
  badges only windows of its own. The tray icon's line carries the rates everywhere.
- **The command palette opens on `Alt+X`**, Editora's `M-x`; Settings → General → Keyboard offers
  the `Ctrl+Shift+P` of the first releases instead, applied at once, and the View menu, the toolbar
  tooltip and the palette's own chip say whichever is in force. In the toolbar the palette now sits
  with the views and Settings alone at the right end, as Editora keeps them.
- **The Statistics window** (`Ctrl+Shift+T`, View → Statistics, the palette), the last step of
  the RSS, creation and statistics milestone: this session's and all-time downloaded, uploaded,
  share ratio and active time, bytes with protocol overhead, wasted bytes, connected and
  connecting peers, DHT nodes, disk reads, writes, buffers and queued jobs, and the torrents by
  state, every one from libtorrent's own `session_stats` counters, refreshed each second while
  the window shows. Under them the session speed graph: download and upload over the last
  minute, ten minutes, hour or day (a chip each; the choice is `stats.window` in the settings
  file and syncs), the rate now and the window's peak in the legend, drawn in the theme's
  colours. The all-time totals are `stats.properties` in the config folder, written every five
  minutes and at quit, this machine's and never synced.
- **A Speed tab in the detail pane**: the selected torrent's download and upload graph, the
  same windows, kept for every torrent that has been selected while it is in the list; nothing
  more is asked of the engine, the rates come with the snapshot the list already gets.
- **The status bar keeps what it said.** A click on the status text, `Ctrl+Shift+M` or View →
  Messages shows this session's status lines newest first, each with its time, warnings and
  errors in colour; a filter box leads the list, Copy takes the selected lines or all of them,
  Esc closes. The line a torrent finishing replaced is no longer gone. Editora's message log.
- **A debug log for bug reports.** View → Debug Log (also Settings → General → Diagnostics)
  shows every warning and error the application logged this session and every uncaught
  exception, with its stack trace, in a window with Copy; the same lines are written to
  `torrenta-session.log` beside the settings, truncated each run, so a crash leaves its story
  behind. A packaged app has no console; this is where its stderr went. Termina's.
- **The Create Torrent dialog** (`Ctrl+N`, File → New Torrent…, the toolbar's page glyph), the
  third step of the RSS, creation and statistics milestone: a file or folder (browsed, typed or
  dropped on the dialog), hybrid, v1-only or v2-only, a piece size from 16 KiB to 32 MiB or
  Automatic (about 1,500 pieces, qBittorrent's rule), private, "Start seeding after creation",
  "Align files to piece boundaries" (always on for v2 and hybrid, libtorrent's canonical layout),
  trackers one per line with a blank line between tiers (pre-filled from the last creation, else
  the Protocol page's default trackers), web seeds, a comment and a private tracker's source tag.
  The header sizes the source on a worker and shows the piece count for the size chosen, live.
  Create… asks where to save (the folder is remembered as `create.lastDir`, this machine's),
  hashes on a worker with a progress bar and a Cancel, writes the file beside its name and moves
  it into place, and shows the v1 and v2 info-hashes and the magnet link with Copy buttons; the
  dialog stays open so they can be copied. Seeding adds the torrent through the usual add path
  with the source's parent as the save path and the hash check skipped, so it seeds at once.
  `Engine.create` is the engine's side: libtorrent lists and lays out the files and writes the
  torrent, the pieces are hashed in Java (v1 pieces and v2 piece layers, bit for bit what
  libtorrent produces, which the tests check) so Cancel stops at the next piece, the `source`
  key is written into the info dictionary by hand (libtorrent has no setter), and the hashes are
  read off the final bytes.
- **RSS auto-download rules**, the second step of the RSS milestone, qBittorrent's shape. A rule
  (Settings → RSS → Rules; Add…, Edit…, Remove, Duplicate) names the articles it wants: words the
  title must and must not contain (every word of a phrase, `|` between alternatives,
  case-insensitive) or regular expressions; an episode filter in qBittorrent's syntax (`1x2` one
  episode, `1x2-5` a range, `1x2-` from episode 2 on, several separated by `;`), matched against
  `S01E02`, `1x02` or `Season 1 Episode 2` in the title, a bad filter or regex said under the form
  and Save held back; a smart episode filter that keeps the rule from taking an episode twice
  (what it took is remembered with the rule, the last thousand); which feeds (none ticked: every
  feed); how old is too old. And what to do with a match: start paused, started, or as the Add
  dialog's default says; a category (its folder is then the save path); a save path of its own.
  The rule dialog lists, as you type, every article the rule matches among those the feeds hold,
  the matching off the FX thread. With Download new articles' torrents automatically on
  (`rss.autoDownload`, synced), every refresh runs the rules over the feed's new articles on the
  fetch thread: the first rule that wants an article takes it, its torrent is added (a magnet as it
  is, a `.torrent` link fetched first), the article is marked downloaded (a glyph before its title)
  and read, and the Event Log says "Rule 'Linux ISOs' added X from feed Y", or why it could not; an
  article is never added twice. Run RSS Rules Now (File menu, the palette, the page's button) goes
  over every unread article not yet downloaded, switch or not. The article context menu gains
  Download (added at once with the defaults, no dialog) and Create rule from title… (the rule
  dialog with the title's words before its episode token filled in). The rules are
  `rss/rules.json` beside the settings, this machine's, never synced.
- **The RSS reader** (`Ctrl+R`, File → RSS Feeds…, the toolbar's feed glyph), the first step of
  the RSS milestone, a third tab beside the transfers and the search: the feeds on the left (All
  feeds first, each with its unread count, a glyph while it refreshes or after it failed), the
  articles of the chosen feed on the right (title in bold while unread, date, feed; a filter box
  and an Unread-only toggle above them), and the chosen article's text, plain, with its link,
  below. A feed is added by URL (`N`, `Ctrl+N`, the Add feed… button, or Settings → RSS), fetched
  once to check it, a blank name taken from the feed's title; it may have its own interval. RSS
  2.0 and Atom are read with the JDK's own parser, entities and DTDs off; an item's torrent is
  its enclosure, a magnet in its link, guid or text, a `<torrent:magnetURI>` or an info-hash
  element; dates in the shapes feeds use, missing ones allowed. Every feed is refreshed on its
  schedule (Settings → RSS → Refresh every, 30 minutes by default, 5 to 1440, synced), on `F5`,
  or on Refresh; an unchanged feed costs one request (`ETag` and `Last-Modified` go back and a
  304 comes). New articles are merged by guid, read flags kept, at most 500 per feed; a failure is
  the feed's status ("last error: the server answered 404"). Choosing an article marks it read,
  `M` toggles it, `Shift+M` marks the feed; `Enter` or a double-click on an article with a torrent
  opens the Add dialog for it ("FROM RSS"), a `.torrent` link fetched first; `Ctrl+C` copies the
  link; `Delete` removes a feed after a confirm; the context menus mirror the keys. The feeds and
  articles are `rss/feeds.json` and `rss/articles/<id>.json` beside the settings, this machine's,
  never synced. The columns are remembered as `rss.columns`. The auto-download switch on the
  Settings page is there and off: the rules come next.
- **The menu bar can share the window's title bar.** Off by default, under Settings → General →
  Appearance, experimental; it applies on restart, since a window's style is fixed once it has
  been shown, and the status bar says so when the setting and the window disagree. With it on, the
  window is drawn without a system title bar and the menus, the window title and the system
  buttons share that one row: a full bar of vertical space back for the list. Minimise, maximise,
  close, drag-to-move, double-click-to-maximise, edge resize and snapping all remain the
  platform's own, so nothing about how the window behaves changes; only where the menus sit. The
  window buttons follow the app theme, not the desktop's, so a dark theme on a light desktop does
  not hide them. Not offered on macOS, where the menu belongs to the system menu bar.
- **A tray icon**, on by default under Settings → General → System tray: the application's own
  arrow-in-a-tray mark, teal while something downloads, red while a torrent has an error, muted
  otherwise, with the rates and a count of what the torrents are doing as its tooltip and as the
  first line of its menu (Show Torrenta, Quit). Closing the window hides it behind the icon and
  the torrents go on; Quit in the File menu or the tray menu ends the program. Both are switches,
  and the second only applies once an icon has actually appeared: a window hidden with no icon to
  bring it back is an unreachable program, so without a tray closing quits and the status bar says
  why. Linux speaks StatusNotifierItem over D-Bus (KDE, GNOME with the AppIndicator extension,
  X11 and Wayland alike); Windows and macOS use the system's own. As in Nux and Modula.
- **Search providers from definition files**, the last step of the search milestone. A site
  without an API is `search-providers/<id>.json` in the config folder: where to search (a URL
  template with `{query}` and `{category}`, the category mapped through the file's own table;
  optional paging with `{page}`, a page count and a delay; headers) and how to read the answer
  (`json`: a JSON-pointer path to the rows and one per field; `html`: a CSS-like selector for the
  rows and one per field, with `attr` for an attribute, `regex` for a capture and `template` to
  build a URL from other fields). Sizes ("1.4 GiB", "700 MB", "1,234,567") and dates (ISO,
  RFC 1123, "Sep 13, 2026", "2 days ago", or a pattern of the file's own) are parsed; a missing
  field is a dash, a row without a name or a link is skipped, a page with no rows is an empty
  answer. One generic provider interprets every file; the HTML is read by a lenient tokeniser and
  a small selector engine of our own (tag, `.class`, `#id`, `[attr=value]`, descendant and child
  combinators, `:nth-of-type(n)`, `:contains`), no new dependency, documented for what it is. A
  file that does not load is said once in the Event Log with the file and the key at fault, and
  unknown keys are errors so a typo cannot pass. Settings → Search lists the files with their
  type and status, switches each off (`search.disabled`, which syncs), reloads the folder and
  opens it; the folder is also re-read when the Search tab opens. Every loaded file is a provider
  chip in the tab beside the Torznab servers. Three examples ship and are copied in on the first
  run, never written over (a `.example` beside yours if you changed one): the Internet Archive's
  advanced search (JSON), LinuxTracker's listing (HTML) and Academic Torrents' feed (RSS read with
  the same parser, filtered locally, since the site asks not to be scraped). The format in full is
  `docs/search-providers.md`. This completes 0.6.
- **The Search tab** (`Ctrl+K`, File → Search…, the toolbar's search glyph), the second step of
  the search milestone. It takes the place of the transfers, which come back as they were with
  `Esc`: a query field, a row of category chips (All by default), a row of provider chips (every
  enabled provider, on unless switched off; a link to Settings → Search while there is none), a
  status line with one clause per provider ("Prowlarr: 42 results · Jackett: could not connect")
  and the results table: Name, Size, Seeds, Leechers, Date, Provider, sorted by seeds, the ranked
  order when no column is. `Enter` asks every chosen provider at once on a worker and each answer
  lands as it arrives, the rows reconciled in place so the selection survives; a new search drops
  the running one. `Enter` or a double-click on a result opens the Add dialog for it ("FROM
  SEARCH"): a magnet as it is, a `.torrent` link fetched first with the status bar saying so;
  `Ctrl+C` copies the link; the context menu adds, copies and opens the detail page. The whole tab
  is driven from the keyboard: `Tab` between the query, the chips and the table, `Space` on a
  chip, `Down` from the query into the results, `Esc` in the results back to the query. The
  columns are remembered as `search.columns`. The service now reports each provider as it answers
  (`SearchService.Progress`), in arrival order, on the searching thread.
- **Search providers** (Settings → Search), the first of the search milestone. A Torznab server,
  Prowlarr (`http://host:9696/1/api`) or Jackett
  (`http://host:9117/api/v2.0/indexers/all/results/torznab/api`, or one indexer's), is added by
  URL and API key (blank for a Jackett without one), enabled or not; Test asks it who it is and
  how many categories it knows, in the dialog and on the page, and fills a blank name in with the
  server's title. Behind it, the search package the next step's tab will drive: a provider
  interface, the Torznab client (`t=caps`, `t=search` with the Newznab category numbers, RSS with
  the `torznab:attr` seeders, peers, size, info-hash and magnet read with the JDK's own parser,
  the server's `<error>` and a 429 said in words), and a service that asks every enabled provider
  at once on its own thread with a 20 s limit each, merges the answers, keeps the same torrent
  once (by info-hash, else by link; the better-seeded copy wins and borrows what it lacked), ranks
  them by seeds, then by how many of the query's words the name has, then by date, asks a
  provider at most once every 2 s and remembers an answer for the session. The list, keys
  included, is `search-providers.json` beside the settings: this machine's, never synced.
- **Run on event and desktop notifications** (Settings → Automation). One optional command line
  per event: torrent added (not the ones restored at start), download finished, seeding done (the
  share limit paused or removed it). Placeholders are qBittorrent's, spelled the same: `%N` name,
  `%L` category, `%G` tags (comma separated), `%F` content path (the root folder or the one file),
  `%R` root path (empty without a root folder), `%D` save path, `%C` file count, `%Z` size in
  bytes, `%T` current tracker, `%I` info-hash v1, `%J` info-hash v2, `%K` torrent id, `%%` a
  percent. The line is split as a shell would (quotes keep a value together) and each argument is
  substituted on its own, so a name with spaces stays one argument; no shell runs it. Output (the
  first three lines) and the exit code go to the Event Log; a program still running after ten
  minutes is killed and the log says so. Desktop notifications per event, "`<name>` finished" and
  the like, with "error" for a torrent, file or tracker error; only finished is on by default.
  They go through the platform's own facility, `notify-send` on Linux, `osascript` on macOS and a
  PowerShell toast (`Windows.UI.Notifications`) on Windows, each a short program given the text
  as arguments; a machine without one is noted once in the log, and the page has a Test
  notification button. Keys `automation.onAdded`, `automation.onFinished`,
  `automation.onSeedingDone` (this machine's, never synced: they name its programs) and
  `notify.added`, `notify.finished`, `notify.seedingDone`, `notify.error`, which sync.
- **The Add dialog, relaid out, and a magnet that never blocks it.** Two columns: the file tree
  (a select-all box, sizes, priorities with High in the accent and Skip muted; `Space` ticks,
  `+` and `−` step Skip/Low/Normal/High, `←` `→` fold) and, on the right, the folder with a disk
  bar and "412 GiB free on /data · 5.9 GiB leaves 406 GiB", Category and Stop when, Tags, and the
  options (sequential, first and last pieces first, skip hash check). A header says where it came
  from, the name, and size · files · v1/v2/hybrid · trackers · the comment. `Enter` adds,
  `Shift+Enter` adds paused (the other way round when Start paused is the default), `Esc`
  cancels, each key shown beside its button. A magnet's file list is fetched while the dialog is
  open and fills the tree in live ("Fetching file list · 3 peers · 12s"); until then everything
  else can be set, and **Add now, pick files when ready** (`Enter`) adds it to stop when its
  metadata arrives, or **Wait here for the file list** (`Ctrl+Enter`). "Next time, add with these
  settings without asking" (Settings → Adding torrents → Add without asking, `add.withoutAsking`)
  adds every input with the defaults and opens no dialog.
- **Watch folders** (Settings → Watch folders). A `.torrent` dropped into a watched folder is
  added without a dialog, once it has stopped growing; a `.magnet` file holds one magnet link per
  line, as qBittorrent reads them. Each entry says what becomes of the file (deleted, or renamed
  `.added`), where the torrent saves (the download folder, the watch folder itself, or another
  folder; the category's folder when the category has one), its category, and whether it starts
  paused. A file that is not a torrent is renamed `.invalid` once and reported once; a torrent
  already in the session is reported and the file is still handled; a folder that does not exist
  is noted on the page's status line. Folders are watched through the file system and polled
  every minute as the safety net (network mounts, macOS). The list is `watch-folders.json` beside
  the settings, this machine's, never synced. One event-log line per add: "Added X from watch
  folder Y".
- **Incomplete folder, `.part` suffix and pre-allocation** (Settings → General → Downloads, all
  off by default). With the incomplete folder on, a new torrent downloads there and is moved to
  its save path (its category's, when the category has one) the moment it completes; a file already
  at the destination is never written over. The Add dialog's line under the save path says where
  the files start and the free space of both folders. "Append .part to incomplete files" gives each
  file the suffix after the check (a file that was already complete keeps its name) and takes it
  off as the file completes; "Pre-allocate disk space" writes a file at its full size from its
  first piece (libtorrent 2.x allocates a file when it is first written to, not when the torrent is
  added). What is still owed to a torrent's files is `torrents/<info-hash>.storage` beside its
  resume data, so a restart mid-download knows where to move and which names to take back. Keys
  `download.incompleteEnabled`, `download.incompleteDir` (this machine's, never synced),
  `download.partSuffix`, `download.preallocate`.
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

### Changed

- **The sidebar and the details pane go where you want them.** The sidebar sits on the left or
  the right of the list, or is hidden; the details pane sits under the list, beside it on the
  right (running the full height, General in one column), or is collapsed. View has "Sidebar on
  the Right", "Details Pane" (`Ctrl+Shift+D`) and "Details Pane on the Right", the palette has
  the same, and Settings → General → Appearance has both as choices. Hiding either keeps its
  place, and each place of the details pane keeps its own size.

- **The filter box is an omnibox** (`Ctrl+K`, `Ctrl+F` still works): a `key:value` term becomes a
  chip the moment the space after it is typed, one the box cannot read becomes a red chip saying
  why, `Backspace` on an empty box hands the last chip back for editing and a click removes one;
  `>` then a name runs a command through the palette; a pasted magnet, `.torrent` link or path
  opens the Add dialog; `Enter` selects every match and returns to the list, so the next key acts
  on all of them. `Esc` peels one layer at a time: the box, then the list's filter, then its
  selection. The Search tab moved from `Ctrl+K` to `Ctrl+Shift+F`.

- **`Ctrl+N` is New Torrent in the RSS tab too**; `N` still adds a feed there.
- **Reset to defaults asks first**, puts the theme back as well, and re-applies what the reset
  keys drive (the engine's connection, speed and queue settings, the IP filter, and the tray,
  file handlers and badge where those switches changed) instead of leaving them as they were
  until the next restart.

- **A magnet added from the Add dialog starts with the peers its file-list fetch found**, both
  with "Add now, pick files when ready" and after the files arrived, instead of looking for the
  swarm again from nothing.

- **Alternative speed limits have a command**: View → Alternative Speed Limits (`Ctrl+Shift+J`),
  checked while they are in force, and in the palette. Quit is in the palette too.
- **Settings search opens the page that answers it best**: a row whose title matches beats one
  whose description does, which beats a keyword, so "proxy" opens Connection rather than Sync.
  A section's heading counts as a keyword of its rows ("proxy" lists the whole Proxy section).
  `Esc` in a search clears it before it closes the window, and the window opens again unfiltered.
- **Every removal asks first.** Feeds, rules, watched folders, search providers, engine profiles,
  categories and tags in Settings are removed after the same question the torrent list and the
  RSS tab ask; the categories' and tags' buttons say Remove, like the rest.
- **A search asks the providers again after five minutes.** An answer was reused for as long as
  the window stayed open, so running the same search later never found anything new.

- **JavaFX 27**, as Editora; was 26.0.2.

### Fixed

- **The rest of the interface speaks the chosen language.** Engine errors and events (the status
  bar, the Add dialog, the Event Log, the notifications), search and feed failures, a search
  definition's problem in Settings → Search, the RSS rule dialog's problem line, the rule and
  watch-folder log lines, the tray and badge reasons, the Trackers tab's status column and the
  run-on-event lines were English whatever the language. Every confirmation and prompt has a
  translated title and OK/Cancel (JavaFX's stock buttons followed the system locale instead), and
  key names in hints come from the catalogue or the platform (`⌘V` on macOS, `Leertaste` and
  `Entf` in German menus) rather than a fixed "Ctrl+V" or "Space". The UI string sweep now also
  fails on English handed to an engine exception or event, or returned from a method whose result
  the window shows.
- **A long status message no longer pushes the rest of the status bar off the window.** A
  tracker warning used to elide "Alt speed" and the rates as well and push free space off the
  right edge; now the message alone shortens, with an ellipsis, and the port, DHT, rates and free
  space keep their width.
- **The same message is one line with a count.** libtorrent reports a tracker's error once per
  listen interface, so one unreachable tracker logged the same warning about seven times per
  torrent; the event log and the status bar's history now keep one line ("… ×7") for the same
  level, torrent and words within ten seconds of the last time.
- **"IP filter cleared" no longer appears (twice) at every start.** The start applied the stored
  filter and then refreshed, which applied it again; a refresh now runs only when a download is
  due, and the engine says nothing when clearing a filter that was never set.
- **The window remembers its size, its place and whether it was maximised**, on this machine
  only (never synced), and comes back on a screen that is still there, made to fit it. The first
  window is wide enough for the default columns, with no horizontal scrollbar.
- **The Statistics window's sections are spaced and aligned**: a gap between sections, the values
  of a column starting at one place in every section, and the window as tall as its graph needs
  instead of cutting it at the baseline.
- **A torrent's Speed tab is no longer empty the first time it is chosen**: every torrent keeps
  its last two minutes of rates, and the tab starts from them.
- **Ban Peer was unreachable**: the Peers tab has a context menu, Ban Peer and Copy Address
  (Ban Peer only for an engine that takes our IP filter), as Settings → IP filter promised. It
  bans the host alone: libtorrent's addresses read `tcp://host:port`, and the scheme went into the
  ban, which the filter then skipped. Transmission's IPv6 peers are shown in brackets.
- **The Add dialog's "Wait here for the file list" led nowhere once the fetch had failed**: it hid
  Add now and left Add disabled for good. Wait is offered only while the file list can still come.
- **"Add now, pick files when ready" downloaded every file on Transmission**, which has no stop
  condition. A daemon gets plain Add and Add paused for a magnet instead.
- **Spinners dropped a number typed before Tab or a click elsewhere**: the RSS refresh interval,
  the sync interval, a feed's own interval and a rule's "ignore for" days now keep it, as the
  other spinners do; text that is not a number puts the old value back instead of throwing.
- **Settings → Connection → Proxy port** had no description, no keywords, and showed for an engine
  without a proxy.

- **Every style after the debug log's in the stylesheet was ignored** (the RSS rule dialog's among
  them, with a CSS warning at every start): a merge had dropped a closing brace.
- **The detail pane opening collapsed to its tab strip.** The empty "No torrents yet" state,
  which stands in for the list at every launch until the engine's first poll, could push the
  divider down while the window was being laid out, and that position was saved. The divider is
  no longer remembered while the empty state shows, the saved one is put back when the list
  appears, and a saved position that leaves the pane its tabs and nothing more is ignored.
- **Add Trackers took several URLs as one**: the prompt split on a literal `\s` rather than on
  whitespace, so two URLs on two lines reached the engine as a single announce URL.
- **The menus now show `Space`, `Delete`, `Shift+Delete`, `Ctrl+V`, `Ctrl+K`, `Esc`, `Ctrl+A`,
  `Ctrl+Shift+L`, `Ctrl+Shift+T` and `Ctrl+Shift+M`** beside their items; the keys stay where they
  were (Space and Delete on the list only, so a space typed into the filter does not pause).
- **`Ctrl+Shift+G` opens Set Category**, as the palette always said it did.
- **A poll racing a profile switch** could read the engine field after the switch had cleared
  it; the poll now works on its own copy and skips a tick with no engine.
- **A crash, any time after a file of 40 pieces or more was checked, downloaded or seeded** (exit
  139, no error file, "Segmentation fault" from the shell, usually at a garbage collection):
  libtorrent's memory-mapped disk I/O installs a SIGSEGV/SIGBUS handler of its own in place of
  the JVM's once it maps a file, and the JVM's next safepoint poll died under it. The engine now
  sets libtorrent's mmap cutoff above every file, so the same disk I/O reads with `pread` and
  installs nothing. Found while making the torrent creator, whose hashing met the same handler;
  a test seeds a 2 MiB file while a thread drives the collector.
- **A crash at quit with a torrent in the list** (exit 139, no error file, every few runs): the
  window's poll could still be reading the torrents while the engine deleted the native session
  under it. Every engine call now finishes before the session goes, and a later one is refused.

- **A JVM crash in the engine** (`SIGSEGV` in `libtorrent4j.so` while reading a torrent's detail,
  seen once on CI): libtorrent4j's vector proxies hand out pointers that its finalizer frees. The
  engine now keeps only handles, trackers and peers it owns.

