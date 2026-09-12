# QQ 通道段落渐进发送（假渐进）设计

- 日期：2026-09-12
- 状态：已实现（454 用例通过）
- 范围：仅 channel/qq 包 + application.yaml，核心包零改动

## 目标

QQ 通道回复从「等 LLM 生成完 → 整包一条发出」改为「按空行段落拆成多条、条间延迟逐条弹出」，观感像人连发消息。

## 平台约束（决定方案边界）

1. QQ 消息发出后不可编辑 → 单条消息内打字机效果不可行
2. OneBot 11 / NapCat 无「正在输入」状态 API
3. 出站频率受腾讯风控约束 → 拆条数量与间隔必须有上限保护

## 决策记录

- **段落级**（用户选定）：长回答按段落拆 2-4 条；排除逐句连发（风控风险高）
- **假渐进**（用户选定）：仍阻塞等完整回复再拆段发送；排除真流式（需改 QQ 通道消费 streamReactive，超时/取消/写回全要适配，收益仅首段秒回）
- 放弃变体：无 max-chunks 上限——极长回答可拆 8+ 条连续弹屏

## 行为定义

- `progressive=false`：字节级等价原行为（`splitReply` 原样，零延迟零拆分变化），作为回退开关
- `progressive=true`（默认）：
  - 多段回答**始终按空行段落逐条发送**（不设总长门槛——这是与 `splitReply` 的本质差异，正常长度的多段回答也能渐进）
  - 切分粒度由 `split-chars`（40 字）独立控制，与 `max-length`（仅渐进关闭路径使用）解耦
  - 单段回答 = 单条（与关闭渐进一致）
  - 块超过 `split-chars` 走**层级下切**（参考文档切片逻辑）：段落 → 单换行行 → 句末标点（。！？!?；;，标点保留句尾）；**无字符硬切兜底**——切不动的无标点块（长 URL/代码段）原样成片直接发送
  - 段落数 > `max-chunks` 时尾部段落合并为最后一条（合并条可能超 max-length，仅极端长回答触发，可接受）
  - 条间延迟 `inter-chunk-delay-ms`（首条不延迟；0 = 不等待）
- 群聊引用段（reply 段）仍只在首条带；`/reset` 确认语单条不受影响
- 与入站 10s 限频无交互（限频只管入站触发）
- 等待被中断（executor 停机）：停止后续发送，已发部分保留，恢复中断标志

## 配置

```yaml
napcat:
  reply:
    max-length: 3000            # 既有
    progressive: true           # 段落渐进发送开关
    inter-chunk-delay-ms: 1200  # 条间延迟
    max-chunks: 4               # 单次回复最多拆几条；0 = 不限制
```

## 实现

- [NapCatProperties.Reply](/home/wsl/development/java-harness/src/main/java/com/dark/javaHarness/channel/qq/NapCatProperties.java)：新增 progressive / interChunkDelayMs / maxChunks 三字段（纯绑定载体，默认值在 application.yaml）
- [OneBotEventServiceImpl](/home/wsl/development/java-harness/src/main/java/com/dark/javaHarness/channel/qq/OneBotEventServiceImpl.java)：
  - `splitProgressive(text, max, maxChunks)`：渐进拆分（段落即消息 + 尾部合并 + 超长硬切）
  - `ChunkPause` 函数接口 + 包内可注入 `chunkPause` 字段（测试注入记录器避免真 sleep；注意方法名不可用 `wait`——与 `Object.wait` 冲突）
  - `sendReply`：按开关选择拆分路径，`i>0` 时 pause，中断则 return 停止后续发送

## 测试

- `splitProgressive_multiParagraph_alwaysSplitsByParagraph`：无总长门槛逐段拆 / 单段单条 / 尾部合并 / maxChunks=0
- `handle_progressiveMultiParagraph_sendsChunksWithDelayBetween`：3 条发送、首条零延迟、条间各 1200ms
- `handle_progressiveDisabled_singleMessageNoDelay`：关闭开关 = 整包一条 + 零等待（原行为回退保证）
