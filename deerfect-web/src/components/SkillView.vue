<script setup lang="ts">
/**
 * 技能：skills/ 目录解析结果（GET /api/skills）。
 *
 * 2026-11-16 按能力面参考图改为卡片网格：
 *   统计条（技能 / 限定 Agent / 解析异常）→ 筛选 chips + 搜索 + 排序 → 三列卡片
 *   （图标 / 名称 / 适用 Agent / 状态 / 入口文件 / 描述 / 大小 / 复制路径）。
 * 只做只读展示与前端筛选排序：技能内容由目录里的 SKILL.md 决定，web 不写文件。
 */
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'
import type { SkillItem } from '../api'
import { fmtBytes } from '../utils/resourceFormat'
import PageHeader from './resource/PageHeader.vue'
import EmptyState from './resource/EmptyState.vue'

type FilterKey = 'all' | 'ready' | 'scoped' | 'error'
type SortKey = 'recommended' | 'name' | 'size'

const items = ref<SkillItem[]>([])
const loading = ref(false)
const error = ref('')
const note = ref('')
const query = ref('')
const filter = ref<FilterKey>('all')
const sort = ref<SortKey>('recommended')
const reloading = ref(false)

async function load(reload = false): Promise<void> {
  if (reload) reloading.value = true
  else loading.value = true
  error.value = ''
  try {
    items.value = await api.listSkills()
  } catch (e) {
    error.value = `技能目录读取失败：${e instanceof Error ? e.message : String(e)}`
  } finally {
    loading.value = false
    reloading.value = false
  }
}

onMounted(() => {
  void load()
})

const readyCount = computed(() => items.value.filter((s) => !s.error).length)
const scopedCount = computed(() => items.value.filter((s) => s.agents?.length).length)
const errorCount = computed(() => items.value.filter((s) => s.error).length)

const stats = computed(() => [
  { label: '技能', value: String(items.value.length) },
  { label: '限定 Agent', value: String(scopedCount.value) },
  { label: '解析异常', value: String(errorCount.value) },
])

const filters = computed(() => [
  { key: 'all' as const, label: '全部', count: items.value.length },
  { key: 'ready' as const, label: '就绪', count: readyCount.value },
  { key: 'scoped' as const, label: '限定 Agent', count: scopedCount.value },
  { key: 'error' as const, label: '解析异常', count: errorCount.value },
])

const filtered = computed(() => {
  const q = query.value.trim().toLowerCase()
  let list = items.value.filter((s) => {
    if (filter.value === 'ready' && s.error) return false
    if (filter.value === 'scoped' && !s.agents?.length) return false
    if (filter.value === 'error' && !s.error) return false
    if (!q) return true
    return [s.name, s.description, s.entryFile, ...(s.agents ?? [])].some((field) =>
      field?.toLowerCase().includes(q),
    )
  })
  if (sort.value === 'name') {
    list = [...list].sort((a, b) => a.name.localeCompare(b.name))
  } else if (sort.value === 'size') {
    list = [...list].sort((a, b) => b.size - a.size)
  }
  return list
})

/** 未限定 Agent 的技能显示「全部 Agent」，与卡片上的 chip 行对齐 */
function agentsOf(skill: SkillItem): string[] {
  return skill.agents?.length ? skill.agents : ['全部 Agent']
}

async function copyPath(skill: SkillItem): Promise<void> {
  try {
    await navigator.clipboard.writeText(skill.entryFile)
    note.value = `已复制入口路径：${skill.entryFile}`
  } catch {
    note.value = `复制失败，请手动复制：${skill.entryFile}`
  }
}
</script>

<template>
  <section class="res-page" aria-label="技能">
    <div class="res-inner">
      <PageHeader eyebrow="能力面" title="技能" sub="skills/ 目录下的 SKILL.md 解析结果与格式诊断">
        <template #actions>
          <button class="res-btn" type="button" :disabled="reloading" @click="load(true)">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M20 11a8 8 0 0 0-14-5L4 8" />
              <path d="M4 4v4h4" />
              <path d="M4 13a8 8 0 0 0 14 5l2-2" />
              <path d="M20 20v-4h-4" />
            </svg>
            {{ reloading ? '读取中…' : '刷新' }}
          </button>
        </template>
      </PageHeader>

      <p v-if="error" class="res-alert" role="alert">{{ error }}</p>
      <p v-else-if="note" class="res-note" role="status">{{ note }}</p>

      <div class="res-stats">
        <div v-for="stat in stats" :key="stat.label" class="res-stat">
          <span class="res-stat-label">{{ stat.label }}</span>
          <span class="res-stat-value">{{ stat.value }}</span>
        </div>
      </div>

      <div class="skill-filters" role="group" aria-label="技能筛选">
        <button
          v-for="f in filters"
          :key="f.key"
          class="skill-filter"
          :class="{ 'is-active': filter === f.key }"
          type="button"
          :aria-pressed="filter === f.key"
          @click="filter = f.key"
        >{{ f.label }} <span>{{ f.count }}</span></button>
      </div>

      <div class="skill-searchbar">
        <label class="res-search skill-search">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <circle cx="11" cy="11" r="7" />
            <path d="m20 20-3.5-3.5" />
          </svg>
          <input v-model="query" type="search" placeholder="搜索技能名称、描述或标签…" aria-label="搜索技能" />
        </label>
        <select v-model="sort" class="skill-sort" aria-label="排序">
          <option value="recommended">推荐排序</option>
          <option value="name">按名称</option>
          <option value="size">按文件大小</option>
        </select>
      </div>

      <div class="skill-list-head">
        <span class="skill-list-title">已启用 <b>{{ filtered.length }}</b> 项</span>
        <span class="skill-list-note">修改目录文件后点「刷新」即可重读</span>
      </div>

      <p v-if="loading && items.length === 0" class="res-loading">正在解析技能目录…</p>
      <EmptyState
        v-else-if="filtered.length === 0"
        icon="bolt"
        :title="items.length === 0 ? '技能目录为空' : '没有匹配的技能'"
        :desc="items.length === 0
          ? '在 skills/<name>/SKILL.md 放入带 name 与 description 的 front-matter，后端会在下次扫描时解析。'
          : '换个关键词或切回「全部」试试。'"
      />
      <div v-else class="skill-grid">
        <article
          v-for="skill in filtered"
          :key="skill.name"
          class="skill-card"
          :class="{ 'is-error': skill.error }"
        >
          <div class="skill-card-head">
            <span class="skill-mark" aria-hidden="true">
              <svg viewBox="0 0 24 24">
                <path d="M13.5 2.5 5 13.5h5.5L10 21.5 19 10h-5.5z" />
              </svg>
            </span>
            <div class="skill-card-title">
              <span class="skill-name" :title="skill.name">{{ skill.name }}</span>
              <span class="skill-agents">
                <span v-for="agent in agentsOf(skill)" :key="agent" class="res-chip">{{ agent }}</span>
              </span>
            </div>
            <span class="res-chip" :class="skill.error ? 'res-chip-warn' : 'res-chip-ok'">
              {{ skill.error ? '解析异常' : '就绪' }}
            </span>
          </div>

          <div class="skill-file" :title="skill.entryFile">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
            </svg>
            <span class="res-mono">{{ skill.entryFile }}</span>
          </div>

          <p class="skill-desc">{{ skill.description || '（SKILL.md 未写 description）' }}</p>

          <div class="skill-card-foot">
            <span class="skill-size">{{ fmtBytes(skill.size) }}</span>
            <button
              class="skill-copy"
              type="button"
              title="复制入口文件路径"
              :aria-label="`复制 ${skill.name} 的入口路径`"
              @click="copyPath(skill)"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <rect x="9" y="9" width="11" height="11" rx="2" />
                <path d="M5 15V5a2 2 0 0 1 2-2h8" />
              </svg>
            </button>
          </div>
        </article>
      </div>
    </div>
  </section>
</template>
