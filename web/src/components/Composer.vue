/**
 * 输入区(卡片式):无边框 textarea(Enter 发送、Shift+Enter 换行、输入法组词中的
 * Enter 不发送)+ 底部控制行(左侧 controls 插槽放 Agent 选择,右侧圆形发送键)。
 * streaming 时发送键变圆形停止键(触发 stop),并禁止再次发送(输入仍可继续)。
 */
<script setup lang="ts">
import { ref } from 'vue'

const props = defineProps<{ disabled: boolean }>()
const emit = defineEmits<{ send: [text: string]; stop: [] }>()

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
        <slot name="controls" />
      </div>
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
  </section>
</template>
