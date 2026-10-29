# Checklist

## 迁移与兼容
- [x] V23 迁移执行成功，7 个新列全 NULL 兼容，存量行不回填，启动无 Flyway 报错
- [x] 旧轨迹数据（无 turn_id）在轨迹页保持平铺渲染，无 JS 报错

## 标识语义正确
- [x] turnId 在 ChatService 入口生成，同一轮内 route-judge / 直答 / 编排 /
      工具调用记录的 turn_id 一致
- [x] traceId 每次 Goal 执行唯一；编排内 lead/subtask/aggregate 调用
      trace_id 相同
- [x] span 树正确：subtask/aggregate 的 parent_span = lead 的 span_id，
      根调用 parent_span 为 NULL；树关系与落库时序无关
- [x] /submit 直发：turn_id NULL、trace_id 有值；route-judge：仅 turn_id；
      memory-profile：全 NULL

## 工具侧关联
- [x] 工具行 turn_id/trace_id 与触发它的 LLM 调用一致，parent_span = 该
      调用 span_id
- [x] ToolContext 缺值/异常时静默落 NULL，工具执行不受影响（无新增堆栈日志）

## 前端展示
- [x] 「第 N 轮 · M 步」分段统计正确（多轮会话实测）
- [x] LLM/工具行按 parent_span 缩进，层级一眼可辨
- [x] types.ts 与后端序列化字段对齐，构建无类型错误

## 主链路安全
- [x] 标识生成/传递仅为内存操作（UUID + Map），无 IO/锁/阻塞
- [x] 观测落库失败仅 warn（既有异步边界约束覆盖新字段）
- [x] server 全量测试 0 失败 0 错误（实测 575/0/0，BUILD SUCCESS）（`mvn -pl server -am test`），前端
      `npm run build` 通过
- [x] 浏览器实测一轮 SIMPLE + 一轮带工具/编排，轨迹页与库内标识一致
