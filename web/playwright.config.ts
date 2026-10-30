import { defineConfig, devices } from '@playwright/test'

/**
 * E2E 冒烟配置：mock 模式（VITE_USE_MOCK=true 注入 webServer），不需要后端与数据库。
 * 端口固定 4173 + strictPort：端口被占时立即失败，避免 Playwright 等在一个并不存在的 URL 上。
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 90_000,
  expect: { timeout: 20_000 },
  reporter: process.env.CI ? 'github' : 'list',
  use: {
    baseURL: 'http://127.0.0.1:4173',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 4173 --strictPort',
    url: 'http://127.0.0.1:4173',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
    env: { VITE_USE_MOCK: 'true' },
  },
})
