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

`MimeType=` lists `application/x-bittorrent` and `x-scheme-handler/magnet`, and `Exec` ends in
`%U` so a double-clicked file or a browser's magnet link reaches the command line; a running
Torrenta takes them from the second instance (`os.SingleInstance`). `application/x-bittorrent`
has been in freedesktop's shared-mime-info for as long as the database has existed, so no MIME
XML of ours is needed for the type to exist; the deb and rpm carry jpackage's own
(`--file-associations packaging/torrent-file.properties`) all the same, for a distribution that
trimmed the database. The desktop's *default* for the two types is set by the running program
(`xdg-mime default`, Settings → General → Operating system), since a package must not decide it.

CI asserts all of this against a built `.deb` rather than against this file, because every failure
above produces a package that looks fine from the outside.
