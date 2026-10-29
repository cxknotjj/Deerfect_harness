# 知识库增强余项 Spec（目录监听 / BM25 混合检索 / Web 管理页）

对应 HARNESS_TODO「知识库增强余项」（拆自 RAG 条目遗留）。

## Why

当前知识库有三块短板：① 知识文档增删改后必须手动 `POST /api/knowledge/sync` 才生效；② 检索是纯向量相似度，对关键词、编号、专有名词类查询召回差（嵌入把字面信息抹掉了）；③ 管理台账/删除/调试检索只能 curl REST，无可视化入口。

## What Changes

- 新增 **WatchService 目录监听**：`knowledge/` 目录文件变更自动触发增量摄取（免手动 sync），默认关闭（`app.knowledge.watch-enabled=false`），开启后同步语义与手动 sync 完全一致
- **sync 并发防护**：手动 sync 与监听触发可能并发，加锁串行化（当前 sync 无并发保护，监听上线前就必须补上）
- 新增 **BM25 内存索引 + RRF 融合**：chunk 文本（PG 向量表 content 列）建内存 BM25 索引，检索时向量 leg + BM25 leg 按倒数排名融合重排；默认关闭（`app.knowledge.hybrid-enabled=false`），关闭时行为与现状逐字节一致
- **管理端点小幅扩展**：`/search` 增加可选 `full=true` 返回片段原文（向后兼容）；新增 `POST /api/knowledge/upload`（multipart 上传 .md/.txt 到指定 kb）
- 新增 **单文件静态管理页** `static/knowledge.html`（原生 HTML+JS，无前端框架）：台账分页、删除、触发同步、上传、调试检索（含片段全文）
- application.yaml `app.knowledge` 块新增 4 个键；**无 DB schema 变更**（不需要新迁移）

## Impact

- 受影响代码：
  - `knowledge/` 包：新增 `KnowledgeDirectoryWatcher`、`KnowledgeBm25Index` 两个组件
  - `KnowledgeServiceImpl`：sync 加锁、检索索引失效钩子、构造器新增参数（`KnowledgeConfig` 与测试同步更新）
  - `KnowledgeConfig`：新 bean 条件装配（watcher、bm25 均挂 `@Lazy` 与既有降级语义一致）
  - `KnowledgeAdminController`：upload 端点 + /search full 参数
  - `application.yaml`、`README.md`、`static/knowledge.html`、`src/test/.../knowledge/*`
- 明确不做：PG 中文分词扩展（zhparser 等，需换 PG 镜像，过重）、前端框架/构建链、管理页鉴权（与现有管理端点同等暴露面，项目现状无鉴权层）、目录不存在时的监听热注册（启动时目录必须存在——Docker 挂载天然保证，文档化即可）

## ADDED Requirements

### Requirement: 目录文件监听自动增量摄取

`app.knowledge.enabled=true` 且 `app.knowledge.watch-enabled=true` 时，系统 SHALL 通过 NIO WatchService 递归监听 `app.knowledge.dir`（根目录 + 一级子目录，与扫描器两层口径一致；监听期间新建的子目录 SHALL 被补注册），并在文件变更静默期（`app.knowledge.watch-debounce-seconds`，默认 3）过后触发一次 `KnowledgeService.sync()`。

#### Scenario: 新增/修改文档自动摄取
- **WHEN** 向 knowledge/（或其一级子目录）放入/修改 .md/.txt 文件
- **THEN** 静默期过后自动执行一次增量摄取，日志输出与手动 sync 同格式；`kb_document` 台账与向量 chunk 落位一致

#### Scenario: 删除文档自动清理
- **WHEN** 从 knowledge/ 删除某文档
- **THEN** 自动 sync 触发孤儿清理（向量 chunk + 台账行删除），与手动 sync 的 `cleanupOrphans` 语义一致

#### Scenario: 事件风暴去抖
- **WHEN** 短时间内（< 静默期）产生连续文件事件（如批量拷贝、编辑器原子写）
- **THEN** 合并为一次 sync 执行，不按事件数放大

#### Scenario: 监听不致命
- **WHEN** 监听线程发生异常（目录被移走、WatchService closed 等）
- **THEN** 仅 warn 日志并尝试恢复监听，绝不中断应用；`watch-enabled=false`（默认）时零行为变化、不创建监听线程

### Requirement: sync 并发串行化

`KnowledgeService.sync()` SHALL 串行化执行：同一时刻至多一个 sync 在跑，并发调用方不阻塞等待而是**跳过本轮**（监听触发场景下静默期后的下一轮事件会自然补齐；手动 API 场景返回明确的「已有同步在执行」提示）。

#### Scenario: 手动 sync 与监听触发并发
- **WHEN** 手动 `POST /api/knowledge/sync` 与监听触发的 sync 同时到达
- **THEN** 后到者立即返回不重复摄取，向量库不出现并发写

### Requirement: BM25 混合检索与重排

`app.knowledge.hybrid-enabled=true` 时，`KnowledgeService.search()` SHALL 执行双路检索：向量 leg（现状逻辑，`min-score`/`top-k` 照旧作用于本路）+ BM25 leg（内存索引，chunk 文本取自 PG 向量表 content 列，中文按字符 bigram 分词、ASCII 按小写词元），两路排名经 **RRF（k=60）** 融合重排后输出；融合分数归一化到 (0,1] 填入 `KnowledgeHit.score`（保持「相关度 %.2f」渲染契约）。

#### Scenario: 关键词查询命中
- **WHEN** 查询包含与文档字面精确匹配的关键词/编号，且向量检索对该文档打分低于 `min-score`
- **THEN** 混合模式下该文档经 BM25 leg 进入融合结果（BM25 leg 不受 `min-score` 约束——两路分数语义不同，不可混用阈值）

#### Scenario: 关闭开关零回归
- **WHEN** `hybrid-enabled=false`（默认）
- **THEN** 不构建 BM25 索引、不产生任何 PG 额外查询，`search()` 输出与现状完全一致

#### Scenario: 索引失效重建
- **WHEN** sync（含监听触发的自动 sync）或 delete 完成
- **THEN** BM25 内存索引标记失效，下次检索前按需重建（懒重建，不阻塞摄取路径）

#### Scenario: 规模护栏
- **WHEN** chunk 总数超过 `app.knowledge.bm25-max-chunks`（默认 20000，0 = 不限）
- **THEN** 索引构建跳过并 warn，检索退化为纯向量（护栏防内存失控，融合是增强不是义务）

### Requirement: 知识文档上传

`POST /api/knowledge/upload` SHALL 接收 multipart 文件（仅 .md/.txt，单文件 ≤ 1MB）与可选 `kb` 表单字段（归库子目录名，白名单 `[a-z0-9-_]`，空 = 根目录 default 库），文件名清洗（拒绝路径分隔符/隐藏文件）后落盘到 `app.knowledge.dir` 对应位置；**只落盘不自动摄取**（前端随后调 sync，保持单一摄取代码路径）。重名文件覆盖写（mtime 变更自然触发重摄取）。

#### Scenario: 上传后同步生效
- **WHEN** 管理页上传 `notes.md` 到 kb=`java`，随后触发 sync
- **THEN** 文件出现在 `knowledge/java/notes.md`，摄取台账新增该行，检索可见

#### Scenario: 非法输入拒绝
- **WHEN** 上传 .pdf、超 1MB、或 kb 含路径分隔符/非法字符
- **THEN** 返回 400 与可读错误，不落盘

### Requirement: 静态知识库管理页

`GET /knowledge.html`（Spring Boot 静态资源，`src/main/resources/static/knowledge.html`，单文件原生 HTML+JS+CSS，中文界面）SHALL 提供：台账分页列表（kb/标题/文件名/mtime/chunk 数/状态）、删除（二次确认）、触发同步（展示 scanned/updated/skipped/chunks 结果）、上传（选择文件 + 可选 kb 输入，成功后自动链 sync）、调试检索（q + kb 过滤，展示命中片段全文与相关度）。

#### Scenario: 未启用降级提示
- **WHEN** `app.knowledge.enabled=false` 时打开管理页
- **THEN** 任一请求收到 503 后页面展示「知识库未启用」文案与开启指引（复用现有 `KnowledgeDisabledException` 的 message），不白屏

## MODIFIED Requirements

### Requirement: 调试检索响应可选全文

`GET /api/knowledge/search` SHALL 增加可选参数 `full`（默认 false）：false 时响应保持现状（KnowledgeSource 三字段，**向后兼容**）；true 时额外返回 `text`（chunk 原文），供管理页展示命中片段。

### Requirement: application.yaml 配置键

`app.knowledge` 块 SHALL 新增（数值键遵循全项目「0 = 关闭该约束」口径）：

```yaml
app.knowledge:
  watch-enabled: false            # 目录监听自动摄取总开关
  watch-debounce-seconds: 3       # 文件事件静默期
  hybrid-enabled: false           # BM25 混合检索总开关
  bm25-max-chunks: 20000          # BM25 索引规模护栏；0 = 不限
```

`KnowledgeProperties` 同步增加绑定字段；README 的 RAG 章节补一段配置说明。

## REMOVED Requirements

（无）
