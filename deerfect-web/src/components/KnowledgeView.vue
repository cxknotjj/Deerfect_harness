<script setup lang="ts">
/**
 * 知识库：RAG 文档台账（GET /api/knowledge/documents）+ 增量摄取 + 删除。
 * 只做管理动作，不伪造命中数据；检索调试仍在 CLI/接口侧。
 */
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'
import type { KbDocument } from '../api'
import { fmtDateTime } from '../utils/resourceFormat'
import PageHeader from './resource/PageHeader.vue'
import EmptyState from './resource/EmptyState.vue'

const emit = defineEmits<{ changed: [] }>()

const docs = ref<KbDocument[]>([])
const loading = ref(false)
const error = ref('')
const note = ref('')
const query = ref('')
/** 当前进行中的写操作：'sync' 或文档 docName；防止重复提交 */
const busy = ref('')

function errText(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const page = await api.listKbDocuments(1, 200)
    docs.value = page.list
  } catch (e) {
    error.value = `知识库台账加载失败：${errText(e)}（若未启用 app.knowledge.enabled，后端返回 503）`
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
})

const stats = computed(() => [
  { label: '文档', value: String(docs.value.length) },
  { label: '向量分块', value: String(docs.value.reduce((sum, d) => sum + (d.chunkCount ?? 0), 0)) },
  { label: '知识库', value: String(new Set(docs.value.map((d) => d.kb)).size) },
])

const filtered = computed(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return docs.value
  return docs.value.filter((d) =>
    [d.title, d.docName, d.kb].some((field) => field?.toLowerCase().includes(q)),
  )
})

async function sync(): Promise<void> {
  if (busy.value) return
  busy.value = 'sync'
  error.value = ''
  note.value = ''
  try {
    const result = await api.syncKnowledge()
    note.value = `摄取完成：扫描 ${result.scanned} · 更新 ${result.updated} · 跳过 ${result.skipped} · 新增分块 ${result.chunks}`
    await load()
    emit('changed')
  } catch (e) {
    error.value = `同步失败：${errText(e)}`
  } finally {
    busy.value = ''
  }
}

async function remove(doc: KbDocument): Promise<void> {
  if (busy.value || !window.confirm(`删除「${doc.title}」？向量分块与台账行会一并删除。`)) return
  busy.value = doc.docName
  error.value = ''
  note.value = ''
  try {
    await api.deleteKbDocument(doc.docName)
    await load()
    emit('changed')
  } catch (e) {
    error.value = `删除失败：${errText(e)}`
  } finally {
    busy.value = ''
  }
}
</script>

<template>
  <section class="res-page" aria-label="知识库">
    <div class="res-inner">
      <PageHeader eyebrow="知识面" title="知识库" sub="RAG 文档台账与增量摄取">
        <template #actions>
          <button class="res-btn" type="button" :disabled="busy === 'sync'" @click="sync">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M20 11a8 8 0 0 0-14-5L4 8" />
              <path d="M4 4v4h4" />
              <path d="M4 13a8 8 0 0 0 14 5l2-2" />
              <path d="M20 20v-4h-4" />
            </svg>
            {{ busy === 'sync' ? '摄取中…' : '同步知识库' }}
          </button>
        </template>
      </PageHeader>

      <p v-if="error" class="res-alert" role="alert">{{ error }}</p>
      <p v-else-if="note" class="res-note" role="status">{{ note }}</p>

      <div class="res-stats">
        <div v-for="stat in stats" :key="stat.label" class="res-stat">
          <span class="res-stat-label">{{ stat.label }}</span>
          <span class="res-stat-value">{{ stat.value }}</span>
        </div>
      </div>

      <section class="res-panel" aria-label="文档台账">
        <div class="res-panel-head">
          <div>
            <h2 class="res-panel-title">文档台账</h2>
            <p class="res-panel-sub">摄取状态、分块数与所属知识库；删除会同步清理向量分块</p>
          </div>
          <label class="res-search">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <circle cx="11" cy="11" r="7" />
              <path d="m20 20-3.5-3.5" />
            </svg>
            <input v-model="query" type="search" placeholder="搜索标题 / 路径 / 知识库" aria-label="搜索文档" />
          </label>
        </div>

        <p v-if="loading && docs.length === 0" class="res-loading">正在读取台账…</p>
        <EmptyState
          v-else-if="filtered.length === 0"
          icon="book"
          :title="query.trim() ? '没有匹配的文档' : '知识库里还没有文档'"
          :desc="query.trim()
            ? '换个关键词试试，或清空搜索框查看全部台账。'
            : '把 .md / .txt 放进 knowledge/ 目录后点击「同步知识库」即可完成摄取；未启用知识库时后端会返回 503。'"
        />
        <ul v-else class="res-list">
          <li v-for="doc in filtered" :key="doc.docName" class="res-row">
            <span class="res-mark" aria-hidden="true">
              <svg viewBox="0 0 24 24">
                <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
                <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" />
              </svg>
            </span>
            <div class="res-row-main">
              <div class="res-row-title">{{ doc.title }}</div>
              <div class="res-row-meta">
                <span class="res-mono">{{ doc.docName }}</span>
              </div>
            </div>
            <span class="res-chip res-kb-chip">{{ doc.kb }}</span>
            <span v-if="doc.status === 0" class="res-chip res-chip-warn">摄取失败</span>
            <span class="res-row-col">{{ doc.chunkCount }} 分块</span>
            <span class="res-row-col res-row-time">{{ fmtDateTime(doc.updatedAt ?? doc.createdAt) }}</span>
            <button
              class="res-row-del"
              type="button"
              :title="`删除 ${doc.docName}`"
              :aria-label="`删除 ${doc.title}`"
              :disabled="busy === doc.docName"
              @click="remove(doc)"
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
