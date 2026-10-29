# Tasks

- [x] Task 1: sync 并发串行化（后续监听功能的前置依赖）
  - [x] 1.1 `KnowledgeServiceImpl.sync()` 加 `ReentrantLock`：tryLock 失败的并发调用方立即返回「跳过」语义（复用 KnowledgeSyncView 计数或抛可读异常，实现时二选一并写清 javadoc；手动 API 需可感知，监听触发需可静默）
  - [x] 1.2 更新 `KnowledgeServiceImplTest` 并发用例（两线程同时 sync，断言仅一个执行）
- [x] Task 2: 目录文件监听（WatchService）
  - [x] 2.1 `KnowledgeProperties` 新增 `watchEnabled` / `watchDebounceSeconds` 字段 + application.yaml 配置键（默认 false / 3）
  - [x] 2.2 新建 `knowledge/KnowledgeDirectoryWatcher`：递归注册根目录+一级子目录、新建子目录补注册、事件合并（静默期 debounce）、单守护线程 executor 触发 sync、异常 warn + 恢复监听、`watch-enabled=false` 时不装配
  - [x] 2.3 `KnowledgeConfig` 条件装配 watcher（保持 @Lazy 与降级口径）
  - [x] 2.4 测试：临时目录 + 真实 WatchService 写入/删除文件，断言 debounce 后 sync 被调用（mock KnowledgeService）；事件风暴合并为一次
- [x] Task 3: BM25 内存索引
  - [x] 3.1 新建 `knowledge/KnowledgeBm25Index`：从 PG 向量表（vectorJdbcTemplate + `props.getPgvector().getTableName()`）按 kb 过滤拉取 chunk（id/content/metadata），bigram+ASCII 词元分词器、BM25 评分（k1=1.2/b=0.75 常量）、按 kb 惰性构建缓存
  - [x] 3.2 规模护栏：chunk 总数 > `bm25-max-chunks`（0=不限）时跳过构建并 warn
  - [x] 3.3 失效钩子：`KnowledgeServiceImpl.sync()`/`delete()` 末尾调用 `invalidate()`（sync 失败路径也失效）
  - [x] 3.4 测试：分词正确性、评分排序 sanity（含关键词命中文档排名靠前）、失效后重建
- [x] Task 4: RRF 混合融合接入 search
  - [x] 4.1 `KnowledgeServiceImpl.search()`：`hybrid-enabled=true` 时向量 leg + BM25 leg → RRF（k=60）融合 → 分数归一化 (0,1] 输出；false 时逐字节现状路径（不查 PG content、不建索引）
  - [x] 4.2 `KnowledgeConfig` 装配 Bm25Index 注入 `KnowledgeServiceImpl` 构造器（新参数），同步修测试构造调用
  - [x] 4.3 `KnowledgeProperties` 新增 `hybridEnabled` / `bm25MaxChunks` + application.yaml 键
  - [x] 4.4 测试：融合排序（两路都命中的文档置顶）、BM25-only 命中（向量低分关键词文档）、开关关闭零回归（现有测试全绿）
- [x] Task 5: 管理端点扩展
  - [x] 5.1 `GET /api/knowledge/search` 增加可选 `full` 参数（默认 false；true 时响应带 `text` 字段，向后兼容）
  - [x] 5.2 新增 `POST /api/knowledge/upload`：multipart（.md/.txt ≤1MB）+ 可选 `kb` 字段白名单校验与文件名清洗，落盘不摄取；application.yaml 补 `spring.servlet.multipart.max-file-size/max-request-size: 1MB`
  - [x] 5.3 测试：full 参数、合法/非法上传（扩展名/大小/kb 白名单/路径穿越）
- [ ] Task 6: 静态管理页
  - [x] 6.1 新建 `src/main/resources/static/knowledge.html`：台账分页、删除（confirm）、触发同步（结果提示）、上传（选文件+kb 输入，成功自动链 sync）、调试检索（full=true 展示片段全文+相关度）、503 未启用提示；单文件原生 JS，中文 UI
  - [ ] 6.2 手工验证：本地起服务（启用知识库）跑通五个功能；禁用知识库时打开页面看到降级文案
- [x] Task 7: 文档与全量回归
  - [x] 7.1 README RAG 章节补配置说明（4 个新键 + 管理页入口 `/knowledge.html` + 上传端点）
  - [x] 7.2 `mvn -s .mvn/settings.xml test` 全绿；HARNESS_TODO 该条目勾选

# Task Dependencies

- Task 2 依赖 Task 1（监听触发的 sync 必须已有并发防护）
- Task 4 依赖 Task 3（融合消费 BM25 索引）
- Task 6 依赖 Task 5（管理页消费 upload/full 接口）
- Task 7 依赖全部；Task 1 / Task 3 可并行起步
