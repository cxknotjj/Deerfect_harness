# Tasks

- [x] Task 1: 配置层：`NapCatProperties` 新增 `Emoji` 嵌套类（字段：`enabled`/`dir`/`indexFile`/`maxPerReply`/`probability`/`sendDelayMs`/`cheerEmoji`；纯绑定零默认值）+ `application.yaml` 新增 `napcat.emoji` 行为配置块（不含映射表）+ 新建 `channel/emoji-index.json` 映射表文件（4 条示例，进 git）
  - [x] 1.1 `Emoji`/`EmojiItem` 字段与 accessor
  - [x] 1.2 yaml 配置块：enabled/dir/index-file/max-per-reply/probability/send-delay-ms/cheer-emoji
  - [x] 1.3 `channel/emoji-index.json`：表情名 → {path（相对 dir 文件名）, tags}
- [x] Task 2: 新增 `channel/qq/EmojiReplies` 组件：Jackson 启动加载映射 JSON（缺失/损坏 WARN 降级空映射）+ `pickFor(chunkText)` 匹配（tag contains 优先 → 句尾 ~/！/! 兜底 cheer-emoji）+ 文件校验（`File::isFile`）+ image 段生成（`base64://` 内联图片，跨机/Docker 可用）；`Random` 字段包内可注入（probability 边界测试）
- [x] Task 3: `OneBotEventServiceImpl.sendReply` 集成钩子：每条 chunk 发送后经 `EmojiReplies.pickFor` 检测 → 命中且未超上限且概率通过 → `chunkPause(sendDelayMs)` 停顿 → 发送 image 段；`sentEmojis` 计数；中断返回 false 即停止后续发送；构造函数内部创建 `emojiReplies`（包内可见字段，签名不变）
- [x] Task 4: 单元测试：新增 `EmojiRepliesTest`（JSON 加载与损坏降级/tag 命中/多命中随机/句尾兜底/tag 优先级/文件缺失/段格式与 URI/enabled=false）+ `OneBotEventServiceImplTest` 集成用例（条间表情顺序/max-per-reply 上限/probability=0 不发/群聊私聊路径/chunkPause 延迟断言）
- [x] Task 5: 验证：沙箱外 `mvn test`（OneBotEventServiceImplTest + EmojiRepliesTest 全绿）+ `todo_list.md` 表情包条目勾选并注明验证方式

# Task Dependencies
- Task 2 依赖 Task 1（读取 Emoji 配置）
- Task 3 依赖 Task 1、Task 2
- Task 4 依赖 Task 1 ~ Task 3
- Task 5 依赖 Task 4
