import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ContextUsageRing from './ContextUsageRing.vue'
import { api } from '../api'
import type { LlmModelView } from '../api/admin'

vi.mock('../api', () => ({
  api: {
    monitorContext: vi.fn(),
    listLlmModels: vi.fn(),
    updateModelContextWindow: vi.fn(),
  },
}))

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

function modelRow(id: number, name: string, window: number): LlmModelView {
  return {
    id, providerId: 1, providerCode: 'p', upstreamName: name, displayName: name,
    capabilities: JSON.stringify({ context_window: window }), defaultParams: null, enabled: 1,
  }
}

function composition(model: string) {
  return {
    sessionId: 's1',
    agentName: 'general',
    model,
    turnId: 't1',
    capturedAt: Date.now(),
    estimatedTotalTokens: 1000,
    parts: [{ key: 'user', label: '用户', group: 'conversation', tokens: 1000, percent: 100 }],
  }
}

/** 推进微任务（覆盖 fake timers 下的 promise 结算） */
const tick = async (): Promise<void> => { await vi.advanceTimersByTimeAsync(0) }

beforeEach(() => {
  vi.useFakeTimers()
})
afterEach(() => {
  vi.useRealTimers()
  vi.resetAllMocks()
})

async function openPopover() {
  const wrapper = mount(ContextUsageRing, { props: { sessionId: 's1', streaming: false } })
  await wrapper.find('.usage-trigger').trigger('click')
  await tick()
  await tick()
  return wrapper
}

describe('ContextUsageRing 上限保存', () => {
  it('保存中继续拖动：在途完成后补存最后一次值', async () => {
    vi.mocked(api.monitorContext).mockResolvedValue(composition('qwen-plus'))
    vi.mocked(api.listLlmModels).mockResolvedValue([modelRow(9, 'qwen-plus', 128000)])
    const first = deferred<LlmModelView>()
    vi.mocked(api.updateModelContextWindow)
      .mockReturnValueOnce(first.promise)
      .mockResolvedValueOnce(modelRow(9, 'qwen-plus', 512000))
    const wrapper = await openPopover()
    const slider = wrapper.find('.usage-limit-scale')
    expect(wrapper.find('.usage-limit-title').text()).toContain('128,000 tok')

    await slider.trigger('keydown', { key: 'End' }) // 1M
    await vi.advanceTimersByTimeAsync(500)
    expect(api.updateModelContextWindow).toHaveBeenCalledTimes(1)

    await slider.trigger('keydown', { key: 'ArrowLeft' }) // 512K，第一次仍在途
    await vi.advanceTimersByTimeAsync(500)
    expect(api.updateModelContextWindow).toHaveBeenCalledTimes(1)

    first.resolve(modelRow(9, 'qwen-plus', 1000000)) // 第一次完成 → 补存最后一次
    await tick()
    expect(api.updateModelContextWindow).toHaveBeenCalledTimes(2)
    expect(vi.mocked(api.updateModelContextWindow).mock.calls[1][1]).toBe(512000)
    wrapper.unmount()
  })

})
