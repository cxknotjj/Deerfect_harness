```
问题：目前子任务如果有一个执行失败的话，就直接走降级general链路，导致其他子任务的执行结果失效，正常子任务执行失败应该跳过他，进行聚合节点。
```

<br />

```
问题：目前子任务执行工具时，在前端都显示在思考块前边，无法区分工具是那个子agent调用的。
```

```
问题：rag链路在轨迹中并不可见，用于对于是否执行rag操作无感
```

```
2026-10-02T18:06:48.306+08:00  WARN 59466 --- [javaHarness] [    mvc-async-7] c.d.j.service.impl.AgentServiceImpl      : [goal-418cdd5e-893e-40cf-93dd-b09792ce3825] goal '帮我查一下今天发生啥事了' 取消：客户端断开，停止推送并终止编排
2026-10-02T18:06:48.310+08:00  WARN 59466 --- [javaHarness] [nio-8080-exec-3] c.d.j.exception.GlobalExceptionHandler   : 客户端断开（断开的管道），停止本次响应推送
2026-10-02T18:06:48.326+08:00  WARN 59466 --- [javaHarness] [    mvc-async-7] c.d.j.a.o.MultiAgentStreamPipeline       : [multi-agent] 客户端已断开，终止编排：不再发起新的 LLM 调用（进行中的调用等待自然结束）查一下啥情况，
```

```
2026-10-03T21:30:31.564+08:00  INFO 98416 --- [javaHarness] [action-thread-1] c.d.j.config.agent.ChatClientRegistry    : [registry] 命中已注册客户端: modelProviderId=10
2026-10-03T21:30:31.574+08:00  WARN 98416 --- [javaHarness] [action-thread-1] c.dark.javaHarness.tool.ToolAssignments  : [tool分配] agent='general' tools 声明含未识别 token 'browser_click'，跳过
2026-10-03T21:30:31.574+08:00  WARN 98416 --- [javaHarness] [action-thread-1] c.dark.javaHarness.tool.ToolAssignments  : [tool分配] agent='general' tools 声明含未识别 token 'browser_type'，跳过
2026-10-03T21:30:31.574+08:00  WARN 98416 --- [javaHarness] [action-thread-1] c.dark.javaHarness.tool.ToolAssignments  : [tool分配] agent='general' tools 声明含未识别 token 'browser_press_key'，跳过
2026-10-03T21:30:31.574+08:00  WARN 98416 --- [javaHarness] [action-thread-1] c.dark.javaHarness.tool.ToolAssignments  : [tool分配] agent='general' tools 声明含未识别 token 'browser_scroll'，跳过
2026-10-03T21:30:31.586+08:00  WARN 98416 --- [javaHarness] [action-thread-1] c.dark.javaHarness.tool.ToolAssignments  : [tool分配] agent='general' tools 声明含未识别 token 'browser_click'，跳过
2026-10-03T21:30:31.586+08:00  WARN 98416 --- [javaHarness] [action-thread-1] c.dark.javaHarness.tool.ToolAssignments  : [tool分配] agent='general' tools 声明含未识别 token 'browser_type'，跳过
2026-10-03T21:30:31.586+08:00  WARN 98416 --- [javaHarness] [action-thread-1] c.dark.javaHarness.tool.ToolAssignments  : [tool分配] agent='general' tools 声明含未识别 token 'browser_press_key'，跳过
2026-10-03T21:30:31.586+08:00  WARN 98416 --- [javaHarness] [action-thread-1] c.dark.javaHarness.tool.ToolAssignments  : [tool分配] agent='general' tools 声明含未识别 token 'browser_scroll'，跳过  这些工具日志还是存在啊
```

