# Web 端 TODO

> 已规划未实施的优化项,按优先级排列。纯前端项可随时做;标注「需后端」的等真模式联调时一起。

## 功能补全

- [x] **真模式联调**(已完成 2026-09-20):后端 `GET /api/harness/agents` 返回 agent 表真实主键(`AgentItemView(id, name)`,is_internal=0 按 id 升序),前端 `AgentView` 改对象结构、下拉与绑定全部用真实 id;SSE 真实流式 + agent 切换绑定已浏览器端到端验证(general/deepseek 双模型自述正确)
- [x] **会话历史加载**(已完成 2026-09-20):后端补 `GET /api/harness/sessions/{id}/messages`(`SessionMessagesView`,复用会话上下文快照解析为 role/content 列表);前端切回旧会话先渲染本地缓存、再拉服务端历史覆盖
- [x] **重新生成**(已完成 2026-09-20):最后一条 assistant 回复操作栏加「重新生成」,复用其上一条 user 消息原地重跑(不新增 user 气泡;生成中禁用)
- [x] **会话本地缓存**(已完成 2026-09-20):消息按会话存入 `localStorage`(`harness-chat-cache`),刷新页面/切回旧会话即时渲染;单会话限 200 条、最多 20 个会话,容量不足自动淘汰;进度与错误态不入库

## 体验增强

- [x] **窄屏响应式**(已完成 2026-09-20):≤768px 侧栏转抽屉模式(`min(300px, 85vw)` 离屏 + 遮罩 + transform 滑入滑出),选中/新建会话与 Escape/遮罩点击自动收起;桌面内联折叠不变,`prefers-reduced-motion` 降级为直切,双主题遮罩 token 适配
- [ ] **键盘可达性**:会话项目前是 `li` click,无 `tabindex` / Enter 选中;补全焦点态与键盘操作

## 工程健壮

- [ ] **web 端单测**:useChat / useSessions 等组合式逻辑零测试,引入 vitest;并进 CI(与 CI 条目合并跟进)
- [ ] **部署方案**:web/dist 生产托管路径未定(nginx 或 server 静态托管),连同 CI/CD 条目一起规划
- [ ] **轨迹观测增强(turn_id / trace_id,需后端,影响大暂缓)**:llm_call_log / tool_call_log 加轮次与调用关联标识(「N 轮 M 步」统计、条带按轮分段、跨 agent 调用树)。需穿透 ChatService→Agent→ChatClient→工具回调全链路传递上下文(Reactor context + ThreadLocal 桥接),跨 CLI/QQ/web 三入口,影响面大——以后另立 spec 实施前置:本批三列(output_summary / first_token_ms / cached_tokens)落地(spec 见 `.trae/specs/add-trace-observability-columns/`)
