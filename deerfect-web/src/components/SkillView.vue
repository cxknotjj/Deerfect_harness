<script setup lang="ts">
/**
 * 技能：技能目录解析结果（GET /api/skills）。
 * 只读视图——技能由 skills/ 目录里的 SKILL.md 提供，前端不改动文件。
 */
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'
import type { SkillItem } from '../api'
import { fmtBytes } from '../utils/resourceFormat'
import PageHeader from './resource/PageHeader.vue'
import EmptyState from './resource/EmptyState.vue'

const items = ref<SkillItem[]>([])
const loading = ref(false)
const error = ref('')

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    items.value = await api.listSkills()
  } catch (e) {
    error.value = `技能目录读取失败：${e instanceof Error ? e.message : String(e)}`
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
})

const stats = computed(() => [
  { label: '技能', value: String(items.value.length) },
  { label: '限定 Agent', value: String(items.value.filter((s) => s.agents?.length).length) },
  { label: '解析异常', value: String(items.value.filter((s) => s.error).length) },
])
</script>

<template>
  <section class="res-page" aria-label="技能">
    <div class="res-inner">
      <PageHeader eyebrow="能力面" title="技能" sub="skills/ 目录下的 SKILL.md 解析结果与格式诊断" />

      <p v-if="error" class="res-alert" role="alert">{{ error }}</p>

      <div class="res-stats">
        <div v-for="stat in stats" :key="stat.label" class="res-stat">
          <span class="res-stat-label">{{ stat.label }}</span>
          <span class="res-stat-value">{{ stat.value }}</span>
        </div>
      </div>

      <section class="res-panel" aria-label="技能清单">
        <div class="res-panel-head">
          <div>
            <h2 class="res-panel-title">技能清单</h2>
            <p class="res-panel-sub">入口文件、适用 Agent 与解析诊断；修改目录文件后刷新即可重读</p>
          </div>
        </div>

        <p v-if="loading && items.length === 0" class="res-loading">正在解析技能目录…</p>
        <EmptyState
          v-else-if="items.length === 0"
          icon="bolt"
          title="技能目录为空"
          desc="在 skills/<name>/SKILL.md 放入带 name 与 description 的 front-matter，后端会在下次扫描时解析。"
        />
        <ul v-else class="res-list">
          <li v-for="skill in items" :key="skill.name" class="res-row">
            <span class="res-mark" aria-hidden="true">
              <svg viewBox="0 0 24 24">
                <path d="M13.5 2.5 5 13.5h5.5L10 21.5 19 10h-5.5z" />
              </svg>
            </span>
            <div class="res-row-main">
              <div class="res-skill-name">{{ skill.name }}</div>
              <div class="res-row-sub">
                <template v-if="skill.agents?.length">
                  <span v-for="agent in skill.agents" :key="agent" class="res-chip">{{ agent }}</span>
                </template>
                <span v-else class="res-chip">全部 Agent</span>
                <span class="res-mono">{{ skill.entryFile }}</span>
              </div>
              <div v-if="skill.description" class="res-skill-desc">{{ skill.description }}</div>
            </div>
            <span class="res-row-col">{{ fmtBytes(skill.size) }}</span>
            <span v-if="skill.error" class="res-chip res-chip-warn" :title="skill.error">解析异常</span>
            <span v-else class="res-chip res-chip-ok">就绪</span>
          </li>
        </ul>
      </section>
    </div>
  </section>
</template>
