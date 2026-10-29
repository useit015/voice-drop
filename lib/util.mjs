import { mkdirSync, renameSync, writeFileSync } from 'node:fs'
import { dirname } from 'node:path'

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// Write via a temp file so a crash never leaves a half-written file behind.
export function writeAtomic(file, data) {
  mkdirSync(dirname(file), { recursive: true })
  const tmp = `${file}.tmp`
  writeFileSync(tmp, data)
  renameSync(tmp, file)
}
