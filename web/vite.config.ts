import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { VitePWA } from 'vite-plugin-pwa'

// 自包含配置:dev 代理 /api 到本地 server(8080),实现同域调用
// 真实模式需本地启动 server;mock 模式(.env.development VITE_USE_MOCK=true)无需 server
//
// PWA(手机主屏入口):manifest 声明安装信息,service worker 预缓存应用壳,
// 断网仍能启动并显示离线门(App.vue 的 OfflineView)。字体 woff2 不进预缓存
// (fontsource 子集体量大),改用运行时 CacheFirst,首次用到后离线可用。
export default defineConfig({
  plugins: [
    vue(),
    VitePWA({
      registerType: 'autoUpdate',
      manifest: {
        name: 'DeerFect HARNESS',
        short_name: 'HARNESS',
        description: '个人 Agent 控制台',
        lang: 'zh-CN',
        start_url: '/',
        scope: '/',
        display: 'standalone',
        background_color: '#0d1219',
        theme_color: '#0d1219',
        icons: [
          { src: '/icons/pwa-192x192.png', sizes: '192x192', type: 'image/png' },
          { src: '/icons/pwa-512x512.png', sizes: '512x512', type: 'image/png' },
          {
            src: '/icons/pwa-maskable-512x512.png',
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
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
