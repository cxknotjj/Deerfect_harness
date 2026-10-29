# Checklist

- [ ] V33 建表 kb_retrieval_log：字段/索引符合 spec，ObservabilityLogCleaner 纳入同 retention 清理
- [ ] KbRetrievalRecorder：null 零行为、采集异常吞掉 WARN 单行不阻断主链路
- [ ] 执行期四类事件正确落行：cache_hit（duration≈0/hit_count NULL）、inline_query（hit_count=注入条数）、无命中（hit_count=0）、降级（ok=0 + error_msg）
- [ ] 预取三来源正确标记：entry_prefetch / lead_prefetch / subtask_prefetch，trace 列 NULL
- [ ] 执行期行 turn_id/trace_id 填全（AgentRequestSpecFactory 的 trace 参数透传）
- [ ] 零行为短路（未绑定/aggregator/query 过短）不记行
- [ ] 签名扩展（buildKnowledgeBlock/prefetch/submit）后检索返回值、缓存命中、预算截留行为与现状逐字段一致
- [ ] 既有调用点全部适配编译，主链路零语义变化
- [ ] `mvn -pl server -am test` 0 失败 0 错误
