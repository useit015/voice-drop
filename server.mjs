#!/usr/bin/env node
// Local web UI: browse myinstants, pick a contact, send as a voice note.
import { spawn } from 'node:child_process'
import { createReadStream, existsSync, statSync } from 'node:fs'
import { createServer } from 'node:http'
import { dirname, extname, join, normalize } from 'node:path'
import { fileURLToPath } from 'node:url'
import QRCode from 'qrcode'
import { fetchSound, toVoiceNote } from './lib/audio.mjs'
import { assertSoundUrl, browse } from './lib/myinstants.mjs'
import * as prefs from './lib/prefs.mjs'
import * as sent from './lib/sent.mjs'
import * as store from './lib/store.mjs'
import { isJid, WA } from './lib/wa.mjs'

const PUBLIC = join(dirname(fileURLToPath(import.meta.url)), 'public')
const PORT = Number(process.env.PORT) || 4877
const HOSTS = new Set([`127.0.0.1:${PORT}`, `localhost:${PORT}`])
const HEX = /^#[0-9a-f]{6}$/i
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.svg': 'image/svg+xml', '.json': 'application/json', '.ico': 'image/x-icon',
}

const wa = await new WA().start()

// Voice notes sent before the message store existed: show them in their conversations too.
store.insertMany(sent.list().map((e) => ({
  jid: e.jid, id: e.id, fromMe: true, ts: e.at, kind: 'voice', text: null,
  meta: { seconds: e.seconds, wave: Buffer.from(e.waveform ?? []).toString('base64') || null, mime: 'audio/ogg; codecs=opus', size: 0 },
})))

class HttpError extends Error {
  constructor(status, message) { super(message); this.status = status }
}

const json = (res, status, body) => {
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' })
  res.end(JSON.stringify(body))
}

// This server can message people as you, so only the UI served from this very origin may call it.
// The Host check blocks DNS rebinding; the JSON content type + Origin check block cross-site form posts.
function guard(req) {
  if (!HOSTS.has(req.headers.host)) throw new HttpError(403, 'forbidden host')
  if (req.method === 'GET') return
  const origin = req.headers.origin
  if (origin && !HOSTS.has(new URL(origin).host)) throw new HttpError(403, 'forbidden origin')
  if (!(req.headers['content-type'] ?? '').startsWith('application/json')) throw new HttpError(415, 'json only')
}

async function body(req) {
  let size = 0
  const chunks = []
  for await (const c of req) {
    size += c.length
    if (size > 64 * 1024) throw new HttpError(413, 'body too large')
    chunks.push(c)
  }
  try { return JSON.parse(Buffer.concat(chunks).toString() || '{}') } catch { throw new HttpError(400, 'bad json') }
}

async function state() {
  return {
    status: wa.status,
    qr: wa.qr ? await QRCode.toDataURL(wa.qr, { margin: 1, width: 280 }) : null,
    me: wa.me,
    contacts: wa.contacts.size,
    groups: wa.groups.size,
    syncing: wa.syncing,
    history: wa.history,
    stored: store.count(),
  }
}

// Range-aware so Safari will play the preview.
function sendFile(req, res, file, type) {
  const { size } = statSync(file)
  const range = /^bytes=(\d*)-(\d*)$/.exec(req.headers.range ?? '')
  if (range) {
    const start = range[1] ? Number(range[1]) : Math.max(0, size - Number(range[2]))
    const end = range[1] && range[2] ? Math.min(Number(range[2]), size - 1) : size - 1
    if (start > end || start >= size) { res.writeHead(416, { 'content-range': `bytes */${size}` }); return res.end() }
    res.writeHead(206, { 'content-type': type, 'accept-ranges': 'bytes', 'content-length': end - start + 1, 'content-range': `bytes ${start}-${end}/${size}` })
    return createReadStream(file, { start, end }).pipe(res)
  }
  res.writeHead(200, { 'content-type': type, 'accept-ranges': 'bytes', 'content-length': size, 'cache-control': 'public, max-age=3600' })
  createReadStream(file).pipe(res)
}

// jid query params: "me" or a real JID, never anything else.
function jidParam(url, name = 'jid') {
  const raw = url.searchParams.get(name) ?? ''
  const jid = raw === 'me' ? wa.me?.jid : raw
  if (!jid || !isJid(jid)) throw new HttpError(400, 'bad jid')
  return jid
}

// Live updates for the open page: new, deleted and edited messages, history progress.
const listeners = new Set()
const push = (t, data = {}) => { const line = `data: ${JSON.stringify({ t, ...data })}\n\n`; for (const r of listeners) r.write(line) }
let historyTimer
wa.on('message', (msg) => push('msg', { msg }))
wa.on('revoke', (r) => push('revoke', r))
wa.on('edit', (e) => push('edit', e))
wa.on('history', () => { clearTimeout(historyTimer); historyTimer = setTimeout(() => push('history'), 800) })
wa.on('history-progress', (h) => push('progress', { history: h }))
setInterval(() => { for (const r of listeners) r.write(': ping\n\n') }, 25000).unref()

const routes = {
  'GET /api/events': async (req, res) => {
    res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-store', connection: 'keep-alive' })
    res.write(': ok\n\n')
    listeners.add(res)
    req.on('close', () => listeners.delete(res))
    return null
  },

  'GET /api/chats': async () => ({ chats: wa.chats() }),

  'GET /api/messages': async (_req, _res, url) => {
    const jid = jidParam(url)
    const before = url.searchParams.get('before')
    return store.page(jid, {
      ...(before ? { before: Number(before), beforeId: url.searchParams.get('beforeId') ?? '\uffff' } : {}),
      limit: Math.min(Number(url.searchParams.get('limit')) || 60, 200),
    })
  },

  'GET /api/media': async (req, res, url) => {
    const { file, type, name } = await wa.media(jidParam(url), String(url.searchParams.get('id') ?? '')).catch((e) => { throw new HttpError(404, e.message) })
    if (name) res.setHeader('content-disposition', `inline; filename*=UTF-8''${encodeURIComponent(name)}`)
    sendFile(req, res, file, type)
    return null
  },

  'POST /api/text': async (req) => {
    const { to, text } = await body(req)
    const t = String(text ?? '').replace(/\s+$/, '')
    if (!t) throw new HttpError(400, 'empty message')
    if (t.length > 4000) throw new HttpError(400, 'message is too long')
    if (wa.status !== 'open') throw new HttpError(503, 'Still connecting to WhatsApp, try again in a moment')
    let jid
    try { jid = await wa.resolveJid(to) } catch (e) { throw new HttpError(400, e.message) }
    return { ok: true, message: await wa.sendText(jid, t) }
  },

  'POST /api/history': async (req) => {
    const { jid } = await body(req)
    if (!isJid(jid)) throw new HttpError(400, 'bad jid')
    try { await wa.fetchHistory(jid) } catch (e) { throw new HttpError(409, e.message) }
    return { ok: true }
  },

  'GET /api/state': async () => state(),

  'GET /api/people': async () => ({ me: wa.me, people: wa.people() }),

  'GET /api/sounds': async (_req, _res, url) => browse({
    src: url.searchParams.get('src') ?? 'ma', q: url.searchParams.get('q') ?? '', page: url.searchParams.get('page') ?? 1,
  }),

  'GET /api/audio': async (req, res, url) => {
    const file = await fetchSound(assertSoundUrl(url.searchParams.get('u') ?? ''))
    sendFile(req, res, file, 'audio/mpeg')
    return null
  },

  'GET /api/sent': async () => ({ sent: sent.list() }),

  'GET /api/prefs': async () => prefs.get(),

  'POST /api/prefs': async (req) => {
    const b = await body(req)
    const next = {}
    if (Array.isArray(b.pinned)) next.pinned = b.pinned.filter((j) => typeof j === 'string' && isJid(j)).slice(0, 200)
    if (Array.isArray(b.favorites)) {
      next.favorites = b.favorites.slice(0, 500).map((f) => {
        try { return { title: String(f.title).slice(0, 200), mp3: assertSoundUrl(f.mp3), color: HEX.test(f.color) ? f.color : null } } catch { return null }
      }).filter(Boolean)
    }
    return prefs.set(next)
  },

  // Download + convert ahead of time so the send that follows is instant.
  'GET /api/warm': async (_req, _res, url) => {
    toVoiceNote({ url: assertSoundUrl(url.searchParams.get('u') ?? '') }).catch(() => {})
    return { ok: true }
  },

  'GET /api/avatar': async (req, res, url) => {
    const raw = url.searchParams.get('jid') ?? ''
    const jid = raw === 'me' ? wa.me?.jid : raw
    if (!jid || !isJid(jid)) throw new HttpError(400, 'bad jid')
    const file = await wa.avatar(jid)
    if (!file) throw new HttpError(404, 'no photo')
    sendFile(req, res, file, 'image/jpeg')
    return null
  },

  'POST /api/send': async (req) => {
    const { to, mp3, title, color } = await body(req)
    if (wa.status !== 'open') throw new HttpError(503, 'Still connecting to WhatsApp, try again in a moment')
    const note = await toVoiceNote({ url: assertSoundUrl(mp3) })
    let jid
    try { jid = await wa.resolveJid(to) } catch (e) { throw new HttpError(400, e.message) }
    const message = await wa.sendVoice(jid, note)
    const entry = sent.add({
      id: message.id, jid, title: String(title ?? '').slice(0, 200), mp3, seconds: note.seconds,
      waveform: Array.from(note.waveform), color: HEX.test(color) ? color : null, at: Date.now(),
    })
    return { ok: true, entry, message }
  },

  'POST /api/undo': async (req) => {
    const { jid, id } = await body(req)
    if (typeof id !== 'string' || !/^[\w-]{6,64}$/.test(id)) throw new HttpError(400, 'bad id')
    await wa.revoke(await wa.resolveJid(jid), id)
    sent.remove(id)
    return { ok: true }
  },

  'POST /api/people/refresh': async () => { wa.refreshContacts(); return { ok: true } },

  'POST /api/relink': async (req) => { const b = await body(req); await wa.relink({ history: b.history === true }); return { ok: true } },
}

const server = createServer(async (req, res) => {
  try {
    guard(req)
    const url = new URL(req.url, `http://${req.headers.host}`)
    const route = routes[`${req.method} ${url.pathname}`]
    if (route) {
      const out = await route(req, res, url)
      if (out !== null) json(res, 200, out)
      return
    }
    if (req.method !== 'GET' || url.pathname.startsWith('/api/')) throw new HttpError(404, 'not found')
    let file = normalize(join(PUBLIC, decodeURIComponent(url.pathname === '/' ? '/index.html' : url.pathname)))
    if (!file.startsWith(PUBLIC) || !existsSync(file) || !statSync(file).isFile()) throw new HttpError(404, 'not found')
    res.writeHead(200, { 'content-type': MIME[extname(file)] ?? 'application/octet-stream', 'cache-control': 'no-cache' })
    createReadStream(file).pipe(res)
  } catch (e) {
    if (res.headersSent) return res.end()
    const status = e.status ?? 500
    if (status === 500) console.error(e)
    json(res, status, { error: status === 500 ? e.message || 'server error' : e.message })
  }
})

server.listen(PORT, '127.0.0.1', () => {
  const url = `http://127.0.0.1:${PORT}`
  console.log(`Voice Drop running at ${url}`)
  if (!process.argv.includes('--no-open')) spawn('open', [url], { stdio: 'ignore', detached: true }).unref()
})

let stopping = false
for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, async () => {
    if (stopping) process.exit(1)
    stopping = true
    console.log('\nsaving session...')
    server.close()
    sent.flush()
    await wa.shutdown()
    process.exit(0)
  })
}
