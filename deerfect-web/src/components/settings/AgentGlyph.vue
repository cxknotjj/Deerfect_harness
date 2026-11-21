<script setup lang="ts">
/**
 * Agent 身份图标：内置 Agent 渲染专属线性图形，未知 Agent 回退到首字母。
 * 外层容器负责底色/尺寸，本组件只画图形，便于卡片与弹窗复用。
 */
import { computed } from 'vue'
import { agentGlyphFor } from '../../utils/agentGlyph'

const props = defineProps<{ name: string }>()

const glyph = computed(() => agentGlyphFor(props.name))
const fallback = computed(() => {
  const trimmed = props.name.trim()
  return trimmed ? trimmed.charAt(0).toUpperCase() : '?'
})
</script>

<template>
  <svg
    v-if="glyph"
    class="agent-glyph"
    viewBox="0 0 24 24"
    width="19"
    height="19"
    aria-hidden="true"
    focusable="false"
  >
    <g fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
      <path v-for="path in glyph.paths" :key="path" :d="path" />
      <ellipse
        v-for="(ellipse, index) in glyph.ellipses"
        :key="`ellipse-${index}`"
        :cx="ellipse.cx"
        :cy="ellipse.cy"
        :rx="ellipse.rx"
        :ry="ellipse.ry"
        :transform="ellipse.rotate ? `rotate(${ellipse.rotate} 12 12)` : undefined"
      />
      <circle
        v-for="(dot, index) in glyph.dots"
        :key="`dot-${index}`"
        :cx="dot.cx"
        :cy="dot.cy"
        :r="dot.r"
        :fill="dot.filled ? 'currentColor' : 'none'"
        :stroke="dot.filled ? 'none' : 'currentColor'"
      />
    </g>
  </svg>
  <span v-else class="agent-glyph-fallback" aria-hidden="true">{{ fallback }}</span>
</template>
