/**
 * 侧边栏系统诊断：只回答「服务端还可达吗」。
 *
 * 组件级健康（NapCat / Docker / MCP）详情留在监控页——本机开发时那几项常年
 * 未启用，若直接映射成侧栏红灯会变成噪音，所以这里只按一次轻量请求的成败着色。
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from '../api'

export type SystemHealth = 'checking' | 'online' | 'offline'

export function useSystemHealth(intervalMs = 60_000) {
  const health = ref<SystemHealth>('checking')
  let timer: number | undefined

  async function check(): Promise<void> {
    try {
      await api.monitor(1, '', '', 1)
      health.value = 'online'
    } catch {
      health.value = 'offline'
    }
  }

  onMounted(() => {
    void check()
    timer = window.setInterval(() => {
      void check()
    }, intervalMs)
  })

  onBeforeUnmount(() => {
    if (timer !== undefined) window.clearInterval(timer)
  })

  return { health, check }
}
