<script setup lang="ts">
/**
 * 监控·明细：三张统计卡 + 一个「明细数据」面板（活跃会话 / LLM / 工具三段折叠）。
 *
 * 与旧版（三个独立折叠卡）相比，这里合并成一张面板：折叠行带数量胶囊与说明，
 * 展开后表格缩进在行下方；「上下文构成」仍在点「上下文」后单独出现在面板下方。
 */
import { computed, ref } from 'vue'
import { api } from '../../api'
import type { MonitorContextComposition, MonitorSnapshot } from '../../api'
import {
  channelLabel,
  ctxGroupClass,
  fmtClock,
  fmtMs,
  fmtNumber,
  fmtTime,
  statusClass,
} from '../../utils/monitorFormat'

const props = defineProps<{
  data: MonitorSnapshot
  sessionsOpen: boolean
  llmOpen: boolean
  toolsOpen: boolean
}>()
const emit = defineEmits<{ toggle: [key: 'sessions' | 'llm' | 'tools' | 'all'] }>()

const allOpen = computed(() => props.sessionsOpen && props.llmOpen && props.toolsOpen)

const stats = computed(() => [
  {
    key: 'sessions',
    label: '活跃会话',
    value: props.data.sessions.length,
    note: '时间窗内最近活跃',
    icon: ['M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z'],
  },
  {
    key: 'llm',
    label: '最近 LLM 调用',
    value: props.data.llmCalls.length,
    note: '按调用时间倒序',
    icon: ['M12 3 3 8l9 5 9-5-9-5z', 'M3 8v8l9 5 9-5V8'],
  },
  {
    key: 'tools',
    label: '最近工具调用',
    value: props.data.toolCalls.length,
    note: '外部与内置工具的执行记录',
    icon: ['M14.7 6.3a4 4 0 0 0 5 5L10.3 20.7a2 2 0 0 1-2.8-2.8z', 'm14 7 3 3'],
  },
])

/* ---------- 上下文构成（按需拉取，序号丢弃过期响应） ---------- */
const contextSessionId = ref('')
const contextData = ref<MonitorContextComposition | null>(null)
const contextLoading = ref(false)
const contextError = ref('')
let contextSeq = 0

async function loadContext(sessionId: string): Promise<void> {
  const sid = String(sessionId)
  const seq = ++contextSeq
  contextSessionId.value = sid
  contextLoading.value = true
  contextError.value = ''
  try {
    const resp = await api.monitorContext(sid)
    if (seq !== contextSeq) return
    contextData.value = resp
  } catch (e) {
    if (seq === contextSeq) {
      contextData.value = null
      contextError.value = `上下文快照加载失败：${e instanceof Error ? e.message : String(e)}`
    }
  } finally {
    if (seq === contextSeq) contextLoading.value = false
  }
}
</script>

<template>
  <div class="detail-stats">
    <article v-for="s in stats" :key="s.key" class="detail-stat">
      <span class="detail-stat-icon" aria-hidden="true">
        <svg viewBox="0 0 24 24">
          <path v-for="d in s.icon" :key="d" :d="d" />
        </svg>
      </span>
      <div class="detail-stat-text">
        <span class="detail-stat-label">{{ s.label }}</span>
        <strong class="detail-stat-value">{{ s.value }}</strong>
        <span class="detail-stat-note">{{ s.note }}</span>
      </div>
    </article>
  </div>

  <section class="panel detail-panel" aria-label="明细数据">
    <div class="detail-panel-head">
      <h3>明细数据</h3>
      <span class="detail-panel-note">活跃会话 · LLM 调用 · 工具调用</span>
      <button
        class="res-btn res-btn-sm"
        type="button"
        data-testid="details-toggle-all"
        :aria-expanded="allOpen"
        @click="emit('toggle', 'all')"
      >{{ allOpen ? '全部收起' : '全部展开' }}</button>
    </div>

    <!-- 活跃会话 -->
    <section class="detail-section" :class="{ 'is-open': sessionsOpen }">
      <button class="detail-row" type="button" :aria-expanded="sessionsOpen" @click="emit('toggle', 'sessions')">
        <svg class="detail-chevron" viewBox="0 0 24 24" aria-hidden="true"><path d="m9 6 6 6-6 6" /></svg>
        <span class="detail-row-title">活跃会话</span>
        <span class="detail-row-count">{{ data.sessions.length }}</span>
        <span class="detail-row-note">时间窗内最近活跃</span>
      </button>
      <div v-show="sessionsOpen" class="detail-body">
        <div v-if="data.sessions.length === 0" class="empty">时间窗内暂无活跃会话</div>
        <div v-else class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>会话 ID</th>
                <th>渠道</th>
                <th>Agent</th>
                <th>会话名</th>
                <th>最近活跃</th>
                <th>调用</th>
                <th>Tokens</th>
                <th>上下文</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="s in data.sessions" :key="s.sessionId">
                <td class="num">{{ s.sessionId }}</td>
                <td><span class="channel-badge">{{ channelLabel(s.channel) }}</span></td>
                <td>{{ s.agentName ?? '—' }}</td>
                <td class="ellipsis" :title="s.sessionName ?? ''">{{ s.sessionName ?? '—' }}</td>
                <td class="num">{{ fmtTime(s.lastActiveAt) }}</td>
                <td class="num">{{ fmtNumber(s.calls) }}</td>
                <td class="num">{{ fmtNumber(s.totalTokens) }}</td>
                <td>
                  <button
                    type="button"
                    class="ctx-btn"
                    :class="{ active: contextSessionId === String(s.sessionId) }"
                    @click="loadContext(String(s.sessionId))"
                  >上下文</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </section>

    <!-- 最近 LLM 调用 -->
    <section class="detail-section" :class="{ 'is-open': llmOpen }">
      <button class="detail-row" type="button" :aria-expanded="llmOpen" @click="emit('toggle', 'llm')">
        <svg class="detail-chevron" viewBox="0 0 24 24" aria-hidden="true"><path d="m9 6 6 6-6 6" /></svg>
        <span class="detail-row-title">最近 LLM 调用</span>
        <span class="detail-row-count">{{ data.llmCalls.length }}</span>
        <span class="detail-row-note">首 token — 表示该调用未采集 TTFT</span>
      </button>
      <div v-show="llmOpen" class="detail-body">
        <div v-if="data.llmCalls.length === 0" class="empty">时间窗内暂无 LLM 调用</div>
        <div v-else class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>时间</th>
                <th>会话</th>
                <th>渠道</th>
                <th>Agent</th>
                <th>模型</th>
                <th>类型</th>
                <th>首 token</th>
                <th>耗时</th>
                <th>Tokens</th>
                <th>状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="c in data.llmCalls" :key="c.id" class="llm-call-row">
                <td class="num">{{ fmtTime(c.createdAt) }}</td>
                <td class="num">{{ c.sessionId ?? '—' }}</td>
                <td><span class="channel-badge">{{ channelLabel(c.channel) }}</span></td>
                <td>{{ c.agentName ?? '—' }}</td>
                <td class="num ellipsis" :title="c.model ?? ''">{{ c.model ?? '—' }}</td>
                <td class="num">{{ c.callKind ?? '—' }}</td>
                <td class="num">{{ fmtMs(c.firstTokenMs) }}</td>
                <td class="num">{{ fmtMs(c.durationMs) }}</td>
                <td class="num">{{ fmtNumber(c.totalTokens) }}</td>
                <td>
                  <span class="status" :class="statusClass(c.status)">{{ c.status ?? '—' }}</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </section>

    <!-- 最近工具调用 -->
    <section class="detail-section" :class="{ 'is-open': toolsOpen }">
      <button class="detail-row" type="button" :aria-expanded="toolsOpen" @click="emit('toggle', 'tools')">
        <svg class="detail-chevron" viewBox="0 0 24 24" aria-hidden="true"><path d="m9 6 6 6-6 6" /></svg>
        <span class="detail-row-title">最近工具调用</span>
        <span class="detail-row-count">{{ data.toolCalls.length }}</span>
        <span class="detail-row-note">外部与内置工具的执行记录</span>
      </button>
      <div v-show="toolsOpen" class="detail-body">
        <div v-if="data.toolCalls.length === 0" class="empty">时间窗内暂无工具调用</div>
        <div v-else class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>时间</th>
                <th>会话</th>
                <th>渠道</th>
                <th>Agent</th>
                <th>工具</th>
                <th>来源</th>
                <th>耗时</th>
                <th>状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="t in data.toolCalls" :key="t.id">
                <td class="num">{{ fmtTime(t.createdAt) }}</td>
                <td class="num">{{ t.sessionId ?? '—' }}</td>
                <td><span class="channel-badge">{{ channelLabel(t.channel) }}</span></td>
                <td>{{ t.agentName ?? '—' }}</td>
                <td class="num">{{ t.toolName ?? '—' }}</td>
                <td>{{ t.serverName ?? '内置' }}</td>
                <td class="num">{{ fmtMs(t.durationMs) }}</td>
                <td>
                  <span class="status" :class="statusClass(t.status)">{{ t.status ?? '—' }}</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </section>

    <p class="detail-hint">展开后按时间倒序列出记录</p>
  </section>

  <!-- 上下文构成：点会话行「上下文」后出现在明细面板下方 -->
  <section v-if="contextSessionId" class="panel" aria-label="上下文构成">
    <div class="panel-head">
      <h3>上下文构成</h3>
      <span class="panel-note">估算口径 TokenEstimator · 不含轮内动态工具结果</span>
    </div>
    <div v-if="contextLoading" class="empty">加载中…</div>
    <div v-else-if="contextError" class="monitor-error" role="alert">{{ contextError }}</div>
    <div v-else-if="!contextData" class="empty">该会话暂无最近请求快照（先发一条消息再试）</div>
    <template v-else>
      <div class="ctx-meta">
        <span>会话 <code>{{ contextData.sessionId }}</code></span>
        <span>Agent <code>{{ contextData.agentName ?? '—' }}</code></span>
        <span>模型 <code>{{ contextData.model ?? '—' }}</code></span>
        <span>轮次 <code>{{ contextData.turnId ?? '—' }}</code></span>
        <span>采集 {{ fmtClock(contextData.capturedAt) }}</span>
        <span>估算合计 <strong>{{ fmtNumber(contextData.estimatedTotalTokens) }}</strong> tokens</span>
      </div>
      <div class="ctx-bar" role="img" aria-label="上下文构成占比">
        <span
          v-for="p in contextData.parts"
          :key="p.key"
          class="ctx-seg"
          :class="ctxGroupClass(p.group)"
          :style="{ width: `${p.percent}%` }"
          :title="`${p.label} · ${fmtNumber(p.tokens)} tokens · ${p.percent.toFixed(1)}%`"
        ></span>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>组成项</th>
              <th>分组</th>
              <th>Tokens</th>
              <th>占比</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="p in contextData.parts" :key="p.key">
              <td>{{ p.label }}</td>
              <td class="num">
                <span class="ctx-dot" :class="ctxGroupClass(p.group)"></span>{{ p.group }}
              </td>
              <td class="num">{{ fmtNumber(p.tokens) }}</td>
              <td class="num">{{ p.percent.toFixed(1) }}%</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </section>
</template>
