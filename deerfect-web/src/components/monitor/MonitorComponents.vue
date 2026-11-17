<script setup lang="ts">
/**
 * 监控·组件与资源：上通栏组件状态表，下双栏「资源占用 + 探活与连接」。
 * 组件状态对齐 ComponentStatus（配置启用 + 当前可用），探活缓存固定 30s。
 */
import type { MonitorSnapshot } from '../../api'
import { componentClass, componentLabel, fmtNumber } from '../../utils/monitorFormat'
import MonitorResources from './MonitorResources.vue'

defineProps<{ data: MonitorSnapshot }>()
</script>

<template>
  <section class="panel">
    <div class="panel-head">
      <h3>组件状态</h3>
      <span class="panel-note">NapCat / Docker / MCP · 探活缓存 30s</span>
    </div>
    <div v-if="data.components.length === 0" class="empty">未配置外部组件</div>
    <div v-else class="table-wrap">
      <table class="component-table">
        <thead>
          <tr>
            <th>组件</th>
            <th>状态</th>
            <th>说明</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in data.components" :key="c.name" class="component-row">
            <td>{{ c.name }}</td>
            <td>
              <span class="status" :class="componentClass(c)">{{ componentLabel(c) }}</span>
            </td>
            <td class="component-detail">{{ c.detail }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>

  <div class="monitor-split monitor-split-components">
    <MonitorResources :data="data" />

    <section class="panel" aria-label="探活与连接">
      <div class="panel-head">
        <h3>探活与连接</h3>
        <span class="panel-note">进程级 · 重启清零</span>
      </div>
      <div class="runtime-rows">
        <div class="runtime-row">
          <span>探活缓存</span>
          <b>30s</b>
        </div>
        <div class="runtime-row">
          <span>覆盖组件</span>
          <b>NapCat · Docker · MCP</b>
        </div>
        <div class="runtime-row">
          <span>SSE 活跃连接</span>
          <b>{{ fmtNumber(data.runtime.sseActive) }}</b>
        </div>
      </div>
    </section>
  </div>
</template>
