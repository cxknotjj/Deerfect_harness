import { defineConfig, devices } from '@playwright/test'

/**
 * E2E 冒烟配置：mock 模式（VITE_USE_MOCK=true 注入 webServer），不需要后端与数据库。
 * 端口固定 4173 + strictPort：端口被占时立即失败，避免 Playwright 等在一个并不存在的 URL 上。
 *
 * 浏览器选择：默认用 Playwright 自带 Chromium（CI 由 `npx playwright install --with-deps chromium`
 * 安装）；本地来不及下载时可复用系统 Chrome/Edge：
 *   PowerShell: $env:PLAYWRIGHT_CHANNEL='chrome'; npm run test:e2e
 *   bash:       PLAYWRIGHT_CHANNEL=chrome npm run test:e2e
 */
const channel = process.env.PLAYWRIGHT_CHANNEL

export default defineConfig({
  testDir: './e2e',
  timeout: 90_000,
  expect: { timeout: 20_000 },
  reporter: process.env.CI ? 'github' : 'list',
  use: {
    baseURL: 'http://127.0.0.1:4173',
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'], ...(channel ? { channel } : {}) },
    },
  ],
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 4173 --strictPort',
    url: 'http://127.0.0.1:4173',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
    env: { VITE_USE_MOCK: 'true' },
  },
})
