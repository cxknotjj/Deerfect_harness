import { createApp } from 'vue'
import App from './App.vue'
import './styles/main.css'

// 主题初始化:默认夜间(Telemetry Dark);localStorage 为 light 时切日间。
// 无论哪种主题都同步写 data-theme 与 colorScheme(挂载前执行,防首帧闪烁),
// 否则夜间模式下原生 select 弹层/系统滚动条会按浅色 UA 渲染
const theme = localStorage.getItem('web-theme') === 'light' ? 'light' : 'dark'
document.documentElement.dataset.theme = theme
document.documentElement.style.colorScheme = theme

createApp(App).mount('#app')
