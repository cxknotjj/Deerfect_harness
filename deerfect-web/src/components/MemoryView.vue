<script setup lang="ts">
/**
 * 记忆：显式记忆的查看 / 补录 / 软删（GET|POST|DELETE /api/memories）。
 * 对话里说「记住…」写入的是 source=user，这里补录的是 source=manual。
 */
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'
import type { MemoryItem } from '../api'
import { fmtDateTime } from '../utils/resourceFormat'
import PageHeader from './resource/PageHeader.vue'
import EmptyState from './resource/EmptyState.vue'

const emit = defineEmits<{ changed: [] }>()

const items = ref<MemoryItem[]>([])
const loading = ref(false)
const error = ref('')
const note = ref('')
const content = ref('')
const key = ref('')
const busy = ref(false)

function errText(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    items.value = await api.listMemories('active', 100)
  } catch (e) {
    error.value = `记忆加载失败：${errText(e)}`
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
})

const stats = computed(() => [
  { label: '生效记忆', value: String(items.value.length) },
  { label: '带标签', value: String(items.value.filter((m) => m.key).length) },
  { label: '手动补录', value: String(items.value.filter((m) => m.source === 'manual').length) },
])

const SOURCE_LABEL: Record<string, string> = {
  user: '对话中明示',
  manual: '手动补录',
  cli: '命令行',
}

async function add(): Promise<void> {
  const text = content.value.trim()
  if (!text || busy.value) return
  busy.value = true
  error.value = ''
  note.value = ''
  try {
    await api.createMemory(text, key.value)
    content.value = ''
    key.value = ''
    note.value = '已记住（同 key 会覆盖旧记忆）'
    await load()
    emit('changed')
  } catch (e) {
    error.value = `写入失败：${errText(e)}`
  } finally {
    busy.value = false
  }
}

async function remove(id: number): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = ''
  note.value = ''
  try {
    await api.deleteMemory(id)
    await load()
    emit('changed')
  } catch (e) {
    error.value = `删除失败：${errText(e)}`
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="res-page" aria-label="记忆">
    <div class="res-inner">
      <PageHeader eyebrow="长期上下文" title="记忆" sub="会被注入提示词的显式记忆；仅展示生效中的条目" />

      <p v-if="error" class="res-alert" role="alert">{{ error }}</p>
      <p v-else-if="note" class="res-note" role="status">{{ note }}</p>

      <div class="res-stats">
        <div v-for="stat in stats" :key="stat.label" class="res-stat">
          <span class="res-stat-label">{{ stat.label }}</span>
          <span class="res-stat-value">{{ stat.value }}</span>
        </div>
      </div>

      <section class="res-panel" aria-label="新增记忆">
        <div class="res-panel-head">
          <div>
            <h2 class="res-panel-title">新增记忆</h2>
            <p class="res-panel-sub">同 key 会覆盖旧记忆；留空 key 表示一次性事实</p>
          </div>
        </div>
        <form class="res-form" @submit.prevent="add">
          <textarea
            v-model="content"
            class="res-textarea"
            rows="2"
            maxlength="1000"
            placeholder="例如：这个仓库的后端测试用 MySQL 空库跑全量 Flyway 迁移"
            aria-label="记忆内容"
          ></textarea>
          <div class="res-form-row">
            <input
              v-model="key"
              class="res-input res-input-key"
              type="text"
              maxlength="64"
              placeholder="标签（可选）"
              aria-label="记忆标签"
            />
            <div class="res-form-foot">
              <span class="res-form-hint">在对话里说「记住…」也会写入这里</span>
              <button class="res-btn res-btn-primary" type="submit" :disabled="busy || !content.trim()">
                记住
              </button>
            </div>
          </div>
        </form>
      </section>

      <section class="res-panel" aria-label="生效中的记忆">
        <div class="res-panel-head">
          <div>
            <h2 class="res-panel-title">生效中的记忆</h2>
            <p class="res-panel-sub">删除为软删，保留审计但不再注入上下文</p>
          </div>
        </div>

        <p v-if="loading && items.length === 0" class="res-loading">正在读取记忆…</p>
        <EmptyState
          v-else-if="items.length === 0"
          icon="sparkle"
          title="还没有记住任何事"
          desc="可以在上方补录一条，或在对话里说「记住…」，下次对话会自动带入这些事实。"
        />
        <ul v-else class="res-list">
          <li v-for="item in items" :key="item.id" class="res-row">
            <span class="res-mark" aria-hidden="true">
              <svg viewBox="0 0 24 24">
                <path d="M12 3.5 14 9l5.5 2-5.5 2-2 5.5-2-5.5L4.5 11 10 9z" />
              </svg>
            </span>
            <div class="res-row-main">
              <div class="res-row-text">{{ item.content }}</div>
              <div class="res-row-sub">
                <span v-if="item.key" class="res-chip res-chip-accent">{{ item.key }}</span>
                <span class="res-chip">{{ SOURCE_LABEL[item.source] ?? item.source }}</span>
                <span>{{ fmtDateTime(item.createdAt) }}</span>
                <span v-if="item.expiresAt">到期 {{ fmtDateTime(item.expiresAt) }}</span>
              </div>
            </div>
            <button
              class="res-row-del"
              type="button"
              title="忘掉这条记忆"
              :aria-label="`删除记忆 ${item.id}`"
              :disabled="busy"
              @click="remove(item.id)"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M4 7h16M9 7V4h6v3M6 7l1 14h10l1-14M10 11v6M14 11v6" />
              </svg>
            </button>
          </li>
        </ul>
      </section>
    </div>
  </section>
</template>
