/**
 * 文档元信息虚拟模块：把 docs/ 下每个 Markdown 的本地修改日期提供给前端。
 *
 * 前端只打包正文（?raw），拿不到 mtime；这里在 dev/build 时扫一遍目录，
 * 通过 `virtual:docs-meta` 注入 `{ 'guides/setup.md': '2026-11-18', ... }`。
 * dev 下文档变化触发一次整页刷新，保证「更新」时间不会停留在启动时刻。
 */
import { readdirSync, statSync } from 'node:fs'
import { join, normalize, relative } from 'node:path'
import type { Plugin } from 'vite'

const DOC_DIRS = ['', 'guides', 'design', 'task-checklist', 'reports'] as const
const VIRTUAL_ID = 'virtual:docs-meta'
const RESOLVED_ID = `\0${VIRTUAL_ID}`

function localDate(date: Date): string {
  const pad = (n: number): string => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

export function docsMetaPlugin(docsRoot: string): Plugin {
  const root = normalize(docsRoot)

  function collect(): string {
    const dates: Record<string, string> = {}
    for (const dir of DOC_DIRS) {
      const folder = dir ? join(root, dir) : root
      let files: string[]
      try {
        files = readdirSync(folder)
      } catch {
        continue
      }
      for (const file of files) {
        if (!file.toLowerCase().endsWith('.md')) continue
        const full = join(folder, file)
        const rel = relative(root, full).split('\\').join('/')
        dates[rel] = localDate(statSync(full).mtime)
      }
    }
    return `export default ${JSON.stringify(dates)}`
  }

  return {
    name: 'docs-meta',
    resolveId(source) {
      return source === VIRTUAL_ID ? RESOLVED_ID : null
    },
    load(id) {
      return id === RESOLVED_ID ? collect() : null
    },
    configureServer(server) {
      server.watcher.add(root)
      const refresh = (file: string): void => {
        if (!normalize(file).startsWith(root) || !file.toLowerCase().endsWith('.md')) return
        const mod = server.moduleGraph.getModuleById(RESOLVED_ID)
        if (mod) server.moduleGraph.invalidateModule(mod)
        server.ws.send({ type: 'full-reload' })
      }
      server.watcher.on('change', refresh)
      server.watcher.on('add', refresh)
      server.watcher.on('unlink', refresh)
    },
  }
}
