import { beforeEach, describe, expect, it, vi } from 'vitest'
import { loadCache, saveCache } from './chatCache'

/** chatCache 依赖 localStorage(jsdom 提供);覆盖读写/淘汰/降级/损坏容错核心口径 */
describe('chatCache', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('无缓存读取返回空数组', () => {
    expect(loadCache('s1')).toEqual([])
  })

  it('保存后可读回,空数组保存视为删除该会话缓存', () => {
    const msgs = [{ id: 1, role: 'user' as const, content: '你好', ts: 1 }]
    saveCache('s1', msgs)
    expect(loadCache('s1')).toEqual(msgs)

    saveCache('s1', [])
    expect(loadCache('s1')).toEqual([])
  })

  it('单会话超长裁剪:仅保留最近 MAX_MESSAGES 条', () => {
    const many = Array.from({ length: 260 }, (_, i) => ({
      id: i + 1,
      role: 'user' as const,
      content: `m${i + 1}`,
      ts: i + 1,
    }))
    saveCache('s1', many)
    const loaded = loadCache('s1')
    expect(loaded.length).toBe(200)
    expect(loaded[0].content).toBe('m61') // 前 60 条被裁掉
  })

  it('会话数超上限:按 savedAt 淘汰更早写入的会话', () => {
    // 假时钟逐毫秒推进:保证 21 个会话的 savedAt 严格递增,淘汰顺序确定
    vi.useFakeTimers()
    let tick = 1_000_000
    vi.setSystemTime(tick)
    for (let i = 1; i <= 21; i++) {
      tick += 1
      vi.setSystemTime(tick)
      saveCache(`s${i}`, [{ id: i, role: 'user', content: `c${i}`, ts: i }])
    }
    expect(loadCache('s1')).toEqual([]) // 最早写入的被淘汰
    expect(loadCache('s21').length).toBe(1) // 最近写入的保留
    vi.useRealTimers()
  })

  it('损坏的缓存 JSON 容错:读取返回空数组不抛错', () => {
    localStorage.setItem('harness-chat-cache', '{broken json')
    expect(loadCache('s1')).toEqual([])
  })
})
