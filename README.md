# CQ MixPad (Mantra)

**An Android app on the Pixel 7 that drives Marko's Allen & Heath CQ-12T over the cable, with Sonnet 5.5 as the mixing
assistant for the Santoshi band, and a live RTA compared with the Krishna Das reference profile.**
Marko, 7.11.2026: *"auto configure mixer to my needs ... mix concerts from Santoshi Band ... voice commands ... write the
name ... learning about mixing from concert to concert ... using Sonnet ... analyze the sound and make suggestions."*

## The cable
Pixel USB-C → USB-LAN dongle → LAN → CQ network port, with **Ethernet tethering ON** (repo CQ_ETHERNET_BRIDGE). The app
finds the mixer by itself on the cable's subnet (port 51325) and remembers the address.

## What it does
- **The desk**: a strip per channel the CQ really has (Ip1–10, ST1, USB, BT, FX1–2 returns on the 12T): fader, MUTE,
  pan (on MAIN), the person's name and the channel's role. Mix tabs MAIN, OUT1–6 (monitor sends), FX1–2 (effect sends);
  the mix master and DCA1–4. Every move on the desk or in CQ MixPad shows up here (the CQ reports changes).
- **Sonnet 5.5** (`claude-sonnet-5-5`, official anthropic-java SDK, server-side refusal fallback on): the line at the
  bottom (type) or SPEAK (voice). "Backing vocal Tajana on channel 4" → the channel shows *Tajana*, the role *backing vocal*
  decides how it is mixed. Commands are carried out at once, with limits in the code: no more than +6 dB at once.
- **RTA**: the phone's microphone, ten octave bands, each band's share of the whole: the measure of
  SANTOSHI_CONCERT_MIX `compare_ref.py`. Amber bars = the Krishna Das references, cyan = the room, numbers under = room
  minus reference. **MATCH KD** sends the averaged RTA to Sonnet; its moves wait for **APPLY**.
- **Concerts** (v3): the amber chip = this concert (place · date). Each concert keeps its own names, roles and mix; NEW
  CONCERT copies the one marked ▶ as its template; SEND ITS MIX TO THE DESK puts its faders, sends, pans and mutes on the
  CQ; SAVE stores the desk into it. Šubić 17.10.2026 (the recorded concert, its balance from SANTOSHI_CONCERT_MIX) and
  Indian Embassy 7.11.2026 are there from the start. Lessons (`remember`) are shared by all concerts.
- **Voice** (v2): every answer is spoken through Speechify (Beatrice, rendered to a file first, the next key when one is
  spent); Android's own voice only if every key fails. VOICE ON/OFF in the top bar.

## What the cable cannot do (the CQ's MIDI protocol V1.2)
EQ, gain, compressor, Library presets and the names on the desk itself are not in Allen & Heath's MIDI protocol; only
CQ MixPad's private protocol reaches them. Sonnet says those steps as orange lines ("Ip4: PEQ, −2 dB at 3 kHz").

## Build
GitHub Actions only (`.github/workflows/android.yml`): unit tests (every example of the protocol PDF, `CqTest.kt`), then
the APK, release v<N> Latest. Version in `android/gradle.properties`. The Anthropic key is put into the app's own files
from the Mac (`adb shell run-as com.mantra.cqmixpad`), never in the repository.

## The private protocol (TCP 51326 + UDP)
Recorder (REC/STOP/state/armed tracks) and the mixer's own **RTA** (31 third-octave bands) are decoded: see
PROTOCOL.md and `tools/cq_mitm.py` (the man-in-the-middle used to decode them). One client at a time: keep CQ MixPad
closed while this app records or reads the RTA.
