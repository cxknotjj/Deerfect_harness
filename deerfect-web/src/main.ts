import { createApp } from 'vue'
import App from './App.vue'
// 本地打包字体(@fontsource,构建期依赖运行时零外链):IBM Plex Sans 承载拉丁/数字
// (技术感 UI 配 JetBrains Mono 组成工程控制台字体对),CJK 回退系统字体栈;
// fontsource 按权重引 CSS,woff2 带 unicode-range 子集,按需加载
import '@fontsource/ibm-plex-sans/400.css'
import '@fontsource/ibm-plex-sans/500.css'
import '@fontsource/ibm-plex-sans/600.css'
import '@fontsource/ibm-plex-sans/700.css'
import '@fontsource/jetbrains-mono/400.css'
import '@fontsource/jetbrains-mono/600.css'
import './styles/main.css'
import { reportGlobalError } from './composables/globalError'

// 主题初始化:默认夜间(Telemetry Dark);localStorage 为 light 时切日间。
// 无论哪种主题都同步写 data-theme 与 colorScheme(挂载前执行,防首帧闪烁),
// 否则夜间模式下原生 select 弹层/系统滚动条会按浅色 UA 渲染
const theme = localStorage.getItem('web-theme') === 'light' ? 'light' : 'dark'
document.documentElement.dataset.theme = theme
document.documentElement.style.colorScheme = theme

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
