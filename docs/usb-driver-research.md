# USB driver research: 16-track capture from the CQ-12T on a Pixel 7

**Date:** 9.11.2026. **Question (Marko):** does a userspace USB audio driver for Android already exist, or do we build it; should we ask Allen & Heath first?

## Verdict

**Build it, but ask Allen & Heath first (the email costs nothing).** The searches (GitHub via `gh search repos`, web) found no open-source Android project doing UAC2 multichannel isochronous capture. Nothing specific to Allen & Heath on Android either. Search coverage was thin (GitHub search returned empty for several queries; web search only), so treat "none" as "none found".

## Existing code and references

| Candidate | What it is | Usefulness |
|---|---|---|
| libusb (libusb.info, LGPL-2.1) | Android build supports wrapping a UsbManager file descriptor (`libusb_wrap_sys_device`, `LIBUSB_OPTION_NO_DEVICE_DISCOVERY`) and isochronous transfers via usbfs | Core of the plan: claim interface, set alt setting, submit iso transfers, no root |
| Gist "libusb isochronous capture from an audio mixer" (github.com/grag38/a97335c08b68e59e4063) | Desktop libusb example: claim, alt setting, read iso packets | Pattern for the transfer loop only; not UAC2, not Android |
| Android UVC camera libraries over usbfs (libuvc based, e.g. saki4510t/UVCCamera) | Proves isochronous IN over UsbDeviceConnection/usbfs works on stock Android without root | Reference for the Android fd/permission plumbing and iso scheduling; video, not audio |
| Android docs "USB digital audio" (source.android.com/docs/core/audio/usb) | Framework supports only a subset of UAC1/2, channel count mono or stereo for apps | Explains why the HAL path gives zeros / 2-ch only |
| Stack Overflow "android usb isochronous data transfer" and "aoa2 usb isochronous audio" | Reports of iso working only when the kernel driver is detached; a HID interface on the same device can steal bandwidth | Warning: detach the snd-usb-audio driver from the audio interface and watch other interfaces |
| Commercial apps: UAPP, USB Audio Recorder PRO, n-Track, Audio Evolution Mobile | Closed source; they ship their own userspace USB drivers (libusb-like over usbfs) | Proof of feasibility; no code available |

Not found: any maintained GitHub project with UAC2 multichannel capture on Android, and anything A&H-specific (CQ, Qu, SQ, Avantis) on Android.

## Feasibility on the Pixel 7 (stock, no root)

- UsbManager gives an fd after the user grants permission; usbfs `USBDEVFS_SUBMITURB` with ISO URBs is available to apps (this is how UVC and the commercial audio apps work). No root needed.
- Must call `releaseInterface`/detach the kernel driver (libusb `libusb_detach_kernel_driver` / usbfs disconnect-claim) so the framework's snd-usb-audio stops using the AudioStreaming interface, and the AoC DSP offload route stops grabbing it. Side effect: the phone mic fallback and system audio routing for that device go away while we own it.
- Risks: usbfs URB memory limit (usbfs_memory_mb, default 16 MB; 16ch x 24-bit x 48 kHz = about 2.3 MB/s, fine), high-speed iso scheduling (microframe packets, 6 packets per 750 us), and keeping the cable awake. Not verified on the Pixel 7 itself; first step is a spike measuring that iso IN returns non-zero data.
- Data format to parse from the descriptors: UAC2 AS interface, 16 channels, subslot size 3 or 4 bytes, 48 kHz via clock source SET_CUR (UAC2 clock selector/ sampling rate control request).

## Effort estimate

- Spike (descriptor dump, claim, iso IN, confirm non-zero 16-ch samples): 1 to 2 days.
- Capture engine (libusb via JNI or pure Kotlin usbfs, ring buffer, 16 tracks to WAV/FLAC, dropout detection): 1 to 2 weeks.
- Integration into CQ MixPad (permission flow, service, UI, tests on hardware): about 1 week.
- Total: roughly 3 weeks, with a go/no-go after the spike.

## Allen & Heath

- Support: web portal and knowledgebase **https://support.allen-heath.com** (Zendesk style; there is a "submit a request" form). The contact page https://www.allen-heath.com/contact/ sends technical questions to the Technical Support Centre or the local distributor (it returned 403 to automated fetch, so no direct email address was verified). Head office: Allen & Heath Ltd, Kernick Industrial Estate, Penryn, Cornwall TR10 9LU, UK, +44 (0)1326 372070.
- Their CQ docs (CQ CONFIG screens, support.allen-heath.com/hc/en-gb/articles/48456934886545): USB-B Stream Mode: Stereo is for phones and tablets that do not support multichannel USB audio; Multitrack is for multichannel recording to and from a DAW. Spec lists ASIO/WDM drivers for Windows and Core Audio compliance for Mac; "class compliant" is not stated by A&H in anything found. No Android driver, SDK or partner programme found. The CQ MIDI protocol PDF is public (already in docs/); the TCP/UDP control protocol is not documented publicly as far as found.
- Fallback that works today: Stream Mode Stereo over USB, or 16-track recording to the SD card (virtual soundcheck).

## Email draft (to the support form; saved as a Gmail draft to Marko)

**Subject:** CQ-12T USB multitrack on Android: driver, SDK or descriptor documentation?

Hello Allen & Heath support,

I am a musician (Santoshi kirtan band, Croatia) and I own a CQ-12T. I am building my own companion app for live mixing on Android.

I would like to record all 16 tracks from the CQ-12T over USB-B (Stream Mode Multitrack, 16x16 at 48 kHz) on an Android phone. At the moment the phone opens the device at 16 channels but the app receives silence, and 2- or 4-channel requests fall back to the phone microphone. Could you please help with these questions:

1. Is the CQ-12T's USB audio fully USB Audio Class 2 compliant for the 16x16 multitrack mode, and is it known to work on Android?
2. Is there an Android driver, an SDK or a partner programme, or documentation of the USB descriptors and the isochronous format?
3. Do you plan, or recommend, any app for multitrack recording to Android or iOS?
4. Is the TCP/UDP control protocol used by CQ-MixPad documented for developers, in addition to the MIDI protocol PDF?

Thank you very much for your time.

Kind regards,
Marko Bosko
Croatia
