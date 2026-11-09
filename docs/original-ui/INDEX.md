# Original CQ MixPad 1.2.2: screens (9.11.2026)

Taken in the Mantra Sandbox (landscape 2400x1080) in the original app's Demo mode, so the desk was never touched.
Our CQ mode (app v41, `CqLook.kt` + `CqScreen` in MainActivity.kt) re-draws these in Compose: no A&H logo, icon or image is used.

| files | what it shows |
|---|---|
| 01 | start screen after Demo connect: top tabs CONFIG / PROCESSING / FADER / FX / HOME, left soft keys 1-3, Custom, five layers, right Main LR fader |
| 07-11 processing_* | PROCESSING: per strip PFL, gain knob, GATE, comp, PEQ, EQ curve, SENDS, small fader (layers IN 1-8, 9-16, ST.IN/FX, OUTPUTS, MGRP/DCA) |
| 12-16 fader_* | FADER: name, PFL, pan bar, fader with scale 10/0/10/20/40/Off, value, MUTE |
| 17-21 fx_*, 32_fx_* | FX: four FX units, INPUTS / CONTROL / PEQ / OUTPUTS editors (easyVERB etc.) |
| 02-06 config_*, 30_config_* | CONFIG: INPUTS (preamps, source, trim), OUTPUTS, AMM, CONTROL & NETWORK (soft keys, footswitch) |
| 22-26 home_*, 31_home_* | HOME: Quick Start, RECORD (stereo, multitrack), SCENES (list 1-10), DATA (shows, libraries), SYSTEM (info, storage, firmware, meters, preferences) |
| 33_processing_panel_* | the open panels: gain/source/HPF, gate, PEQ (4 bands), compressor, sends |

In our CQ mode: FADER and PROCESSING are drawn fully (strips, layers, Main fader, PFL = our RTA select, PROCESSING taps open our input panel),
HOME has Quick Start / RECORD (our recorder) / SCENES (long press recalls) / DATA / SYSTEM; CONFIG and FX are framed notes that point to MANTRA mode.
The power button opens the AI panel (RTA, KD match, Sonnet, log). Not yet drawn: FX editors, gate/comp panels, config pages, SD/USB pages.
