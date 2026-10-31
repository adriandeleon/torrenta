#!/usr/bin/env bash
# Wraps an already-finished application image into this platform's native installers.
#
# Wraps, rather than builds: jpackage's --app-image mode packages exactly what it is handed, so the
# image that was smoke-tested is the one that ships. jpackage is host-specific and there is no
# cross-building, so each platform makes its own:
#   macOS   .dmg
#   Windows .msi
#   Linux   .deb and .rpm
#
# Usage: build-installers.sh <app-image> <app-version> <dest-dir> <icon>
set -euo pipefail

image="${1:?app image required}"
version="${2:?app version required}"
dest="${3:?destination required}"
icon="${4:-}"

[ -d "$image" ] || { echo "no app image at $image" >&2; exit 1; }
mkdir -p "$dest"

common=(--app-image "$image" --name Torrenta --app-version "$version" --dest "$dest"
        --vendor "Adrian De Leon"
        --description "A keyboard-first BitTorrent client"
        --copyright "Copyright 2026 Adrian De Leon")
# The .torrent association: registry entries in the MSI, a MIME XML in the deb and rpm. The dmg
# wraps a bundle whose Info.plist already carries it (scripts/add-mac-types.sh).
association="$(dirname "$0")/../packaging/torrent-file.properties"
[ -n "$icon" ] && [ -f "$icon" ] && common+=(--icon "$icon")

build() {
    local type="$1"; shift
    echo "[installers] building $type"
    if jpackage --type "$type" "${common[@]}" "$@"; then
        echo "[installers] $type OK"
    else
        echo "::error::[installers] $type failed" >&2
        return 1
    fi
}

case "$(uname -s)" in
    Darwin)
        build dmg --mac-package-name Torrenta
        # The disk image gets its own Developer ID signature when the app has one (see
        # sign-mac-app.sh), so Gatekeeper can check it before mounting; release.yml then notarizes it.
        if [ -n "${TORRENTA_MAC_SIGN_IDENTITY:-}" ]; then
            sign=(codesign --force --timestamp)
            [ -n "${TORRENTA_MAC_KEYCHAIN:-}" ] && sign+=(--keychain "$TORRENTA_MAC_KEYCHAIN")
            for dmg in "$dest"/*.dmg; do
                "${sign[@]}" --sign "$TORRENTA_MAC_SIGN_IDENTITY" "$dmg"
                codesign --verify --verbose=2 "$dmg"
            done
        fi
        ;;
    Linux)
        # The resource directory overrides the desktop entry (for StartupWMClass and the categories;
        # see packaging/linux/NOTES.md) and the icon, which the deb and rpm bundlers otherwise
        # regenerate from their own 32x32 stock image.
        resources="$(mktemp -d)"
        cp "$(dirname "$0")/../packaging/linux/"*.desktop "$resources/"
        [ -n "$icon" ] && [ -f "$icon" ] && cp "$icon" "$resources/Torrenta.png"
        build deb \
            --linux-package-name torrenta \
            --linux-deb-maintainer "adrian.deleon@gmail.com" \
            --linux-menu-group "Network;P2P;FileTransfer" \
            --linux-shortcut \
            --file-associations "$association" \
            --resource-dir "$resources"
        build rpm \
            --linux-package-name torrenta \
            --linux-rpm-license-type MIT \
            --linux-menu-group "Network;P2P;FileTransfer" \
            --linux-shortcut \
            --file-associations "$association" \
            --resource-dir "$resources"
        ;;
    *)
        # A fixed upgrade UUID is what makes the next MSI replace this one rather than installing
        # beside it. Generated once for Torrenta and never changed.
        # The magnet: protocol is not an installer matter (jpackage has no option for a URL
        # scheme); the running program writes it under HKCU from Settings (os.Handlers).
        build msi \
            --file-associations "$association" \
            --win-menu \
            --win-menu-group Torrenta \
            --win-shortcut \
            --win-dir-chooser \
            --win-per-user-install \
            --win-upgrade-uuid "b7c2e9d4-5a61-4f0e-9c3b-2d8f7a1e6c05"
        ;;
esac

echo "[installers] produced:"
ls -la "$dest"
