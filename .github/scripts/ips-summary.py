"""Prints the part of a macOS crash report (.ips) that names a crash: why, and the crashed thread.

An .ips file is a JSON header line followed by a JSON body. The body's threads list frames as an
image index and an offset, with a symbol when the image has one; the images are listed apart.
"""

import json
import sys


def main(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        header = f.readline()
        body = json.loads(f.read())
    print(f"=== {path}")
    print("header:", header.strip()[:300])
    for key in ("exception", "termination", "asi", "ktriageinfo"):
        if key in body:
            print(f"{key}: {json.dumps(body[key])[:1500]}")
    images = body.get("usedImages", [])
    threads = body.get("threads", [])
    crashed = body.get("faultingThread", 0)
    for index, thread in enumerate(threads):
        mark = "CRASHED " if index == crashed else ""
        name = thread.get("name") or thread.get("queue") or ""
        frames = thread.get("frames", [])
        # Every thread, briefly: the crashed one in full, the rest by their top frames.
        shown = frames if index == crashed else frames[:6]
        print(f"--- {mark}thread {index} {name}")
        for frame in shown:
            at = frame.get("imageIndex")
            image = images[at] if isinstance(at, int) and 0 <= at < len(images) else {}
            where = image.get("name") or image.get("path") or "?"
            symbol = frame.get("symbol", "")
            print(f"    {where} +{frame.get('imageOffset', 0):#x} {symbol}")


if __name__ == "__main__":
    for arg in sys.argv[1:]:
        main(arg)
