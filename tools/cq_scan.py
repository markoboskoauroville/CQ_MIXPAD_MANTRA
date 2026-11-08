#!/usr/bin/env python3
"""cq_scan.py: play the selected SD take through a running cq_mitm.py, seek every STEP s, and read the input meters
(UDP type 8, 10 words per input from word 3) for each point: which mics are active where. 8.11.2026."""
import glob, os, socket, struct, sys, time
import numpy as np
STEP = int(sys.argv[1]) if len(sys.argv) > 1 else 180
END = int(sys.argv[2]) if len(sys.argv) > 2 else 3740
cap = sorted(glob.glob(os.path.dirname(os.path.abspath(__file__)) + "/../captures/2026*/"))[-1]
def inject(*hexes):
    s = socket.create_connection(("127.0.0.1", 51399)); s.sendall(("\n".join(hexes) + "\n").encode()); s.close()
def meters(t0, t1):
    b = open(cap + "udp_d2c.bin", "rb").read(); i = 0; rows = []
    while i < len(b):
        t, l = struct.unpack(">qI", b[i:i + 12]); d = b[i + 12:i + 12 + l]; i += 12 + l
        if t0 <= t < t1 and d[:2] == b"\x7f\x08" and len(d) >= 646:
            rows.append(np.frombuffer(d[:646], dtype="<u2").astype(float))
    return np.array(rows)
names = ["igor", "jadran", "tena", "MrdLow", "Djembe", "Guitar", "snoopy", "tajana", "SnpDI", "IgMIC"]
inject("f7 11 11 10 02 ff 00 00 00"); time.sleep(1)
print("time   " + " ".join(f"{n[:6]:>7}" for n in names))
for pos in range(30, END, STEP):
    inject("f7 11 11 10 06 " + struct.pack("<I", pos * 1000).hex(" ")); time.sleep(1.5)
    t0 = int(time.time() * 1000); time.sleep(2.5); M = meters(t0, int(time.time() * 1000))
    if not len(M): print(pos, "no meters"); continue
    lv = [M[:, 3 + 10 * k].max() for k in range(10)]
    print(f"{pos//60:3d}:{pos%60:02d} " + " ".join(f"{(v-0x8000)/256 if v else -99:7.1f}" for v in lv), flush=True)
inject("f7 11 11 10 01 ff 00 00 00", "f7 11 11 11 01 ff 00 00 00")
