# Why the cask template is overridden

(This file sits outside `distributions/torrenta/brew/` on purpose: JReleaser copies every file in a
packager's template directory into the repository it publishes to, and a dry run showed this one
on its way into the tap.)

JReleaser's stock cask template emits

    binary "<artifact-root>/bin/<executable>"

which is the layout of a command-line tool unpacked into a folder. Torrenta ships a macOS
application bundle: its executable is at `Torrenta.app/Contents/MacOS/Torrenta`, the path the stock
stanza names does not exist, and what a cask should do with a bundle is install it — `app`, not
`binary`.

The `cask.appName` setting is meant to produce exactly that stanza. It is dropped from the resolved
configuration (`jreleaser config` shows the cask with `enabled`, `name` and `displayName` and no
`appName`), so the template says it outright instead.

Another thing lives here rather than in `jreleaser.yml`:

- **`zap`.** Removes `~/.torrenta`, which holds settings and the session log.

There are no `caveats`. Until the dmg was Developer ID signed and notarized (docs/release.md), the
cask carried one telling users to allow the app under Privacy & Security; a notarized app needs no
such step. (Passing caveats through `extraProperties` never reached the template, which is why it
had been written here.)

There is deliberately no `depends_on macos:`. The bundle's own `LSMinimumSystemVersion` is jpackage's
default of 10.11, and the real floor — whatever JavaFX 27 and the jlink runtime actually require — has
not been tested. A version stated too high blocks installs that would have worked; too low lets a
broken one through. Omitting it lets Homebrew allow any macOS, which is at least not a claim.

Note that multi-line `{{! }}` comments swallow the lines that follow them in JReleaser's renderer —
the caveats block rendered as an empty heredoc until the comments were removed. Keep comments in
this template to a single line.

Regenerate and inspect after any change:

    jreleaser package --basedir .   (needs a clone, not a worktree: JGit reads no .git file)

The output is under `out/jreleaser/package/torrenta/brew/Casks/`.
