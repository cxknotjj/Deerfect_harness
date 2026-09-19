import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 自包含配置:dev 代理 /api 到本地 server(8080),实现同域调用
// 真实模式需本地启动 server;mock 模式(.env.development VITE_USE_MOCK=true)无需 server
export default defineConfig({
  plugins: [vue()],
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
