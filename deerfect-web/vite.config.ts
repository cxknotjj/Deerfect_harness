import { copyFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import type { Plugin } from 'vite'
import vue from '@vitejs/plugin-vue'
import { VitePWA } from 'vite-plugin-pwa'
import { docsMetaPlugin } from './vite.docs-meta'

const docsRoot = fileURLToPath(new URL('../docs', import.meta.url))
const webRoot = fileURLToPath(new URL('.', import.meta.url))

// 自包含配置:dev 代理 /api 到本地 server(8080),实现同域调用
// 真实模式需本地启动 server;mock 模式(.env.development VITE_USE_MOCK=true)无需 server
//
// PWA/Service Worker 为可选项(VITE_ENABLE_SW=true 才启用):manifest 声明安装信息,
// service worker 预缓存应用壳,断网仍能启动并显示离线门(App.vue 的 OfflineView)。
// 默认关闭——静态托管换哈希后,旧 SW 的导航回退会返回指向已删除资源的旧壳(普通刷新黑屏)。
// 关闭时构建末尾写一个自毁 sw.js,让线上遗留的旧 SW 更新后自行注销。
//
// GitHub Pages 项目页部署在仓库子路径下(https://deerfect.me/Deerfect_harness/),
// 构建 base 必须与之一致;pwa 的 start_url/scope/icons 同样是绝对路径,必须一起带 base。
// 为避免影响本地开发与 E2E,只有 `--mode pages`(npm run build:pages)才切到子路径。
const PAGES_BASE = '/Deerfect_harness/'

const distDir = fileURLToPath(new URL('./dist', import.meta.url))

const SELF_DESTROYING_SW = `// DeerFect 构建：退役旧 Service Worker。
// 旧 SW 的导航回退会把指向已删除哈希资源的旧壳返回给刷新请求（黑屏），
// 本脚本被 SW 更新检查拉取后清空缓存并注销自身，后续导航重新走网络。
self.addEventListener('install', () => {
  self.skipWaiting()
})
self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    const keys = await caches.keys()
    await Promise.all(keys.map((key) => caches.delete(key)))
    await self.registration.unregister()
    const clients = await self.clients.matchAll({ type: 'window' })
    await Promise.all(clients.map((client) => client.navigate(client.url)))
  })())
})
`

/**
 * 构建收尾：
 * 1. GitHub Pages 没有 SPA 回退：直接访问/刷新 /Deerfect_harness/chat 这类深链会返回 404。
 *    pages 模式把 index.html 复制为 404.html，Pages 对未命中路径回该壳，
 *    应用启动后再按 pathname 还原页面（App.vue 的 parseRoute）。
 * 2. 未启用 SW 时在相同路径写自毁脚本，清理线上遗留的旧 Service Worker 缓存。
 */
function buildFallback(mode: string, enableSw: boolean): Plugin {
  return {
    name: 'build-fallback',
    apply: 'build',
    closeBundle() {
      if (mode === 'pages') {
        copyFileSync(resolve(distDir, 'index.html'), resolve(distDir, '404.html'))
      }
      if (!enableSw) {
        writeFileSync(resolve(distDir, 'sw.js'), SELF_DESTROYING_SW)
      }
    },
  }
}

export default defineConfig(({ mode }) => {
  const base = mode === 'pages' ? PAGES_BASE : '/'
  // SW 开关：.env.* 或命令前设置 VITE_ENABLE_SW=true 才生成 PWA（默认关闭）
  const enableSw = loadEnv(mode, webRoot, 'VITE_').VITE_ENABLE_SW === 'true'

  return {
    base,
    plugins: [
      vue(),
      docsMetaPlugin(docsRoot),
      buildFallback(mode, enableSw),
      ...(enableSw
        ? [
            VitePWA({
              registerType: 'autoUpdate',
              manifest: {
                name: 'DeerFect HARNESS',
                short_name: 'HARNESS',
                description: '个人 Agent 控制台',
                lang: 'zh-CN',
                start_url: base,
                scope: base,
                display: 'standalone',
                background_color: '#0d1219',
                theme_color: '#0d1219',
                icons: [
                  { src: `${base}deerfect-mark.svg`, sizes: 'any', type: 'image/svg+xml' },
                  { src: `${base}icons/pwa-192x192.png`, sizes: '192x192', type: 'image/png' },
                  { src: `${base}icons/pwa-512x512.png`, sizes: '512x512', type: 'image/png' },
                  {
                    src: `${base}icons/pwa-maskable-512x512.png`,
                    sizes: '512x512',
                    type: 'image/png',
                    purpose: 'maskable',
                  },
                ],
              },
              workbox: {
                globPatterns: ['**/*.{js,css,html,png,svg}'],
                runtimeCaching: [
                  {
                    urlPattern: /\/assets\/.*\.woff2$/,
                    handler: 'CacheFirst',
                    options: {
                      cacheName: 'web-fonts',
                      expiration: { maxEntries: 40, maxAgeSeconds: 60 * 60 * 24 * 365 },
                    },
                  },
                ],
              },
            }),
          ]
        : []),
    ],
    server: {
      port: 80,
      // 文档页直接 ?raw 打包仓库根下的 docs/（在 deerfect-web 之外），dev 需放行上一级
      fs: { allow: ['..'] },
      proxy: {
        '/api': {
          target: 'http://localhost:8080',
          changeOrigin: true,
        },
      },
    },
    preview: {
      // PWA 真机验收需 HTTPS:允许 quick tunnel 域名作为 Host(仅 *.trycloudflare.com)
      allowedHosts: ['.trycloudflare.com'],
    },
  }
})
