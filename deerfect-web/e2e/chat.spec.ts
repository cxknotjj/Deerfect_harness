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

  for (const path of ['/chat', '/chat/trace']) {
    test(`恢复会话：${path} 刷新后加载历史并保留视图`, async ({ page }) => {
      await page.addInitScript(() => {
        localStorage.setItem('ui-last-session', '1')
        localStorage.setItem('ui-restore-session', '1')
      })
      await page.goto(path)
      await page.reload()
      await expect(page.locator('.session-item-active')).toContainText('Qwen 接入排查')
      await expect(page).toHaveURL(new RegExp(`${path}$`))
      if (path.endsWith('/trace')) {
        await expect(page.getByRole('tab', { name: '轨迹' })).toHaveAttribute('aria-selected', 'true')
        await expect(page.locator('.trace-row')).toHaveCount(6)
        await page.getByRole('tab', { name: '对话', exact: true }).click()
      }
      await expect(page.locator('.msg-bubble-assistant').last()).toContainText('401 排查思路')
      await page.getByRole('button', { name: '会话 Qwen 接入排查' }).click()
      await expect(page.locator('.msg-bubble-user')).toHaveCount(2)
      await expect(page.locator('.msg-bubble-assistant')).toHaveCount(2)
    })
  }

  for (const scenario of [
    { name: '关闭恢复', id: '1', enabled: '0' },
    { name: '已删除会话', id: 'missing', enabled: '1' },
  ]) {
    test(`恢复会话：${scenario.name} 保持草稿视图`, async ({ page }) => {
      await page.addInitScript(({ id, enabled }) => {
        localStorage.setItem('ui-last-session', id)
        localStorage.setItem('ui-restore-session', enabled)
      }, scenario)
      await page.goto('/chat')
      await expect(page.getByRole('button', { name: '会话 Qwen 接入排查' })).toBeVisible()
      await expect(page.locator('.session-item-active')).toHaveCount(0)
      await expect(page.locator('.msg-bubble-assistant')).toHaveCount(0)
      await expect(page.getByRole('button', { name: '介绍一下这个项目的架构' })).toBeVisible()
    })
  }

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

  test('监控页：概览/运行时/组件/明细四个 tab', async ({ page }) => {
    await page.goto('/')
    await page.getByTestId('nav-monitor').click()

    // 概览：6 KPI + [每小时调用量 | 资源占用] + 渠道分布 + 页脚
    await expect(page.locator('.monitor-tab')).toHaveCount(4)
    await expect(page.locator('.monitor-tab').first()).toHaveText('概览')
    await expect(page.locator('.kpi-grid .kpi')).toHaveCount(6)
    await expect(page.locator('.kpi-label').first()).toHaveText('LLM 调用')
    await expect(page.locator('.chart .bar-col').first()).toBeVisible()
    await expect(page.getByRole('heading', { name: '资源占用' })).toBeVisible()
    await expect(page.locator('.usage-track')).toHaveCount(2)
    await expect(page.getByRole('heading', { name: '渠道分布' })).toBeVisible()
    await expect(page.locator('.channel-filter')).toHaveCount(3)
    await expect(page.locator('.channel-filter').first()).toHaveText('QQ')
    await expect(page.locator('.monitor-foot')).toContainText('更新于')
    // 运行时指标不在概览里，切 tab 才渲染
    await expect(page.locator('.runtime-item')).toHaveCount(0)

    // 运行时：进程累计指标 9 格 + [路径决策构成 | 连接与线程]
    await page.getByTestId('monitor-tab-runtime').click()
    await expect(page.getByText('进程累计指标')).toBeVisible()
    await expect(page.locator('.runtime-item')).toHaveCount(9)
    await expect(page.locator('.runtime-item').first()).toContainText('SSE 活跃连接')
    await expect(page.locator('.route-bar')).toBeVisible()
    await expect(page.getByRole('heading', { name: '路径决策构成' })).toBeVisible()
    await expect(page.getByRole('heading', { name: '连接与线程' })).toBeVisible()

    // 组件与资源：组件状态通栏 + [资源占用 | 探活与连接]
    await page.getByTestId('monitor-tab-components').click()
    await expect(page.getByRole('heading', { name: '组件状态' })).toBeVisible()
    await expect(page.locator('.component-row')).toHaveCount(4)
    await expect(page.locator('.component-row').first()).toContainText('NapCat / QQ')
    await expect(page.locator('.component-row').first()).toContainText('可用')
    await expect(page.locator('.usage-track')).toHaveCount(2)
    await expect(page.getByRole('heading', { name: '探活与连接' })).toBeVisible()
    await expect(page.locator('.runtime-row')).toHaveCount(3)

    // 明细：三张统计卡 + 单面板三段折叠
    await page.getByTestId('monitor-tab-details').click()
    await expect(page.locator('.detail-stat')).toHaveCount(3)
    await expect(page.locator('.detail-section.is-open')).toHaveCount(3)
    await expect(page.locator('.detail-row-title').first()).toHaveText('活跃会话')
    await expect(page.locator('.channel-badge').first()).toBeVisible()
    await expect(page.locator('.llm-call-row').first()).toBeVisible()

    // 单段折叠 → 总开关全部展开 → 全部收起 → 刷新后保留
    const toggleAll = page.getByTestId('details-toggle-all')
    await expect(toggleAll).toHaveText('全部收起')

    await page.locator('.detail-row').filter({ hasText: '最近 LLM 调用' }).click()
    await expect(page.locator('.detail-section.is-open')).toHaveCount(2)
    await expect(page.locator('.llm-call-row').first()).toBeHidden()

    await toggleAll.click() // 此时非全展开 → 全部展开
    await expect(page.locator('.detail-section.is-open')).toHaveCount(3)
    await expect(page.locator('.llm-call-row').first()).toBeVisible()

    await toggleAll.click() // 全部收起
    await expect(page.locator('.detail-section.is-open')).toHaveCount(0)
    await expect(toggleAll).toHaveText('全部展开')

    await page.reload()
    await page.getByTestId('nav-monitor').click()
    await expect(page.locator('.detail-section.is-open')).toHaveCount(0)
  })

  test('监控明细：上下文弹窗环状图、排行与关闭', async ({ page }) => {
    await page.goto('/')
    await page.getByTestId('nav-monitor').click()
    await page.getByTestId('monitor-tab-details').click()
    await page.locator('.ctx-btn').first().click()

    await expect(page.locator('.ctx-modal')).toBeVisible()
    await expect(page.locator('#ctx-modal-title')).toHaveText('上下文构成')
    // 环图四段 + 中心合计 + 图例四组 + 明细排行 11 项
    await expect(page.locator('.ctx-ring-seg')).toHaveCount(4)
    await expect(page.locator('.ctx-ring-center strong')).toHaveText('2,602')
    await expect(page.locator('.ctx-legend-item')).toHaveCount(4)
    await expect(page.locator('.ctx-legend-item').first()).toContainText('45.4%')
    await expect(page.locator('.ctx-rank-item')).toHaveCount(11)
    await expect(page.locator('.ctx-modal-foot')).toContainText('估算合计 2,602 tokens')

    // Esc 关闭：焦点回到触发按钮，页面滚动锁解除
    await page.keyboard.press('Escape')
    await expect(page.locator('.ctx-modal')).toHaveCount(0)
    await expect(page.locator('.ctx-btn').first()).toBeFocused()
    expect(await page.evaluate(() => document.body.style.overflow)).toBe('')

    // 点遮罩关闭
    await page.locator('.ctx-btn').first().click()
    await expect(page.locator('.ctx-modal')).toBeVisible()
    await page.locator('.ctx-modal-mask').click({ position: { x: 8, y: 8 } })
    await expect(page.locator('.ctx-modal')).toHaveCount(0)
  })

  test('手机端对话：输入区收在卡片内、控件单行', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto('/chat')
    await page.getByRole('button', { name: '打开会话列表' }).click()
    await page.getByRole('button', { name: '会话 Qwen 接入排查' }).click()

    // 发送键右边缘不得越过输入卡片（手机上曾溢出到卡片外）
    const composer = await page.locator('.composer').boundingBox()
    const send = await page.locator('.composer-send').boundingBox()
    expect(composer).not.toBeNull()
    expect(send).not.toBeNull()
    expect(send!.x + send!.width).toBeLessThanOrEqual(composer!.x + composer!.width + 0.5)

    // 控件行没有横向滚动，输入框高度已收敛（桌面 rows=3 的高度是 75）
    expect(
      await page.locator('.composer-controls').evaluate((el) => el.scrollWidth <= el.clientWidth + 1),
    ).toBe(true)
    const input = await page.locator('.composer-input').boundingBox()
    expect(input!.height).toBeLessThan(70)

    // 底部状态栏仍在
    await expect(page.locator('.chat-statusbar')).toBeVisible()
  })
})
