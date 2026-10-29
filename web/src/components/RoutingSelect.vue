/**
 * 执行路径选择器(自绘 drop-up,与 AgentSelect 同交互口径):
 * auto=服务端判定(规则短路 + LLM 判灰区),agentic=固定单 Agent 自主执行,
 * graph=固定 Graph 编排。显式选项随每轮消息以 ChatRequest.routing 下发,服务端标记
 * Source.OVERRIDE;auto 不携带该字段(等同于未指定)。
 */
<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { RoutingMode } from '../api/types'

const props = defineProps<{ modelValue: RoutingMode }>()
const emit = defineEmits<{ 'update:modelValue': [value: RoutingMode] }>()

interface Option {
  value: RoutingMode
  label: string
  hint: string
}

const OPTIONS: Option[] = [
  { value: 'auto', label: '自动', hint: '服务端判定：工作流形态规则短路 + LLM 判灰区' },
  { value: 'agentic', label: 'Agentic', hint: '单 Agent 自主多轮工具调用，适合探索型任务' },
  { value: 'graph', label: 'Graph', hint: '拆分并行工作流 + 聚合交付，适合多产物任务' },
]

const open = ref(false)
const rootEl = ref<HTMLElement | null>(null)

const selected = computed(() => OPTIONS.find((o) => o.value === props.modelValue) ?? OPTIONS[0])

function toggle(): void {
  open.value = !open.value
}

function choose(value: RoutingMode): void {
  emit('update:modelValue', value)
  open.value = false
}

function onDocClick(e: MouseEvent): void {
  if (open.value && rootEl.value && !rootEl.value.contains(e.target as Node)) open.value = false
}

function onKeydown(e: KeyboardEvent): void {
  if (!open.value) return
  if (e.key === 'Escape') {
    open.value = false
  }
}

onMounted(() => document.addEventListener('click', onDocClick))
onBeforeUnmount(() => document.removeEventListener('click', onDocClick))
</script>

<template>
  <div ref="rootEl" class="agent-select" @keydown="onKeydown">
    <button
      class="agent-trigger"
      type="button"
      :aria-expanded="open"
      aria-haspopup="listbox"
      :title="selected.hint"
      @click="toggle"
    >
      <span class="agent-select-label">路径</span>
      <span class="agent-trigger-value">{{ selected.label }}</span>
      <svg class="agent-chevron" :class="{ 'agent-chevron-open': open }" width="10" height="6" viewBox="0 0 10 6" aria-hidden="true">
        <path d="M1 1l4 4 4-4" stroke="currentColor" stroke-width="1.5" fill="none" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
    </button>

    <ul v-if="open" class="agent-menu" role="listbox" aria-label="选择执行路径">
      <li
        v-for="o in OPTIONS"
        :key="o.value"
        class="agent-option"
        :class="{ 'agent-option-selected': o.value === modelValue }"
        role="option"
        :aria-selected="o.value === modelValue"
        :title="o.hint"
        @click="choose(o.value)"
      >
        <span>{{ o.label }}</span>
        <svg v-if="o.value === modelValue" class="agent-check" width="12" height="12" viewBox="0 0 12 12" aria-hidden="true">
          <path d="M2 6.5L5 9.5L10 3" stroke="currentColor" stroke-width="1.6" fill="none" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </li>
    </ul>
  </div>
</template>
