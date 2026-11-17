<script setup lang="ts">
/**
 * 监控·渠道分布：按渠道汇总调用/错误/token/工具；点击渠道名即筛选（再点取消）。
 */
import type { MonitorSnapshot } from '../../api'
import { channelLabel, fmtNumber } from '../../utils/monitorFormat'

const props = defineProps<{ data: MonitorSnapshot; channel: string }>()
const emit = defineEmits<{ filter: [channel: string] }>()

function toggleChannel(key: string): void {
  emit('filter', props.channel === key ? '' : key)
}
</script>

<template>
  <section class="panel">
    <div class="panel-head">
      <h3>渠道分布</h3>
      <span class="panel-note">
        {{ channel ? `当前筛选：${channelLabel(channel)}` : '点击渠道名可筛选 / 取消筛选' }}
      </span>
    </div>
    <div v-if="data.channels.length === 0" class="empty">时间窗内暂无调用</div>
    <div v-else class="table-wrap">
      <table class="channel-table">
        <thead>
          <tr>
            <th>渠道</th>
            <th>LLM 调用</th>
            <th>错误</th>
            <th>Tokens</th>
            <th>工具调用</th>
            <th>工具错误</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in data.channels" :key="c.channel">
            <td>
              <button
                type="button"
                class="channel-filter"
                :class="{ active: channel === c.channel }"
                :title="channel === c.channel ? '取消渠道筛选' : `只看 ${channelLabel(c.channel)}`"
                @click="toggleChannel(c.channel)"
              >{{ channelLabel(c.channel) }}</button>
            </td>
            <td class="num">{{ fmtNumber(c.calls) }}</td>
            <td class="num" :class="{ 'cell-bad': c.errors > 0 }">{{ fmtNumber(c.errors) }}</td>
            <td class="num">{{ fmtNumber(c.totalTokens) }}</td>
            <td class="num">{{ fmtNumber(c.toolCalls) }}</td>
            <td class="num" :class="{ 'cell-bad': c.toolErrors > 0 }">{{ fmtNumber(c.toolErrors) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>
