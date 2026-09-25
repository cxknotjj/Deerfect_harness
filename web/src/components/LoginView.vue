/**
 * 登录视图:访问口令 → POST /api/auth/login → 服务端下发 HttpOnly Cookie。
 * 仅在服务端启用登录通道(app.security.password 非空)且当前浏览器未登录时替代主界面;
 * 静态资源公开、门只锁数据接口,故此页本身可匿名加载。
 */
<script setup lang="ts">
import { ref } from 'vue'

defineProps<{ busy?: boolean; error?: string }>()
const emit = defineEmits<{ submit: [password: string] }>()

const password = ref('')

function submit(): void {
  const pwd = password.value
  if (pwd === '') return
  emit('submit', pwd)
}
</script>

<template>
  <main class="login-view">
    <form class="login-card" @submit.prevent="submit">
      <img class="login-logo" src="/deer_logo.png" alt="" />
      <h1 class="login-title">DeerFect HARNESS</h1>
      <p class="login-sub">访问需要口令</p>
      <input
        v-model="password"
        class="login-input"
        type="password"
        placeholder="访问口令"
        autocomplete="current-password"
        aria-label="访问口令"
        :disabled="busy"
      />
      <button class="login-btn" type="submit" :disabled="busy || password === ''">
        {{ busy ? '验证中…' : '进入' }}
      </button>
      <p v-if="error" class="login-error" role="alert">{{ error }}</p>
    </form>
  </main>
</template>
