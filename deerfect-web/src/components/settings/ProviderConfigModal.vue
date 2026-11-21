<script setup lang="ts">
/**
 * 提供商配置弹窗：新建/编辑 code、Base URL、凭证引用、协议与启停。
 * 密钥只保存环境变量名，弹窗内不接触真实 key；code 编辑时只读，保证模型归属稳定。
 */
import { computed, ref, watch } from 'vue'
import type { LlmProviderUpsert, LlmProviderView } from '../../api/admin'

const props = defineProps<{
  open: boolean
  provider: LlmProviderView | null
  creating: boolean
  saving: boolean
  error: string
}>()

const emit = defineEmits<{
  close: []
  save: [payload: { providerId: number | null; body: LlmProviderUpsert }]
}>()

const code = ref('')
const baseUrl = ref('')
const credentialRef = ref('')
const protocol = ref('openai')
const enabled = ref(1)
const localError = ref('')

const title = computed(() => (props.creating ? '新建提供商' : '提供商配置'))
const subtitle = computed(() =>
  props.creating
    ? '登记新的云端模型来源；密钥只从环境变量读取'
    : `${props.provider?.code ?? ''} · ${props.provider?.protocol ?? ''} · #${props.provider?.id ?? ''}`,
)
const errorText = computed(() => localError.value || props.error)

watch(
  () => [props.open, props.provider, props.creating] as const,
  ([open]) => {
    if (!open) return
    localError.value = ''
    code.value = props.provider?.code ?? ''
    baseUrl.value = props.provider?.baseUrl ?? ''
    credentialRef.value = props.provider?.credentialRef ?? ''
    protocol.value = props.provider?.protocol || 'openai'
    enabled.value = props.provider?.enabled ?? 1
  },
  { immediate: true },
)

function submit(): void {
  localError.value = ''
  const trimmedCode = code.value.trim()
  const trimmedUrl = baseUrl.value.trim()
  const trimmedRef = credentialRef.value.trim()
  if (!trimmedCode) {
    localError.value = 'code 不能为空'
    return
  }
  if (!trimmedUrl) {
    localError.value = 'Base URL 不能为空'
    return
  }
  if (!trimmedRef) {
    localError.value = '凭证引用名不能为空'
    return
  }
  emit('save', {
    providerId: props.creating ? null : (props.provider?.id ?? null),
    body: {
      code: trimmedCode,
      baseUrl: trimmedUrl,
      credentialRef: trimmedRef,
      protocol: protocol.value,
      enabled: enabled.value,
    },
  })
}
</script>

<template>
  <div v-if="open" class="ra-modal-mask" @click.self="emit('close')">
    <section class="ra-modal provider-modal" role="dialog" aria-modal="true" aria-labelledby="provider-modal-title">
      <header class="ra-modal-head">
        <div>
          <h2 id="provider-modal-title">{{ title }}</h2>
          <p>{{ subtitle }}</p>
        </div>
        <button class="ra-modal-close" type="button" aria-label="关闭" @click="emit('close')">×</button>
      </header>

      <div class="ra-modal-body">
        <label class="ra-field">
          <span>code</span>
          <input
            v-model="code"
            class="res-input"
            :class="{ 'ra-readonly': !creating }"
            :disabled="!creating"
            placeholder="唯一标识，如 moonshot"
          />
          <small v-if="!creating" class="ra-field-hint">模型归属标识，保存后不可修改</small>
        </label>

        <label class="ra-field">
          <span>Base URL</span>
          <input v-model="baseUrl" class="res-input" placeholder="https://api.example.com/v1" />
        </label>

        <label class="ra-field">
          <span>凭证引用（环境变量名）</span>
          <input v-model="credentialRef" class="res-input" placeholder="MOONSHOT_API_KEY" />
          <small class="ra-field-hint">只存变量名，密钥不落库、不回传</small>
        </label>

        <div class="ra-field-row">
          <label class="ra-field">
            <span>协议</span>
            <select v-model="protocol" class="res-input">
              <option value="openai">openai</option>
              <option value="anthropic">anthropic</option>
            </select>
          </label>
          <label class="ra-field">
            <span>状态</span>
            <select v-model.number="enabled" class="res-input">
              <option :value="1">启用</option>
              <option :value="0">禁用</option>
            </select>
          </label>
        </div>

        <p v-if="errorText" class="res-alert" role="alert">{{ errorText }}</p>
      </div>

      <footer class="ra-modal-foot">
        <button class="res-btn" type="button" :disabled="saving" @click="emit('close')">取消</button>
        <button class="res-btn res-btn-primary" type="button" :disabled="saving" @click="submit">
          {{ saving ? '保存中…' : '保存' }}
        </button>
      </footer>
    </section>
  </div>
</template>
