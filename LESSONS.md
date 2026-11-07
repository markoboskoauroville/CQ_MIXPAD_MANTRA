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
