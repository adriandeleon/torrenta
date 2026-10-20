## Install

**macOS (Apple Silicon)** — `brew install --cask adriandeleon/tap/torrenta`, or download the
`macos-arm64` DMG below, open it, and drag Torrenta to Applications. Intel Macs are not supported
yet: the engine's native library is built for Apple Silicon only.

Torrenta is not yet signed with an Apple Developer ID, so macOS will refuse to open it the first
time. Allow it once under System Settings → Privacy & Security → Open Anyway.

**Windows** — `winget install AdrianDeLeon.Torrenta`, or download and run the `windows-x64` MSI
below. The build is unsigned, so SmartScreen may warn on first run.

**Linux (x86_64)** — download the DEB or RPM below. On Debian or Ubuntu, install with
`sudo apt install ./Torrenta-*.deb`; on Fedora, install with `sudo dnf install ./Torrenta-*.rpm`.

Verify a download against `checksums_sha256.txt`.

## Changes

{{changelogChanges}}

{{changelogContributors}}
