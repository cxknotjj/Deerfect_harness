import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import KnowledgeSearchPanel from './KnowledgeSearchPanel.vue'
import { api } from '../api'
import type { KbDocument, KnowledgeSearchConfig, KnowledgeSearchHit } from '../api'

vi.mock('../api', () => ({
  api: {
    knowledgeSearchConfig: vi.fn(),
    searchKnowledge: vi.fn(),
  },
}))

const config: KnowledgeSearchConfig = {
  topK: 4,
  minScore: 0.5,
  fusionMinScore: 0.6,
  hybridEnabled: true,
}

function doc(docName: string, kb: string, title: string): KbDocument {
  return {
    id: 1,
    docName,
    kb,
    title,
    mtime: 0,
    chunkCount: 1,
    status: 1,
    createdAt: '2026-11-21T00:00:00',
    updatedAt: '2026-11-21T00:00:00',
  }
}

const docs = [
  doc('java/SpringBoot常见问题.md', 'java', 'Spring Boot 常见问题'),
  doc('ops/推理服务运维手册.md', 'ops', '智算云推理服务运维手册'),
]

function hit(title: string, score: number): KnowledgeSearchHit {
  return {
    docName: 'ops/推理服务运维手册.md',
    title,
    score,
    text: 'ERR-4021 表示显存不足以容纳当前 batch。先降低 max_batch_size。',
  }
}

async function mountReady() {
  vi.mocked(api.knowledgeSearchConfig).mockResolvedValue(config)
  const wrapper = mount(KnowledgeSearchPanel, { props: { docs } })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.mocked(api.knowledgeSearchConfig).mockReset()
  vi.mocked(api.searchKnowledge).mockReset()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('KnowledgeSearchPanel', () => {
  it('挂载后按服务端配置填充参数，而不是页面写死数字', async () => {
    const wrapper = await mountReady()

    expect((wrapper.find('.kn-number').element as HTMLInputElement).value).toBe('4')
    const ranges = wrapper.findAll('.kn-range')
    expect((ranges[0].element as HTMLInputElement).value).toBe('0.5')
    expect((ranges[1].element as HTMLInputElement).value).toBe('0.6')
    expect(wrapper.find('.kn-mode').text()).toContain('混合检索')
    expect(wrapper.find('.kn-run').attributes('disabled')).toBeDefined()
  })

  it('提交 query 后展示 chunk、来源与相关度，并按 kb chip 过滤', async () => {
    vi.mocked(api.searchKnowledge).mockResolvedValue([hit('ERR-4021', 0.94)])
    const wrapper = await mountReady()

    await wrapper.find('.kn-query-input').setValue('ERR-4021')
    await wrapper.find('.kn-form').trigger('submit')
    await flushPromises()

    expect(api.searchKnowledge).toHaveBeenCalledWith('ERR-4021', [], 4, 0.5, 0.6)
    expect(wrapper.findAll('.kn-hit')).toHaveLength(1)
    expect(wrapper.find('.kn-hit-title').text()).toBe('ERR-4021')
    expect(wrapper.find('.kn-hit-doc').text()).toBe('ops/推理服务运维手册.md')
    expect(wrapper.find('.kn-hit-score').text()).toContain('94')
    expect(wrapper.find('.kn-hit-text').text()).toContain('显存不足')

    await wrapper.findAll('.kn-kb-chip').find((chip) => chip.text() === 'ops')!.trigger('click')
    await wrapper.find('.kn-form').trigger('submit')
    await flushPromises()
    expect(api.searchKnowledge).toHaveBeenLastCalledWith('ERR-4021', ['ops'], 4, 0.5, 0.6)
  })

  it('空 query 不发起请求，配置 503 时展示可读错误', async () => {
    const wrapper = await mountReady()

    await wrapper.find('.kn-form').trigger('submit')
    expect(api.searchKnowledge).not.toHaveBeenCalled()

    vi.mocked(api.knowledgeSearchConfig).mockRejectedValueOnce(
      new Error('HTTP 503: {"message":"知识库未启用：app.knowledge.enabled=false"}'),
    )
    const offline = mount(KnowledgeSearchPanel, { props: { docs } })
    await flushPromises()

    expect(offline.find('.res-alert').text()).toContain('知识库未启用')
    expect(offline.find('.kn-run').attributes('disabled')).toBeDefined()
  })
})
