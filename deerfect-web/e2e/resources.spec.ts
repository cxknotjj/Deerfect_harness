import { expect, test } from '@playwright/test'

/**
 * 资源面导航冒烟（mock 模式）：侧栏分组/徽标/系统诊断、五个新页面的真实 mock 数据、
 * 记忆写入后徽标联动、外观分段控件、通栏「新会话」入口。
 * 不依赖后端：VITE_USE_MOCK=true 由 playwright.config.ts 的 webServer 注入。
 */
test.describe('资源面导航', () => {
  test('侧边栏：分组条目、徽标与系统诊断', async ({ page }) => {
    await page.goto('/')

    const ids = ['dashboard', 'chat', 'knowledge', 'memory', 'skills', 'calendar', 'mcp', 'monitor']
    for (const id of ids) {
      await expect(page.getByTestId(`nav-${id}`)).toBeVisible()
    }
    await expect(page.locator('.nav-group-label')).toHaveText(['核心', '连接', '观测'])
    // mock 台账：4 篇文档 / 2 条记忆 / 3 个技能
    await expect(page.getByTestId('nav-knowledge')).toContainText('4')
    await expect(page.getByTestId('nav-memory')).toContainText('2')
    await expect(page.getByTestId('nav-skills')).toContainText('3')
    await expect(page.locator('.nav-health')).toContainText('在线')
  })

  test('知识库 / 技能 / 内容日历 / MCP 页数据渲染', async ({ page }) => {
    await page.goto('/')

    await page.getByTestId('nav-knowledge').click()
    await expect(page.locator('.res-title')).toHaveText('知识库')
    await expect(page.locator('.res-row')).toHaveCount(4)
    await expect(page.locator('.res-stat-value').first()).toHaveText('4')

    await page.getByTestId('nav-skills').click()
    await expect(page.locator('.res-title')).toHaveText('技能')
    await expect(page.locator('.res-row')).toHaveCount(3)

    await page.getByTestId('nav-calendar').click()
    await expect(page.locator('.res-title')).toHaveText('内容日历')
    await expect(page.locator('.res-row')).toHaveCount(2)

    await page.getByTestId('nav-mcp').click()
    await expect(page.locator('.res-title')).toHaveText('MCP')
    await expect(page.locator('.mcp-card')).toHaveCount(2)
    await expect(page.locator('.mcp-card').first()).toContainText('tavily')
  })

  test('记忆新增写入后侧栏徽标同步', async ({ page }) => {
    await page.goto('/')
    await page.getByTestId('nav-memory').click()
    await expect(page.locator('.res-row')).toHaveCount(2)

    await page.getByLabel('记忆内容').fill('前端不引入组件库')
    await page.getByLabel('记忆标签').fill('ui')
    await page.getByRole('button', { name: '记住' }).click()

    await expect(page.locator('.res-row')).toHaveCount(3)
    await expect(page.getByTestId('nav-memory')).toContainText('3')
  })

  test('URL 路由：刷新保持当前页，前进后退与非法路径回落', async ({ page }) => {
    await page.goto('/')
    await expect(page).toHaveURL(/#\/dashboard$/)

    await page.getByTestId('nav-knowledge').click()
    await expect(page).toHaveURL(/#\/knowledge$/)
    await expect(page.locator('.res-title')).toHaveText('知识库')

    // 刷新后仍停在知识库
    await page.reload()
    await expect(page.locator('.res-title')).toHaveText('知识库')

    // 浏览器后退回到仪表盘
    await page.goBack()
    await expect(page.locator('.dash-title')).toHaveText('仪表盘')

    // 对话内 tab 也进地址栏：轨迹刷新后仍激活
    await page.getByTestId('nav-chat').click()
    await page.getByRole('tab', { name: '轨迹' }).click()
    await expect(page).toHaveURL(/#\/chat\/trace$/)
    await page.reload()
    await expect(page.getByRole('tab', { name: '轨迹' })).toHaveAttribute('aria-selected', 'true')

    // 未知路径回落到仪表盘并修正地址
    await page.goto('/#/nope')
    await expect(page.locator('.dash-title')).toHaveText('仪表盘')
    await expect(page).toHaveURL(/#\/dashboard$/)
  })

  test('外观分段与通栏新会话', async ({ page }) => {
    await page.goto('/')

    await page.getByRole('button', { name: '日间' }).click()
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'light')
    await page.getByRole('button', { name: '夜间' }).click()
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')

    await page.getByTestId('nav-new-session').click()
    await expect(page.locator('.chat-layout')).toBeVisible()
    await expect(page.getByTestId('nav-chat')).toHaveAttribute('aria-current', 'page')
  })
})
