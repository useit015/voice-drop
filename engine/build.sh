#!/usr/bin/env bash
# Builds app/libs/engine.aar (Java package app.voicedrop.go.engine) for arm64 devices and the arm64/x86_64 emulators.
set -euo pipefail
cd "$(dirname "$0")"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  ANDROID_NDK_HOME="$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -1)"
  export ANDROID_NDK_HOME
fi
if [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/opt/openjdk@21 ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
fi
export PATH="$(go env GOPATH)/bin:$PATH"

mkdir -p ../app/libs
gomobile bind \
  -target=android/arm64,android/amd64 \
  -androidapi 29 \
  -javapkg app.voicedrop.go \
  -ldflags="-s -w" \
  -o ../app/libs/engine.aar \
  .
echo "built app/libs/engine.aar"
