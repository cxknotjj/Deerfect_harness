<script setup lang="ts">
/**
 * 周趋势折线图：按天聚合的 LLM 调用（左轴）与令牌用量（右轴，单位 k）。
 * 纯 SVG 手绘（项目零图表库约束），配色只取 accent / blueprint，网格用 line-soft。
 * 窄屏切紧凑 viewBox（360×208）：轴文字按真实像素渲染，避免整图缩放后压到 5px。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

interface TrendPoint {
  label: string
  calls: number
  tokens: number
}

const props = defineProps<{ points: TrendPoint[] }>()

/** 窄屏断点：与 CSS 的 640px 对齐 */
const compact = ref(window.matchMedia('(max-width: 640px)').matches)
const mql = window.matchMedia('(max-width: 640px)')
function syncCompact(e: MediaQueryListEvent | MediaQueryList): void {
  compact.value = e.matches
}
onMounted(() => mql.addEventListener('change', syncCompact))
onBeforeUnmount(() => mql.removeEventListener('change', syncCompact))

const geom = computed(() => {
  const w = compact.value ? 360 : 720
  const h = compact.value ? 208 : 232
  const pad = compact.value ? { l: 44, r: 44, t: 12, b: 24 } : { l: 36, r: 46, t: 14, b: 26 }
  return { w, h, pad, innerW: w - pad.l - pad.r, innerH: h - pad.t - pad.b }
})

function niceMax(value: number): number {
  if (value <= 0) return 1
  const exp = Math.floor(Math.log10(value))
  const base = 10 ** exp
  const n = value / base
  const step = n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10
  return step * base
}

const maxCalls = computed(() => niceMax(Math.max(...props.points.map((p) => p.calls), 0)))
const maxTokensK = computed(() =>
  niceMax(Math.max(...props.points.map((p) => p.tokens / 1000), 0)),
)

const empty = computed(() => props.points.every((p) => p.calls === 0 && p.tokens === 0))

function x(index: number): number {
  const { pad, innerW } = geom.value
  const n = props.points.length
  if (n <= 1) return pad.l + innerW / 2
  return pad.l + (innerW * index) / (n - 1)
}

function gridY(ratio: number): number {
  const { pad, innerH } = geom.value
  return pad.t + innerH * (1 - ratio)
}

function yCalls(value: number): number {
  return gridY(value / maxCalls.value)
}

function yTokens(value: number): number {
  const { pad, innerH } = geom.value
  return pad.t + innerH * (1 - value / 1000 / maxTokensK.value)
}

const callsPath = computed(() =>
  props.points.map((p, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${yCalls(p.calls).toFixed(1)}`).join(' '),
)

const tokensPath = computed(() =>
  props.points
    .map((p, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${yTokens(p.tokens).toFixed(1)}`)
    .join(' '),
)

const areaPath = computed(() => {
  if (props.points.length === 0) return ''
  const base = gridY(0)
  return `${callsPath.value} L${x(props.points.length - 1).toFixed(1)},${base} L${x(0).toFixed(1)},${base} Z`
})

/** 4 段网格（0/25/50/75/100%），左右轴各自标注 */
const ticks = [0, 0.25, 0.5, 0.75, 1]

function callsTick(ratio: number): string {
  const v = maxCalls.value * ratio
  return Number.isInteger(v) ? String(v) : v.toFixed(1)
}

function tokensTick(ratio: number): string {
  const v = maxTokensK.value * ratio
  return Number.isInteger(v) ? String(v) : v.toFixed(1)
}
</script>

<template>
  <div class="chart-wrap">
    <div v-if="empty" class="chart-empty">近 7 天暂无调用记录</div>
    <svg
      v-else
      class="chart-svg"
      :viewBox="`0 0 ${geom.w} ${geom.h}`"
      role="img"
      aria-label="近 7 天调用与令牌趋势"
    >
      <defs>
        <linearGradient id="trend-area" x1="0" y1="0" x2="0" y2="1">
          <stop class="trend-stop-top" offset="0%" />
          <stop class="trend-stop-bottom" offset="100%" />
        </linearGradient>
      </defs>

      <!-- 网格与左右轴刻度 -->
      <g>
        <template v-for="t in ticks" :key="t">
          <line
            :x1="geom.pad.l"
            :x2="geom.w - geom.pad.r"
            :y1="gridY(t)"
            :y2="gridY(t)"
            class="chart-grid-line"
          />
          <text
            :x="geom.pad.l - 8"
            :y="gridY(t) + 3"
            text-anchor="end"
            class="chart-axis"
          >{{ callsTick(t) }}</text>
          <text
            :x="geom.w - geom.pad.r + 8"
            :y="gridY(t) + 3"
            text-anchor="start"
            class="chart-axis"
          >{{ tokensTick(t) }}</text>
        </template>
      </g>

      <!-- 令牌面积不画，仅调用画面积，避免两条半透明层叠发脏 -->
      <path :d="areaPath" fill="url(#trend-area)" />
      <path :d="tokensPath" class="chart-line-tokens" />
      <path :d="callsPath" class="chart-line-calls" />

      <!-- 数据点：hover 走原生 title，够用且不引入浮层逻辑 -->
      <g v-for="(p, i) in points" :key="p.label">
        <circle :cx="x(i)" :cy="yCalls(p.calls)" r="3" class="chart-dot-calls">
          <title>{{ p.label }}：{{ p.calls }} 次调用</title>
        </circle>
        <circle :cx="x(i)" :cy="yTokens(p.tokens)" r="2.4" class="chart-dot-tokens">
          <title>{{ p.label }}：{{ p.tokens }} tokens</title>
        </circle>
        <text :x="x(i)" :y="geom.h - 6" text-anchor="middle" class="chart-axis">{{ p.label }}</text>
      </g>
    </svg>
  </div>
</template>
