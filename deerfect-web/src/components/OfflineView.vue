/**
 * 离线门:断网时替代主界面。service worker 预缓存了应用壳,所以页面本身能启动,
 * 这里明确告知「离线」并提供重连,避免白屏或带着初始化失败的空壳继续用。
 */
<script setup lang="ts">
defineEmits<{ retry: [] }>()
</script>

<template>
  <main class="offline-view">
    <div class="offline-card">
      <img class="offline-logo" src="/deer_logo.png" alt="" />
      <h1 class="offline-title">当前离线</h1>
      <p class="offline-sub">网络不可用，正在使用本地缓存的应用外壳</p>
      <button class="offline-btn" type="button" @click="$emit('retry')">重新连接</button>
    </div>
  </main>
</template>
