# Voice Drop

Browse myinstants.com sounds and send them to WhatsApp as real voice notes (mono Opus in OGG, push-to-talk, with duration and waveform), not as mp3 attachments.

Needs Node 20+ and ffmpeg (`brew install ffmpeg`). Run `npm install` once.

## Web UI

```bash
npm start
```

Opens http://127.0.0.1:4877. First run shows a QR code: WhatsApp > Linked devices > Link a device.

- Left: your chats, with profile photos and the last message. Search by name or number, filter All / Groups / Pinned, star to pin, type a full number to message someone new.
- Right: the full conversation (text, voice notes, photos, documents; incoming and outgoing, live), a message box, and a sound drawer. The waveform button next to the message box shows or hides the drawer.
- Send text with Enter (Shift+Enter for a new line). Hover a message you sent to delete it for everyone.
- Sounds: tap one to send it as a voice note, the play button previews it. Morocco trending by default, plus Favorites (star), Recent, Trending, All-time, New, and search across all of myinstants.
- Keys: just start typing (goes to the box you used last), arrows move the sound highlight, Enter sends, Shift+Enter previews, `Cmd+K` searches chats, `Esc` stops playback.
- Hovering a sound prepares it on the server, so the send that follows is instant.

### Message history

When you link the app, your phone sends your recent chats once (about a minute). From then on every message the app sees is stored in `data/messages.db`. Older messages are pulled from the phone on demand: scroll to the top of a chat, or open a thin one, and the app asks your phone for 50 more at a time. "Load everything" keeps asking until your phone has nothing older. Nothing needs re-linking, and the phone must be online for older messages.

Attachments older than a few weeks may no longer exist on WhatsApp's servers and show as not available. If linking or syncing seems stuck, `data/wa.log` (and http://127.0.0.1:4877/api/debug) lists the last connection and sync events.

The server listens on localhost only and rejects requests from other sites. Stop it with Ctrl-C and wait for "saving session" to finish; killing it mid-write can corrupt the WhatsApp session in `./auth`.

## CLI

```bash
node mi.mjs login            # link without the UI
node mi.mjs search bruh
node mi.mjs send q:bruh me
node mi.mjs pick --to 212612345678
```

Stop the UI before using the CLI: both share one WhatsApp session. `node mi.mjs` prints all commands.

## Files

- `auth/` WhatsApp session. `data/` synced contacts, messages, sent log, cached profile photos and attachments. `cache/` downloaded and converted sounds. All stay on this machine; delete `auth/` to unlink locally.
- Built on Baileys, an unofficial WhatsApp Web client. Fine for personal use; don't bulk-send, it can get a number banned.
