#!/usr/bin/env bash
# Signs the finished Torrenta.app with a Developer ID Application identity, hardened runtime and a
# secure timestamp: what Apple's notarization asks for.
#
# Runs only when TORRENTA_MAC_SIGN_IDENTITY names the identity. release.yml's macOS leg imports the
# certificate into a throw-away keychain and exports it, with that keychain in TORRENTA_MAC_KEYCHAIN.
# Anywhere else (a local build, CI, a dry run without the secrets) this is a no-op and the bundle keeps
# the ad-hoc signature fix-mac-bundle-version.sh and add-mac-types.sh gave it.
#
# Order matters. It runs after every edit to the bundle (both of those scripts edit Info.plist) and
# before build-installers.sh wraps it, and it signs inside-out, as codesign requires: every loose
# Mach-O file (the runtime's dylibs, jspawnhelper, libtorrent4j, the torrenta-nox launcher), then the
# nested runtime bundle, then the app. --deep is not used for signing: it would give every nested
# binary the app's entitlements.
#
# Usage: sign-mac-app.sh <app-bundle>
set -euo pipefail

app="${1:?app bundle path required}"
identity="${TORRENTA_MAC_SIGN_IDENTITY:-}"

[ "$(uname -s)" = "Darwin" ] || exit 0
[ -n "$identity" ] || { echo "sign: TORRENTA_MAC_SIGN_IDENTITY not set, bundle stays ad-hoc signed"; exit 0; }
[ -d "$app" ] || { echo "sign: no app bundle at $app" >&2; exit 1; }

entitlements="$(cd "$(dirname "$0")/.." && pwd)/packaging/macos/Torrenta.entitlements"
[ -f "$entitlements" ] || { echo "sign: no $entitlements" >&2; exit 1; }

sign=(codesign --force --timestamp --options runtime)
[ -n "${TORRENTA_MAC_KEYCHAIN:-}" ] && sign+=(--keychain "$TORRENTA_MAC_KEYCHAIN")
sign+=(--sign "$identity")

echo "sign: Developer ID signing $app as \"$identity\""

# jpackage's dmg wrap reads this flag. For an image it believes unsigned, it copies the app without
# .jpackage.xml, adds a .package file and re-signs the copy ad hoc, throwing this signature away (Apple
# then rejects every binary as unsigned). For a signed image it copies the app untouched. It is sealed
# with the rest of the bundle, so it must change before codesign. (jpackage 25: MacPackagingPipeline.)
state="$app/Contents/app/.jpackage.xml"
[ -f "$state" ] || { echo "sign: no $state; jpackage would re-sign the app ad hoc in the dmg" >&2; exit 1; }
if grep -q '<signed>' "$state"; then
    perl -pi -e 's#<signed>\s*false\s*</signed>#<signed>true</signed>#' "$state"
else
    perl -pi -e 's#</jpackage-state>#  <signed>true</signed>\n</jpackage-state>#' "$state"
fi
grep -q '<signed>true</signed>' "$state" || { echo "sign: could not mark $state as signed" >&2; exit 1; }

# Finder metadata or resource forks anywhere in the bundle make codesign refuse to seal it.
xattr -cr "$app"

main="$app/Contents/MacOS/Torrenta"
libraries=()
executables=()
while IFS= read -r -d '' file; do
    [ "$file" = "$main" ] && continue
    file -b "$file" | grep -q '^Mach-O' || continue
    case "$file" in
        *.dylib) libraries+=("$file") ;;
        *) if [ -x "$file" ]; then executables+=("$file"); else libraries+=("$file"); fi ;;
    esac
done < <(find "$app" -type f -print0)
echo "sign: ${#libraries[@]} libraries, ${#executables[@]} executables besides the main launcher"

[ "${#libraries[@]}" -gt 0 ] && "${sign[@]}" "${libraries[@]}"
[ "${#executables[@]}" -gt 0 ] && "${sign[@]}" --entitlements "$entitlements" "${executables[@]}"
[ -d "$app/Contents/runtime" ] && "${sign[@]}" "$app/Contents/runtime"
"${sign[@]}" --entitlements "$entitlements" "$app"

codesign --verify --deep --strict --verbose=2 "$app"
echo "sign: app signed and verified"
