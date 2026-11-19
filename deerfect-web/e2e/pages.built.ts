import { expect, test } from '@playwright/test'

const base = '/Deerfect_harness/'

for (const path of ['chat/trace', 'knowledge', 'docs/guides/setup.md']) {
  test(`Pages 静态托管：${path} 直达与刷新`, async ({ page }) => {
    const errors: string[] = []
    const assets: string[] = []
    page.on('pageerror', (error) => errors.push(error.message))
    page.on('response', (response) => {
      if (['script', 'stylesheet', 'font', 'image'].includes(response.request().resourceType())) {
        assets.push(response.url())
        if (!response.ok()) errors.push(`${response.status()} ${response.url()}`)
      }
    })
    const response = await page.goto(base + path)
    expect(response?.status()).toBe(404)
    await expect(page.locator('.app-shell')).toBeVisible()
    await expect(page).toHaveURL(new RegExp(`${base}${path}$`))
    if (path === 'chat/trace') {
      await expect(page.getByRole('tab', { name: '轨迹' })).toHaveAttribute('aria-selected', 'true')
    } else if (path === 'docs/guides/setup.md') {
      await expect(page.locator('.docs-doc-title')).toHaveText('本地部署详解')
    } else {
      await expect(page.getByRole('heading', { name: '知识库', exact: true })).toBeVisible()
    }
    const reload = await page.reload()
    expect(reload?.status()).toBe(404)
    await expect(page.locator('.app-shell')).toBeVisible()
    await expect(page).toHaveURL(new RegExp(`${base}${path}$`))
    await page.getByTestId('nav-dashboard').click()
    await expect(page.locator('.dash-title')).toHaveText('仪表盘')
    await expect(page).toHaveURL(new RegExp(`${base}dashboard$`))
    expect(assets.length).toBeGreaterThan(0)
    expect(assets.every((url) => new URL(url).pathname.startsWith(base))).toBe(true)
    expect(errors).toEqual([])
  })
}
