<p align="center">
  <img src="branding/torrenta.png" alt="" width="128" height="128">
</p>

<h1 align="center">Torrenta</h1>

<p align="center">
  <a href="https://github.com/adriandeleon/torrenta/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/adriandeleon/torrenta/actions/workflows/ci.yml/badge.svg"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/github/license/adriandeleon/torrenta"></a>
  <img alt="Java" src="https://img.shields.io/badge/Java-25-orange">
  <img alt="JavaFX" src="https://img.shields.io/badge/JavaFX-27-blue">
  <img alt="Platforms" src="https://img.shields.io/badge/platforms-macOS%20%C2%B7%20Windows%20%C2%B7%20Linux-lightgrey">
</p>

A keyboard-first, cross-platform BitTorrent client built on JavaFX.

Status: **early**. A themed window with a menu bar, an icon toolbar, a torrent list, a status bar that keeps its history, an event log, a debug log,
on a real engine: libtorrent, through [libtorrent4j](https://github.com/aldenml/libtorrent4j).
Torrents arrive from a file, a magnet link, a URL, a drop or the clipboard through an Add dialog
(file selection and priorities, save path with what the torrent leaves free, category, tags, stop
condition, sequential, first and last pieces, skip check; a magnet's file list fills in while it is
open, or it is added at once to choose its files later),
are listed with live columns, can be paused, resumed, rechecked, reannounced and removed, show
their files, peers, trackers and pieces in a detail pane, and come back after a restart. No settings
window or limits yet; see [docs/ROADMAP.md](docs/ROADMAP.md).

## Running

```bash
./mvnw javafx:run
```

Requires JDK 25 (the build refuses another and says so). Everything else — including Maven itself,
via the wrapper — is fetched on demand.

```bash
./mvnw verify
```

runs the tests, the coverage floor and the formatter check. `./mvnw spotless:apply` formats.

```bash
./mvnw javafx:run
scripts/dev-run.sh -- "magnet:?xt=urn:btih:…"    # with arguments for the app
```

Builds need a JDK 25: the formatter (Palantir 2.92) does not run on JDK 27.

One Torrenta runs per settings folder. A second one started with a `.torrent` file, a magnet link
or a URL on its command line hands them to the running one, which comes to the front (out of the
tray if it was hidden there) and opens the Add dialog for each as it would for a dropped file;
the second prints nothing and is gone within a second. Started with nothing, it just brings the
window to the front. That is how a double-clicked `.torrent` and a browser's magnet link arrive:
the packages register both types (the Linux desktop entry's `MimeType`, the macOS bundle's
document and URL types, the Windows installer's file association), and Settings → General →
Operating system → "Register as handler for .torrent files and magnet links" (on by default,
`os.handler`, this machine's) makes Torrenta the default at each start: `xdg-mime default` on
Linux, the `magnet:` protocol and the `.torrent` default under `HKCU\Software\Classes` on
Windows (unticking removes them), and on macOS the bundle's declaration is all there is and
Finder's Get Info → Open with chooses. Below it, "Show download activity on the dock icon"
(`os.badge`) puts the download rate on the macOS dock badge and the count of downloading torrents
on a Linux launcher that honours Unity's badge (KDE's task manager, GNOME's Dash to Dock), cleared
when nothing downloads; Windows has no badge for a JavaFX window. The tray icon's tooltip and menu
carry the rates on every platform.

A folder can be watched for `.torrent` and `.magnet` files (Settings → Watch folders): what lands
there is added without a dialog, then deleted or renamed `.added`. A program can run when a
torrent is added, finishes or is done seeding (Settings → Automation, qBittorrent's `%N` `%F`
`%D` … placeholders, no shell), and a desktop notification can be shown for each of those and for
errors, through `notify-send`, `osascript` or a PowerShell toast.

A torrent of your own is made with `Ctrl+N` (File → New Torrent…, the toolbar's page glyph):
drop a file or folder on the dialog or browse to it, choose hybrid (the default), v1 or v2, a
piece size (Automatic aims at about 1,500 pieces; the header shows the count for the size chosen,
live), private, the trackers one per line with a blank line between tiers (pre-filled from the
last creation, else the Protocol page's default trackers), web seeds, a comment and a source tag,
and press Create…: it asks where to save the `.torrent` (the folder is remembered), hashes on a
worker with a progress bar and a Cancel, writes the file whole, and shows the v1 and v2
info-hashes and the magnet link with Copy buttons. "Start seeding after creation" adds it to the
list at once, trusted as complete, seeded from the folder the source is in.

Search (`Ctrl+Shift+F`) goes through a Torznab server of yours, Prowlarr or Jackett (Settings → Search):
add it by URL and API key, press Test, and the Search tab asks every enabled one at once, each
answer landing in the results as it arrives with a line per provider saying how many it found or
why it could not answer; the same torrent from two servers is shown once, the better-seeded copy
first, sorted by seeds unless a column is clicked. `Enter` on a result opens the Add dialog for it,
a `.torrent` link fetched first. The results table's columns are remembered like the transfer
list's.

RSS (`Ctrl+R`) is a third tab: feeds on the left (All feeds first, each with its unread count and
a glyph while it refreshes or after it failed), the articles of the chosen feed on the right (bold
while unread; a filter box and an Unread-only toggle above them), and the chosen article's text
and link below. A feed is added by URL (`N`), fetched once to check it and named after itself,
refreshed every half hour by default (Settings → RSS; a feed may have its own interval), and only
re-read when it changed (`ETag` and `Last-Modified` are sent back). Choosing an article marks it
read; `M` and `Shift+M` mark one or the whole feed by hand; `Enter` on an article that carries a
torrent (an enclosure, a magnet in its link or text, a `<torrent:magnetURI>`) opens the Add dialog
for it, and the context menu's Download adds it at once with the defaults. Rules (Settings → RSS →
Rules) add new articles' torrents by themselves once the switch above them is on: a rule names
what the title must and must not contain (words that must all appear, `|` between alternatives,
or regular expressions), an episode filter in qBittorrent's syntax (`1x2` one episode, `1x2-5` a
range, `1x2-` from episode 2 on, several separated by `;`, read off `S01E02`, `1x02` or `Season 1
Episode 2` in the title), a smart episode filter that keeps the rule from taking an episode twice,
which feeds, how old is too old, and what to do with a match (start paused or not, a category, a
save path). The first rule that wants an article takes it, once, said in the Event Log; the rule
dialog lists what the rule matches among the articles as you type; Create rule from title… in the
article's context menu starts one from the title's words; Run RSS Rules Now (File menu, palette)
goes over everything unread and not yet downloaded.

**Search providers without an API** are definition files: `search-providers/<id>.json` in the config
folder, one per site, interpreted by one generic provider, so the app ships no site-specific code
and a site change is a file edit. A file names the site (`id`, `name`, `homepage`), its `type`
(`json` or `html`), a `search.url` template with `{query}` and `{category}` (the latter mapped
through a `categories` table from Torrenta's category names to the site's values), optional paging
(`search.page` with `{page}`, `pages`, `delayMs`), `headers`, and `fields`: a `rows` path or
selector and, per field (`name`, `size`, `seeds`, `leechers`, `date`, `magnet`, `torrentUrl`,
`infoHash`, `detailUrl`), a JSON-pointer path or a CSS-like selector, with `attr`, `regex`,
`template` and a date `format` where the text needs them. Sizes ("1.4 GiB", "700 MB", "1,234,567")
and dates (ISO, RFC 1123, "Sep 13, 2026", "2 days ago") are parsed; what is missing is a dash, never
a failure. The HTML side is a small selector engine and a lenient parser of our own, enough for a
listing table. Three examples ship and are copied in once (Internet Archive, LinuxTracker, Academic
Torrents; never written over, a `.example` beside yours if you changed one); Settings → Search
lists the files, says which loaded and why not, switches each off (`search.disabled`, synced),
reloads the folder and opens it. The format in full: [docs/search-providers.md](docs/search-providers.md).

### Building a native app image

```bash
./mvnw clean -Pdist -DskipTests package                          # => target/dist/Torrenta{.app}
./mvnw clean -Pdist -DskipTests -Dskip.installers=false package  # + target/installers/ (.deb .rpm | .dmg | .msi)
```

Self-contained: bundled runtime, no JDK needed to run it. `clean` is not optional — jlink's input is
built from `target/classes`, and an incremental compile can leave that inconsistent in ways javac
will not correct on its own. jlink and jpackage are host-specific, so each platform builds its own.

The engine's native library is not linked into the image. It is unpacked from the per-OS jar at
build time and placed beside the image (`lib/torrenta-native/` on Linux, `Contents/torrenta-native/`
on macOS, `torrenta-native\` on Windows), and the launcher hands libtorrent4j its path, so an
installed Torrenta never writes under `~/.torrenta/native`. CI builds the image on all three
platforms, starts it, and fails unless the engine came up inside it.

### Development capture

```bash
scripts/dev-run.sh -Dtorrenta.capture=/tmp/torrenta.png -- "magnet:?xt=urn:btih:…"
TORRENTA_CAPTURE=/tmp/torrenta.png ./mvnw javafx:run
```

Renders the real window, writes it to a PNG, prints a few `capture:` facts (size, title, focus owner,
engine, status bar) and exits. CI runs the first form under Xvfb on Linux as a smoke test.
`-Dtorrenta.captureAction=search:debian,search-keys:DOWN+ENTER` drives the Search tab; a stand-in
Torznab server for it is `java scripts/torznab-stub.java 9696 <search.xml> [caps.xml]` with the
fixtures under `src/test/resources/com/torrenta/search`, and a provider at
`http://127.0.0.1:9696/api` in the config folder's `search-providers.json`. For a definition file,
`java scripts/search-stub.java 9797 src/test/resources/com/torrenta/search/declarative` serves the
recorded pages, so a file whose `search.url` is `http://127.0.0.1:9797/linuxtracker.html?q={query}`
searches without asking the site; `settings-page:Search` and `settings:definition` photograph the
page that lists the files, `settings-definitions-reload` presses Reload, and the capture prints a
`definition=` line per file. For the RSS tab, `java scripts/feed-stub.java 9898
src/test/resources/com/torrenta/rss/rss2.xml` serves the fixture with an `ETag`;
`rss:http://127.0.0.1:9898/feed.xml` adds it as a feed, `rss-keys:DOWN+ENTER` walks the articles
once it is fetched, `rss-dialog[:<url>]` and `settings-rss-add[:<url>]` open the Add Feed dialog
from the tab and from Settings → RSS, `settings-page:RSS` photographs the page, and the capture
prints `rss-feed=`, `rss-row=`, `rss-status=` and `rss-detail=` lines. For the rules,
`settings-rss-rule-add[:<must contain>]` opens the rule dialog from Settings → RSS,
`rss-rule-from-title` presses Create rule from title… on the first article and `rss-download`
presses Download on it (both once the feeds are fetched), `settings:rules` photographs the Rules
section, and the capture prints `rss-rule=` lines for the table and, after the shot, the open
dialog's `rule-matches=` and `rule-problem=` lines; a `rules.json` under `rss/` in the config
folder, with `rss.autoDownload=true` in the settings, makes the rules run on the first fetch.
`stats` photographs the Statistics window and prints its rows and its graph's state after the
shot (`stats=`, `stats-graph=`), `detail:speed` shows the Speed tab and prints `speed-tab=`; for a
line that moves, `java scripts/peer-stub.java <file.torrent> <save dir> 6881 1048576 127.0.0.2`
(with `target/deps` and the unpacked native on the path, as its header says) downloads from the
running app at 1 MiB/s, so a seeding torrent in the config folder shows an upload.

## Files

Everything lives in one folder, `~/.torrenta` (`--config-dir DIR` or `TORRENTA_CONFIG_DIR` to move
it): `settings.properties`, hand-editable; `stats.properties`, the all-time totals (bytes down and
up, seconds active) the Statistics window adds this session to, written every five minutes and
at quit, this machine's and never synced; `torrents/<info-hash>.fastresume`, one per torrent,
libtorrent's resume data with the info dictionary inside, so deleting one file forgets one torrent;
`native/<version>/`, the unpacked engine. While Torrenta runs, `instance.lock` is held, and
`instance.port` and `instance.token` (readable by you alone where the file system has modes) say
where a second Torrenta hands over its command line; both are deleted at quit, and a stale pair
left by a crash is taken over without a word. Downloads go to `~/Downloads` until changed in
Settings (`Ctrl+,`), which writes the same file. Three more choices sit under the download folder
there, all off by default: an **incomplete folder** (downloads start there and are moved to their
save path, or their category's, when complete; a file already at the destination is never written
over; `download.incompleteDir` is this machine's and does not sync), **append `.part` to incomplete
files** (each file carries the suffix until it is complete, Transmission's `.part`, qBittorrent's
`.!qB`), and **pre-allocate disk space** (a file is written at its full size from its first piece).
What the engine still owes a torrent's files, the move and the names to take back, is
`torrents/<info-hash>.storage` beside its resume data, so a restart mid-download knows both; the
Add dialog's line under the save path says where the files start and how much room there is. Categories (name and save path) are
`categories.json` and tags are `tags.json`, both small enough to edit by hand; which torrent is
filed under which is `torrents/<info-hash>.labels` beside its resume data, written the moment it
changes and deleted with the torrent. Watch folders are `watch-folders.json` (folder, what to do
with the file, save path, category, start paused), this machine's and never synced; a file that is
not a torrent is renamed `.invalid` and left alone. Search providers are `search-providers.json`
(name, URL, API key, enabled, the category count the last Test found), this machine's too and
never synced, since the key is a secret. Definition-file providers are `search-providers/<id>.json`,
one per site, read only (the shipped examples are copied in once and never written over); which are
switched off is `search.disabled` in the settings file and syncs. The RSS reader keeps its feeds in
`rss/feeds.json` (id, URL, name, its own interval if any) and each feed's articles, read or not,
with the last answer's `ETag`, `Last-Modified`, time and error, in `rss/articles/<id>.json`, at most
500 per feed, the oldest dropped, and its auto-download rules, with what each remembers (the last
match, the episodes taken under the smart filter), in `rss/rules.json`: machine data, this
machine's, never synced; the refresh interval, `rss.refreshMinutes`, and the auto-download switch,
`rss.autoDownload`, are in the settings file and do. The commands run on an event are
`automation.onAdded`, `automation.onFinished` and `automation.onSeedingDone` in the settings file,
this machine's too (they name its programs); the notification switches are `notify.added`,
`notify.finished`, `notify.seedingDone` and `notify.error` and do sync. A torrent's own share limit (Torrent → Limits…)
is kept there as `torrent.<info-hash>.share` and goes with the torrent when it is removed; its rate
caps and connection counts are libtorrent's and live in its resume data. The global queue and share
limits are `queue.*` and `share.*`. The theme is there too, under General →
Appearance: Editora Light and Dark (Caret & Ink, as Editora and Termina), Nord and Cupertino,
light and dark, applied the moment one is picked (`ui.theme` in the file). Settings → General →
Language picks the interface language: System (the operating system's, English when that is not
one of them), English, Deutsch, Español, Français, Italiano or Português, in force after a restart
(`ui.language`, a preference, so it syncs). Beside it, **Merge the
menu bar into the title bar** (`ui.extendedWindow`, off by default, experimental) draws the window
without a system title bar and puts the menus, the title and the window buttons on that one row,
a row of space back for the list; the window keeps the platform's own buttons, drag, double-click
and edge resize. It applies on restart, and is not offered on macOS, where the menu belongs to the
system bar. The capture prints the style in force as `capture: style=`.

**Settings sync.** Settings → Sync connects the settings file to a Git repository of yours (a
private one on GitHub, say: "Create on GitHub" makes it through the `gh` CLI). Every computer that
connects to it gets the same settings, merged one key at a time; a key changed on both sides keeps
this machine's value and says so. It syncs after startup, half a minute after a change, and every
few minutes while the window is in front, without ever asking for a password: git signs in the way
it does for you, and with two `gh` accounts the one that can read the repository is used. Folders,
the network interface, the clocks of the last checks and the proxy password stay on each machine.

After startup, at most once a day, Torrenta asks GitHub whether there is a newer release and says
so in the status bar and the Help menu; nothing about you or the machine is sent beyond the request
itself. Help → Check for Updates asks now; Skip Version keeps that one quiet; `updates.check=false`
in the settings file turns the automatic check off.

### Releasing

A release is a tag. Set `<version>` in `pom.xml` to the release number (no `-SNAPSHOT`), commit,
tag `v<version>`, push the tag. `release.yml` builds the installers on all three platforms, smoke
tests each packaged app, and JReleaser publishes the GitHub release with install notes and a
changelog, then updates the Homebrew cask and opens the winget pull request when the
`PACKAGES_TOKEN` secret is present. A manual run of the workflow is a dry run: it builds and uploads
everything and publishes nothing. Afterwards, reopen `master` at the next `-SNAPSHOT`.

## Keyboard

| Chord | Action |
|---|---|
| `Ctrl+N` | New torrent: the Create Torrent dialog (a file or folder, hybrid/v1/v2, piece size, private, trackers by tier, web seeds, comment, source; Save as…, then hashed on a worker with a Cancel; the info-hashes and magnet link with Copy buttons once made; seeds at once when asked). In the RSS tab the chord adds a feed instead |
| `Ctrl+O` (`Cmd+O` on macOS) | Add `.torrent` files, through the Add dialog |
| `Ctrl+M` | Add a magnet link or a link to a `.torrent` (the clipboard's, if it holds one) |
| `Ctrl+V` | Add whatever magnet or torrent link is on the clipboard; the status bar says when there is one |
| `Ctrl+K` (or `Ctrl+F`) | The omnibox. Words filter the list by name as you type; a `key:value` term (`state:seeding`, `cat:Films`, `ratio<1`, `size>4g`, `added<7d`, `tracker:host`, `hash:7a3f`, `-tag:keep`) becomes a chip when you type the space after it, a term it cannot read becomes a red chip saying why, `Backspace` on an empty box hands the last chip back for editing, a click on a chip removes it. `>` and then a name runs a command (the palette opens with what you typed). A pasted magnet, `.torrent` link or path opens the Add dialog. `Enter` selects every match and returns to the list, so the next key acts on all of them; `Esc` empties the box, or returns to the list when it is empty |
| `Ctrl+B` / `Ctrl+Shift+B` | Focus the sidebar (All, then Status, Categories, Tags and Trackers, every entry with a count; arrows walk every section, `Space` makes the entry the section's choice, `Enter` chooses and returns to the list, `Esc` returns without choosing; `Left`/`Right` fold a section) / show or hide it |
| `Alt+X` (`M-x`, as in Editora; Settings → General → Keyboard can make it `Ctrl+Shift+P`) | Command palette: every command, found by typing part of its name |
| `Ctrl+Shift+F` | Search: the Search tab in place of the transfers, with the query focused. A category chip row and a provider chip row (`Tab` walks them, `Space` toggles), `Enter` asks every chosen provider at once and the results land as each answers; `Down` enters the results, `Enter` or a double-click opens the Add dialog for one, `Ctrl+C` copies its link, the context menu opens its detail page; `Esc` returns to the transfers |
| `Ctrl+R` | RSS: the RSS tab in place of the transfers. Arrows walk the feeds and the articles (`Left`/`Right` between them, `Down` from the filter box into the articles), `Enter` or a double-click on an article with a torrent opens the Add dialog for it, `M` toggles read, `Shift+M` marks the feed read, `N` or `Ctrl+N` adds a feed, `F5` refreshes it (`Shift+F5` every feed), `Delete` in the feed list removes one after a confirm, `Ctrl+C` copies the torrent or the page link; the context menus mirror the keys and add Download (at once, with the defaults) and Create rule from title…; `Esc` returns to the transfers |
| `Ctrl+,` | Settings: download folder, update check, the Add dialog's defaults, Categories and tags (names, save paths, delete), Watch folders, Automation (a command per event with placeholders, desktop notifications), Search (Torznab providers), RSS (the refresh interval, the feeds, the auto-download switch and rules), Connection (port, UPnP, IPv6, interface, connection limits, proxy) and Protocol (DHT, PEX, local discovery, uTP, encryption, anonymous mode, default trackers) IP filter (a blocklist URL refreshed on a schedule, a local list, manual bans), Speed (limits, alternative limits with a weekly schedule, overhead) and Queue and share limits (active downloads, seeds and total, the slow-torrent rule, a ratio and seeding-time limit that pauses or removes); searchable, applies live |
| `Ctrl+I` | Show or hide the detail pane: General, Files (priorities, open), Peers, Trackers (add, remove, reannounce), Pieces, Speed (the selected torrent's graph, the same windows as the Statistics window's) |
| `Ctrl+Shift+L` | Event log: what the engine reported (added, finished, tracker and disk errors, ports) |
| `Ctrl+Shift+T` | Statistics: this session's and all-time downloaded, uploaded, share ratio and active time, bytes with overhead and wasted, peers and DHT nodes, disk reads, writes and buffers, torrents by state, refreshed every second; under them the session speed graph (download and upload over the last minute, ten minutes, hour or day, the rate now and the peak in the legend) |
| `Ctrl+Shift+M` | Messages: what the status bar has said this session, newest first (a click on the status bar does the same) |
| `Space` | Pause the selected torrents, or resume them if all are paused |
| `Ctrl+Shift+U` | Force resume (ignore the queue) |
| `Ctrl+Shift+R` / `Ctrl+Shift+A` | Force recheck / force reannounce |
| `Ctrl+Shift+S` | Sequential download on or off |
| `Ctrl+L` | Limits for the selection: download and upload caps, connections, upload slots, first and last pieces first, and a ratio or seeding-time limit of its own in place of the global one |
| `Ctrl+Shift+G` | Set the selection's category: one that exists, none, or a new one (the Torrent and context menus list them as a submenu) |
| `Ctrl+T` | Edit the selection's tags: a checkbox per tag that exists, a field for new ones |
| `Ctrl+C` / `Ctrl+Shift+H` / `Ctrl+Shift+C` | Copy name / info-hash / magnet link |
| `Ctrl+Shift+O` | Open the destination folder |
| `Delete` / `Shift+Delete` | Remove, or remove with the downloaded files, after confirming |
| `Ctrl+A` | Select all |
| `Ctrl+Q` | Quit |

Every action is in the Torrent menu, the list's right-click menu and the palette, and applies to
the whole selection. `.torrent` files and links can also be dropped on the window, and anything on
the command line goes through the same Add dialog, which offers the categories: one with a save
path fills the folder when chosen, so a new torrent in it lands there. In the dialog Enter adds,
Shift+Enter adds paused and Esc cancels; in its file tree Space ticks, + and − change the priority
and ← → fold. "Next time, add with these settings without asking" (or Settings → Adding) skips it.
The sidebar keeps one
choice per section — a status (Downloading, Seeding, Completed, Paused, Active, Inactive, Stalled,
Checking, Errored), a category, a tag and a tracker host (or Trackerless, or Tracker error) — and
the list shows what passes all of them and the name filter; the palette's "Show: Downloading" and
the like pick a status without the mouse. The choices, the folded sections and whether the sidebar
shows are remembered. The status bar's "Alt speed" switches the
alternative speed limits by hand; the schedule in Settings switches them by the clock. The status bar shows the latest event; an error count appears there while
there are errors, and a click on it opens the log.

The list's columns (seventeen, from the header's ⋮ menu, Category and Tags among them) keep their
order, widths and visibility in `settings.properties`.

## BitTorrent engine

[libtorrent](https://www.libtorrent.org) 2.x, the engine behind qBittorrent and Deluge, through
[libtorrent4j](https://github.com/aldenml/libtorrent4j) (MIT): one core jar plus one jar of native
libraries per platform, chosen by a Maven profile. It sits behind `com.torrenta.engine.Engine`, a
deliberately small interface (start, add, list, rates, stop) that leaks no libtorrent type, so a
second engine — a Transmission daemon over RPC, for a seedbox — can be added without touching the
window.

Two things about the dependency that are not written down anywhere else:

- **libtorrent4j does not unpack its native library.** Its JNI class `System.load`s the path in
  `-Dlibtorrent4j.jni.path`, or falls back to `java.library.path`. The per-OS jars carry the library
  as a plain resource at `lib/<arch>/libtorrent4j.{so,dylib,dll}` (the Android layout).
  `NativeLibrary` copies it to `~/.torrenta/native/<version>/` once and sets the property before any
  libtorrent class loads. The path has to be absolute; `System.load` refuses a relative one.
- **Natives exist for macOS arm64, Linux x86_64 and Windows x86_64 only.** No Intel macs, no ARM
  Linux. On those the engine fails to start with a message that says so and the window stays up.

The alternatives considered were [bt](https://github.com/atomashpolskiy/bt) (pure Java, but its last
release is from 2021, and it has no uTP or v2 torrents) and a Transmission daemon over RPC (no engine
work, but a second program to install).

## Layout

```
src/main/java/com/torrenta                    App, AppInfo, DevCapture
src/main/java/com/torrenta/cli                CommandLine (--help, --version, inputs)
src/main/java/com/torrenta/engine             Engine, Torrent, TorrentState — no libtorrent types
src/main/java/com/torrenta/engine/libtorrent  LibtorrentEngine, NativeLibrary
src/main/java/com/torrenta/store              ConfigDir, Settings, TorrentStore
src/main/java/com/torrenta/ui                 MainWindow, Theme, Bytes
src/main/resources/com/torrenta               build-info.properties, styles/
branding/                         icon
scripts/dev-run.sh                run with ad-hoc -D options
scripts/render-icon.py            branding/torrenta-icon.svg → torrenta.png (Pillow)
```

## Not done

Add-torrent and add-magnet dialogs (the chords only say so in the status bar; pass inputs on the
command line), pause/resume/remove in the window, speed limits, a settings window, per-torrent
detail (files, peers, trackers), a native app image (`-Pdist`, to be taken from Termina, plus the
native library in the image), installers, releases. The order is in [docs/ROADMAP.md](docs/ROADMAP.md).

## License

MIT. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
