import { createApp } from 'vue'
import App from './App.vue'
// 本地打包字体(@fontsource,构建期依赖运行时零外链):Inter 承载拉丁/数字/品牌字
// (几何中性无衬线,数字 0 为窄椭圆,配 JetBrains Mono 承担代码与标识),CJK 走系统字体栈
// (PingFang / HarmonyOS Sans / Source Han / Noto / YaHei);只引 latin 子集,体积最小
import '@fontsource/inter/latin-400.css'
import '@fontsource/inter/latin-500.css'
import '@fontsource/inter/latin-600.css'
import '@fontsource/inter/latin-700.css'
import '@fontsource/jetbrains-mono/400.css'
import '@fontsource/jetbrains-mono/600.css'
import './styles/main.css'
import { reportGlobalError } from './composables/globalError'
import { loadUiPrefs } from './utils/uiPrefs'

// 主题初始化:默认夜间(Telemetry Dark);web-theme-mode 为 light/dark 时固定,
// 为 system 时跟随 prefers-color-scheme。挂载前写 data-theme 与 colorScheme,防首帧闪烁
// (否则夜间模式下原生 select 弹层/系统滚动条会按浅色 UA 渲染)
function initialTheme(): 'light' | 'dark' {
  const mode = localStorage.getItem('web-theme-mode')
  if (mode === 'system') {
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
  }
  if (mode === 'light' || mode === 'dark') return mode
  return localStorage.getItem('web-theme') === 'light' ? 'light' : 'dark'
}
const theme = initialTheme()
document.documentElement.dataset.theme = theme
document.documentElement.style.colorScheme = theme

// 字号缩放 / 侧栏密度同样在挂载前应用
loadUiPrefs()

const app = createApp(App)

// 全局兜底：根组件自身与组件树之外的异常落到这里（子树异常由 App.vue 的
// onErrorCaptured 先接住）。没有它时，一次渲染抛错就只剩白屏，用户无从判断。
app.config.errorHandler = (err, _instance, info) => {
  reportGlobalError(err, `渲染异常（${info}）`)
}

// 未捕获的 Promise 拒绝：异步链路（加载会话、发送消息）最常见的「静默失败」
window.addEventListener('unhandledrejection', (event) => {
  reportGlobalError(event.reason, '未处理的异步错误')
})

app.mount('#app')
