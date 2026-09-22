/**
 * 输入区(卡片式):左上角 ⊕ 附件占位 + 无边框 textarea(Enter 发送、Shift+Enter 换行、
 * 输入法组词中的 Enter 不发送)+ 底部控制行(左侧 Workspace 权限占位,右侧 controls 插槽
 * 放 Agent 选择 + 状态灯 + 圆形发送键)。
 * streaming 时发送键变圆形停止键(触发 stop),并禁止再次发送(输入仍可继续);
 * 状态灯随 disabled(即 streaming)点亮呼吸。placeholder 事件上报占位点击(label)。
 */
<script setup lang="ts">
import { ref } from 'vue'

const props = defineProps<{ disabled: boolean }>()
const emit = defineEmits<{ send: [text: string]; stop: []; placeholder: [label: string] }>()

const text = ref('')
const inputEl = ref<HTMLTextAreaElement | null>(null)

/** Enter 发送;Shift+Enter 走 textarea 默认换行;isComposing 时是输入法确认,不发送 */
function onKeydown(e: KeyboardEvent): void {
  if (e.key !== 'Enter' || e.shiftKey) return
  e.preventDefault()
  if (e.isComposing) return
  submit()
}

function submit(): void {
  const value = text.value.trim()
  if (value === '' || props.disabled) return
  emit('send', value)
  text.value = ''
  // 发送后回到输入框继续输入
  requestAnimationFrame(() => inputEl.value?.focus())
}
</script>

<template>
  <section class="composer">
    <button
      class="composer-attach"
      type="button"
      title="添加附件"
      @click="emit('placeholder', 'attach')"
    >⊕</button>
    <textarea
      ref="inputEl"
      v-model="text"
      class="composer-input"
      rows="3"
      :placeholder="disabled ? '回复生成中…' : '给智能体发消息'"
      @keydown="onKeydown"
    ></textarea>
    <div class="composer-controls">
      <div class="composer-controls-left">
        <button
          class="composer-shield"
          type="button"
          title="工作区权限"
          @click="emit('placeholder', 'workspace')"
        >🛡 Workspace Write<span class="composer-shield-chevron">▾</span></button>
      </div>
      <div class="composer-controls-right">
        <slot name="controls" />
        <span class="composer-dot" :class="{ live: disabled }" aria-hidden="true"></span>
        <button
          v-if="!disabled"
          class="composer-send"
          type="button"
          title="发送"
          :disabled="text.trim() === ''"
          @click="submit"
        >↑</button>
        <button
          v-else
          class="composer-send composer-send-stop"
          type="button"
          title="停止"
          @click="emit('stop')"
        >■</button>
      </div>
    </div>
  </section>
</template>
