<script setup lang="ts">
/**
 * 管理成员弹窗：成员按 priority 排序展示，可上移/下移/移除；
 * 候选只列已启用且尚未加入的模型，加入时按当前最大优先级 +10。
 */
import { computed, ref, watch } from 'vue'
import type { LlmModelView, LlmRouteMemberUpsert, LlmRouteView } from '../../api/admin'

interface MemberDraft extends LlmRouteMemberUpsert {
  upstreamName: string
  providerCode: string
}

const props = defineProps<{
  open: boolean
  route: LlmRouteView | null
  models: LlmModelView[]
  saving: boolean
  error: string
}>()

const emit = defineEmits<{
  close: []
  save: [members: LlmRouteMemberUpsert[]]
}>()

const members = ref<MemberDraft[]>([])
const localError = ref('')

const candidates = computed(() => {
  const joined = new Set(members.value.map((member) => member.modelId))
  return props.models.filter((model) => model.enabled === 1 && !joined.has(model.id))
})
const errorText = computed(() => localError.value || props.error)
const subtitle = computed(() =>
  props.route ? `${props.route.code} · ${members.value.length} 个成员` : '',
)

watch(
  () => [props.open, props.route] as const,
  ([open]) => {
    if (!open || !props.route) return
    localError.value = ''
    members.value = props.route.members
      .map((member) => ({
        modelId: member.modelId,
        priority: member.priority,
        enabled: member.enabled,
        upstreamName: member.upstreamName,
        providerCode: member.providerCode,
      }))
      .sort((a, b) => a.priority - b.priority)
  },
  { immediate: true },
)

function addMember(model: LlmModelView): void {
  const maxPriority = members.value.reduce((max, member) => Math.max(max, member.priority), 0)
  members.value.push({
    modelId: model.id,
    priority: maxPriority + 10,
    enabled: 1,
    upstreamName: model.upstreamName,
    providerCode: model.providerCode,
  })
}

function removeMember(index: number): void {
  members.value.splice(index, 1)
}

/** 交换相邻两行的顺序与优先级，保证同一路由内 priority 仍唯一 */
function move(index: number, delta: -1 | 1): void {
  const target = index + delta
  if (target < 0 || target >= members.value.length) return
  const current = members.value[index]
  const other = members.value[target]
  const priority = current.priority
  current.priority = other.priority
  other.priority = priority
  members.value.splice(index, 1, other)
  members.value.splice(target, 1, current)
}

function submit(): void {
  localError.value = ''
  if (members.value.length === 0) {
    localError.value = '路由至少需要一个成员'
    return
  }
  emit('save', members.value.map((member) => ({
    modelId: member.modelId,
    priority: member.priority,
    enabled: member.enabled,
  })))
}
</script>

<template>
  <div v-if="open" class="ra-modal-mask" @click.self="emit('close')">
    <section class="ra-modal ra-modal-members" role="dialog" aria-modal="true" aria-labelledby="members-modal-title">
      <header class="ra-modal-head">
        <div>
          <h2 id="members-modal-title">管理成员</h2>
          <p>{{ subtitle }}</p>
        </div>
        <button class="ra-modal-close" type="button" aria-label="关闭" @click="emit('close')">×</button>
      </header>

      <div class="ra-modal-body">
        <div class="ra-table-scroll">
          <table class="ra-member-table">
          <thead>
            <tr>
              <th>优先级</th>
              <th>模型</th>
              <th>供应商</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(member, index) in members" :key="member.modelId">
              <td><span class="ra-priority">P{{ member.priority }}</span></td>
              <td class="ra-member-model">{{ member.upstreamName }}</td>
              <td class="ra-member-provider">{{ member.providerCode }}</td>
              <td>
                <span class="res-chip" :class="member.enabled === 1 ? 'res-chip-ok' : ''">
                  {{ member.enabled === 1 ? '已启用' : '已停用' }}
                </span>
              </td>
              <td>
                <div class="ra-member-actions">
                  <button type="button" :disabled="index === 0" @click="move(index, -1)">上移</button>
                  <button type="button" :disabled="index === members.length - 1" @click="move(index, 1)">下移</button>
                  <button type="button" class="is-danger" @click="removeMember(index)">移除</button>
                </div>
              </td>
            </tr>
            <tr v-if="members.length === 0">
              <td colspan="5" class="ra-empty-cell">还没有成员，先从下方添加一个</td>
            </tr>
          </tbody>
        </table>
        </div>

        <div class="ra-add-head">
          <h3>添加成员</h3>
          <span>来自已启用模型</span>
        </div>
        <div class="ra-candidates">
          <div v-for="model in candidates" :key="model.id" class="ra-candidate">
            <div>
              <span class="ra-candidate-name">{{ model.displayName || model.upstreamName }}</span>
              <span class="ra-candidate-provider">{{ model.providerCode }}</span>
            </div>
            <button class="res-btn res-btn-sm" type="button" @click="addMember(model)">+ 加入</button>
          </div>
          <p v-if="candidates.length === 0" class="ra-empty-cell">没有可加入的已启用模型</p>
        </div>

        <p v-if="errorText" class="res-alert" role="alert">{{ errorText }}</p>
      </div>

      <footer class="ra-modal-foot">
        <button class="res-btn" type="button" :disabled="saving" @click="emit('close')">取消</button>
        <button class="res-btn res-btn-primary" type="button" :disabled="saving" @click="submit">
          {{ saving ? '保存中…' : '保存成员' }}
        </button>
      </footer>
    </section>
  </div>
</template>
