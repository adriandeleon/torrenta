# Changelog

Notable changes, newest first. Versions follow [semantic versioning](https://semver.org); until
1.0.0 the minor number moves for anything user-visible.

## Unreleased

### Changed

- **The filter box is an omnibox** (`Ctrl+K`, `Ctrl+F` still works): a `key:value` term becomes a
  chip the moment the space after it is typed, one the box cannot read becomes a red chip saying
  why, `Backspace` on an empty box hands the last chip back for editing and a click removes one;
  `>` then a name runs a command through the palette; a pasted magnet, `.torrent` link or path
  opens the Add dialog; `Enter` selects every match and returns to the list, so the next key acts
  on all of them. `Esc` peels one layer at a time: the box, then the list's filter, then its
  selection. The Search tab moved from `Ctrl+K` to `Ctrl+Shift+F`.

- **A magnet added from the Add dialog starts with the peers its file-list fetch found**, both
  with "Add now, pick files when ready" and after the files arrived, instead of looking for the
  swarm again from nothing.

### Fixed

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

### Added

- **Icons in the menus and on the detail tabs.** Every item of the File, Torrent, View and Help
  menus and of the list's right-click menu shows its glyph before its name, the same line glyph
  the toolbar uses for the same command (`Icons.forCommand` is the one rule), at 16 px; the
  detail pane's General, Files, Peers, Trackers and Pieces tabs carry one each, and so do the
  Files tab's and the Search results' right-click menus.
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
