import { copyFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vite'
import type { Plugin } from 'vite'
import vue from '@vitejs/plugin-vue'
import { VitePWA } from 'vite-plugin-pwa'
import { docsMetaPlugin } from './vite.docs-meta'

const docsRoot = fileURLToPath(new URL('../docs', import.meta.url))

// 自包含配置:dev 代理 /api 到本地 server(8080),实现同域调用
// 真实模式需本地启动 server;mock 模式(.env.development VITE_USE_MOCK=true)无需 server
//
// PWA(手机主屏入口):manifest 声明安装信息,service worker 预缓存应用壳,
// 断网仍能启动并显示离线门(App.vue 的 OfflineView)。字体 woff2 不进预缓存
// (fontsource 子集体量大),改用运行时 CacheFirst,首次用到后离线可用。
//
// GitHub Pages 项目页部署在仓库子路径下(https://deerfect.me/Deerfect_harness/),
// 构建 base 必须与之一致;pwa 的 start_url/scope/icons 同样是绝对路径,必须一起带 base。
// 为避免影响本地开发与 E2E,只有 `--mode pages`(npm run build:pages)才切到子路径。
const PAGES_BASE = '/Deerfect_harness/'

const distDir = fileURLToPath(new URL('./dist', import.meta.url))

/**
 * GitHub Pages 没有 SPA 回退：直接访问/刷新 /Deerfect_harness/chat 这类深链会返回 404。
 * pages 模式构建后把 index.html 复制为 404.html，Pages 对未命中路径回该壳，
 * 应用启动后再按 pathname 还原页面（App.vue 的 parseRoute）。
 */
function pagesSpaFallback(mode: string): Plugin {
  return {
    name: 'pages-spa-fallback',
    apply: 'build',
    closeBundle() {
      if (mode !== 'pages') return
      copyFileSync(resolve(distDir, 'index.html'), resolve(distDir, '404.html'))
    },
  }
}

export default defineConfig(({ mode }) => {
  const base = mode === 'pages' ? PAGES_BASE : '/'

  return {
    base,
    plugins: [
      vue(),
      docsMetaPlugin(docsRoot),
      pagesSpaFallback(mode),
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
