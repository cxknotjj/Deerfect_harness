/**
 * 输入区:textarea(Enter 发送、Shift+Enter 换行、输入法组词中的 Enter 不发送)+ 发送按钮。
 * streaming 时按钮变「停止」(触发 stop),并禁止再次发送(输入仍可继续,便于预写下一条)。
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
  <footer class="composer">
    <textarea
      ref="inputEl"
      v-model="text"
      class="composer-input"
      rows="3"
      :placeholder="disabled ? '回复生成中…' : '输入消息,Enter 发送,Shift+Enter 换行'"
      @keydown="onKeydown"
    ></textarea>
    <button v-if="!disabled" class="btn btn-primary" :disabled="text.trim() === ''" @click="submit">发送</button>
    <button v-else class="btn btn-stop" @click="emit('stop')">停止</button>
  </footer>
</template>
