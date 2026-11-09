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

## Existing apps first (9.11.2026, task 59, Marko: "test different apps ... find somebody who already made a solution")

**Status at writing:** candidates researched and priced; no recording test done yet (every candidate needs Marko's tap on Install, or money). Results of the tests will be added below this section.

### Candidates

| App (package) | Price (Play Store, HR, 9.11.2026) | Claimed multichannel USB | Own USB driver? | Verdict for the test |
|---|---|---|---|---|
| FaderLog - USB DJ Set Recorder (`app.faderlog`, ex "Set Recorder Pro", dev TheSeriousBooty, forum.djtechtools.com post) | Free install, in-app purchases up to EUR 16.99 (do not buy) | Reads the device's UAC1/UAC2 descriptors, records each source as its own track; demo 12 ch from a Pioneer DJM-V10; recent notes add Allen & Heath Xone:DB4 / DB2 / DX / 4D "lossless straight from the mixer". Claims other class-compliant USB mixers should work | Yes: bypasses Android's USB host API and submits isochronous URBs on the usbfs fd (USBDEVFS_SUBMITURB / REAPURB). This is exactly the driver we would write | FIRST TEST, free. Closed source |
| USB Audio Recorder PRO (`com.extreamsd.usbaudiorecorderpro`, eXtream Software Development) | EUR 5.99, no free tier or trial found | "Multichannel recording", class-compliant UAC1/UAC2 devices, USB 1.1/2.0; a user reports it works with a Tascam US-16x08 but without a channel count | Yes (own USB audio driver, same vendor as AEM) | Needs Marko (EUR 5.99; spend gate says ASK MARKO) |
| Audio Evolution Mobile Studio (`com.extreamsd.aemobile`) | EUR 11.99, plus the custom USB audio driver in-app purchase EUR 3.99 (the stock Android USB path stays at 16-bit, high latency) | "Low latency, high quality multi-channel recording and playback", 250+ interfaces; a forum user sees 16 record tracks on a Steinberg UR824 but never confirmed the take | Yes, the EUR 3.99 add-on | Needs Marko (EUR 15.98 total) |
| n-Track Studio (`com.ntrack.studio.demo` free, subscription USD 1.49 / 2.99 / 5.99 per month; Pro `com.ntrack.studio.eight.pro` EUR 37.03) | see left | n-Track states up to 8 simultaneous inputs, iOS wording; no Android multichannel USB claim found | No (uses Android's stock path, so the same 2-channel result as our own app) | Not worth paying; skipped |
| UAPP / USB Audio Player PRO (`com.extreamsd.usbaudioplayerpro`) | EUR 9.49 | Playback only (own driver, bit-perfect) | Yes, but not a recorder | Not a recorder; could be used only to read what the driver logs |
| F-Droid | free | Index (72 MB v2 JSON) searched for multichannel / multitrack / USB audio / UAC2 / audio interface: no recorder (only SyncRecord = multi-phone, TuxGuitar = tabs, Tune Tangler) | - | Nothing to test on F-Droid |
| A1 Multitrack Record, M16 | - | up to 16 ch from class-compliant devices | - | iOS only |

### The standard and Android's level of support

- Standard: USB Audio Class 2.0 (clock source with SET_CUR sampling frequency, isochronous IN endpoint, subslot size 3 or 4 bytes). The CQ enumerates as a composite device with UAC2 interfaces (class 1, subclass 1 and 2, protocol 32 = UAC2 IP version) plus a MIDI streaming interface.
- Android documentation (source.android.com/docs/core/audio/usb): the framework path historically allows only mono or stereo in isochronous mode; AOSP has since gained index-mask multichannel for AAudio (commit "aaudio: fix multichannel legacy streams", e/OS mirror), and the Java AudioFormat reference tells multichannel AudioRecord sessions to use channel index masks. In practice on the Pixel 7 the audio HAL opens the CQ at 16 ch but publishes no profile above 2 channels (LESSONS 9.11.2026), so the Java and AAudio routes return 2 channels or zeros. The only app route to 16 channels is therefore a userspace driver over usbfs, which is what the commercial apps above do.

### What the Pixel itself shows (`adb shell dumpsys usb`, 9.11.2026)

Device `/dev/bus/usb/001/005`, vendor 8944 (0x22F0, Allen & Heath), product 41 (0x29), class 239/2/1 (IAD composite), name CQ12T, one configuration, no root needed. Interfaces: 0 Audio control (UAC2); 1 Audio Out with alt 0 (no endpoints) and alt 1 "Stereo Out" (iso OUT ep 0x01, max packet 1024, attributes 5 = isochronous asynchronous, plus iso feedback IN ep 0x81, 4 bytes, interval 4); 2 Audio In with alt 0 and alt 1 "Stereo In" (iso IN ep 0x82, max packet 1024, attributes 37 = isochronous asynchronous implicit-feedback data); 3 MIDI control; 4 MIDI streaming (bulk 0x83 / 0x03, 512). dumpsys lists only one non-zero alt per interface, and the connection log shows the desk re-enumerating alternately as product 41 and product 38 (probably the two Stream Modes; not yet confirmed which is Multitrack). A full descriptor dump (all alt settings, channel counts in the UAC2 AS descriptors) needs our own UsbManager code or `lsusb -v` on the Mac with the same desk; `/proc/asound` and `/sys/kernel/debug/usb` are not readable over adb shell on the Pixel (permission denied).

### Sniffer

usbmon / Wireshark capture on the phone needs root; the Pixel 7 is stock, so no software bus sniffer is possible there. Options: (1) plug the CQ into the Mac and read its descriptors and alt settings (`system_profiler SPUSBDataType`, `ioreg -p IOUSB -l`, or libusb `lsusb -v`), which gives channel counts per alt setting and the clock requests without any sniffer; (2) a hardware USB analyser (Total Phase Beagle USB 480, about USD 1,500; cheaper: Wireshark with a Linux laptop in between using usbmon with the CQ as a device on a Linux host, or a Raspberry Pi acting as a man-in-the-middle is not practical for high speed); (3) the more useful observation without a sniffer: logcat while FaderLog records (it logs endpoint and format on many builds) and `dumpsys usb` showing which alt setting is active. Reverse-engineering means observing those facts only; no code from the closed apps is copied.

### FaderLog test, 9.11.2026 11:17-11:19 (first result)

- Installed by Marko; compatibility check run on the Pixel (declined its anonymous reporting). USB permission "Only this time"; FaderLog got the CQ12T (step 1 passed).
- **Not a 16-channel test: the desk was in Stereo Stream Mode.** FaderLog's own log parsed the CQ's configuration descriptor (310 bytes, product id 41): only two audio streaming alt settings, both 2 channels (AS_GENERAL bNrChannels=2 on interface 1 and on interface 2; 24-bit in 4-byte subslots, 48 kHz only via clock source 1, iso IN ep 0x82 maxPkt 1024, bInterval 1, 6 frames per microframe). "compat channels: unavailable". Multitrack mode is a different USB personality (the connection log alternates product 41 and product 38), so the desk has to be switched by Marko or by the owner of the Stream Mode setting (private message F7 11 11 13 00 = Multitrack); this worker changed nothing on the desk.
- Signal: the SD playback command selected CQ-MT006 but all meters were silent (-110), so FaderLog also reported "No signal", the stream clock read 21.7 kHz against 48 kHz (a silent or stalled stream) and no PCM was written (0 bytes).
- What FaderLog does (observable): claims interface 2, sets alt 1, reads the clock with GET_RANGE, submits iso URBs on the usbfs fd and runs a reap loop; its engine is called UsbEngineCapture, with a MIDI monitor on interface 4 ep 0x83. Its log line "descriptor 2, geometry 42" shows it cross-checks the descriptor channel count against the measured packet size.
- Next: put the desk in Multitrack (Marko), have signal on SD/inputs, re-run; FaderLog should then show the channel count from the descriptor (expect 16).

### FaderLog second run, desk in Multitrack (PID 38), 9.11.2026 11:22-11:24

- With the desk switched to Multitrack (product id 38, same 310-byte configuration size), FaderLog's log shows: AS_GENERAL bNrChannels=16 on both iface 1 (out) and iface 2 (in), 24-bit in 4-byte subslots, `Capture thread start: sr=48000 ch=16 bits=32 fpMf=6 ep=0x82 bInterval=1`, `URBs submitted (packetBytes=1024 nominal=384)` (16 ch x 4 B x 6 frames = 384 B per microframe, max packet 1024), clock source 1 with GET_RANGE [48000]. It measured the stream at 46,720 Hz "carrying 15.6 ch, not 16", which means the iso IN endpoint delivers data volume of a 16-channel stream on the Pixel 7 without root.
- FaderLog's UI offers the 16 channels as eight stereo pairs (USB 1/2 ... 15/16, one pair per take in the free version; a "tracks" mode exists in the full app) with a live meter per pair.
- **Not yet proven:** a recording with real audio on all channels. The desk was silent (no SD playback signal) and the Bluetooth route to give it a signal is blocked: the Pixel's bond to CQ12T_2225 is stale on the desk side (the Pixel's system shows a "Pairing request" that needs the desk in pairing mode and Marko's tap). Next: signal on the desk (music, or SD), then record each pair and analyse.
- Conclusion so far: the 16-ch isochronous stream is reachable from an app by userspace usbfs on a stock Pixel 7. FaderLog is a working proof; our own driver is feasible, no root needed for that.
