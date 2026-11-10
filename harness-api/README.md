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

## 目录结构

```text
harness-api/
├── README.md                 # 本文件：定位、方向、能力目录、变更规则
├── API.md                    # 方法手册：参数、阻塞性、失败行为
├── pom.xml                   # 依赖只有 Spring AI / Reactor / Validation / Jackson
└── src/
    ├── main/java/com/dark/javaHarness/
    │   ├── contract/api/     # 调用型端口（server 实现，消费者调用）
    │   ├── contract/spi/     # 实现型 SPI（渠道实现，server 调用）
    │   ├── contract/model/   # 端口载荷：record 与枚举
    │   └── view/             # 对外 DTO（HTTP 传输对象）
    └── test/java/com/dark/javaHarness/
        ├── contract/         # PortSignatureGuardTest：公开签名快照
        ├── contract/api/     # 端口契约测试（内存替身，不连数据库）
        ├── contract/spi/     # SPI 约定（不得抛异常、健康四态映射）
        ├── contract/model/   # 状态机测试
        └── view/             # DTO 的 JSON 字段名守卫
```

每个 `contract.*` 包都带 `package-info.java`，写清方向与准入规则——IDE 里导航到包即可看到。


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
| 渠道/组件状态 | `HarnessStatusPort` | server | channel-*（/status 类指令） | 稳定 |
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
| `contract.api` | 调用型端口 + 语音工件桥 | server 实现，消费者调用 |
| `contract.spi` | 实现型 SPI | 渠道实现，server 调用 |
| `contract.model` | 端口载荷（`Goal` / `GoalStatus` / `AgentConfig` / `SessionInfo` / `SessionSnapshotItem` / `SessionMessageEntry` / `ComponentStatus`） | 纯数据 |
| `view` | 跨端 DTO（含 HTTP 传输对象，以及 `Schedule` / `SseProtocol`） | 纯数据 |

**为什么 api 与 spi 分开**：两者唯一的区别是「谁实现」，而这恰恰是调用方最需要一眼看出的信息。
混在一个 `service` 包里时，`MessageSender`（渠道实现、core 调用）和 `ChatService`（core 实现、
渠道调用）看起来是同一类东西，新增渠道时容易漏掉要实现的接口。

每个 `contract` 包都带一个 `package-info.java`，写清方向与准入规则——在 IDE 里导航到包即可看到，
不需要翻到本文件。

`contract.model` 只放端口的入参/出参。持久化实体、只在 server 内部流转的元数据不进契约：
`SessionEntity` 已搬回 server，`ChatMessageMetadata` 同样回到 server；端口改为返回
`SessionInfo`，改数据库列名不再波及渠道与 CLI。

## 变更规则

1. 修改「稳定」级端口的公开签名：同步更新 `PortSignatureGuardTest` 快照与 `API.md`，
   并在提交信息里写明受影响模块。
2. 新增 public 类型：先判断它是否在端口闭包内；不在闭包内，考虑放回实现模块。
3. 修改 `view/*`：检查 `cli` 与各渠道是否引用（`grep -rn "com.dark.javaHarness.view" cli channel-*`）。
4. 不在本模块引入实现依赖（Spring Bean、MyBatis Mapper、HTTP 客户端等）。
   端口返回 `record`，不要返回 MyBatis 实体——实体的字段即数据库列，改列名等于改契约。

## 构建与测试

```bash
mvn -s .mvn/settings.xml -pl harness-api -am test
```

签名守卫：`harness-api/src/test/java/com/dark/javaHarness/contract/PortSignatureGuardTest.java`
（当前覆盖 `AgentService` / `SessionService` / `Goal` 三个高扇入类型）。

## 方法手册

每个方法的参数、返回、阻塞性与失败行为见 [API.md](./API.md)。
