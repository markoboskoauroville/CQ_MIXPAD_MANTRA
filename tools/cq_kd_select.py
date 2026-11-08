"""The SD player's take selection (shared by cq_kd.py and cq_levels.py, 8.11.2026)."""
import re, time
TAKE = "CQ-MT006"
def take(c):
    with c.lock: b = bytes(c.tcp); c.tcp = bytearray()
    n = re.findall(rb"CQ-MT\d{3}", b); return n[-1].decode() if n else None
def select(c, want=TAKE):
    """Move the selected take with next/prev until it is `want` (a stop while stopped, or prev at a take's start,
    changes the selection: learnt 8.11.2026)."""
    c.send("f7 11 11 10 04 ff 00 00 00"); time.sleep(1.2); t = take(c)
    for _ in range(8):
        if t == want: return True
        c.send("f7 11 11 10 0%d ff 00 00 00" % (0 if t and t > want else 4)); time.sleep(1.2); t = take(c) or t
    return t == want
