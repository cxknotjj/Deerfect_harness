<script setup lang="ts">
/**
 * 监控·资源占用：把运行时里的堆内存 / CPU / 运行时长做成带进度条的紧凑面板。
 * 与「运行时指标」的数字格互补：那里看累计量，这里看当前水位。
 * 纯展示，数据由父级一次拉取后透传。
 */
import { computed } from 'vue'
import type { MonitorSnapshot } from '../../api'
import { fmtBytes, fmtNumber, fmtPercent, fmtUptime } from '../../utils/monitorFormat'

const props = defineProps<{ data: MonitorSnapshot }>()

/** 堆内存水位：heapMax 缺失时为 0，不画条（除零保护） */
const heapPercent = computed(() => {
  const { heapUsedBytes, heapMaxBytes } = props.data.runtime
  return heapMaxBytes > 0 ? Math.min(100, (heapUsedBytes / heapMaxBytes) * 100) : 0
})

/** processCpuUsage 是 0-1 比例（单核口径可 >1，故做上限收敛） */
const cpuPercent = computed(() =>
  Math.min(100, Math.max(0, props.data.runtime.processCpuUsage * 100)),
)

const runtime = computed(() => props.data.runtime)
</script>

<template>
  <section class="panel usage-panel" aria-label="资源占用">
    <div class="panel-head">
      <h3>资源占用</h3>
      <span class="panel-note">进程级 · 重启清零</span>
    </div>

    <div class="usage-rows">
      <div class="usage-row">
        <div class="usage-line">
          <span>堆内存<em class="usage-ratio">{{ heapPercent.toFixed(1) }}%</em></span>
          <strong class="usage-value">{{ fmtBytes(runtime.heapUsedBytes) }}</strong>
        </div>
        <div class="usage-track" role="img" :aria-label="`堆内存占用 ${heapPercent.toFixed(1)}%`">
          <span class="usage-fill" :style="{ width: `${heapPercent}%` }"></span>
        </div>
      </div>

      <div class="usage-row">
        <div class="usage-line">
          <span>CPU</span>
          <strong class="usage-value">{{ fmtPercent(runtime.processCpuUsage) }}</strong>
        </div>
        <div class="usage-track" role="img" :aria-label="`CPU 使用率 ${cpuPercent.toFixed(1)}%`">
          <span class="usage-fill" :style="{ width: `${cpuPercent}%` }"></span>
        </div>
      </div>

      <div class="usage-row">
        <div class="usage-line">
          <span>运行时长</span>
          <strong class="usage-value">{{ fmtUptime(runtime.uptimeSeconds) }}</strong>
        </div>
      </div>
    </div>

    <div class="usage-meta">
      <div class="usage-meta-cell">
        <span>堆上限</span>
        <strong>{{ fmtBytes(runtime.heapMaxBytes) }}</strong>
      </div>
      <div class="usage-meta-cell">
        <span>活跃线程</span>
        <strong>{{ fmtNumber(runtime.liveThreads) }}</strong>
      </div>
      <div class="usage-meta-cell">
        <span>SSE 连接</span>
        <strong>{{ fmtNumber(runtime.sseActive) }}</strong>
      </div>
    </div>
  </section>
</template>
