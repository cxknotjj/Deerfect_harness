<script setup lang="ts">
/**
 * 监控·外部组件状态：NapCat / Docker / MCP 的探活结果（只读）。
 */
import type { MonitorSnapshot } from '../../api'
import { componentClass, componentLabel } from '../../utils/monitorFormat'

defineProps<{ data: MonitorSnapshot }>()
</script>

<template>
  <section class="panel">
    <div class="panel-head">
      <h3>组件状态</h3>
      <span class="panel-note">NapCat / Docker / MCP · NapCat 探活缓存 30s</span>
    </div>
    <div v-if="data.components.length === 0" class="empty">未配置外部组件</div>
    <div v-else class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>组件</th>
            <th>状态</th>
            <th>说明</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in data.components" :key="c.name" class="component-row">
            <td class="num">{{ c.name }}</td>
            <td>
              <span class="status" :class="componentClass(c)">{{ componentLabel(c) }}</span>
            </td>
            <td class="component-detail">{{ c.detail }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>
