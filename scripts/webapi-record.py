#!/usr/bin/env python3
"""Records a qBittorrent-API server's answers to one fixed scenario, for the contract test.

Plays the same requests against any server that speaks qBittorrent's WebUI API (a real
qBittorrent to record the contract, a headless Torrenta to compare against it) and writes each
answer under OUT: a JSON answer as <name>.json, a plain one as <name>.txt, and every request's
status and body in statuses.json. The scenario adds two .torrent files and one magnet, files them
under a category and tags, and reads every Tier 1 and Tier 2 endpoint Torrenta speaks.

    scripts/webapi-record.py http://127.0.0.1:8080 admin PASSWORD seed.torrent pack.torrent OUT

SEED is a torrent whose data is already in the server's default save path (it seeds); PACK is a
multi-file one that is added stopped. Standard library only; nothing is sent anywhere but BASE.
"""

import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from http.cookiejar import CookieJar
from pathlib import Path

MAGNET = "magnet:?xt=urn:btih:233640c560f1c7062595a16289846e079d074172&dn=Nobody.Serves.This"


def main():
    if len(sys.argv) != 7:
        sys.exit(__doc__)
    base, user, password, seed, pack, out = sys.argv[1:]
    base = base.rstrip("/")
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    jar = CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    statuses = {}

    def call(name, path, form=None, files=None, method=None, record=True):
        url = base + "/api/v2/" + path
        headers = {"Referer": base + "/"}
        data = None
        if files:
            boundary = uuid.uuid4().hex
            body = b""
            for k, v in (form or {}).items():
                body += f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode()
            for path_ in files:
                body += (f'--{boundary}\r\nContent-Disposition: form-data; name="torrents"; '
                         f'filename="{Path(path_).name}"\r\nContent-Type: application/x-bittorrent\r\n\r\n').encode()
                body += Path(path_).read_bytes() + b"\r\n"
            body += f"--{boundary}--\r\n".encode()
            data = body
            headers["Content-Type"] = "multipart/form-data; boundary=" + boundary
        elif form is not None:
            data = urllib.parse.urlencode(form).encode()  # empty for a POST with no fields, still a POST
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        posting = form is not None or files is not None
        request = urllib.request.Request(url, data=data if posting else None, headers=headers, method=method or ("POST" if posting else "GET"))
        try:
            with opener.open(request, timeout=30) as r:
                status, text = r.status, r.read().decode("utf-8")
        except urllib.error.HTTPError as e:
            status, text = e.code, e.read().decode("utf-8", "replace")
        if record:
            statuses[name] = {"status": status, "body": text if len(text) < 200 else text[:200]}
            try:
                parsed = json.loads(text)
                if isinstance(parsed, (dict, list)):
                    (out / (name + ".json")).write_text(json.dumps(parsed, indent=1, sort_keys=True) + "\n")
                else:
                    (out / (name + ".txt")).write_text(text)
            except ValueError:
                (out / (name + ".txt")).write_text(text)
        return status, text

    call("no-session", "app/version")
    call("login-wrong", "auth/login", {"username": user, "password": password + "x"})
    call("login", "auth/login", {"username": user, "password": password})
    call("version", "app/version")
    call("webapi-version", "app/webapiVersion")
    call("default-save-path", "app/defaultSavePath")
    call("preferences", "app/preferences")

    call("create-category", "torrents/createCategory", {"category": "tv", "savePath": ""})
    call("create-category-again", "torrents/createCategory", {"category": "tv", "savePath": ""})
    call("create-tags", "torrents/createTags", {"tags": "keep,old"})
    call("add-seed", "torrents/add", {}, files=[seed])
    call("add-pack", "torrents/add", {"category": "tv", "tags": "keep", "stopped": "true"}, files=[pack])
    call("add-magnet", "torrents/add", {"urls": MAGNET, "stopped": "true"})
    call("add-nothing", "torrents/add", {"category": "tv"})
    time.sleep(5)

    _, info = call("info", "torrents/info", {"sort": "name"}, method="POST", record=False)
    rows = {r["name"]: r for r in json.loads(info)}
    seed_hash = next(r["hash"] for r in rows.values() if r["progress"] == 1)
    pack_hash = next(r["hash"] for r in rows.values() if r["category"] == "tv")

    call("info", "torrents/info?sort=name")
    call("info-filtered", "torrents/info?filter=completed&category=")
    call("properties", "torrents/properties?hash=" + seed_hash)
    call("properties-unknown", "torrents/properties?hash=ffffffffffffffffffffffffffffffffffffffff")
    call("properties-no-hash", "torrents/properties")
    call("files", "torrents/files?hash=" + pack_hash)
    call("trackers", "torrents/trackers?hash=" + pack_hash)
    call("webseeds", "torrents/webseeds?hash=" + pack_hash)
    call("piece-states", "torrents/pieceStates?hash=" + seed_hash)
    call("categories", "torrents/categories")
    call("tags", "torrents/tags")
    call("transfer", "transfer/info")
    call("speed-mode", "transfer/speedLimitsMode")
    call("download-limit", "transfer/downloadLimit")
    call("torrent-download-limit", "torrents/downloadLimit", {"hashes": pack_hash})
    status, maindata = call("maindata", "sync/maindata?rid=0")
    rid = json.loads(maindata)["rid"] if status == 200 else 0
    call("set-category", "torrents/setCategory", {"hashes": seed_hash, "category": "tv"})
    call("set-category-unknown", "torrents/setCategory", {"hashes": seed_hash, "category": "nope"})
    call("file-prio-bad", "torrents/filePrio", {"hash": pack_hash, "id": "0", "priority": "4"})
    time.sleep(2)
    call("maindata-diff", f"sync/maindata?rid={rid}")
    call("peers", "sync/torrentPeers?rid=0&hash=" + seed_hash)
    call("log", "log/main")
    call("unknown-endpoint", "torrents/noSuchThing")
    call("delete", "torrents/delete", {"hashes": "all", "deleteFiles": "false"})
    call("categories-remove", "torrents/removeCategories", {"categories": "tv"})
    call("tags-delete", "torrents/deleteTags", {"tags": "keep,old"})
    call("logout", "auth/logout", {})

    (out / "statuses.json").write_text(json.dumps(statuses, indent=1, sort_keys=True) + "\n")
    print(f"{len(statuses)} requests recorded in {out}")


if __name__ == "__main__":
    main()
