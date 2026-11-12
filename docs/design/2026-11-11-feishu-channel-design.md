# 飞书渠道：设计决策与根因（2026-11-11）

> 记录「为什么这样做」和「改哪里会坏」。配置项、权限申请与使用方式见
> [channel-feishu/README.md](../../channel-feishu/README.md)；2026-11-10 那轮审计的
> 八项加固是一次性记录，见 [archive](../archive/2026-11-11-dev-history.md)。

## 飞书长连接（M3b 事件订阅）

与 M3a 的群 webhook 是两条独立链路：webhook 是「往固定群发通知」，长连接是「收消息 + 回复」。
开关也分开：`CHANNEL_FEISHU_ENABLED`（出站）/ `CHANNEL_FEISHU_WS_ENABLED`（入站）。

接入准备（环境变量、三项权限、事件订阅配置）见
[channel-feishu/README.md](../../channel-feishu/README.md#接入准备)——那里是唯一的操作口径，
本文只保留设计取舍。

**配对**：`allowed-users` 为空时进入配对模式——收到消息只把 `open_id` 打进日志，不回复、不调模型。
把日志里的 `open_id` 加进白名单并重启，才能对话。这是刻意的：长连接没有回调端点，
白名单是机器人被拉进陌生群后唯一能拦住「对所有人开口」的闸。

**群聊**：群里必须 @ 机器人（依赖飞书只推送 @ 消息的语义），回复走 `receive_id_type=chat_id`；
会话按 `(open_id, chat_id)` 绑定，同一个人在单聊和不同群里各自独立上下文。

必须知道的坑：

- **SDK 无法停止**：oapi-sdk 2.4.19 的 `Client` 没有公开 stop，`pingLoop` 是死循环且吞中断，
  OkHttp 线程也不是守护线程——JVM 不会自然退出。`FeishuWsClient.stop()` 在断开后延迟 3 秒
  调用 `Runtime.halt(0)`；升级 SDK 后必须重跑一次「启动 → Ctrl+C」确认行为。

## 飞书入站：群聊全量接收与图片多模态

群聊要「每条都回」，靠的是后台开 `im:message.group_msg`（**敏感权限**，需审核）。只有这个权限
才会把群里的普通消息推给应用；`im:message.group_at_msg` 只推 @ 机器人的。

两条容易踩的：

- **自回环**：开了 group_msg 后，事件里若带上机器人自己的消息，会自己回自己。入站按
  `sender_type` 过滤（非 `user` 一律丢）。飞书文档说不推 bot 消息，但这是权限配置正确时的前提，
  闸门不能省。
- **图片要三层才到模型**：飞书 `image_key` → 下载字节（`im:resource`）→ `ChatAttachment` →
  AgentScope `ImageBlock`。任何一层没接上，模型只收到一段文字，**不报错**。所以验证必须看
  模型能不能说出图里的内容，而不是看有没有异常日志。

已知限制：多 Agent 编排路径（`MultiAgentGraphAgent`）走 `Agent.execute` 的默认实现，附件被丢弃；
图片也不进会话快照（`saveContextBatch` 只存文本），下一轮看不到上一轮的图。

## 飞书指令：只有一个人能用

`FeishuChatHandler` 在入队之前拦 `/new` `/reset` `/help` `/history` `/agent`：不建会话、
不进会话队列、不调模型。三个设计点：

- **授权是单个 open_id，不是白名单**。白名单回答「谁能和机器人说话」，指令授权回答
  「谁能重置上下文」。群里也响应指令，而 `/new` 重置的是「这个人 + 这个群」的会话——
  放开给所有人，任何成员都能把别人正在进行的对话打散。
- **非授权用户不交给模型**。模型会顺着斜杠编一份并不存在的命令清单，比不回复更糟。
  回一句固定提示即可。
- **不认识的 `/xxx` 不拦**。用户可能只是发了条以斜杠开头的普通消息，指令层不该吞掉它。

配置 `FEISHU_WS_COMMAND_USER`（yaml: `channel.feishu.ws.command-user`）；留空回退
`allowed-users` 首位。**它必须在 `allowed-users` 里**，否则入站闸门先挡掉消息，
表现为「发指令没反应」——启动日志会打一条 warn。

## 飞书表情包：为什么和 QQ 各写一份

标记约定（`[emoji:分类]`）、数据（`emojis/` 目录 + 索引）都一样，代码却各留一份——
`channel-feishu` 看不见 `channel-qq`，抽公共模块又要为百来行代码新增一个反应堆成员，
还得同步 Dockerfile 的模块拷贝。共享的是**数据**，不是代码。

飞书侧比 QQ 多一步：图片必须先 `POST /im/v1/images` 换 `image_key`，再用
`msg_type=image` 发送——飞书不接受内联字节。上传与下载图片同属 `im:resource` 权限。

两个容易踩的点：

- **标记一律剥离**：即使 `emoji.enabled=false` 也要剥，否则关掉开关反而让正文里露出
  `[emoji:happy]`。关闭只意味着不选图、不发图。
- **模型不知道就不会发**：约定写在 `PromptAssembler` 的飞书信道规范段里。如果某次改动
  把它删了，表现是「表情功能突然不工作」，而不是报错。

## 飞书回复形态（引用 / @ / 撤回 / 回复模式）

四个都在渠道层，不动 core：

- **引用回复**：改走 `POST /im/v1/messages/{id}/reply`，群里显示引用块。失败（原消息被撤回、
  删除）自动退回普通发送——宁可丢引用，不能丢回复。`reply-in-thread=true` 可切话题形式。
- **@ 提问者**：群聊文本前加 `<at user_id="ou_xxx"></at>`，与 QQ 的 `at-asker` 同名同义。
- **`/recall`**：按 `openId+chatId` 记最近一条发出的消息 id（上限 512，超出清空），
  `DELETE /im/v1/messages/{id}` 撤回。只记对话回复与表情图，不记指令回执。
- **`reply-mode`**：`all` / `mention` / `probability`。只作用于群聊的普通消息：
  单聊不限制，指令也不受限制（显式操作不该被概率吞掉）。`mention` 依赖入站事件里的
  `mentions`，所以 `FeishuInboundMessage` 新增了 `mentioned` 字段。

## 飞书语音回复：为什么必须过一道 ffmpeg（2026-11-11）

core 的 TTS 产出 mp3，飞书语音条（`msg_type=audio`）只认 opus，且上传
（`POST /im/v1/files`）必须带 `duration`（毫秒）——两个约束都不是可选的，
所以链路上必然有一次转码。QQ 不需要：它直接内联 mp3 当 `record` 段发。

不引纯 Java 方案的理由：JDK 既没有 mp3 解码器也没有 opus 编码器，为一件事引两个原生库
不如调 ffmpeg（运维也熟）。代价是**运行环境必须有 ffmpeg**：没有时 `toOpus` 返回空，
本条退回文字，日志里只 warn 一次（不刷屏）。

时长从 ffmpeg 的 stderr（`Duration: 00:00:03.48`）里取，不再单独起 ffprobe——
为了一个字段多一次进程启动不值当。`-nostdin` 不能省：服务端没有终端，ffmpeg 默认会去读
标准输入，可能直接挂住。

## 文件下发：字节为什么必须经服务端中转（2026-11-11）

沙箱容器**不挂宿主卷**（这是隔离的主防线，见 `SandboxGuards`），文件只在容器里；渠道又只
认识 HTTP。两边都不动的前提下，只剩一条路：服务端把文件读出来，放
`FileArtifactStore`，渠道按 `file:<id>` flag 取走上传。

读取走容器内的 `stat -c %s` + `base64 -w0`，**只读**，边界复用写文件的同一套
（`SandboxGuards.requireWorkspacePath`）：越界读和越界写一样危险。三个刻意的取舍：

- **单文件上限 2MB**（`app.sandbox.send-file-max-bytes`）：内存里放一份、base64 再翻
  1.33 倍，无上限就是把容器里的文件大小变成服务的堆压力。超限直接拒绝，不截断。
- **路径含单引号一律拒绝**：路径要拼进 shell 命令，单引号内无法再转义；换个文件名即可。
- **`base64` 用 MIME 解码器**：busybox 版本按 76 列折行，严格解码器会直接报错。

工具的 shell 调用抽成 `SendFileTool.Shell` 函数接口，单测注入假实现，不必起 Docker。
