import { defineConfig } from 'vitest/config'

// Vitest 单测配置:环境 jsdom(chatCache 依赖 localStorage);
// 测试文件约定 src 下的 *.test.ts,与源码同目录便于维护。
export default defineConfig({
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.ts'],
  },
})
