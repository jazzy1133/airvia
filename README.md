# Airvia

**Stream all your Android audio to AirPlay 2 speakers — HomePod included.**

Airvia captures your phone's audio output (Spotify, YouTube Music, podcasts,
radio, almost any app) and sends it to your speakers over **real AirPlay 2**,
with the modern transient-pairing handshake current HomePods require.

Free and open source. No trial, no added noise, no account, no ads.

## Why Airvia?

Android-to-speaker casting apps have been around for years, but:

- **AirPlay 2 is the headline.** Airvia speaks the actual AirPlay 2 protocol
  (HAP transient pairing, encrypted channels, buffered ALAC audio) — the same
  sender stack that was verified packet-by-packet against pyatv and owntone
  and confirmed playing on a real HomePod mini.
- **Your volume buttons control the speaker — everywhere.** While casting,
  the side buttons adjust the *speaker's* volume, even from the lock screen
  or inside other apps (a dedicated remote-volume media session handles it).
  When the cast stops, the buttons are the phone's again.
- **You can see what's happening.** A live connection log (copy/share built
  in) shows the full pairing and streaming conversation — no black box.
- **It's free.** No 10-minute trial noise, no paywall.

## Features

- One-tap casting to discovered speakers (AirPlay NSD, DLNA/Sonos SSDP,
  Chromecast), or connect by IP
- Streams **all device audio** via Android's AudioPlaybackCapture API
  (Android 10+; no root) — or only the apps you pick (per-app capture)
- True AirPlay 2: transient HAP pairing (SRP), ChaCha20-Poly1305 channels,
  ALAC audio at 44.1 kHz, NTP timing + sync, retransmit handling — with a
  classic AirPlay 1 / RAOP fallback for older receivers; pick Auto, AirPlay 2 only or AirPlay 1 only in the Cast tab
- **Multi-speaker**: cast to several speakers at once; each speaker has
  its **own volume slider** and remembers its level
- **DLNA / Sonos and Chromecast** via the built-in local stream server
- **Kodi / LibreELEC**: cast straight to Kodi's built-in AirPlay receiver — including the quirks other casters trip on (confirmed working by a LibreELEC user)
- **5-band EQ + preamp** with presets, applied live
- **Now playing**: title, artist, album and artwork sent to the speaker
- Sleep timer, silence auto-stop, Quick Settings tile, reconnect card
- Foreground service with notification controls — keeps casting with the
  screen off; Stop from the notification
- Speaker volume from the app slider **and** the hardware buttons
  (foreground, background, lock screen)
- Live diagnostics log with copy/share

## Honest limits (Android platform rules — every caster has them)

- Apps can opt out of playback capture, and DRM-protected output is never
  captured; those stay silent. Voice-call audio is never captured.
- AirPlay buffers about a second or two of audio, so there's a delay —
  perfect for music and podcasts, not for lip-syncing video.
- Multi-speaker playback is near-synchronized (one shared capture,
  independent per-speaker pacing), not sample-exact.

## Requirements

- Android 10 (API 29) or newer
- Speaker and phone on the same Wi-Fi network

## Building

No Gradle — shell scripts only. Requirements:

- JDK 17, Kotlin 2.4.21 (kotlin-compiler-embeddable + Compose plugin):
  `scripts/setup_toolchain.sh` installs both into `~/airvia-toolchain`
  (macOS or Linux; override with `AIRVIA_TOOLCHAIN`)
- Android SDK with platform `android-36` and build-tools `36.0.0`
  (`ANDROID_HOME`, default `~/Library/Android/sdk`)
- A checkout path without spaces

```sh
scripts/setup_toolchain.sh   # first time only
./build.sh                   # APK lands in dist/Airvia.apk
```

The debug signing key is kept at `~/.airvia/debug.keystore` (override with
`AIRVIA_KEYSTORE`) so rebuilds stay installable over each other.


JVM self-tests for the crypto/protocol stack and the 48→44.1 kHz
resampler live in `tests/` and run on any JVM.

## How it works

1. `AudioPlaybackCapture` (with the per-session consent Android requires)
   taps the playback mix at 48 kHz.
2. A streaming resampler converts to 44.1 kHz; a ring buffer absorbs jitter.
3. The AirPlay 2 sender pairs transiently (no PIN, no stored keys), opens
   the encrypted control/event channels, and streams ALAC-framed PCM with
   timing sync packets sent from the advertised control socket — the detail
   that makes a HomePod actually schedule playout.

Protocol behaviour was cross-checked against the open-source references
[pyatv](https://pyatv.dev) and [owntone](https://owntone.github.io/owntone-server/).

## Contributing — developers welcome!

**Airvia is a public project, and developers are warmly welcome to join in
and make it better.** This app exists because people share what they figure
out — the AirPlay 2 sender here stands on the shoulders of open-source
references like pyatv and owntone.

Whether it's a bug fix, a new receiver protocol, better device
compatibility, UI polish, or just a good idea:

- Open an **issue** — bug reports (with your speaker model and a log from
  the app's Log tab) genuinely help, and feature ideas are welcome.
- Send a **pull request** — small or large, it will be reviewed with
  thanks. If you're planning something big, open an issue first so we can
  point you at the right part of the codebase.
- Help **test** on hardware you own — every speaker brand behaves a
  little differently, and reports from real devices keep the support list
  honest.

Fork it, build on it, ship improvements back — that's how a small free
project like this stays alive and keeps getting better for everyone.
No ads, no paywall, no account — just open source, kept open by the
people who use it and build it.

## Privacy

Airvia collects no personal data — no account, no ads, no analytics, no
crash reporting. Your audio stays on your own Wi-Fi network. See the
full [Privacy Policy](PRIVACY.md).

## License

MIT — see [LICENSE](LICENSE).
