# QQ 表情包发送模块 Spec

## Why
QQ 通道回复目前只有纯文本，缺少拟人趣味。todo_list「表情包发送模块」（L14-19）要求：建立情绪-表情映射表，在分段发送文本的间隙按检测到的情绪自动追加表情包，让机器人更贴近真人聊天节奏。

## What Changes
- 新增表情包配置块 `napcat.emoji.*`（application.yaml 只管行为开关与路径）；情绪-表情映射表**不放 yaml**，独立为 channel 文件夹下的 JSON 文件
- 新增 `channel/emoji-index.json`：映射表（表情名 → {path, tags}），进 git 提供开箱即用的示例结构（图片本身由使用者放置，不进 git）
- 新增 `channel/qq/EmojiReplies` 组件：启动时经 Jackson 加载映射 JSON，关键词/句尾符号 → 表情图片匹配，生成 OneBot v11 image 段（`file://` 本地 URI）
- `OneBotEventServiceImpl.sendReply` 集成发送钩子：每条 chunk 发送完成后检测，命中则先发表情再进入下一条
- 表情包目录 `channel/emojis`（相对启动工作目录，可配绝对路径）；目录与图片由使用者自行放置，不纳入 git 与本次变更
- 完成后勾选 todo_list.md 表情包条目并注明验证方式

## Impact
- Affected specs: 无既有 spec
- Affected code:
  - `channel/qq/NapCatProperties.java`：新增 `Emoji` 嵌套类
  - `src/main/resources/application.yaml`：新增 `napcat.emoji` 块（行为开关与路径，不含映射表）
  - 新增 `channel/emoji-index.json`：映射表 JSON（进 git）
  - 新增 `channel/qq/EmojiReplies.java`
  - `channel/qq/OneBotEventServiceImpl.java`：sendReply 钩子 + emojiReplies 字段（构造签名不变）
  - 测试：新增 `EmojiRepliesTest`；`OneBotEventServiceImplTest` 增集成用例
  - `NapCatApiClient` / `MessageSegment` 不改：image 段走现有通用段列表（`{"type":"image","data":{"file":"file://<绝对路径>"}}`）

## ADDED Requirements

### Requirement: 行为配置与映射表文件
系统 SHALL 提供 `napcat.emoji.*` 行为配置（application.yaml，`NapCatProperties.Emoji` 纯绑定载体）：
- `enabled`：总开关；false 时整个模块零行为（不读映射文件/目录、不检测、不发送）
- `dir`：表情包根目录，默认 `channel/emojis`（相对启动工作目录；即 channel 文件夹下的 emojis 文件夹，支持绝对路径）
- `index-file`：情绪-表情映射表 JSON 文件路径，默认 `channel/emoji-index.json`（channel 文件夹下，支持绝对路径）
- `max-per-reply`：单次回复最多发送张数，默认 2；0 = 不限制
- `probability`：命中后发送概率（0.0~1.0），默认 1.0
- `send-delay-ms`：表情发送前停顿毫秒数，默认 800；0 = 不等待
- `cheer-emoji`：句尾符号触发的默认表情名，默认 `happy_01`

情绪-表情映射表 SHALL 定义在 `channel/emoji-index.json`（不放 yaml、不硬编码 Java）：表情名 → `{"path": "相对 dir 的文件名", "tags": ["关键词", ...]}`；文件进 git，内置 todo_list 给出的 4 条示例（happy_01/开心转圈.gif、sad_02/流泪猫猫头.png、wtf_03/地铁老人手机.png、poke_04/戳一戳.gif）。启动时加载一次（Jackson 解析）。

#### Scenario: 映射表加载
- **WHEN** 启动加载 `channel/emoji-index.json`
- **THEN** 解析为表情映射表；文件缺失或 JSON 损坏时记 WARN 并按空映射降级，不影响应用启动

#### Scenario: 行为配置绑定
- **WHEN** Spring 装配 `napcat.emoji` 块
- **THEN** `Emoji` 嵌套类字段全部由 yaml 注入，Java 类不写死任何值

### Requirement: 情绪匹配规则
`EmojiReplies` SHALL 按单条 chunk 文本判定是否发表情及发哪张：
1. **tag 匹配优先**：chunk 文本包含某映射项任一 tag（contains 语义）→ 选中该表情；多个命中随机取一
2. **句尾符号兜底**：chunk 以 `~`、`！`、`!` 结尾且无 tag 命中 → 选中 `cheer-emoji`
3. 未命中 → 不发送

#### Scenario: 关键词命中
- **WHEN** chunk 文本含「开心」（happy_01 的 tags 之一），且句尾同时有 `！`
- **THEN** 选中 happy_01（tag 命中优先于句尾符号）

#### Scenario: 句尾符号触发
- **WHEN** chunk 文本「好耶~」且无任何 tag 命中
- **THEN** 选中 cheer-emoji

#### Scenario: 无命中
- **WHEN** chunk 文本「普通陈述句。」
- **THEN** 不发送表情

### Requirement: 图片段生成与文件校验
系统 SHALL 以 OneBot v11 image 段发送本地图片（`{"type":"image","data":{"file":"base64://<图片内容 base64>"}}`——NapCat 常跑在远端 Docker，`file://` 指向其自身文件系统跨机必然 ENOENT，base64 内联无此问题）；表情文件不存在或目录不可读 SHALL 记 WARN 并跳过，绝不影响文本回复。

#### Scenario: 文件缺失跳过
- **WHEN** 命中 happy_01 但对应图片文件不存在
- **THEN** 不发送表情段，文本 chunk 照常发送

### Requirement: 发送钩子集成
`sendReply` 在每条文本 chunk 发送完成后（含最后一条）SHALL 检测该 chunk：
- 满足 `enabled` + `sentEmojis < max-per-reply` + 概率命中 → 等待 `send-delay-ms` → 发送表情段（群聊走 `sendGroupMsg`、私聊走 `sendPrivateMsg`，与文本同 API）
- 中断语义与条间等待一致：`chunkPause` 返回 false（executor 停机）→ 停止后续发送，已发部分保留
- 表情顺序：chunk N → （send-delay 停顿）→ 表情 → 条间动态延迟 → chunk N+1

#### Scenario: 渐进回复条间甩表情
- **WHEN** 回复拆 3 条 chunk，第 2 条含 tag「激动」
- **THEN** 发送顺序 chunk1 → chunk2 → 表情 → chunk3，表情计入 max-per-reply

#### Scenario: 上限限流
- **WHEN** `max-per-reply=1` 且 3 条 chunk 均命中
- **THEN** 仅第 1 个命中的 chunk 后发送表情

### Requirement: 失败隔离
表情模块任何异常（匹配/IO/段生成/发送）SHALL 只记日志不外抛，不影响文本回复主链路（与 NapCatApiClient「尽力而为」语义一致）。

## MODIFIED Requirements
无

## REMOVED Requirements
无
