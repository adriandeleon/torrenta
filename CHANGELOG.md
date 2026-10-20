# Changelog

Notable changes, newest first. Versions follow [semantic versioning](https://semver.org); until
1.0.0 the minor number moves for anything user-visible.

## Unreleased

Project scaffold: a themed window with a menu bar, a torrent list and a status bar; the
`--help`/`--version` command line, which also takes `.torrent` paths and magnet links; the Caret &
Ink themes carried over from Editora and Termina; the development screen capture; CI on all three
platforms. The engine is libtorrent through libtorrent4j 2.1.0-39, behind a small `Engine`
interface; torrents from the command line are added and listed with size, progress, rates, peers
and state. No dialogs, resume data, limits or settings yet.
