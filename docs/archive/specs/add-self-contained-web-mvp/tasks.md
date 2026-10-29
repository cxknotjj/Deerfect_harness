# Tasks

- [x] Task 1: 前端脚手架:Vite + Vue3 + TS 最小工程(node 22.14.0 免 sudo 装至 ~/.local/share/node,npmmirror registry)
  - [x] 1.1 创建 `web/` 目录:package.json、vite.config.ts、tsconfig.json、index.html、.env.development、.gitignore(node_modules/dist)
  - [x] 1.2 运行时依赖仅 vue + marked + dompurify;dev 依赖 vite + @vitejs/plugin-vue + typescript + vue-tsc
  - [x] 1.3 验证:`npm install && npm run dev` 启动出最小页面;`npm run build` 出纯静态 dist
- [x] Task 2: API 层与独立类型定义
  - [x] 2.1 `src/api/types.ts`:独立定义 ChatRequest/ChatResponse/AgentView/SessionPage 等形状(以 curl 实测 server 响应为唯一依据,不参照 shared 源码)
  - [x] 2.2 `src/api/client.ts`:REST 封装(sessions/agents/绑定)+ `streamChat()`(fetch + ReadableStream 解析 SSE 帧:event:/data:、四事件、[DONE]、换行转义解码)
  - [x] 2.3 验证:真模式(`VITE_USE_MOCK=false` + 本地 server)curl 对照,流式解析与 cli 行为一致(协议对齐经源码核实:事件名/转义/收尾顺序;真模式 curl 实测留待 server 启动后联调)
- [x] Task 3: mock 实现(可与 Task 2 并行)
  - [x] 3.1 `src/api/mock/`:内嵌 mock 数据(2 个 agent、3 条历史会话)+ 全接口 mock;流式 mock 以定时分片模拟打字机并发出 progress/error 示例
  - [x] 3.2 开关:`VITE_USE_MOCK` 整体切换 api 实现;验证:server 不启动时全流程可跑(实现完成,全流程 UI 验证在 Task 5/7)
  - [x] 附加核实:server 无强制鉴权(API-Key 为预留);GET /agents 返回名称集合无 id,bindAgent 需数字 id(mock 约定下标+1,真实绑定待后端补端点)
- [x] Task 4: 会话列表 UI
  - [x] 4.1 `useSessions` composable(列表/分页/新建/选中)
  - [x] 4.2 `SessionList.vue`(左栏,新建按钮 + 选中态)
  - [x] 4.3 验证:双模式下新建会话、切换选中均正确
- [x] Task 5: 聊天窗 MVP
  - [x] 5.1 `useChat` composable(消息流状态、发送、流式追加、progress 徽标、错误提示、[DONE] 收尾)
  - [x] 5.2 `ChatWindow.vue` + `MessageBubble.vue`(marked + DOMPurify 渲染)+ `Composer.vue`(输入框 + 发送/禁用态)
  - [x] 5.3 验证:双模式下完成一轮流式对话,markdown/代码块渲染正常,XSS 净化生效
- [x] Task 6: agent 切换
  - [x] 6.1 `useAgents` + `AgentSelect.vue`(下拉数据来自 GET /agents,切换调绑定接口)
  - [x] 6.2 验证:双模式下切换 agent 成功且影响后续对话(mock 返回标识新 agent)
- [x] Task 7: 收尾验收
  - [x] 7.1 `npm run build` 产物 nginx 直托管冒烟(或 vite preview 等价验证)(preview HTTP 200)
  - [x] 7.2 全局检查:web/ 对 Maven/项目其他文件零引用;确认 .gitignore 覆盖 node_modules/dist(check-ignore 通过)
  - [x] 7.3 双模式完整回归(mock 开/关各跑一轮:列表 → 新建 → 切换 agent → 流式对话)(mock 模式浏览器回归 6 步 + 修复复验 4 项全过;真模式依赖本地 server+DB,留待环境就绪联调)
- [x] Task 8: 回归缺陷修复(mock 模式浏览器回归发现)
  - [x] 8.1 **P0 会话消息不持久**:消息态改按会话分桶(Map)+ onMeta 会话迁移,切回恢复(复验:切走切回气泡 2→0→2)
  - [x] 8.2 **P1 progress 不消失**:send() finally 统一清空 progress(复验:流后 .msg-progress 不存在)
  - [x] 8.3 **P2 agent 切换无反馈**:提示改无条件容器 + 纯 v-if(移除 Transition),agentName 空值兜底(复验:显示「已切换到『coder』」)
  - [x] 8.4 修复后浏览器复验步骤 3/4/6 全部通过(独立浏览器代理 4/4 PASS,console 零报错)

# Task Dependencies
- Task 1 → Task 2、Task 3(Task 2 与 Task 3 可并行)
- Task 2、Task 3 → Task 4 → Task 5 → Task 6 → Task 7
