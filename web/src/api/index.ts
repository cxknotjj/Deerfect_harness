/**
 * API 统一出口:按 VITE_USE_MOCK 决定导出 mock 实现还是真实现(同一签名)。
 * 消费方统一 `import { api } from '@/api'`(或相对路径),不感知底层实现切换。
 */
import { realApi } from './client'
import type { Api } from './client'
import { mockApi } from './mock/mock'

export const api: Api = import.meta.env.VITE_USE_MOCK === 'true' ? mockApi : realApi

export * from './types'
export type { Api, StreamHandlers } from './client'
