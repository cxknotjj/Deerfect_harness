# wms-mcp-adapter

**案例模块**：把第三方 WMS（仓储系统）的只读 REST 端点包装成 10 个 MCP 工具，
供 harness 通过 MCP client 接入，实现「用自然语言查库存/查单据」。

独立 Spring Boot 应用，**不进 server 的 fat jar**，进程外部署——WMS 不可用时不影响主服务。

```text
harness (MCP client) ──HTTP /mcp──▶ wms-mcp-adapter ──GET──▶ WMS
```

## 目录结构

```text
wms-mcp-adapter/
├── pom.xml
├── wms-openapi.json          # WMS 的 OpenAPI 描述（端点常量与工具清单的来源）
└── src/
    ├── main/java/com/dark/wms/
    │   ├── WmsMcpAdapterApplication.java   # 独立启动类
    │   ├── client/  (4)                    # WMS HTTP 客户端
    │   ├── config/  (2)                    # 配置与调用方鉴权
    │   └── tool/    (1)                    # MCP 工具集
    ├── main/resources/application.yaml      # 端口、WMS 地址与凭证占位
    └── test/java/com/dark/wms/...           # 4 个测试类，含「只读守护」
```

### `client/`

| 类 | 作用 |
| --- | --- |
| `WmsRestClient` | 对 WMS 的 GET 调用，统一超时与错误翻译 |
| `WmsEndpoints` | 10 个只读端点的路径常量。**只有 GET**——写操作、导出、面单打印、`/ai/*` 一律不收录 |
| `WmsPage` | WMS 分页结构 → 工具返回值的统一形状 |
| `WmsApiException` | WMS 侧错误，最终被翻译成结构化 JSON 而不是抛栈 |

### `config/`

| 类 | 作用 |
| --- | --- |
| `WmsProperties` | WMS 地址、凭证、超时 |
| `CallerTokenFilter` | 调用方 token 校验：`/mcp` 不是公网端点，但也不能裸奔 |

### `tool/`

`WmsTools`：10 个 `wms_` 前缀工具，全部只读，中文 description 与参数说明供模型正确选择。

| 类别 | 工具 |
| --- | --- |
| 查询类（7） | `wms_get_product`、`wms_get_stock_in_order`、`wms_get_out_order`、`wms_search_products`、`wms_search_stock`、`wms_search_tasks`、`wms_search_warehouse_network` |
| 单据类（3） | `wms_search_stock_in_orders`、`wms_search_out_orders`、`wms_search_shipments` |

约定：错误以 `{"error":true,"message":...}` 文本返回，不抛异常——MCP 会话保持存活，
模型能看到失败原因并自行换参数重试。

## 这个模块的示范意义

它回答的是「怎么把一套已有系统接进 harness」：

1. **零硬编码**：地址与凭证全走环境变量，仓库里没有真实地址。
2. **只读守护有测试**：`WmsToolListGuardTest` 校验工具清单与端点常量一致，
   任何人往 `WmsEndpoints` 里加一个写端点都会失败——「只读」不是口头承诺。
3. **进程外解耦**：适配器崩了不影响 harness 主服务，反之亦然。
4. **工具描述是给人看的**：中文 description 与参数说明，模型选错工具时能看出是描述问题还是路由问题。
