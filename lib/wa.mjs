// Long-lived WhatsApp connection (Baileys) with a persisted contact book and message store.
import { createHash } from 'node:crypto'
import { EventEmitter } from 'node:events'
import { appendFileSync, existsSync, mkdirSync, readFileSync, renameSync, rmSync, statSync, truncateSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import makeWASocket, { BufferJSON, DisconnectReason, downloadMediaMessage, fetchLatestBaileysVersion, proto, useMultiFileAuthState } from '@whiskeysockets/baileys'
import pino from 'pino'
import { run } from './audio.mjs'
import { parseMessage } from './messages.mjs'
import * as store from './store.mjs'
import { sleep, writeAtomic } from './util.mjs'

// libsignal prints whole session objects (private keys included) via console.info/warn, and a stack trace
// per undecryptable incoming message via console.error; drop those. We only send, so incoming decrypt noise is harmless.
for (const k of ['info', 'warn', 'error']) {
  const orig = console[k].bind(console)
  console[k] = (...a) => (typeof a[0] === 'string' && /session/i.test(a[0]) ? undefined : orig(...a))
}

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const AUTH_DIR = join(ROOT, 'auth')
const DATA_DIR = join(ROOT, 'data')
const CONTACTS_FILE = join(DATA_DIR, 'contacts.json')
const CONTACT_TTL = 12 * 60 * 60 * 1000
const APP_STATE = ['critical_block', 'critical_unblock_low', 'regular_high', 'regular_low', 'regular']
const JID_RE = /^(\d{6,20}@s\.whatsapp\.net|\d{6,20}@lid|[\d-]{10,40}@g\.us)$/

const AVATAR_DIR = join(DATA_DIR, 'avatars')
const MEDIA_DIR = join(DATA_DIR, 'media')
const LOG_FILE = join(DATA_DIR, 'wa.log')
const NO_AVATAR_TTL = 7 * 24 * 60 * 60 * 1000
const digits = (jid) => (jid ?? '').replace(/@.*/, '').replace(/\D/g, '')

export const isJid = (s) => JID_RE.test(s)

export class WA extends EventEmitter {
  status = 'connecting' // connecting | qr | open | logged-out
  qr = null
  sock = null
  contacts = new Map() // jid -> { jid, name, notify, lid }
  groups = new Map() // jid -> { jid, name, size }
  syncing = false
  syncedAt = 0
  history = { active: false, progress: 0, imported: 0 } // initial sync right after linking
  events = [] // recent connection/sync events, for diagnosing a stuck link
  lidMap = new Map() // lid -> phone jid
  #historyTimer = null
  #onDemand = Promise.resolve()
  #closing = false
  #pending = new Set()
  #last = Date.now()
  #saveTimer = null
  #opened = null
  #pairingPhone

  constructor({ pairingPhone } = {}) {
    super()
    this.#pairingPhone = pairingPhone
    try {
      const saved = JSON.parse(readFileSync(CONTACTS_FILE, 'utf8'))
      this.syncedAt = saved.syncedAt ?? 0
      for (const c of saved.contacts ?? []) { this.contacts.set(c.jid, c); if (c.lid && c.jid.endsWith('@s.whatsapp.net')) this.lidMap.set(c.lid, c.jid) }
    } catch { /* first run or unreadable: rebuilt from WhatsApp */ }
  }

  #log(event, detail = {}) {
    const line = { t: new Date().toISOString(), event, ...detail }
    this.events.push(line)
    if (this.events.length > 80) this.events.shift()
    try {
      mkdirSync(DATA_DIR, { recursive: true })
      if (existsSync(LOG_FILE) && statSync(LOG_FILE).size > 512 * 1024) truncateSync(LOG_FILE, 0)
      appendFileSync(LOG_FILE, `${JSON.stringify(line)}\n`)
    } catch { /* logging must never break the connection */ }
  }

  get me() {
    const id = this.sock?.user?.id
    return id ? { jid: id.replace(/:\d+@/, '@'), name: this.sock.user.name ?? null } : null
  }

  async start() {
    this.#closing = false
    this.#opened = new Promise((ok) => this.once('open', ok))
    this.#opened.catch(() => {})
    this.#connect()
    return this
  }

  ready() { return this.#opened }

  #setStatus(status) {
    this.status = status
    if (status !== 'qr') this.qr = null
    this.emit('status', status)
  }

  #track(p) {
    this.#last = Date.now()
    const q = p.catch(() => {}).finally(() => { this.#pending.delete(q); this.#last = Date.now() })
    this.#pending.add(q)
    return p
  }

  async #connect() {
    const { state, saveCreds } = await useMultiFileAuthState(AUTH_DIR)
    // Exiting mid-write leaves empty files in ./auth and loses the session, so track every write and drain before exit.
    const keysSet = state.keys.set.bind(state.keys)
    state.keys.set = (data) => this.#track(keysSet(data))
    const { version } = await fetchLatestBaileysVersion().catch(() => ({ version: undefined }))

    const sock = makeWASocket({
      version, auth: state, logger: pino({ level: 'silent' }), browser: ['MyInstants', 'Desktop', '1.0'],
      // Ask the phone for the quick "recent" sync only. The full dump is slow and Baileys' default ignores it anyway.
      markOnlineOnConnect: false, syncFullHistory: false,
    })
    this.sock = sock
    sock.ev.on('creds.update', () => { this.#track(saveCreds()) })
    sock.ev.on('contacts.upsert', (cs) => cs.forEach((c) => this.#upsert(c)))
    sock.ev.on('contacts.update', (cs) => cs.forEach((c) => this.#upsert(c)))
    sock.ev.on('messaging-history.set', ({ contacts, messages, chats, progress, syncType, peerDataRequestSessionId }) => {
      this.#last = Date.now()
      contacts?.forEach((c) => this.#upsert(c))
      const { fresh, jids } = this.#ingest(messages ?? [], false)
      const onDemand = syncType === proto.HistorySync.HistorySyncType.ON_DEMAND
      this.#log('history', { type: proto.HistorySync.HistorySyncType[syncType], progress, messages: messages?.length ?? 0, fresh, chats: chats?.length ?? 0, contacts: contacts?.length ?? 0 })
      if (onDemand) this.emit('ondemand', { session: peerDataRequestSessionId, added: fresh, jids: [...jids] })
      else if (messages?.length || progress != null) this.#bumpHistory(progress, fresh)
    })
    sock.ev.on('messages.upsert', ({ messages }) => { this.#last = Date.now(); this.#ingest(messages, true) })
    sock.ev.on('messages.delete', (d) => {
      for (const k of d.keys ?? []) {
        const jid = this.#norm(k.remoteJid, k.remoteJidAlt)
        store.remove(jid, k.id)
        this.emit('revoke', { jid, id: k.id })
      }
    })
    sock.ev.on('lid-mapping.update', (m) => { if (m?.lid && m?.pn) this.lidMap.set(m.lid, m.pn) })
    sock.ev.on('groups.upsert', () => this.#loadGroups())
    sock.ev.on('groups.update', () => this.#loadGroups())

    let asked = false
    sock.ev.on('connection.update', async ({ connection, lastDisconnect, qr }) => {
      if (connection || lastDisconnect) this.#log('connection', { connection, code: lastDisconnect?.error?.output?.statusCode, reason: lastDisconnect?.error?.message })
      if (qr && !state.creds.me) {
        if (this.#pairingPhone) {
          if (!asked) {
            asked = true
            this.emit('pairing-code', await sock.requestPairingCode(this.#pairingPhone))
          }
        } else {
          this.qr = qr
          this.#setStatus('qr')
          this.emit('qr', qr)
        }
      }
      if (connection === 'open') {
        if (!this.history.active && !state.creds.accountSyncCounter) this.#bumpHistory(0, 0)
        if (sock.user?.lid && this.me) this.lidMap.set(sock.user.lid.replace(/:\d+@/, '@'), this.me.jid)
        this.#setStatus('open')
        this.emit('open', this)
        this.#loadGroups()
        if (!this.contacts.size || Date.now() - this.syncedAt > CONTACT_TTL) this.refreshContacts()
      }
      if (connection === 'close') {
        if (this.sock !== sock || this.#closing) return
        const code = lastDisconnect?.error?.output?.statusCode
        if (code === DisconnectReason.loggedOut) {
          this.#setStatus('logged-out')
          return
        }
        // 515 = restart required right after pairing; anything else is transient.
        this.#setStatus('connecting')
        await sleep(code === DisconnectReason.restartRequired ? 0 : 2000)
        if (!this.#closing) this.#connect()
      }
    })
  }

  // Wipe the local session and show a fresh QR (only used when WhatsApp has unlinked this device).
  async relink() {
    this.#closing = true
    try { this.sock?.end(undefined) } catch { /* already closed */ }
    this.sock = null // late close events from the old socket must not trigger a reconnect
    rmSync(AUTH_DIR, { recursive: true, force: true })
    this.contacts.clear()
    this.groups.clear()
    this.syncedAt = 0
    this.#saveNow()
    this.history = { active: false, progress: 0, imported: 0 }
    this.#setStatus('connecting')
    this.#closing = false
    this.#connect()
  }

  // The first sync after linking has no clear "finished" signal, so it ends at 100% or after 30 quiet seconds.
  #bumpHistory(progress, added) {
    const prev = this.history
    const next = { active: true, progress: Math.max(prev.active ? prev.progress : 0, Math.round(progress ?? 0)), imported: (prev.active ? prev.imported : 0) + added }
    clearTimeout(this.#historyTimer)
    if (next.progress >= 100) next.active = false
    else this.#historyTimer = setTimeout(() => { this.history = { ...this.history, active: false }; this.emit('history-progress', this.history); this.emit('history', 0) }, 30000)
    this.history = next
    this.emit('history-progress', next)
  }

  // ---- contacts ----

  #upsert(c) {
    const phone = c.phoneNumber ?? (c.id?.endsWith('@s.whatsapp.net') ? c.id : null)
    const jid = phone ?? c.id
    if (!jid || !/@(s\.whatsapp\.net|lid)$/.test(jid)) return
    if (c.lid && jid.endsWith('@s.whatsapp.net')) this.lidMap.set(c.lid, jid)
    const old = this.contacts.get(jid) ?? { jid }
    const next = {
      jid,
      name: c.name ?? old.name ?? null,
      notify: c.notify ?? old.notify ?? null,
      lid: c.lid ?? (c.id?.endsWith('@lid') ? c.id : old.lid) ?? null,
    }
    if (JSON.stringify(old) === JSON.stringify(next)) return
    this.contacts.set(jid, next)
    this.#scheduleSave()
  }

  #scheduleSave() {
    clearTimeout(this.#saveTimer)
    this.#saveTimer = setTimeout(() => this.#saveNow(), 800)
  }

  #saveNow() {
    clearTimeout(this.#saveTimer)
    writeAtomic(CONTACTS_FILE, JSON.stringify({ syncedAt: this.syncedAt, contacts: [...this.contacts.values()] }))
  }

  async refreshContacts() {
    if (this.syncing || !this.sock) return
    this.syncing = true
    this.emit('contacts')
    try {
      // Without this, a resync only returns changes since the last one, not the whole address book.
      await this.sock.authState.keys.set({ 'app-state-sync-version': Object.fromEntries(APP_STATE.map((n) => [n, null])) })
      await this.sock.resyncAppState(APP_STATE, true)
      this.syncedAt = Date.now()
      this.#saveNow()
    } catch (e) {
      this.emit('error-soft', e)
    } finally {
      this.syncing = false
      this.emit('contacts')
    }
  }

  async #loadGroups() {
    try {
      const all = await this.sock.groupFetchAllParticipating()
      this.groups = new Map(Object.values(all).map((g) => [g.id, { jid: g.id, name: g.subject, size: g.participants?.length ?? 0 }]))
      this.emit('contacts')
    } catch { /* retried on next groups event or reconnect */ }
  }

  // Everything the UI can send to, minus yourself (that has its own entry).
  people() {
    const me = this.me?.jid
    const out = []
    for (const g of this.groups.values()) out.push({ jid: g.jid, kind: 'group', name: g.name, size: g.size })
    for (const c of this.contacts.values()) {
      if (c.jid === me) continue
      const phone = c.jid.endsWith('@s.whatsapp.net') ? digits(c.jid) : null
      const name = c.name ?? c.notify ?? null
      if (!name && !phone) continue
      out.push({ jid: c.jid, kind: 'contact', name, phone })
    }
    return out
  }

  // ---- profile photos ----

  // Cached on disk. Fetched one at a time with a small gap, the way WhatsApp Web does as you scroll the chat list.
  #avatarQueue = Promise.resolve()

  avatar(jid) {
    const file = join(AVATAR_DIR, `${createHash('sha1').update(jid).digest('hex').slice(0, 16)}.jpg`)
    const none = `${file}.none`
    if (existsSync(file)) return Promise.resolve(file)
    if (existsSync(none) && Date.now() - statSync(none).mtimeMs < NO_AVATAR_TTL) return Promise.resolve(null)
    const job = this.#avatarQueue.then(async () => {
      if (existsSync(file)) return file
      if (this.status !== 'open') return null
      try {
        const url = await this.sock.profilePictureUrl(jid, 'preview')
        const res = url && (await fetch(url))
        if (!res?.ok) throw Object.assign(new Error('item-not-found'), { output: { statusCode: 404 } })
        writeAtomic(file, Buffer.from(await res.arrayBuffer()))
        return file
      } catch (e) {
        // No photo or hidden by privacy settings: remember that. Anything else may be transient.
        if ([401, 403, 404].includes(e?.output?.statusCode) || /item-not-found|not-authorized/.test(e?.message ?? '')) {
          writeAtomic(none, '')
        }
        return null
      } finally {
        await sleep(150)
      }
    })
    this.#avatarQueue = job.catch(() => {})
    return job
  }

  // ---- sending ----

  async resolveJid(input) {
    const s = String(input ?? '').trim()
    if (s === 'me') return this.me.jid
    if (JID_RE.test(s)) return s
    const num = s.replace(/[\s()+-]/g, '')
    if (/^\d{8,15}$/.test(num)) {
      const [hit] = (await this.sock.onWhatsApp(`${num}@s.whatsapp.net`)) ?? []
      if (!hit?.exists) throw new Error(`+${num} is not on WhatsApp`)
      return hit.jid
    }
    throw new Error(`bad recipient: ${s}`)
  }

  async sendVoice(jid, note) {
    if (this.status !== 'open') throw new Error('not connected to WhatsApp yet')
    const sent = await this.sock.sendMessage(jid, {
      audio: note.buffer, mimetype: 'audio/ogg; codecs=opus', ptt: true, seconds: note.seconds, waveform: note.waveform,
    })
    this.#ingest([sent], true, jid)
    return store.get(jid, sent.key.id)
  }

  async sendText(jid, text) {
    if (this.status !== 'open') throw new Error('not connected to WhatsApp yet')
    const sent = await this.sock.sendMessage(jid, { text })
    this.#ingest([sent], true, jid)
    return store.get(jid, sent.key.id)
  }

  // "Delete for everyone".
  async revoke(jid, id) {
    await this.sock.sendMessage(jid, { delete: { remoteJid: jid, fromMe: true, id } })
    store.remove(jid, id)
  }

  // ---- messages ----

  // Chats arrive under a phone number or a LID depending on the message; keep one JID per person.
  #norm(jid, alt) {
    if (!jid) return null
    const id = jid.replace(/:\d+@/, '@')
    if (!id.endsWith('@lid')) return id
    if (alt?.endsWith('@s.whatsapp.net')) return alt.replace(/:\d+@/, '@')
    return this.lidMap.get(id) ?? id
  }

  // Store a batch; returns how many rows were new and which chats they belong to.
  #ingest(msgs, live, jidOverride) {
    const rows = []
    for (const m of msgs) {
      const r = parseMessage(m, (j, a) => this.#norm(j, a))
      if (!r) continue
      if (r.revoke) { store.remove(r.revoke.jid, r.revoke.id); this.emit('revoke', r.revoke) }
      else if (r.edit) { store.edit(r.edit.jid, r.edit.id, r.edit.text); this.emit('edit', r.edit) }
      else { if (jidOverride) r.jid = jidOverride; rows.push(r) }
    }
    if (!rows.length) return { fresh: 0, jids: new Set() }
    const fresh = store.insertMany(rows)
    if (live) for (const row of fresh) this.emit('message', row)
    else if (fresh.length) this.emit('history', fresh.length)
    return { fresh: fresh.length, jids: new Set(rows.map((r) => r.jid)) }
  }

  chats() { return store.chats() }

  // Ask the phone for `count` messages older than the oldest one we hold and wait for the answer.
  // The phone needs a real message to start from (it ignores made-up ones), so a chat with nothing stored
  // cannot be loaded; it becomes loadable as soon as a message is sent or received there. One request at a time.
  fetchHistory(jid, count = 50) {
    if (this.status !== 'open') return Promise.reject(new Error('not connected to WhatsApp yet'))
    if (!store.oldest(jid)) return Promise.resolve({ added: 0, noAnchor: true })
    const go = async () => {
      const old = store.oldest(jid)
      const anchor = { id: old.id, fromMe: !!old.from_me, ts: old.ts }
      const answer = new Promise((ok) => {
        const timer = setTimeout(() => { this.off('ondemand', on); ok({ added: 0, timedOut: true }) }, 25000)
        const on = (e) => {
          if (e.jids.length && !e.jids.includes(jid)) return // someone else's answer
          clearTimeout(timer)
          this.off('ondemand', on)
          ok({ added: e.added })
        }
        this.on('ondemand', on)
      })
      this.#log('history-request', { jid, count })
      await this.sock.fetchMessageHistory(count, { remoteJid: jid, id: anchor.id, fromMe: anchor.fromMe }, anchor.ts)
      return answer
    }
    const p = this.#onDemand.then(go, go)
    this.#onDemand = p.catch(() => {})
    return p
  }

  // Download (once) and cache a received attachment. Voice notes become AAC so every browser can play them.
  async media(jid, id) {
    const row = store.getRaw(jid, id)
    if (!row?.raw) throw new Error('that attachment is not available')
    const meta = row.meta ? JSON.parse(row.meta) : {}
    const audio = row.kind === 'voice' || row.kind === 'audio'
    const base = join(MEDIA_DIR, createHash('sha1').update(`${jid}/${id}`).digest('hex').slice(0, 20))
    const ext = audio ? 'm4a' : row.kind === 'sticker' ? 'webp' : (meta.mime?.split('/')[1]?.split(';')[0] ?? 'bin')
    const file = `${base}.${ext}`
    if (!existsSync(file)) {
      if ((meta.size ?? 0) > 60e6) throw new Error('that file is too large to open here')
      if (this.status !== 'open') throw new Error('not connected to WhatsApp yet')
      const msg = JSON.parse(row.raw, BufferJSON.reviver)
      const download = (m) => downloadMediaMessage(m, 'buffer', {}, { logger: pino({ level: 'silent' }), reuploadRequest: this.sock.updateMediaMessage })
      let buf
      try {
        buf = await download(msg)
      } catch (e) {
        // Old media is gone from WhatsApp's servers; ask the phone to upload it again, then retry once.
        if (!/\b(404|410)\b|Failed to fetch stream/.test(`${e?.message} ${e?.output?.statusCode}`)) throw e
        try { buf = await download(await this.sock.updateMediaMessage(msg)) } catch { throw new Error('this attachment is no longer available on WhatsApp') }
      }
      if (audio) {
        writeAtomic(`${base}.src`, buf)
        await run('ffmpeg', ['-y', '-v', 'error', '-i', `${base}.src`, '-vn', '-c:a', 'aac', '-b:a', '64k', '-f', 'ipod', `${file}.part`])
        renameSync(`${file}.part`, file)
        rmSync(`${base}.src`, { force: true })
      } else {
        writeAtomic(file, buf)
      }
    }
    return { file, type: audio ? 'audio/mp4' : row.kind === 'sticker' ? 'image/webp' : (meta.mime ?? 'application/octet-stream'), name: meta.name ?? null }
  }

  // Wait for in-flight auth writes, then close; exiting earlier corrupts the session.
  async shutdown(quietMs = 3000, maxMs = 60000) {
    const t0 = Date.now()
    while ((this.#pending.size || Date.now() - this.#last < quietMs) && Date.now() - t0 < maxMs) await sleep(250)
    this.#closing = true
    this.#saveNow()
    try { this.sock?.end(undefined) } catch { /* already closed */ }
    await sleep(500)
  }
}
