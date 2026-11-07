import { expect, test } from '@playwright/test'

/**
 * PWA 离线门：断网时用已缓存的应用外壳渲染离线页，而不是白屏；恢复联网自动退出。
 * service worker 在 dev 下不注册（插件 devOptions 默认关闭），此处验证门的交互；
 * manifest/sw 的构建产物在 `npm run build` 后由 dist 校验。
 */
test('断网显示离线门，恢复联网后自动退出', async ({ page, context }) => {
  await page.goto('/')
  await expect(page.locator('.app-shell')).toBeVisible()

  await context.setOffline(true)
  await expect(page.locator('.offline-view')).toBeVisible()
  await expect(page.getByRole('button', { name: '重新连接' })).toBeVisible()

  await context.setOffline(false)
  await expect(page.locator('.offline-view')).toBeHidden()
  await expect(page.locator('.app-shell')).toBeVisible()
})
