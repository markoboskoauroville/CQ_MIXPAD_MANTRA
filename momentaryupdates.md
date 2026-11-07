# Momentary updates (requests saved word for word)

## 7.11.2026

> Now let's start building. Application for For controlling CQ mixer. C q mix ped mantra.

> And its main feature is to Auto config auto configure mixer to my needs. And my needs are to mix. Concerts from Santoshi Band and then it needs to help me automatically set up mixer. Through voice commands. So I can just say guitar is on channel one and then it will load the Press from Library for guitar. And it will write a name of guitarist on the channel. And so on. So we need to make this thing work. Serving my purpose, mixing the band and also learning about mixing, how we go to the concert to concert and slowly automatically write all the labels. Mixing levels, etc. It will be specialized for Santoshi mixing. We have today concert at Embassy and then we can start. So you need to link this. With API from cloud. Using Sonnet. To intelligently mix and control. My mixer and my mixes, and all the channels. And also, you know. Analyze the sound and make. Suggestions for corrections. Let's build that.

**Status:** v1 being built 7.11.2026 for tonight's concert at the Embassy.

> Actually, so user interface needs to have all these faders I can control and all the functions as CQ mixer, original Mixpad. But at the bottom there is entry field for sending commands to Sonnet and then Sonnet will adjust the mixer itself. And to really access the mixing, you cannot listen to it and you don't need to listen to it. There is RTA, real-time analyzer of everything. Inside the application itself, you can read real-time analyzer and you store the profile of Krishna Das music and then compare with that profile. And then we have it. I think that's already in GitHub somewhere. Santoshi mix we were doing with this profile and you just need to compare RTA with the profile of Krishna Das and then adjust. all the channels looking at RTA so everything fits and you need to adjust EQ so vocals are in the first plan and by the names of the channel I will tell you the name of the channel and the name of the persons so you will only write the name of the person but you know if I say backing vocal Diana that you write Diana on the channel But backing vocal is the actual channel function, and you need to adjust mixing according to this.

> 100dolara antropic . txt This is a file name for a tropic key. You need to copy this file to pixel and loading API key to the pixel. Also in the same folder, so this is all in downloads API folder. You need to find speechy. Apis. So when application speaks to me, it needs to speak through Speechify. So, I think that's a good Please. Get this API through wa uh wifi AD by debugging. Transfer them to pixel, load them in the application, and everything should work.

> The problem with mixer. And trying to control it through the mix pad on macOS is that mixer is now connected. To my phone. So internet cable going through the dongle to the phone. So everything you need to do, you need to do through ADB and that one app, which is now the hub for all the controlling and changing settings inside the mixer. If you cannot change some settings, in the pixel there is the original app for CQ Mix pad controller, and then you change it there, and then you learn how to change it with our app. Go and test it. Please now go and test it.

> Since the whole show was mixed with AI, everything, all the data is there. And build the skills as you go. Skill.md, skill for mixing concerts needs to be there.

> SD card is in the mixer. Format SD card and record a concert on SD card so you can play the concert live from SD card and then use the— our app to test it.

> You need to record the, the concert as much as you need, 10-15 minutes on the SD card, and don't use then anymore the Ableton Live. Play it from the SD card and test it.

> So idea is to have a test sound check already there, and now based on this you can prepare one template for all the concerts. And today there will not be all musicians, some things are changing, so you need to adapt to the new situation. And each concert needs to have its preset in the app, or we need to log in everything— logging, storing. So this first concert is Shubhich, and you need to put the date. Today is Indian Embassy, and you put the date because the channels are changing and things are changing from concert to concert. So some things are same, some are changing. So we need to also adapt our mixing.

> Every concert will be now stored on this SD card. There is enough space for the whole year, so we are just adding. We don't delete and don't format anymore, and you need to name the concerts based on location and date.

> So for these first concerts, we can pretend this is— this was live in Šubič, and the date you know. If you don't know the date, open the web page of, of our band and see it there in history.

**Status 7.11.2026:** v2 Speechify; v3 concerts (Šubić 17.10.2026 template, Indian Embassy 7.11.2026); SD card formatted once (it held 192 KB), 12 min of Šubić recording to it.

> Keep testing keep debugging go deep it must work for tonight , scene is saved on mixer so just recall it when i close the session

> use ears and voice of mac to test if needed using external mic and aadam audio

> for voice recognition usr assembly ai api key is in downloads api folder compy assembly ai keys to pixel seven

> look at ttt my keyboard app how to transcribe using AssemblyAI

**Status 7.11.2026:** v4 relative faders, always spoken; v5 spoken by name, RTA guard; v6 AssemblyAI Sync (TTT's way), 20 keys on the Pixel. Voice loop tested with ADAM + the Mac's mic + Whisper.

> mixer itself has rta use that instead of recording with mobile phone microphone

> next to prompt box add voice command icon when voice recording for commands is happening add pause button to stop it for a moment if needed , copy ui and vunmeter from ttt for voice inputs

**Status:** v9 mic icon beside the box, recording bar with TTT's VU meter, PAUSE/RESUME, ■ send, ✕ cancel; the mixer's own RTA under research (MixPad's private protocol, TCP 51326 + UDP meters, DigiMixer).

> original cq app shows rta in his interface maybe that can be work around

> build feedback detection in app to detect wich mic is causing it and use build in feedback suppressor on that individual channel

**Status:** v10 the desk's live meters over its private protocol (DigiMixer's handshake), working; v11 feedback guard: phone FFT finds a ringing tone, the desk meters name the mic, -3 dB in the ringing mix (max -9), spoken, orange step for the CQ's Feedback Assistant (it is per output, not per channel, and only MixPad reaches it).

> work harder on decoding cq rta

> add recoding display and trigger to start multichannel recording but only of cannel in use fir concert it ia verty important to not miss recoding of concert this concert today start around 17.30

> also information about upcoming concert are on our web page Santosh band as well as in repository wich creates pages

> to plan and give reminders of record if it is not recording app should trigger it automatically

> (the Embassy's programme: 16:30 arrival … 17:35–18:00 Performance by the Santoshi Kirtan band … 19:00 close)

**Status:** v15 REC panel (the desk's own state, F7 12 12 0D), START / STOP-with-confirm, auto REC 10 min before the concert (17:25 tonight), reminders, alerts every 2 min while not recording. Arming per track: in MixPad (not in any known protocol).

> please build in recording tab in app to monitor recorder

> add tab to hide and show certain channel from views as well as rearrange them in the view only

> when we record it is alway multichannel on sd card

**Status:** v19 the recorder decoded on the desk itself (REC/STOP and state, elapsed, card left, take names: no registration needed), REC tab, VIEW tab; the meter session off until decoded.
