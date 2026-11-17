<script setup lang="ts">
/**
 * 监控·每小时调用量：柱高 = 调用次数，悬停给出 token 与平均首 token。
 * 时间窗 7d 时有 168 个小时桶，横轴标签抽稀到约 8 个，容器可横向滚动。
 */
import { computed } from 'vue'
import type { MonitorSnapshot } from '../../api'
import { fmtMs, fmtNumber } from '../../utils/monitorFormat'

const props = defineProps<{ data: MonitorSnapshot }>()

/**
 * 只画有调用的时段：后端按整点补零返回（7d = 168 桶），全画出来柱子会细到糊成一片，
 * 参考图也只展示有数据的桶。空小时不占位，横轴标签自带日期时间可读出间隔。
 */
const buckets = computed(() => props.data.hourly.filter((h) => h.calls > 0))

const maxHourlyCalls = computed(() =>
  Math.max(1, ...(buckets.value.map((h) => h.calls) ?? [0])),
)

function barHeight(calls: number): string {
  return `${Math.max(2, Math.round((calls / maxHourlyCalls.value) * 100))}%`
}

/** 柱状图横轴标签间隔：桶数超过 12 时抽稀到约 8 个标签 */
const labelStep = computed(() => {
  const total = buckets.value.length
  return total > 12 ? Math.ceil(total / 8) : 1
})
</script>

<template>
  <section class="panel">
    <div class="panel-head">
      <h3>每小时调用量</h3>
      <span class="panel-note">柱高 = 调用次数 · 仅显示有调用的小时 · 悬停查看 token 与首 token</span>
    </div>
    <div v-if="buckets.length === 0" class="empty">时间窗内暂无调用</div>
    <div v-else class="chart" role="img" aria-label="每小时调用量柱状图">
      <div v-for="(h, i) in buckets" :key="h.bucket" class="bar-col">
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
