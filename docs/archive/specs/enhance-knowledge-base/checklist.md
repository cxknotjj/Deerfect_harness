# Checklist

- [ ] sync 并发安全：两线程并发调 `sync()` 仅一个执行，另一个立即返回且不产生向量库并发写
- [ ] `watch-enabled=false`（默认）时不创建监听线程，应用行为与改动前逐字节一致
- [ ] `watch-enabled=true` 时：新增/修改/删除 knowledge/ 下 .md/.txt 文件，静默期后自动触发一次增量摄取，台账与向量 chunk 与手动 sync 结果一致
- [ ] 事件风暴（静默期内 N 次写入）合并为一次 sync；监听线程异常仅 warn 不影响应用
- [ ] `hybrid-enabled=false`（默认）时不建 BM25 索引、无 PG content 额外查询，现有知识库测试全绿（零回归）
- [ ] `hybrid-enabled=true` 时：字面关键词/编号类查询经 BM25 leg 进入融合结果（该文档向量分低于 min-score 仍可命中）；BM25 leg 不受 min-score 约束
- [ ] 融合分数归一化到 (0,1]，`KnowledgeHit.score` 契约（相关度 %.2f 渲染）不破坏；两路都命中的文档融合排名置顶
- [ ] sync/delete 完成后 BM25 索引失效，下次检索懒重建；sync 失败路径同样失效
- [ ] chunk 数超 `bm25-max-chunks`（0=不限）时跳过索引构建仅 warn，检索退化为纯向量
- [ ] `GET /api/knowledge/search` 不带 `full` 参数响应与现状一致；`full=true` 响应含 `text` 字段
- [ ] `POST /api/knowledge/upload`：合法 .md/.txt ≤1MB 落盘到指定 kb（白名单校验）；.pdf/超限/路径穿越/kb 非法字符返回 400 不落盘；上传只落盘不自动摄取
- [ ] `/knowledge.html` 五个功能可用：台账分页、删除（confirm）、触发同步、上传（成功自动链 sync）、调试检索（展示片段全文+相关度）
- [ ] `app.knowledge.enabled=false` 时打开 `/knowledge.html` 展示「知识库未启用」提示不白屏
- [ ] application.yaml 新增 4 个配置键（watch-enabled / watch-debounce-seconds / hybrid-enabled / bm25-max-chunks）并符合「0 = 关闭该约束」口径
- [ ] README RAG 章节含新配置说明与 `/knowledge.html` 入口；HARNESS_TODO「知识库增强余项」条目勾选
- [ ] `mvn -s .mvn/settings.xml test` 全量通过
