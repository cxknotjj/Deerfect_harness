# 外部工具：三层模型与实现边界（2026-11-11）

> core（需运行时上下文）/ external（MCP + 声明式 HTTP）/ skill（编排）。
> 记录为什么这样切、哪些决定已经收盘、以及实测踩到的坑。

## 外部工具：清单驱动，而不是给每个接口写一个类（2026-11-11）

工具面分三层，边界由「是否需要运行时上下文」决定：

| 层 | 放什么 | 为什么 |
| --- | --- | --- |
| core | `history` / `memory` / `sandbox.*` / artifact（`send_file`、TTS、点歌） | 要 `turnId`、容器句柄、工件库，外部进程拿不到 |
| external | MCP（第三方/多语言）+ 声明式 HTTP（自家 REST） | 接新系统不改 Java、不重打包 |
| skill | 编排与说明 | 把「十个近义工具」压成「一个工具 + 一份说明书」 |

声明式 HTTP 的形状是 `tools/http/*.yaml`：一个清单 = 一个工具 + N 个资源。
资源绑定**固定路径**与**参数白名单**，模型只能选资源名和参数值，不能拼路径——
这是它和通用 `http_request` 的关键区别，后者等于给所有 agent 一条无约束的出站通道。

为什么不「一个端点一个工具」：WMS 有二十个只读端点，逐个做成工具会让工具面塞满近义名字
（`wms_search_stock` / `wms_search_stock_in_orders` / `wms_get_stock_in_order`…），
模型在选工具这一步就开始出错。收敛成 `wms_query` 后，「查什么」是枚举，「怎么查」在 skill 里。

响应整形也放在清单里：`format: jeecg` 拆 `result` 包装（records 分页 / 数组 / 单对象三种形态），
`drop-fields` 剔除 `createBy` 这类审计字段。这些是数据，不该每个接入方写一遍代码。

WMS 这一步走了两版，值得记下来：

- **V18** 先把十个工具换成 `tools/http/wms.yaml`（`wms.query` pack），组合查询下沉到 skill。
- 落地后确认适配器本身更适合承载这段逻辑：它是 MCP 服务，有进程隔离、调用方鉴权，
  而且「商品名换 productId」本来就在服务端做。于是 **V19** 把分发器做进 `WmsTools`：
  只暴露一个 `wms_query(resource, params)`，十六个资源在内部映射到原有的十段查询逻辑。
- `tools/http/wms.yaml` 删除——同一批端点写两遍必然漂移。HTTP 机制保留给
  「不值得起一个服务」的轻量集成。

代价与收益：模型仍然只看到一个工具、一份说明书；不同的是组合查询回到服务端，一次调用完成，
不用模型先查商品再查库存。参数白名单从 yaml 移进 `WmsTools.ALLOWED`，
`WmsQueryDispatchTest` 与 `WmsToolListGuardTest` 一起守着（后者原来守十个工具名，现在守资源契约）。

**实测发现的一个坑**：模型会把嵌套对象序列化成字符串
（`"params":"{\"productId\":\"p1\"}"`），只接受 Map 的实现会在绑定阶段直接失败。
HTTP 工具和 MCP 分发器现在都用 `Object` 接参数、两种形态都认，
`HttpToolCallbackTest` 与 `WmsQueryDispatchTest` 各有一条用例守着。
这类问题单测造不出来——只有真跑一次、看服务端收到的 query string 才会暴露。

能力清单：`GET /api/capabilities` 按来源聚合（core/sandbox/mcp/http/skill），
每条带定位符（源文件、清单路径、URL、技能目录）与工具名；`POST /api/capabilities/reload`
重载两类外部来源：HTTP 清单与 MCP server 配置。

MCP 重载的做法：把「规格 + 连接状态」收成一份不可变快照，reload 时原子换引用；
旧连接延迟 30 秒关闭——在途调用跑完，新请求立刻走新连接。失败仍按 server 隔离，
不影响其他 server。改 `mcp-config.json` 不再需要重建容器。

`wms-mcp-adapter` 是 WMS 的唯一实现，不再删除：它是 MCP 服务，进程隔离、调用方鉴权、
组合查询都在它那边；声明式 HTTP 机制留给「不值得起一个服务」的轻量集成。
