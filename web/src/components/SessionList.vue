/**
 * 左栏会话列表(DeepSeek 形态):brand 行(logo + Harness + 折叠钮)→ 通栏「新会话」→
 * 「会话」分区标题 → 会话项(名称 + 最近提问摘要,圆角块选中态)→ 底部设置行。
 * 类型说明:SessionView 无更新时间字段,副标题按类型实际字段取 lastQuestion。
 */
<script setup lang="ts">
defineProps<{
  sessions: { id: string; name: string; lastQuestion: string | null }[]
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
}>()
</script>

<template>
  <aside class="session-list">
    <div class="session-list-head">
      <div class="session-brand">
        <img class="session-brand-logo" src="/deer_logo.png" alt="" />
        <span class="session-brand-name">Harness</span>
      </div>
      <!-- 收起侧栏(展开入口在顶栏左侧) -->
      <button class="icon-btn" type="button" title="收起侧栏" @click="emit('collapse')">☰</button>
    </div>

    <div class="session-new">
      <button class="btn-new" type="button" @click="emit('create')">+ 新会话</button>
    </div>

    <div class="session-section">会话</div>

    <ul class="session-items">
      <li
        v-for="s in sessions"
        :key="s.id"
        class="session-item"
        :class="{ 'session-item-active': s.id === currentId }"
        @click="emit('select', s.id)"
      >
        <span class="status-dot" aria-hidden="true"></span>
        <div class="session-item-body">
          <div class="session-item-name">{{ s.name }}</div>
          <div class="session-item-meta">{{ s.lastQuestion ?? '暂无对话' }}</div>
        </div>
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

    <!-- 底部:设置(预留);主题切换在顶栏右上角 -->
    <button class="settings-row" type="button" title="设置" @click="emit('settings')">
      <span class="row-icon">⚙</span>
      <span>设置</span>
    </button>
  </aside>
</template>
