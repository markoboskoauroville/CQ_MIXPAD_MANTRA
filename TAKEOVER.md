# TAKEOVER: CQ MixPad (Mantra)

**State 7.11.2026:** v1 written and pushed for the Santoshi concert at the Embassy tonight. Protocol proven on the real
CQ-12T by hand first (gets from the Pixel: Ip1 muted, Ip1 fader ≈0 dB, Main ≈ −7 dB).

**Install:** `gh release download v<N> -R markoboskoauroville/CQ_MIXPAD_MANTRA -p '*.apk'`, `adb install -r`.
**Key:** `adb shell run-as com.mantra.cqmixpad sh -c 'cat > files/anthropic.key' < ~/Downloads/API/Claude.api.txt` (never printed).

**Files:** Cq.kt (protocol, pure Kotlin), Link.kt (TCP, discovery, state), Brain.kt (Sonnet tools + prompt with the
Santoshi knowledge), Ears.kt (speech, RTA), Store.kt (labels, notes, concerts), MainActivity.kt (the desk).

**Next ideas:** a USB hub (Ethernet + the CQ's USB audio) would give a true RTA of the Main and of each channel instead
of the phone mic; MixPad's private protocol for EQ and names (needs a capture between MixPad and the CQ).
