<!--
  分段式状态栏（自 ChatWindow 拆出，2026-11-05）：消息数 / 会话累计 token / 流式状态 / 最近耗时。
  仅展示真实数据；无会话内容时不渲染。观测拉取失败静默（状态栏不因统计缺失报错）。
-->
<script setup lang="ts">
import { ref, watch } from 'vue'
import { api } from '../../api'
import type { MessageItem } from '../../composables/useChat'

const props = defineProps<{ messages: MessageItem[]; streaming: boolean; sessionId: string }>()

/** 最近一次流式耗时:streaming 置 true 起表,false 结算(毫秒);无数据不显示分段 */
const lastElapsedMs = ref<number | null>(null)
let streamStartTs = 0
watch(
  () => props.streaming,
  (on) => {
    if (on) {
      streamStartTs = performance.now()
    } else if (streamStartTs > 0) {
      lastElapsedMs.value = Math.round(performance.now() - streamStartTs)
      streamStartTs = 0
    }
  },
)

/** 会话累计 token(观测端点汇总,与轨迹页同源):输入/输出分向;无会话不显示分段 */
const tokenFlow = ref<{ input: number; output: number } | null>(null)

async function loadTokens(): Promise<void> {
  if (props.sessionId === '') {
    tokenFlow.value = null
    return
  }
  try {
    const calls = await api.listLlmCalls(props.sessionId)
    let input = 0
    let output = 0
    for (const c of calls) {
      input += c.promptTokens ?? 0
      output += c.completionTokens ?? 0
    }
    tokenFlow.value = { input, output }
  } catch {
    /* 观测拉取失败静默:状态栏不因统计缺失报错 */
  }
}

watch(() => props.sessionId, loadTokens, { immediate: true })
// 流结束后刷新本轮消耗:观测落库在服务端异步执行,留 1s 缓冲再拉
watch(
  () => props.streaming,
  (on, old) => {
    if (old === true && on === false) setTimeout(loadTokens, 1000)
  },
)

/** token 数 → 紧凑计数:812 / 1.2k / 435.0k(与轨迹页口径一致) */
function fmtTokens(n: number): string {
  if (n < 1000) return String(n)
  return `${(n / 1000).toFixed(1)}k`
}
</script>

<template>
  <div v-if="messages.length > 0" class="chat-statusbar">
    <span>{{ messages.length }} 条消息</span>
    <template v-if="tokenFlow !== null">
      <span class="statusbar-sep">│</span>
      <span>输入 {{ fmtTokens(tokenFlow.input) }} tok</span>
      <span class="statusbar-sep">│</span>
      <span>输出 {{ fmtTokens(tokenFlow.output) }} tok</span>
    </template>
    <span class="statusbar-sep">│</span>
    <span :class="{ 'chat-statusbar-live': streaming }">{{ streaming ? '生成中…' : '空闲' }}</span>
    <template v-if="lastElapsedMs !== null">
      <span class="statusbar-sep">│</span>
      <span>上次 {{ (lastElapsedMs / 1000).toFixed(1) }}s</span>
    </template>
  </div>
</template>
