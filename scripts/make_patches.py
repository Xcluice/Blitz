#!/usr/bin/env python3
"""Builds delta patches from earlier release APKs to the new one, plus update.json.

usage: make_patches.py NEW_APK NEW_CODE OUT_DIR OLD_CODE:OLD_APK [...]
Patch format "BLZ1": magic(4) newSize ctrlLen diffLen extraLen (int64 LE each), then one zlib stream
holding ctrl + diff + extra (the three bsdiff blocks, decompressed).
"""
import bz2, hashlib, json, os, struct, sys, zlib
import bsdiff4


def sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def offtin(b):
    y = int.from_bytes(b[:7], "little") | ((b[7] & 0x7F) << 56)
    return -y if b[7] & 0x80 else y


def convert(bs):
    assert bs[:8] == b"BSDIFF40"
    cl, dl, new_size = offtin(bs[8:16]), offtin(bs[16:24]), offtin(bs[24:32])
    ctrl = bz2.decompress(bs[32:32 + cl])
    diff = bz2.decompress(bs[32 + cl:32 + cl + dl])
    extra = bz2.decompress(bs[32 + cl + dl:])
    head = b"BLZ1" + struct.pack("<qqqq", new_size, len(ctrl), len(diff), len(extra))
    return head + zlib.compress(ctrl + diff + extra, 9)


def main():
    new_apk, new_code, out = sys.argv[1], int(sys.argv[2]), sys.argv[3]
    os.makedirs(out, exist_ok=True)
    meta = {"code": new_code, "sha256": sha(new_apk), "size": os.path.getsize(new_apk), "patches": []}
    new_bytes = open(new_apk, "rb").read()
    for spec in sys.argv[4:]:
        code, path = spec.split(":", 1)
        code = int(code)
        old_bytes = open(path, "rb").read()
        patch = convert(bsdiff4.diff(old_bytes, new_bytes))
        if len(patch) >= len(new_bytes) * 0.9:
            print(f"skip {code}: patch not worth it ({len(patch)} bytes)")
            continue
        name = f"patch-{code}-to-{new_code}.bin"
        with open(os.path.join(out, name), "wb") as f:
            f.write(patch)
        meta["patches"].append({"from": code, "fromSha": sha(path), "file": name, "size": len(patch)})
        print(f"{name}: {len(patch)} bytes (full {len(new_bytes)})")
    with open(os.path.join(out, "update.json"), "w") as f:
        json.dump(meta, f)


main()
