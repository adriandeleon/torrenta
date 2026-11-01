## Install

**macOS (Apple Silicon)** — download the `macos-arm64` DMG below, open it, and drag Torrenta to
Applications. Intel Macs are not supported yet: the engine's native library is built for Apple
Silicon only.

The app is signed with a Developer ID and notarized by Apple, so it opens on first launch like any
other app.

**Windows** — download and run the `windows-x64` MSI below. The build is unsigned, so SmartScreen
may warn on first run.

**Linux (x86_64)** — download the DEB or RPM below. On Debian or Ubuntu, install with
`sudo apt install ./Torrenta-*.deb`; on Fedora, install with `sudo dnf install ./Torrenta-*.rpm`.

Verify a download against `checksums_sha256.txt`. The DEB and RPM are also signed: import
`torrenta-signing-key.asc` (`gpg --import torrenta-signing-key.asc`) and run
`gpg --verify <file>.asc <file>`. The RPM carries its signature inside as well, which
`rpm --import torrenta-signing-key.asc` lets `rpm -K` and `dnf` check.
