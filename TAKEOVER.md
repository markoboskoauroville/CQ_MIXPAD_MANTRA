# TAKEOVER: CQ MixPad (Mantra)

**State 8.11.2026, late night: v36, GitHub build green, installed and tested on the Pixel 7. Desk left as Embassy mix v2.**

## Where we stopped (8.11.2026, late night, background session)
- **All scales calibrated** (cq_mixpad_protocol "Calibrated scales"): frequency Hz = 4·2^(raw/4608) (PEQ and HPF, 15
  points, < 0.2 %), times µs = 2^(raw/1793.44) (gate attack/hold/release, comp attack/release, 15 points), comp ratio
  table 0..40 (1:1 … 40:1, Infinity), PEQ width table 0..25 (Shelf, 1.5 … 1/9 octave). Method: cq_mitm.py's new `c <hex>`
  line shows a value on CQ MixPad only (the desk never changes), read on MixPad running in the **Mantra Sandbox**
  (repo MANTRA_SANDBOX, command `sandbox`): no Mac mouse or keyboard used.
- **Toured:** Out1-6 / Main LR PEQ = block 28 (CH 30-35 / 38), Main comp = block 10 CH 38, limiter = block 29 (0C in,
  0D threshold, 0E fast/slow), ST IN / USB / BT processing = input blocks with CH 18 / 1C / 1E, stride 280 confirmed on
  all ten inputs (dump slots are indexed by CH number). Every test ended with a dump diff of zero.
- **App v36:** Recorder knows the scales; Sonnet's set_eq takes freq_hz and width_oct, new set_comp (threshold, ratio,
  gain, attack, release, on); INPUT SETTINGS NOW shows every band's frequency and each compressor. Tested on the Pixel:
  read-out matched the desk; a write (Ip7 ratio 2:1, LM 520 Hz) landed exactly (dump diff), then put back (diff zero).
- **Embassy mix v2** ("Embassy 7.11.2026 - RTA mix 30-45 min v2.wav", 15:00, peak −5.6 dBFS, no clipping, no dropouts;
  the 0.6 s quiet at 6:57 is in the concert and in v1 too). On Drive: gdrive:Santoshi band website/prints/ (MD5 checked).
  Moves (scripts tools/cq_solo.py, cq_kd.py, measured per channel alone on the Main): Tena Ip3 fader −6.2 → −4.2 (2 dB under
  Jadranka before: call and response now equal), Snoopy Ip7 −16.1 → −19.1 (he was 2 dB louder than Igor's guitar), Igor's
  guitar stays −10; tone on the **Main LR PEQ**: LF bell 70 Hz w 1.1 +7.5, LM 250 Hz w 1 −2.5, HM 1.6 kHz w 1.5 +1,
  HF 11 kHz w 1.3 −7. Main RTA − KD (desk units) at 30:00: 31 −1.0, 63 −2.4, 125 +0.4, 250 +1.4, 500 −0.4, 1k −1.1,
  2k −0.1, 4k −1.7, 8k +2.7, 16k +2.8 (v1: 63 −6.4, 8k +8.5, 16k +9.3). FFT of the files v2 vs v1: 63 Hz +5.0 dB,
  8k −5.7, 16k −9.3.
- **Back to the v1 desk** (only 14 words differ): Main PEQ gains 0 dB, freqs 63/220/2500/10000 Hz, widths 0.5 (index 16)
  on all four bands (F7 28 28 0C/0D/0E 38 <band> ...), Ip3 fader −6.2 dB (F7 06 06 0E 02 10 C9 79), Ip7 −16.1 dB
  (F7 06 06 0E 06 10 DE 6F). Private state files: captures/calib/baseline_20261108.bin (v1) and
  embassy_v2_state_20261108.bin (kept out of git: the dump holds Bluetooth pairing names).
- SD player: there is a take **CQ-MT007** after MT006 (not made by this session). A stop while stopped, or prev at a take's
  start, changes the selected take: tools/cq_play.py and cq_kd_select.py select CQ-MT006 by name.

## Pending for next time
1. Still not toured: Out GEQ, FBA (feedback assistant), FX inner tabs, meter type 24.
2. Ip1-10 back to Analogue on the desk before real mics (still on SD/USB for playback).
3. Swift repo CQ_MIXPAD_MAC waits for Marko's yes. Jadranka's Quick label "Vocal Low" (cosmetic).
4. Never toggle Stereo Link; one private-protocol client at a time.

---

**State 8.11.2026, night: v35, GitHub build green, installed on the Pixel 7. Repo public since 8.11.2026.**

## Where we stopped (8.11.2026)
- **Protocol tour done** (PROTOCOL.md "The full tour", and the public database repo cq_mixpad_protocol): tools/cq_mitm.py,
  cq_tour.py, cq_dump.py, cq_inject.sh, cq_live.py, cq_ctl.py, cq_scan.py. Every test ends with a cq_dump diff of zero.
- **App v28 to v35:** metering bridge in the fader grooves; master RTA on top, a channel's own RTA on tap; CUSTOM tab;
  long-press drag moves a fader (orange underline); IN panel with gain, trim, HPF and the 4 EQ bands read from the desk's
  state dump; pinch zoom and three-finger pan; big buttons; SD player and USB stick buttons (REC TO USB says why it cannot
  while the SD plays); Sonnet tools set_eq / set_hpf / set_trim (absolute values) with measured levels; test channel
  `run-as com.mantra.cqmixpad` files/command.txt in, files/answer.txt out.
- **Embassy 15-minute RTA mix** (minute 30 to 45 of CQ-MT006) printed to
  ~/Music/SantoshiKoncert/Embassy/"Embassy 7.11.2026 - RTA mix 30-45 min.wav" through the desk's USB audio
  (tools/print_embassy.sh), because the desk ignores USB-stick REC while the SD card plays.
- **The desk is left as mixed:** Ip1 muted, Jadranka trim -6, Main +5.8, EQ changes on the four channels. Scene 1
  "Santoshi Mix" is untouched: recall it to go back.
- The Swift Mac copy is in ~/Developer/CQ_MIXPAD_MAC, local only (no GitHub repo until Marko says yes).

## Pending for next time
1. **Calibration:** PEQ frequency, gate and comp times, ratio and width: only two points known for each. The HPF checks
   out at three points.
2. **Untoured parts:** Main LR EQ and comp, Out1-6 PEQ, Ip2-10 processing, ST IN / USB / BT, FX inner tabs, meters type 9
   and 24.
3. **Swift repo CQ_MIXPAD_MAC:** waits for Marko's yes (creating it unattended was blocked).
4. **Ip1-10 back to Analogue** on the desk before real mics (they are on the SD/USB source for playback).
5. Jadranka's (Ip2) Quick label says "Vocal Low" (was "Vocal High"; cosmetic, all sound values restored).
6. **Never toggle Stereo Link in a test:** it copies the odd channel onto the even one and unlinking does not undo it.
7. **One private-protocol client at a time:** CQ MixPad, our app, cq_mitm or a cq_*.py tool, never two together.

Note: commit bb6b0dd (print_embassy.sh check, cq_ctl next/prev, 8.11.2026) was pushed without the Co-Authored-By /
Claude-Session lines; it was written in session_01NceKU8PKmedvkKm89PySPN by Claude Opus 5.5.

---

## Earlier state

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
3. DONE 8.11.2026: the full tour (PROTOCOL.md "The full tour"); left out on purpose: +48 V, sample rate, scenes/Library recall, network. Next: build these messages into the app (EQ, gate, comp, HPF, names from Sonnet).
4. Desk meters (types 8/9) layout for the feedback guard's culprit and live meters.

**8.11.2026, 00:30 — the embassy video is rendered, upload tomorrow.** `~/Movies/SANTOSHI/render/Santoshi - Indian Embassy Zagreb 7.11.2026.mp4` (4.47 GB, 57:37, YouTube 1080p preset, camera sound only: Marko dropped the desk mix). The desk and camera were synced to the sample every 20 s (Ableton Live Actions v20, Sync Track 2 to Track 1); the synced WAVs are in `~/Movies/SANTOSHI/aligned/SYNC wav`, and the Live set "indijska ambasada 7-nov-21" holds the same sync as warp markers (not saved by me). Next: "upload the latest Santoshi concert" → channel Santoshi Kirtan Band, Unlisted (memory latest-santoshi-concert-upload).
