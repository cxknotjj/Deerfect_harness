import { expect, test } from '@playwright/test'

/**
 * mock 模式冒烟三条：聊天流式链路、路由徽标回显、轨迹页观测数据。
 * mock 数据与事件形状见 src/api/mock/mock.ts（meta.route 固定 Agentic/confidence=1）。
 */
test.describe('mock 模式冒烟', () => {
  test('聊天流式：进度行 → token 渲染 → meta 收尾', async ({ page }) => {
    await page.goto('/')
    await page.getByRole('button', { name: '介绍一下这个项目的架构' }).click()

    await expect(page.locator('.msg-bubble-user')).toContainText('介绍一下这个项目的架构')
    await expect(page.locator('.msg-progress-item').first()).toContainText('agent')

    const assistant = page.locator('.msg-bubble-assistant')
    await expect(assistant).toContainText('关于「介绍一下这个项目的架构」的回答')
    // 回复末段（流结束才算真正吐完），同时状态栏回到空闲
    await expect(assistant).toContainText('需要真实回答时')
    await expect(page.locator('.chat-statusbar')).toContainText('空闲')
  })

  test('路由徽标：流末 meta 回显路径与置信度', async ({ page }) => {
    await page.goto('/')
    const input = page.locator('.composer-input')
    await input.fill('用 Python 写一个快速排序')
    await input.press('Enter')

    await expect(page.locator('.msg-route-tag')).toHaveText('Agentic · 1.00')
  })

  test('轨迹页：展示会话的 LLM 调用记录', async ({ page }) => {
    await page.goto('/')
    // 选中预置会话（mock 的会话 1 有 2 条 LLM + 2 条工具记录）
    await page.getByRole('button', { name: '会话 Qwen 接入排查' }).click()
    await page.getByRole('tab', { name: '轨迹' }).click()

    await expect(page.locator('.trace-row').first()).toBeVisible()
    await expect(page.locator('.trace-kind').first()).toHaveText('LLM')
    await expect(page.locator('.trace-chip').first()).toContainText('总耗时')
  })
})
