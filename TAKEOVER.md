# TAKEOVER: CQ MixPad (Mantra)

**State 7.11.2026, 10:25:** v3 on the Pixel (concerts, Speechify). Sonnet answered for real (Ip9 label). The CQ in Multitrack, Ip1–10 on SD/USB, the SD card formatted once and recording 12 min of Šubić. Skill: ~/.claude/skills/concert-mix.

**Earlier:** v1 written and pushed for the Santoshi concert at the Embassy tonight. Protocol proven on the real
CQ-12T by hand first (gets from the Pixel: Ip1 muted, Ip1 fader ≈0 dB, Main ≈ −7 dB).

**Install:** `gh release download v<N> -R markoboskoauroville/CQ_MIXPAD_MANTRA -p '*.apk'`, `adb install -r`.
**Keys** (never printed): `files/anthropic.key` from `~/Downloads/API/100dolara antropic.txt` (Claude.api.txt answers 401), `files/speechify.keys` = every `sk_` key of speechify_api.txt, one per line; both through `adb shell run-as com.mantra.cqmixpad sh -c 'cat > files/…'`.

**Files:** Cq.kt (protocol, pure Kotlin), Link.kt (TCP, discovery, state), Brain.kt (Sonnet tools + prompt with the
Santoshi knowledge), Ears.kt (speech, RTA), Store.kt (labels, notes, concerts), MainActivity.kt (the desk).

**Next ideas:** a USB hub (Ethernet + the CQ's USB audio) would give a true RTA of the Main and of each channel instead
of the phone mic; MixPad's private protocol for EQ and names (needs a capture between MixPad and the CQ).
