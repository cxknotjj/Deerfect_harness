import { describe, expect, it } from 'vitest'
import {
  DOC_GROUPS,
  buildDocTemplate,
  docMarkdown,
  findDoc,
  isValidDocFilename,
  resolveDocLink,
  searchDocs,
} from './registry'

describe('文档注册表', () => {
  it('按五个分组索引 docs，索引置顶并带 front-matter 序号', () => {
    expect(DOC_GROUPS.map((g) => g.label)).toEqual(['概览', '指南', '设计', '任务清单', '报告'])
    const overview = DOC_GROUPS[0]
    expect(overview.docs[0].path).toBe('index.md')
    expect(overview.docs[0].title).toBe('文档导航')
    expect(overview.docs[0].order).toBe(1)
    expect(overview.docs.map((d) => d.order)).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9, 10])
    // 其他分组也按展示顺序补 1..N 序号
    expect(DOC_GROUPS[1].docs.map((d) => d.order)).toEqual(
      Array.from({ length: DOC_GROUPS[1].count }, (_, i) => i + 1),
    )
  })

  it('组计数按折叠后的真实目录重算', () => {
    const counts = Object.fromEntries(DOC_GROUPS.map((g) => [g.key, g.count]))
    expect(counts).toEqual({ overview: 10, guides: 15, design: 15, tasks: 13, reports: 4 })
  })

  it('英文变体折叠成一条：中文条目带 enPath，树里不再占行', () => {
    const structure = DOC_GROUPS[0].docs.find((d) => d.path === 'project-structure.md')
    expect(structure?.enPath).toBe('project-structure.en.md')
    expect(DOC_GROUPS.some((g) => g.docs.some((d) => d.path.endsWith('.en.md')))).toBe(false)
    expect(findDoc('project-structure.en.md')?.lang).toBe('en')
  })

  it('正文按路径取回，front-matter 不进正文', () => {
    const markdown = docMarkdown('index.md')
    expect(markdown).toContain('## 项目约束')
    expect(markdown?.startsWith('---')).toBe(false)
    expect(findDoc('index.md')?.updated).toBe('2026-11-14')
    expect(findDoc('guides/setup.md')?.title).toBeTruthy()
    // 没有 front-matter 的文档用本地文件修改日期，保证每篇都有「更新」
    expect(findDoc('guides/setup.md')?.updated).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    expect(docMarkdown('nope.md')).toBeNull()
  })

  it('搜索命中标题与正文', () => {
    const hits = searchDocs('项目约束')
    expect(hits.length).toBeGreaterThan(0)
    expect(hits[0].path).toBe('index.md')
    expect(hits[0].snippet.length).toBeGreaterThan(0)
  })

  it('相对链接落在 docs 内', () => {
    expect(resolveDocLink('index.md', './guides/setup.md')).toBe('guides/setup.md')
    expect(resolveDocLink('guides/setup.md', '../principal.md')).toBe('principal.md')
    expect(resolveDocLink('index.md', 'https://example.com/a.md')).toBe('https://example.com/a.md')
  })

  it('模板路径与文件名校验', () => {
    const template = buildDocTemplate('guides', 'new-doc.md', '新文档')
    expect(template.path).toBe('guides/new-doc.md')
    expect(template.markdown.startsWith('---')).toBe(true)
    expect(buildDocTemplate('overview', 'note.md', '').markdown).toContain('# note')
    expect(isValidDocFilename('新文档.md')).toBe(true)
    expect(isValidDocFilename('../secret.md')).toBe(false)
    expect(isValidDocFilename('a/b.md')).toBe(false)
  })
})
