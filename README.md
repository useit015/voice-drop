# Voice Drop

An Android WhatsApp client with extras: a sound board that sends [myinstants](https://www.myinstants.com) sounds as real voice notes, and GIF search. It works as a **linked device** (like WhatsApp Web), so your phone keeps WhatsApp and its notifications.

The original Node/web proof of concept lives in [`poc/`](poc/) (tag `poc-final`).

## What works

- Linking by phone-number code (main way) or QR code
- Chat list with pinned/archived/muted/unread state synced with your phone, filters, and search across names and messages
- Conversations:
  - text, photos, videos, GIFs, stickers, voice notes, audio, documents
  - read-only display of locations, contacts and polls
  - replies (swipe or long-press), reactions, edit, delete for me/everyone, star, copy
  - receipts, typing/recording and online/last-seen indicators
  - loading older history from the phone as you scroll up
- Voice notes:
  - recorded in the app: hold the mic to record, slide left to cancel, slide up to lock
  - played back with a waveform, seeking and 1×/1.5×/2× speed
- **Sounds tab**:
  - browse Morocco trending, Trending, All-time and New, or search myinstants, with favorites and recents
  - tap a sound to send it as a voice note, long-press to preview it
- **GIFs tab**: GIPHY trending and search, sent as looping MP4 like WhatsApp's own GIFs
- Attachments from the gallery, camera or files
- Media viewer with pinch zoom

Not supported: calls (no unofficial client can do them), status/stories, and notifications (the official app keeps those).

This is an unofficial client. Sending many messages quickly can get a number banned.

## Layout

```
engine/   Go: whatsmeow behind a small JSON bridge, bound for Android with gomobile
app/      Gradle Kotlin Multiplatform project (Android target for now)
  shared/       Room database, repository, Compose UI, myinstants + GIPHY clients
                androidMain: Opus/OGG encoder (MediaCodec), ExoPlayer, recorder, pickers
  androidApp/   Application, MainActivity, engine bridge, background sync
poc/      archived Node proof of concept
```

The engine keeps only the WhatsApp session (`files/wa/session.db`). The engine sends every message, receipt, chat setting and contact to the app as an event, and the app stores them in Room. The UI reads only from Room.

Connection policy:
- The app connects while it is open.
- It drops the connection a minute after it goes to the background.
- A WorkManager job reconnects briefly every 15 minutes, so offline messages are already stored when the app opens.

## Build

Requirements:
- Go 1.27+
- JDK 21
- Android SDK with platform 37, build-tools and an NDK
- gomobile: `go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init`

```bash
./engine/build.sh                      # -> app/libs/engine.aar
cd app && ./gradlew :androidApp:assembleDebug
```

`app/local.properties` needs:

```
sdk.dir=/path/to/Android/sdk
giphy.key=YOUR_GIPHY_API_KEY   # optional; without it the GIF tab explains how to add one
```

Run the tests:

```bash
cd engine && go test ./...
cd app && ./gradlew :shared:testAndroidHostTest
```

## Later

- Video clipping (trim and send as a video or GIF)
- Stickers
- Group and contact info screens
- iOS and desktop builds through Compose Multiplatform
