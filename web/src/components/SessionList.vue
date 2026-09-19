/**
 * 左栏会话列表:顶部「+ 新建会话」,会话项(名称 + 最近提问摘要),选中高亮,分页「加载更多」。
 * 类型说明:SessionView 无更新时间字段,副标题按类型实际字段取 lastQuestion(首条提问前为 null)。
 */
<script setup lang="ts">
defineProps<{
  sessions: { id: string; name: string; lastQuestion: string | null }[]
  currentId: string
  hasMore: boolean
  loading: boolean
}>()

const emit = defineEmits<{ select: [id: string]; create: []; more: [] }>()
</script>

<template>
  <aside class="session-list">
    <div class="session-list-head">
      <span class="session-list-title">会话</span>
      <button class="btn btn-primary" @click="emit('create')">+ 新建会话</button>
    </div>

    <ul class="session-items">
      <li
        v-for="s in sessions"
        :key="s.id"
        class="session-item"
        :class="{ 'session-item-active': s.id === currentId }"
        @click="emit('select', s.id)"
      >
        <div class="session-item-name">{{ s.name }}</div>
        <div class="session-item-meta">{{ s.lastQuestion ?? '暂无对话' }}</div>
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
  </aside>
</template>
