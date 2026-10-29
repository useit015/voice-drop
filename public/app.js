// Voice Drop UI: full WhatsApp conversations, text, and one-tap sounds sent as voice notes.
const $ = (sel, el = document) => el.querySelector(sel)

const store = {
  get(k, d) { try { return JSON.parse(localStorage.getItem(k)) ?? d } catch { return d } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)) } catch { /* private mode: just not remembered */ } },
}

const h = (tag, props = {}, ...kids) => {
  const el = document.createElement(tag)
  for (const [k, v] of Object.entries(props ?? {})) {
    if (v == null) continue
    if (k.startsWith('aria-')) { el.setAttribute(k, String(v)); continue }
    if (v === false) continue
    if (k === 'class') el.className = v
    else if (k.startsWith('on')) el.addEventListener(k.slice(2), v)
    else el.setAttribute(k, v === true ? '' : v)
  }
  for (const kid of kids.flat()) if (kid != null && kid !== false) el.append(kid.nodeType ? kid : document.createTextNode(kid))
  return el
}
const icon = (name, fill = false) => h('i', { class: `${fill ? 'ph-fill' : 'ph'} ph-${name}`, 'aria-hidden': 'true' })

async function api(path, opts) {
  const res = await fetch(path, opts)
  const data = await res.json().catch(() => ({}))
  if (!res.ok) throw new Error(data.error || `Request failed (${res.status})`)
  return data
}
const post = (path, body = {}) => api(path, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) })

const TABS = [['favs', 'Favorites'], ['recent', 'Recent'], ['ma', 'Morocco'], ['trending', 'Trending'], ['best', 'All-time'], ['new', 'New']]
const LOCAL = new Set(['favs', 'recent'])
const FILTERS = [['all', 'All'], ['groups', 'Groups'], ['pinned', 'Pinned']]
const ME = { jid: 'me', kind: 'me', display: 'You', _n: 'you me myself', _d: '' }
const CONTACT_CAP = 400
const KIND_LABEL = { voice: 'Voice message', audio: 'Audio', image: 'Photo', video: 'Video', sticker: 'Sticker', document: 'Document', contact: 'Contact', location: 'Location', poll: 'Poll' }
const mobile = matchMedia('(max-width: 859px)')
const isMobile = () => mobile.matches

const S = {
  wa: { status: 'connecting' }, offline: false, people: [], peopleLoaded: false, peopleKey: '', meJid: null,
  recipient: store.get('vd.to', null), pinned: new Set(), favs: [], sent: [], titles: new Map(), chats: new Map(),
  msgs: [], msgMore: false, msgToken: 0, olderBusy: false, histDone: new Set(), histAdded: 0,
  filter: 'all', pq: '', showAll: false, rows: [], kbd: -1,
  src: store.get('vd.src', null), q: '', page: 0, hasMore: false, loading: false, token: 0, items: [], tiles: [], hl: 0,
  expanded: false, drawer: store.get('vd.drawer', !isMobile()), lastField: 'sound',
}

// ---------- helpers ----------

const norm = (s) => (s || '').normalize('NFKD')
  .replace(/[̀-ًͯ-ٰٟ]/g, '').replace(/[أإآ]/g, 'ا').replace(/ى/g, 'ي').replace(/ة/g, 'ه').toLowerCase()

const initials = (name) => {
  const w = (name || '').replace(/^\+/, '').trim().split(/\s+/).filter(Boolean).map((x) => [...x])
  return w.length ? (w[0][0] + (w[1]?.[0] ?? '')).toUpperCase() : '#'
}

const letterOf = (p) => {
  const c = [...norm(p.display).replace(/[^\p{L}\p{N}]/gu, '')][0]
  return c && /\p{L}/u.test(c) ? c.toUpperCase() : '#'
}

function decorate(p) {
  p.display = p.name || (p.phone ? `+${p.phone}` : 'Unknown')
  p._n = norm(p.display)
  p._d = p.phone || ''
  return p
}

function subOf(p) {
  if (p.kind === 'me') return 'Message yourself'
  if (p.kind === 'group') return `Group · ${p.size} members`
  if (p.kind === 'number') return 'New chat'
  return p.name && p.phone ? `+${p.phone}` : ''
}

const ago = (at) => {
  const m = Math.floor((Date.now() - at) / 60000)
  return m < 1 ? 'now' : m < 60 ? `${m}m` : m < 1440 ? `${Math.floor(m / 60)}h` : `${Math.floor(m / 1440)}d`
}
const clock = (at) => new Date(at).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })
const fmtDur = (s) => `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
const fmtSize = (n) => (n > 1e6 ? `${(n / 1e6).toFixed(1)} MB` : `${Math.max(1, Math.round(n / 1e3))} KB`)
const dayLabel = (at) => {
  const d = new Date(at), t = new Date()
  const diff = Math.round((new Date(t.getFullYear(), t.getMonth(), t.getDate()) - new Date(d.getFullYear(), d.getMonth(), d.getDate())) / 864e5)
  return diff === 0 ? 'Today' : diff === 1 ? 'Yesterday' : d.toLocaleDateString([], { month: 'short', day: 'numeric', year: diff > 300 ? 'numeric' : undefined })
}

// The server stores real JIDs; the UI's "me" and typed numbers map onto them here.
const keyOf = (r) => (!r ? null : r.jid === 'me' ? S.wa.me?.jid : /^\d+$/.test(r.jid) ? `${r.jid}@s.whatsapp.net` : r.jid)
const lastFor = (jid) => S.chats.get(jid === 'me' ? S.wa.me?.jid : jid)

function previewOf(c) {
  const title = c.kind === 'voice' ? S.titles.get(c.id)?.title : null
  const body = c.kind === 'text' ? (c.text || '').replace(/\s+/g, ' ').trim()
    : title ? `Sound: ${title}` : (KIND_LABEL[c.kind] ?? 'Message')
  return (c.fromMe ? 'You: ' : '') + body
}

const personOf = (r) => S.people.find((p) => p.jid === r.jid)
  ?? (r.jid === 'me' ? ME : decorate({ jid: r.jid, kind: r.kind ?? 'contact', name: r.name, phone: r.phone, size: r.size }))

const setScreen = (s) => { $('#app').dataset.screen = s }
const debounce = (fn, ms) => { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms) } }

// ---------- prefs (pinned chats, favourite sounds; stored by the server) ----------

let prefsTimer
function savePrefs() {
  clearTimeout(prefsTimer)
  prefsTimer = setTimeout(() => post('/api/prefs', { pinned: [...S.pinned], favorites: S.favs }).catch(() => toast('Could not save your favorites', { kind: 'err' })), 300)
}

async function loadPrefs() {
  const local = store.get('vd.pinned', [])
  try {
    const p = await api('/api/prefs')
    S.favs = p.favorites ?? []
    S.pinned = new Set(p.pinned?.length ? p.pinned : local)
    if (!p.pinned?.length && local.length) savePrefs() // one-time move from this browser to the server
  } catch { S.pinned = new Set(local) }
}

const isFav = (item) => S.favs.some((f) => f.mp3 === item.mp3)

// ---------- profile photos ----------

const photos = new Map() // jid -> object URL | null (none) | Promise
async function photoUrl(jid) {
  if (!photos.has(jid)) {
    photos.set(jid, fetch(`/api/avatar?jid=${encodeURIComponent(jid)}`)
      .then(async (r) => (r.ok ? URL.createObjectURL(await r.blob()) : null))
      .catch(() => null)
      .then((url) => { photos.set(jid, url); return url }))
  }
  return photos.get(jid)
}

const photoObserver = new IntersectionObserver((entries) => {
  for (const e of entries) {
    if (!e.isIntersecting) continue
    photoObserver.unobserve(e.target)
    photoUrl(e.target.dataset.pj).then((url) => { if (url && e.target.isConnected) e.target.append(h('img', { src: url, alt: '', draggable: 'false' })) })
  }
}, { rootMargin: '150px' })

function Avatar(p, size = '') {
  const fb = p.kind === 'me' ? icon('user', true) : p.kind === 'group' ? icon('users-three', true) : p.kind === 'number' ? icon('phone', true) : initials(p.display)
  const el = h('span', { class: `avatar ${size}${p.kind === 'me' || p.kind === 'number' ? ' accent' : ''}` }, h('span', { class: 'fb' }, fb))
  if (p.kind === 'number') return el
  el.dataset.pj = p.jid
  const cached = photos.get(p.jid)
  if (typeof cached === 'string') el.append(h('img', { src: cached, alt: '', draggable: 'false' }))
  else if (cached !== null) photoObserver.observe(el)
  return el
}

// ---------- toasts ----------

function toast(text, { kind = 'ok', undo, ms = 6000 } = {}) {
  const box = $('#toasts')
  while (box.children.length >= 3) box.firstElementChild.remove()
  let timer
  const close = () => {
    clearTimeout(timer)
    el.classList.add('out')
    setTimeout(() => el.remove(), 250)
  }
  const undoBtn = undo && h('button', {
    type: 'button',
    onclick: async () => {
      undoBtn.disabled = true
      try { await undo(); close(); toast('Unsent for everyone') } catch (e) { close(); toast(e.message, { kind: 'err' }) }
    },
  }, 'Undo')
  const el = h('div', { class: `toast${kind === 'err' ? ' err' : ''}` }, icon(kind === 'err' ? 'warning-circle' : 'check-circle', true), h('span', {}, text), undoBtn)
  box.append(el)
  timer = setTimeout(close, ms)
}

// ---------- audio (one player for tiles and chat bubbles) ----------

const audio = new Audio()
audio.preload = 'none'
let cur = null // { ui }

function stopPlay() {
  if (!cur) return
  audio.pause()
  cur.ui.on(false)
  cur.ui.progress(0)
  cur = null
}

function startPlay(src, ui) {
  if (cur?.ui === ui) return stopPlay()
  stopPlay()
  cur = { ui }
  ui.on(true)
  audio.src = src
  audio.play().catch(() => {
    if (cur?.ui === ui) { ui.on(false); cur = null }
    toast('Could not play that sound', { kind: 'err', ms: 3000 })
  })
  const tick = () => {
    if (cur?.ui !== ui) return
    ui.progress(audio.duration ? audio.currentTime / audio.duration : 0)
    requestAnimationFrame(tick)
  }
  requestAnimationFrame(tick)
}
audio.addEventListener('ended', stopPlay)

const setPlayUi = (btn, on) => {
  btn.setAttribute('aria-pressed', on)
  btn.replaceChildren(icon(on ? 'pause' : 'play', true))
}
const srcOf = (mp3) => `/api/audio?u=${encodeURIComponent(mp3)}`
const mediaUrl = (m) => `/api/media?jid=${encodeURIComponent(m.jid)}&id=${encodeURIComponent(m.id)}`

// ---------- conversation ----------

function needRecipient() {
  const head = $('#chatHead')
  head.classList.remove('nudge')
  void head.offsetWidth
  head.classList.add('nudge')
  toast('Pick a chat first', { kind: 'err', ms: 3000 })
  if (isMobile()) setScreen('list')
  else $('#peopleSearch').focus()
}

function renderChatHead() {
  const r = S.recipient
  const back = h('button', { type: 'button', class: 'icon-btn only-mobile', 'aria-label': 'Back to chats', onclick: () => setScreen('list') }, icon('arrow-left'))
  if (!r) {
    $('#chatHead').replaceChildren(back, h('div', { class: 'who' }, h('b', {}, 'Choose a chat'), h('small', { class: 'muted' }, 'Pick someone to start')))
    return
  }
  const p = personOf(r)
  const pinned = S.pinned.has(p.jid)
  const pin = (p.kind === 'contact' || p.kind === 'group') && h('button', {
    type: 'button', class: `icon-btn${pinned ? ' on' : ''}`, 'aria-pressed': pinned, 'aria-label': `${pinned ? 'Unpin' : 'Pin'} ${p.display}`, onclick: () => togglePin(p),
  }, icon('star', pinned))
  $('#chatHead').replaceChildren(...[back, Avatar(p, 'sm'), h('div', { class: 'who' }, h('b', { dir: 'auto' }, p.display), h('small', { class: 'muted' }, subOf(p))), pin].filter(Boolean))
  $('#soundSearch').placeholder = `Search sounds for ${p.display}`
  $('#textInput').placeholder = `Message ${p.display}`
}

const downsample = (arr, n) => Array.from({ length: n }, (_, i) => {
  const a = Math.floor((i * arr.length) / n)
  const b = Math.max(a + 1, Math.floor(((i + 1) * arr.length) / n))
  let m = 0
  for (let k = a; k < b; k++) m = Math.max(m, arr[k] ?? 0)
  return m
})

const decodeWave = (b64) => { try { return [...atob(b64)].map((c) => c.charCodeAt(0)) } catch { return null } }

function linkify(text) {
  const out = []
  let last = 0
  for (const m of text.matchAll(/https?:\/\/[^\s<]+/gi)) {
    const url = m[0].replace(/[),.;:!?]+$/, '')
    out.push(text.slice(last, m.index), h('a', { href: url, target: '_blank', rel: 'noopener noreferrer' }, url), m[0].slice(url.length))
    last = m.index + m[0].length
  }
  out.push(text.slice(last))
  return out
}

const textBody = (text) => h('div', { class: 'body', dir: 'auto' }, linkify(text ?? ''))

function senderName(m) {
  return S.people.find((p) => p.jid === m.sender)?.display ?? m.push ?? (m.sender ? `+${m.sender.replace(/@.*/, '')}` : 'Someone')
}

// Show the full image when it scrolls into view; the thumbnail stays until then (and if the download fails).
const fullObserver = new IntersectionObserver((entries) => {
  for (const e of entries) {
    if (!e.isIntersecting) continue
    fullObserver.unobserve(e.target)
    const img = e.target
    const probe = new Image()
    probe.onload = () => { img.src = img.dataset.full; img.classList.add('full'); img._onLoaded?.() }
    probe.onerror = () => img._onFailed?.()
    probe.src = img.dataset.full
  }
}, { rootMargin: '200px' })

function mediaEl(m) {
  const thumb = m.meta?.thumb ? `data:image/jpeg;base64,${m.meta.thumb}` : null
  const full = mediaUrl(m)
  const holder = h('span', { class: 'ph-fallback', style: `aspect-ratio:${m.meta?.w && m.meta?.h ? `${m.meta.w} / ${m.meta.h}` : '4 / 3'}` },
    icon(m.kind === 'video' ? 'video-camera' : 'image'), h('small', {}, m.kind === 'video' ? 'Video' : m.kind === 'sticker' ? 'Sticker' : 'Photo'))
  const img = h('img', { alt: '', draggable: 'false', src: thumb, hidden: !thumb })
  if (m.kind !== 'video' && !m.pending) {
    img.dataset.full = full
    img._onLoaded = () => { img.hidden = false; holder.remove() }
    img._onFailed = () => { holder.classList.add('failed'); holder.querySelector('small').textContent = 'Not available' }
    fullObserver.observe(img)
  }
  return h('a', { class: `media${m.kind === 'sticker' ? ' sticker' : ''}`, href: full, target: '_blank', rel: 'noopener' },
    img, thumb ? null : holder, m.kind === 'video' && h('span', { class: 'badge' }, icon('play-circle', true)))
}

function docEl(m) {
  return h('a', { class: 'doc', href: mediaUrl(m), target: '_blank', rel: 'noopener', download: m.meta?.name ?? '' },
    icon('file-text', true), h('span', {}, h('b', {}, m.meta?.name ?? 'Document'), m.meta?.size ? h('small', {}, fmtSize(m.meta.size)) : null))
}

function voiceParts(m, entry) {
  const wave0 = entry?.waveform ?? (m.meta?.wave ? decodeWave(m.meta.wave) : null)
  const bars = wave0 ? downsample(wave0, 40) : Array.from({ length: 40 }, (_, i) => 24 + ((i * 37) % 40))
  const wave = h('div', { class: 'wave' }, bars.map((v, i) => h('span', { style: `--h:${Math.max(12, v)};--i:${i}` })))
  const spans = [...wave.children]
  const btn = h('button', {
    type: 'button', class: 'play', 'aria-pressed': 'false', 'aria-label': 'Play voice message',
    onclick: () => { if (!m.pending) startPlay(entry ? srcOf(entry.mp3) : mediaUrl(m), ui) },
  }, icon('play', true))
  const ui = {
    kind: 'bubble', on: (b) => setPlayUi(btn, b),
    progress: (p) => { const n = Math.round(p * bars.length); spans.forEach((s, i) => s.classList.toggle('on', i < n)) },
  }
  const seconds = m.meta?.seconds ?? entry?.seconds
  const title = entry?.title ?? m.title
  return [
    h('div', { class: 'vn' }, btn, wave, h('span', { class: 'dur' }, seconds ? fmtDur(seconds) : '')),
    title && h('div', { class: 'cap', dir: 'auto', title }, title),
  ]
}

function bubbleFor(m, { fresh, sender } = {}) {
  const entry = S.titles.get(m.id)
  const body = []
  if (sender) body.push(h('div', { class: 'sender', dir: 'auto' }, senderName(m)))
  if (m.kind === 'text') body.push(textBody(m.text))
  else if (m.kind === 'voice' || m.kind === 'audio') body.push(...voiceParts(m, entry))
  else if (m.kind === 'image' || m.kind === 'sticker' || m.kind === 'video') { body.push(mediaEl(m)); if (m.text) body.push(textBody(m.text)) }
  else if (m.kind === 'document') { body.push(docEl(m)); if (m.text) body.push(textBody(m.text)) }
  else if (m.kind === 'contact' || m.kind === 'location' || m.kind === 'poll') body.push(h('div', { class: 'body' }, `${KIND_LABEL[m.kind]}: ${m.text ?? ''}`))
  else body.push(h('div', { class: 'body dim' }, m.text || 'This message type is not shown here'))
  const del = m.fromMe && !m.pending && h('button', {
    type: 'button', class: 'del', title: 'Delete for everyone', 'aria-label': 'Delete for everyone', onclick: () => unsend(m, el),
  }, icon('trash'))
  const el = h('div', { class: `msg${m.kind === 'voice' || m.kind === 'audio' ? ' voice' : ''}${m.pending ? ' pending' : ''}${fresh ? ' fresh' : ''}` },
    del, body, h('div', { class: 'meta' }, clock(m.ts), m.fromMe && icon(m.pending ? 'clock' : 'check')))
  return el
}

function olderRow() {
  const key = keyOf(S.recipient)
  if (!key || S.msgMore) return null
  if (S.olderBusy) {
    return h('div', { class: 'older' }, h('span', { class: 'spin' }, icon('circle-notch'), ` Loading earlier messages${S.histAdded ? ` (${S.histAdded} so far)` : ''}`))
  }
  if (S.histDone.has(key)) return h('div', { class: 'older' }, h('span', {}, S.msgs.length ? 'Start of the conversation' : 'No messages in the last 3 months'))
  return h('div', { class: 'older' }, h('div', { class: 'links' },
    h('button', { type: 'button', onclick: () => loadEarlier() }, 'Load earlier messages'),
    h('button', { type: 'button', onclick: () => loadEarlier({ all: true }) }, 'Load everything')))
}

// opts: { bottom } jump to newest; { prepend } keep the view steady after older messages were added; { fresh } id of a new message
function renderMsgs(opts = {}) {
  const body = $('#chatBody')
  const fromBottom = body.scrollHeight - body.scrollTop
  const nearBottom = body.scrollHeight - body.scrollTop - body.clientHeight < 140
  if (cur?.ui.kind === 'bubble') stopPlay()
  const frag = document.createDocumentFragment()
  frag.append(olderRow() ?? '')
  if (!S.recipient) {
    frag.append(h('div', { class: 'empty-chat' }, icon('chats-circle'), h('div', {}, h('b', {}, 'Choose a chat'), h('span', {}, 'Pick someone on the left to see the conversation.'))))
  } else if (!S.msgs.length) {
    frag.append(h('div', { class: 'empty-chat' }, icon('chat-teardrop-text'), h('div', {}, h('b', {}, `Say something to ${personOf(S.recipient).display}`), h('span', {}, 'Type a message or tap a sound below.'))))
  }
  let day = ''
  let prev = null
  const group = keyOf(S.recipient)?.endsWith('@g.us')
  for (const m of S.msgs) {
    const d = dayLabel(m.ts)
    if (d !== day) { day = d; prev = null; frag.append(h('div', { class: 'day' }, d)) }
    const newRun = !prev || prev.fromMe !== m.fromMe || prev.sender !== m.sender || m.ts - prev.ts > 5 * 60000
    const row = h('div', { class: `row ${m.fromMe ? 'out' : 'in'}${newRun && prev ? ' gap' : ''}` }, bubbleFor(m, { fresh: m.id === opts.fresh, sender: group && !m.fromMe && newRun }))
    frag.append(row)
    prev = m
  }
  $('#msgs').replaceChildren(frag)
  if (opts.bottom || (nearBottom && !opts.prepend)) body.scrollTop = body.scrollHeight
  else body.scrollTop = body.scrollHeight - fromBottom
}

async function loadMessages(reset = true, limit = 60) {
  const key = keyOf(S.recipient)
  if (!key) { S.msgs = []; S.msgMore = false; renderMsgs(); return }
  const token = reset ? ++S.msgToken : S.msgToken
  const params = new URLSearchParams({ jid: key, limit })
  if (!reset && S.msgs[0]) { params.set('before', S.msgs[0].ts); params.set('beforeId', S.msgs[0].id) }
  try {
    const res = await api(`/api/messages?${params}`)
    if (token !== S.msgToken) return
    S.msgMore = res.more
    if (reset) {
      S.msgs = res.messages
      renderMsgs({ bottom: true })
      if (S.msgs.length < 25 && !S.msgMore && S.wa.status === 'open') loadEarlier() // looks thin: fill it from the phone
    } else { S.msgs = [...res.messages, ...S.msgs]; renderMsgs({ prepend: true }) }
  } catch { /* leaves the conversation as it is */ }
}

// Re-read the newest messages (after an import or an on-demand fetch) without jumping the view.
async function refreshMessages() {
  const key = keyOf(S.recipient)
  if (!key) return
  const token = S.msgToken
  try {
    const res = await api(`/api/messages?${new URLSearchParams({ jid: key, limit: Math.min(200, Math.max(60, S.msgs.length + 60)) })}`)
    if (token !== S.msgToken) return
    S.msgs = res.messages
    S.msgMore = res.more
    renderMsgs()
  } catch { /* next event retries */ }
}

// Older messages: our own store first, then ask the phone for 50 more (repeatedly with `all`).
async function loadEarlier({ all = false } = {}) {
  const key = keyOf(S.recipient)
  if (!key || S.olderBusy || S.histDone.has(key)) return
  S.olderBusy = true
  S.histAdded = 0
  renderMsgs({ prepend: true })
  try {
    for (;;) {
      if (S.msgMore) { await loadMessages(false, all ? 500 : 60); if (!all) break; continue }
      const { added } = await post('/api/history', { jid: key, count: all ? 500 : 200 })
      if (key !== keyOf(S.recipient)) return // switched chats meanwhile
      if (!added) { S.histDone.add(key); break }
      S.histAdded += added
      await (S.msgs.length ? loadMessages(false, all ? 500 : 60) : loadMessages(true))
      if (!all) break
    }
  } catch (e) {
    toast(e.message, { kind: 'err' })
  } finally {
    S.olderBusy = false
    S.histAdded = 0
    if (key === keyOf(S.recipient)) renderMsgs({ prepend: true })
  }
}

$('#chatBody').addEventListener('scroll', (e) => {
  if (e.target.scrollTop < 120) loadEarlier()
})

function addMessage(m) {
  if (S.msgs.some((x) => x.id === m.id)) return
  const tmpAt = S.msgs.findIndex((x) => x.pending && x.fromMe && x.kind === m.kind && (m.kind !== 'text' || x.text === m.text))
  if (m.fromMe && tmpAt >= 0) S.msgs[tmpAt] = m
  else S.msgs.push(m)
  renderMsgs({ fresh: m.id })
}

function replaceTmp(tmp, message) {
  const i = S.msgs.indexOf(tmp)
  if (S.msgs.some((x) => x.id === message.id)) { if (i >= 0) S.msgs.splice(i, 1) } else if (i >= 0) S.msgs[i] = message
  touchChat(message)
  renderMsgs({ fresh: message.id, bottom: true })
}

async function unsend(m, el) {
  el.classList.add('out-anim')
  try {
    await post('/api/undo', { jid: m.jid, id: m.id })
    S.msgs = S.msgs.filter((x) => x.id !== m.id)
    S.sent = S.sent.filter((e) => e.id !== m.id)
    indexSent()
    renderMsgs()
    loadChats()
  } catch (e) { el.classList.remove('out-anim'); toast(e.message, { kind: 'err' }) }
}

function indexSent() { S.titles = new Map(S.sent.map((e) => [e.id, e])) }

async function loadSent() {
  try { S.sent = (await api('/api/sent')).sent } catch { /* shown without titles */ }
  indexSent()
}

// ---------- chat list ----------

async function loadChats() {
  try {
    const { chats } = await api('/api/chats')
    S.chats = new Map(chats.map((c) => [c.jid, c]))
  } catch { return }
  renderPeople()
}
const loadChatsSoon = debounce(loadChats, 400)

function touchChat(m) {
  const prev = S.chats.get(m.jid)
  const next = { jid: m.jid, ts: m.ts, id: m.id, kind: m.kind, text: m.text, fromMe: m.fromMe, push: (!m.fromMe && m.push) || prev?.push || null }
  S.chats = new Map([[m.jid, next], ...[...S.chats].filter(([k]) => k !== m.jid)])
  renderPeopleSoon()
}
const renderPeopleSoon = debounce(() => renderPeople(), 300)

function choose(p) {
  S.recipient = { jid: p.jid, name: p.display, kind: p.kind, phone: p.phone, size: p.size }
  store.set('vd.to', S.recipient)
  renderChatHead()
  S.msgs = []
  S.msgMore = false
  S.olderBusy = false
  renderMsgs({ bottom: true })
  loadMessages(true)
  if (isMobile()) { setScreen('chat'); markSelected(); return }
  if (S.pq) { $('#peopleSearch').value = ''; S.pq = ''; renderPeople() } else markSelected()
  focusField()
}

function markSelected() {
  for (const row of S.rows) {
    const on = row.dataset.jid === S.recipient?.jid
    row.classList.toggle('on', on)
    row.setAttribute('aria-selected', on)
  }
}

function togglePin(p) {
  S.pinned.has(p.jid) ? S.pinned.delete(p.jid) : S.pinned.add(p.jid)
  savePrefs()
  renderPeople()
  renderChatHead()
}

function personRow(p) {
  const sel = S.recipient?.jid === p.jid
  const pinned = S.pinned.has(p.jid)
  const last = lastFor(p.jid)
  const star = (p.kind === 'contact' || p.kind === 'group') && h('button', {
    type: 'button', class: `star${pinned ? ' on' : ''}`, 'aria-pressed': pinned, 'aria-label': `${pinned ? 'Unpin' : 'Pin'} ${p.display}`,
    onclick: (e) => { e.stopPropagation(); togglePin(p) },
  }, icon('star', pinned))
  const row = h('div', {
    class: `person${sel ? ' on' : ''}`, role: 'option', tabindex: '0', 'aria-selected': sel, 'data-jid': p.jid,
    onclick: () => choose(p),
    onkeydown: (e) => { if (e.target === row && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); choose(p) } },
  },
  Avatar(p),
  h('span', { class: 'txt' }, h('span', { class: 'name', dir: 'auto' }, p.display), h('span', { class: 'sub', dir: 'auto' }, last ? previewOf(last) : subOf(p))),
  h('span', { class: 'tail' }, last && h('span', { class: 'when' }, ago(last.ts)), star))
  return row
}

function searchPeople(q, pool) {
  const nq = norm(q)
  const dq = q.replace(/\D/g, '')
  const numeric = /^[+\d\s()-]+$/.test(q) && dq.length >= 3
  const hits = []
  for (const p of pool) {
    let score = 9
    if (p._n.startsWith(nq)) score = 0
    else if (p._n.split(/\s+/).some((w) => w.startsWith(nq))) score = 1
    else if (p._n.includes(nq)) score = 2
    else if (numeric && dq && p._d.includes(dq)) score = 3
    if (score < 9) hits.push([score, p])
  }
  hits.sort((a, b) => a[0] - b[0] || a[1]._n.localeCompare(b[1]._n))
  const out = hits.map(([, p]) => p)
  if (numeric && dq.length >= 8 && !out.some((p) => p._d === dq)) {
    out.unshift(decorate({ jid: dq, kind: 'number', name: null, phone: dq }))
  }
  return out
}

const byRecent = (a, b) => (lastFor(b.jid)?.ts ?? 0) - (lastFor(a.jid)?.ts ?? 0) || a._n.localeCompare(b._n)
const note = (ic, text) => h('div', { class: 'note' }, icon(ic), h('span', {}, text))

function chatPerson(c) {
  return S.people.find((p) => p.jid === c.jid) ?? decorate(c.jid.endsWith('@g.us')
    ? { jid: c.jid, kind: 'group', name: 'Group', size: 0 }
    : { jid: c.jid, kind: 'contact', name: c.push, phone: c.jid.endsWith('@s.whatsapp.net') ? c.jid.replace(/@.*/, '') : null })
}

function renderPeople() {
  const list = $('#peopleList')
  const keep = list.scrollTop
  const frag = document.createDocumentFragment()
  const rows = []
  const add = (p) => { const r = personRow(p); rows.push(r); frag.append(r) }
  const title = (t) => frag.append(h('div', { class: 'group-title' }, t))
  const q = S.pq.trim()
  const pinnedPeople = () => [...S.pinned].map((jid) => S.people.find((p) => p.jid === jid)).filter(Boolean)

  if (!S.peopleLoaded) {
    for (let i = 0; i < 7; i++) frag.append(h('div', { class: 'skel-row' }))
  } else if (q || S.filter !== 'all') {
    const pool = S.filter === 'groups' ? S.people.filter((p) => p.kind === 'group')
      : S.filter === 'pinned' ? pinnedPeople() : [ME, ...S.people]
    const hits = q ? searchPeople(q, pool) : [...pool].sort(byRecent)
    if (!hits.length) {
      frag.append(q
        ? note('magnifying-glass', `No chat matches “${q}”. Type a full phone number with country code to message someone new.`)
        : S.filter === 'pinned' ? note('star', 'No pinned chats yet. Tap the star on any chat to pin it.') : note('users-three', 'No groups found.'))
    }
    hits.slice(0, 80).forEach(add)
  } else {
    title('You')
    add(ME)
    const pinned = pinnedPeople()
    if (pinned.length) { title('Pinned'); pinned.forEach(add) }
    const recent = [...S.chats.values()].filter((c) => c.jid !== S.wa.me?.jid && !S.pinned.has(c.jid)).slice(0, 10).map(chatPerson)
    if (recent.length) { title('Recent'); recent.forEach(add) }
    else if (S.wa.history?.active) frag.append(note('arrows-clockwise', 'Syncing your recent chats. They appear here as they arrive.'))

    const groups = S.people.filter((p) => p.kind === 'group').sort((a, b) => a._n.localeCompare(b._n))
    if (groups.length) { title('Groups'); groups.forEach(add) }

    const contacts = S.people.filter((p) => p.kind === 'contact').sort((a, b) => {
      const la = letterOf(a), lb = letterOf(b)
      return (la === '#') - (lb === '#') || a._n.localeCompare(b._n)
    })
    if (contacts.length) {
      title(`Contacts (${contacts.length})`)
      let last = ''
      const shown = S.showAll ? contacts : contacts.slice(0, CONTACT_CAP)
      for (const p of shown) {
        const l = letterOf(p)
        if (l !== last) { frag.append(h('div', { class: 'letter' }, l)); last = l }
        add(p)
      }
      if (shown.length < contacts.length) {
        frag.append(h('button', { type: 'button', class: 'more-btn', onclick: () => { S.showAll = true; renderPeople() } }, `Show all ${contacts.length} contacts`))
      }
    } else if (S.wa.status !== 'open' || S.wa.syncing) {
      frag.append(note('arrows-clockwise', S.wa.status === 'open' ? 'Syncing your contacts. This takes a few seconds.' : 'Contacts appear once WhatsApp is connected.'))
    } else {
      frag.append(note('address-book', 'No saved contacts found. You can still type a phone number above.'))
    }
  }

  list.replaceChildren(frag)
  S.rows = rows
  list.scrollTop = q ? 0 : keep
  setKbd(q && rows.length ? 0 : -1)
}

function setKbd(i) {
  S.kbd = S.rows.length ? Math.max(-1, Math.min(i, S.rows.length - 1)) : -1
  S.rows.forEach((r, n) => r.classList.toggle('kbd', n === S.kbd))
  if (S.kbd >= 0 && S.pq) S.rows[S.kbd].scrollIntoView({ block: 'nearest' })
}

function renderChips() {
  $('#chips').replaceChildren(...FILTERS.map(([key, label]) => h('button', {
    type: 'button', class: 'chip', role: 'tab', 'aria-selected': S.filter === key,
    onclick: () => { S.filter = key; renderChips(); renderPeople() },
  }, label)))
}

async function loadPeople() {
  try {
    const { people } = await api('/api/people')
    S.people = people.map(decorate)
    S.peopleLoaded = S.people.length > 0 || S.wa.status === 'open'
  } catch { /* retried on the next state poll */ }
  renderPeople()
  renderChatHead()
}

// ---------- sounds ----------

const grid = $('#grid')
const scroller = $('#drawerScroll')
const sentinel = $('#sentinel')

function renderTabs() {
  $('#tabs').replaceChildren(...TABS.map(([key, label]) => h('button', {
    type: 'button', class: 'tab', role: 'tab', 'aria-selected': !S.q && S.src === key,
    onclick: () => {
      S.src = key
      store.set('vd.src', key)
      $('#soundSearch').value = ''
      S.q = ''
      renderTabs()
      scroller.scrollTo({ top: 0 })
      loadSounds(true)
    },
  }, label)))
}

function localItems(src) {
  if (src === 'favs') return S.favs.map((f) => ({ ...f }))
  const seen = new Set()
  const out = []
  for (const e of [...S.sent].reverse()) {
    if (seen.has(e.mp3)) continue
    seen.add(e.mp3)
    out.push({ title: e.title, mp3: e.mp3, color: e.color ?? null })
  }
  return out.slice(0, 60)
}

const skeletons = () => grid.replaceChildren(...Array.from({ length: 12 }, () => h('div', { class: 'skel' })))

function emptyState(kind, err) {
  grid.append(kind === 'error'
    ? h('div', { class: 'empty' }, icon('wifi-slash'), h('b', {}, 'Could not load sounds'), h('span', {}, err), h('button', { type: 'button', onclick: () => loadSounds(S.items.length === 0) }, 'Try again'))
    : kind === 'favs'
      ? h('div', { class: 'empty' }, icon('star'), h('b', {}, 'No favorites yet'), h('span', {}, 'Tap the star on any sound to keep it here.'))
      : kind === 'recent'
        ? h('div', { class: 'empty' }, icon('clock-counter-clockwise'), h('b', {}, 'Nothing sent yet'), h('span', {}, 'Sounds you send show up here for quick reuse.'))
        : h('div', { class: 'empty' }, icon('speaker-slash'), h('b', {}, S.q ? `No sounds for “${S.q}”` : 'Nothing here yet'), h('span', {}, 'Try a shorter or different word.')))
}

async function loadSounds(reset) {
  if (reset) {
    S.token++
    S.items = []
    S.tiles = []
    S.hl = 0
    S.page = 0
    S.hasMore = true
    S.loading = false
    stopPlay()
    skeletons()
  }
  if (S.loading || !S.hasMore) return
  if (!S.q && LOCAL.has(S.src)) {
    const items = localItems(S.src)
    grid.replaceChildren()
    items.forEach((it, n) => { const t = tile(it, n); grid.append(t); S.tiles.push(t) })
    S.items = items
    S.hasMore = false
    if (items.length) setHl(0, false)
    else emptyState(S.src)
    return
  }
  S.loading = true
  const token = S.token
  const first = S.page === 0
  try {
    const r = await api(`/api/sounds?${new URLSearchParams({ src: S.src, q: S.q, page: S.page + 1 })}`)
    if (token !== S.token) return
    if (first) grid.replaceChildren()
    grid.querySelector('.empty')?.remove()
    const seen = new Set(S.items.map((i) => i.mp3))
    const fresh = r.items.filter((i) => !seen.has(i.mp3))
    for (const [n, it] of fresh.entries()) {
      const t = tile(it, S.items.length + n)
      grid.append(t)
      S.tiles.push(t)
    }
    S.items.push(...fresh)
    S.page++
    S.hasMore = r.hasMore && fresh.length > 0
    if (!S.items.length) emptyState('empty')
    else if (first) setHl(0, false)
  } catch (e) {
    if (token !== S.token) return
    if (first) grid.replaceChildren()
    S.hasMore = true
    emptyState('error', e.message)
    S.loading = false
    return
  }
  S.loading = false
  maybeMore()
}

// The observer only fires on changes, so re-check by hand after each page.
function maybeMore() {
  if (S.hasMore && !S.loading && sentinel.getBoundingClientRect().top < scroller.getBoundingClientRect().bottom + 500) loadSounds(false)
}
new IntersectionObserver((e) => { if (e.some((x) => x.isIntersecting)) maybeMore() }, { root: scroller, rootMargin: '500px' }).observe(sentinel)

const hashColor = (str) => {
  let x = 0
  for (const ch of str) x = (x * 31 + ch.codePointAt(0)) >>> 0
  return `hsl(${x % 360} 58% 48%)`
}

// Download + convert on the server while the pointer is still on the way, so the send itself is instant.
const warmed = new Set()
function warm(item) {
  if (warmed.has(item.mp3)) return
  warmed.add(item.mp3)
  fetch(`/api/warm?u=${encodeURIComponent(item.mp3)}`).catch(() => warmed.delete(item.mp3))
}

function favUi(btn, on) {
  btn.classList.toggle('on', on)
  btn.setAttribute('aria-pressed', on)
  btn.setAttribute('aria-label', on ? 'Remove from favorites' : 'Add to favorites')
  btn.replaceChildren(icon('star', on))
}

function toggleFav(item) {
  const i = S.favs.findIndex((f) => f.mp3 === item.mp3)
  if (i >= 0) S.favs.splice(i, 1)
  else S.favs.unshift({ title: item.title, mp3: item.mp3, color: item.color ?? null })
  savePrefs()
  for (const t of S.tiles) if (t._item.mp3 === item.mp3) favUi(t.querySelector('.fav'), i < 0)
}

function tile(item, i) {
  const play = h('button', { type: 'button', class: 'play', 'aria-pressed': 'false', 'aria-label': `Preview ${item.title}`, onclick: () => startPlay(srcOf(item.mp3), ui) }, icon('play', true))
  const ui = { kind: 'tile', on: (b) => setPlayUi(play, b), progress: (p) => play.style.setProperty('--p', p * 100) }
  const pick = h('button', { type: 'button', class: 'pick', 'aria-label': `Send ${item.title}`, onclick: () => sendTile(el) }, h('span', { class: 't', dir: 'auto' }, item.title))
  const fav = h('button', { type: 'button', class: 'fav', onclick: () => toggleFav(item) })
  favUi(fav, isFav(item))
  const state = h('i', { class: 'state', 'aria-hidden': 'true' })
  let timer
  const el = h('div', {
    class: 'tile', style: `--i:${i};--c:${item.color || hashColor(item.title)}`, 'data-state': 'idle',
    onpointerenter: () => { timer = setTimeout(() => warm(item), 120) },
    onpointerleave: () => clearTimeout(timer),
  }, play, pick, fav, state)
  el._item = item
  el._ui = ui
  return el
}

function setTileState(el, state) {
  el.dataset.state = state
  el.querySelector('.state').className = `state ph-fill ph-${state === 'sent' ? 'check-circle' : 'circle-notch'}`
}

let hlTimer
function setHl(i, scroll = true) {
  if (!S.tiles.length) return
  S.hl = Math.max(0, Math.min(i, S.tiles.length - 1))
  S.tiles.forEach((el, n) => el.classList.toggle('hl', n === S.hl))
  if (scroll) S.tiles[S.hl].scrollIntoView({ block: 'nearest' })
  clearTimeout(hlTimer)
  hlTimer = setTimeout(() => S.tiles[S.hl] && warm(S.tiles[S.hl]._item), 250)
}

function columns() {
  if (!S.tiles.length) return 1
  const top = S.tiles[0].offsetTop
  let c = 0
  while (c < S.tiles.length && S.tiles[c].offsetTop === top) c++
  return c || 1
}

// ---------- sending ----------

const ready = (to) => {
  if (!to) { needRecipient(); return false }
  if (S.wa.status !== 'open') { toast('Still connecting to WhatsApp, one moment', { kind: 'err', ms: 3000 }); return false }
  return true
}

async function sendTile(el) {
  const item = el._item
  const to = S.recipient
  if (!ready(to) || el.dataset.state === 'sending') return
  const tmp = { id: `tmp-${Date.now()}`, jid: keyOf(to), fromMe: true, ts: Date.now(), kind: 'voice', text: null, meta: { seconds: null, wave: null }, title: item.title, pending: true }
  S.msgs.push(tmp)
  renderMsgs({ bottom: true })
  setTileState(el, 'sending')
  try {
    const { entry, message } = await post('/api/send', { to: to.jid, mp3: item.mp3, title: item.title, color: item.color ?? null })
    S.sent.push(entry)
    indexSent()
    replaceTmp(tmp, message)
    setTileState(el, 'sent')
    setTimeout(() => setTileState(el, 'idle'), 1400)
    if (S.expanded) toast(`Sent “${item.title}” to ${personOf(to).display}`, { ms: 8000, undo: () => post('/api/undo', { jid: message.jid, id: message.id }).then(() => { S.msgs = S.msgs.filter((x) => x.id !== message.id); renderMsgs() }) })
  } catch (e) {
    S.msgs = S.msgs.filter((x) => x !== tmp)
    renderMsgs()
    setTileState(el, 'idle')
    toast(e.message, { kind: 'err' })
  }
}

const textInput = $('#textInput')
const sendBtn = $('#sendText')

function autosize() {
  textInput.style.height = 'auto'
  textInput.style.height = `${Math.min(textInput.scrollHeight + 2, 132)}px`
  textInput.style.overflowY = textInput.scrollHeight + 2 > 132 ? 'auto' : 'hidden'
}
const syncSendBtn = () => { sendBtn.disabled = !textInput.value.trim() }

async function sendText() {
  const text = textInput.value.replace(/\s+$/, '')
  const to = S.recipient
  if (!text || !ready(to)) return
  const tmp = { id: `tmp-${Date.now()}`, jid: keyOf(to), fromMe: true, ts: Date.now(), kind: 'text', text, pending: true }
  S.msgs.push(tmp)
  textInput.value = ''
  autosize()
  syncSendBtn()
  renderMsgs({ bottom: true })
  try {
    const { message } = await post('/api/text', { to: to.jid, text })
    replaceTmp(tmp, message)
  } catch (e) {
    S.msgs = S.msgs.filter((x) => x !== tmp)
    renderMsgs()
    textInput.value = text
    autosize()
    syncSendBtn()
    toast(e.message, { kind: 'err' })
  }
}

// ---------- live events ----------

function onEvent(ev) {
  if (ev.t === 'msg') {
    touchChat(ev.msg)
    if (ev.msg.jid === keyOf(S.recipient)) addMessage(ev.msg)
  } else if (ev.t === 'revoke') {
    if (ev.jid === keyOf(S.recipient) && S.msgs.some((m) => m.id === ev.id)) { S.msgs = S.msgs.filter((m) => m.id !== ev.id); renderMsgs() }
    loadChatsSoon()
  } else if (ev.t === 'edit') {
    const m = S.msgs.find((x) => x.id === ev.id)
    if (m) { m.text = ev.text; renderMsgs() }
  } else if (ev.t === 'history') {
    loadChatsSoon()
    if (!S.olderBusy) refreshMessages()
  } else if (ev.t === 'progress') {
    const was = S.wa.history?.active
    S.wa.history = ev.history
    renderMeLine()
    if (was && !ev.history.active) { toast('Recent chats synced', { ms: 5000 }); loadChats() }
  }
}

function connectEvents() {
  const es = new EventSource('/api/events')
  es.onmessage = (e) => { try { onEvent(JSON.parse(e.data)) } catch { /* ignore malformed event */ } }
  // The browser reconnects by itself; catch up on anything missed meanwhile.
  es.onopen = () => { loadChatsSoon(); if (S.msgs.length) refreshMessages() }
}

// ---------- connection state ----------

function renderGate() {
  const gate = $('#gate')
  const card = $('#gateCard')
  const s = S.wa.status
  if (S.offline) {
    gate.hidden = false
    card.replaceChildren(icon('plugs'), h('h1', {}, 'Can’t reach the app'), h('p', {}, 'Voice Drop is not running. Start it again with npm start in the project folder.'))
  } else if (s === 'qr') {
    gate.hidden = false
    card.replaceChildren(
      h('h1', {}, 'Link WhatsApp'),
      S.wa.qr ? h('img', { src: S.wa.qr, alt: 'WhatsApp link QR code', width: 244, height: 244 }) : h('div', { class: 'qr-skel' }),
      h('ol', {}, h('li', {}, 'Open WhatsApp on your phone'), h('li', {}, 'Go to Settings, then Linked devices'), h('li', {}, 'Tap Link a device and scan this code')),
      h('p', {}, 'After linking, your phone sends your recent chats. It takes about a minute.'))
  } else if (s === 'logged-out') {
    gate.hidden = false
    card.replaceChildren(
      h('h1', {}, 'WhatsApp was unlinked'),
      h('p', {}, 'This device was removed from Linked devices. Link it again to keep sending.'),
      h('button', { class: 'btn', type: 'button', onclick: async () => { await post('/api/relink').catch(() => {}); poll() } }, 'Link again'))
  } else {
    gate.hidden = true
  }
}

function renderMeLine() {
  const { status, syncing, me, history } = S.wa
  $('#meLine').textContent = history?.active ? `Syncing recent chats${history.progress ? ` ${history.progress}%` : ''} · ${(history.imported ?? 0).toLocaleString()} messages`
    : status === 'open' ? (syncing ? 'Syncing contacts...' : `Connected as +${(me?.jid ?? '').replace(/@.*/, '').replace(/\D/g, '')}`)
      : status === 'connecting' ? 'Connecting to WhatsApp...' : 'Not connected'
  $('#refreshPeople').classList.toggle('spin', !!syncing)
}

let pollTimer
async function poll() {
  clearTimeout(pollTimer)
  try {
    const s = await api('/api/state')
    S.offline = false
    const wasOpen = S.wa.status === 'open'
    S.wa = s
    const key = `${s.status === 'open'}:${s.contacts}:${s.groups}:${s.syncing}`
    if (key !== S.peopleKey) { S.peopleKey = key; await loadPeople() }
    if (s.me?.jid !== S.meJid) { S.meJid = s.me?.jid; await loadChats(); await loadMessages(true) }
    else if (s.status === 'open' && !wasOpen) { loadChatsSoon(); refreshMessages() }
  } catch {
    S.offline = true
  }
  renderGate()
  renderMeLine()
  pollTimer = setTimeout(poll, S.wa.status === 'open' && !S.wa.syncing ? 8000 : 1500)
}

// ---------- wiring ----------

function focusField() {
  if (isMobile()) return
  const useText = S.lastField === 'text' || !S.drawer
  ;(useText ? textInput : $('#soundSearch')).focus()
}

function setDrawer(on, remember = true) {
  S.drawer = on
  if (remember) store.set('vd.drawer', on) // only an explicit toggle is remembered, never the default
  $('#chat').dataset.drawer = on ? 'open' : 'closed'
  const btn = $('#toggleDrawer')
  btn.classList.toggle('on', on)
  btn.setAttribute('aria-pressed', on)
  btn.setAttribute('aria-label', on ? 'Hide sounds' : 'Show sounds')
  if (!on && S.expanded) toggleExpand()
  if (on) maybeMore()
  const nearBottom = $('#chatBody').scrollHeight - $('#chatBody').scrollTop - $('#chatBody').clientHeight < 200
  if (nearBottom) requestAnimationFrame(() => { $('#chatBody').scrollTop = $('#chatBody').scrollHeight })
}

function toggleExpand() {
  S.expanded = !S.expanded
  $('#chat').dataset.expanded = S.expanded
  const btn = $('#expand')
  btn.setAttribute('aria-pressed', S.expanded)
  btn.setAttribute('aria-label', S.expanded ? 'Show conversation' : 'Show more sounds')
  btn.replaceChildren(icon(S.expanded ? 'arrows-in-simple' : 'arrows-out-simple'))
  maybeMore()
}

let soundTimer
// Returns true when the box held a new query that has now started loading.
function applySoundQuery() {
  clearTimeout(soundTimer)
  const q = $('#soundSearch').value.trim()
  if (q === S.q) return false
  S.q = q
  renderTabs()
  scroller.scrollTo({ top: 0 })
  loadSounds(true)
  return true
}
$('#soundSearch').addEventListener('input', () => { clearTimeout(soundTimer); soundTimer = setTimeout(applySoundQuery, 250) })
$('#soundSearch').addEventListener('keydown', (e) => {
  const inp = e.target
  const t = S.tiles[S.hl]
  if (e.key === 'Enter') {
    e.preventDefault()
    // The grid still shows the previous query's sounds: load the new ones first, never send a stale highlight.
    if (applySoundQuery() || (S.loading && S.page === 0)) return
    if (t) e.shiftKey ? startPlay(srcOf(t._item.mp3), t._ui) : sendTile(t)
  } else if (e.key === 'ArrowDown') { e.preventDefault(); setHl(S.hl + columns()) }
  else if (e.key === 'ArrowUp') { e.preventDefault(); setHl(S.hl - columns()) }
  else if (e.key === 'ArrowRight' && inp.selectionStart === inp.value.length) { e.preventDefault(); setHl(S.hl + 1) }
  else if (e.key === 'ArrowLeft' && inp.selectionStart === 0 && inp.selectionEnd === 0) { e.preventDefault(); setHl(S.hl - 1) }
  else if (e.key === 'Escape') {
    if (inp.value) { inp.value = ''; inp.dispatchEvent(new Event('input')) } else { stopPlay(); inp.blur() }
  }
})
$('#soundSearch').addEventListener('focus', () => { S.lastField = 'sound'; $('#enterHint').textContent = '↵ send' })
$('#soundSearch').addEventListener('blur', () => { $('#enterHint').textContent = '/' })

textInput.addEventListener('focus', () => { S.lastField = 'text' })
textInput.addEventListener('input', () => { autosize(); syncSendBtn() })
textInput.addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); sendText() }
  else if (e.key === 'Escape') textInput.blur()
})
$('#composer').addEventListener('submit', (e) => { e.preventDefault(); sendText() })
$('#toggleDrawer').addEventListener('click', () => { setDrawer(!S.drawer); focusField() })

$('#peopleSearch').addEventListener('input', (e) => { S.pq = e.target.value; renderPeople() })
$('#peopleSearch').addEventListener('keydown', (e) => {
  if (e.key === 'ArrowDown') { e.preventDefault(); setKbd(S.kbd + 1) }
  else if (e.key === 'ArrowUp') { e.preventDefault(); setKbd(S.kbd - 1) }
  else if (e.key === 'Enter' && S.rows[S.kbd]) { e.preventDefault(); S.rows[S.kbd].click() }
  else if (e.key === 'Escape') { e.target.value = ''; S.pq = ''; renderPeople() }
})

$('#expand').addEventListener('click', toggleExpand)
$('#refreshPeople').addEventListener('click', async () => {
  $('#refreshPeople').classList.add('spin')
  await post('/api/people/refresh').catch((e) => toast(e.message, { kind: 'err' }))
  poll()
})

addEventListener('keydown', (e) => {
  const typing = /^(INPUT|TEXTAREA)$/.test(document.activeElement?.tagName)
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    if (isMobile()) setScreen('list')
    $('#peopleSearch').focus()
    $('#peopleSearch').select()
  } else if (e.key === 'Escape' && !typing) {
    stopPlay()
  } else if (!e.metaKey && !e.ctrlKey && !e.altKey && e.key.length === 1 && document.activeElement === document.body && $('#gate').hidden && textInput.offsetParent) {
    // Type anywhere: the character lands in whichever field you used last.
    const target = S.lastField === 'text' || !S.drawer ? textInput : $('#soundSearch')
    target.focus()
    if (e.key === '/' && target !== textInput) e.preventDefault()
  }
})

await loadPrefs()
await loadSent()
if (!S.src) S.src = S.favs.length ? 'favs' : 'ma'
setDrawer(S.drawer, false)
renderChips()
renderTabs()
renderChatHead()
renderMsgs({ bottom: true })
renderPeople()
setScreen(isMobile() && S.recipient ? 'chat' : 'list')
loadSounds(true)
connectEvents()
poll()
focusField()
