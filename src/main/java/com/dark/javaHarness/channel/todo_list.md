# channel 模块 TODO

> QQ（NapCat / OneBot 11 纯 HTTP）通道的待办清单。总任务清单见 `docs/HARNESS_TODO.md`，本文件只收 channel 侧事项；完成后打勾并注明验证方式。

## 功能缺口

- [ ] **群聊群号白名单**：私聊有 `private-allow-users`，群聊无任何准入控制——任何群 @ 均触发 LLM（现靠 @ 触发 + 10s 限频防刷）。建议加 `group-allow-groups`（CSV，空 = 不限制），与私聊白名单语义对齐；恶意拉群场景兜底
- [ ] **多媒体消息降级提示**：接收侧只提取 message 数组的 text 段（`OneBotEventServiceImpl.joinTextSegments`），图片/语音/表情包等非文本消息**静默忽略**——群友发图 @ 机器人无任何反馈。至少对 @ 触发但提取不到文本的场景回一句「暂不支持图片/语音消息」
- [ ] **好友/加群请求处理**：notice/request 类事件现全部静默 ACK（`OneBotEventController`），好友申请需在 QQ 手动同意后才能私聊；可评估自动同意 + 白名单联动
- [ ] **多机器人支持**：`napcat.self-id` 单值，一个 harness 实例只能挂一个 QQ 号；多号需 self-id 集合 + binding 键扩充
- [x] **段落渐进发送（假渐进）**：多段回答按粒度逐条弹出，像人连发——已完成（2026-09-12）。`napcat.reply.progressive` 开关 + `split-chars` 切分粒度（字，超限按下一层边界继续切：段落 → 换行 → 。！？，标点保留句尾，无字符硬切兜底）+ `inter-chunk-delay-ms` 条间延迟 + `max-chunks` 防刷屏。设计见 `docs/superpowers/specs/2026-09-12-qq-progressive-reply-design.md`
- [ ] **流式文本缓冲器（真渐进，可选升级）**：当前为假渐进——阻塞拿完整回复后才拆段发送，首段仍要等 LLM 全部生成完。真渐进 = 拦截 streamReactive 的流式 token 入缓冲区，遇边界（。！？\n 等）即成句发送，首句秒回；需把 QQ 通道消费侧切到流式并适配超时/取消/写回语义，成本高收益有限，暂缓
- [x] **动态拟人延迟**：已完成（2026-09-12）。条间延迟升级为按字数动态计算：`inter-chunk-delay-ms`（基础值）+ 字数 × `delay-factor`（0.1 秒/字）+ ±`jitter-range-ms` 随机扰动，`max-delay-ms` 截断防超长块（无标点长文/合并尾条）算出分钟级停顿；首条不等待，按「即将发送的这条」的字数取值。验证：OneBotEventServiceImplTest 动态延迟 4 用例（900ms 公式断言 / 扰动区间 \[600,1200] / 上限截断 / 关闭渐进零等待）
- [x] **表情包发送模块（QQ 协议对接）**：已完成（2026-09-12）。映射表在 `channel/emoji-index.json`（表情名 → {path, tags}，Jackson 启动加载，缺失/损坏空映射降级），行为开关 `napcat.emoji.*`（enabled/dir/index-file/max-per-reply/probability/send-delay-ms/cheer-emoji）；匹配规则：tag contains 优先（多命中随机取一）→ 句尾 ~/！/! 兜底 cheer-emoji；sendReply 每条 chunk 发送后挂钩子（表情前 send-delay 停顿走 chunkPause 可中断），image 段 `base64://` 内联图片（跨机部署 file:// 在 NapCat 侧不可达，会 ENOENT），全量失败隔离不影响文本回复；图片放 `channel/emojis/`（自行放置，不进 git）。验证：EmojiRepliesTest 10 用例 + OneBotEventServiceImplTest 集成用例，两测试类 38 例全绿

* **梳理表情包协议格式**：查阅你所用框架（如 NapCat ）的 OneBot v11 文档，明确发送 自定义图片的 API 格式。
* **建立情绪-表情映射表**：在代码中配置一个字典config，将大模型输出的情绪标签或关键词映射到具体的 QQ图片。
* 以下是字典的示例emoji\_index = {     "happy\_01": {"path": "./emojis/开心转圈.gif", "tags": \["开心", "激动", "好耶", "太棒了"]},     "sad\_02":   {"path": "./emojis/流泪猫猫头.png", "tags": \["伤心", "委屈", "难过", "呜呜"]},     "wtf\_03":   {"path": "./emojis/地铁老人手机.png", "tags": \["疑惑", "不解", "无语", "离谱"]},     "poke\_04":  {"path": "./emojis/戳一戳.gif", "tags": \["在吗", "理我", "戳", "出来"]} }表情包可以存储在./emojis文件夹下，此文件夹需要在channel文件夹下
* **实现表情发送钩子（Hook）**：在分段发送文本的间隙，加入表情发送逻辑。例如，当检测到句子末尾带有“\~”或“！”时，自动追加一个可爱的表情。

- [ ] **进阶拟人化特性（可选优化）**

* **实现“拍一拍”互动**：当用户在机器人“打字”期间发送新消息时，触发打断逻辑，先撤回或停止当前输出，并发送一个“拍一拍”或“探头”表情作为缓冲。
* **模拟手滑/撤回机制**：极低概率（如 2%）触发“错别字撤回”事件。发送一条带错别字的短句后，延迟 2 

## 安全与治理

- [ ] **上报验签默认启用**：`napcat.event-secret` 默认空 = 跳过 `X-Signature`（HMAC-SHA1）校验，任何人可伪造上报刷 LLM——生产部署必须配置，且与 NapCat httpClients.secret 一致；开发环境维持空可接受
- [ ] **onebot\_session\_binding 过期清理**：绑定行只增不删（仅 `/reset` 时删除对应行），长期运行无限增长；加定时清理（如 30 天未活跃），harness 侧会话清理策略对齐
- [ ] **隧道调试配置固化**：跨机调试的正反向 SSH 隧道命令（API 3000 / WebUI 6099 / 反向上报 8080 三合一）已验证可用，建议固化进 README「QQ 通道」小节备查

## 体验

- [ ] **超时告知开关**：`chat-timeout-seconds` 触发后静默放弃回复（用户端无感知），可加可配置开关——超时后回一条「处理超时请稍后重试」；注意与限频、静默语义的交互，默认仍建议静默

## 附录：跨机调试隧道（已验证）

```bash
# 三合一：正向 API 3000 + WebUI 6099 + 反向上报 8080（Windows PowerShell；本地 harness 监听 8080）
ssh -i "<SSH 私钥路径>" -N -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -L 3000:127.0.0.1:3000 -L 6099:127.0.0.1:6099 -R 8080:127.0.0.1:8080 root@<SERVER_IP>

# 远端 NapCat 在 Docker 内：容器够不到服务端 127.0.0.1，需 socat 把上报转接到网桥（无需改 sshd）
#   socat TCP-LISTEN:8080,bind=172.17.0.1,fork,reuseaddr TCP:127.0.0.1:8080
#   NapCat httpClients.url = http://172.17.0.1:8080/onebot/event
```

