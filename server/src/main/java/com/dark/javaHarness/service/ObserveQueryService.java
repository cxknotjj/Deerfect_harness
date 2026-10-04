package com.dark.javaHarness.service;

import com.dark.javaHarness.domain.entity.KbRetrievalLogEntity;
import com.dark.javaHarness.domain.entity.LlmCallLogEntity;
import com.dark.javaHarness.domain.entity.ToolCallLogEntity;
import java.util.List;

/**
 * 观测日志查询服务：LLM 调用 / 工具调用 / RAG 检索三张观测表的读取口径统一收口，
 * Controller 不直连 mapper（分层约定：controller 只做参数映射与路由）。
 * 全部按 id 倒序、limit 钳制 [1, 200] 防全表拖取。
 */
public interface ObserveQueryService {

    /** LLM 调用日志：sessionId 可空查全量 */
    List<LlmCallLogEntity> listLlmCalls(String sessionId, int limit);

    /** 工具调用日志：sessionId / serverName 均可空查全量 */
    List<ToolCallLogEntity> listToolCalls(String sessionId, String serverName, int limit);

    /** RAG 检索日志：sessionId 可空查全量 */
    List<KbRetrievalLogEntity> listKbRetrievals(String sessionId, int limit);
}
