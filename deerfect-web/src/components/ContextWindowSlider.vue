<script setup lang="ts">
/**
 * 模型上下文窗口滑块（设置 → 模型管理）：离散档位吸附，停手 500ms 防抖上报 save。
 * 只负责交互与展示，不发请求；保存/回滚由父组件承担。窗口是模型能力声明，非请求预算。
 */
import { computed, ref, watch } from 'vue'

const props = defineProps<{ modelValue: number }>()
const emit = defineEmits<{ save: [value: number] }>()

/** 非线性档位：索引定位、按档位值保存，避免设出无意义的中间值 */
const TIERS = [
  { value: 8192, display: '8K', label: '精简' },
  { value: 32768, display: '32K', label: '基础' },
  { value: 65536, display: '64K', label: '标准' },
  { value: 131072, display: '128K', label: '适中' },
  { value: 262144, display: '256K', label: '充裕' },
  { value: 600000, display: '600K', label: '加大' },
  { value: 1048576, display: '1M', label: '超大' },
] as const

function nearestIndex(value: number): number {
  if (value <= 0) return 3
  let best = 0
  let bestDist = Number.POSITIVE_INFINITY
  TIERS.forEach((t, i) => {
    const d = Math.abs(t.value - value)
    if (d < bestDist) {
      bestDist = d
      best = i
    }
  })
  return best
}

const idx = ref(nearestIndex(props.modelValue))
watch(() => props.modelValue, (v) => { idx.value = nearestIndex(v) })

const tier = computed(() => TIERS[idx.value])
const isExact = computed(() => props.modelValue === tier.value.value)

let timer: ReturnType<typeof setTimeout> | undefined
function onInput(): void {
  clearTimeout(timer)
  timer = setTimeout(() => emit('save', tier.value.value), 500)
}
</script>

<template>
  <div class="ctx-slider">
    <div class="ctx-slider-readout">
      <strong v-if="modelValue > 0 || isExact">{{ tier.display }} 上下文 · {{ tier.label }}</strong>
      <strong v-else>未声明上下文 · 拖动设置</strong>
      <span v-if="modelValue > 0 && !isExact" class="ctx-slider-actual">
        当前声明 {{ modelValue.toLocaleString() }} tok，保存后对齐档位
      </span>
    </div>
    <input
      v-model.number="idx"
      class="ctx-slider-input"
      type="range"
      :min="0"
      :max="TIERS.length - 1"
      step="1"
      aria-label="模型上下文窗口"
      :aria-valuetext="`${tier.display}，${tier.label}`"
      @input="onInput"
    />
    <div class="ctx-slider-ends" aria-hidden="true">
      <span>{{ TIERS[0].display }}</span>
      <span>{{ TIERS[TIERS.length - 1].display }}</span>
    </div>
  </div>
</template>

<style scoped>
.ctx-slider {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px 16px;
  border: 1px solid var(--border, #2a3441);
  border-radius: 12px;
  background: var(--panel, #121821);
}
.ctx-slider-readout {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  text-align: center;
}
.ctx-slider-readout strong {
  font-size: 15px;
  font-weight: 600;
}
.ctx-slider-actual {
  font-size: 12px;
  opacity: 0.7;
}
.ctx-slider-input {
  -webkit-appearance: none;
  appearance: none;
  width: 100%;
  height: 14px;
  border-radius: 999px;
  background: linear-gradient(90deg, #17b890, #2fd6a8);
  outline: none;
  cursor: pointer;
}
.ctx-slider-input::-webkit-slider-thumb {
  -webkit-appearance: none;
  appearance: none;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: #fff;
  border: none;
  box-shadow: 0 1px 4px rgb(0 0 0 / 0.4);
}
.ctx-slider-input::-moz-range-thumb {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: #fff;
  border: none;
  box-shadow: 0 1px 4px rgb(0 0 0 / 0.4);
}
.ctx-slider-input:focus-visible {
  box-shadow: 0 0 0 3px rgb(47 214 168 / 0.35);
}
.ctx-slider-ends {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  opacity: 0.6;
}
</style>
