# 飞书入站扩展：群聊全量 / 富文本 / 图片

- 范围：`channel-feishu` 的 `ws` 包；契约侧 `harness-api`（`ChatRequest` / `ChatAttachment`）；
  执行侧 `server` 的 `AgentService` 与 `AgentScopeGeneralAgent`
- 创建：2026-11-10
- 状态：共 11 项，已完成 0 项
- 前置：飞书后台权限
  - `im:message.group_msg`（获取群组中所有消息，**敏感权限需审核**）—— 用户已确认开通
  - `im:resource`（下载消息中的图片/文件资源）—— **待确认**，图片链路依赖它
  - `im:message:send_as_bot`（已有，回复用）
- 关系：`feishu-channel.md` 的 M3b 只做了「单聊 + 群聊 @」，本清单是对入站能力的扩展

## 进度概览

| 分类 | 已完成 | 待办 | 小计 |
| --- | --- | --- | --- |
| 群聊接入 | 0 | 3 | 3 |
| 消息解析 | 0 | 2 | 2 |
| 图片多模态 | 0 | 4 | 4 |
| 测试与文档 | 0 | 2 | 2 |
| 合计 | 0 | 11 | 11 |

## 背景

当前入站有两道门禁，都在 `FeishuInboundService.onEvent`：

```java
// 第 90 行：群里没人 @ 就丢弃（依赖「只推送 @ 消息」的旧权限语义）
if (group && (message.getMentions() == null || message.getMentions().length == 0)) { ... return; }
// 第 108 行：非 text 一律丢弃
if (!"text".equals(message.getMessageType())) { ... return; }
```

目标是：**群里所有消息都回**，并且**图片直接给模型看**、**富文本能读**。

两处都牵动边界值得先说清：

1. **放开群聊会引入自回环风险**。飞书文档写明 `im.message.receive_v1` 的群消息不包含机器人自己发的，
   但这是「权限配置正确时」的前提；一旦权限或事件配置变化，机器人可能对自己的回复再回一轮。
   闸门放在 `sender_type` 上，成本极低。
2. **图片要穿过三层**才能到模型：飞书下载 → 契约携带 → AgentScope 消息块。任何一层断了，
   模型都只会看到一段文字，而且**不会报错**（图片被静默丢弃），所以验收必须看模型是否真的描述了图。

## 条目

### 群聊接入

- [ ] G1 去掉 `mentions` 门禁
  - 作用：群内普通消息（不 @ 机器人）也能触发回复
  - 位置：`channel-feishu/.../ws/FeishuInboundService.java` 第 90~95 行
  - 做法：删除 group + mentions 空即 return 的分支；`stripMentions` 保留（@ 过的消息仍要剥占位符）
  - 验收：群里发一句不 @ 的话，机器人回复；日志出现 `收到群聊消息` 而不是 `群里没人 @ 机器人，忽略`

- [ ] G2 过滤机器人自身与其他 bot 的消息
  - 作用：防止自回环——开 `group_msg` 后若事件包含 bot 消息，会形成无限对话
  - 位置：同上，取 `data.getSender().getSenderType()`
  - 做法：`senderType` 不是 `user` 直接丢（`app`/`bot` 都算）；日志按 debug，避免正常运行时刷屏
  - 验收：机器人回复后不再触发第二轮；两个机器人在同群不会互刷
  - 备注：飞书文档称群消息事件不含机器人消息，这里是有意保留的一道闸，不是兜底逻辑

- [ ] G3 权限与文案同步
  - 作用：代码放开了但后台没开权限，表现是「没人 @ 就永远没反应」，极易误判为 bug
  - 位置：`channel-feishu/README.md`、`docs/guides/qq-channel.md` 无需改、`docs/dev.md`
  - 做法：README 把「群聊必须 @ 机器人」改成「群内所有消息都会回复（需 `im:message.group_msg`，敏感权限）」；
    启动日志或 README 提示权限缺失时的表现
  - 验收：按 README 配好权限能收到普通群消息；未开权限时文档能解释「为什么没反应」

### 消息解析

- [ ] P1 富文本 `post` 解析为纯文本
  - 作用：飞书长消息/粘贴内容常以 post 发送，当前整条被丢弃
  - 位置：`FeishuInboundService`，新增 `postText(JsonNode)`；`onEvent` 按 `messageType` 分派
  - 做法：`content` 结构为 `{"title":"...","content":[[{"tag":"text","text":"..."},...]]}`；
    遍历段落与元素，`text` 取 `text`、`a` 取 `text`、`at` 取显示名或剥离、`img` 收集 `image_key`；
    `title` 非空时置于首行。解析失败按原文兜底
  - 验收：一条「标题 + 两段文字 + 一张图」的富文本，模型能读到完整文字并看到图

- [ ] P2 图片消息识别与附件提取
  - 作用：`image` 类型当前直接丢弃，用户发图问「这是什么」没有任何反馈
  - 位置：同上；`FeishuInboundMessage` 增加附件字段
  - 做法：`image` 类型取 `content.image_key`；post 里的 `img` 元素同样收集；
    `FeishuInboundMessage` 增加 `List<FeishuAttachment>`（`fileKey` + 推断的 mimeType）
  - 验收：单发一张图（无文字）也能触发，文本部分为占位（如「[图片]」），不因 `@NotBlank` 被拦

### 图片多模态

- [ ] V1 契约：`ChatAttachment` 与 `ChatRequest.attachments`
  - 作用：让"一条消息带图"成为端口能力，而不是渠道内部的私货
  - 位置：`harness-api` 新增 `contract/model/ChatAttachment.java`；`view/ChatRequest.java` 加组件
  - 做法：`record ChatAttachment(String mimeType, byte[] data)`，构造时校验非空；
    `ChatRequest` 增加 `List<ChatAttachment> attachments`，**保留 5 参重载**（现有 55 处调用不动），
    紧凑构造器把 null 归一为空列表
  - 验收：`PortSignatureGuardTest` 更新快照后通过；不带附件的调用零改动

- [ ] V2 执行链：把附件送到 Agent
  - 作用：`ChatService` 到 Agent 之间只有 `String objective`，附件没有通道
  - 位置：`contract/api/AgentService.java`、`server/.../AgentServiceImpl.java`、`agent/Agent.java`、
    `service/impl/ChatTurnExecutor.java`
  - 做法：`AgentService` 增 `executeSync(..., List<ChatAttachment>)` 重载；
    `Agent` 增 `default execute(Goal, List<ChatAttachment>)`（默认委托无附件版本，保持既有实现不改）；
    `ChatTurnExecutor` 传 `request.attachments()`
  - 验收：单测用假 Agent 断言附件原样到达；无附件时走原路径，行为不变

- [ ] V3 AgentScope 侧构造多模态消息
  - 作用：让模型真的"看见"图，而不是收到一句「用户发了图片」
  - 位置：`server/.../agent/AgentScopeGeneralAgent.java` 约 202 行
  - 做法：有附件时用 `new UserMessage(ContentBlock...)`：`TextBlock` 放正文，
    每个附件转 `ImageBlock(new Base64Source(mimeType, base64(data)))`；无附件保持原写法
  - 验收：发一张能明确辨认内容的图（如写着数字的截图），模型回答里出现该内容
  - 已知限制：**Graph 编排路径不支持**（`MultiAgentGraphAgent` 走 default 实现，附件被忽略）。
    多 Agent 场景下图片会丢，需在文档标注

- [ ] V4 飞书下载图片资源
  - 作用：事件里只有 `image_key`，字节要单独取
  - 位置：`channel-feishu/.../ws/FeishuApiClient.java`
  - 做法：`GET /open-apis/im/v1/messages/{message_id}/resources/{file_key}?type=image`，
    带 `tenant_access_token`；按响应头 `Content-Type` 定 mimeType；
    **单图上限 8 MB**（飞书限制 10 MB，留余量），超限丢弃并 WARN
  - 验收：图片下载成功且 mimeType 正确；缺 `im:resource` 时日志能看出是权限问题（401/403），
    且文本部分仍照常回复

### 测试与文档

- [ ] T1 入站解析用例
  - 作用：三类消息的分派逻辑最容易回归
  - 位置：`channel-feishu/src/test/.../FeishuInboundServiceTest.java`
  - 做法：补「群聊不 @ 也入队」「sender_type=app 丢弃」「post 解析出文字 + 图片」「image 提取 image_key + 占位文本」
  - 验收：新增用例全绿；原有 8 条不受影响

- [ ] T2 端到端与文档
  - 做法：真机发普通群消息、发图、发富文本各一条；`docs/dev.md` 记录「群聊全量接收」的权限前提与自回环闸门
  - 验收：三条消息都有回复，图片被正确描述；README 与实现一致

## 风险与取舍

| 项 | 说明 |
| --- | --- |
| 群里每条都回 | 用户明确要求。群多或活跃时 LLM 成本与骚扰都会上来；后续若要收敛，可复用 `SemanticTriggerJudge` 加「何时开口」策略，本次不做 |
| 敏感权限审核 | `im:message.group_msg` 需企业管理员审核；个人租户通常即时通过 |
| 图片不进记忆 | 会话快照只存文本，下一轮看不到上一轮的图。模型当轮可见，多轮追问需要重发 |
| Graph 路径不支持图 | 多 Agent 编排会把附件丢掉，需要在文档明确；若要支持，得改编排节点的消息构造 |
| base64 体积 | 图片以 base64 进请求体，8 MB 图约 10.7 MB 请求。飞书发送上限不受影响（那是出站），但模型侧有 token 成本 |

## 暂不做

- **文件 / 语音 / 表情包消息**：`file`、`audio`、`sticker` 仍记 debug 丢弃。
  文件要先下载再按类型解析（PDF/Excel 等），语音要 ASR，属于独立议题。
- **图片回传**：让模型/Agent 生成图片再发回飞书，与本清单方向相反，另开。
