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

// 主题初始化:默认夜间(Telemetry Dark);localStorage 为 light 时切日间。
// 无论哪种主题都同步写 data-theme 与 colorScheme(挂载前执行,防首帧闪烁),
// 否则夜间模式下原生 select 弹层/系统滚动条会按浅色 UA 渲染
const theme = localStorage.getItem('web-theme') === 'light' ? 'light' : 'dark'
document.documentElement.dataset.theme = theme
document.documentElement.style.colorScheme = theme

createApp(App).mount('#app')
