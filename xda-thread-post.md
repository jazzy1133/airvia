# XDA Forums thread — Airvia announcement (draft, saved 2026-10-04)

Section: Android Apps and Games — https://xdaforums.com/f/android-apps-and-games.530/

## Title
[APP] Airvia — Stream all Android audio to HomePod (AirPlay 2), Chromecast & Sonos — free & open source

## Body
Hi all — I'm the developer of Airvia, a free and open-source Android app I built because I switched to Android but kept my HomePod, and there was no good way to play my phone's audio on it.

**What it does**
Airvia captures your phone's system audio (using Android's AudioPlaybackCapture, with the per-session consent Android requires) and streams it to your speakers:
• AirPlay 2 — real AP2 pairing and streaming, so HomePod (including stereo pairs) and Apple TV work, not just legacy AirPlay 1
• AirPlay 1 / RAOP fallback for older receivers
• Chromecast (Google Cast)
• Sonos / DLNA speakers

Whatever app you're playing in — your music player, podcast app, YouTube, browser — just plays, and the audio comes out of the speaker.

**Features**
• Multi-speaker playback with per-speaker volume (and volume memory)
• 5-band EQ + preamp, with presets
• Per-app capture — send only certain apps' audio
• Sleep timer and silence auto-stop
• Now-playing metadata sent to AirPlay receivers
• Phone stays silent while casting (its volume ducks to zero and restores when you stop)
• Quick Settings tile

**Honest limits**
• About 1–2 s of AirPlay buffering — great for music and podcasts, not for video lip-sync or gaming
• Apps can opt out of audio capture (some DRM apps do) — those stay silent; that's an Android platform rule every caster faces
• HomePods still need an Apple device for their one-time initial setup — after that, Android handles the day-to-day streaming

**Requirements**
• Android 10 or newer
• Speaker and phone on the same Wi-Fi network

**Download & source**
Free, no ads, no account, no trial — MIT licensed.
GitHub: https://github.com/jazzy1133/airvia
Latest release (v1.2.2): https://github.com/jazzy1133/airvia/releases/tag/v1.2.2

Contributors are welcome — issues with your speaker model and a log from the app's Log tab genuinely help, and PRs are reviewed with thanks.

Happy to answer questions and hear which speakers people test it with!

## Status
- POSTED 2026-10-04 11:15 AM EDT by forum user **jazzything11**: https://xdaforums.com/t/app-airvia-stream-all-android-audio-to-homepod-airplay-2-chromecast-sonos-free-open-source.4803834/
- XDA has TWO logins: the main site (xda-developers.com, display name "Jazzything") and the forums (xdaforums.com, username jazzything11) — the browser session holds both as of 2026-10-04.
