<script setup lang="ts">
/**
 * 监控·调用轨迹：最近 LLM 调用与工具调用两张表（观测数据，mono 示波器语义）。
 */
import type { MonitorSnapshot } from '../../api'
import { channelLabel, fmtMs, fmtNumber, fmtTime, statusClass } from '../../utils/monitorFormat'

defineProps<{ data: MonitorSnapshot }>()
</script>

<template>
  <section class="panel">
    <div class="panel-head">
      <h3>最近 LLM 调用</h3>
      <span class="panel-note">首 token — 表示该调用未采集 TTFT</span>
    </div>
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
            <td class="mono">{{ fmtTime(c.createdAt) }}</td>
            <td class="mono">{{ c.sessionId ?? '—' }}</td>
            <td><span class="channel-badge">{{ channelLabel(c.channel) }}</span></td>
            <td>{{ c.agentName ?? '—' }}</td>
            <td class="mono ellipsis" :title="c.model ?? ''">{{ c.model ?? '—' }}</td>
            <td class="mono">{{ c.callKind ?? '—' }}</td>
            <td class="mono">{{ fmtMs(c.firstTokenMs) }}</td>
            <td class="mono">{{ fmtMs(c.durationMs) }}</td>
            <td class="mono">{{ fmtNumber(c.totalTokens) }}</td>
            <td>
              <span class="status" :class="statusClass(c.status)">{{ c.status ?? '—' }}</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>

  <section class="panel">
    <div class="panel-head">
      <h3>最近工具调用</h3>
      <span class="panel-note">{{ data.toolCalls.length }} 条</span>
    </div>
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
            <td class="mono">{{ fmtTime(t.createdAt) }}</td>
            <td class="mono">{{ t.sessionId ?? '—' }}</td>
            <td><span class="channel-badge">{{ channelLabel(t.channel) }}</span></td>
            <td>{{ t.agentName ?? '—' }}</td>
            <td class="mono">{{ t.toolName ?? '—' }}</td>
            <td>{{ t.serverName ?? '内置' }}</td>
            <td class="mono">{{ fmtMs(t.durationMs) }}</td>
            <td>
              <span class="status" :class="statusClass(t.status)">{{ t.status ?? '—' }}</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>
