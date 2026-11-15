<script setup lang="ts">
/** 资源面空态：图标 + 一句结论 + 一条可操作说明（不做假数据） */
import { computed } from 'vue'

type IconKind = 'book' | 'sparkle' | 'bolt' | 'calendar' | 'plug' | 'inbox'

const props = withDefaults(
  defineProps<{
    title: string
    desc: string
    icon?: IconKind
  }>(),
  { icon: 'inbox' },
)

const ICONS: Record<IconKind, string[]> = {
  book: ['M4 19.5A2.5 2.5 0 0 1 6.5 17H20', 'M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z'],
  sparkle: ['M12 3.5 14 9l5.5 2-5.5 2-2 5.5-2-5.5L4.5 11 10 9z'],
  bolt: ['M13.5 2.5 5 13.5h5.5L10 21.5 19 10h-5.5z'],
  calendar: ['M4 8a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2z', 'M4 10.5h16', 'M8.5 3v4M15.5 3v4'],
  plug: ['M9 3v5M15 3v5', 'M6.5 8h11v2.5a5.5 5.5 0 0 1-11 0z', 'M12 16v5'],
  inbox: ['M4 4h16v16H4z', 'M4 13h5l1 2h4l1-2h5'],
}

const paths = computed(() => ICONS[props.icon])
</script>

<template>
  <div class="res-empty">
    <span class="res-empty-icon" aria-hidden="true">
      <svg viewBox="0 0 24 24">
        <path v-for="d in paths" :key="d" :d="d" />
      </svg>
    </span>
    <div class="res-empty-title">{{ title }}</div>
    <p class="res-empty-desc">{{ desc }}</p>
  </div>
</template>
