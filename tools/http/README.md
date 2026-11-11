# 声明式 HTTP 工具清单

本目录放 `*.yaml` / `*.yml`，由 `HttpToolLoader` 在启动时装载；改完调用
`POST /api/capabilities/reload` 即可生效，不用改 Java、不用重新打包、不用重启。

## 什么时候用这里

**适合**：把几个自家 REST 只读端点包装成工具，没有业务逻辑、不需要给别的客户端复用、
也不需要独立进程隔离。清单把 URL 路径、参数白名单、鉴权头名和响应整形固定下来，
模型只能选 `resource` 和参数值，**不能自由拼路径或出站**——这是它和通用 `http_request`
的根本区别。

**不适合**（改走 MCP server）：需要业务组合逻辑、进程隔离、调用方鉴权，或一个服务要被
多个客户端复用。WMS 就是这种情况：曾经在这里有过一份 `wms.yaml`，后来改回
`wms-mcp-adapter` 的单个 `wms_query` 分发器，清单随之删除。

## 当前是空目录，是刻意的

仓库里暂时没有需要走这条路的集成。`HttpToolLoader` 目录不存在或为空时只记一条日志、
工具面为空，不影响启动。保留这个目录是为了让 `docker/Dockerfile` 的 `COPY tools`
与 compose 的 `../tools` 挂载有稳定落点——git 不跟踪空目录，所以留了这份 README。

## 清单格式

```yaml
pack: <包名，agent.tools 里引用它>
base-url: ${XXX_API_BASE:}
auth: { header: ${XXX_TOKEN_HEADER:X-Access-Token}, token-env: XXX_API_TOKEN }
response: { format: raw | jeecg, drop-fields: [createBy], max-chars: 6000 }
tool: { name: <工具名>, description: "...", resource-param: resource, params-param: params }
resources:
  <资源名>:
    path: /固定的/只读路径
    params: [允许的参数名]
```

凭据只写**环境变量名**，不要写进文件；`path` 必须是固定字符串，不做模板拼接。
字段完整定义见 `HttpToolManifest`，设计取舍见
[docs/design/2026-11-11-external-tools.md](../../docs/design/2026-11-11-external-tools.md)。

装载结果可在 `GET /api/capabilities` 里查到，坏清单会在那里标出问题
（`app.tools.http.fail-fast=true` 时改为启动即失败）。
