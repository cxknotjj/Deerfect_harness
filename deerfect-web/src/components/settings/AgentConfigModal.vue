<script setup lang="ts">
/**
 * Agent 配置弹窗：名称只读（注册标识），可改描述、绑定路由、工具清单与三个开关。
 * 工具 chips 支持删除与回车追加；空清单回写 null，后端按内置分配兜底。
 */
import { computed, ref, watch } from 'vue'
import type { AdminAgentUpsert, AdminAgentView, LlmRouteView } from '../../api/admin'
import { joinTools, routeSummary, splitTools } from '../../utils/routeAgent'

const props = defineProps<{
  open: boolean
  agent: AdminAgentView | null
  routes: LlmRouteView[]
  saving: boolean
  error: string
}>()

const emit = defineEmits<{
  close: []
  save: [body: AdminAgentUpsert]
}>()

const name = ref('')
const description = ref('')
const routeId = ref(0)
const tools = ref<string[]>([])
const toolsExpanded = ref(false)
const toolInput = ref('')
const thinking = ref(0)
const status = ref(1)
const isInternal = ref(0)
const knowledge = ref<string | null>(null)
const localError = ref('')

const enabledRoutes = computed(() => props.routes.filter((route) => route.enabled === 1))
const visibleTools = computed(() => (toolsExpanded.value ? tools.value : tools.value.slice(0, 7)))
const hiddenToolCount = computed(() => Math.max(0, tools.value.length - 7))
const subtitle = computed(() =>
  props.agent ? `${props.agent.name} · ${props.agent.description || 'Agent 配置'}` : '',
)
const errorText = computed(() => localError.value || props.error)

watch(
  () => [props.open, props.agent] as const,
  ([open]) => {
    if (!open || !props.agent) return
    localError.value = ''
    name.value = props.agent.name
    description.value = props.agent.description ?? ''
    routeId.value = props.agent.routeId
    tools.value = splitTools(props.agent.tools)
    toolsExpanded.value = false
    toolInput.value = ''
    thinking.value = props.agent.thinking === 1 ? 1 : 0
    status.value = props.agent.status === 1 ? 1 : 0
    isInternal.value = props.agent.isInternal === 1 ? 1 : 0
    knowledge.value = props.agent.knowledge
  },
  { immediate: true },
)

function addTool(): void {
  const tool = toolInput.value.trim()
  if (tool === '') return
  if (!tools.value.includes(tool)) tools.value.push(tool)
  toolInput.value = ''
  toolsExpanded.value = true
}

function removeTool(tool: string): void {
  tools.value = tools.value.filter((item) => item !== tool)
}

function submit(): void {
  localError.value = ''
  if (routeId.value === 0) {
    localError.value = '请选择一条可用路由'
    return
  }
  emit('save', {
    description: description.value.trim() || null,
    routeId: routeId.value,
    tools: joinTools(tools.value),
    knowledge: knowledge.value,
    thinking: thinking.value,
    status: status.value,
    isInternal: isInternal.value,
  })
}
</script>

<template>
  <div v-if="open" class="ra-modal-mask" @click.self="emit('close')">
    <section class="ra-modal ra-modal-agent" role="dialog" aria-modal="true" aria-labelledby="agent-modal-title">
      <header class="ra-modal-head">
        <div>
          <h2 id="agent-modal-title">Agent 配置</h2>
          <p>{{ subtitle }}</p>
        </div>
        <button class="ra-modal-close" type="button" aria-label="关闭" @click="emit('close')">×</button>
      </header>

      <div class="ra-modal-body">
        <label class="ra-field">
          <span>Agent 名称</span>
          <input v-model="name" class="res-input ra-readonly" disabled />
          <small class="ra-field-hint">注册标识，会话与路由按它查找，不可修改</small>
        </label>

        <label class="ra-field">
          <span>描述</span>
          <input v-model="description" class="res-input" placeholder="这个 Agent 负责什么" />
        </label>

        <label class="ra-field">
          <span>路由</span>
          <select v-model.number="routeId" class="res-input">
            <option :value="0" disabled>选择路由</option>
            <option v-for="route in enabledRoutes" :key="route.id" :value="route.id">
              {{ route.code }} · {{ routeSummary(route.description) }}
            </option>
          </select>
        </label>

        <div class="ra-field">
          <span>工具</span>
          <div class="ra-tool-chips">
            <button
              v-for="tool in visibleTools"
              :key="tool"
              class="ra-tool-chip"
              type="button"
              :title="`移除 ${tool}`"
              @click="removeTool(tool)"
            >{{ tool }}<i>×</i></button>
            <button
              v-if="hiddenToolCount > 0 && !toolsExpanded"
              class="ra-tool-more"
              type="button"
              @click="toolsExpanded = true"
            >+{{ hiddenToolCount }}</button>
            <button
              v-else-if="toolsExpanded && tools.length > 7"
              class="ra-tool-more"
              type="button"
              @click="toolsExpanded = false"
            >收起</button>
            <span v-if="tools.length === 0" class="ra-tool-empty">未限制（回退内置分配）</span>
          </div>
          <div class="ra-tool-add">
            <input
              v-model="toolInput"
              class="res-input"
              placeholder="输入工具名，回车添加"
              @keydown.enter.prevent="addTool"
            />
            <button class="res-btn res-btn-sm" type="button" @click="addTool">添加</button>
          </div>
        </div>

        <div class="ra-switch-list">
          <div class="ra-switch-row">
            <div class="ra-switch-text">
              <strong>思考模式</strong>
              <span>仅是否向前端展示思考内容</span>
            </div>
            <button
              class="ra-toggle"
              :class="{ 'is-on': thinking === 1 }"
              type="button"
              role="switch"
              :aria-checked="thinking === 1"
              @click="thinking = thinking === 1 ? 0 : 1"
            ><i></i></button>
            <span class="ra-switch-state">{{ thinking === 1 ? '开启' : '关闭' }}</span>
          </div>
          <div class="ra-switch-row">
            <div class="ra-switch-text">
              <strong>内部 Agent</strong>
              <span>内部角色不进入对话 Agent 列表</span>
            </div>
            <button
              class="ra-toggle"
              :class="{ 'is-on': isInternal === 1 }"
              type="button"
              role="switch"
              :aria-checked="isInternal === 1"
              @click="isInternal = isInternal === 1 ? 0 : 1"
            ><i></i></button>
            <span class="ra-switch-state">{{ isInternal === 1 ? '是' : '否' }}</span>
          </div>
          <div class="ra-switch-row">
            <div class="ra-switch-text">
              <strong>状态</strong>
              <span>停用后不再接受新请求</span>
            </div>
            <button
              class="ra-toggle"
              :class="{ 'is-on': status === 1 }"
              type="button"
              role="switch"
              :aria-checked="status === 1"
              @click="status = status === 1 ? 0 : 1"
            ><i></i></button>
            <span class="ra-switch-state">{{ status === 1 ? '已启用' : '已停用' }}</span>
          </div>
        </div>

        <p v-if="errorText" class="res-alert" role="alert">{{ errorText }}</p>
      </div>

      <footer class="ra-modal-foot">
        <button class="res-btn" type="button" :disabled="saving" @click="emit('close')">取消</button>
        <button class="res-btn res-btn-primary" type="button" :disabled="saving" @click="submit">
          {{ saving ? '保存中…' : '保存更改' }}
        </button>
      </footer>
    </section>
  </div>
</template>
