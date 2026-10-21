#!/usr/bin/env bash
# Declares the .torrent document type and the magnet: URL scheme in the packaged bundle's Info.plist.
#
# jpackage writes CFBundleDocumentTypes from --file-associations only while it builds the image, and
# refuses that option for the app-image type; the dmg step wraps the finished image as it is. It has
# no option for a URL scheme at all, and a browser only offers an application for magnet: links when
# its bundle lists the scheme under CFBundleURLTypes. So both are added here, after jpackage, with
# PlistBuddy, and the bundle is re-signed: jpackage ad-hoc-signs the image and Info.plist is part of
# what the signature seals (see fix-mac-bundle-version.sh, which runs first and does the same).
# The type's name and extension are the ones packaging/torrent-file.properties gives the other two
# platforms' installers.
#
# At run time a file or link arrives through java.awt.Desktop's open handlers (os.MacHandlers) when
# Torrenta is running, and as a command-line argument from the launcher when it is not.
#
# Usage: add-mac-types.sh <app-bundle>
set -euo pipefail

app="${1:?app bundle path required}"

# Only macOS produces a bundle at all; everywhere else this is a no-op by design.
[ "$(uname -s)" = "Darwin" ] || exit 0
[ -d "$app" ] || exit 0

plist="$app/Contents/Info.plist"
pb() { /usr/libexec/PlistBuddy -c "$1" "$plist"; }
if pb "Print :CFBundleURLTypes:0:CFBundleURLSchemes:0" 2>/dev/null | grep -q '^magnet$'; then
    exit 0
fi

pb "Delete :CFBundleDocumentTypes" 2>/dev/null || true
pb "Add :CFBundleDocumentTypes array"
pb "Add :CFBundleDocumentTypes:0 dict"
pb "Add :CFBundleDocumentTypes:0:CFBundleTypeName string 'BitTorrent file'"
pb "Add :CFBundleDocumentTypes:0:CFBundleTypeExtensions array"
pb "Add :CFBundleDocumentTypes:0:CFBundleTypeExtensions:0 string torrent"
pb "Add :CFBundleDocumentTypes:0:CFBundleTypeMIMETypes array"
pb "Add :CFBundleDocumentTypes:0:CFBundleTypeMIMETypes:0 string application/x-bittorrent"
pb "Add :CFBundleDocumentTypes:0:CFBundleTypeRole string Viewer"
pb "Add :CFBundleDocumentTypes:0:LSHandlerRank string Alternate"
pb "Add :CFBundleDocumentTypes:0:CFBundleTypeIconFile string Torrenta.icns"

pb "Delete :CFBundleURLTypes" 2>/dev/null || true
pb "Add :CFBundleURLTypes array"
pb "Add :CFBundleURLTypes:0 dict"
pb "Add :CFBundleURLTypes:0:CFBundleURLName string 'Magnet link'"
pb "Add :CFBundleURLTypes:0:CFBundleTypeRole string Viewer"
pb "Add :CFBundleURLTypes:0:CFBundleURLSchemes array"
pb "Add :CFBundleURLTypes:0:CFBundleURLSchemes:0 string magnet"
codesign --force --deep --sign - "$app"
echo "bundle types: .torrent document, magnet URL scheme (re-signed)"
