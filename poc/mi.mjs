#!/usr/bin/env node
// CLI: myinstants -> WhatsApp voice note. The web UI lives in server.mjs.
import { existsSync } from 'node:fs'
import { basename, resolve } from 'node:path'
import { createInterface } from 'node:readline/promises'
import qrcode from 'qrcode-terminal'
import { toVoiceNote } from './lib/audio.mjs'
import { BASE, DEFAULT_LIST, listPage, resolveSound, search } from './lib/myinstants.mjs'
import { WA } from './lib/wa.mjs'

const USAGE = `Usage:
  mi login [--code <phone>]          link WhatsApp (QR in terminal, or pairing code)
  mi groups                          list group names + JIDs
  mi list [url]                      list sounds on a myinstants page (default: Morocco trending)
  mi search <query>                  search myinstants
  mi send <sound> <to> [<to>...]     send as voice note
  mi pick [query] --to <to>          list/search, choose a number, send it
  mi convert <sound>                 download + convert only, print the .ogg path

<sound>  instant page URL, /media/sounds/x.mp3 URL, local audio file, or "q:<search>" (first hit)
<to>     phone number with country code (212612345678), group JID (...@g.us), or "me"

The web UI is \`npm start\`. Stop it before using the CLI: both share one WhatsApp session.`

const die = (msg) => { console.error(msg); process.exit(1) }

const soundOf = async (spec) => (existsSync(spec) ? { title: basename(spec), file: resolve(spec) } : resolveSound(spec))

async function connect({ pairingPhone } = {}) {
  const wa = new WA({ pairingPhone })
  wa.on('qr', (qr) => { console.log('Scan with WhatsApp > Linked devices > Link a device:'); qrcode.generate(qr, { small: true }) })
  wa.on('pairing-code', (code) => console.log(`Pairing code: ${code}\nWhatsApp > Linked devices > Link with phone number instead`))
  wa.on('status', (s) => { if (s === 'logged-out') die('logged out - delete ./auth and run `mi login`') })
  await wa.start()
  await wa.ready()
  return wa
}

const printList = (items) => items.forEach((s, i) => console.log(`${String(i + 1).padStart(2)}. ${s.title}  ${s.page.replace(BASE, '')}`))

async function sendAll(wa, jids, note) {
  for (const jid of jids) {
    await wa.sendVoice(jid, note)
    console.log(`sent -> ${jid}`)
  }
  await new Promise((r) => setTimeout(r, 1500)) // let the socket flush before closing
}

async function main() {
  const argv = process.argv.slice(2)
  const cmd = argv.shift()
  const flag = (name) => { const i = argv.indexOf(name); return i < 0 ? undefined : argv.splice(i, 2)[1] }

  switch (cmd) {
    case 'login': {
      const wa = await connect({ pairingPhone: flag('--code')?.replace(/\D/g, '') })
      console.log(`linked as ${wa.me.jid} - saving session, wait...`)
      await wa.shutdown(8000)
      console.log('session saved')
      process.exit(0)
    }
    case 'groups': {
      const wa = await connect()
      await new Promise((r) => setTimeout(r, 2500))
      for (const g of wa.groups.values()) console.log(`${g.jid}  ${g.name}`)
      await wa.shutdown()
      process.exit(0)
    }
    case 'list': printList(await listPage(argv[0] ?? DEFAULT_LIST)); return
    case 'search': {
      if (!argv.length) die(USAGE)
      printList(await search(argv.join(' ')))
      return
    }
    case 'convert': {
      if (!argv[0]) die(USAGE)
      const sound = await soundOf(argv[0])
      const note = await toVoiceNote(sound)
      console.log(`${sound.title}: ${note.path} (${note.seconds}s)`)
      return
    }
    case 'send': {
      const [spec, ...to] = argv
      if (!spec || !to.length) die(USAGE)
      const sound = await soundOf(spec)
      const note = await toVoiceNote(sound)
      console.log(`${sound.title} (${note.seconds}s)`)
      const wa = await connect()
      await sendAll(wa, await Promise.all(to.map((t) => wa.resolveJid(t))), note)
      await wa.shutdown()
      process.exit(0)
    }
    case 'pick': {
      const to = flag('--to')
      if (!to) die(USAGE)
      const items = argv.length ? await search(argv.join(' ')) : await listPage(DEFAULT_LIST)
      if (!items.length) die('nothing found')
      printList(items)
      const rl = createInterface({ input: process.stdin, output: process.stdout })
      const wa = await connect()
      const jid = await wa.resolveJid(to)
      for (;;) {
        const a = (await rl.question('number to send (empty to quit): ')).trim()
        if (!a) break
        const item = items[Number(a) - 1]
        if (!item) { console.log('invalid'); continue }
        await sendAll(wa, [jid], await toVoiceNote({ url: item.mp3 }))
      }
      rl.close()
      await wa.shutdown()
      process.exit(0)
    }
    default: console.log(USAGE)
  }
}

main().catch((e) => die(e.message))
