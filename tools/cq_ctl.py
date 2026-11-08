#!/usr/bin/env python3
"""cq_ctl.py DESK_IP "f7 11 11 10 02 ff 00 00 00" ... : open the CQ's private session (TCP 51326) for a moment, send
the messages, close. The desk keeps playing/recording after we let go. Fails if another client (MixPad, our app)
holds the desk. Shortcuts: play, stop, seek=SECONDS, usbrec, usbstop. 8.11.2026."""
import socket, struct, sys, time
SC = {"play": "f7 11 11 10 02 ff 00 00 00", "stop": "f7 11 11 10 01 ff 00 00 00",
      "usbrec": "f7 11 11 0f 03 ff 00 00 00", "usbstop": "f7 11 11 0f 01 ff 00 00 00"}
ip = sys.argv[1]
u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); u.bind(("0.0.0.0", 0)); port = u.getsockname()[1]
f = lambda t, d=b"": bytes([0x7F, t]) + struct.pack("<I", len(d)) + d
s = socket.create_connection((ip, 51326), timeout=4)
s.sendall(f(0, bytes([port & 255, port >> 8]))); time.sleep(0.1); s.sendall(f(1)); time.sleep(0.1); s.sendall(f(12, b"\x02\x00")); time.sleep(0.8)
for a in sys.argv[2:]:
    if a.startswith("seek="): h = "f7 11 11 10 06 " + struct.pack("<I", int(float(a[5:]) * 1000)).hex(" ")
    else: h = SC.get(a, a)
    s.sendall(bytes.fromhex(h)); print("sent", h); time.sleep(0.4)
time.sleep(0.5); s.close()
