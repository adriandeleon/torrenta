# Releasing

A release is a tag. [`release.yml`](../.github/workflows/release.yml) builds the installers on the
three platforms the engine's native library exists for, smoke-tests each packaged app, and
[JReleaser](https://jreleaser.org) ([`jreleaser.yml`](../jreleaser.yml)) publishes the GitHub
release and, when it has a token for them, the Homebrew cask and the winget pull request. JReleaser
builds nothing itself: jlink and jpackage are host-specific, so each platform's runner makes its
own installers and JReleaser is handed the finished files.

| Target | Runner | Files |
|---|---|---|
| `linux-x64` | `ubuntu-latest` | `Torrenta-X.Y.Z-linux-x64.deb`, `.rpm` |
| `macos-arm64` | `macos-latest` | `Torrenta-X.Y.Z-macos-arm64.dmg` |
| `windows-x64` | `windows-latest` | `Torrenta-X.Y.Z-windows-x64.msi` |

Plus `checksums_sha256.txt`. The release body is the install text in
[`.github/release-notes.md`](../.github/release-notes.md) followed by the version's section of
[`CHANGELOG.md`](../CHANGELOG.md), written by
[`scripts/release/ReleaseNotes.java`](../scripts/release/ReleaseNotes.java). JReleaser allows a
body at most 10,000 characters, so a section longer than that is shown by its entries' bold leads, one line
each, with a link to the full text at the tag.

## Before the first tag: a dry run

*Actions → Release → Run workflow* on `master` (or `gh workflow run release.yml --ref master`). It
does everything a tag does except publish:

- the preflight reports the version (the pom's, without `-SNAPSHOT`), builds the notes from
  `## Unreleased` and prints them in the run's summary, and says whether CI passed on the commit;
- the three build legs package, smoke-test and upload their installers;
- the publish job runs `jreleaser full-release --dry-run`: checksums, the release as it would be
  created, the cask and the winget manifests rendered (read from the tap and the fork with the
  workflow's own token, pushed nowhere). The `jreleaser-output` artifact holds them and the trace.

A green dry run is the go-ahead. Download the artifacts and install one if anything about packaging
changed.

## Cutting a release

1. In one commit on `master` (through a pull request like any other change):
   - set `<version>` in `pom.xml` to the release number, without `-SNAPSHOT`;
   - rename `## Unreleased` in `CHANGELOG.md` to `## X.Y.Z — YYYY-MM-DD` and put a fresh, empty
     `## Unreleased` above it. A section that grew by merges can have its `### Added` / `### Fixed`
     headings merged now; the release notes show each heading once either way.
2. Once CI is green on the merge commit, tag it and push the tag:

   ```bash
   git tag v0.X.0 && git push origin v0.X.0
   ```

   Only version-shaped tags start the workflow (`vX.Y.Z`, optionally `-rc1` or another suffix,
   which publishes a pre-release).
3. The preflight stops the release in seconds when the tag and the pom disagree, when the pom is
   still a snapshot, or when `CHANGELOG.md` has no `## X.Y.Z` section. It then requires CI to have
   passed on the tagged commit: it waits for a run in progress, and when there is none (or the
   newest was cancelled by a later push to `master`) it starts one on the tag and waits up to an
   hour. A failed run stops it; if the failure had nothing to do with the code, re-run CI and then
   the Release workflow.
4. After the release, reopen `master`: the next commit sets `<version>` to the next `-SNAPSHOT`.

A failed publish on the same tag: delete the release (`gh release delete vX.Y.Z`, which keeps the
tag), fix, and re-run the workflow's failed jobs. `immutableRelease` in `jreleaser.yml` creates the
release as a draft, uploads every file, then publishes it, which is the only flow that works once
GitHub's Immutable Releases are turned on for the repository; it rules out overwriting in place.

## macOS signing and notarization

The `macos-arm64` leg signs the app and dmg with the **Developer ID Application** certificate of
team `8JKSF797L8` (Adrian De Leon Saldivar), has Apple notarize the dmg and staples the ticket to
it, so Gatekeeper opens a downloaded Torrenta with no warning. The same certificate and API key sign
Editora; the setup is shared.

- **Import signing certificate (macOS)** puts the `.p12` into a throw-away keychain and exports
  `TORRENTA_MAC_SIGN_IDENTITY` / `TORRENTA_MAC_KEYCHAIN`. On a tag, missing secrets fail the leg; a
  dry run without them builds ad-hoc signed and warns.
- [`scripts/sign-mac-app.sh`](../scripts/sign-mac-app.sh), run by the `dist` profile after the
  Info.plist edits and before [`build-installers.sh`](../scripts/build-installers.sh), first sets
  `<signed>true</signed>` in `Contents/app/.jpackage.xml` (jpackage's dmg wrap otherwise re-signs
  its copy ad hoc, discarding ours), then signs every Mach-O file (the runtime, jspawnhelper,
  `torrenta-nox`, libtorrent4j), the runtime bundle, and the app with the hardened runtime and
  [`packaging/macos/Torrenta.entitlements`](../packaging/macos/Torrenta.entitlements) (JIT only;
  library validation stays on, since libtorrent4j ships in the bundle under the same team ID).
  `build-installers.sh` then signs the dmg. Without the identity (any local build) nothing changes.
- The **smoke test** runs that signed app, so the engine is proven to start under the hardened
  runtime before anything is uploaded.
- **Notarize the dmg (macOS)** checks the app inside the dmg still verifies, runs
  `notarytool submit --wait` with the App Store Connect API key, prints Apple's log, staples, and
  checks the dmg and its app with `spctl` the way Gatekeeper will.

Secrets (repository secrets on `adriandeleon/torrenta`, the same values as Editora's):

| Secret | What it is |
|---|---|
| `MACOS_CERT_P12` | base64 of a `.p12` holding the Developer ID Application certificate, its private key and Apple's Developer ID G2 intermediate (exported with `openssl pkcs12 -export -legacy`) |
| `MACOS_CERT_PASSWORD` | the `.p12` password |
| `APPLE_API_KEY_P8` | base64 of the App Store Connect API key (`AuthKey_<id>.p8`, team key, Developer role) |
| `APPLE_API_KEY_ID` | that key's 10-character ID |
| `APPLE_API_ISSUER` | the issuer ID (a UUID) shown above the team key list |

The certificate expires **2027-11-30** and the Apple Developer Program membership **2027-10-31**.
Renew the membership first, then make a new certificate and replace `MACOS_CERT_P12` and
`MACOS_CERT_PASSWORD` here and in Editora. Apps signed before expiry keep working: the secure
timestamp proves when they were signed.

## The package managers

Both need `PACKAGES_TOKEN`, a personal access token with `repo` scope that can push to
`adriandeleon/homebrew-tap` and `adriandeleon/winget-pkgs` (the workflow's own token cannot write
to another repository). Without the secret a release publishes on GitHub only and says so in a
notice; with it, the same workflow updates both. Neither Termina nor Torrenta has the secret yet, so
neither path has run for real. Before adding it:

- **Homebrew.** The cask is `Casks/torrenta.rb` in the tap, from
  [`cask.rb.tpl`](../src/jreleaser/distributions/torrenta/brew/cask.rb.tpl) (why it is overridden:
  [`src/jreleaser/NOTES.md`](../src/jreleaser/NOTES.md)). The tap's own README is left alone.
- **winget.** Sync the fork first (`gh repo sync adriandeleon/winget-pkgs`): it was 35,643 commits
  behind `microsoft/winget-pkgs` on 2026-10-22, and JReleaser branches from its `master`. Then
  look at a dry run's commit (`out/jreleaser/publish/torrenta-windows/winget`, `git show --stat`):
  in a local dry run it carried, beside the three manifests, line-ending changes to five files
  under `Tools/` and `.github/` that upstream stores with CRLF against its own `eol=crlf`
  attributes. JGit re-adds them as LF; git would not. A pull request with those in it will not be
  accepted, so either they are gone after the sync or the pull request is opened by hand with the
  manifests alone. The LICENSE that JReleaser also copied in is turned off (`skipLicenseFile`).
- **The install text.** [`.github/release-notes.md`](../.github/release-notes.md) names neither
  package manager while they publish nothing (0.1.0 went out saying `brew install` and
  `winget install`, and its notes were edited afterwards). Once the first cask and winget package
  exist, put the commands back in front of the downloads:
  `brew install --cask adriandeleon/tap/torrenta` for macOS and `winget install AdrianDeLeon.Torrenta`
  for Windows.

## Checking the configuration locally

JReleaser's CLI reads the history through JGit, which cannot open a git worktree (a `.git` file,
not a directory), so run it in a clone:

```bash
git clone -q . /tmp/torrenta-jr && cd /tmp/torrenta-jr && mkdir artifacts
for f in macos-arm64.dmg windows-x64.msi linux-x64.deb linux-x64.rpm; do echo x > artifacts/Torrenta-0.1.0-$f; done
java scripts/release/ReleaseNotes.java --unreleased 0.1.0 CHANGELOG.md .github/release-notes.md out/release-notes.md
JRELEASER_PROJECT_VERSION=0.1.0 JRELEASER_GITHUB_TOKEN=$(gh auth token) jreleaser full-release --dry-run
```

`jreleaser` is the CLI 1.26.0 (the version the workflow pins), or `java -jar
jreleaser-tool-provider-1.26.0.jar` from its GitHub release. `--dry-run` reads the repositories (the packagers clone the tap and the fork) and writes nothing.
The notes builder's own test is `java scripts/release/ReleaseNotesTest.java`; the preflight runs it.
