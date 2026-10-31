// Turn a Baileys WAMessage into the flat row we store and show.
import { BufferJSON, normalizeMessageContent, proto } from '@whiskeysockets/baileys'

const PT = proto.Message.ProtocolMessage.Type
const MEDIA = new Set(['voice', 'audio', 'image', 'video', 'sticker', 'document'])

const num = (v) => (v == null ? 0 : typeof v === 'number' ? v : Number(v.toString()))
const b64 = (u8) => (u8 && u8.length ? Buffer.from(u8).toString('base64') : null)

// Returns a row, or { revoke } / { edit } for protocol messages, or null for things we do not show.
export function parseMessage(msg, norm) {
  const key = msg.key
  if (!key?.id || !key.remoteJid) return null
  const jid = norm(key.remoteJid, key.remoteJidAlt)
  if (!jid || jid === 'status@broadcast' || /@(newsletter|broadcast)$/.test(jid)) return null
  const m = normalizeMessageContent(msg.message)
  if (!m) return null

  if (m.protocolMessage) {
    const p = m.protocolMessage
    if (p.type === PT.REVOKE && p.key?.id) return { revoke: { jid, id: p.key.id } }
    if (p.type === PT.MESSAGE_EDIT && p.key?.id) {
      const e = normalizeMessageContent(p.editedMessage)
      const text = e?.conversation ?? e?.extendedTextMessage?.text
      return text != null ? { edit: { jid, id: p.key.id, text } } : null
    }
    return null
  }

  const row = {
    jid, id: key.id, fromMe: !!key.fromMe, ts: num(msg.messageTimestamp) * 1000 || Date.now(),
    sender: jid.endsWith('@g.us') && !key.fromMe ? norm(key.participant, key.participantAlt) : null,
    push: msg.pushName ?? null, kind: 'other', text: null, meta: null, raw: null,
  }

  if (m.conversation != null) { row.kind = 'text'; row.text = m.conversation }
  else if (m.extendedTextMessage) { row.kind = 'text'; row.text = m.extendedTextMessage.text ?? '' }
  else if (m.audioMessage) {
    const a = m.audioMessage
    row.kind = a.ptt ? 'voice' : 'audio'
    row.meta = { seconds: num(a.seconds), wave: b64(a.waveform), mime: a.mimetype ?? null, size: num(a.fileLength) }
  } else if (m.imageMessage) {
    const i = m.imageMessage
    row.kind = 'image'
    row.text = i.caption ?? null
    row.meta = { thumb: b64(i.jpegThumbnail), w: i.width ?? null, h: i.height ?? null, mime: i.mimetype ?? 'image/jpeg', size: num(i.fileLength) }
  } else if (m.videoMessage) {
    const v = m.videoMessage
    row.kind = 'video'
    row.text = v.caption ?? null
    row.meta = { thumb: b64(v.jpegThumbnail), w: v.width ?? null, h: v.height ?? null, seconds: num(v.seconds), mime: v.mimetype ?? 'video/mp4', size: num(v.fileLength) }
  } else if (m.stickerMessage) {
    const s = m.stickerMessage
    row.kind = 'sticker'
    row.meta = { w: s.width ?? null, h: s.height ?? null, mime: 'image/webp', size: num(s.fileLength) }
  } else if (m.documentMessage) {
    const d = m.documentMessage
    row.kind = 'document'
    row.text = d.caption ?? null
    row.meta = { name: d.fileName ?? 'document', mime: d.mimetype ?? 'application/octet-stream', size: num(d.fileLength), thumb: b64(d.jpegThumbnail) }
  } else if (m.contactMessage) { row.kind = 'contact'; row.text = m.contactMessage.displayName ?? 'Contact' }
  else if (m.contactsArrayMessage) { row.kind = 'contact'; row.text = m.contactsArrayMessage.displayName ?? 'Contacts' }
  else if (m.locationMessage || m.liveLocationMessage) { row.kind = 'location'; row.text = m.locationMessage?.name ?? 'Location' }
  else if (m.reactionMessage) return null
  else if (m.pollCreationMessage || m.pollCreationMessageV2 || m.pollCreationMessageV3) {
    row.kind = 'poll'
    row.text = (m.pollCreationMessage ?? m.pollCreationMessageV2 ?? m.pollCreationMessageV3).name ?? 'Poll'
  } else if (msg.messageStubType) return null

  if (MEDIA.has(row.kind)) row.raw = JSON.stringify(msg, BufferJSON.replacer)
  return row
}
