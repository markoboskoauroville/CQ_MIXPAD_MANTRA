#!/usr/bin/env python3
"""cq_solo.py SECONDS START: each live input of CQ-MT006 (Ip2, Ip3, Ip6, Ip7) alone on the Main over the same passage
(the other three muted for the moment), Main meter p90/p50 (dB re nominal) and its octave spectrum: what each player
really contributes to the mix. Mutes are put back as they were; the caller proves it with a dump diff. 8.11.2026."""
import sys, time, numpy as np
sys.path.insert(0, __file__.rsplit("/", 1)[0]); from cq_live import Live; import cq_kd_select as sel
sec = float(sys.argv[1]) if len(sys.argv) > 1 else 20; st = float(sys.argv[2]) if len(sys.argv) > 2 else 1800
LIVE = [1, 2, 5, 6]
mute = lambda c, k, on: c.send(f"f7 06 06 0c {k:02x} 00 {1 if on else 0:02x} 00")
c = Live("192.168.1.62")
if not sel.select(c): sys.exit("no take")
c.send("f7 2f 2f 0f 00 38 01 00")
out = {}
try:
    for k in LIVE:
        for j in LIVE: mute(c, j, j != k)
        c.send("f7 11 11 10 06 " + int(st * 1000).to_bytes(4, "little").hex(" "), "f7 11 11 10 02 ff 00 00 00"); time.sleep(1.2)
        t0 = time.time(); time.sleep(sec)
        with c.lock: fr = [d for t, d in c.frames if t >= t0]
        m = np.array([(int.from_bytes(d[6 + 16 * 6 + 14:6 + 16 * 6 + 16], "little") - 0x8000) / 256 for d in fr if d[1] == 9 and len(d) >= 134])
        r = [np.array([(int.from_bytes(d[6 + 2 * (312 + b):8 + 2 * (312 + b)], "little") - 0x8000) / 256 for b in range(31)]) for d in fr if d[1] == 23 and len(d) >= 692]
        p = np.mean([10 ** (x / 10) for x in r if x.max() > -80], axis=0)
        oc = [10 * np.log10(p[i - 1:i + 2].sum()) for i in (2, 5, 8, 11, 14, 17, 20, 23, 26, 29)]
        act = m[m > -60]
        out[k] = (np.percentile(act, 90), np.percentile(act, 50), 10 * np.log10(p.sum()), oc)
        c.send("f7 11 11 10 01 ff 00 00 00"); time.sleep(0.5); sel.select(c)
finally:
    for j in LIVE: mute(c, j, False)
    c.close()
names = {1: "Jadranka/Igor Ip2", 2: "Tena Ip3", 5: "Igor guitar Ip6", 6: "Snoopy Ip7"}
print("                     MainL p90   p50   RTA total |  31   63  125  250  500   1k   2k   4k   8k  16k")
for k, (a, b, tot, oc) in out.items():
    print(f"{names[k]:20s} {a:8.1f} {b:6.1f} {tot:9.1f}  | " + " ".join(f"{v:4.0f}" for v in oc))
