import { readFile } from 'node:fs/promises'
import { createServer } from 'node:http'
import { extname, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const pages = process.argv[2] === 'pages'
const base = pages ? '/Deerfect_harness/' : '/'
const root = fileURLToPath(new URL('../dist/', import.meta.url))
const fallback = await readFile(resolve(root, pages ? '404.html' : 'index.html'))
const types = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json',
  '.webmanifest': 'application/manifest+json',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
}

createServer(async (request, response) => {
  let pathname
  try {
    pathname = decodeURIComponent(new URL(request.url, 'http://127.0.0.1').pathname)
  } catch {
    response.writeHead(400).end()
    return
  }
  if (!pathname.startsWith(base)) {
    response.writeHead(404).end()
    return
  }
  const path = resolve(root, pathname.slice(base.length) || 'index.html')
  if (!path.startsWith(resolve(root) + sep)) {
    response.writeHead(403).end()
    return
  }
  let body
  let status = 200
  let type = types[extname(path)] ?? 'application/octet-stream'
  try {
    body = await readFile(path)
  } catch (error) {
    if (error.code !== 'ENOENT' && error.code !== 'EISDIR') {
      response.writeHead(500).end()
      return
    }
    status = pages ? 404 : 200
    type = types['.html']
    body = fallback
  }
  // 禁用 HTTP 缓存，离线刷新只能依靠真实 Service Worker 缓存。
  response.writeHead(status, { 'Content-Type': type, 'Cache-Control': 'no-store' })
  response.end(body)
}).listen(4175, '127.0.0.1', () => {
  console.log(`Serving built ${pages ? 'Pages' : 'PWA'} at http://127.0.0.1:4175${base}`)
})
