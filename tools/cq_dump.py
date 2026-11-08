#!/usr/bin/env python3
"""cq_dump.py CAPTURE_DIR [CAPTURE_DIR2]: the desk's full state dump(s) (7F 04 <u32 len> ...) out of a cq_mitm capture.
With one dir: lists the dumps. With two: diffs the first dump of dir 1 against the last dump of dir 2, as 16-bit words
(offset, before, after). Built 8.11.2026 to check every value put back after the protocol tour."""
import struct, sys


def stream(cap):
    b = open(cap.rstrip("/") + "/tcp_d2c.bin", "rb").read(); out, i = bytearray(), 0
    while i + 12 <= len(b):
        t, l = struct.unpack(">qI", b[i:i + 12]); out += b[i + 12:i + 12 + l]; i += 12 + l
    return bytes(out)


def dumps(cap):
    s, res, i = stream(cap), [], 0
    while True:
        i = s.find(b"\x7f\x04", i)
        if i < 0: break
        n = struct.unpack("<I", s[i + 2:i + 6])[0]
        if 1000 < n < 200000 and i + 6 + n <= len(s): res.append(s[i + 6:i + 6 + n]); i += 6 + n
        else: i += 2
    return res


if __name__ == "__main__":
    a = dumps(sys.argv[1]); print(f"{sys.argv[1]}: {len(a)} dumps, sizes {[len(d) for d in a]}")
    if len(sys.argv) > 2:
        b = dumps(sys.argv[2]); print(f"{sys.argv[2]}: {len(b)} dumps"); x, y = a[0], b[-1]
        for o in range(0, min(len(x), len(y)) - 1, 2):
            if x[o:o + 2] != y[o:o + 2]: print(f"  off {o:6d} (0x{o:05x}): {x[o:o+2].hex(' ')} -> {y[o:o+2].hex(' ')}")
