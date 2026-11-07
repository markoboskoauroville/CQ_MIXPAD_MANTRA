# TAKEOVER: CQ MixPad (Mantra)

**State 7.11.2026, 14:50 — v27, before the Indian Embassy concert.**

## What works (tested on the real CQ-12T, over the cable and over Wi-Fi at 192.168.1.102)
- **Desk** (TCP 51325, A&H MIDI): faders, sends OUT1–6/FX1–2, mutes, pans, masters, DCAs, scenes, soft keys; live sync.
- **Recorder** (TCP 51326, private, `Recorder.kt`): REC, STOP (confirm), state, take timer, card left, armed-track badges,
  auto-REC 10 min before the concert (17:25 for the Santoshi set at 17:35), reminders, alerts.
- **RTA of the mixer itself** (v27): UDP type 23 words 312..342 = 31 third-octave bands, read in the recorder's single
  session; feeds the RTA strip and MATCH KD (Krishna Das profile). Verified: 1 kHz tone → 1 kHz band.
- **Sonnet 5.5** commands (typed / AssemblyAI voice), names + roles per concert, Speechify voice, VIEW tab, feedback guard
  (announces only; the desk meters are not decoded, so it never cuts).

## Rules learnt the hard way
- The desk takes **one private-protocol client**: CQ MixPad open = our recorder and RTA refused. Keep MixPad closed.
- Network writes never on Android's main thread. Recall of Scene 1 resets Ip sources; a muted Ip1 gives a silent Main.
- Keys in the app's files over `adb shell run-as com.mantra.cqmixpad` (anthropic.key from "100dolara antropic.txt",
  speechify.keys, assemblyai.keys); never printed.

## Decoding more of the protocol
`tools/cq_mitm.py 192.168.1.102 --local`, CQ MixPad for Mac → Other… → 127.0.0.1, drive it with `macro do`, log each
action with its time in captures/<time>/actions.log, diff TCP c2d around it. Results go into PROTOCOL.md.

## Open (asked by Marko 7.11.2026)
1. A Swift app that does the sniffing (cq_mitm as a Mac app, with an action log and a diff view).
2. A macOS emulator of the CQ-12T (answers MixPad and our app like the desk) to keep developing without the mixer.
3. Go through every function of MixPad and log it (PEQ bands, gate, comp, FX, routing, names, scenes, meters 8/9/24).
4. Desk meters (types 8/9) layout for the feedback guard's culprit and live meters.

**8.11.2026, 00:30 — the embassy video is rendered, upload tomorrow.** `~/Movies/SANTOSHI/render/Santoshi - Indian Embassy Zagreb 7.11.2026.mp4` (4.47 GB, 57:37, YouTube 1080p preset, camera sound only: Marko dropped the desk mix). The desk and camera were synced to the sample every 20 s (Ableton Live Actions v20, Sync Track 2 to Track 1); the synced WAVs are in `~/Movies/SANTOSHI/aligned/SYNC wav`, and the Live set "indijska ambasada 7-nov-21" holds the same sync as warp markers (not saved by me). Next: "upload the latest Santoshi concert" → channel Santoshi Kirtan Band, Unlisted (memory latest-santoshi-concert-upload).
