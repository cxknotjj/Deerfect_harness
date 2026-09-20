# Web 端 TODO

> 已规划未实施的优化项,按优先级排列。纯前端项可随时做;标注「需后端」的等真模式联调时一起。

## 功能补全

- [x] **真模式联调**(已完成 2026-09-20):后端 `GET /api/harness/agents` 返回 agent 表真实主键(`AgentItemView(id, name)`,is_internal=0 按 id 升序),前端 `AgentView` 改对象结构、下拉与绑定全部用真实 id;SSE 真实流式 + agent 切换绑定已浏览器端到端验证(general/deepseek 双模型自述正确)
- [ ] **会话历史加载**(需后端):切回旧会话目前是空窗,后端补 `GET /api/harness/sessions/{id}/messages` 后前端接入
- [ ] **重新生成**:assistant 回答操作栏加「重新生成」,复用最后一条 user 消息重新发送

## 体验增强

- [ ] **窄屏响应式**:侧栏 260px 固定宽度,手机/窄窗口需要抽屉式侧栏(遮罩 + 滑入滑出)
- [ ] **键盘可达性**:会话项目前是 `li` click,无 `tabindex` / Enter 选中;补全焦点态与键盘操作

## 工程健壮

- [ ] **web 端单测**:useChat / useSessions 等组合式逻辑零测试,引入 vitest;并进 CI(与 CI 条目合并跟进)
- [ ] **部署方案**:web/dist 生产托管路径未定(nginx 或 server 静态托管),连同 CI/CD 条目一起规划
