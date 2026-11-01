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

# The GNOME Software listing

`io.github.adriandeleon.Torrenta.metainfo.xml` is the AppStream listing — name, description,
licence, homepage, screenshot, release notes — that GNOME Software and KDE Discover show on
Torrenta's page once it is installed. It names its package (`<pkgname>torrenta</pkgname>`), since a
downloaded package has no distribution catalogue to add that, and the icon by its theme name; the
menu entry asks for the same name. Both are files the packages own: the rpm's through
`torrenta.spec`, jpackage's own template (JDK 25) with two `install` lines added, and the deb's
through `own_in_deb` in `build-installers.sh`, which unpacks the finished deb and rebuilds it with
them, since jpackage's deb bundler packages `/opt/torrenta` and nothing else. Termina does the same,
and its `packaging/linux/NOTES.md` has the detail. Keep the spec's jpackage tokens; after a JDK
upgrade compare it with the new template.

**Software has two pages, and only the installed one reads the listing.** Double-clicking a
downloaded `.deb` opens an installation window built from the package's control fields alone:
"torrenta" in lower case, a generic icon, Unknown License, the summary as the description, and the
`Homepage` (`--about-url`), which is the one field a package can improve there. That holds for any
downloaded deb. Check either page without root or a window:

```bash
/usr/libexec/gnome-software-cmd filename-to-app "$PWD/torrenta_<v>_amd64.deb" --show-results
/usr/libexec/gnome-software-cmd installed --show-results --refine-flags=license,url,icon
```

**The `<releases>` are written by hand**, at each release (docs/release.md).
