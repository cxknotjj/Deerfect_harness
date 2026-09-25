/**
 * 左栏会话列表(DeepSeek 桌面端复刻):brand 行(鹿 logo + deepseek + HARNESS 描边徽章)→
 * 左对齐「+ 新会话」→ 工作区标签行(右侧装饰图标,不接行为)→
 * 会话项(单行:名称 + 右侧灰色相对时间)→ 底部用户区(头像 + 我的工作区)。
 * 相对时间用服务端 lastActiveAt(真实最近活跃时刻);缺失时回退 utils/sessionMtime 首见时间。
 */
<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { relativeTime } from '../utils/relativeTime'
import { touch } from '../utils/sessionMtime'

const props = defineProps<{
  sessions: { id: string; name: string; lastQuestion: string | null; lastActiveAt?: number | null }[]
  currentId: string
  hasMore: boolean
  loading: boolean
}>()

const emit = defineEmits<{
  select: [id: string]
  create: []
  more: []
  collapse: []
  settings: []
  remove: [id: string]
}>()

/** 删除会话:阻断冒泡避免触发选中;确认后才 emit(由外层调 API 并联动清理) */
function onRemove(id: string, e: Event): void {
  e.stopPropagation()
  if (window.confirm('确定删除该会话？其服务端记录与本地缓存将一并清除。')) {
    emit('remove', id)
  }
}

/** 时间 tick:驱动相对时间文案随流逝刷新(「刚刚」→「N分钟」…) */
const nowTick = ref(Date.now())
let tickTimer: number | undefined
onMounted(() => {
  tickTimer = window.setInterval(() => {
    nowTick.value = Date.now()
  }, 30_000)
})
onUnmounted(() => window.clearInterval(tickTimer))

/** 会话 id → 相对时间文案;真实最近活跃时刻优先,缺失回退首见时间;tick 驱动重算 */
const timeLabels = computed<Record<string, string>>(() => {
  const labels: Record<string, string> = {}
  for (const s of props.sessions) {
    labels[s.id] = relativeTime(s.lastActiveAt ?? touch(s.id), nowTick.value)
  }
  return labels
})
</script>

<template>
  <aside class="session-list">
    <div class="session-list-head">
      <div class="session-brand">
        <img class="session-brand-logo" src="/deer_logo.png" alt="" />
        <span class="session-brand-name">DeerFect</span>
        <span class="brand-badge">HARNESS</span>
      </div>
      <!-- 收起侧栏(展开入口在顶栏左侧) -->
      <button class="icon-btn" type="button" title="收起侧栏" @click="emit('collapse')">☰</button>
    </div>

    <div class="session-new">
      <button class="btn-new" type="button" @click="emit('create')">
        <span aria-hidden="true">+</span><span>新会话</span>
      </button>
    </div>

    <!-- 工作区标签行:右侧三个装饰小图标(搜索/筛选/新建文件夹语义),不接行为 -->
    <div class="workspace-row">
      <span>工作区</span>
      <span class="workspace-icons" aria-hidden="true">
        <span class="workspace-icon" title="搜索">
          <svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="7" /><path d="m20 20-3.5-3.5" /></svg>
        </span>
        <span class="workspace-icon" title="筛选">
          <svg viewBox="0 0 24 24"><path d="M4 5h16l-6.5 7.5V19l-3 2v-8.5Z" /></svg>
        </span>
        <span class="workspace-icon" title="新建文件夹">
          <svg viewBox="0 0 24 24"><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z" /><path d="M12 11v6" /><path d="M9 14h6" /></svg>
        </span>
      </span>
    </div>

    <ul class="session-items">
      <li
        v-for="s in sessions"
        :key="s.id"
        class="session-item"
        :class="{ 'session-item-active': s.id === currentId }"
        @click="emit('select', s.id)"
      >
        <span class="session-item-name">{{ s.name }}</span>
        <span class="session-item-time">{{ timeLabels[s.id] }}前</span>
        <!-- 删除入口:hover 显现;确认后交外层执行 -->
        <button
          class="session-item-del"
          type="button"
          title="删除会话"
          @click="onRemove(s.id, $event)"
        >✕</button>
      </li>
    </ul>

    <!-- 有更多才显示「加载更多」;一条会话都没有时才提示 -->
    <div v-if="hasMore" class="session-list-foot">
      <button class="btn btn-ghost" :disabled="loading" @click="emit('more')">
        {{ loading ? '加载中…' : '加载更多' }}
      </button>
    </div>
    <div v-else-if="sessions.length === 0" class="session-list-foot session-list-foot-tip">
      没有更多会话了
    </div>

    <!-- 底部:用户区;点击沿用原 settings 事件(主题切换在顶栏右上角) -->
    <button class="user-row" type="button" title="我的工作区" @click="emit('settings')">
      <img class="user-avatar" src="/deer_logo.png" alt="" />
      <span>我的工作区</span>
    </button>
  </aside>
</template>
