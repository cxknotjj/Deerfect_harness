import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ChatMessageList from './ChatMessageList.vue'
import type { MessageItem } from '../../composables/useChat'

/** 最小消息夹具（只填列表渲染关心的字段） */
function message(id: number, role: 'user' | 'assistant', content: string): MessageItem {
  return {
    id,
    role,
    content,
    progress: [],
    thinking: [],
    sources: [],
    route: null,
    error: false,
    ts: id,
  }
}

describe('ChatMessageList', () => {
  it('空会话渲染引导与快捷提问，点击即发', async () => {
    const wrapper = mount(ChatMessageList, {
      props: { messages: [], streaming: false },
      global: { stubs: { MessageBubble: true } },
    })

    expect(wrapper.find('.chat-empty-title').text()).toBe('开始新的对话')
    const chips = wrapper.findAll('.suggest-chip')
    expect(chips).toHaveLength(4)

    await chips[0].trigger('click')
    expect(wrapper.emitted('send')?.[0]).toEqual(['介绍一下这个项目的架构'])
  })

  it('有消息时逐个渲染消息气泡，不再显示空态', () => {
    const wrapper = mount(ChatMessageList, {
      props: {
        messages: [message(1, 'user', '你好'), message(2, 'assistant', '你好，我是助手')],
        streaming: true,
      },
      global: { stubs: { MessageBubble: true } },
    })

    expect(wrapper.find('.chat-empty').exists()).toBe(false)
    expect(wrapper.findAll('message-bubble-stub')).toHaveLength(2)
  })
})
