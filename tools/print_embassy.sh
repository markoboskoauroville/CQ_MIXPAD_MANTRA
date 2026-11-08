#!/bin/bash
# Prints 15 minutes of the SD take CQ-MT006 (Indian Embassy 7.11.2026, from minute 30) through the desk's mix to the Mac
# over USB audio (the USB stick cannot record while the SD plays: tested 8.11.2026). Whole take, no splicing.
# It first checks that the player really plays (input meters move) and stops if not: the desk lost its selected take
# once (take 2 of the print was silent, 8.11.2026; "next" from MT005 selects MT006 again).
OUT="${1:-$HOME/Music/SantoshiKoncert/Embassy/Embassy 7.11.2026 - RTA mix 30-45 min.wav}"
cd "$(dirname "$0")/.."
python3 tools/cq_ctl.py 192.168.1.62 stop seek=1800 play >/dev/null 2>&1; sleep 5
LIVE=$(python3 - <<'PY'
import sys; sys.path.insert(0,"tools"); from cq_live import Live
c=Live("192.168.1.62"); a=c.inputs(2.0); c.close()
print(int(len(a) > 0 and float(a.max()) > -60))
PY
)
if [ "$LIVE" != "1" ]; then echo "STOP: the desk plays nothing (select CQ-MT006 on the SD player first)"; python3 tools/cq_ctl.py 192.168.1.62 stop >/dev/null 2>&1; exit 1; fi
python3 tools/cq_ctl.py 192.168.1.62 stop seek=1800 play >/dev/null 2>&1
sox -q --buffer 262144 -t coreaudio "CQ12T - Audio" -b 24 -r 48000 -c 2 "$OUT" trim 0 900
python3 tools/cq_ctl.py 192.168.1.62 stop >/dev/null 2>&1
echo "printed: $OUT"
