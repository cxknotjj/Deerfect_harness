import { expect, test } from '@playwright/test'

test('PWA 构建产物：Service Worker 控制后可断网刷新与冷开页面', async ({ page, context }) => {
  await page.goto('/')
  await expect(page.locator('.app-shell')).toBeVisible()
  const manifest = await page.locator('link[rel="manifest"]').getAttribute('href')
  expect(manifest).toBeTruthy()
  const response = await page.request.get(manifest!)
  expect(response.ok()).toBe(true)
  expect(await response.json()).toMatchObject({ start_url: '/', scope: '/' })
  await page.evaluate(() => navigator.serviceWorker.ready.then(() => undefined))
  await page.waitForFunction(() => navigator.serviceWorker.controller !== null)
  await page.getByTestId('nav-docs').click()
  await expect(page.locator('.docs-article')).toBeVisible()

  await context.setOffline(true)
  await page.reload({ waitUntil: 'domcontentloaded' })
  await expect(page.locator('.offline-view')).toBeVisible()
  const fresh = await context.newPage()
  await fresh.goto('/chat', { waitUntil: 'domcontentloaded' })
  await expect(fresh.locator('.offline-view')).toBeVisible()
  await expect(fresh.getByRole('button', { name: '重新连接' })).toBeVisible()

  await context.setOffline(false)
  await expect(page.locator('.offline-view')).toBeHidden()
  await expect(page.locator('.docs-article')).toBeVisible()
  await expect(fresh.locator('.app-shell')).toBeVisible()
  await expect(fresh.getByRole('tab', { name: '对话', exact: true })).toBeVisible()
})
