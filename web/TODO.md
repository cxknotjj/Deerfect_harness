# Web 端 TODO

> 已规划未实施的优化项,按优先级排列。纯前端项可随时做;标注「需后端」的等真模式联调时一起。
> 2026-11-06 按代码核对：前端工程现状见 [web/README.md](README.md) 与 [docs/dev.md](../docs/dev.md) 的「前端运行要求」「前端开发与验证」。

## 功能补全

- [x] **真模式联调**(已完成 2026-09-20):后端 `GET /api/harness/agents` 返回 agent 表真实主键(`AgentItemView(id, name)`,is_internal=0 按 id 升序),前端 `AgentView` 改对象结构、下拉与绑定全部用真实 id;SSE 真实流式 + agent 切换绑定已浏览器端到端验证(general/deepseek 双模型自述正确)
- [x] **会话历史加载**(已完成 2026-09-20):后端补 `GET /api/harness/sessions/{id}/messages`(`SessionMessagesView`,复用会话上下文快照解析为 role/content 列表);前端切回旧会话先渲染本地缓存、再拉服务端历史覆盖
- [x] **重新生成**(已完成 2026-09-20):最后一条 assistant 回复操作栏加「重新生成」,复用其上一条 user 消息原地重跑(不新增 user 气泡;生成中禁用)
- [x] **会话本地缓存**(已完成 2026-09-20):消息按会话存入 `localStorage`(`harness-chat-cache`),刷新页面/切回旧会话即时渲染;单会话限 200 条、最多 20 个会话,容量不足自动淘汰;进度与错误态不入库

## 体验增强

- [x] **窄屏响应式**(已完成 2026-09-20):≤768px 侧栏转抽屉模式(`min(300px, 85vw)` 离屏 + 遮罩 + transform 滑入滑出),选中/新建会话与 Escape/遮罩点击自动收起;桌面内联折叠不变,`prefers-reduced-motion` 降级为直切,双主题遮罩 token 适配
- [ ] **键盘可达性**:会话项目前是 `li` click,无 `tabindex` / Enter 选中;补全焦点态与键盘操作

## 工程健壮

- [x] **web 端单测 + CI**(已完成):vitest 已落地(`web/src/**/*.test.ts`,`npm test` = `vitest run`);另有 Playwright E2E 冒烟 3 条。GitHub Actions `ci.yml` 的 `web` / `e2e` 两个 job 分别跑单测与 E2E,见 [docs/dev.md](../docs/dev.md) 的「流水线」一节
- [ ] **部署方案**:web/dist 生产托管路径未定(nginx 或 server 静态托管)。CI 与镜像发布已落地(`ci.yml` / `docker-image.yml`),剩余是托管方式选型与 CD 自动部署/分支保护强制校验
- [x] **轨迹观测增强(turn_id / trace_id)**(已完成 2026-09-24):llm_call_log 加 turn_id/trace_id/span_id/parent_span、tool_call_log 加前三者减 span_id(压平前的 V23 迁移,已并入 V1 基线;全 NULL 兼容);turnId 在 ChatService 入口生成沿显式参数链贯通,工具侧复用 Spring AI ToolContext 双参通道(原设想 ThreadLocal 桥接不需要);span 树经 OverAllState 状态键贯通编排 lead→subtask/aggregate,降级重答复用编排链 trace;轨迹页按轮分段(「第 N 轮 · M 步」)+ 按 parent_span 缩进,旧数据平铺如旧(spec 见 `docs/archive/specs/add-turn-trace-observability/`)
