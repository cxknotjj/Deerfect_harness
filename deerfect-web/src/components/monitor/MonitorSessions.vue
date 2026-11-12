<script setup lang="ts">
/**
 * 监控·会话与上下文明细：活跃会话表 + 单会话上下文构成面板。
 *
 * 上下文快照按需拉取（点击会话行「上下文」），自带加载/错误/空态；
 * 用递增序号丢弃过期响应，避免快速连点导致旧结果覆盖新结果。
 */
import { ref } from 'vue'
import { api } from '../../api'
import type { MonitorContextComposition, MonitorSnapshot } from '../../api'
import { channelLabel, ctxGroupClass, fmtClock, fmtNumber, fmtTime } from '../../utils/monitorFormat'

defineProps<{ data: MonitorSnapshot }>()

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
  <section class="panel">
    <div class="panel-head">
      <h3>活跃会话</h3>
      <span class="panel-note">时间窗内最近活跃 · {{ data.sessions.length }} 条</span>
    </div>
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
            <td class="mono">{{ s.sessionId }}</td>
            <td><span class="channel-badge">{{ channelLabel(s.channel) }}</span></td>
            <td>{{ s.agentName ?? '—' }}</td>
            <td class="ellipsis" :title="s.sessionName ?? ''">{{ s.sessionName ?? '—' }}</td>
            <td class="mono">{{ fmtTime(s.lastActiveAt) }}</td>
            <td class="mono">{{ fmtNumber(s.calls) }}</td>
            <td class="mono">{{ fmtNumber(s.totalTokens) }}</td>
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
  </section>

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
              <td class="mono">
                <span class="ctx-dot" :class="ctxGroupClass(p.group)"></span>{{ p.group }}
              </td>
              <td class="mono">{{ fmtNumber(p.tokens) }}</td>
              <td class="mono">{{ p.percent.toFixed(1) }}%</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </section>
</template>
