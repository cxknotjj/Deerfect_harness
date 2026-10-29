# Checklist

- [x] `DELETE /api/harness/sessions/{sessionId}` 存在且成功返回 2xx 空体
- [x] 删除存在会话：session 行 isDelete 置 1、session_messages 行被物理删除、onebot_session_binding 绑定行被删除
- [x] 删除不存在/已删会话幂等成功，不抛错
- [x] saveContext 守卫：已软删会话的流收尾不重建快照行、不 touch 会话、不抛异常
- [x] llm_call_log / tool_call_log / goal 数据不受删除影响（保留）
- [x] client.ts / Api 接口 / realApi / mock 均含 deleteSession
- [x] useSessions.remove 成功移除列表项并修正 total，失败不误删本地数据
- [x] SessionList.vue hover 出现删除入口且有确认步骤
- [x] 删除当前会话：流被终止、视图切回草稿空态、消息桶与 localStorage 缓存被清除
- [x] 删除非当前会话：聊天视图不受影响
- [x] 删除按钮样式双主题（夜间/日间 token）正常
- [x] 后端全量测试通过（mvn -pl shared,server -am test）
- [x] web typecheck 与 build 通过
- [x] 浏览器端到端验证（mock + 真模式）通过
