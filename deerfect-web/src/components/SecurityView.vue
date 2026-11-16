<script setup lang="ts">
/**
 * 安全：管理面访问控制状态（GET /api/auth/me）与登录策略说明。
 *
 * 只展示后端真实暴露的事实：登录通道是否启用、当前浏览器是否已登录；
 * 口令/有效期/锁定策略来自配置默认值（环境变量可能覆盖），页面标注「默认」，
 * 不伪造运行期数值。登出复用 App 的统一处理（emit logout）。
 */
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'
import type { AuthStateView } from '../api'
import PageHeader from './resource/PageHeader.vue'

const emit = defineEmits<{ logout: [] }>()

const state = ref<AuthStateView | null>(null)
const loading = ref(false)
const error = ref('')

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    state.value = await api.authState()
  } catch (e) {
    error.value = `安全状态读取失败：${e instanceof Error ? e.message : String(e)}`
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
})

const enabled = computed(() => state.value?.enabled ?? false)
const authenticated = computed(() => state.value?.authenticated ?? false)

const stats = computed(() => [
  {
    label: '登录通道',
    value: loading.value ? '…' : enabled.value ? '已启用' : '未启用',
  },
  {
    label: '当前浏览器',
    value: loading.value ? '…' : !enabled.value ? '免登录' : authenticated.value ? '已登录' : '未登录',
  },
  { label: '拦截范围', value: '/api/**' },
])

/** 只读策略：默认值来自 application-security.yaml，实际值以部署配置为准 */
const policies = [
  { key: '口令来源', value: '环境变量 APP_PASSWORD（留空 = 不启用登录）' },
  { key: '登录态', value: 'harness_login · HttpOnly · SameSite=Lax · 默认 168 小时滑动续期' },
  { key: '失败锁定', value: '同 IP 连续失败达阈值后短暂锁定（默认 5 次 / 60 秒）' },
  { key: '机器通道', value: '可选 X-API-Token 请求头（APP_SECURITY_API_TOKEN），供 curl / 脚本直连' },
  { key: '豁免路径', value: '/api/auth/**、/onebot/**、/error 与静态资源' },
]
</script>

<template>
  <section class="res-page" aria-label="安全">
    <div class="res-inner">
      <PageHeader eyebrow="系统" title="安全" sub="管理面访问控制状态与登录策略" />

      <p v-if="error" class="res-alert" role="alert">{{ error }}</p>

      <div class="settings-stats">
        <div v-for="stat in stats" :key="stat.label" class="res-stat">
          <span class="res-stat-label">{{ stat.label }}</span>
          <span class="res-stat-value">{{ stat.value }}</span>
        </div>
      </div>

      <section class="res-panel" aria-label="登录状态">
        <div class="res-panel-head">
          <div>
            <h2 class="res-panel-title">登录状态</h2>
            <p class="res-panel-sub">
              {{ enabled
                ? '浏览器持有 HttpOnly 登录 Cookie；关闭浏览器不会立即失效，按有效期滑动续期'
                : '未设置 APP_PASSWORD，本机免登录；公网部署建议配置后重启' }}
            </p>
          </div>
          <button
            class="res-btn"
            type="button"
            :disabled="!enabled || !authenticated"
            @click="emit('logout')"
          >退出登录</button>
        </div>
        <div class="settings-rows">
          <div class="settings-row">
            <div class="settings-row-text">
              <span class="settings-row-title">登录通道</span>
              <span class="settings-row-desc">由 app.security.password 是否为空决定，改动需重启生效</span>
            </div>
            <span class="res-chip" :class="enabled ? 'res-chip-ok' : ''">{{ enabled ? '已启用' : '未启用' }}</span>
          </div>
          <div class="settings-row">
            <div class="settings-row-text">
              <span class="settings-row-title">当前会话</span>
              <span class="settings-row-desc">登录态保存在服务端内存，重启后需要重新登录</span>
            </div>
            <span class="res-chip">{{ !enabled ? '免登录' : authenticated ? '已登录' : '未登录' }}</span>
          </div>
        </div>
      </section>

      <section class="res-panel" aria-label="策略与来源">
        <div class="res-panel-head">
          <div>
            <h2 class="res-panel-title">策略与来源</h2>
            <p class="res-panel-sub">标注「默认」的项以 application-security.yaml 为准，环境变量可覆盖</p>
          </div>
        </div>
        <div class="settings-rows">
          <div v-for="item in policies" :key="item.key" class="settings-row">
            <span class="settings-row-title settings-row-key">{{ item.key }}</span>
            <span class="settings-row-value">{{ item.value }}</span>
          </div>
        </div>
      </section>
    </div>
  </section>
</template>
