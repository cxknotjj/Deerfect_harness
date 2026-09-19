/**
 * 左栏会话列表(DeepSeek 形态):brand 行(小鹿 logo + Harness)→ 通栏「新会话」→
 * 「会话」分区标题 → 会话项(名称 + 最近提问摘要,圆角块选中态)→ 底部主题切换行。
 * 类型说明:SessionView 无更新时间字段,副标题按类型实际字段取 lastQuestion。
 */
<script setup lang="ts">
defineProps<{
  sessions: { id: string; name: string; lastQuestion: string | null }[]
  currentId: string
  hasMore: boolean
  loading: boolean
  theme: 'dark' | 'light'
}>()

const emit = defineEmits<{ select: [id: string]; create: []; more: []; toggleTheme: [] }>()
</script>

<template>
  <aside class="session-list">
    <div class="session-list-head">
      <div class="session-brand">
        <img class="session-brand-logo" src="/deer_logo.png" alt="" />
        <span class="session-brand-name">Harness</span>
      </div>
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

    <!-- 有更多才显示「加载更多」;无更多且有内容时给一行淡提示 -->
    <div v-if="hasMore" class="session-list-foot">
      <button class="btn btn-ghost" :disabled="loading" @click="emit('more')">
        {{ loading ? '加载中…' : '加载更多' }}
      </button>
    </div>
    <div v-else-if="sessions.length > 0" class="session-list-foot session-list-foot-tip">
      没有更多会话了
    </div>

    <!-- 主题切换(对齐截图左栏底部「设置」位) -->
    <button
      class="theme-row"
      type="button"
      :title="theme === 'dark' ? '切换日间模式' : '切换夜间模式'"
      @click="emit('toggleTheme')"
    >
      <span class="theme-row-icon">{{ theme === 'dark' ? '☀' : '☾' }}</span>
      <span>{{ theme === 'dark' ? '日间模式' : '夜间模式' }}</span>
    </button>
  </aside>
</template>
