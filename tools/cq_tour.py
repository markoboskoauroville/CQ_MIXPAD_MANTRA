#!/usr/bin/env python3
"""cq_tour.py [capture_dir] [--d2c] [--last N]: every MixPad->desk TCP message of a cq_mitm capture, each under the
action (actions.log) it followed, heartbeats left out. With --d2c the desk's TCP answers too (7F 03 dumps shortened).
Built 8.11.2026 for the full tour of MixPad's functions (Marko: "Go through every function of the mixer and log it")."""
import glob, struct, sys

av = sys.argv[1:]
args = [a for k, a in enumerate(av) if not a.startswith("--") and (k == 0 or av[k - 1] != "--last")]
cap = args[0] if args else sorted(glob.glob(__file__.rsplit("/tools/", 1)[0] + "/captures/2026*/"))[-1]
last = int(sys.argv[sys.argv.index("--last") + 1]) if "--last" in sys.argv else 0


def records(name):
    try: b = open(cap + "/" + name, "rb").read()
    except FileNotFoundError: return []
    out, i = [], 0
    while i + 12 <= len(b):
        t, l = struct.unpack(">qI", b[i:i + 12]); out.append((t, b[i + 12:i + 12 + l])); i += 12 + l
    return out


def split(d):
    """Split a TCP record into messages: F7 a a c ... / 7F c ...; a new message starts at F7 x x (x repeated)."""
    msgs, i, start = [], 0, 0
    while i < len(d):
        if i > start and d[i] == 0xF7 and i + 2 < len(d) and d[i + 1] == d[i + 2]:
            msgs.append(d[start:i]); start = i
        i += 1
    msgs.append(d[start:]); return [m for m in msgs if m]


acts = []
for line in open(cap + "/actions.log"):
    t, _, a = line.strip().partition(" "); acts.append((int(t), a))
ev = [(t, "c2d", m) for t, d in records("tcp_c2d.bin") for m in split(d)]
if "--d2c" in sys.argv: ev += [(t, "d2c", m) for t, d in records("tcp_d2c.bin") for m in split(d)]
ev = [e for e in ev if not (e[2][:2] in (b"\x7f\x00", b"\x7f\x05") or (e[1] == "d2c" and e[2][:2] == b"\x7f\x07"))]
ev.sort(key=lambda e: e[0])
blocks = [(0, "(connect)")] + acts
for k, (t0, a) in enumerate(blocks):
    if last and k < len(blocks) - last: continue
    t1 = blocks[k + 1][0] if k + 1 < len(blocks) else 1 << 62
    sel = [e for e in ev if t0 <= e[0] < t1]
    print(f"== {a}  ({len(sel)} msgs)")
    for t, w, m in sel[:60]:
        h = m.hex(" ") if len(m) <= 40 else m[:24].hex(" ") + f" ... ({len(m)} bytes)"
        print(f"  {(t - t0) / 1000:6.2f}s {w} {h}")
