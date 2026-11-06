#!/usr/bin/env bash
#
# Records the torrent store a released Torrenta writes, for UpgradeRestoreTest:
#
#   scripts/upgrade-fixture.sh v0.4.1 [v0.4.0 …]
#
# For each tag: a detached worktree of it, compiled with its own pom, then
# scripts/upgrade-fixture/RecordStore.java compiled and run against that build's classes. The
# store and what that build showed of it land in
# src/test/resources/com/torrenta/engine/libtorrent/upgrade/<version>/. Add the version to
# UpgradeRestoreTest.VERSIONS. Needs a JDK 25 and network for Maven.
#
# The recorder runs in a network namespace of its own with loopback alone (`unshare -rn`, so
# Linux only): the release's default settings have DHT, LSD and UPnP on, and a session with the
# network writes the peers it met into the resume data, strangers' addresses and this machine's
# own among them, which have no place in the repository.
set -euo pipefail

if ! unshare -rn true 2>/dev/null; then
    echo "upgrade-fixture: needs unprivileged network namespaces (unshare -rn), so Linux" >&2
    exit 1
fi

cd "$(dirname "$0")/.."
root="$PWD"
recorder="$root/scripts/upgrade-fixture/RecordStore.java"
work="$(mktemp -d "${TMPDIR:-/tmp}/torrenta-upgrade-fixture.XXXXXX")"
trap 'git -C "$root" worktree remove --force "$work/src" >/dev/null 2>&1 || true; rm -rf "$work"' EXIT

for tag in "$@"; do
    version="${tag#v}"
    echo "== $tag"
    git -C "$root" worktree remove --force "$work/src" >/dev/null 2>&1 || true
    rm -rf "$work/src" "$work/classes"
    git -C "$root" worktree add --detach "$work/src" "$tag" >/dev/null
    (
        cd "$work/src"
        ./mvnw -q -B -DskipTests compile
        ./mvnw -q -B dependency:build-classpath -Dmdep.outputFile="$work/cp.txt" -Dmdep.includeScope=runtime
    )
    cp="$work/src/target/classes:$(cat "$work/cp.txt")"
    mkdir -p "$work/classes"
    javac -d "$work/classes" -cp "$cp" "$recorder"
    unshare -rn sh -c 'ip link set lo up 2>/dev/null || true; exec "$@"' sh \
        java --enable-native-access=ALL-UNNAMED \
            -Dtorrenta.nativeDir="$work/native" \
            -cp "$work/classes:$cp" RecordStore \
            "$root/src/test/resources/com/torrenta/engine/libtorrent/upgrade/$version" "$version"
done
