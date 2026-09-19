# 📚 知识库问答（RAG）

> README「REST 接口」的 RAG 深入篇：触发条件、多知识库与 agent 绑定、目录监听 / 混合检索等增强配置。

---


把文档放进 `knowledge/` 目录（`.md` / `.txt`，支持 front-matter `title:`），摄取后路径 A/B 回答自动检索注入。触发是**每次组装 prompt 前的旁路检查**：总开关、角色名单、查询长度、相关度、注入预算五层条件全部满足才注入，任一不满足静默降级、主链路零感知——全部配置驱动（`application.yaml` 的 `app.knowledge.*`），决策流程与时序图见 [`data-flow.md` 5i 节](data-flow.md#5i-rag-知识检索注入数据流prompt-组装前旁路)。

```bash
mkdir -p knowledge && cp 你的文档.md knowledge/
curl -X POST http://localhost:8080/api/knowledge/sync          # 增量摄取（只处理 mtime 变更的文档）
curl 'http://localhost:8080/api/knowledge/search?q=部署步骤'     # 调试检索看命中
```

回答中出现 `【出处N】` 内联引用时，CLI 回合末尾会打印「来源:」尾注；`meta.sources` / `ChatResponse.sources` 携带结构化出处（文档名/标题/相关度）。

#### 🗂️ 多知识库与 agent 绑定

`knowledge/` 的一级子目录即独立知识库（kb 标识），根目录散文档归公共库 `default`：

```bash
mkdir -p knowledge/java knowledge/frontend        # 一级子目录 = 知识库
cp spring.md knowledge/java/ && cp vue.md knowledge/frontend/
curl -X POST http://localhost:8080/api/knowledge/sync
```

在 `agent` 表 `knowledge` 列填写逗号分隔的 kb 标识（如 `java,frontend`）即可把 agent 绑定到指定知识库——检索时按向量 metadata 的 `kb` 字段过滤，agent 只读绑定的库，防止读串；列留空/NULL = 未绑定，不触发知识库检索。文档在子目录间移动（kb 变更）会在下次 sync 自动重摄取补齐。

#### ⚙️ 知识库增强配置（app.knowledge.*）

```yaml
app.knowledge:
  watch-enabled: false            # 目录监听自动摄取总开关：开启后 knowledge/ 增删改文件，
                                  # 静默期过后自动触发一次增量摄取（免手动 sync；并发安全，
                                  # 与手动 sync 同时到达时后到者跳过、由下一轮事件补齐）
  watch-debounce-seconds: 3       # 文件事件静默期（秒）：批量拷贝/编辑器原子写合并为一次
  hybrid-enabled: false           # BM25 混合检索总开关：开启后向量 + BM25 双路 RRF 融合重排，
                                  # 关键词/编号/专有名词类查询字面精确召回更好（默认关 = 纯向量）
  bm25-max-chunks: 20000          # BM25 内存索引规模护栏：chunk 总数超过则不建索引、退化为纯向量；0 = 不限
  search-timeout-seconds: 10      # 检索限时（秒）：嵌入+向量查询在守护线程池限时执行，超时中断降级
                                  # （不注入知识块、不阻断聊天主链路）；0 = 关闭包裹直通现状
```

- **目录监听**（`watch-enabled`）：默认关。开启要求启动时 `knowledge/` 目录已存在（Docker 挂载天然保证）；监听范围 = 根目录 + 一级子目录，监听期间新建的一级子目录自动补注册；监听线程异常仅告警并自动恢复，绝不影响应用。
- **混合检索**（`hybrid-enabled`）：默认关，关闭时行为与纯向量完全一致。开启后 BM25 索引从 PG 向量表按需懒构建（`sync`/`delete` 后自动失效重建），检索输出仍受 `top-k` 约束，融合分数归一化到 (0,1] 保持「相关度 %.2f」渲染口径。
- **检索限时与预取**（`search-timeout-seconds`）：嵌入端点/PG 挂起时请求线程不再分钟级阻塞——超时中断按既有语义静默降级。同时入口在 RouteJudge 判定期间并行预取检索（无数据依赖，judge 与 RAG 耗时重叠），结果按「同会话 + 同 query + 同 kb 绑定」缓存短路命中，编排专家节点（query 为子任务描述）不受影响仍精准现查。
- **上传**：`POST /api/knowledge/upload`（multipart，`.md`/`.txt` ≤1MB，`kb` 可选且仅允许小写字母/数字/-/_）只落盘不自动摄取——随后调 sync 或依赖目录监听生效；管理页上传成功会自动链同步。


---

[⬅ 返回 README](../README.md)
