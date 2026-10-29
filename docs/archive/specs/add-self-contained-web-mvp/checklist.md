# Checklist

- [x] `web/` 为完全自包含工程:`npm install && npm run dev` 不依赖 Maven 与项目其他文件即可启动(node 22.14.0 免 sudo 装至 ~/.local/share/node)
- [x] TS 类型独立定义,代码中无对 shared/server/cli 源码的任何引用(grep 复核零命中)
- [x] `VITE_USE_MOCK=true` 且 server 未启动时,可完成会话列表、新建会话、agent 切换与一轮流式对话(浏览器回归验证)
- [x] 流式聊天实现 SSE 帧解析(token 打字机追加、progress 徽标、error 提示、[DONE] 收尾),协议经 server/cli 源码核实(事件名/转义/收尾顺序)
- [x] markdown 渲染经 DOMPurify 净化,无 XSS 风险
- [x] 会话列表支持分页、新建、选中切换;agent 下拉切换调用绑定接口(消息按会话持久)
- [x] dev 模式经 vite proxy 同域转发 `/api`,API Key 经环境变量注入且不硬编码(server 实测无强制鉴权,X-API-Key 为预留)
- [x] 运行时依赖仅 vue + marked + dompurify(无 pinia/router/组件库)
- [x] `npm run build` 产物为纯静态文件,vite preview 托管 HTTP 200
- [x] `.gitignore` 覆盖 node_modules/dist,git check-ignore 通过
- [ ] mock 开/关两种模式各完成一轮完整回归(mock 模式全过;真模式待本地 server + DB 就绪后联调)
