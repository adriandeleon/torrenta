#!/usr/bin/env bash
# Puts the true version into the packaged bundle's Info.plist.
#
# jpackage refuses an --app-version whose first number is zero, but only on macOS, so a 0.x release
# has to be built under a placeholder (0.1.0 -> 1.1.0). Left there, Finder's Get Info, `mdls` and
# System Settings would all report a version the application itself disagrees with.
#
# jpackage ad-hoc-signs the app image, and Info.plist is part of what the signature seals — so
# editing it makes macOS refuse to launch the app as tampered with. Hence the re-sign afterwards.
#
# Usage: fix-mac-bundle-version.sh <app-bundle> <true-version>
set -euo pipefail

app="${1:?app bundle path required}"
version="${2:?version required}"

# Only macOS produces a bundle at all; everywhere else this is a no-op by design.
[ "$(uname -s)" = "Darwin" ] || exit 0
[ -d "$app" ] || exit 0

plist="$app/Contents/Info.plist"
current=$(/usr/libexec/PlistBuddy -c "Print :CFBundleShortVersionString" "$plist" 2>/dev/null || echo "")
[ "$current" = "$version" ] && exit 0

/usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $version" "$plist"
/usr/libexec/PlistBuddy -c "Set :CFBundleVersion $version" "$plist"
codesign --force --deep --sign - "$app"
echo "bundle version: $current -> $version (re-signed)"
