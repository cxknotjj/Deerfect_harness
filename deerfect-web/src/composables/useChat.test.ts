import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { api } from '../api'
import type { SessionMessagesView } from '../api'
import type { StreamHandlers } from '../api/chatStream'
import { useChat } from './useChat'
import { loadCache } from './chatCache'

vi.mock('../api', () => ({
  api: { listMessages: vi.fn(), streamChat: vi.fn() },
  isAbort: (error: unknown) => error instanceof DOMException && error.name === 'AbortError',
}))

function pending<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

function history(content: string): SessionMessagesView {
  return { sessionId: '1', messages: [{ role: 'assistant', content, ts: 1 }] }
}

function createChat(initialSessionId = '1') {
  let sessionId = initialSessionId
  return useChat({ getSessionId: () => sessionId, setSessionId: (id) => { sessionId = id } })
}

beforeEach(() => {
  localStorage.clear()
  vi.resetAllMocks()
})

describe('聊天缓存持久化', () => {
  it('历史落盘后，新实例在历史请求完成前即可显示缓存', async () => {
    vi.mocked(api.listMessages).mockResolvedValueOnce(history('缓存回答'))
    const chat = createChat()
    chat.show('1')
    await flushPromises()
    expect(loadCache('1').map((m) => m.content)).toEqual(['缓存回答'])
    vi.mocked(api.listMessages).mockReturnValue(pending<SessionMessagesView>().promise)
    const restored = createChat()
    restored.show('1')
    expect(restored.messages.value.map((m) => m.content)).toEqual(['缓存回答'])
  })

  it('发送、重新生成和删除都保存到当前会话', async () => {
    vi.mocked(api.listMessages).mockResolvedValue({ sessionId: '1', messages: [] })
    vi.mocked(api.streamChat).mockImplementation(async (_request, handlers) => {
      handlers.onToken?.('回答')
    })
    const chat = createChat()
    chat.show('1')
    await flushPromises()
    await chat.send('问题')
    expect(loadCache('1').map((m) => m.content)).toEqual(['问题', '回答'])
    expect(Object.keys(loadCache('1')[1]).sort()).toEqual(['content', 'id', 'role', 'ts'])
    vi.mocked(api.streamChat).mockImplementation(async (_request, handlers) => {
      handlers.onToken?.('重新回答')
    })
    await chat.regenerate(chat.messages.value[1].id)
    expect(loadCache('1').map((m) => m.content)).toEqual(['问题', '重新回答'])
    chat.remove(chat.messages.value[0].id)
    expect(loadCache('1')).toEqual([])
  })

  it('草稿收到新会话 ID 后保存到真实会话', async () => {
    vi.mocked(api.streamChat).mockImplementation(async (_request, handlers) => {
      handlers.onToken?.('新会话回答')
      handlers.onMeta?.({
        sessionId: 'new', newSession: true, goalId: null, status: 'SUCCEEDED',
        error: null, sources: null, route: null,
      })
    })
    const chat = createChat('')
    await chat.send('新会话问题')
    expect(loadCache('new').map((m) => m.content)).toEqual(['新会话问题', '新会话回答'])
    expect(loadCache('__draft__')).toEqual([])
  })

  it('切走后旧流收尾仍保存到原会话', async () => {
    const stream = pending<void>()
    vi.mocked(api.listMessages).mockResolvedValue({ sessionId: '1', messages: [] })
    vi.mocked(api.streamChat).mockImplementation((_request, handlers) => {
      handlers.onToken?.('原会话回答')
      return stream.promise
    })
    const chat = createChat()
    chat.show('1')
    await flushPromises()
    const sending = chat.send('原会话问题')
    chat.show('2')
    await flushPromises()
    stream.resolve()
    await sending
    expect(loadCache('1').map((m) => m.content)).toEqual(['原会话问题', '原会话回答'])
    expect(loadCache('2')).toEqual([])
  })
})

describe('聊天历史同步', () => {
  it('空闲时正常加载服务端历史', async () => {
    vi.mocked(api.listMessages).mockResolvedValue(history('历史回答'))
    const chat = createChat()
    chat.show('1')
    await flushPromises()
    expect(chat.messages.value.map((m) => m.content)).toEqual(['历史回答'])
  })

  it('迟到历史不能移除正在生成的新消息', async () => {
    const response = pending<SessionMessagesView>()
    const stream = pending<void>()
    let handlers!: StreamHandlers
    vi.mocked(api.listMessages).mockReturnValue(response.promise)
    vi.mocked(api.streamChat).mockImplementation((_request, callbacks) => {
      handlers = callbacks
      return stream.promise
    })
    const chat = createChat()
    chat.show('1')
    const sending = chat.send('新问题')
    response.resolve(history('旧历史'))
    await flushPromises()
    handlers.onToken?.('新回答')
    stream.resolve()
    await sending
    expect(chat.messages.value.map((m) => m.content)).toEqual(['新问题', '新回答'])
  })

  it('流已结束后到达的旧历史也不能覆盖新回答', async () => {
    const response = pending<SessionMessagesView>()
    vi.mocked(api.listMessages).mockReturnValue(response.promise)
    vi.mocked(api.streamChat).mockImplementation(async (_request, handlers) => {
      handlers.onToken?.('新回答')
    })
    const chat = createChat()
    chat.show('1')
    await chat.send('新问题')
    response.resolve(history('旧历史'))
    await flushPromises()
    expect(chat.messages.value.map((m) => m.content)).toEqual(['新问题', '新回答'])
  })

  it('切走再切回相同会话时只接收最新历史请求', async () => {
    const oldResponse = pending<SessionMessagesView>()
    const newResponse = pending<SessionMessagesView>()
    vi.mocked(api.listMessages)
      .mockReturnValueOnce(oldResponse.promise)
      .mockReturnValueOnce(newResponse.promise)
    const chat = createChat()
    chat.show('1')
    chat.show('')
    chat.show('1')
    newResponse.resolve(history('最新历史'))
    await flushPromises()
    oldResponse.resolve(history('过期历史'))
    await flushPromises()
    expect(chat.messages.value.map((m) => m.content)).toEqual(['最新历史'])
  })

  for (const action of ['remove', 'regenerate'] as const) {
    it(`${action} 后不让迟到历史撤销本地操作`, async () => {
      const initial: SessionMessagesView = { sessionId: '1', messages: [
        { role: 'user', content: '问题', ts: 1 },
        { role: 'assistant', content: '旧回答', ts: 2 },
      ] }
      const response = pending<SessionMessagesView>()
      vi.mocked(api.listMessages).mockResolvedValueOnce(initial).mockReturnValueOnce(response.promise)
      vi.mocked(api.streamChat).mockImplementation(async (_request, handlers) => {
        handlers.onToken?.('重新回答')
      })
      const chat = createChat()
      chat.show('1')
      await flushPromises()
      chat.show('1')
      if (action === 'remove') chat.remove(chat.messages.value[0].id)
      else await chat.regenerate(chat.messages.value[1].id)
      response.resolve(initial)
      await flushPromises()
      expect(chat.messages.value.map((m) => m.content)).toEqual(
        action === 'remove' ? [] : ['问题', '重新回答'],
      )
    })
  }
})
