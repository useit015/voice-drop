// Every message this app sees (history sync, live, and our own sends), in a local SQLite file.
import { mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

// node:sqlite works without flags but prints an experimental warning; keep the console clean.
const warn = process.emitWarning
process.emitWarning = (w, ...a) => (String(w).includes('SQLite') ? undefined : warn.call(process, w, ...a))
const { DatabaseSync } = await import('node:sqlite')
process.emitWarning = warn

const DATA = join(dirname(fileURLToPath(import.meta.url)), '..', 'data')
mkdirSync(DATA, { recursive: true })

const db = new DatabaseSync(join(DATA, 'messages.db'))
db.exec(`
  PRAGMA journal_mode = WAL;
  CREATE TABLE IF NOT EXISTS msgs (
    jid TEXT NOT NULL, id TEXT NOT NULL, from_me INTEGER NOT NULL, ts INTEGER NOT NULL,
    sender TEXT, push TEXT, kind TEXT NOT NULL, text TEXT, meta TEXT, raw TEXT,
    PRIMARY KEY (jid, id)
  );
  CREATE INDEX IF NOT EXISTS msgs_by_time ON msgs (jid, ts, id);
`)

const COLS = 'jid, id, from_me, ts, sender, push, kind, text, meta'
const insert = db.prepare('INSERT OR IGNORE INTO msgs (jid, id, from_me, ts, sender, push, kind, text, meta, raw) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)')
const selPage = db.prepare(`SELECT ${COLS} FROM msgs WHERE jid = ? AND (ts < ? OR (ts = ? AND id < ?)) ORDER BY ts DESC, id DESC LIMIT ?`)
const selOne = db.prepare(`SELECT ${COLS}, raw FROM msgs WHERE jid = ? AND id = ?`)
const selOldest = db.prepare('SELECT id, from_me, ts FROM msgs WHERE jid = ? ORDER BY ts ASC, id ASC LIMIT 1')
const selChats = db.prepare('SELECT jid, MAX(ts) AS ts FROM msgs GROUP BY jid ORDER BY ts DESC LIMIT 300')
const selLast = db.prepare('SELECT id, kind, text, from_me FROM msgs WHERE jid = ? ORDER BY ts DESC, id DESC LIMIT 1')
const selPush = db.prepare('SELECT push FROM msgs WHERE jid = ? AND from_me = 0 AND push IS NOT NULL ORDER BY ts DESC LIMIT 1')
const del = db.prepare('DELETE FROM msgs WHERE jid = ? AND id = ?')
const upd = db.prepare('UPDATE msgs SET text = ? WHERE jid = ? AND id = ?')
const cnt = db.prepare('SELECT COUNT(*) AS n FROM msgs')

const view = (r) => ({
  jid: r.jid, id: r.id, fromMe: !!r.from_me, ts: r.ts, sender: r.sender, push: r.push, kind: r.kind, text: r.text,
  meta: r.meta ? JSON.parse(r.meta) : null,
})

// Returns only the rows that were new, in the shape the UI wants.
export function insertMany(rows) {
  const fresh = []
  db.exec('BEGIN')
  try {
    for (const r of rows) {
      const res = insert.run(r.jid, r.id, r.fromMe ? 1 : 0, r.ts, r.sender ?? null, r.push ?? null, r.kind, r.text ?? null, r.meta ? JSON.stringify(r.meta) : null, r.raw ?? null)
      if (res.changes) fresh.push({ jid: r.jid, id: r.id, fromMe: !!r.fromMe, ts: r.ts, sender: r.sender ?? null, push: r.push ?? null, kind: r.kind, text: r.text ?? null, meta: r.meta ?? null })
    }
    db.exec('COMMIT')
  } catch (e) {
    db.exec('ROLLBACK')
    throw e
  }
  return fresh
}

// Oldest-first page of up to `limit` messages older than the cursor (or the newest ones when there is no cursor).
export function page(jid, { before = Number.MAX_SAFE_INTEGER, beforeId = '￿', limit = 60 } = {}) {
  const rows = selPage.all(jid, before, before, beforeId, limit).map(view).reverse()
  return { messages: rows, more: rows.length === limit }
}

export const get = (jid, id) => { const r = selOne.get(jid, id); return r ? view(r) : null }
export const getRaw = (jid, id) => selOne.get(jid, id)
export const oldest = (jid) => selOldest.get(jid)
export const remove = (jid, id) => { del.run(jid, id) }
export const edit = (jid, id, text) => { upd.run(text, jid, id) }
export const count = () => cnt.get().n

export function chats() {
  return selChats.all().map(({ jid, ts }) => {
    const last = selLast.get(jid)
    return { jid, ts, id: last.id, kind: last.kind, text: last.text, fromMe: !!last.from_me, push: selPush.get(jid)?.push ?? null }
  })
}
