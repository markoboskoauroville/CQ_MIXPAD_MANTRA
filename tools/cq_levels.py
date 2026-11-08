#!/usr/bin/env python3
"""cq_levels.py SECONDS START [START2 ...]: per-input active level (90th and 50th percentile of the desk's input meters,
dB re nominal) over SECONDS from each START of take CQ-MT006, plus each input's Main fader and trim from the state dump,
so the post-fader balance can be judged on singing, not silence. 8.11.2026."""
import sys, time, struct, numpy as np
sys.path.insert(0, __file__.rsplit("/", 1)[0])
import cq_kd_select as sel
from cq_live import Live
sec = float(sys.argv[1]); starts = [float(a) for a in sys.argv[2:]] or [1800]
c = Live("192.168.1.62"); d = c.dump(5)
fad = [(struct.unpack_from("<H", d, 19916 + 168 * k)[0] - 0x8000) / 256 for k in range(10)]
trim = [(struct.unpack_from("<H", d, 656 + 280 * k)[0] - 0x8000) / 256 for k in range(10)]
if not sel.select(c): sys.exit("could not select CQ-MT006")
allrows = []
for st in starts:
    c.send("f7 11 11 10 06 " + int(st * 1000).to_bytes(4, "little").hex(" "), "f7 11 11 10 02 ff 00 00 00"); time.sleep(1.5)
    allrows.append(c.inputs(sec))
c.send("f7 11 11 10 01 ff 00 00 00"); c.close()
a = np.vstack(allrows)[:, :10]
for k in (1, 2, 5, 6):
    x = a[:, k]; act = x[x > -60]
    p90 = np.percentile(act, 90) if len(act) else -110; p50 = np.percentile(act, 50) if len(act) else -110
    print(f"Ip{k+1}: active {100*len(act)/len(x):3.0f}%  p90 {p90:6.1f}  p50 {p50:6.1f}  fader {fad[k]:+6.1f}  trim {trim[k]:+5.1f}  p90+fader {p90+fad[k]:6.1f}")
