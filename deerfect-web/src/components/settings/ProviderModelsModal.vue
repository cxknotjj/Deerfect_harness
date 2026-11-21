<script setup lang="ts">
/**
 * 模型管理弹窗：展示某提供商下的模型注册表，只读。
 * 模型新增/停用仍由后端注册表维护，这里不伪造写能力。
 */
import { computed } from 'vue'
import type { LlmModelView, LlmProviderView } from '../../api/admin'
import { parseContextWindow } from '../../api/admin'

const props = defineProps<{
  open: boolean
  provider: LlmProviderView | null
  models: LlmModelView[]
}>()

const emit = defineEmits<{ close: [] }>()

const enabledCount = computed(() => props.models.filter((model) => model.enabled === 1).length)
const subtitle = computed(() =>
  props.provider ? `${props.provider.code} · ${enabledCount.value} 个可用 / 共 ${props.models.length} 个` : '',
)

function contextLabel(model: LlmModelView): string {
  const tokens = parseContextWindow(model.capabilities)
  if (!tokens) return '—'
  if (tokens >= 1024) return `${Math.round(tokens / 1024)}K`
  return String(tokens)
}
</script>

<template>
  <div v-if="open" class="ra-modal-mask" @click.self="emit('close')">
    <section class="ra-modal provider-models-modal" role="dialog" aria-modal="true" aria-labelledby="provider-models-title">
      <header class="ra-modal-head">
        <div>
          <h2 id="provider-models-title">模型管理</h2>
          <p>{{ subtitle }}</p>
        </div>
        <button class="ra-modal-close" type="button" aria-label="关闭" @click="emit('close')">×</button>
      </header>

      <div class="ra-modal-body">
        <div v-if="models.length" class="provider-model-table-wrap">
          <table class="provider-model-table">
            <thead>
              <tr>
                <th scope="col">模型</th>
                <th scope="col">上游标识</th>
                <th scope="col">上下文</th>
                <th scope="col">状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="model in models" :key="model.id">
                <td>
                  <span class="provider-model-name">{{ model.displayName }}</span>
                </td>
                <td class="provider-model-upstream">{{ model.upstreamName }}</td>
                <td class="provider-model-context">{{ contextLabel(model) }}</td>
                <td>
                  <span class="res-chip" :class="model.enabled === 1 ? 'res-chip-ok' : ''">
                    {{ model.enabled === 1 ? '已启用' : '已停用' }}
                  </span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-else class="provider-empty">该提供商下还没有模型</p>
      </div>

      <footer class="ra-modal-foot">
        <button class="res-btn" type="button" @click="emit('close')">关闭</button>
      </footer>
    </section>
  </div>
</template>
