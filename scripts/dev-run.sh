#!/usr/bin/env bash
#
# Runs Torrenta from target/classes with an explicit module path.
#
# `mvn javafx:run` cannot take ad-hoc -D options (the plugin's <options> are fixed in the pom), and
# the development capture hook is driven entirely by system properties. This builds the module path
# itself so any -D can be passed through:
#
#   scripts/dev-run.sh -Dtorrenta.capture=/tmp/shot.png
#   scripts/dev-run.sh -Dtorrenta.capture=/tmp/shot.png -- "magnet:?xt=urn:btih:..."
#
# Arguments before `--` go to the JVM (-D, -X, --enable-...); arguments after it go to the app.
set -euo pipefail

cd "$(dirname "$0")/.."

mvn -q -B compile
mvn -q -B dependency:copy-dependencies -DoutputDirectory=target/deps

MODULE_PATH="$(python3 - <<'PY'
import os, platform

deps = 'target/deps'
machine = platform.machine()
arch = 'aarch64' if machine in ('arm64', 'aarch64') else 'x86_64'
system = platform.system()
classifier = {'Darwin': 'mac', 'Linux': 'linux', 'Windows': 'win'}.get(system, 'mac')
if classifier == 'mac' and arch == 'aarch64':
    want = 'mac-aarch64'
elif classifier == 'linux' and arch == 'aarch64':
    want = 'linux-aarch64'
else:
    want = classifier

keep = []
for jar in sorted(os.listdir(deps)):
    if jar.startswith('javafx-'):
        # JavaFX ships a stub jar plus a platform-classifier jar holding the real classes. Both
        # declare the same module, so putting both on the path is a duplicate-module error.
        if want in jar:
            keep.append(jar)
        continue
    if jar.startswith(('junit-', 'apiguardian', 'opentest4j')):
        continue
    keep.append(jar)

print(os.pathsep.join(os.path.join(deps, j) for j in keep))
PY
)"

case "$(uname -s)" in
    Darwin) PRISM=mtl,es2,sw ;;
    Linux)  PRISM=es2,sw ;;
    *)      PRISM=d3d,es2,sw ;;
esac

JVM_ARGS=()
APP_ARGS=()
seen_separator=false
for arg in "$@"; do
    if [ "$seen_separator" = true ]; then
        APP_ARGS+=("$arg")
    elif [ "$arg" = "--" ]; then
        seen_separator=true
    else
        JVM_ARGS+=("$arg")
    fi
done

exec java \
    --module-path "$MODULE_PATH:target/classes" \
    --add-modules com.torrenta,ALL-MODULE-PATH \
    --enable-native-access=javafx.graphics,libtorrent4j \
    "-Dprism.order=$PRISM" \
    "${JVM_ARGS[@]}" \
    -m com.torrenta/com.torrenta.App \
    "${APP_ARGS[@]}"
