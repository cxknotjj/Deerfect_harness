/**
 * 文档中心数据源：前端直接打包仓库 `docs/` 下的 Markdown（Vite `?raw` + eager glob）。
 *
 * - 不经过后端、不入库；dev 下改文档会触发 HMR，生产构建后随包发布；
 * - 只收录导航里的五个分组（概览 / 指南 / 设计 / 任务清单 / 报告），
 *   archive/ 属历史存档，不打包进前端（README 里提到但不做跳转）；
 * - 搜索是全量正文扫描：文档总量 ~700KB，一次性打包后本地搜索无网络开销。
 */

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

export interface DocItem {
  /** docs 内相对路径，如 guides/setup.md */
  path: string
  name: string
  /** 首个 H1；缺失时回落到文件名 */
  title: string
  /** UTF-8 字节数 */
  size: number
  group: string
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

/** 标题里的开头 emoji（🧭 / 🏗️ / 🚀 …）不进目录与页头，保持与文档导航一致的纯文字 */
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

function groupKeyOf(path: string): string {
  const top = path.includes('/') ? path.split('/')[0] : ''
  return SPECS.find((spec) => spec.dir === top)?.key ?? ''
}

const ENTRIES: DocEntry[] = Object.entries(MODULES).map(([key, markdown]) => {
  const path = relPath(key)
  const name = path.split('/').pop() ?? path
  return {
    path,
    name,
    title: titleOf(markdown, name),
    size: new TextEncoder().encode(markdown).length,
    group: groupKeyOf(path),
    markdown,
  }
})

const BY_PATH = new Map(ENTRIES.map((entry) => [entry.path, entry]))

function readmeFirst(a: DocEntry, b: DocEntry): number {
  const ar = a.name.toLowerCase() === 'readme.md' ? 0 : 1
  const br = b.name.toLowerCase() === 'readme.md' ? 0 : 1
  if (ar !== br) return ar - br
  return a.title.localeCompare(b.title, 'zh-Hans-CN')
}

/** 概览按文档导航的阅读顺序排（README 索引 → 功能 → 架构 → …），其余分组按标题 */
const OVERVIEW_ORDER = [
  'README.md',
  'features.md',
  'architecture.md',
  'data-flow.md',
  'TECH_STACK.md',
  'project-structure.md',
  'project-structure_EN.md',
  'principal.md',
  'dev.md',
  'ops.md',
  'HARNESS_TODO.md',
]

function compareDocs(groupKey: string) {
  return (a: DocEntry, b: DocEntry): number => {
    if (groupKey === 'overview') {
      const ai = OVERVIEW_ORDER.indexOf(a.name)
      const bi = OVERVIEW_ORDER.indexOf(b.name)
      if (ai !== bi) return (ai < 0 ? 999 : ai) - (bi < 0 ? 999 : bi)
    }
    return readmeFirst(a, b)
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
  }
}

/** 目录树：只含白名单分组，README 置顶，其余按标题排序 */
export const DOC_GROUPS: DocGroup[] = SPECS.map((spec) => {
  const docs = ENTRIES
    .filter((entry) => entry.group === spec.key)
    .sort(compareDocs(spec.key))
    .map(toItem)
  return { key: spec.key, label: spec.label, count: docs.length, docs }
})

const GROUP_LABEL = new Map(DOC_GROUPS.map((group) => [group.key, group.label]))

export function findDoc(path: string): DocItem | undefined {
  const entry = BY_PATH.get(path)
  return entry ? toItem(entry) : undefined
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

/** 相对链接解析：README 里的 ./guides/setup.md、../principal.md 都能落到 docs 内路径 */
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
  const markdown = `# ${heading}\n\n> 由 Web 文档中心生成 · ${new Date().toISOString().slice(0, 10)}\n\n## 概述\n\n（待补充）\n`
  return { path: dir ? `${dir}/${filename}` : filename, markdown }
}

/** 文件名：单层、以 .md 结尾，允许中文 / 字母 / 数字 / 点下划线短横线 */
export function isValidDocFilename(name: string): boolean {
  return /^[\p{Script=Han}A-Za-z0-9][\p{Script=Han}A-Za-z0-9._-]*\.md$/u.test(name.trim()) && !name.includes('..')
}
