# The CQ-12T's private protocol (decoded 7.11.2026, man-in-the-middle on the Mac: tools/cq_mitm.py)

Captures: ~/Developer/CQ_MIXPAD_MANTRA/captures/20261107-142825/ (tcp_c2d, tcp_d2c, udp_d2c, udp_c2d, find,
actions.log with the time of every click and tone). CQ MixPad on the Mac connected through "Other... 127.0.0.1".

## Discovery
UDP 51320: the app broadcasts "CQ Find"; the desk answers its name ("CQ12T_2225\0").

## Session (TCP 51326, one client at a time)
7F 00 02 00 00 00 <our UDP port LE> (handshake, repeated every 2 s as heartbeat) · 7F 01 (version) · 7F 0C 02 00 02 00
(start the stream) · desk answers 7F 00 … <its UDP port> (51324). UDP keep-alive 7F 05 every 2.5 s to the desk's port.

## Commands seen (MixPad → desk)
| what | bytes |
|---|---|
| Main LR PEQ: RTA on | F7 2F 2F 0F 00 38 01 00 |
| Ip1 Quick preset (Vocal Hi Pitch / Lo Pitch) | F7 00 04 0C 00 01 4E 00 / … 4F 00 |
| Ip1 Quick Comp on / off | F7 10 10 0C 00 00 01 00 / … 00 00 |
| SD multitrack REC | F7 11 11 11 03 FF 00 00 00 |
| SD multitrack STOP | F7 11 11 10 01 FF 00 00 00 + F7 11 11 11 01 FF 00 00 00 |
| full data dump (armed tracks: mask 100 bytes from its end) | 7F 03 |

## The RTA (UDP, desk → app)
Message type 23 (760 bytes, ~25/s): words 312..342 (u16 LE) = **31 one-third-octave bands, 20 Hz … 20 kHz**,
dB = (raw − 0x8000)/256, floor −110. Verified with tones on the USB channel: 100 Hz → band 7, 250 → 11, 1 k → 17,
4 k → 23, 10 k → 27. Two more 31-band arrays move with it: type 23 words 244..274 and type 9 words 224..254 (another
tap point, not identified yet). Types 8/9/10/24 also carry level meters (layout not fully mapped).

## Not yet mapped
PEQ band parameters, gate, compressor details, FX, output routing, names, scenes by the private protocol,
the other meter arrays. Method: tools/cq_mitm.py + an actions.log, one change at a time.
