// Log of voice notes sent from here, so the UI can show a conversation per chat.
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { writeAtomic } from './util.mjs'

const FILE = join(dirname(fileURLToPath(import.meta.url)), '..', 'data', 'sent.json')
const MAX = 1000

let log = []
try { log = JSON.parse(readFileSync(FILE, 'utf8')) } catch { /* nothing sent yet */ }

let timer
export function flush() {
  clearTimeout(timer)
  writeAtomic(FILE, JSON.stringify(log))
}
const save = () => { clearTimeout(timer); timer = setTimeout(flush, 300) }

export const list = () => log

export function add(entry) {
  log.push(entry)
  if (log.length > MAX) log.splice(0, log.length - MAX)
  save()
  return entry
}

export function remove(id) {
  const i = log.findIndex((e) => e.id === id)
  if (i >= 0) { log.splice(i, 1); save() }
}
