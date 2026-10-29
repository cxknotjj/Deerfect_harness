# Checklist

- [x] LeadOutputParser 解析 toolPacks 数组（trim/去空/保序），缺省空名单，旧格式兼容不变
- [x] 包资格校验：is_internal=0 且 tools 列非空；is_internal=1 行被拒绝
- [x] 合法包解析出工具名单（tools 列 CSV）与纪律段（行 prompt 原文）
- [x] 非法包名 / 行无工具 / 回调解析为空（adapter 未启动空工具面）均 warn 单行并静默跳过，不阻断编排
- [x] `subtaskPacks_0..3` 状态键注册 replace 策略，lead 出口写入；旧 checkpoint 缺键安全回退空名单
- [x] Assembly.extraToolNames 通道生效：包工具在装饰链之前并入、按回调名去重、装饰链（预算）统一覆盖
- [x] 未声明 toolPacks 时工具面与第一阶段性状完全一致；disableTools 短路不受影响
- [x] 纪律段以 `【领域规范·包名】` 前缀追加到 user 文本（brief 之后），system 装配与 fallbackSystem 语义零改动
- [x] `app.context.subtask-max-count` 生效：clamp [1,4]、0/超界回退内置兜底 4、配置下调时超额子任务丢弃；KeyStrategy 槽位 0..3 不变
- [x] `app.context.subtask-wall-clock-seconds` 生效：yaml 默认 600、0=不限制；超时经 deadline 包装取消令牌在 token 边界中止，写占位结果 + warn 单行，聚合注入超时降级说明
- [x] V32 迁移 REPLACE 幂等：模板清单移除 wms 专家引导、新增 toolPacks 用法说明
- [x] wms 白名单容错保留：旧习惯 `agent:"wms"` 指派仍由 wms 表行执行
- [x] RAG 预取链路参数不变（kb 绑定按执行模板行解析）；直答路径（wms 专属绑定会话）不受影响
- [x] `mvn -pl server -am test` 0 失败 0 错误（659 tests, 0 failures, 0 errors, BUILD SUCCESS）
