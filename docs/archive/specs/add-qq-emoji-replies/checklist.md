# Checklist

- [x] `napcat.emoji` 配置键与 `NapCatProperties.Emoji` 字段一一对应，Java 侧纯绑定零默认值
- [x] 映射表独立于 yaml：`channel/emoji-index.json` 定义表情名 → {path, tags}，Jackson 启动加载一次；文件缺失/JSON 损坏 WARN 降级空映射，不影响启动
- [x] tag 关键词命中 → 发送对应表情 image 段（`base64://` 内联图片内容），群聊 `sendGroupMsg`/私聊 `sendPrivateMsg` 各自正确
- [x] 句尾 `~`/`！`/`!` 兜底触发 `cheer-emoji`，tag 命中优先于句尾符号，多 tag 命中随机取一
- [x] `max-per-reply` 限流生效（0 = 不限制）；`probability` 0/1 边界行为正确（0 必不发、1 必发）
- [x] 表情文件缺失/目录缺失仅 WARN 跳过，文本 chunk 回复不受任何影响
- [x] `enabled=false` 时表情模块零行为（不读目录、不检测、不发送）
- [x] 表情发送前 `send-delay-ms` 停顿走 `chunkPause`，中断返回 false 即停止后续发送
- [x] 表情模块异常只记日志不外抛，主回复链路无感
- [x] `mvn test` 全绿（OneBotEventServiceImplTest + EmojiRepliesTest，Tests run: 38, Failures: 0, Errors: 0, BUILD SUCCESS）
- [x] `todo_list.md` 表情包条目勾选并注明验证方式
