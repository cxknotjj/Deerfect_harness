import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

// Vitest 单测配置:环境 jsdom(chatCache/组件测试依赖 DOM);
// 测试文件约定 src 下的 *.test.ts,与源码同目录便于维护。
export default defineConfig({
  plugins: [vue()],
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.ts'],
  },
})
