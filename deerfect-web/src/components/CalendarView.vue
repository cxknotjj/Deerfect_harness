<script setup lang="ts">
/**
 * 内容日历：把定时任务（GET /api/schedules）按「下一次触发」排开。
 *
 * 后端目前只有「定时让 Agent 自己开始干活」这一种排期，所以这里展示的就是
 * 真实的 schedule 台账，不造内容素材/渠道数据；每次触发产生的 Goal 在监控页可查。
 */
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'
import type { ScheduleItem } from '../api'
import { fmtDateTime, fmtDayParts, localTimeMs } from '../utils/resourceFormat'
import PageHeader from './resource/PageHeader.vue'
import EmptyState from './resource/EmptyState.vue'

const emit = defineEmits<{ changed: [] }>()

const items = ref<ScheduleItem[]>([])
const loading = ref(false)
const error = ref('')
const busy = ref('')

function errText(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    items.value = await api.listSchedules()
  } catch (e) {
    error.value = `排期加载失败：${errText(e)}`
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
})

/** 下一次触发升序；无 nextRunAt（已停用/异常）排到末尾 */
const sorted = computed(() =>
  [...items.value].sort((a, b) => localTimeMs(a.nextRunAt) - localTimeMs(b.nextRunAt)),
)

const stats = computed(() => {
  const upcoming = sorted.value[0]?.nextRunAt
  return [
    { label: '排期', value: String(items.value.length) },
    { label: '已启用', value: String(items.value.filter((s) => s.enabled).length) },
    { label: '最近触发', value: upcoming ? fmtDateTime(upcoming) : '—' },
  ]
})

function dayOf(value: string | null): { day: string; week: string } {
  return fmtDayParts(value)
}

async function remove(item: ScheduleItem): Promise<void> {
  if (busy.value || !window.confirm(`删除排期「${item.objective}」？已触发的 Goal 不受影响。`)) return
  busy.value = item.id
  error.value = ''
  try {
    await api.deleteSchedule(item.id)
    await load()
    emit('changed')
  } catch (e) {
    error.value = `删除失败：${errText(e)}`
  } finally {
    busy.value = ''
  }
}
</script>

<template>
  <section class="res-page" aria-label="内容日历">
    <div class="res-inner">
      <PageHeader eyebrow="自动排期" title="内容日历" sub="定时任务按下一次触发时间排开；每次运行产生一个 Goal" />

      <p v-if="error" class="res-alert" role="alert">{{ error }}</p>

      <div class="res-stats">
        <div v-for="stat in stats" :key="stat.label" class="res-stat">
          <span class="res-stat-label">{{ stat.label }}</span>
          <span class="res-stat-value">{{ stat.value }}</span>
        </div>
      </div>

      <section class="res-panel" aria-label="排期清单">
        <div class="res-panel-head">
          <div>
            <h2 class="res-panel-title">排期清单</h2>
            <p class="res-panel-sub">cron 为 Spring 6 段表达式（秒 分 时 日 月 周）</p>
          </div>
        </div>

        <p v-if="loading && items.length === 0" class="res-loading">正在读取排期…</p>
        <EmptyState
          v-else-if="items.length === 0"
          icon="calendar"
          title="日历上还没有安排"
          desc="通过 CLI 或 POST /api/schedules 创建定时任务（objective / agentName / cron）后，会在这里按触发时间排开。"
        />
        <ul v-else class="res-list">
          <li v-for="item in sorted" :key="item.id" class="res-row">
            <span class="res-date" aria-hidden="true">
              <span class="res-date-day">{{ dayOf(item.nextRunAt).day }}</span>
              <span class="res-date-week">{{ dayOf(item.nextRunAt).week }}</span>
            </span>
            <div class="res-row-main">
              <div class="res-row-title">{{ item.objective }}</div>
              <div class="res-row-sub">
                <span class="res-chip res-chip-accent">{{ item.agentName }}</span>
                <span class="res-mono">{{ item.cron }}</span>
                <span v-if="item.notifyChannel" class="res-mono">{{ item.notifyChannel }}</span>
                <span v-if="!item.enabled" class="res-chip">已停用</span>
              </div>
            </div>
            <span class="res-row-col">下次 {{ fmtDateTime(item.nextRunAt) }}</span>
            <span class="res-row-col">上次 {{ fmtDateTime(item.lastRunAt) }}</span>
            <button
              class="res-row-del"
              type="button"
              title="删除排期"
              :aria-label="`删除排期 ${item.objective}`"
              :disabled="busy === item.id"
              @click="remove(item)"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M4 7h16M9 7V4h6v3M6 7l1 14h10l1-14M10 11v6M14 11v6" />
              </svg>
            </button>
          </li>
        </ul>
      </section>
    </div>
  </section>
</template>
