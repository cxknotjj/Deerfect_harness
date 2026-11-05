import { afterEach, describe, expect, it, vi } from 'vitest'
import { streamChat } from './chatStream'

/** 最小流式 Response 桩：chunks 按序编码，末次 read 返回 done */
function streamingResponse(chunks: string[]): Response {
  let index = 0
  const encoder = new TextEncoder()
  return {
    ok: true,
    status: 200,
    body: {
      getReader: () => ({
        read: async () =>
          index < chunks.length
            ? { done: false, value: encoder.encode(chunks[index++]) }
            : { done: true, value: undefined },
      }),
    },
    text: async () => '',
  } as unknown as Response
}

describe('streamChat SSE 分发', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('半帧跨 chunk 拼接后成帧，token 转义还原并以 [DONE] 结束', async () => {
    const tokens: string[] = []
    let done = false
    vi.stubGlobal('fetch', vi.fn(async () => streamingResponse([
      'event: token\r\ndata: hel',
      'lo\\nworld\r\n',
      'event: token\ndata: [DONE]\n',
    ])))

    await streamChat({ message: 'hi' }, {
      onToken: (t) => tokens.push(t),
      onDone: () => { done = true },
    })

    expect(tokens).toEqual(['hello\nworld'])
    expect(done).toBe(true)
  })

  it('meta / progress / error 分别分发，注释行忽略', async () => {
    const metas: unknown[] = []
    const progresses: unknown[] = []
    const errors: string[] = []
    vi.stubGlobal('fetch', vi.fn(async () => streamingResponse([
      'event: meta\ndata: {"sessionId":"s1"}\n',
      'event: progress\ndata: stage=lead\n',
      'event: error\ndata: boom\n',
      ': keep-alive\n',
    ])))

    await streamChat({ message: 'hi' }, {
      onMeta: (m) => metas.push(m),
      onProgress: (p) => progresses.push(p),
      onError: (e) => errors.push(e),
    })

    expect(metas).toEqual([{ sessionId: 's1' }])
    expect(progresses).toEqual([{ stage: 'progress', detail: 'stage=lead' }])
    expect(errors).toEqual(['boom'])
  })
})
