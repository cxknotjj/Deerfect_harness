<script setup lang="ts">
/**
 * 设置·语音合成：语音服务状态 + 默认语音参数。
 *
 * 后端只有 Edge TTS（`text_to_speech` 工具，配置在 application-tts.yaml / TTS_PYTHON），
 * 没有服务商管理或参数下发接口。因此：
 * - 服务商卡片只展示真实探测结果（capabilities），不摆 Azure/火山的假 API Key 输入；
 * - 默认语音参数保存在本机，仅用于「试听示例」（浏览器 Web Speech），
 *   页面明确标注服务端合成仍按 application-tts.yaml 执行。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { api } from '../../api'
import type { CapabilityEntry } from '../../api'
import { UI_KEYS } from '../../utils/uiPrefs'

interface TtsPrefs {
  voice: string
  backupVoice: string
  format: string
  rate: '0.8' | '1' | '1.2'
  lang: string
  pitch: 'low' | 'normal' | 'high'
}

const DEFAULT_PREFS: TtsPrefs = {
  voice: 'zh-CN-XiaoxiaoNeural',
  backupVoice: 'zh-CN-YunxiNeural',
  format: 'mp3-48',
  rate: '1',
  lang: 'zh-CN',
  pitch: 'normal',
}

function initialPrefs(): TtsPrefs {
  try {
    const raw = localStorage.getItem(UI_KEYS.ttsPrefs)
    if (!raw) return { ...DEFAULT_PREFS }
    return { ...DEFAULT_PREFS, ...(JSON.parse(raw) as Partial<TtsPrefs>) }
  } catch {
    return { ...DEFAULT_PREFS }
  }
}

const prefs = reactive<TtsPrefs>(initialPrefs())
const note = ref('')
const entries = ref<CapabilityEntry[]>([])
const loading = ref(false)
const error = ref('')

const ttsEntry = computed(() =>
  entries.value.find((e) => e.pack === 'text_to_speech' || e.tools.includes('text_to_speech')),
)
const ttsReady = computed(() => ttsEntry.value?.state === 'ready')
const speechSupported = computed(() => 'speechSynthesis' in window)

watch(prefs, () => {
  localStorage.setItem(UI_KEYS.ttsPrefs, JSON.stringify(prefs))
  note.value = '参数已保存到本机（试听立即生效）'
})

const voices = [
  { value: 'zh-CN-XiaoxiaoNeural', label: '女声 · 晓晓（普通话）' },
  { value: 'zh-CN-YunxiNeural', label: '男声 · 云希（普通话）' },
  { value: 'zh-CN-XiaoyiNeural', label: '女声 · 晓伊（普通话）' },
  { value: 'en-US-AriaNeural', label: '女声 · Aria（英语）' },
]

const formats = [
  { value: 'mp3-48', label: 'MP3 · 48 kHz' },
  { value: 'mp3-24', label: 'MP3 · 24 kHz' },
  { value: 'wav', label: 'WAV · 无损' },
]

const langs = [
  { value: 'zh-CN', label: '中文（简体）' },
  { value: 'en-US', label: 'English (US)' },
]

function speak(): void {
  note.value = ''
  if (!speechSupported.value) {
    note.value = '当前浏览器不支持 Web Speech，无法试听'
    return
  }
  const utter = new SpeechSynthesisUtterance('你好，这是语音合成试听示例。')
  utter.lang = prefs.lang
  utter.rate = Number(prefs.rate)
  utter.pitch = prefs.pitch === 'high' ? 1.3 : prefs.pitch === 'low' ? 0.7 : 1
  const match = window.speechSynthesis.getVoices().find((v) => v.lang === prefs.lang)
  if (match) utter.voice = match
  window.speechSynthesis.cancel()
  window.speechSynthesis.speak(utter)
  note.value = '正在用浏览器语音朗读（试听音色与服务端 Edge TTS 可能不同）'
}

onMounted(async () => {
  loading.value = true
  try {
    const report = await api.capabilities()
    entries.value = report.entries
  } catch (e) {
    error.value = `能力清单读取失败：${e instanceof Error ? e.message : String(e)}`
  } finally {
    loading.value = false
  }
})

defineExpose({ speak, speechSupported })
</script>

<template>
  <p v-if="error" class="res-alert" role="alert">{{ error }}</p>
  <p v-else-if="note" class="res-note" role="status">{{ note }}</p>

  <section class="setting-group">
    <h3 class="setting-group-title">
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d="M11 5 6 9H3v6h3l5 4z" />
        <path d="M16 9a4 4 0 0 1 0 6" />
        <path d="M19 6a8 8 0 0 1 0 12" />
      </svg>
      语音服务
    </h3>
    <div class="tts-grid">
      <article class="tts-card">
        <header class="tts-card-head">
          <span class="tts-mark" aria-hidden="true">E</span>
          <div class="tts-title">
            <span class="tts-name">Edge TTS</span>
            <span class="tts-code">edge-tts · 服务端</span>
          </div>
          <span class="res-chip" :class="ttsReady ? 'res-chip-ok' : 'res-chip-warn'">
            {{ loading ? '检测中…' : ttsReady ? '已接入' : '未检测到' }}
          </span>
        </header>
        <p class="tts-count">
          可用 {{ ttsEntry?.tools.length ?? 0 }} 个工具
          <span class="tts-count-total">/ {{ ttsEntry?.location ?? 'TextToSpeechTools' }}</span>
        </p>
        <div class="tts-cred">
          <span class="tts-cred-label">运行依赖</span>
          <code class="tts-cred-value">TTS_PYTHON · edge-tts · ffmpeg</code>
          <span class="tts-cred-note">语音名/语速/音量由 application-tts.yaml 决定</span>
        </div>
        <div class="tts-actions">
          <button class="res-btn res-btn-sm" type="button" :disabled="!speechSupported" @click="speak">试听示例</button>
          <span class="tts-hint">试听使用浏览器语音</span>
        </div>
      </article>

      <article class="tts-card is-muted">
        <header class="tts-card-head">
          <span class="tts-mark" aria-hidden="true">+</span>
          <div class="tts-title">
            <span class="tts-name">更多服务商</span>
            <span class="tts-code">Azure / 火山引擎 / Piper</span>
          </div>
          <span class="res-chip">未接入</span>
        </header>
        <p class="tts-count">后端暂无这些服务商的适配与凭证接口</p>
        <p class="tts-desc">
          当前实例只提供 Edge TTS。要支持多服务商切换，需要后端新增
          provider 配置与试听接口，前端再补保存与连通性测试。
        </p>
      </article>
    </div>
  </section>

  <section class="setting-group">
    <h3 class="setting-group-title">
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d="M4 6h16M4 12h16M4 18h10" />
      </svg>
      默认语音参数
      <em class="setting-group-hint">仅本机试听偏好，服务端合成仍按 application-tts.yaml</em>
    </h3>
    <div class="setting-card setting-card-grid">
      <div class="setting-row">
        <span class="setting-row-label">默认音色</span>
        <select v-model="prefs.voice" class="setting-select" aria-label="默认音色">
          <option v-for="v in voices" :key="v.value" :value="v.value">{{ v.label }}</option>
        </select>
      </div>
      <div class="setting-row">
        <span class="setting-row-label">备用音色</span>
        <select v-model="prefs.backupVoice" class="setting-select" aria-label="备用音色">
          <option v-for="v in voices" :key="v.value" :value="v.value">{{ v.label }}</option>
        </select>
      </div>
      <div class="setting-row">
        <span class="setting-row-label">输出格式</span>
        <select v-model="prefs.format" class="setting-select" aria-label="输出格式">
          <option v-for="f in formats" :key="f.value" :value="f.value">{{ f.label }}</option>
        </select>
      </div>
      <div class="setting-row">
        <span class="setting-row-label">语速</span>
        <div class="setting-seg" role="group" aria-label="语速">
          <button type="button" :class="{ 'is-active': prefs.rate === '0.8' }" @click="prefs.rate = '0.8'">0.8×</button>
          <button type="button" :class="{ 'is-active': prefs.rate === '1' }" @click="prefs.rate = '1'">1.0×</button>
          <button type="button" :class="{ 'is-active': prefs.rate === '1.2' }" @click="prefs.rate = '1.2'">1.2×</button>
        </div>
      </div>
      <div class="setting-row">
        <span class="setting-row-label">默认语言</span>
        <select v-model="prefs.lang" class="setting-select" aria-label="默认语言">
          <option v-for="l in langs" :key="l.value" :value="l.value">{{ l.label }}</option>
        </select>
      </div>
      <div class="setting-row">
        <span class="setting-row-label">音调</span>
        <div class="setting-seg" role="group" aria-label="音调">
          <button type="button" :class="{ 'is-active': prefs.pitch === 'low' }" @click="prefs.pitch = 'low'">低</button>
          <button type="button" :class="{ 'is-active': prefs.pitch === 'normal' }" @click="prefs.pitch = 'normal'">标准</button>
          <button type="button" :class="{ 'is-active': prefs.pitch === 'high' }" @click="prefs.pitch = 'high'">高</button>
        </div>
      </div>
    </div>
  </section>
</template>
