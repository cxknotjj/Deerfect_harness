<script setup lang="ts">
/**
 * 仪表盘统计卡：图标 + 主数值 + 说明。
 * 纯展示，数值文案由父级格式化后传入（口径统一在 DashboardView）。
 */
type IconName = 'sessions' | 'calls' | 'tools' | 'tokens'

defineProps<{
  icon: IconName
  label: string
  value: string
  note?: string
}>()

const ICONS: Record<IconName, string[]> = {
  sessions: ['M20 15a2 2 0 0 1-2 2H8l-4 4V5a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2z', 'M8 9h8', 'M8 12.5h5'],
  calls: ['M3 12h4l2-6 4 12 2-6h6'],
  tools: ['M14.5 6.5a3.5 3.5 0 0 0 4.7 4.7l1.8 1.8-8 8-2.5-2.5 8-8z', 'M6.5 6.5 4 9l2.5 2.5'],
  tokens: [
    'M12 3c4.4 0 8 1.3 8 3s-3.6 3-8 3-8-1.3-8-3 3.6-3 8-3z',
    'M4 6v6c0 1.7 3.6 3 8 3s8-1.3 8-3V6',
    'M4 12v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6',
  ],
}
</script>

<template>
  <article class="stat-card">
    <span class="stat-icon" aria-hidden="true">
      <svg viewBox="0 0 24 24">
        <path v-for="d in ICONS[icon]" :key="d" :d="d" />
      </svg>
    </span>
    <div>
      <div class="stat-value">{{ value }}</div>
      <div class="stat-label">{{ label }}<template v-if="note"> · {{ note }}</template></div>
    </div>
  </article>
</template>
