// Pinned chats and favourite sounds, kept server-side so every browser sees the same ones.
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { writeAtomic } from './util.mjs'

const FILE = join(dirname(fileURLToPath(import.meta.url)), '..', 'data', 'prefs.json')

let prefs = { pinned: [], favorites: [] }
try { prefs = { ...prefs, ...JSON.parse(readFileSync(FILE, 'utf8')) } } catch { /* first run */ }

export const get = () => prefs

export function set(partial) {
  prefs = { ...prefs, ...partial }
  writeAtomic(FILE, JSON.stringify(prefs))
  return prefs
}
