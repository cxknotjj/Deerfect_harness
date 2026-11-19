// DeerFect 构建：退役旧 Service Worker。
// 旧 SW 的导航回退会把指向已删除哈希资源的旧壳返回给刷新请求（黑屏），
// 本脚本被 SW 更新检查拉取后清空缓存并注销自身，后续导航重新走网络。
self.addEventListener('install', () => {
  self.skipWaiting()
})
self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    const keys = await caches.keys()
    await Promise.all(keys.map((key) => caches.delete(key)))
    await self.registration.unregister()
    const clients = await self.clients.matchAll({ type: 'window' })
    await Promise.all(clients.map((client) => client.navigate(client.url)))
  })())
})
