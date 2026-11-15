<script setup lang="ts">
/** 资源面页头：统一 eyebrow 胶囊 / 大标题 / 说明 + 右侧动作插槽 */
defineProps<{
  eyebrow: string
  title: string
  sub: string
}>()
</script>

<template>
  <header class="res-head">
    <div class="res-head-text">
      <span class="res-eyebrow">{{ eyebrow }}</span>
      <h1 class="res-title">{{ title }}</h1>
      <p class="res-sub">{{ sub }}</p>
    </div>
    <div class="res-head-actions">
      <slot name="actions" />
    </div>
  </header>
</template>
