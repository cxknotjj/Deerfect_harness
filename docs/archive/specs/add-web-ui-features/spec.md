# Web 端界面功能落地评估报告

> 应用户要求,本文档为**评估报告**(非实施 spec):盘点 web 端界面功能的落地缺口,
> 重点评估后端变动。暂缓项已按用户决定标注(反馈/偏好/权限/附件先不做)。

## 评估范围与方法
- 浏览器实测最新 web 端(393×852 窄屏)+ 前端源码核对 + 后端 5 个 Controller
  端点面逐一核对(Harness/Chat/ToolCall/LlmCall/ProviderAdmin/KnowledgeAdmin)
- 范围:① 界面占位交互;② 后端已有能力与界面暴露度的差集

## 一、已实现边界(非占位,无需评估)
Session log 下载(窄屏隐藏)、user 消息复制/删除、assistant 复制/重新生成/
错误重试、代码块复制、Agent 切换绑定(空会话置灰)、主题切换、流式 progress
实时轨迹行、SSE 真流式 + 会话历史 + 本地缓存。品牌名已统一 DeerFect。

## 二、界面占位交互评估(重点:后端变动)

### 总览
| 占位交互 | 前端量 | 后端量 | 结论 |
|---------|-------|-------|------|
| 轨迹 tab | 中 | **零改动** | 建议做(纯前端) |
| 跨会话搜索 | 中 | 小(1 条接口) | 建议做 |
| 消息级反馈(赞/踩/分享) | 小 | 中(seq 化 + 新表 + 2 接口) | **暂缓**(用户决定) |
| 用户偏好(设置) | 小 | 零改动 | **暂缓**(用户决定) |
| 会话级工作区权限 | 小 | 中(加列 + 接口 + 工具过滤链) | **暂缓**(用户决定) |
| 附件上传 | 中 | 大(上传 + prompt 注入) | **暂缓**(用户决定) |

### 1. 轨迹 tab(建议做,后端零改动,纯前端)
后端数据已齐全:`GET /api/tool-calls?sessionId=`(工具调用:参数摘要/状态/耗时/
来源 server)与 `GET /api/llm-calls?sessionId=`(LLM 调用:耗时/token)。
与已有流式 progress 行的边界:progress 是流式过程实时轨迹,轨迹 tab 是
**历史回看**(刷新/切回旧会话后仍可查),互补不重复。
前端工作:tab 切换拉取两组数据按时间合并渲染时间线,空态替换
「轨迹功能开发中」文案。

### 2. 跨会话搜索(建议做,后端仅 1 条接口)
- **后端**:新增 `GET /api/harness/search?q=&limit=`——会话名
  (session.name LIKE)+ 消息内容(session_messages 快照 JSON LIKE),
  返回 sessionId + 会话名 + 命中摘要。首期 LIKE 足够,量大再议全文索引
- **前端**:搜索弹层;入口需**新增桌面顶栏按钮**(实测桌面当前无搜索入口,
  仅窄屏 rail 🔍 有),结果点击跳转会话

### 3. 暂缓项评估存档(按用户决定先不做,评估结论保留备查)
- **消息级反馈**:赞/踩/分享按钮已存在(SVG),但真实落地依赖**消息稳定 seq 化**
  (后端快照 JSON 加递增 seq + 存量按数组下标兜底)+ 新表 `message_feedback`
  (UNIQUE(session_id, message_seq))+ POST 反馈 / GET 反馈映射两条接口。
  seq 化是本项前置,可随本项一起做
- **会话级工作区权限**:session 表加 workspace_mode 列 + 读写接口 +
  工具装配链按会话模式剔除写类工具(与 Agent 级 least-privilege 过滤取交集);
  前端 🛡 下拉需适配窄屏图标态(脱离文档流弹出,规避移动端重叠)
- **附件上传**:后端最大项——`POST /api/files`(文本类白名单 + 1MB 上限)+
  ChatRequest 扩展 attachments + prompt 注入(超长截断)+ 快照记录文件名;
  首期需明确排除图片多模态(需 qwen-vl)与 PDF/Word 解析(需 Tika)
- **用户偏好(设置)**:纯前端即可(rail ⚙ + SessionList 底部双入口,
  主题/默认 Agent/清缓存,localStorage 存储),无后端缺口

## 三、后端已有但 web 端未暴露的能力评估(重点)

| 能力 | 后端现状 | 界面价值 | 建议 |
|------|---------|---------|------|
| 目标编排状态 + 断点续跑 | `GET /api/harness/goals`、`GET /api/chat/goal-status`、`POST /api/chat/resume` 均已实现;CLI 已有 /resume 提示 | COMPLEX 编排在 web 端失败/中断后,用户既看不到目标状态也无法续跑——**这是当前 web 端最实际的功能缺口** | 轨迹 tab 内并入 goal 卡片(状态 + 续跑按钮),后端零改动 |
| LLM 调用观测 | `GET /api/llm-calls?sessionId=` 已实现 | 轨迹 tab 直接消费 | 随轨迹 tab 落地 |
| 工具调用观测 | `GET /api/tool-calls?sessionId=&serverName=` 已实现 | 轨迹 tab 直接消费 | 随轨迹 tab 落地 |
| 知识库管理 | `/api/knowledge` 端点齐全(sync/documents 分页/search/upload/delete) | web 端无任何知识库界面,知识同步/检索目前只能 API 调用 | 运维向功能,价值明确但体量不小(上传/列表/检索/删除面板),建议**独立 spec 另议** |
| 模型供应商管理 | `GET/POST /api/providers` 已实现 | 低频运维操作 | 暂缓,同上并入管理面板议题 |
| 同步聊天接口 | `POST /api/chat` 已实现 | stream 已覆盖同步场景 | 不暴露,无需评估 |

## 四、结论
1. **后端不是瓶颈**:观测(llm-calls/tool-calls)、编排(goals/resume)、管理
   (knowledge/providers)端点已齐全;界面功能的真实缺口在前端界面,
   后端仅需补 1 条搜索接口
2. **建议实施(仅两项,后端改动 1 条接口)**:
   - 轨迹 tab(纯前端,并入 goal 状态/续跑卡片,补齐编排闭环)
   - 跨会话搜索(后端 1 条接口 + 前端弹层,补桌面入口)
3. **暂缓四项**(反馈/偏好/权限/附件)按用户决定不做,评估结论存档备查;
   其中反馈的 seq 化前置关系已记录,后续启动时直接引用
4. **另议**:知识库/供应商管理面板为运维向独立功能,建议单独立项评估
