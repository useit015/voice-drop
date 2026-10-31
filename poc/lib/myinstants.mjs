// myinstants.com scraping: listings, search, and resolving a sound to its mp3.
import { basename } from 'node:path'

export const BASE = 'https://www.myinstants.com'
export const UA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36'
export const DEFAULT_LIST = `${BASE}/en/index/ma/`

export const SOURCES = {
  ma: '/en/index/ma/',
  trending: '/en/trending/',
  best: '/en/best_of_all_time/',
  new: '/en/recent/',
}

const pages = new Map() // url -> { at, html }
const TTL = 5 * 60 * 1000

export async function getHtml(url, { ttl = TTL } = {}) {
  const hit = pages.get(url)
  if (hit && Date.now() - hit.at < ttl) return hit.html
  const res = await fetch(url, { headers: { 'user-agent': UA } })
  if (res.status === 404) return ''
  if (!res.ok) throw new Error(`myinstants answered ${res.status}`)
  const html = await res.text()
  pages.set(url, { at: Date.now(), html })
  if (pages.size > 200) pages.delete(pages.keys().next().value)
  return html
}

const decode = (s) => s.replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#x27;|&#39;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>')

export function parseListing(html) {
  const out = []
  for (const chunk of html.split('<div class="instant">').slice(1)) {
    const play = chunk.match(/play\('([^']+)'/)
    const link = chunk.match(/<a href="([^"]+)"[^>]*class="instant-link[^"]*"[^>]*>([^<]*)<\/a>/)
    if (!play || !link) continue
    out.push({
      title: decode(link[2]).trim(),
      mp3: new URL(decode(play[1]), BASE).href,
      page: new URL(link[1], BASE).href,
      color: chunk.match(/background-color:\s*(#[0-9a-fA-F]{6})/)?.[1] ?? null,
    })
  }
  return out
}

export async function listPage(url) {
  return parseListing(await getHtml(url))
}

// One page of results for the UI: a search query wins over the named source.
export async function browse({ src = 'ma', q = '', page = 1 } = {}) {
  q = q.trim()
  page = Math.max(1, Math.min(Number(page) || 1, 50))
  const url = q
    ? `${BASE}/en/search/?name=${encodeURIComponent(q)}&page=${page}`
    : `${BASE}${SOURCES[src] ?? SOURCES.ma}${page > 1 ? `?page=${page}` : ''}`
  const html = await getHtml(url)
  return { items: parseListing(html), hasMore: html.includes(`page=${page + 1}`) }
}

export const search = async (q) => (await browse({ q })).items

// Only ever fetch audio from myinstants itself.
export function assertSoundUrl(u) {
  let url
  try { url = new URL(u) } catch { throw new Error('bad sound url') }
  const host = url.hostname.replace(/^www\./, '')
  if (url.protocol !== 'https:' || host !== 'myinstants.com' || !url.pathname.startsWith('/media/sounds/')) {
    throw new Error('not a myinstants sound')
  }
  return url.href
}

// instant page URL | mp3 URL | "q:<search>"  ->  { title, url }
export async function resolveSound(spec) {
  if (spec.startsWith('q:')) {
    const hits = await search(spec.slice(2))
    if (!hits.length) throw new Error(`no results for "${spec.slice(2)}"`)
    return { title: hits[0].title, url: hits[0].mp3 }
  }
  const url = new URL(spec, BASE)
  if (url.hostname !== 'www.myinstants.com' && url.hostname !== 'myinstants.com') throw new Error(`not a myinstants URL: ${spec}`)
  if (/\.(mp3|ogg|wav|m4a)$/i.test(url.pathname)) return { title: basename(url.pathname), url: assertSoundUrl(url.href) }
  const html = await getHtml(url.href)
  const raw = html.match(/<meta property="og:audio" content="([^"]+)"/)?.[1] ?? html.match(/play\('([^']+)'/)?.[1]
  if (!raw) throw new Error(`no audio found on ${spec}`)
  const title = decode(html.match(/<meta property="og:title" content="([^"]*?)(?: - Sound Button)?"/)?.[1] ?? basename(url.pathname))
  return { title, url: assertSoundUrl(new URL(decode(raw), BASE).href) }
}
