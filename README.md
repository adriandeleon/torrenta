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

Status: **early**. A themed window with a menu bar, an icon toolbar, a torrent list, a status bar and an event log,
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

Requires JDK 25. Everything else — including Maven itself, via the wrapper — is fetched on demand.

```bash
./mvnw verify
```

runs the tests, the coverage floor and the formatter check. `./mvnw spotless:apply` formats.

```bash
./mvnw javafx:run
scripts/dev-run.sh -- "magnet:?xt=urn:btih:…"    # with arguments for the app
```

Builds need a JDK 25: the formatter (Palantir 2.92) does not run on JDK 27.

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

## Files

Everything lives in one folder, `~/.torrenta` (`--config-dir DIR` or `TORRENTA_CONFIG_DIR` to move
it): `settings.properties`, hand-editable; `torrents/<info-hash>.fastresume`, one per torrent,
libtorrent's resume data with the info dictionary inside, so deleting one file forgets one torrent;
`native/<version>/`, the unpacked engine. Downloads go to `~/Downloads` until changed in
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
changes and deleted with the torrent. A torrent's own share limit (Torrent → Limits…)
is kept there as `torrent.<info-hash>.share` and goes with the torrent when it is removed; its rate
caps and connection counts are libtorrent's and live in its resume data. The global queue and share
limits are `queue.*` and `share.*`. The theme is there too, under General →
Appearance: Editora Light and Dark (Caret & Ink, as Editora and Termina), Nord and Cupertino,
light and dark, applied the moment one is picked (`ui.theme` in the file).

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
| `Ctrl+O` (`Cmd+O` on macOS) | Add `.torrent` files, through the Add dialog |
| `Ctrl+M` | Add a magnet link or a link to a `.torrent` (the clipboard's, if it holds one) |
| `Ctrl+V` | Add whatever magnet or torrent link is on the clipboard; the status bar says when there is one |
| `Ctrl+F` | Filter the list by name; `Esc` clears and returns to the list |
| `Ctrl+B` / `Ctrl+Shift+B` | Focus the sidebar (All, then Status, Categories, Tags and Trackers, every entry with a count; arrows walk every section, `Space` makes the entry the section's choice, `Enter` chooses and returns to the list, `Esc` returns without choosing; `Left`/`Right` fold a section) / show or hide it |
| `Ctrl+Shift+P` | Command palette: every command, found by typing part of its name |
| `Ctrl+,` | Settings: download folder, update check, the Add dialog's defaults, Categories and tags (names, save paths, delete), Connection (port, UPnP, IPv6, interface, connection limits, proxy) and Protocol (DHT, PEX, local discovery, uTP, encryption, anonymous mode, default trackers) IP filter (a blocklist URL refreshed on a schedule, a local list, manual bans), Speed (limits, alternative limits with a weekly schedule, overhead) and Queue and share limits (active downloads, seeds and total, the slow-torrent rule, a ratio and seeding-time limit that pauses or removes); searchable, applies live |
| `Ctrl+I` | Show or hide the detail pane: General, Files (priorities, open), Peers, Trackers (add, remove, reannounce), Pieces |
| `Ctrl+Shift+L` | Event log: what the engine reported (added, finished, tracker and disk errors, ports) |
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
