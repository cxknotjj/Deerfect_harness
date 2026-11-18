import { expect, test } from '@playwright/test'

/**
 * 资源面导航冒烟（mock 模式）：侧栏分组/徽标/系统诊断、五个新页面的真实 mock 数据、
 * 记忆写入后徽标联动、外观分段控件、通栏「新会话」入口。
 * 不依赖后端：VITE_USE_MOCK=true 由 playwright.config.ts 的 webServer 注入。
 */
test.describe('资源面导航', () => {
  test('侧边栏：分组条目、徽标与系统诊断', async ({ page }) => {
    await page.goto('/')

    const ids = [
      'dashboard',
      'chat',
      'knowledge',
      'memory',
      'docs',
      'skills',
      'calendar',
      'mcp',
      'monitor',
      'settings',
      'security',
    ]
    for (const id of ids) {
      await expect(page.getByTestId(`nav-${id}`)).toBeVisible()
    }
    await expect(page.locator('.nav-group-label')).toHaveText(['核心', '连接', '观测', '系统'])
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
    await expect(page.locator('.skill-card')).toHaveCount(3)
    // 筛选：限定 Agent 只剩 2 个（systematic-debugging 是全部 Agent）
    await expect(page.locator('.skill-filter')).toHaveCount(4)
    await page.locator('.skill-filter').filter({ hasText: '限定 Agent' }).click()
    await expect(page.locator('.skill-card')).toHaveCount(2)
    await page.locator('.skill-filter').filter({ hasText: '全部' }).click()
    // 搜索：按名称/描述过滤
    await page.locator('.skill-search input').fill('wms')
    await expect(page.locator('.skill-card')).toHaveCount(1)
    await page.locator('.skill-search input').fill('')

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

  test('URL 路由：干净路径、刷新保持、前进后退与非法路径回落', async ({ page }) => {
    await page.goto('/')
    // History API 路由：地址栏不带 #
    await expect(page).toHaveURL(/\/dashboard$/)

    await page.getByTestId('nav-knowledge').click()
    await expect(page).toHaveURL(/\/knowledge$/)
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
    await expect(page).toHaveURL(/\/chat\/trace$/)
    await page.reload()
    await expect(page.getByRole('tab', { name: '轨迹' })).toHaveAttribute('aria-selected', 'true')

    // 未知路径回落到仪表盘并修正地址
    await page.goto('/nope')
    await expect(page.locator('.dash-title')).toHaveText('仪表盘')
    await expect(page).toHaveURL(/\/dashboard$/)
  })

  test('文档页：前端打包 docs、目录/搜索/内链与深链', async ({ page }) => {
    await page.goto('/docs')
    await expect(page.locator('.docs-doc-title')).toHaveText('文档导航')
    await expect(page.locator('.docs-group-row')).toHaveCount(5)
    await expect(page.locator('.docs-group-row').first()).toContainText('概览')
    // 组计数按折叠英文变体后的真实目录重算
    await expect(page.locator('.docs-group-count')).toHaveText(['10', '15', '15', '13', '4'])

    // 所有分组都带两位序号：概览 01–10 来自 front-matter order，指南 01–15 按展示顺序
    const treeGroups = page.locator('.docs-group')
    await expect(treeGroups.first().locator('.docs-item-order')).toHaveCount(10)
    await expect(treeGroups.first().locator('.docs-item-order').first()).toHaveText('01')
    await expect(treeGroups.nth(1).locator('.docs-item-order')).toHaveCount(15)
    await expect(treeGroups.nth(1).locator('.docs-item-order').first()).toHaveText('01')
    const metaBar = page.locator('.docs-doc-meta')
    await expect(metaBar).toContainText('docs/index.md')
    await expect(metaBar).toContainText('group 概览')
    await expect(metaBar).toContainText('order 01')
    await expect(metaBar).toContainText('lang zh')
    await expect(metaBar).toContainText('更新 2026-11-14')

    // 正文来自打包的 index.md：表格与「项目约束」段落
    await expect(page.locator('.docs-md table').first()).toBeVisible()
    await expect(page.locator('.docs-md')).toContainText('项目约束')

    // 英文变体折叠：项目结构行只有一条，EN 徽标可切英文 / 切回中文
    const structureRow = page.locator('.docs-item').filter({ hasText: '项目结构' }).first()
    await expect(structureRow.locator('.docs-item-lang')).toHaveText('EN')
    await structureRow.locator('.docs-item-lang').click()
    await expect(page).toHaveURL(/\/docs\/project-structure\.en\.md$/)
    await expect(metaBar).toContainText('lang en')
    await expect(structureRow.locator('.docs-item-lang')).toHaveText('中')
    await structureRow.locator('.docs-item-lang').click()
    await expect(metaBar).toContainText('lang zh')

    // 内链跳转：index → principal.md，地址栏同步到可刷新路径
    await page.locator('.docs-item').filter({ hasText: '文档导航' }).first().click()
    await page.locator('.docs-md a').filter({ hasText: 'principal.md' }).click()
    await expect(page).toHaveURL(/\/docs\/principal\.md$/)
    await expect(page.locator('.docs-doc-title')).toHaveText('项目约束')
    // 没有 front-matter 的文档也显示文件更新日期
    await expect(metaBar).toContainText(/更新 \d{4}-\d{2}-\d{2}/)

    // 搜索在本地扫描正文
    await page.locator('.docs-search input').fill('飞书')
    await expect(page.locator('.docs-hit').first()).toBeVisible()
    await expect(page.locator('.docs-tree-head')).toContainText('搜索结果')

    // 深链直接打开并刷新保持（前端打包，不依赖后端）
    await page.goto('/docs/guides/setup.md')
    await expect(page.locator('.docs-doc-title')).toHaveText('本地部署详解')
    await page.reload()
    await expect(page.locator('.docs-doc-title')).toHaveText('本地部署详解')
  })

  test('文档章节浮层：展开、跳转高亮、Esc 与方向键', async ({ page }) => {
    await page.goto('/docs')
    const pill = page.locator('.docs-menu-pill')
    await expect(pill).toContainText('本页目录')

    // 展开：当前章节 = 顶部第一节
    await pill.click()
    await expect(page.locator('.docs-menu-panel')).toBeVisible()
    await expect(page.locator('.docs-menu-item')).toHaveCount(6)
    await expect(page.locator('.docs-menu-item.is-active')).toHaveText('项目约束')

    // 方向键在列表内移动焦点
    await page.keyboard.press('ArrowDown')
    await expect(page.locator('.docs-menu-item').nth(1)).toBeFocused()

    // 点某项：浮层收起，正文滚动，高亮切到该项
    await page.locator('.docs-menu-item').filter({ hasText: '架构与设计' }).click()
    await expect(page.locator('.docs-menu-panel')).toHaveCount(0)
    expect(await page.locator('.docs-article').evaluate((el) => el.scrollTop)).toBeGreaterThan(0)
    await pill.click()
    await expect(page.locator('.docs-menu-item.is-active')).toHaveText('架构与设计')

    // Esc 关闭并回到胶囊
    await page.keyboard.press('Escape')
    await expect(page.locator('.docs-menu-panel')).toHaveCount(0)
    await expect(pill).toBeFocused()

    // 点浮层外收起
    await pill.click()
    await page.locator('.docs-doc-title').click()
    await expect(page.locator('.docs-menu-panel')).toHaveCount(0)
  })

  test('系统分组：设置页模型管理/外观/实例与安全页状态', async ({ page }) => {
    await page.goto('/')

    await page.getByTestId('nav-settings').click()
    await expect(page).toHaveURL(/\/settings$/)
    await expect(page.locator('.settings-nav-title')).toHaveText('设置')

    // 模型管理：3 个 mock 供应商；卡片只展示凭证引用名，不出现密钥
    await expect(page.locator('.provider-card')).toHaveCount(3)
    await expect(page.locator('.provider-card').first()).toContainText('DASHSCOPE_API_KEY')

    // 禁用第一个供应商 → 状态徽标变「已禁用」
    const firstCard = page.locator('.provider-card').first()
    await firstCard.getByRole('button', { name: '禁用' }).click()
    await expect(firstCard.locator('.res-chip').first()).toHaveText('已禁用')

    // 外观 tab：切日间立即生效并写回 localStorage
    await page.getByTestId('settings-nav-appearance').click()
    await expect(page.locator('.settings-theme-card')).toHaveCount(2)
    await page.locator('.settings-theme-card').filter({ hasText: '日间' }).click()
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'light')
    expect(await page.evaluate(() => localStorage.getItem('web-theme'))).toBe('light')

    // 实例信息 tab
    await page.getByTestId('settings-nav-instance').click()
    await expect(page.locator('.settings-stats .res-stat')).toHaveCount(4)

    // 安全是快捷入口：跳到独立页面
    await page.getByTestId('settings-nav-security').click()
    await expect(page).toHaveURL(/\/security$/)
    await expect(page.locator('.res-title')).toHaveText('安全')
    await expect(page.locator('.settings-stats .res-stat').first()).toContainText('登录通道')
    await expect(page.locator('.settings-row').filter({ hasText: 'APP_PASSWORD' })).toBeVisible()
  })

  test('系统设置：主题/字号/密度即时生效并保存', async ({ page }) => {
    await page.goto('/')
    await page.getByTestId('nav-settings').click()
    await page.getByTestId('settings-nav-system').click()
    await expect(page.locator('.setting-group')).toHaveCount(3)
    await expect(page.locator('.setting-group-title').first()).toContainText('界面')

    // 主题跟随系统：模式写入本机
    await page.getByRole('group', { name: '主题外观' }).getByRole('button', { name: '跟随系统' }).click()
    // 字号 110%：zoom 即时生效
    await page.getByRole('group', { name: '字号缩放' }).getByRole('button', { name: '110%' }).click()
    expect(await page.evaluate(() => document.documentElement.style.zoom)).toBe('1.1')

    // 保存后落 localStorage；恢复默认回到 100%
    await page.locator('.settings-main-actions').getByRole('button', { name: '保存设置' }).click()
    expect(await page.evaluate(() => localStorage.getItem('ui-zoom'))).toBe('1.1')
    expect(await page.evaluate(() => localStorage.getItem('web-theme-mode'))).toBe('system')

    await page.locator('.settings-main-actions').getByRole('button', { name: '恢复默认' }).click()
    expect(await page.evaluate(() => document.documentElement.style.zoom)).toBe('')
    expect(await page.evaluate(() => localStorage.getItem('ui-zoom'))).toBe('1')
  })

  test('语音合成：Edge TTS 状态与试听参数保存', async ({ page }) => {
    await page.goto('/')
    await page.getByTestId('nav-settings').click()
    await page.getByTestId('settings-nav-tts').click()

    await expect(page.locator('.tts-card')).toHaveCount(2)
    await expect(page.locator('.tts-card').first()).toContainText('Edge TTS')
    await expect(page.locator('.tts-card').first()).toContainText('已接入')
    await expect(page.locator('.tts-card').first()).toContainText('TextToSpeechTools')

    // 语音参数保存在本机
    await page.getByRole('group', { name: '语速' }).getByRole('button', { name: '1.2×' }).click()
    const stored = await page.evaluate(() => localStorage.getItem('ui-tts-prefs'))
    expect(stored).toContain('"rate":"1.2"')
  })

  test('工作区：打开切换侧栏、新建与归档', async ({ page }) => {
    await page.goto('/settings')
    await page.getByTestId('settings-nav-workspace').click()
    await expect(page.locator('.settings-main-head h2')).toHaveText('工作区')
    await expect(page.locator('.ws-card')).toHaveCount(3)

    // 打开沙盒实验：卡片转「活跃」，侧栏工作区名同步
    const sandbox = page.locator('.ws-card').filter({ hasText: '沙盒实验' })
    await sandbox.getByRole('button', { name: '打开' }).click()
    await expect(page.locator('.nav-workspace-name')).toHaveText('沙盒实验')
    await expect(sandbox.locator('.res-chip')).toHaveText('活跃')

    // 新建工作区：「导入 / 新建」在页头，表单登记到本机
    await page.getByRole('button', { name: '+ 新建工作区' }).click()
    const form = page.locator('.ws-form')
    await form.locator('input').nth(0).fill('数据实验')
    await form.locator('input').nth(1).fill('D:/DoubaWork/data-lab')
    await form.getByRole('button', { name: '创建' }).click()
    await expect(page.locator('.ws-card')).toHaveCount(4)

    // 行内设置：编辑表单占满整行，Agent 归属默认「全部」
    const created = page.locator('.ws-card').filter({ hasText: '数据实验' })
    await created.getByRole('button', { name: '设置' }).click()
    const editor = page.locator('.ws-form-edit')
    await expect(editor).toContainText('设置「数据实验」')
    await expect(editor.locator('.ws-agent-chip').first()).toHaveText('全部')
    await editor.getByRole('button', { name: '取消' }).click()
    await expect(page.locator('.ws-form-edit')).toHaveCount(0)

    // 归档后移出网格；刷新仍保留归档记录
    await created.getByRole('button', { name: '归档' }).click()
    await expect(page.locator('.ws-card')).toHaveCount(3)
    await expect(page.locator('.ws-archive-row')).toHaveCount(1)
    await page.reload()
    await page.getByTestId('settings-nav-workspace').click()
    await expect(page.locator('.ws-archive-row')).toHaveCount(1)
  })

  test('旧 hash 书签就地改写为干净路径', async ({ page }) => {
    // 整页加载 #/mcp（模拟旧书签），应用启动时把地址改成 /mcp
    await page.goto('/#/mcp')
    await expect(page).toHaveURL(/\/mcp$/)
    await expect(page.locator('.res-title')).toHaveText('MCP')
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
