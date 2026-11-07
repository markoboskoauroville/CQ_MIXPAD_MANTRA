#!/usr/bin/env python3
"""cq_mitm.py DESK_IP [PHONE_IP]: a man-in-the-middle between CQ MixPad and an Allen & Heath CQ, to decode its
private protocol (the RTA above all). Marko, 7.11.2026: "Man in the middle needs to be there. Go ahead ... ARTA we
need to decode, dead or alive." His own desk, his own phone, his own network.

CQ MixPad ("Other..." -> this Mac's IP) talks to this script as if it were the desk:
  UDP 51320  "CQ Find" -> relayed to the desk, the answer back (MixPad then connects to us)
  TCP 51326  control: relayed both ways; MixPad's handshake 7F 00 02 00 00 00 <udp port LE> tells which UDP port
             MixPad listens on; the desk's answer 7F 00 ... <port> tells the desk's UDP port.
  UDP        the desk streams meters (and, we hope, the RTA) to the TCP peer's IP = this Mac, on MixPad's port:
             we listen there and forward to the phone; MixPad's keep-alives to "the desk's" UDP port come to us and
             go on to the desk from the same socket.
Everything is written to captures/<time>/: tcp_c2d.bin, tcp_d2c.bin, udp_d2c.bin, udp_c2d.bin, find.bin, as records
[8-byte ms time][4-byte length][bytes]. events.log says what happened in words.
"""
import os, socket, struct, sys, threading, time

DESK = sys.argv[1] if len(sys.argv) > 1 else "192.168.1.102"
# --local: CQ MixPad runs on this same Mac ("Other..." -> 127.0.0.1). Its UDP port is taken by MixPad itself, so the
# proxy tells the desk its OWN port instead (rewriting MixPad's handshake) and forwards the desk's stream to MixPad.
LOCAL = "--local" in sys.argv
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "captures", time.strftime("%Y%m%d-%H%M%S"))
os.makedirs(OUT, exist_ok=True)
lock = threading.Lock()
files = {}
state = {"phone": None, "mp_udp": None, "desk_udp": None}


def rec(name, data):
    with lock:
        f = files.get(name) or open(os.path.join(OUT, name), "ab")
        files[name] = f
        f.write(struct.pack(">qI", int(time.time() * 1000), len(data)) + data); f.flush()


def log(msg):
    line = time.strftime("%H:%M:%S ") + msg
    print(line, flush=True)
    with open(os.path.join(OUT, "events.log"), "a") as f: f.write(line + "\n")


def finder():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("0.0.0.0", 51320))
    up = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); up.settimeout(1.5)
    while True:
        q, addr = s.recvfrom(4096)
        state["phone"] = addr[0]
        rec("find.bin", q)
        up.sendto(q, (DESK, 51320))
        try:
            a, _ = up.recvfrom(4096)
            s.sendto(a, addr); rec("find.bin", a)
            log(f"CQ Find from {addr} answered: {a[:20]!r}")
        except socket.timeout:
            log("the desk did not answer CQ Find")


def frames_port(b, want_type=0):
    """The UDP port in the first 7F 00 02 00 00 00 <lo> <hi> frame of b."""
    i = b.find(b"\x7f\x00\x02\x00\x00\x00")
    return (b[i + 6] | (b[i + 7] << 8)) if i >= 0 and i + 8 <= len(b) else None


def udp_bridge(mp_port):
    """Desk -> us on mp_port -> phone:mp_port; phone -> us on desk_udp -> desk:desk_udp (from the mp_port socket)."""
    a = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); a.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    a.bind(("0.0.0.0", mp_port))
    log(f"UDP: listening on {mp_port} for the desk's stream (MixPad's port)")

    def desk_to_phone():
        while True:
            d, addr = a.recvfrom(65535)
            if addr[0] == DESK:
                rec("udp_d2c.bin", d)
                if state["phone"]: a.sendto(d, (state["phone"], mp_port))
    threading.Thread(target=desk_to_phone, daemon=True).start()

    def phone_to_desk():
        while state["desk_udp"] is None: time.sleep(0.1)
        b = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); b.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        b.bind(("0.0.0.0", state["desk_udp"]))
        log(f"UDP: listening on {state['desk_udp']} for MixPad's keep-alives (the desk's port)")
        while True:
            d, addr = b.recvfrom(65535)
            rec("udp_c2d.bin", d)
            a.sendto(d, (DESK, state["desk_udp"]))
    threading.Thread(target=phone_to_desk, daemon=True).start()


def local_bridge(mp_port):
    """Local mode: the desk streams to our port Q; we forward to MixPad at 127.0.0.1:mp_port. MixPad's keep-alives to
    127.0.0.1:<desk udp> come to us and go on to the desk from Q."""
    a = state["q_sock"]
    def desk_to_mp():
        while True:
            d, addr = a.recvfrom(65535)
            if addr[0] == DESK:
                rec("udp_d2c.bin", d); a.sendto(d, ("127.0.0.1", mp_port))
    threading.Thread(target=desk_to_mp, daemon=True).start()
    def mp_to_desk():
        while state["desk_udp"] is None: time.sleep(0.1)
        b = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); b.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        b.bind(("127.0.0.1", state["desk_udp"]))
        log(f"UDP: listening on 127.0.0.1:{state['desk_udp']} for MixPad's keep-alives")
        while True:
            d, addr = b.recvfrom(65535)
            rec("udp_c2d.bin", d); a.sendto(d, (DESK, state["desk_udp"]))
    threading.Thread(target=mp_to_desk, daemon=True).start()


def rewrite_port(d):
    """Local mode: MixPad's '7F 00 02 00 00 00 <P>' (handshake and heartbeat) with our port Q instead."""
    if not LOCAL or state["mp_udp"] is None: return d
    p = state["mp_udp"]; q = state["q_sock"].getsockname()[1]
    return d.replace(b"\x7f\x00\x02\x00\x00\x00" + bytes([p & 0xFF, p >> 8]), b"\x7f\x00\x02\x00\x00\x00" + bytes([q & 0xFF, q >> 8]))


def pipe(src, dst, name, sniff, fix=lambda d: d):
    try:
        while True:
            d = src.recv(65536)
            if not d: break
            rec(name, d)
            sniff(d)
            dst.sendall(fix(d))
    except OSError:
        pass
    for s in (src, dst):
        try: s.shutdown(socket.SHUT_RDWR)
        except OSError: pass


def control():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", 51326)); srv.listen(4)
    while True:
        c, addr = srv.accept()
        state["phone"] = addr[0]
        log(f"TCP: CQ MixPad connected from {addr}")
        d = socket.create_connection((DESK, 51326), timeout=5); d.settimeout(None)
        log(f"TCP: connected to the desk {DESK}:51326")

        def c2d_sniff(b):
            if state["mp_udp"] is None:
                p = frames_port(b)
                if p:
                    state["mp_udp"] = p; log(f"MixPad's UDP port {p}")
                    if LOCAL:
                        q = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); q.bind(("0.0.0.0", 0)); state["q_sock"] = q
                        log(f"local mode: the desk is told our UDP port {q.getsockname()[1]}"); local_bridge(p)
                    else:
                        udp_bridge(p)

        def d2c_sniff(b):
            if state["desk_udp"] is None:
                p = frames_port(b)
                if p: state["desk_udp"] = p; log(f"the desk's UDP port {p}")

        threading.Thread(target=pipe, args=(c, d, "tcp_c2d.bin", c2d_sniff, rewrite_port), daemon=True).start()
        threading.Thread(target=pipe, args=(d, c, "tcp_d2c.bin", d2c_sniff), daemon=True).start()


if __name__ == "__main__":
    log(f"cq_mitm: desk {DESK}, capturing to {os.path.abspath(OUT)}")
    threading.Thread(target=finder, daemon=True).start()
    control()
