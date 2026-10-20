<p align="center">
  <img src="branding/torrenta.png" alt="" width="128" height="128">
</p>

<h1 align="center">Torrenta</h1>

<p align="center">
  <a href="https://github.com/adriandeleon/torrenta/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/adriandeleon/torrenta/actions/workflows/ci.yml/badge.svg"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/github/license/adriandeleon/torrenta"></a>
  <img alt="Java" src="https://img.shields.io/badge/Java-25-orange">
  <img alt="JavaFX" src="https://img.shields.io/badge/JavaFX-26-blue">
  <img alt="Platforms" src="https://img.shields.io/badge/platforms-macOS%20%C2%B7%20Windows%20%C2%B7%20Linux-lightgrey">
</p>

A keyboard-first, cross-platform BitTorrent client built on JavaFX.

Status: **scaffold**. A themed window with a menu bar, a torrent list and a status bar, on a real
engine: libtorrent, through [libtorrent4j](https://github.com/aldenml/libtorrent4j). Torrents given
on the command line are added and listed; there are no dialogs, settings, resume data or limits yet.

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

### Development capture

```bash
scripts/dev-run.sh -Dtorrenta.capture=/tmp/torrenta.png -- "magnet:?xt=urn:btih:…"
TORRENTA_CAPTURE=/tmp/torrenta.png ./mvnw javafx:run
```

Renders the real window, writes it to a PNG, prints a few `capture:` facts (size, title, focus owner,
engine, status bar) and exits. CI runs the first form under Xvfb on Linux as a smoke test.

## Keyboard

| Chord | Action |
|---|---|
| `Ctrl+O` (`Cmd+O` on macOS) | Add a `.torrent` file |
| `Ctrl+M` | Add a magnet link |
| `Ctrl+Q` | Quit |

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
src/main/java/com/torrenta/ui                 MainWindow, Theme, Bytes
src/main/resources/com/torrenta               build-info.properties, styles/
branding/                         icon
scripts/dev-run.sh                run with ad-hoc -D options
scripts/render-icon.py            branding/torrenta-icon.svg → torrenta.png (Pillow)
```

## Not done

Add-torrent and add-magnet dialogs (the chords only say so in the status bar; pass inputs on the
command line), resume data across restarts, pause/resume/remove, speed limits, a download-folder
setting, per-torrent detail (files, peers, trackers), a native app image (`-Pdist`, to be taken from
Termina, plus the native library in the image), installers, releases.

## License

MIT. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
