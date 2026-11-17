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
  it('按五个分组索引 docs，README 置顶', () => {
    expect(DOC_GROUPS.map((g) => g.label)).toEqual(['概览', '指南', '设计', '任务清单', '报告'])
    const overview = DOC_GROUPS[0]
    expect(overview.docs[0].path).toBe('README.md')
    expect(overview.docs[0].title).toContain('javaHarness')
    expect(DOC_GROUPS.find((g) => g.key === 'guides')!.count).toBeGreaterThan(10)
  })

  it('正文按路径取回', () => {
    expect(docMarkdown('README.md')).toContain('## 项目约束')
    expect(findDoc('guides/setup.md')?.title).toBeTruthy()
    expect(docMarkdown('nope.md')).toBeNull()
  })

  it('搜索命中标题与正文', () => {
    const hits = searchDocs('项目约束')
    expect(hits.length).toBeGreaterThan(0)
    expect(hits[0].path).toBe('README.md')
    expect(hits[0].snippet.length).toBeGreaterThan(0)
  })

  it('相对链接落在 docs 内', () => {
    expect(resolveDocLink('README.md', './guides/setup.md')).toBe('guides/setup.md')
    expect(resolveDocLink('guides/setup.md', '../principal.md')).toBe('principal.md')
    expect(resolveDocLink('README.md', 'https://example.com/a.md')).toBe('https://example.com/a.md')
  })

  it('模板路径与文件名校验', () => {
    expect(buildDocTemplate('guides', 'new-doc.md', '新文档').path).toBe('guides/new-doc.md')
    expect(buildDocTemplate('overview', 'note.md', '').markdown).toContain('# note')
    expect(isValidDocFilename('新文档.md')).toBe(true)
    expect(isValidDocFilename('../secret.md')).toBe(false)
    expect(isValidDocFilename('a/b.md')).toBe(false)
  })
})
