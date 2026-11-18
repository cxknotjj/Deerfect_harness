import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { docsMetaPlugin } from './vite.docs-meta'

const docsRoot = fileURLToPath(new URL('../docs', import.meta.url))

// Vitest 单测配置:环境 jsdom(chatCache/组件测试依赖 DOM);
// 测试文件约定 src 下的 *.test.ts,与源码同目录便于维护。
export default defineConfig({
  plugins: [vue(), docsMetaPlugin(docsRoot)],
  // 文档注册表用 ?raw 读取仓库根下的 docs/，单测同样要放行上一级目录
  server: { fs: { allow: ['..'] } },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.ts'],
  },
})
