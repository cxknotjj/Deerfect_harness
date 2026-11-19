import { defineConfig, devices } from '@playwright/test'

const mode = process.env.E2E_BUILD_MODE ?? 'pages'
if (mode !== 'pages' && mode !== 'pwa') throw new Error(`Unknown E2E_BUILD_MODE: ${mode}`)
const channel = process.env.PLAYWRIGHT_CHANNEL

export default defineConfig({
  testDir: './e2e',
  testMatch: `${mode}.built.ts`,
  timeout: 60_000,
  expect: { timeout: 20_000 },
  reporter: process.env.CI ? 'github' : 'list',
  use: {
    ...devices['Desktop Chrome'],
    ...(channel ? { channel } : {}),
    baseURL: 'http://127.0.0.1:4175',
    serviceWorkers: mode === 'pages' ? 'block' : 'allow',
    trace: 'retain-on-failure',
  },
  webServer: {
    command: `npm run ${mode === 'pages' ? 'build:pages' : 'build'} && node e2e/serve-built.mjs ${mode}`,
    url: `http://127.0.0.1:4175${mode === 'pages' ? '/Deerfect_harness/' : '/'}`,
    reuseExistingServer: false,
    timeout: 120_000,
    env: { VITE_USE_MOCK: 'true', VITE_ENABLE_SW: mode === 'pwa' ? 'true' : 'false' },
  },
})
