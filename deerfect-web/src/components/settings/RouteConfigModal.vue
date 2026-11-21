<script setup lang="ts">
/**
 * 路由配置弹窗：别名（编辑时只读）、描述、策略、启停。
 * 新建模式额外给一个初始成员选择——后端要求路由至少一个成员，保存后可在「管理成员」继续扩充。
 */
import { computed, ref, watch } from 'vue'
import type { LlmModelView, LlmRouteUpsert, LlmRouteView } from '../../api/admin'
import { routeMark } from '../../utils/routeAgent'

const props = defineProps<{
  open: boolean
  route: LlmRouteView | null
  creating: boolean
  models: LlmModelView[]
  agentCount: number
  saving: boolean
  error: string
}>()

const emit = defineEmits<{
  close: []
  save: [payload: { routeId: number | null; body: LlmRouteUpsert }]
}>()

const code = ref('')
const description = ref('')
const strategy = ref('priority')
const enabled = ref(1)
const initialModelId = ref(0)
const localError = ref('')

const enabledModels = computed(() => props.models.filter((model) => model.enabled === 1))
const title = computed(() => (props.creating ? '新建路由' : '路由配置'))
const subtitle = computed(() =>
  props.creating
    ? '选择初始成员，保存后可继续在「管理成员」里扩充'
    : `${props.route?.code ?? ''} · ${props.route?.members.length ?? 0} 个成员 · ${props.agentCount} 个 Agent 绑定`,
)
const errorText = computed(() => localError.value || props.error)

watch(
  () => [props.open, props.route, props.creating] as const,
  ([open]) => {
    if (!open) return
    localError.value = ''
    code.value = props.route?.code ?? ''
    description.value = props.route?.description ?? ''
    strategy.value = props.route?.strategy || 'priority'
    enabled.value = props.route?.enabled ?? 1
    initialModelId.value = enabledModels.value[0]?.id ?? 0
  },
  { immediate: true },
)

function submit(): void {
  localError.value = ''
  const trimmedCode = code.value.trim()
  if (!trimmedCode) {
    localError.value = '路由别名不能为空'
    return
  }
  if (props.creating && initialModelId.value === 0) {
    localError.value = '新建路由需要一个初始成员'
    return
  }
  const members = props.route
    ? props.route.members.map((member) => ({
        modelId: member.modelId,
        priority: member.priority,
        enabled: member.enabled,
      }))
    : [{ modelId: initialModelId.value, priority: 10, enabled: 1 }]
  emit('save', {
    routeId: props.creating ? null : (props.route?.id ?? null),
    body: {
      code: trimmedCode,
      description: description.value.trim() || null,
      strategy: strategy.value,
      enabled: enabled.value,
      members,
    },
  })
}
</script>

<template>
  <div v-if="open" class="ra-modal-mask" @click.self="emit('close')">
    <section class="ra-modal ra-modal-route" role="dialog" aria-modal="true" aria-labelledby="route-modal-title">
      <header class="ra-modal-head">
        <div>
          <h2 id="route-modal-title">{{ title }}</h2>
          <p>{{ subtitle }}</p>
        </div>
        <button class="ra-modal-close" type="button" aria-label="关闭" @click="emit('close')">×</button>
      </header>

      <div class="ra-modal-body">
        <label class="ra-field">
          <span>路由别名</span>
          <input
            v-model="code"
            class="res-input"
            :class="{ 'ra-readonly': !creating }"
            :disabled="!creating"
            placeholder="唯一标识，如 backup"
          />
          <small v-if="!creating" class="ra-field-hint">别名是 Agent 的稳定绑定标识，不可修改</small>
        </label>

        <label class="ra-field">
          <span>描述</span>
          <textarea v-model="description" class="res-input ra-textarea" rows="2" placeholder="这条路由负责什么"></textarea>
        </label>

        <div class="ra-field-row">
          <label class="ra-field">
            <span>策略</span>
            <select v-model="strategy" class="res-input">
              <option value="priority">priority · 按优先级失败转移</option>
            </select>
          </label>
          <label v-if="creating" class="ra-field">
            <span>初始成员</span>
            <select v-model.number="initialModelId" class="res-input">
              <option v-for="model in enabledModels" :key="model.id" :value="model.id">
                {{ model.displayName || model.upstreamName }} · {{ model.providerCode }}
              </option>
            </select>
          </label>
        </div>

        <div class="ra-switch-row">
          <div class="ra-switch-text">
            <strong>状态</strong>
            <span>{{ enabled === 1 ? '启用后 Agent 可以绑定这条路由' : '停用后新请求不能再走这条路由' }}</span>
          </div>
          <button
            class="ra-toggle"
            :class="{ 'is-on': enabled === 1 }"
            type="button"
            role="switch"
            :aria-checked="enabled === 1"
            @click="enabled = enabled === 1 ? 0 : 1"
          ><i></i></button>
        </div>

        <div v-if="route" class="ra-modal-preview">
          <span class="ra-mark">{{ routeMark(route.code) }}</span>
          <span class="ra-preview-code">{{ route.code }}</span>
          <span class="res-chip" :class="route.enabled === 1 ? 'res-chip-ok' : ''">
            {{ route.enabled === 1 ? '已启用' : '已停用' }}
          </span>
          <span class="ra-preview-members">当前 {{ route.members.length }} 个成员</span>
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
