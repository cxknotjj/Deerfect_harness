<script setup lang="ts">
/**
 * 模型管理面板：提供商卡片 + 提供商配置弹窗 + 模型列表弹窗。
 *
 * 卡片本身只读；「配置」「管理模型」「新建提供商」都走弹窗，避免在三位一体的
 * 卡片网格里展开长表单把列宽和相邻卡片挤乱。
 */
import { computed, onMounted, ref } from 'vue'
import { api } from '../../api'
import type { LlmModelView, LlmProviderUpsert, LlmProviderView } from '../../api/admin'
import ProviderConfigModal from './ProviderConfigModal.vue'
import ProviderModelsModal from './ProviderModelsModal.vue'

type ModalKind = 'config' | 'models'

const providers = ref<LlmProviderView[]>([])
const models = ref<LlmModelView[]>([])
const loading = ref(false)
const error = ref('')
const note = ref('')
const busyId = ref<number | null>(null)

const modal = ref<ModalKind | null>(null)
const modalError = ref('')
const saving = ref(false)
const activeProvider = ref<LlmProviderView | null>(null)
const creating = ref(false)

/** providerId → 模型列表（一次分组，避免模板里反复 filter） */
const modelsByProvider = computed(() => {
  const map = new Map<number, LlmModelView[]>()
  for (const model of models.value) {
    const list = map.get(model.providerId)
    if (list) list.push(model)
    else map.set(model.providerId, [model])
  }
  return map
})

const activeModels = computed(() =>
  activeProvider.value ? (modelsByProvider.value.get(activeProvider.value.id) ?? []) : [],
)

function modelsOf(provider: LlmProviderView): LlmModelView[] {
  return modelsByProvider.value.get(provider.id) ?? []
}

function enabledModelCount(provider: LlmProviderView): number {
  return modelsOf(provider).filter((model) => model.enabled === 1).length
}

async function loadAll(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const [providerRows, modelRows] = await Promise.all([api.listLlmProviders(), api.listLlmModels()])
    providers.value = providerRows
    models.value = modelRows
  } catch (e) {
    error.value = `模型配置读取失败：${e instanceof Error ? e.message : String(e)}`
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  activeProvider.value = null
  creating.value = true
  modalError.value = ''
  modal.value = 'config'
}

function openConfig(provider: LlmProviderView): void {
  activeProvider.value = provider
  creating.value = false
  modalError.value = ''
  modal.value = 'config'
}

function openModels(provider: LlmProviderView): void {
  activeProvider.value = provider
  creating.value = false
  modalError.value = ''
  modal.value = 'models'
}

function closeModal(): void {
  if (saving.value) return
  modal.value = null
  modalError.value = ''
}

async function saveProvider(payload: { providerId: number | null; body: LlmProviderUpsert }): Promise<void> {
  saving.value = true
  modalError.value = ''
  try {
    if (payload.providerId === null) {
      const created = await api.createLlmProvider(payload.body)
      providers.value = [...providers.value, created]
      note.value = `已创建提供商 ${created.code}`
    } else {
      const updated = await api.updateLlmProvider(payload.providerId, payload.body)
      providers.value = providers.value.map((item) => (item.id === updated.id ? updated : item))
      note.value = `已保存 ${updated.code} 的配置`
    }
    modal.value = null
  } catch (e) {
    modalError.value = `保存失败：${e instanceof Error ? e.message : String(e)}`
  } finally {
    saving.value = false
  }
}

async function toggleProvider(provider: LlmProviderView): Promise<void> {
  if (busyId.value !== null) return
  busyId.value = provider.id
  error.value = ''
  note.value = ''
  try {
    const updated = await api.updateLlmProvider(provider.id, {
      code: provider.code,
      baseUrl: provider.baseUrl,
      credentialRef: provider.credentialRef,
      protocol: provider.protocol,
      enabled: provider.enabled === 1 ? 0 : 1,
    })
    providers.value = providers.value.map((item) => (item.id === updated.id ? updated : item))
    note.value = `${updated.code} 已${updated.enabled === 1 ? '启用' : '禁用'}`
  } catch (e) {
    error.value = `切换状态失败：${e instanceof Error ? e.message : String(e)}`
  } finally {
    busyId.value = null
  }
}

onMounted(() => {
  void loadAll()
})

defineExpose({ reload: loadAll })
</script>

<template>
  <section class="provider-panel" aria-label="模型管理">
    <div class="provider-head">
      <div>
        <h3>云端模型</h3>
        <p>{{ providers.length }} 个提供商 · {{ models.length }} 个模型</p>
      </div>
      <div class="provider-head-actions">
        <button class="res-btn" type="button" @click="openCreate">+ 自定义提供商</button>
      </div>
    </div>

    <p v-if="error" class="res-alert" role="alert">{{ error }}</p>
    <p v-else-if="note" class="res-note" role="status">{{ note }}</p>

    <p v-if="loading && providers.length === 0" class="res-loading">正在读取模型提供商…</p>
    <div v-else class="provider-grid">
      <article v-for="provider in providers" :key="provider.id" class="provider-card">
        <header class="provider-card-head">
          <span class="provider-mark" aria-hidden="true">{{ provider.code.slice(0, 1).toUpperCase() }}</span>
          <div class="provider-title">
            <span class="provider-name">{{ provider.code }}</span>
            <span class="provider-code">{{ provider.protocol }} · #{{ provider.id }}</span>
          </div>
          <span class="res-chip" :class="provider.enabled === 1 ? 'res-chip-ok' : ''">
            {{ provider.enabled === 1 ? '已启用' : '已禁用' }}
          </span>
        </header>

        <p class="provider-count">
          可用 {{ enabledModelCount(provider) }} 个模型
          <span class="provider-count-total">/ 共 {{ modelsOf(provider).length }} 个</span>
        </p>

        <div class="provider-cred">
          <span class="provider-cred-label">凭证引用</span>
          <code class="provider-cred-value">{{ provider.credentialRef }}</code>
          <span class="provider-cred-note">密钥由环境变量提供，仅存服务端</span>
        </div>

        <div class="provider-meta">
          <span class="provider-meta-key">Base URL</span>
          <span class="res-mono" :title="provider.baseUrl">{{ provider.baseUrl }}</span>
        </div>

        <div class="provider-actions">
          <button class="res-btn res-btn-sm" type="button" @click="openModels(provider)">管理模型</button>
          <button class="res-btn res-btn-sm" type="button" @click="openConfig(provider)">配置</button>
          <button
            class="res-btn res-btn-sm"
            type="button"
            :disabled="busyId === provider.id"
            @click="toggleProvider(provider)"
          >{{ provider.enabled === 1 ? '禁用' : '启用' }}</button>
        </div>
      </article>
    </div>

    <ProviderConfigModal
      :open="modal === 'config'"
      :provider="activeProvider"
      :creating="creating"
      :saving="saving"
      :error="modalError"
      @close="closeModal"
      @save="saveProvider"
    />
    <ProviderModelsModal
      :open="modal === 'models'"
      :provider="activeProvider"
      :models="activeModels"
      @close="closeModal"
    />
  </section>
</template>
