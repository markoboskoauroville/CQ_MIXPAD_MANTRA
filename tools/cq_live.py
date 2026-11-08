#!/usr/bin/env python3
"""cq_live.py: a private-protocol session to the CQ with its UDP stream, as a library (8.11.2026).
    c = Live("192.168.1.62"); c.send("f7 ..."); c.rta(seconds) -> mean 31-band dB; c.inputs(s) / c.outputs(s); c.close()
RTA = UDP type 23 words 312..342 (u16, dB = (raw-0x8000)/256); input meters type 8 (20 bytes per input, level at +14);
output meters type 9 (16 bytes per output, level at +14; 6/7 = Main L/R). One client at a time."""
import socket, struct, threading, time
import numpy as np
F = lambda t, d=b"": bytes([0x7F, t]) + struct.pack("<I", len(d)) + d
BANDS = [20, 25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800, 1000, 1250, 1600, 2000, 2500,
         3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000, 20000]
class Live:
    def __init__(self, ip):
        self.ip = ip; self.u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); self.u.bind(("0.0.0.0", 0)); self.u.settimeout(0.5)
        p = self.u.getsockname()[1]; self.hello = F(0, bytes([p & 255, p >> 8]))
        self.s = socket.create_connection((ip, 51326), timeout=4)
        self.s.sendall(self.hello); time.sleep(0.1); self.s.sendall(F(1)); time.sleep(0.1); self.s.sendall(F(12, b"\x02\x00"))
        self.frames = []; self.lock = threading.Lock(); self.run = True
        threading.Thread(target=self._udp, daemon=True).start(); threading.Thread(target=self._beat, daemon=True).start()
        threading.Thread(target=self._drain, daemon=True).start(); time.sleep(1)
    def _drain(self):
        self.s.settimeout(1); self.tcp = bytearray()
        while self.run:
            try:
                d = self.s.recv(65536)
                if d:
                    with self.lock: self.tcp += d
            except Exception: pass
    def dump(self, wait=3.0):
        """The desk's full state (7F 04 <u32 len> ...), asked with 7F 03."""
        with self.lock: self.tcp = bytearray()
        self.s.sendall(F(3)); time.sleep(wait)
        with self.lock: b = bytes(self.tcp)
        i = b.find(b"\x7f\x04")
        while i >= 0:
            n = struct.unpack("<I", b[i + 2:i + 6])[0]
            if 1000 < n < 200000 and i + 6 + n <= len(b): return b[i + 6:i + 6 + n]
            i = b.find(b"\x7f\x04", i + 2)
        return None
    def _beat(self):
        while self.run:
            try: self.s.sendall(self.hello); self.u.sendto(F(5), (self.ip, 51324))
            except Exception: pass
            time.sleep(2)
    def _udp(self):
        while self.run:
            try: d, a = self.u.recvfrom(65535)
            except Exception: continue
            with self.lock: self.frames.append((time.time(), d))
    def send(self, *hexes):
        for h in hexes: self.s.sendall(bytes.fromhex(h)); time.sleep(0.15)
    def _collect(self, typ, sec):
        t0 = time.time(); time.sleep(sec)
        with self.lock: fr = [d for t, d in self.frames if t >= t0 and d[1] == typ]; self.frames = self.frames[-2000:]
        return fr
    def rta(self, sec=3.0, drop=-80):
        rows = [np.array([(struct.unpack_from("<H", d, 6 + 2 * (312 + k))[0] - 0x8000) / 256 for k in range(31)])
                for d in self._collect(23, sec) if len(d) >= 6 + 2 * 343]
        rows = [r for r in rows if r.max() > drop]           # dropout frames out
        return np.mean(rows, axis=0) if rows else None
    def inputs(self, sec=2.0):
        fr = self._collect(8, sec); return np.array([[(struct.unpack_from("<H", d, 6 + 20 * k + 14)[0] - 0x8000) / 256 for k in range(16)] for d in fr if len(d) >= 646])
    def outputs(self, sec=2.0):
        fr = self._collect(9, sec); return np.array([[(struct.unpack_from("<H", d, 6 + 16 * k + 14)[0] - 0x8000) / 256 for k in range(8)] for d in fr if len(d) >= 6 + 128])
    def close(self): self.run = False; time.sleep(0.3); self.s.close()
