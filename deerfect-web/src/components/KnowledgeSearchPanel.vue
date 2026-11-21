<script setup lang="ts">
/**
 * 知识库检索调试：直接调用 GET /api/knowledge/search?full=true，观察命中 chunk、
 * 相关度分数与来源文档；topK / 向量阈值 / 融合下限按本次请求覆盖，不写回配置。
 * 参数默认值来自 GET /api/knowledge/search-config，页面不复制一份配置数字。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import type { KbDocument, KnowledgeSearchConfig, KnowledgeSearchHit } from '../api'

const props = defineProps<{ docs: KbDocument[] }>()

const config = ref<KnowledgeSearchConfig | null>(null)
const configError = ref('')
const configReady = ref(false)

const query = ref('')
const selectedKbs = ref<string[]>([])
const topK = ref(0)
const minScore = ref(0)
const fusionMinScore = ref(0)

const hits = ref<KnowledgeSearchHit[]>([])
const loading = ref(false)
const error = ref('')
const searched = ref(false)
const searchedQuery = ref('')
const elapsedMs = ref<number | null>(null)
const expanded = ref<Record<number, boolean>>({})
const lastParams = ref<{ topK: number; minScore: number; fusionMinScore: number } | null>(null)

const availableKbs = computed(() => [...new Set(props.docs.map((doc) => doc.kb))].sort())

const modeLabel = computed(() => (config.value?.hybridEnabled ? '混合检索' : '纯向量'))

const scoreLabel = computed(() =>
  config.value?.hybridEnabled ? '相关度 · RRF 融合分' : '相关度 · 向量相似度')

const canSearch = computed(() =>
  configReady.value && query.value.trim().length > 0 && !loading.value)

function errText(e: unknown): string {
  const raw = e instanceof Error ? e.message : String(e)
  const body = raw.replace(/^HTTP \d+:\s*/, '')
  try {
    const parsed = JSON.parse(body) as { message?: string }
    return parsed.message || raw
  } catch {
    return raw
  }
}

async function loadConfig(): Promise<void> {
  configError.value = ''
  configReady.value = false
  try {
    const cfg = await api.knowledgeSearchConfig()
    config.value = cfg
    topK.value = cfg.topK
    minScore.value = cfg.minScore
    fusionMinScore.value = cfg.fusionMinScore
    configReady.value = true
  } catch (e) {
    configError.value = `检索配置读取失败：${errText(e)}`
  }
}

onMounted(() => {
  void loadConfig()
})

watch(availableKbs, (kbs) => {
  selectedKbs.value = selectedKbs.value.filter((kb) => kbs.includes(kb))
})

function toggleKb(kb: string): void {
  selectedKbs.value = selectedKbs.value.includes(kb)
    ? selectedKbs.value.filter((item) => item !== kb)
    : [...selectedKbs.value, kb]
}

async function runSearch(): Promise<void> {
  if (!canSearch.value) return
  loading.value = true
  error.value = ''
  searched.value = true
  searchedQuery.value = query.value.trim()
  lastParams.value = {
    topK: Number(topK.value),
    minScore: Number(minScore.value),
    fusionMinScore: Number(fusionMinScore.value),
  }
  const started = performance.now()
  try {
    hits.value = await api.searchKnowledge(
      searchedQuery.value,
      selectedKbs.value,
      lastParams.value.topK,
      lastParams.value.minScore,
      lastParams.value.fusionMinScore,
    )
    elapsedMs.value = Math.round(performance.now() - started)
    expanded.value = {}
  } catch (e) {
    hits.value = []
    elapsedMs.value = null
    error.value = `检索失败：${errText(e)}`
  } finally {
    loading.value = false
  }
}

function toggleText(index: number): void {
  expanded.value = { ...expanded.value, [index]: !expanded.value[index] }
}

function scoreWidth(score: number): string {
  return `${Math.max(0, Math.min(100, Math.round(score * 100)))}%`
}
</script>

<template>
  <section
    id="kb-search-panel"
    class="res-panel kn-search"
    aria-labelledby="kb-tab-search"
    role="tabpanel"
  >
    <div class="res-panel-head">
      <div>
        <h2 class="res-panel-title">检索调试</h2>
        <p class="res-panel-sub">
          直接观察命中 chunk 与相关度，不注入 prompt；参数只对本次请求生效，不修改服务端配置。
        </p>
      </div>
      <span
        v-if="configReady"
        class="kn-mode"
        :class="{ 'is-vector': !config?.hybridEnabled }"
        :title="config?.hybridEnabled ? 'hybrid-enabled=true；BM25 索引不可用时后端自动退化为纯向量' : 'hybrid-enabled=false，仅走向量召回'"
      >
        <span class="kn-mode-dot" aria-hidden="true"></span>
        {{ modeLabel }}
      </span>
    </div>

    <p v-if="configError" class="res-alert" role="alert">{{ configError }}</p>

    <form class="kn-form" @submit.prevent="runSearch">
      <div class="kn-query-row">
        <label class="kn-query">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <circle cx="11" cy="11" r="7" />
            <path d="m20 20-3.5-3.5" />
          </svg>
          <input
            v-model="query"
            class="kn-query-input"
            type="search"
            placeholder="输入 query，例如：ERR-4021 / 事务传播 / 部署步骤"
            aria-label="检索词"
            :disabled="!configReady"
          />
        </label>
        <button class="res-btn res-btn-primary kn-run" type="submit" :disabled="!canSearch">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <circle cx="11" cy="11" r="7" />
            <path d="m20 20-3.5-3.5" />
          </svg>
          {{ loading ? '检索中…' : '开始检索' }}
        </button>
      </div>

      <div class="kn-kb-row">
        <span class="kn-kb-label">知识库</span>
        <button
          class="kn-kb-chip"
          :class="{ 'is-active': selectedKbs.length === 0 }"
          type="button"
          @click="selectedKbs = []"
        >
          全部
        </button>
        <button
          v-for="kb in availableKbs"
          :key="kb"
          class="kn-kb-chip"
          :class="{ 'is-active': selectedKbs.includes(kb) }"
          type="button"
          @click="toggleKb(kb)"
        >
          {{ kb }}
        </button>
        <span v-if="availableKbs.length === 0" class="kn-kb-empty">台账里还没有知识库</span>
      </div>

      <div class="kn-controls" :class="{ 'is-two': config && !config.hybridEnabled }">
        <label class="kn-control">
          <span class="kn-control-head">
            <span>返回条数</span>
            <input v-model.number="topK" class="kn-number" type="number" min="0" max="50" :disabled="!configReady" />
          </span>
          <span class="kn-control-hint">topK，0 = 不限条数</span>
        </label>

        <label class="kn-control">
          <span class="kn-control-head">
            <span>向量阈值</span>
            <strong class="kn-control-value">{{ Number(minScore).toFixed(2) }}</strong>
          </span>
          <input v-model.number="minScore" class="kn-range" type="range" min="0" max="1" step="0.01" :disabled="!configReady" />
          <span class="kn-control-hint">低于该值的向量召回会被丢弃</span>
        </label>

        <label v-if="config?.hybridEnabled" class="kn-control">
          <span class="kn-control-head">
            <span>融合下限</span>
            <strong class="kn-control-value">{{ Number(fusionMinScore).toFixed(2) }}</strong>
          </span>
          <input v-model.number="fusionMinScore" class="kn-range" type="range" min="0" max="1" step="0.01" :disabled="!configReady" />
          <span class="kn-control-hint">RRF 融合分低于该值按噪音丢弃</span>
        </label>
      </div>
    </form>

    <p v-if="configReady" class="kn-legend">
      {{ config?.hybridEnabled
        ? '混合检索开启时，「相关度」是向量与 BM25 两路 RRF 融合分；向量阈值只作用于向量召回，融合下限作用于最终结果。'
        : '纯向量模式下「相关度」是向量余弦相似度，与向量阈值同一口径。' }}
    </p>

    <div class="kn-results" aria-live="polite">
      <p v-if="loading" class="kn-state">正在检索命中片段…</p>
      <p v-else-if="error" class="res-alert kn-state-alert" role="alert">{{ error }}</p>

      <div v-else-if="!searched" class="kn-state kn-idle">
        <span class="kn-idle-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24">
            <circle cx="11" cy="11" r="7" />
            <path d="m20 20-3.5-3.5" />
            <path d="M7 11h8M11 7v8" />
          </svg>
        </span>
        <p class="kn-idle-title">输入 query，观察知识库到底召回了什么</p>
        <p class="kn-idle-desc">命中后会按相关度排序展示来源文档、分数与 chunk 原文，方便定位分块、阈值与 topK 问题。</p>
      </div>

      <div v-else-if="hits.length === 0" class="kn-state">
        <p class="kn-empty-title">没有命中</p>
        <p class="kn-empty-desc">可以降低向量阈值或融合下限、增大 topK，或换一个更贴近文档内容的 query。</p>
      </div>

      <template v-else>
        <div class="kn-results-head">
          <div class="kn-results-title">
            <strong>{{ hits.length }}</strong> 条命中
            <span class="kn-results-query">「{{ searchedQuery }}」</span>
            <span v-if="elapsedMs !== null" class="kn-results-time">{{ elapsedMs }} ms</span>
          </div>
          <span class="kn-score-label">{{ scoreLabel }}</span>
        </div>
        <div v-if="lastParams" class="kn-request-line">
          topK {{ lastParams.topK }} · 向量阈值 {{ lastParams.minScore.toFixed(2) }}<span
            v-if="config?.hybridEnabled"
          > · 融合下限 {{ lastParams.fusionMinScore.toFixed(2) }}</span>
          · {{ selectedKbs.length ? selectedKbs.join(' / ') : '全部知识库' }}
        </div>

        <ul class="kn-hit-list">
          <li v-for="(hit, index) in hits" :key="`${hit.docName}-${index}`" class="kn-hit">
            <div class="kn-hit-head">
              <span class="kn-rank" aria-hidden="true">{{ index + 1 }}</span>
              <div class="kn-hit-main">
                <div class="kn-hit-title">{{ hit.title || '未命名文档' }}</div>
                <div class="kn-hit-doc" :title="hit.docName">{{ hit.docName }}</div>
              </div>
              <span class="kn-hit-score" :title="`原始分数 ${hit.score}`">
                {{ (hit.score * 100).toFixed(0) }}<small>%</small>
              </span>
            </div>
            <div class="kn-score-track" aria-hidden="true">
              <span :style="{ width: scoreWidth(hit.score) }"></span>
            </div>
            <pre
              class="kn-hit-text"
              :class="{ 'is-expanded': expanded[index], 'is-short': hit.text.length <= 150 }"
            >{{ hit.text }}</pre>
            <button
              v-if="hit.text.length > 150"
              class="kn-hit-toggle"
              type="button"
              :aria-expanded="Boolean(expanded[index])"
              @click="toggleText(index)"
            >
              {{ expanded[index] ? '收起片段' : '展开完整片段' }}
            </button>
          </li>
        </ul>
      </template>
    </div>
  </section>
</template>
