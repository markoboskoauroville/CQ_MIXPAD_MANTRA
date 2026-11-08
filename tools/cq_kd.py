#!/usr/bin/env python3
"""cq_kd.py [seconds] [start_s]: play the selected SD take from start_s (default 1800 = minute 30), read Main LR's own RTA
(31 third-octave bands) for N seconds and print the octave shares 31..16k against the Krishna Das target in desk units,
plus the input and Main meters. One private-protocol client at a time. 8.11.2026."""
import sys, time, numpy as np
sys.path.insert(0, __file__.rsplit("/", 1)[0]); from cq_live import Live
KD = np.array([-29.0, -13.8, -6.6, -7.5, -6.4, -6.3, -11.8, -15.7, -21.4, -30.8])
sec = float(sys.argv[1]) if len(sys.argv) > 1 else 20; start = float(sys.argv[2]) if len(sys.argv) > 2 else 1800
import re
TAKE = "CQ-MT006"
c = Live("192.168.1.62")
def take():
    with c.lock: b = bytes(c.tcp); c.tcp = bytearray()
    n = re.findall(rb"CQ-MT\d{3}", b); return n[-1].decode() if n else None
def select(want=TAKE):
    """The SD player's selected take, moved with next/prev until it is `want` (a stop while stopped, or prev at a
    take's start, changes the selection: learnt 8.11.2026)."""
    c.send("f7 11 11 10 04 ff 00 00 00"); time.sleep(1.2); t = take()
    for _ in range(8):
        if t == want: return True
        c.send("f7 11 11 10 0%d ff 00 00 00" % (0 if t and t > want else 4)); time.sleep(1.2); t = take() or t
    return t == want
if not select(): sys.exit("could not select " + TAKE)
c.send("f7 11 11 10 06 " + int(start * 1000).to_bytes(4, "little").hex(" "), "f7 11 11 10 02 ff 00 00 00")
c.send("f7 2f 2f 0f 00 38 01 00"); time.sleep(2)
fr = c._collect(23, sec)
rows = [np.array([(int.from_bytes(d[6 + 2 * (312 + k):8 + 2 * (312 + k)], "little") - 0x8000) / 256 for k in range(31)]) for d in fr if len(d) >= 692]
rows = [r for r in rows if r.max() > -80]
inp = c.inputs(2); out = c.outputs(2)
c.send("f7 11 11 10 01 ff 00 00 00"); c.close()
p = np.mean([10 ** (r / 10) for r in rows], axis=0)
octs = np.array([p[i - 1:i + 2].sum() for i in (2, 5, 8, 11, 14, 17, 20, 23, 26, 29)])
share = 10 * np.log10(octs / octs.sum())
print("frames", len(rows))
print("band   " + " ".join(f"{b:>6}" for b in ("31", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")))
print("mix    " + " ".join(f"{v:6.1f}" for v in share))
print("mix-KD " + " ".join(f"{v:+6.1f}" for v in share - KD))
if len(inp): print("inputs mean (Ip1..10):", " ".join(f"{v:.0f}" for v in inp.mean(0)[:10]), " max:", " ".join(f"{v:.0f}" for v in inp.max(0)[:10]))
if len(out): print("Main L/R mean", round(out.mean(0)[6], 1), round(out.mean(0)[7], 1), "max", round(out.max(0)[6], 1))
