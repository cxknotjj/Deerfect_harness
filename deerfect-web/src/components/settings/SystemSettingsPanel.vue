<script setup lang="ts">
/**
 * 设置·系统设置：界面偏好 / 运行时行为 / 本地数据。
 *
 * 能真实生效的项（主题、字号缩放、侧栏密度、恢复上次会话、自动检查更新、清理缓存）
 * 立即应用；无后端接口的项（语言、日志级别、匿名统计、工作目录）只读展示并注明来源，
 * 不放点了没反应的假开关。
 */
import { computed, reactive, ref, watch } from 'vue'
import {
  UI_KEYS,
  applyDensity,
  applyZoom,
  readBool,
  writeBool,
  type Density,
  type ThemeMode,
  type ZoomScale,
} from '../../utils/uiPrefs'

const props = defineProps<{ themeMode: ThemeMode }>()
const emit = defineEmits<{ 'set-theme-mode': [mode: ThemeMode] }>()

interface Draft {
  themeMode: ThemeMode
  zoom: ZoomScale
  density: Density
  restoreSession: boolean
  autoUpdate: boolean
}

function initialDraft(): Draft {
  return {
    themeMode: props.themeMode,
    zoom: (localStorage.getItem(UI_KEYS.zoom) as ZoomScale) || '1',
    density: (localStorage.getItem(UI_KEYS.density) as Density) || 'standard',
    restoreSession: readBool(UI_KEYS.restoreSession, true),
    autoUpdate: readBool(UI_KEYS.autoUpdate, true),
  }
}

const draft = reactive<Draft>(initialDraft())
const dirty = ref(false)
const note = ref('')
const error = ref('')

// 主题即时生效；字号/密度即时预览，保存时才落 localStorage
watch(() => draft.themeMode, (mode) => emit('set-theme-mode', mode))
watch(() => draft.zoom, (zoom) => applyZoom(zoom))
watch(() => draft.density, (density) => applyDensity(density))
watch(draft, () => {
  dirty.value = true
  note.value = ''
  error.value = ''
})

const zoomOptions: { value: ZoomScale; label: string }[] = [
  { value: '0.9', label: '90%' },
  { value: '1', label: '100%' },
  { value: '1.1', label: '110%' },
]

const themeOptions: { value: ThemeMode; label: string }[] = [
  { value: 'light', label: '浅色' },
  { value: 'dark', label: '深色' },
  { value: 'system', label: '跟随系统' },
]

function save(): void {
  localStorage.setItem(UI_KEYS.zoom, draft.zoom)
  localStorage.setItem(UI_KEYS.density, draft.density)
  writeBool(UI_KEYS.restoreSession, draft.restoreSession)
  writeBool(UI_KEYS.autoUpdate, draft.autoUpdate)
  if (!draft.restoreSession) localStorage.removeItem(UI_KEYS.lastSession)
  emit('set-theme-mode', draft.themeMode)
  window.dispatchEvent(new Event('ui-prefs-changed'))
  dirty.value = false
  note.value = '设置已保存到本机'
}

function restoreDefaults(): void {
  draft.themeMode = 'dark'
  draft.zoom = '1'
  draft.density = 'standard'
  draft.restoreSession = true
  draft.autoUpdate = true
  save()
  note.value = '已恢复默认设置'
}

async function clearCache(): Promise<void> {
  error.value = ''
  try {
    if ('caches' in window) {
      const keys = await caches.keys()
      await Promise.all(keys.map((key) => caches.delete(key)))
    }
    // 清业务缓存但保留界面偏好与会话记忆，避免用户设置被一并清掉
    const keep = new Set<string>([
      UI_KEYS.themeMode,
      'web-theme',
      UI_KEYS.zoom,
      UI_KEYS.density,
      UI_KEYS.restoreSession,
      UI_KEYS.autoUpdate,
      UI_KEYS.lastSession,
      UI_KEYS.ttsPrefs,
      'monitor-tab',
      'monitor-details-open',
      'ui-tts-prefs',
    ])
    for (const key of Object.keys(localStorage)) {
      if (!keep.has(key)) localStorage.removeItem(key)
    }
    note.value = '缓存已清理，偏好设置已保留'
  } catch (e) {
    error.value = `清理失败：${e instanceof Error ? e.message : String(e)}`
  }
}

async function checkUpdate(): Promise<void> {
  if (!('serviceWorker' in navigator)) {
    note.value = '当前环境未启用 Service Worker（开发模式）'
    return
  }
  const reg = await navigator.serviceWorker.getRegistration()
  if (!reg) {
    note.value = '当前环境未注册 Service Worker'
    return
  }
  await reg.update()
  note.value = '已向服务端检查更新'
}

const cacheReady = computed(() => 'caches' in window)

defineExpose({ save, restoreDefaults })
</script>

<template>
  <p v-if="error" class="res-alert" role="alert">{{ error }}</p>
  <p v-else-if="note" class="res-note" role="status">{{ note }}</p>
  <p v-else-if="dirty" class="res-note" role="status">有未保存的更改</p>

  <section class="setting-group">
    <h3 class="setting-group-title">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 5h16v14H4z" /><path d="M4 9h16" /></svg>
      界面
    </h3>
    <div class="setting-card">
      <div class="setting-row">
        <span class="setting-row-label">界面语言</span>
        <span class="setting-row-value">简体中文 <em>当前版本仅中文</em></span>
      </div>
      <div class="setting-row">
        <span class="setting-row-label">主题外观</span>
        <div class="setting-seg" role="group" aria-label="主题外观">
          <button
            v-for="opt in themeOptions"
            :key="opt.value"
            type="button"
            :class="{ 'is-active': draft.themeMode === opt.value }"
            @click="draft.themeMode = opt.value"
          >{{ opt.label }}</button>
        </div>
      </div>
      <div class="setting-row">
        <span class="setting-row-label">字号缩放</span>
        <div class="setting-seg" role="group" aria-label="字号缩放">
          <button
            v-for="opt in zoomOptions"
            :key="opt.value"
            type="button"
            :class="{ 'is-active': draft.zoom === opt.value }"
            @click="draft.zoom = opt.value"
          >{{ opt.label }}</button>
        </div>
      </div>
      <div class="setting-row">
        <span class="setting-row-label">侧栏密度</span>
        <div class="setting-seg" role="group" aria-label="侧栏密度">
          <button type="button" :class="{ 'is-active': draft.density === 'compact' }" @click="draft.density = 'compact'">紧凑</button>
          <button type="button" :class="{ 'is-active': draft.density === 'standard' }" @click="draft.density = 'standard'">标准</button>
        </div>
      </div>
    </div>
  </section>

  <section class="setting-group">
    <h3 class="setting-group-title">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M13 3 4 14h6l-1 7 9-11h-6z" /></svg>
      运行时
    </h3>
    <div class="setting-card">
      <div class="setting-row">
        <span class="setting-row-label">启动时恢复上次会话</span>
        <label class="setting-switch">
          <input v-model="draft.restoreSession" type="checkbox" aria-label="启动时恢复上次会话" />
        </label>
      </div>
      <div class="setting-row">
        <span class="setting-row-label">
          自动检查更新
          <em class="setting-row-hint">每 30 分钟向 Service Worker 检查</em>
        </span>
        <div class="setting-row-actions">
          <button class="res-btn res-btn-sm" type="button" @click="checkUpdate">立即检查</button>
          <label class="setting-switch">
            <input v-model="draft.autoUpdate" type="checkbox" aria-label="自动检查更新" />
          </label>
        </div>
      </div>
      <div class="setting-row">
        <span class="setting-row-label">运行日志级别</span>
        <span class="setting-row-value">常规 <em>application.yaml · logging.level.root</em></span>
      </div>
      <div class="setting-row">
        <span class="setting-row-label">匿名使用统计</span>
        <span class="setting-row-value">未启用 <em>本实例不采集</em></span>
      </div>
    </div>
  </section>

  <section class="setting-group">
    <h3 class="setting-group-title">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2z" /></svg>
      本地数据
    </h3>
    <div class="setting-card">
      <div class="setting-row">
        <span class="setting-row-label">工作目录</span>
        <span class="setting-row-value res-mono">服务端进程目录 <em>随部署而定，Web 端不可见</em></span>
      </div>
      <div class="setting-row">
        <span class="setting-row-label">缓存管理</span>
        <span class="setting-row-actions">
          <em class="setting-row-hint">{{ cacheReady ? '清理 Service Worker 与本地缓存，保留偏好' : '当前环境无 Cache Storage' }}</em>
          <button class="res-btn res-btn-sm" type="button" :disabled="!cacheReady" @click="clearCache">清理缓存</button>
        </span>
      </div>
    </div>
  </section>
</template>
