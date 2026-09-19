/**
 * 聊天窗顶部 Agent 下拉:
 * 数据来自 listAgents(名称集合);选中项 value 用数字 id,按「列表下标 + 1」约定
 * (后端 AgentView 只有名称,bindAgent 需数字 id)。无选中会话时禁用。
 */
<script setup lang="ts">
defineProps<{ agents: string[]; modelValue: number | null; disabled: boolean }>()

const emit = defineEmits<{ 'update:modelValue': [value: number | null] }>()

function onChange(e: Event): void {
  const value = (e.target as HTMLSelectElement).value
  emit('update:modelValue', value === '' ? null : Number(value))
}
</script>

<template>
  <label class="agent-select">
    <span class="agent-select-label">Agent</span>
    <select
      class="agent-select-input"
      :value="modelValue ?? ''"
      :disabled="disabled"
      :title="disabled ? '选择会话后可切换 Agent' : '切换当前会话使用的 Agent'"
      @change="onChange"
    >
      <option value="" disabled>默认 Agent</option>
      <option v-for="(name, i) in agents" :key="name" :value="i + 1">{{ name }}</option>
    </select>
  </label>
</template>
