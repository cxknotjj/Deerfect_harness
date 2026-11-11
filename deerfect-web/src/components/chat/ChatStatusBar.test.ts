import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ChatStatusBar from './ChatStatusBar.vue'
import type { MessageItem } from '../../composables/useChat'

const listLlmCalls = vi.fn()

vi.mock('../../api', () => ({
  api: {
    listLlmCalls: (...args: unknown[]) => listLlmCalls(...args),
  },
}))

function message(id: number): MessageItem {
  return { id, role: 'user', content: 'hi', progress: [], thinking: [], sources: [], route: null, error: false, ts: id }
}

describe('ChatStatusBar', () => {
  beforeEach(() => {
    listLlmCalls.mockReset()
    listLlmCalls.mockResolvedValue([
      { promptTokens: 1200, completionTokens: 800 },
      { promptTokens: null, completionTokens: null },
    ])
  })

  it('无消息不渲染状态栏（含会话 id 也不显示）', () => {
    const wrapper = mount(ChatStatusBar, { props: { messages: [], streaming: false, sessionId: 's1' } })
    expect(wrapper.find('.chat-statusbar').exists()).toBe(false)
  })

  it('展示消息数、分向 token 与空闲状态', async () => {
    const wrapper = mount(ChatStatusBar, {
      props: { messages: [message(1), message(2)], streaming: false, sessionId: 's1' },
    })
    await flushPromises()

    const text = wrapper.find('.chat-statusbar').text()
    expect(text).toContain('2 条消息')
    expect(text).toContain('输入 1.2k tok')
    expect(text).toContain('输出 800 tok')
    expect(text).toContain('空闲')
  })

  it('流式进行中显示生成中,不拉取观测（无会话时也不请求）', () => {
    const wrapper = mount(ChatStatusBar, {
      props: { messages: [message(1)], streaming: true, sessionId: '' },
    })
    expect(wrapper.find('.chat-statusbar').text()).toContain('生成中…')
    expect(listLlmCalls).not.toHaveBeenCalled()
  })
})
