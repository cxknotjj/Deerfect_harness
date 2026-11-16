<script setup lang="ts">
/**
 * 监控·可折叠明细面板：标题行就是开关，折叠后只留一行标题 + 条数 + 说明。
 * 状态由父级持有（便于「全部收起/展开」与 localStorage 持久化），本组件只上报点击。
 */
defineProps<{
  id: string
  title: string
  note: string
  count?: number
  open: boolean
}>()

const emit = defineEmits<{ toggle: [] }>()
</script>

<template>
  <section
    class="panel panel-collapsible"
    :class="{ 'panel-collapsed': !open }"
    :data-testid="`detail-${id}`"
  >
    <button
      class="panel-toggle"
      type="button"
      :aria-expanded="open"
      :aria-controls="`detail-body-${id}`"
      :aria-label="`${title}，${open ? '收起' : '展开'}`"
      @click="emit('toggle')"
    >
      <svg class="panel-chevron" viewBox="0 0 24 24" aria-hidden="true">
        <path d="m9 6 6 6-6 6" />
      </svg>
      <span class="panel-title">{{ title }}</span>
      <span v-if="count !== undefined" class="panel-count">{{ count }}</span>
      <span class="panel-note">{{ note }}</span>
    </button>
    <div v-show="open" :id="`detail-body-${id}`" class="panel-body">
      <slot />
    </div>
  </section>
</template>
