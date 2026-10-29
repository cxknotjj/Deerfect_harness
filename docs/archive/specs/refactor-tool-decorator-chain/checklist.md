# Checklist

- [x] 装饰器与业务工具只通过 `ToolCallback` 接口绑定，4 个装饰器组件不 import 任何业务工具类
- [x] 新增装饰器的成本 = 新增一个类 + `DefaultToolDecorators.defaults()` 一行登记；`AgentRequestSpecFactory` 与业务工具类零改动
- [x] 默认链顺序为 观测(100) → 预算(200) → 懒加载(300) → 元工具(400)，且有测试断言该顺序（防 Order 漂移）
- [x] 顺序契约成立：预算截断的引导文本不产生 tool_call_log 记录与 SSE 工具事件；懒加载未展开的轻量 schema 不进观测/预算；expand_tool/load_skill 不经观测与预算额度
- [x] `AgentRequestSpecFactory` 现有 7/8/9 参构造签名保留（GA / AgentChatCaller / MultiAgentGraphAgent / 测试调用点零改动）
- [x] `disableTools=true`（幻觉降级路径）在装饰链之前直接返回，装饰器不执行
- [x] 无 SSE 链路（emitter=null、recorder 非空）时观测装饰器仍落库 tool_call_log（QQ 渠道/API 直调盲区覆盖保持）
- [x] 编排路径预算开关（toolCallBudget=true 且 emitter 非空）行为与改造前一致
- [x] `decorate` 返回 null 时工厂侧快速失败（Objects.requireNonNull）
- [x] 外部行为等价：SSE 进度行、tool_call_log 落库、预算截断、懒加载展开、load_skill 注册全部不变
- [x] 范围外项未动：MCP ServerTaggedCallback、ToolCallTracer.traceAnnotated、tool→agent 的 ProgressLine 反向依赖
- [x] 全量回归 mvn test 全绿（427 用例 = 412 基线 + 15 新增，0 失败 0 错误）
