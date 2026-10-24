#!/usr/bin/env bash
# Downloads the third-party pieces EchoRadio needs but doesn't ship in this repo:
#   - Spotify App Remote SDK (.aar)                      -> app/libs/
#   - sherpa-onnx native libraries for the on-phone voice -> app/src/main/jniLibs/arm64-v8a/
#   - Piper "Ryan" voice model (CC BY-NC-SA 4.0)          -> app/src/main/assets/tts/
set -euo pipefail
cd "$(dirname "$0")"

SHERPA_VERSION="v1.13.6"
SPOTIFY_AAR="spotify-app-remote-release-0.8.0.aar"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

echo "1/3  Spotify App Remote SDK"
mkdir -p app/libs
git clone -q --depth 1 https://github.com/spotify/android-sdk "$tmp/spotify"
cp "$tmp/spotify/app-remote-lib/$SPOTIFY_AAR" app/libs/

echo "2/3  sherpa-onnx $SHERPA_VERSION (on-phone voice engine)"
curl -fsSL -o "$tmp/sherpa.tar.bz2" \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/$SHERPA_VERSION/sherpa-onnx-$SHERPA_VERSION-android.tar.bz2"
mkdir -p "$tmp/sherpa" app/src/main/jniLibs/arm64-v8a
tar -xjf "$tmp/sherpa.tar.bz2" -C "$tmp/sherpa"
cp "$tmp/sherpa/jniLibs/arm64-v8a/libsherpa-onnx-jni.so" "$tmp/sherpa/jniLibs/arm64-v8a/libonnxruntime.so" \
   app/src/main/jniLibs/arm64-v8a/

echo "3/3  Piper 'Ryan' voice"
curl -fsSL -o "$tmp/ryan.tar.bz2" \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-ryan-medium.tar.bz2"
mkdir -p "$tmp/ryan" app/src/main/assets/tts
tar -xjf "$tmp/ryan.tar.bz2" -C "$tmp/ryan"
m="$tmp/ryan/vits-piper-en_US-ryan-medium"
cp "$m/en_US-ryan-medium.onnx" "$m/tokens.txt" "$m/MODEL_CARD" app/src/main/assets/tts/
mkdir -p app/src/main/assets/tts/espeak-ng-data
# English pronunciation data only (keeps the app ~17 MB smaller)
( cd "$m/espeak-ng-data" && cp -r en_dict intonations lang phondata phondata-manifest phonindex phontab voices \
    "$OLDPWD/app/src/main/assets/tts/espeak-ng-data/" )

echo "Done. Open the project in Android Studio and run it."
