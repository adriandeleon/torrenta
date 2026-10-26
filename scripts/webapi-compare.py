#!/usr/bin/env python3
"""Compares two recordings of scripts/webapi-record.py by shape: qBittorrent's against Torrenta's.

    scripts/webapi-compare.py QBITTORRENT_DIR TORRENTA_DIR

For every answer both recorded: a field one has and the other lacks, and a field whose value is of
another kind (number, string, bool, null, array, object; 0 and 0.0 are both numbers), down through
the rows of a list and the values of a map; then every request whose status differs. Values are
not compared: two servers with two clocks never agree on them. Exits 1 when anything differs.
"""

import json
import sys
from pathlib import Path


def kind(v):
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "bool"
    if isinstance(v, (int, float)):
        return "number"
    if isinstance(v, str):
        return "string"
    if isinstance(v, list):
        return "array"
    return "object"


def compare(where, a, b, out):
    if kind(a) != kind(b):
        out.append(f"{where}: {kind(a)} there, {kind(b)} here")
        return
    if isinstance(a, dict):
        # A map keyed by hash or address is a table: compare its first row with each of ours.
        keyed = a and all(len(k) >= 20 or ":" in k for k in a) and b and all(len(k) >= 20 or ":" in k for k in b)
        if keyed:
            # Row against the same row where both have it, else against their first.
            first = next(iter(a.values()))
            for k, v in b.items():
                compare(f"{where}[{k[:8]}]", a.get(k, first), v, out)
            return
        subset = where == "preferences"  # Torrenta reports the keys it has a setting for
        moment = where.endswith("-diff")  # what a diff carries is what moved at that instant
        for k in sorted(set(a) | set(b)):
            if (k not in a or k not in b) and moment:
                continue
            if k not in b and subset:
                continue
            if k not in b:
                out.append(f"{where}.{k}: missing here")
            elif k not in a:
                out.append(f"{where}.{k}: not qBittorrent's")
            else:
                compare(f"{where}.{k}", a[k], b[k], out)
    elif isinstance(a, list) and a and b:
        for i, v in enumerate(b[: len(a)]):
            compare(f"{where}[{i}]", a[min(i, len(a) - 1)], v, out)


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    theirs, ours = Path(sys.argv[1]), Path(sys.argv[2])
    out = []
    for f in sorted(theirs.glob("*.json")):
        if f.name == "statuses.json" or not (ours / f.name).exists():
            continue
        compare(f.stem, json.loads(f.read_text()), json.loads((ours / f.name).read_text()), out)
    a = json.loads((theirs / "statuses.json").read_text())
    b = json.loads((ours / "statuses.json").read_text())
    for name in sorted(set(a) & set(b)):
        if a[name]["status"] != b[name]["status"]:
            out.append(f"{name}: status {a[name]['status']} there, {b[name]['status']} here")
    for line in out:
        print(line)
    print(f"{len(out)} differences")
    sys.exit(1 if out else 0)


if __name__ == "__main__":
    main()
