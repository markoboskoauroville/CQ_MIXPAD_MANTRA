# LESSONS

- **7.11.2026:** The CQ answers MIDI "get" only for channels it has: a get for every source tells the app which strips
  to show (CQ-12T: Ip1–10, ST1, USB, BT, FX1–2, Out1–6, DCA1–4).
- **7.11.2026:** Level parameters follow a pattern: to Out k = 0x2044 + 12·code + (k−1); to FX f = 0x2614 + 4·code +
  (f−1); pan to Out pair = 0x2844 + 12·code + 2·(pair−1); code = the source's Main-level LSB. Checked against every
  example in the PDF (CqTest).
- **7.11.2026:** Compose `pointerInput(Unit)` keeps the first lambda: a fader reused for another mix would keep sending
  to the old one. `rememberUpdatedState` for the callbacks.

- **7.11.2026:** The RTA was NOT in the packets I guessed (types 10/23/24 looked like meters with the RTA off and a muted
  input). It appeared only with MixPad's RTA switched on AND signal on Main: then type 23 words 312..342 lined up with
  test tones band by band. Test with a channel that is unmuted and routed (the USB channel), not Ip1.
- **7.11.2026:** Man-in-the-middle in local mode: MixPad's UDP port is taken by MixPad itself, so the proxy rewrites the
  port in MixPad's handshake to its own and forwards the desk's stream to MixPad. MixPad refuses 127.0.0.1 typed on the
  phone but accepts it on the Mac.
- **7.11.2026:** The Pixel's shell cannot run tcpdump; sniffing between two Wi-Fi devices is not possible from the Mac;
  only a proxy works.
- **8.11.2026 (protocol tour):** Stereo Link in Config › Inputs copies the odd channel onto the even one (EQ, HPF, fader,
  pan, sends, Quick label); unlinking does not give the even channel back. Never toggle it in a test. Jadran (Ip2) was
  restored by injecting its values from the first dump; only its Quick label still says "Vocal Low" (was "Vocal High").
- **8.11.2026:** MixPad's knobs answer only a slow horizontal drag (cliclick dd/dm/du), not the wheel or a fast drag.
  `macro capture-window` of MixPad returned a stale picture: use `macro capture-screen 2`.
- **8.11.2026:** The desk never echoes a change to its sender; check the truth in the connect dump (`tools/cq_dump.py`).
- **9.11.2026:** The sandbox copy of the app searches the LAN and could reach the desk: stop it (`sandbox stop`) before the Pixel app connects (one private client). A picture-driven redesign compiled on the first CI run; interpolating the 10 octave bands into 48 bars looks like the picture but shows silence as a hump, so draw the floor when the level is under -80 dB.
- **9.11.2026:** The original CQ MixPad has a Demo unit on its start screen: walk every screen without touching the desk. Kotlin: a property `uiMode` and a function `setUiMode` clash on the JVM (rename the function). Compose strips: fixed-height blocks eat the fader's weight(1f) in landscape (about 290 dp): budget the heights.
- **9.11.2026:** Same function, same place, in every layout (Marko). One UI-switch button at the top right in all layouts; every shared control (connection dot, VOICE/LOG/SAVE, AI panel) keeps its place; list the ones that cannot. The original CQ MixPad on the Pixel shows the desk's own names and channel colours (dump slot of CH, name at 384, colour u32 at 400) and layers of 5 on the CQ-12T; compare on the Pixel itself (force-stop the original before ours: one client). Never `git add -A` while another session works in the same tree.
- **9.11.2026 (task 54, reference + USB audio):** The Pixel 7's USB audio HAL (audio_hw_usb) opens the CQ-12T in Stream Mode Multitrack at 16 ch / 32-bit / 48 kHz itself, but logs "can't find valid ch for 16" and publishes no input profile with more than 2 channels: apps (AudioRecord, and AAudio/Oboe on the same HAL) get 2. Stream Mode = private message F7 11 11 13 00 (Multitrack) / 01 (Stereo); in the dump one byte, offset 38870: 1 Multitrack, 3 Stereo. Switching makes the phone re-enumerate the USB device (about 2 s).
- **9.11.2026:** Borrowed code: Google Oboe (github.com/google/oboe, Apache-2.0, 4.1k stars, commit 8c13e5e2552bdf9ccadb4211a48646e32b50eabd): FifoBuffer (src/fifo) and OboeTester's WaveFileWriter, ported to Kotlin in reference/OboePort.kt with the licence header; THIRD_PARTY/oboe-LICENSE.txt. adrielcafe/AndroidAudioRecorder (1.6k stars) has no licence: not used.
- **9.11.2026:** The desk's RTA and an FFT of real audio read the same music differently (Krishna Das: 31 Hz +7 dB, 63 Hz -2 dB on the desk). Bands.CAL31 = desk minus file for KD, measured both ways, converts one into the other; profiles are stored in the file measure. Kotlin in `run { }` cannot assign a class `val` (the first CI build of Analyzer failed): compute it in a function. pyloudnorm's RBJ K-weighting reads -3.27 LUFS on the BS.1770 test sine; libebur128's formulas read -3.01 at 48 and 44.1 kHz.
- **9.11.2026:** Two workers in one working tree: commit by explicit path only (the v43 commit swept in unfinished reference/ files and broke the build).
- **9.11.2026 (v45-v48):** With the CQ plugged into the Pixel, Android routes EVERY capture of the app to the USB input, including our own phone-mic RTA / feedback guard (an UNPROCESSED 1-ch session, seen in `dumpsys audio` RecordActivityMonitor). Two captures on the one USB input: the newer one read about 1 s of audio, then exact zeros (a 25 s stereo take: 1.2 s of music, then silence). The probe looked like "every source reads zeros" for the same reason. v48 pauses our mic capture while the USB recorder runs. Debug captures with `dumpsys audio | grep -A25 RecordActivityMonitor`.
