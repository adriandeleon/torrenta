# Torrenta

A keyboard-first, cross-platform BitTorrent client. JDK 25 + JavaFX 26 + Maven, modular (JPMS,
module `com.torrenta`). Same author, layout and conventions as Termina and Editora.

## Commands

- Run: `./mvnw javafx:run`
- Test + format check: `./mvnw verify` (Spotless/Palantir runs at `verify`; `./mvnw spotless:apply`
  fixes formatting)
- Run with ad-hoc options: **`scripts/dev-run.sh -Dfoo=bar -- <app args>`**. `mvn javafx:run`
  cannot take them — the plugin's `<options>` are fixed in the pom.
- Smoke: `scripts/dev-run.sh -Dtorrenta.capture=/tmp/shot.png -- "magnet:?…"` (or
  `TORRENTA_CAPTURE=/tmp/shot.png ./mvnw javafx:run`) renders the real window, writes a PNG, prints
  `capture:` lines (title, focus, engine, status bar) and exits.

Run Maven from the project root with a JDK 25.

## Architecture

| Layer | What | Where |
|---|---|---|
| Entry | `--help`/`--version`, toolkit start, theme | `com.torrenta.App`, `com.torrenta.cli` |
| UI | The window: menu bar, torrent list, status bar | `com.torrenta.ui` |
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

## Things that will bite you

- **libtorrent4j does not unpack its native library.** Its JNI class's static initialiser
  `System.load`s `-Dlibtorrent4j.jni.path`, else `System.loadLibrary("torrent4j")`. The per-OS jar
  is resources only: `lib/<arch>/libtorrent4j.{so,dylib,dll}`. `NativeLibrary.ensureAvailable()`
  copies it out and sets the property, and **must run before any `org.libtorrent4j` class that
  touches native code is loaded** — `LibtorrentEngine.start()` is the only entry point for that
  reason. The failure otherwise is a `LinkageError` whose message is a link to the project page;
  the cause has the real reason.
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
- **Surefire runs on the classpath** and points `torrenta.nativeDir` into `target/`, so tests never
  write under `~/.torrenta`. `LibtorrentEngineTest` starts a real session on purpose: it is the one
  test that proves the native loads on each CI platform.
- **Palantir 2.92 does not run on JDK 27.** Build with a JDK 25.

## Conventions (carried from Termina)

- **Extract the decision, then test it.** Anything with a rule in it becomes a static method or a
  pure class with its own tests. UI classes stay thin.
- **Verify by driving it.** UI bugs are visual and none of them throw. Use the capture hook, not
  reasoning about dispatch order.
- **Chords are `Shortcut+key`** (Ctrl off macOS, Cmd on it), declared once on the menu item so the
  menu and the accelerator cannot disagree.
- **A settings change applies live**, with no OK button, when settings exist.
- **Version comes from the pom** through `build-info.properties`; nothing else states it.

## Not done

Add dialogs (Ctrl+O / Ctrl+M only say so; inputs come from the command line), pause/resume/remove
in the window (`Engine.remove` exists, nothing calls it yet), limits, a settings window, per-torrent
detail, `-Pdist` with the native in the image.
The table replaces its items each tick, which drops the selection — fix before adding row actions.
