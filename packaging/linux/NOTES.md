# The Linux menu entry

`Torrenta.desktop` replaces the one jpackage generates, to add `StartupWMClass` — the class JavaFX
derives from the module's main class, without which a running window cannot be matched to its
launcher and the dock shows a second generic icon beside it — and the P2P categories.

Carried over from Termina, whose packaging found the traps:

- **The name must match the launcher exactly.** jpackage looks for `Torrenta.desktop`; given a
  lowercase name it ignores the override without a word and ships its own.
- **Only some `APPLICATION_*` tokens are substituted**: `NAME`, `DESCRIPTION`, `LAUNCHER`, `ICON`.
  `APPLICATION_CATEGORIES` and `APPLICATION_MIMETYPES` are not, so the categories are written out
  here, and `--linux-menu-group` on the command line has no effect on this file.
- **The deb and rpm bundlers ignore `--icon`** and regenerate `lib/Torrenta.png` from jpackage's
  32x32 stock image; `build-installers.sh` stages `branding/torrenta.png` into the resource
  directory as `Torrenta.png`, the one override they honour.

`MimeType=` (`application/x-bittorrent`, `x-scheme-handler/magnet`) is deliberately not here yet:
the app does not hand a second instance's arguments to the running one until 0.8.

CI asserts all of this against a built `.deb` rather than against this file, because every failure
above produces a package that looks fine from the outside.
