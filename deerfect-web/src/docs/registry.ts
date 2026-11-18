/**
 * 文档中心数据源：前端直接打包仓库 `docs/` 下的 Markdown（Vite `?raw` + eager glob）。
 *
 * - 不经过后端、不入库；dev 下改文档会触发 HMR，生产构建后随包发布；
 * - 只收录导航里的五个分组（概览 / 指南 / 设计 / 任务清单 / 报告），
 *   archive/ 属历史存档，不打包进前端；
 * - front-matter 只取 title / order / updated：order 只影响概览排序与 01–10 序号；
 * - `*.en.md` 与同目录同名中文稿折叠为一条，默认展示中文，树里用 EN 徽标切换；
 * - 报告的 project-intro.*（md/html/pdf 导出稿）不另算条目；
 * - 搜索是全量正文扫描：文档总量 ~700KB，一次性打包后本地搜索无网络开销。
 */

import docDates from 'virtual:docs-meta'

const MODULES = import.meta.glob(
  [
    '../../../docs/*.md',
    '../../../docs/guides/*.md',
    '../../../docs/design/*.md',
    '../../../docs/task-checklist/*.md',
    '../../../docs/reports/*.md',
  ],
  { query: '?raw', import: 'default', eager: true },
) as Record<string, string>

/** 报告的 md/html/pdf 导出稿不单独占目录条目 */
const EXCLUDED_PATHS = new Set(['reports/project-intro.md'])

export interface DocItem {
  /** docs 内相对路径，如 guides/setup.md */
  path: string
  name: string
  /** front-matter title 优先，否则首个 H1，最后回落到文件名 */
  title: string
  /** UTF-8 字节数（不含 front-matter） */
  size: number
  group: string
  /** front-matter order：概览组用于排序与 01–10 序号 */
  order?: number
  /** 文档语言：`.en.md` 为英文变体 */
  lang: 'zh' | 'en'
  /** front-matter updated（YYYY-MM-DD），没有则不展示更新时间 */
  updated?: string
  /** 中文条目对应的英文变体路径；树里折叠为一条，EN 徽标切换 */
  enPath?: string
}

export interface DocGroup {
  key: string
  label: string
  count: number
  docs: DocItem[]
}

export interface DocSearchHit {
  path: string
  title: string
  group: string
  snippet: string
}

interface DocMeta {
  title?: string
  order?: number
  updated?: string
}

interface DocEntry extends DocItem {
  markdown: string
}

/** 分组白名单与展示顺序；dir 为空表示 docs 根层 */
const SPECS: { key: string; label: string; dir: string }[] = [
  { key: 'overview', label: '概览', dir: '' },
  { key: 'guides', label: '指南', dir: 'guides' },
  { key: 'design', label: '设计', dir: 'design' },
  { key: 'tasks', label: '任务清单', dir: 'task-checklist' },
  { key: 'reports', label: '报告', dir: 'reports' },
]

/** 新建文档时可选的落盘目录（相对 docs/） */
export const DOC_GROUP_DIRS: Record<string, string> = {
  overview: '',
  guides: 'guides',
  design: 'design',
  tasks: 'task-checklist',
  reports: 'reports',
}

function relPath(key: string): string {
  const marker = '/docs/'
  const at = key.lastIndexOf(marker)
  return at >= 0 ? key.slice(at + marker.length) : key.replace(/^\.\.\/+/, '')
}

/** 标题里的开头 emoji（🧭 / 🏗️ / 🚀 …）不进目录与页头，保持纯文字 */
function stripLeadingEmoji(title: string): string {
  return title.replace(/^[\p{Extended_Pictographic}\uFE0F\u200D\s]+/u, '').trim()
}

/** 首个 H1 作标题；没有 H1 时去掉 .md 用文件名 */
export function titleOf(markdown: string, fallback: string): string {
  for (const line of markdown.split(/\r?\n/, 80)) {
    const trimmed = line.trim()
    if (trimmed.startsWith('# ')) {
      const title = stripLeadingEmoji(trimmed.slice(2))
      if (title) return title
    }
  }
  return fallback.replace(/\.md$/i, '')
}

/** 解析最小 front-matter：title / order / updated（不引入 YAML 依赖） */
function parseFrontMatter(raw: string): { meta: DocMeta; body: string } {
  const clean = raw.replace(/^\uFEFF/, '')
  const match = clean.match(/^---\r?\n([\s\S]*?)\r?\n---[ \t]*\r?\n?/)
  if (!match) return { meta: {}, body: clean }
  const meta: DocMeta = {}
  for (const line of match[1].split(/\r?\n/)) {
    const pair = line.match(/^([A-Za-z_-]+):\s*(.*)$/)
    if (!pair) continue
    const key = pair[1].toLowerCase()
    let value = pair[2].trim()
    if (
      (value.startsWith('"') && value.endsWith('"'))
      || (value.startsWith("'") && value.endsWith("'"))
    ) {
      value = value.slice(1, -1)
    }
    if (key === 'title' && value) {
      meta.title = value
    } else if (key === 'order') {
      const order = Number(value)
      if (Number.isFinite(order)) meta.order = order
    } else if (key === 'updated' && value) {
      meta.updated = value
    }
  }
  return { meta, body: clean.slice(match[0].length) }
}

function groupKeyOf(path: string): string {
  const top = path.includes('/') ? path.split('/')[0] : ''
  return SPECS.find((spec) => spec.dir === top)?.key ?? ''
}

function langOf(name: string): 'zh' | 'en' {
  return /\.en\.md$/i.test(name) ? 'en' : 'zh'
}

/** 折叠英文变体用的键：project-structure.en.md → project-structure */
function baseOf(name: string, lang: 'zh' | 'en'): string {
  return lang === 'en' ? name.replace(/\.en\.md$/i, '') : name.replace(/\.md$/i, '')
}

const ENTRIES: DocEntry[] = Object.entries(MODULES)
  .map(([key, raw]) => {
    const path = relPath(key)
    const name = path.split('/').pop() ?? path
    const lang = langOf(name)
    const { meta, body } = parseFrontMatter(raw)
    return {
      path,
      name,
      title: stripLeadingEmoji(meta.title ?? '') || titleOf(body, name),
      size: new TextEncoder().encode(body).length,
      group: groupKeyOf(path),
      order: meta.order,
      lang,
      updated: meta.updated ?? docDates[path],
      markdown: body,
    }
  })
  .filter((entry) => !EXCLUDED_PATHS.has(entry.path))

const BY_PATH = new Map(ENTRIES.map((entry) => [entry.path, entry]))

function isIndexName(name: string): boolean {
  const lower = name.toLowerCase()
  return lower === 'index.md' || lower === 'readme.md'
}

/** 概览按 front-matter order；其余分组 index/readme 置顶，再按标题 */
function compareDocs(groupKey: string) {
  return (a: DocItem, b: DocItem): number => {
    if (groupKey === 'overview') {
      const ao = a.order ?? Number.MAX_SAFE_INTEGER
      const bo = b.order ?? Number.MAX_SAFE_INTEGER
      if (ao !== bo) return ao - bo
    }
    const ai = isIndexName(a.name) ? 0 : 1
    const bi = isIndexName(b.name) ? 0 : 1
    if (ai !== bi) return ai - bi
    return a.title.localeCompare(b.title, 'zh-Hans-CN')
  }
}

/** 对外只暴露摘要，不把正文随目录树一起传给组件 */
function toItem(entry: DocEntry): DocItem {
  return {
    path: entry.path,
    name: entry.name,
    title: entry.title,
    size: entry.size,
    group: entry.group,
    order: entry.order,
    lang: entry.lang,
    updated: entry.updated,
  }
}

/** 目录树：英文变体折叠进中文条目，计数按折叠后的真实条目数 */
export const DOC_GROUPS: DocGroup[] = SPECS.map((spec) => {
  const buckets = new Map<string, { zh?: DocEntry; en?: DocEntry }>()
  for (const entry of ENTRIES) {
    if (entry.group !== spec.key) continue
    const base = baseOf(entry.name, entry.lang)
    const bucket = buckets.get(base) ?? {}
    if (entry.lang === 'en') bucket.en = entry
    else bucket.zh = entry
    buckets.set(base, bucket)
  }
  const docs = Array.from(buckets.values())
    .map(({ zh, en }) => {
      const primary = zh ?? en
      if (!primary) return null
      const item = toItem(primary)
      return zh && en ? { ...item, enPath: en.path } : item
    })
    .filter((item): item is DocItem => item !== null)
    .sort(compareDocs(spec.key))
  // 每个分组内按当前展示顺序补两位序号：概览用 front-matter order，其余组用 1..N
  docs.forEach((doc, index) => {
    if (doc.order == null) doc.order = index + 1
  })
  return { key: spec.key, label: spec.label, count: docs.length, docs }
})

const GROUP_LABEL = new Map(DOC_GROUPS.map((group) => [group.key, group.label]))

/** 英文变体沿用中文条目的分组序号，元信息条在英文页也能显示 order */
const DISPLAY_ORDER = new Map<string, number>()
for (const group of DOC_GROUPS) {
  for (const doc of group.docs) {
    if (doc.order != null) DISPLAY_ORDER.set(doc.path, doc.order)
    if (doc.enPath && doc.order != null) DISPLAY_ORDER.set(doc.enPath, doc.order)
  }
}

export function findDoc(path: string): DocItem | undefined {
  const entry = BY_PATH.get(path)
  if (!entry) return undefined
  const item = toItem(entry)
  const order = DISPLAY_ORDER.get(path)
  return order != null ? { ...item, order } : item
}

export function docMarkdown(path: string): string | null {
  return BY_PATH.get(path)?.markdown ?? null
}

export function docGroupLabel(path: string): string {
  const entry = BY_PATH.get(path)
  return entry ? (GROUP_LABEL.get(entry.group) ?? '文档') : '文档'
}

export function docGroupKey(path: string): string {
  return BY_PATH.get(path)?.group ?? ''
}

/** 相对链接解析：index.md 里的 ./guides/setup.md、../principal.md 都能落到 docs 内路径 */
export function resolveDocLink(currentPath: string, href: string): string {
  const clean = href.split(/[?#]/)[0]
  // 外链 / 锚点原样返回，交给调用方决定是否放行
  if (/^(?:[a-z][a-z0-9+.-]*:)?\/\//i.test(clean) || clean.startsWith('#')) return clean
  const stack = currentPath.split('/').slice(0, -1)
  for (const part of clean.split('/')) {
    if (!part || part === '.') continue
    if (part === '..') stack.pop()
    else stack.push(part)
  }
  return stack.join('/')
}

function snippetOf(text: string, index: number): string {
  if (index < 0) {
    const flat = text.replace(/\s+/g, ' ').trim()
    return flat.length <= 120 ? flat : `${flat.slice(0, 120)}…`
  }
  const start = Math.max(0, index - 60)
  const end = Math.min(text.length, index + 60)
  const flat = text.slice(start, end).replace(/\s+/g, ' ').trim()
  return `${start > 0 ? '…' : ''}${flat}${end < text.length ? '…' : ''}`
}

/** 全文搜索：标题 / 文件名 / 正文，最多 20 条，按分组顺序 */
export function searchDocs(query: string): DocSearchHit[] {
  const term = query.trim().toLowerCase()
  if (!term) return []
  const hits: DocSearchHit[] = []
  for (const group of DOC_GROUPS) {
    for (const item of group.docs) {
      const entry = BY_PATH.get(item.path)
      if (!entry) continue
      const index = entry.markdown.toLowerCase().indexOf(term)
      const inTitle = item.title.toLowerCase().includes(term)
      const inName = item.name.toLowerCase().includes(term)
      if (index < 0 && !inTitle && !inName) continue
      hits.push({
        path: item.path,
        title: item.title,
        group: group.label,
        snippet: snippetOf(entry.markdown, index),
      })
      if (hits.length >= 20) return hits
    }
  }
  return hits
}

/** 新建文档只能生成模板：浏览器无写盘权限，下载后由用户放进 docs/ */
export function buildDocTemplate(group: string, filename: string, title: string): { path: string; markdown: string } {
  const dir = DOC_GROUP_DIRS[group] ?? ''
  const heading = title.trim() || filename.replace(/\.md$/i, '')
  const markdown = `---\ntitle: "${heading}"\n---\n\n# ${heading}\n\n> 由 Web 文档中心生成 · ${new Date().toISOString().slice(0, 10)}\n\n## 概述\n\n（待补充）\n`
  return { path: dir ? `${dir}/${filename}` : filename, markdown }
}

/** 文件名：单层、以 .md 结尾，允许中文 / 字母 / 数字 / 点下划线短横线 */
export function isValidDocFilename(name: string): boolean {
  return /^[\p{Script=Han}A-Za-z0-9][\p{Script=Han}A-Za-z0-9._-]*\.md$/u.test(name.trim()) && !name.includes('..')
}
