# harness-api

harness 的能力契约包：**server 提供实现，channel-\* / cli / web 消费**。
本模块不依赖任何实现模块，只回答一个问题——「harness 能做什么」。

> 使用哪些能力、怎么组合、按什么顺序调用，**由消费者自己决定**。
> 本文件只描述「有什么」与「改动的风险有多大」，不规定任何调用方必须或只能使用哪些接口。

## 它是什么，不是什么

- 是：端口（调用型）、SPI（实现型）、以及它们签名可达的共享类型
- 不是：Controller 逻辑、Spring Bean、工具类、持久化实现

判断一个类型该不该放进来，只问一句：**从某个端口/SPI 的签名出发，能不能走到它？**
走不到的类型，要么是别的模块的实现细节，要么是 HTTP 传输对象——即使暂时放在这里，也不属于契约。

## 依赖方向

```text
server      --compile--> harness-api        ✅
channel-*   --compile--> harness-api        ✅
cli         --compile--> harness-api        ✅
server      --compile--> channel-*          ❌ enforcer 禁止（core 不得绑死渠道实现）
harness-api --compile--> server / channel-* ❌ Maven 反应堆禁止成环
```

反向依赖会直接构成环，构建起不来；渠道模块只能看见本模块，看不见 core 实现类。

## 能力目录

| 能力 | 入口 | 实现方 | 调用方 | 稳定性 |
| --- | --- | --- | --- | --- |
| 对话（同步 / 流式 / 断点续跑） | `ChatService` | server | channel-* / cli / web | 稳定 |
| 多轮会话与消息快照 | `SessionService` | server | channel-* / cli / web | 稳定 |
| Agent 列表 / 目标提交 / 流式执行 | `AgentService` | server | channel-* / cli / web | 稳定 |
| 渠道事件语义判断 | `SemanticTriggerJudge` | server | channel-* | 稳定 |
| 会话滚动摘要 | `SessionSummaryPort` | server | core 内部 | 稳定 |
| 主动外发 | `MessageSender` | channel-* | server（outbox） | 稳定 |
| 渠道健康 | `ChannelHealth` | channel-* | server（watchdog / 监控） | 稳定 |
| 回复文本后处理 | `ReplyPostProcessor` | channel-* | server（回复链路） | 稳定 |
| 会话绑定清理 | `SessionBindingCleaner` | channel-* | server（删会话） | 稳定 |
| 语音工件 | `VoiceArtifactStore` | server | server 工具 + channel-* | 跟随实现 |
| HTTP 传输对象 | `view/*` | 无（纯数据） | cli / web / 少量渠道 | 跟随接口 |
| 管理面 DTO | `view/Provider*` | 无（纯数据） | cli / web | 低 |

「稳定性」只描述**变更风险**，不限制使用：

- **稳定**：签名变更视为破坏性变更，必须同步 `PortSignatureGuardTest` 与 `API.md`，并说明受影响模块
- **跟随实现**：随功能演进调整，调用方需自行关注
- **跟随接口 / 低**：面向 REST 或控制台，可能随接口一起改

## 包结构

| 包 | 内容 | 方向 |
| --- | --- | --- |
| `service` | 调用型端口与少量共享记录 | server 实现，消费者调用 |
| `channel` | 实现型 SPI | 渠道实现，server 调用 |
| `view` | 跨端 DTO（含 HTTP 传输对象） | 纯数据 |
| `domain` | 共享模型（`Goal` / `AgentConfig` / `Schedule` / `ChatMessageMetadata`） | 纯数据 |
| `domain.entity` | 持久化实体（当前仅 `SessionEntity`） | 经 `SessionService.getSession` 暴露 |
| `enums` | 共享枚举与协议常量（`GoalStatus` / `SseProtocol` / `AgentConstants`） | 纯常量 |
| `voice` | 语音工件桥（工具写、渠道读） | server 写，渠道消费 |

`domain.entity` 里的 `SessionEntity` 是 MyBatis 实体，出现在 `SessionService` 签名里属于已知的
接口泄漏（数据库字段变更会波及消费者）。新增端口请返回 `record`，不要再把实体带进契约。

## 变更规则

1. 修改「稳定」级端口的公开签名：同步更新 `PortSignatureGuardTest` 快照与 `API.md`，
   并在提交信息里写明受影响模块。
2. 新增 public 类型：先判断它是否在端口闭包内；不在闭包内，考虑放回实现模块。
3. 修改 `view/*`：检查 `cli` 与各渠道是否引用（`grep -rn "com.dark.javaHarness.view" cli channel-*`）。
4. 不在本模块引入实现依赖（Spring Bean、MyBatis Mapper、HTTP 客户端等）。

## 构建与测试

```bash
mvn -s .mvn/settings.xml -pl harness-api -am test
```

签名守卫：`harness-api/src/test/java/com/dark/javaHarness/contract/PortSignatureGuardTest.java`
（当前覆盖 `AgentService` / `SessionService` / `Goal` 三个高扇入类型）。

## 方法手册

每个方法的参数、返回、阻塞性与失败行为见 [API.md](./API.md)。
