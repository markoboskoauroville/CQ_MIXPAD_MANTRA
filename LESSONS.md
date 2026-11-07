# LESSONS

- **7.11.2026:** The CQ answers MIDI "get" only for channels it has: a get for every source tells the app which strips
  to show (CQ-12T: Ip1–10, ST1, USB, BT, FX1–2, Out1–6, DCA1–4).
- **7.11.2026:** Level parameters follow a pattern: to Out k = 0x2044 + 12·code + (k−1); to FX f = 0x2614 + 4·code +
  (f−1); pan to Out pair = 0x2844 + 12·code + 2·(pair−1); code = the source's Main-level LSB. Checked against every
  example in the PDF (CqTest).
- **7.11.2026:** Compose `pointerInput(Unit)` keeps the first lambda: a fader reused for another mix would keep sending
  to the old one. `rememberUpdatedState` for the callbacks.
