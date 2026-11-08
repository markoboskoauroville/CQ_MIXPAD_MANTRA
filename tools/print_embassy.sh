#!/bin/bash
# Prints 15 minutes of the SD take CQ-MT006 (Indian Embassy 7.11.2026, from minute 30) through the desk's mix to the Mac
# over USB audio (the USB stick cannot record while the SD plays: tested 8.11.2026). Whole take, no splicing.
OUT="$HOME/Music/SantoshiKoncert/Embassy/Embassy 7.11.2026 - RTA mix 30-45 min.wav"
cd "$(dirname "$0")/.."
python3 tools/cq_ctl.py 192.168.1.62 stop seek=1800 >/dev/null 2>&1; sleep 1
python3 tools/cq_ctl.py 192.168.1.62 play >/dev/null 2>&1
sox -q -t coreaudio "CQ12T - Audio" -b 24 -r 48000 -c 2 "$OUT" trim 0 900
python3 tools/cq_ctl.py 192.168.1.62 stop >/dev/null 2>&1
echo "printed: $OUT"
