import { readonly, ref } from 'vue'

/**
 * 全局错误横幅：把「用户看不到的低层异常」变成一条可见提示。
 *
 * <p>为什么需要：此前组件渲染抛错或 Promise 未捕获时，页面直接白屏——用户除了刷新
 * 没有任何信息。这里只做「告知 + 可关闭」，不做自动重试：能自动恢复的错误应该在
 * 产生它的地方处理，抛到全局说明已经是不可预期的问题。
 *
 * <p>入口有三处（main.ts 的 errorHandler / unhandledrejection，App.vue 的 onErrorCaptured），
 * 统一收敛到 {@link reportGlobalError}，避免三个地方各写一套提示。
 */
const message = ref('')

/** 横幅自动消失时长：留足阅读时间，又不至于长期占位 */
const AUTO_DISMISS_MS = 8_000

let dismissTimer: ReturnType<typeof setTimeout> | undefined

/** 把异常摘要成一行可读文本（去掉堆栈噪音，只留类型与首行消息） */
function describe(err: unknown): string {
  if (err instanceof Error) {
    const first = err.message.split('\n')[0]
    return first ? `${err.name}: ${first}` : err.name
  }
  const text = String(err ?? '').trim()
  return text || '未知错误'
}

/** 上报一个全局异常：设置横幅文本并重置自动消失计时 */
export function reportGlobalError(err: unknown, context?: string): void {
  const detail = describe(err)
  message.value = context ? `${context}：${detail}` : detail
  if (dismissTimer) clearTimeout(dismissTimer)
  dismissTimer = setTimeout(() => {
    message.value = ''
  }, AUTO_DISMISS_MS)
  // 控制台保留完整堆栈：横幅只是「让用户知道出事了」，排查仍看 console
  console.error('[web]', context ?? '全局异常', err)
}

/** 手动关闭横幅 */
export function dismissGlobalError(): void {
  if (dismissTimer) clearTimeout(dismissTimer)
  dismissTimer = undefined
  message.value = ''
}

/** 供组件读取（只读，避免外部直接改文案） */
export function useGlobalError() {
  return { message: readonly(message), dismiss: dismissGlobalError }
}

/** 仅测试用：重置模块级状态，避免用例间互相污染 */
export function resetGlobalErrorForTest(): void {
  dismissGlobalError()
}
