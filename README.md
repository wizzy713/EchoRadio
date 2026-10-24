# EchoRadio

An Android app that acts as your personal radio DJ. Ask for an artist, a song or a mood, and EchoRadio builds a set, talks between songs like a real DJ, and plays everything through the Spotify app on your phone.

## Features

- **Understands requests.** Gemini works out whether you asked for a song ("Lucid Dreams"), an artist ("Jhené Aiko") or a vibe ("late-night slow burners"). A song plays straight away and the set continues in the same vibe; an artist set sticks to that artist.
- **Learns your taste.** Uses your Spotify top artists, top songs, Liked Songs and recent plays. ❤️ saves a song to Liked Songs and steers the DJ toward it; 👎 means never again.
- **Doesn't repeat itself.** Remembers the last 300 songs it played across restarts, and open-ended requests take a random angle each time.
- **Changes direction when you skip.** Three quick skips in a row switches to a different style (or similar artists, during an artist set).
- **A DJ voice.** Gemini's speech model, with a neural voice that runs entirely on the phone (Piper via sherpa-onnx) when Gemini is unavailable. The DJ talks every 3–4 songs, and an audio-reactive animation replaces the cover while it speaks.
- **Looks after the details.** Colours taken from each album cover, voice requests, themed shows, a weekly listening recap, a DJ alarm, a sleep timer, lock-screen controls and a 4×2 home-screen widget.

## How it works

| Part | File |
|---|---|
| Spotify login (PKCE), search, taste | `SpotifyWeb.kt` |
| Request understanding and set planning (Gemini) | `DjBrain.kt` |
| DJ voice: Gemini speech with on-phone fallback | `DjVoice.kt`, `OfflineVoice.kt` |
| Playback through the Spotify app, the DJ loop, UI | `MainActivity.kt` |
| Lock-screen controls and background playback | `PlaybackService.kt` |
| Home-screen widget | `EchoWidget.kt` |
| Album-cover colours | `AlbumColors.kt` |
| DJ alarm | `DjAlarm.kt` |

## Building it

You need Android Studio, a phone with Spotify installed, and **Spotify Premium**.

1. **Download the third-party pieces** (Spotify's SDK, the on-phone voice engine and voice model):
   ```bash
   ./setup.sh
   ```
2. **Register your own Spotify app** at [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard):
   - Redirect URI: `echoradio://callback`
   - APIs: **Web API** and **Android**
   - Android package `com.echoradio.app`, with the SHA-1 fingerprint of the key you sign with
   - Add your Spotify account under **User Management**
3. Put your app's Client ID in `DEFAULT_CLIENT_ID` in `app/src/main/java/com/echoradio/app/Models.kt`.
4. Open the project in Android Studio and run it on your phone.
5. In the app, open ⋮ → Settings and paste a free Gemini API key from [aistudio.google.com/apikey](https://aistudio.google.com/apikey). Without one, the app falls back to keyword search and simple DJ lines.

The on-phone voice is bundled for 64-bit ARM phones (`arm64-v8a`).

## Credits and licences

- EchoRadio's own code: [MIT](LICENSE).
- [Spotify Android SDK](https://github.com/spotify/android-sdk): downloaded by `setup.sh`, under Spotify's terms.
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache 2.0): `app/src/main/java/com/k2fsa/sherpa/onnx/Tts.kt` is its Kotlin interface, and `setup.sh` downloads its native libraries.
- Piper "Ryan" voice, from the [RyanSpeech](https://www.kaggle.com/datasets/roholazandie/ryanspeech) dataset: **CC BY-NC-SA 4.0**, so non-commercial use only. Swap the voice before using EchoRadio commercially.
- Song choices and DJ lines come from Google's Gemini API, used with your own key.
