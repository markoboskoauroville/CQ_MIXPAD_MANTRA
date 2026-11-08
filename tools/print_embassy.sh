#!/bin/bash
# Prints 15 minutes of the SD take CQ-MT006 (Indian Embassy 7.11.2026, from minute 30) through the desk's mix to the Mac
# over USB audio (the USB stick cannot record while the SD plays: tested 8.11.2026). Whole take, no splicing.
# It first checks that the player really plays (input meters move) and stops if not: the desk lost its selected take
# once (take 2 of the print was silent, 8.11.2026; "next" from MT005 selects MT006 again).
OUT="${1:-$HOME/Music/SantoshiKoncert/Embassy/Embassy 7.11.2026 - RTA mix 30-45 min.wav}"
cd "$(dirname "$0")/.."
# select the take by name and play from minute 30 (a stop while stopped changes the selection: 8.11.2026)
python3 tools/cq_play.py 1800 || { echo "STOP: the desk plays nothing"; exit 1; }
sox -q --buffer 262144 -t coreaudio "CQ12T - Audio" -b 24 -r 48000 -c 2 "$OUT" trim 0 900
python3 tools/cq_ctl.py 192.168.1.62 stop >/dev/null 2>&1
echo "printed: $OUT"
