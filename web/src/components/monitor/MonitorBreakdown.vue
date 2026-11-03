<script setup lang="ts">
/**
 * 监控·分布与趋势：渠道分布（点击行内渠道名即筛选）+ 每小时调用量柱状图。
 */
import { computed } from 'vue'
import type { MonitorSnapshot } from '../../api'
import { channelLabel, fmtMs, fmtNumber } from '../../utils/monitorFormat'

const props = defineProps<{ data: MonitorSnapshot; channel: string }>()
const emit = defineEmits<{ filter: [channel: string] }>()

const maxHourlyCalls = computed(() =>
  Math.max(1, ...(props.data.hourly.map((h) => h.calls) ?? [0])),
)

function barHeight(calls: number): string {
  return `${Math.max(2, Math.round((calls / maxHourlyCalls.value) * 100))}%`
}

/** 柱状图横轴标签间隔：小时数超过 12 时抽稀到约 8 个标签 */
const labelStep = computed(() => {
  const total = props.data.hourly.length
  return total > 12 ? Math.ceil(total / 8) : 1
})

function toggleChannel(key: string): void {
  emit('filter', props.channel === key ? '' : key)
}
</script>

<template>
  <section class="panel">
    <div class="panel-head">
      <h3>渠道分布</h3>
      <span class="panel-note">{{ channel ? `当前筛选：${channelLabel(channel)}` : '点击渠道名可筛选 / 取消筛选' }}</span>
    </div>
    <div v-if="data.channels.length === 0" class="empty">时间窗内暂无调用</div>
    <div v-else class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>渠道</th>
            <th>LLM 调用</th>
            <th>错误</th>
            <th>Tokens</th>
            <th>工具调用</th>
            <th>工具错误</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in data.channels" :key="c.channel">
            <td>
              <button
                type="button"
                class="channel-filter"
                :class="{ active: channel === c.channel }"
                :title="channel === c.channel ? '取消渠道筛选' : `只看 ${channelLabel(c.channel)}`"
                @click="toggleChannel(c.channel)"
              >{{ channelLabel(c.channel) }}</button>
            </td>
            <td class="mono">{{ fmtNumber(c.calls) }}</td>
            <td class="mono" :class="{ 'cell-bad': c.errors > 0 }">{{ fmtNumber(c.errors) }}</td>
            <td class="mono">{{ fmtNumber(c.totalTokens) }}</td>
            <td class="mono">{{ fmtNumber(c.toolCalls) }}</td>
            <td class="mono" :class="{ 'cell-bad': c.toolErrors > 0 }">{{ fmtNumber(c.toolErrors) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>

  <section class="panel">
    <div class="panel-head">
      <h3>每小时调用量</h3>
      <span class="panel-note">柱高 = 调用次数，悬停查看 token 与平均首 token</span>
    </div>
    <div v-if="data.hourly.length === 0" class="empty">时间窗内暂无调用</div>
    <div v-else class="chart" role="img" aria-label="每小时调用量柱状图">
      <div v-for="(h, i) in data.hourly" :key="h.bucket" class="bar-col">
        <div class="bar-track">
          <div
            class="bar"
            :style="{ height: barHeight(h.calls) }"
            :title="`${h.bucket} · ${h.calls} 次 · ${fmtNumber(h.tokens)} tokens · 首 token ${fmtMs(h.avgTtftMs)}`"
          ></div>
        </div>
        <span v-if="i % labelStep === 0" class="bar-label">{{ h.bucket }}</span>
      </div>
    </div>
  </section>
</template>
