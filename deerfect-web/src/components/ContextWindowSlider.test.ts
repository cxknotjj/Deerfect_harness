import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ContextWindowSlider from './ContextWindowSlider.vue'
import { parseContextWindow } from '../api/admin'

beforeEach(() => vi.useFakeTimers())
afterEach(() => vi.useRealTimers())

function slider(modelValue: number) {
  return mount(ContextWindowSlider, { props: { modelValue } })
}

describe('parseContextWindow', () => {
  it('缺失/损坏/非正数返回 0', () => {
    expect(parseContextWindow(null)).toBe(0)
    expect(parseContextWindow('{broken')).toBe(0)
    expect(parseContextWindow('{"context_window":"x"}')).toBe(0)
    expect(parseContextWindow('{"context_window":-5}')).toBe(0)
    expect(parseContextWindow('{}')).toBe(0)
  })
  it('合法正数取整返回', () => {
    expect(parseContextWindow('{"context_window":600000,"tool_calling":true}')).toBe(600000)
  })
})

describe('ContextWindowSlider', () => {
  it('精确档位显示值与程度文案', () => {
    const w = slider(600000)
    expect(w.text()).toContain('600K 上下文 · 加大')
  })

  it('未声明显示引导文案', () => {
    expect(slider(0).text()).toContain('未声明上下文')
  })

  it('非档位值展示真实声明并提示对齐', () => {
    const w = slider(200000)
    expect(w.text()).toContain('200,000')
    expect(w.text()).toContain('保存后对齐档位')
  })

  it('aria-valuetext 携带值与程度', () => {
    const input = slider(131072).find('input[type=range]')
    expect(input.attributes('aria-valuetext')).toBe('128K，适中')
  })

  it('拖动停手 500ms 防抖上报档位值', async () => {
    const w = slider(8192)
    const input = w.find('input[type=range]')
    ;(input.element as HTMLInputElement).value = '5'
    await input.trigger('input')
    expect(w.text()).toContain('600K 上下文 · 加大')
    vi.advanceTimersByTime(400)
    expect(w.emitted('save')).toBeUndefined()
    vi.advanceTimersByTime(100)
    expect(w.emitted('save')).toEqual([[600000]])
  })

  it('连续拖动只上报最后一次', async () => {
    const w = slider(8192)
    const input = w.find('input[type=range]')
    ;(input.element as HTMLInputElement).value = '2'
    await input.trigger('input')
    vi.advanceTimersByTime(200)
    ;(input.element as HTMLInputElement).value = '6'
    await input.trigger('input')
    vi.advanceTimersByTime(500)
    expect(w.emitted('save')).toEqual([[1048576]])
  })

  it('prop 变化同步滑块位置（保存回滚/外部更新）', async () => {
    const w = slider(8192)
    await w.setProps({ modelValue: 262144 })
    expect(w.text()).toContain('256K 上下文 · 充裕')
  })
})
