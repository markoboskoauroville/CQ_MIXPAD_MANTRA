#!/usr/bin/env python3
"""cq_play.py START_S: select CQ-MT006 (next/prev by name, never a stop while stopped), seek, play, check the input
meters move, let go (the desk keeps playing). Exit 1 if nothing plays. 8.11.2026."""
import sys, time
sys.path.insert(0, __file__.rsplit("/", 1)[0]); from cq_live import Live; import cq_kd_select as sel
st = float(sys.argv[1]) if len(sys.argv) > 1 else 1800
c = Live("192.168.1.62")
ok = sel.select(c); print("selected", ok, sel.take(c))
c.send("f7 11 11 10 06 " + int(st * 1000).to_bytes(4, "little").hex(" "), "f7 11 11 10 02 ff 00 00 00"); time.sleep(3)
a = c.inputs(1.5); live = ok and len(a) > 0 and float(a.max()) > -60
c.send("f7 11 11 10 06 " + int(st * 1000).to_bytes(4, "little").hex(" ")); c.close()
print("playing" if live else "SILENT"); sys.exit(0 if live else 1)
