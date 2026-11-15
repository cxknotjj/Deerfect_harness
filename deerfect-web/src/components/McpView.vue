<script setup lang="ts">
/**
 * MCP：能力清单里的外部 MCP server（GET /api/capabilities）+ 配置重载。
 * source 形如 mcp:http / mcp:stdio；state 由后端握手结果给出（CONNECTED / FAILED）。
 */
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'
import type { CapabilityEntry } from '../api'
import PageHeader from './resource/PageHeader.vue'
import EmptyState from './resource/EmptyState.vue'

const emit = defineEmits<{ changed: [] }>()

const entries = ref<CapabilityEntry[]>([])
const problems = ref<string[]>([])
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const note = ref('')

function errText(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const report = await api.capabilities()
    entries.value = report.entries.filter((entry) => entry.source.startsWith('mcp'))
    problems.value = report.problems
  } catch (e) {
    error.value = `能力清单读取失败：${errText(e)}`
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
})

const toolCount = computed(() =>
  new Set(entries.value.flatMap((entry) => entry.tools)).size,
)

const failedCount = computed(() =>
  entries.value.filter((entry) => isFailed(entry.state)).length,
)

const stats = computed(() => [
  { label: 'MCP Server', value: String(entries.value.length) },
  { label: '已发现工具', value: String(toolCount.value) },
  { label: '异常', value: String(failedCount.value + problems.value.length) },
])

const transports = computed(() => [...new Set(entries.value.map((e) => e.source))].join('、') || '—')

function isFailed(state: string): boolean {
  const value = state.toUpperCase()
  return value.includes('FAILED') || value.includes('UNAVAILABLE') || value.includes('MISSING')
}

function stateKind(state: string): 'ok' | 'warn' | 'plain' {
  if (isFailed(state)) return 'warn'
  const value = state.toUpperCase()
  if (value.includes('READY') || value.includes('CONNECTED')) return 'ok'
  return 'plain'
}

async function reload(): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = ''
  note.value = ''
  try {
    const result = await api.reloadCapabilities()
    note.value = `已重载：HTTP 工具 ${result.tools} 个 · MCP server ${result.mcp.servers} 个（重载前 ${result.mcp.previousServers} 个）`
    await load()
    emit('changed')
  } catch (e) {
    error.value = `重载失败：${errText(e)}`
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="res-page" aria-label="MCP">
    <div class="res-inner">
      <PageHeader eyebrow="外部连接" title="MCP" sub="MCP server 的连接状态、发现到的工具与配置重载">
        <template #actions>
          <button class="res-btn" type="button" :disabled="busy" @click="reload">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M20 11a8 8 0 0 0-14-5L4 8" />
              <path d="M4 4v4h4" />
              <path d="M4 13a8 8 0 0 0 14 5l2-2" />
              <path d="M20 20v-4h-4" />
            </svg>
            {{ busy ? '重载中…' : '重载配置' }}
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

      <section class="res-panel" aria-label="MCP Server">
        <div class="res-panel-head">
          <div>
            <h2 class="res-panel-title">MCP Server</h2>
            <p class="res-panel-sub">配置来自项目根目录 mcp-config.json；重载会宽限关闭旧连接</p>
          </div>
        </div>

        <p v-if="loading && entries.length === 0" class="res-loading">正在读取能力清单…</p>
        <EmptyState
          v-else-if="entries.length === 0"
          icon="plug"
          title="还没有配置 MCP Server"
          desc="在项目根目录 mcp-config.json 的 mcpServers 下添加 server 后，点击「重载配置」即可连接并发现工具。"
        />
        <div v-else class="mcp-grid">
          <article v-for="entry in entries" :key="`${entry.source}-${entry.pack}`" class="mcp-card">
            <div class="mcp-card-head">
              <span class="mcp-name">{{ entry.pack }}</span>
              <span
                class="res-chip"
                :class="{
                  'res-chip-ok': stateKind(entry.state) === 'ok',
                  'res-chip-warn': stateKind(entry.state) === 'warn',
                }"
              >{{ entry.state }}</span>
            </div>
            <div class="mcp-location">{{ entry.location }}</div>
            <div class="mcp-tools">
              <span v-for="tool in entry.tools" :key="tool" class="res-chip res-chip-accent">{{ tool }}</span>
              <span v-if="entry.tools.length === 0" class="res-chip">未发现工具</span>
            </div>
          </article>
        </div>
        <p v-if="problems.length" class="mcp-foot">配置问题：{{ problems.join('；') }}</p>
        <p v-else class="mcp-foot">传输类型：{{ transports }}</p>
      </section>
    </div>
  </section>
</template>
