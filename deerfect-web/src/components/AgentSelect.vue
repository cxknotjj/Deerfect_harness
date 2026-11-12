/**
 * Agent 选择器(自绘 drop-up):触发器胶囊(Agent + 当前值 + chevron)+ 向上展开浮层。
 * 浮层走主题 token(bg-panel/grid-line/菜单阴影),选中项 accent + ✓;
 * 键盘:Enter 确认 / Esc 关闭 / ↑↓ 即选即走;点击组件外关闭。
 * 列表条目来自 GET /api/harness/agents(id = agent 表主键),选中即以真实 id 绑定会话。
 */
<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { AgentView } from '../api/types'

const props = defineProps<{ agents: AgentView[]; modelValue: number | null; disabled: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: number | null] }>()

const open = ref(false)
const rootEl = ref<HTMLElement | null>(null)

/** 触发器文案:当前绑定名;未绑定时为占位「默认 Agent」 */
const selectedName = computed(
  () => props.agents.find((a) => a.id === props.modelValue)?.name ?? '默认 Agent',
)

function toggle(): void {
  if (!props.disabled) open.value = !open.value
}

function choose(id: number): void {
  emit('update:modelValue', id)
  open.value = false
}

/** 点击组件外关闭浮层 */
function onDocClick(e: MouseEvent): void {
  if (open.value && rootEl.value && !rootEl.value.contains(e.target as Node)) open.value = false
}

/** 键盘:Esc 关闭;↑↓ 即选即走(循环);Enter 确认当前 */
function onKeydown(e: KeyboardEvent): void {
  if (!open.value) return
  if (e.key === 'Escape') {
    open.value = false
    return
  }
  if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp' && e.key !== 'Enter') return
  e.preventDefault()
  const list = props.agents
  if (list.length === 0) return
  const idx = list.findIndex((a) => a.id === props.modelValue)
  if (e.key === 'Enter') {
    if (idx !== -1) choose(list[idx].id)
    return
  }
  const next =
    e.key === 'ArrowDown' ? Math.min(idx + 1, list.length - 1) : Math.max(idx - 1, 0)
  if (list[next]) choose(list[next].id)
}

onMounted(() => document.addEventListener('click', onDocClick))
onBeforeUnmount(() => document.removeEventListener('click', onDocClick))
</script>

<template>
  <div ref="rootEl" class="agent-select" @keydown="onKeydown">
    <button
      class="agent-trigger"
      type="button"
      :disabled="disabled"
      :aria-expanded="open"
      aria-haspopup="listbox"
      :title="disabled ? '选择会话后可切换 Agent' : '切换当前会话使用的 Agent'"
      @click="toggle"
    >
      <span class="status-dot" aria-hidden="true"></span>
      <span class="agent-select-label">Agent</span>
      <span class="agent-trigger-value" :class="{ 'agent-trigger-placeholder': modelValue === null }">{{
        selectedName
      }}</span>
      <svg class="agent-chevron" :class="{ 'agent-chevron-open': open }" width="10" height="6" viewBox="0 0 10 6" aria-hidden="true">
        <path d="M1 1l4 4 4-4" stroke="currentColor" stroke-width="1.5" fill="none" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
    </button>

    <ul v-if="open" class="agent-menu" role="listbox" aria-label="选择 Agent">
      <li class="agent-option agent-option-placeholder" role="option" aria-disabled="true">
        <span>默认 Agent</span>
      </li>
      <li
        v-for="a in agents"
        :key="a.id"
        class="agent-option"
        :class="{ 'agent-option-selected': a.id === modelValue }"
        role="option"
        :aria-selected="a.id === modelValue"
        @click="choose(a.id)"
      >
        <span>{{ a.name }}</span>
        <svg v-if="a.id === modelValue" class="agent-check" width="12" height="12" viewBox="0 0 12 12" aria-hidden="true">
          <path d="M2 6.5L5 9.5L10 3" stroke="currentColor" stroke-width="1.6" fill="none" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </li>
    </ul>
  </div>
</template>
