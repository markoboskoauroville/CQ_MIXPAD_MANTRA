> **Moved 8.11.2026:** the protocol is now a database of its own, public: https://github.com/markoboskoauroville/cq_mixpad_protocol (protocol.json + PROTOCOL.md + tools). New codes go there first; this file stays as the history of the decoding.

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



## The full tour (8.11.2026, 17:02–17:40; captures/20261108-170235 and -171122)

Method: `tools/cq_mitm.py 192.168.1.62 --local`, MixPad for Mac → Other… → 127.0.0.1, one click at a time logged in
actions.log (scratchpad mp.sh / kdrag.sh / vdrag.sh: knobs move by a slow horizontal drag, faders vertical), read with
`tools/cq_tour.py --last N`. Every value was put back: `tools/cq_inject.sh "<hex>"` sends a message on MixPad's own
session (port 51399 of cq_mitm), and `tools/cq_dump.py <first capture> <last capture>` diffs the desk's full state
(the 42 824-byte `7F 04` dump sent at every connect) against the state before the tour.

### Message form
`F7 BB BB PP CH SS VL VH` (8 bytes): BB = block (written twice), PP = parameter, CH = channel, SS = sub-index (band,
destination, FX parameter), V = u16 LE. Longer values: `7F 07 <u32 LE length> BB BB PP CH SS ...` (names, colours).
The desk does NOT echo a change to the client that sent it; a client sees the true state only in the connect dump.
Injected messages are taken exactly like MixPad's own (proved: HPF, gate, PEQ, sends, FX, names read back in the dump).

### Channel numbers (CH)
Ip1–Ip10 = 00–09 · ST IN = 0x18 · Out1–Out6 = 0x30–0x35 · Main LR = 0x38 · FX1 return = 0x3A (FX2 = 0x3B) ·
FX1 to LR = 0x20. Destinations (SS in block 06): Out1–6 = 08–0D, Main LR = 10, FX1 = 12 (FX2 = 13).

### Value scales
dB values: (raw − 0x8000)/256 (trim, gate threshold/depth, PEQ gain, comp threshold/gain, faders, sends, AMM weight).
Frequencies (HPF, PEQ): logarithmic, Hz = 82.5·10^((raw − 0x4E9B)/15316) (HPF 151 Hz = 0x5E56, 277 Hz = 0x6E12).
Pan: 0x00 (L) … 0x25 (centre) … 0x4A (R). Times (attack, hold, release): logarithmic u16. Ratio, PEQ width: index.

### Inputs
| function | message |
|---|---|
| source Analogue / SD-USB | F7 09 09 0D CH 01 00 17 / … CH 02 00 17 |
| analogue gain | F7 0A 0A 0C CH 01 <dB> |
| trim | F7 0A 0A 0F CH 00 <dB> |
| polarity (Ip1 / Ip4 views) | F7 0A 0A 10 CH 00 01 00 |
| HPF in / freq | F7 0C 0C 0C CH 00 01 00 / F7 0C 0C 0D CH 00 <freq> |
| gate in, threshold, depth, attack, release, hold | F7 0D 0D 0C / 0E / 0F / 10 / 11 / 12 |
| PEQ gain, freq, width, type, in | F7 0F 0F 0C / 0D / 0E / 0F CH <band 0–3> …, F7 0F 0F 10 CH 00 01 00 |
| PEQ band type | 00 bell · LF 06 shelf, 0B filter · HF 07 shelf, 0A filter |
| comp in, RMS/peak, threshold, ratio, gain, attack, release, knee | F7 10 10 0C / 0D / 0E / 0F / 10 / 11 / 12 / 13 |
| AutoGain on | F7 33 33 0C CH 00 01 00 |
| stereo link (DANGER: copies the odd channel onto the even one; unlinking does not undo it) | F7 01 01 0D <1/0> F0 00 00 |
| name | 7F 07 0E 00 00 00 23 23 0C 00 CH 00 00 <ASCII, 0-terminated> |
| colour | 7F 07 0F 00 00 00 23 23 0D 00 CH 00 00 <u32 colour> <u32> (grey 0, red 1; default 00 00 00 00 00 FF FF FF) |

### Mix (block 06)
| function | message |
|---|---|
| send/fader level | F7 06 06 0E CH <dest> <dB> (Main LR fader: CH 38 dest 10) |
| pan | F7 06 06 10 CH 10 <pan> |
| mute | F7 06 06 0C CH 00 01 00 |
| mute group n assign | F7 06 06 1B CH <n−1> 01 00 |
| DCA n assign | F7 06 06 1D CH <n−1> 01 00 |
| output sends pre/post fader | F7 06 06 20 00 <dest> 01/00 00 |
| output source post EQ / post comp | F7 06 06 12 <out> 00 00/01 00 |
| USB/SD source point (Post Preamp 0 … Post EQ 4 …) | F7 06 06 13 <point> 00 00 00 |
| PAFL | F7 17 17 0C CH 00 … · headphone level F7 17 17 24 00 00 <u16> |

### Outputs, FX, AMM, USB, SD
| function | message |
|---|---|
| output EQ type PEQ / GEQ | F7 00 04 0C <out> 01 01 / 00 00 |
| output delay | F7 1E 1E 0D <out> 00 <u16> |
| FX type (easyVERB: Vocal Classic 0 … Percussion 5) | F7 20 20 0C <fx 0/1> 09 <type> 00 |
| FX parameter | F7 20 20 0C <fx> <param> <value>: FX1 size 08; FX2 size 08, delay time 09, delay level 0A, echo level 0B, colour 0C, echo time 0E/0F |
| FX trails / FX Assist (Soften 13, Whisper 08, Clarity 0A, Level 01) | F7 20 20 0E <fx> 00 … / F7 20 20 10 <fx> 00 <id> 00 |
| FX mute / send master / to LR | F7 06 06 0C 3A 00 … / F7 06 06 0E 3A 10 / F7 06 06 0E 20 10 |
| AMM in, follow fader, channel on, weight | F7 2A 2A 0C 00 00 / 12 / 0E CH / 0F CH |
| USB stream mode Multitrack / Stereo | F7 11 11 13 00 … / F7 11 11 13 01 … |
| USB out patch | F7 09 09 0D <source> <side 07 L / 08 R> <slot 0x10 = USB 11 …> 14 |
| SD transport | F7 11 11 10 <00 previous, 01 stop, 02 play/pause, 04 next> FF 00 00 00 |
| SD arm mask | F7 11 11 14 <u32 mask LE> (bit 0 = track 1) |
| desk → app: take info | F7 12 12 0D … |

### Meters (UDP, desk → app, ~25/s)
Type 8 (646 bytes): input meters, 10 words per input starting at word 3 (Ip n = words 3+10(n−1) …); proved with the
SD playback of CQ-MT006: blocks of Ip1, 2, 3, 6, 7 moved, 4, 5, 9, 10 silent. Type 9 (814 bytes): output, Main and FX
meters (words 51–66, 299–302, 321–322, 359–372, 388–390), layout not split yet. Type 23: RTA (above).

### Dump layout (7F 04)
Ip channel stride 280 bytes (Ip1 HPF freq at 484, Ip2 at 764); Ip1's Main fader at 19916, pan 19918, FX1 send 19928;
Ip2's at 20084/20086/20096; USB out patch table at 38440 (4 bytes per slot: source u16, side, flag);
FX1 parameters at 40278 + 2·param, FX2 at 40448 + 2·param. Names are in each channel block as ASCII.

### Not toured
Left out on purpose (could hurt the desk or the sound): +48 V, sample rate (restarts audio), scene store/recall and
Library recall (overwrite the desk), network settings.

Not toured yet (real gaps, decoded from the dump layout only where noted):
- Main LR processing: its PEQ bands, its compressor (only the Main fader and pan were moved; Main LR PEQ is block 2F/0F RTA).
- Outputs 1–6 PEQ bands (type and in/out and delay toured; GEQ values not), output compressor if any.
- Channels Ip2–Ip10 processing: the same stride as Ip1 and Ip4, toured on Ip1 and Ip4 only (Ip2 by accident via the stereo link).
- ST IN, USB and Bluetooth channel processing (only their routing and source point were toured).
- FX1/FX2 inner PEQ, INPUTS and OUTPUTS tabs (only the CONTROL tab was toured).
- Gain Assistant "Auto Set" button, Input Library, custom layers (only the page was seen), soft keys, footswitch
  tip/ring assignments, Mute Groups for every input (one input and one group only), DCAs for every input.
- Meters: type 8 (inputs) mapped partly, type 9 (outputs, Main, FX) not split, type 24 not identified.

## Calibrated scales (8.11.2026, night) — supersedes "Times: logarithmic", "Ratio, PEQ width: index" and the two-point Hz formula
Method: cq_mitm.py's injector takes `c <hex>` = send the message to CQ MixPad only, as if the desk reported a change.
MixPad shows the value; the desk is never touched (the state dump diff before/after: zero differences). MixPad ran in the
Mantra Sandbox (headless Android emulator, github.com/markoboskoauroville/MANTRA_SANDBOX), never on the Mac's screen.

| scale | formula / table | points read |
|---|---|---|
| Frequency (PEQ, HPF) | **Hz = 4 · 2^(raw/4608)** (18·256 per octave); raw = 4608·log2(Hz/4) | 15 points 20 Hz…20.1 kHz, error < 0.2 % |
| Times (gate attack/release/hold, comp attack/release) | **µs = 2^(raw/1793.44)**; raw 0 shows 0.00 µs | 15 points 1 µs…99923 s |
| Comp ratio (10/0F, index) | 0 1 · 1 1.03 · 2 1.05 · 3 1.07 · 4 1.1 · 5 1.15 · 6 1.2 · 7 1.25 · 8 1.3 · 9 1.35 · 10 1.4 · 11 1.5 · 12 1.6 · 13 1.7 · 14 1.8 · 15 1.9 · 16 2 · 17 2.3 · 18 2.5 · 19 2.7 · 20 3 · 21 3.3 · 22 3.5 · 23 3.7 · 24 4 · 25 4.3 · 26 4.5 · 27 4.7 · 28 5 · 29 5.3 · 30 5.5 · 31 5.7 · 32 6 · 33 7 · 34 8 · 35 10 · 36 12 · 37 16 · 38 20 · 39 40 · 40 Infinity (:1) | all 41 |
| PEQ width (0F/0E, index, octaves) | 0 Shelf · 1 1.5 · 2 1.4 · 3 1.3 · 4 1.2 · 5 1.1 · 6 1 · 7 0.95 · 8 0.9 · 9 0.85 · 10 0.8 · 11 3/4 · 12 0.7 · 13 2/3 · 14 0.6 · 15 0.55 · 16 0.5 · 17 0.45 · 18 0.4 · 19 1/3 · 20 0.3 · 21 1/4 · 22 0.2 · 23 1/6 · 24 0.13 · 25 1/9 | all 26 |
| Gate threshold/depth, comp threshold/gain | dB = (raw − 0x8000)/256, raw 0 shows Off | 8 points |

Dump offsets per input k (stride 280, checked on all ten inputs): gate attack 488, release 490, hold 492, threshold 494,
depth 496 · PEQ band b gain 520+6b, freq 522+6b, width 524+6b · comp attack 572, release 574, threshold 576, gain 578,
ratio 580 (low byte). A shelf/filter band keeps another number in its width word (1808, 2840 seen).
