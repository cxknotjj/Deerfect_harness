import { expect, test } from '@playwright/test'

/**
 * mock 模式冒烟：仪表盘落地页、聊天流式链路、路由徽标回显、轨迹页与监控页。
 * 默认页为仪表盘；对话与轨迹在对话页内以 tab 切换，监控走左侧导航。
 * mock 数据与事件形状见 src/api/mock/mock.ts（meta.route 固定 Agentic/confidence=1）。
 */
test.describe('mock 模式冒烟', () => {
  test('仪表盘：统计卡、周趋势与周期对比', async ({ page }) => {
    await page.goto('/')

    await expect(page.locator('.dash-title')).toHaveText('仪表盘')
    await expect(page.locator('.stat-card')).toHaveCount(4)
    await expect(page.locator('.chart-svg')).toBeVisible()
    await expect(page.locator('.period-card')).toHaveCount(3)
  })

  test('聊天流式：进度行 → token 渲染 → meta 收尾', async ({ page }) => {
    await page.goto('/')
    await page.getByTestId('nav-chat').click()
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
    await page.getByTestId('nav-chat').click()
    const input = page.locator('.composer-input')
    await input.fill('用 Python 写一个快速排序')
    await input.press('Enter')

    await expect(page.locator('.msg-route-tag')).toHaveText('Agentic · 1.00')
  })

  test('轨迹页：概览指标、耗时分布、轮次与调用记录', async ({ page }) => {
    await page.goto('/')
    await page.getByTestId('nav-chat').click()
    // 选中预置会话（mock 的会话 1：同一轮 2 LLM + 2 工具 + 2 RAG，含父子 span）
    await page.getByRole('button', { name: '会话 Qwen 接入排查' }).click()
    await page.getByRole('tab', { name: '轨迹' }).click()

    // 概览卡 7 项指标，首项总耗时
    await expect(page.locator('.trace-chip')).toHaveCount(7)
    await expect(page.locator('.trace-chip').first()).toContainText('总耗时')
    // 耗时分布：色段 + 刻度 + 图例
    await expect(page.locator('.trace-band-block').first()).toBeVisible()
    await expect(page.locator('.trace-axis span')).toHaveCount(5)
    await expect(page.getByText('耗时分布')).toBeVisible()
    // 同一轮次：轮次头 + 6 步 + 行内类型徽标（首行是预取 RAG，LLM 行紧跟其后）
    await expect(page.locator('.trace-turn-no')).toHaveText('第 1 轮')
    await expect(page.locator('.trace-row')).toHaveCount(6)
    await expect(page.locator('.trace-kind').filter({ hasText: 'LLM' }).first()).toBeVisible()
    await expect(page.locator('.trace-bar-fill').first()).toBeVisible()
  })

  test('监控页：全渠道 KPI、渠道分布与调用明细', async ({ page }) => {
    await page.goto('/')
    await page.getByTestId('nav-monitor').click()

    await expect(page.locator('.kpi-grid .kpi').first()).toBeVisible()
    await expect(page.locator('.kpi-label').first()).toHaveText('LLM 调用')

    // Micrometer 运行时指标（与 /actuator/prometheus 同源）：SSE / LLM 调用 / Tokens /
    // 首 token / 耗时 / 路径决策 / Graph 降级 / TTS / 活跃线程 共 9 项，5 列分隔线网格
    await expect(page.getByText('运行时指标')).toBeVisible()
    await expect(page.locator('.runtime-item')).toHaveCount(9)
    await expect(page.locator('.runtime-item').first()).toContainText('SSE 活跃连接')
    // 双栏排版：组件状态+资源占用、渠道分布+每小时调用量
    await expect(page.locator('.monitor-split')).toHaveCount(2)
    await expect(page.getByRole('heading', { name: '资源占用' })).toBeVisible()
    await expect(page.locator('.usage-track')).toHaveCount(2)
    await expect(page.locator('.monitor-foot')).toContainText('更新于')

    // 组件状态：NapCat / Docker / MCP server
    await expect(page.getByRole('heading', { name: '组件状态' })).toBeVisible()
    await expect(page.locator('.component-row')).toHaveCount(4)
    await expect(page.locator('.component-row').first()).toContainText('NapCat / QQ')
    await expect(page.locator('.component-row').first()).toContainText('可用')

    // 渠道分布覆盖 QQ / Web / CLI，且可点击筛选
    await expect(page.getByText('渠道分布')).toBeVisible()
    await expect(page.locator('.channel-filter')).toHaveCount(3)
    await expect(page.locator('.channel-filter').first()).toHaveText('QQ')

    // 活跃会话与 LLM 明细表带渠道列
    await expect(page.getByText('活跃会话', { exact: true })).toBeVisible()
    await expect(page.locator('.channel-badge').first()).toBeVisible()
    await expect(page.locator('.llm-call-row').first()).toBeVisible()

    // 明细区：三张表默认展开；单表可折叠；总开关一键收起/展开；偏好刷新后保留
    const toggleAll = page.getByTestId('details-toggle-all')
    await expect(page.locator('.panel-collapsed')).toHaveCount(0)
    await expect(toggleAll).toHaveText('全部收起')

    await page.getByTestId('detail-llm').getByRole('button').first().click()
    await expect(page.locator('.panel-collapsed')).toHaveCount(1)
    await expect(page.locator('.llm-call-row').first()).toBeHidden()

    await toggleAll.click() // 此时非全展开 → 全部展开
    await expect(page.locator('.panel-collapsed')).toHaveCount(0)
    await expect(page.locator('.llm-call-row').first()).toBeVisible()

    await toggleAll.click() // 全部收起
    await expect(page.locator('.panel-collapsed')).toHaveCount(3)
    await expect(toggleAll).toHaveText('全部展开')

    await page.reload()
    await page.getByTestId('nav-monitor').click()
    await expect(page.locator('.panel-collapsed')).toHaveCount(3)
  })
})
