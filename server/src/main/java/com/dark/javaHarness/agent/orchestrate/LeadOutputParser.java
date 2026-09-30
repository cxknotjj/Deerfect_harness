package com.dark.javaHarness.agent.orchestrate;

import com.dark.javaHarness.enums.AgentConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * lead 拆解产物解析（纯静态）：解析 lead 模型返回的 JSON 子任务列表，兼容新旧格式
 * （brief 任务书可选），专家名按白名单归一化。不感知编排图与 LLM 调用。
 */
final class LeadOutputParser {

    private static final Logger log = LoggerFactory.getLogger(LeadOutputParser.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * lead 拆解可指派的专家白名单（wms 为领域模板，对应 V26 lead prompt 的可选专家清单）：
     * 不在名单中的 agent 名一律回退默认（general 语义）
     */
    private static final Set<String> EXPERT_WHITELIST = Set.of(
            AgentConstants.EXPERT_RESEARCHER,
            AgentConstants.EXPERT_CODER,
            AgentConstants.EXPERT_ANALYST,
            AgentConstants.EXPERT_WRITER,
            AgentConstants.EXPERT_WMS,
            AgentConstants.DEFAULT_AGENT);

    /**
     * lead 拆解产物：子任务描述 + 指派专家 + 自包含任务书。
     * agent 可为 null = 未指派，执行时回退默认；
     * brief 为自包含任务书（目标/背景/约束/交付物），可为 null = 未提供（执行退化为 desc）。
     */
    record Subtask(String desc, String agent, String brief) {
    }

    private LeadOutputParser() {
    }

    /**
     * 解析 lead 拆解返回的 JSON 子任务列表，兼容新旧格式；非法返回空表。
     * 新格式 {@code {"subtasks":[{"desc":"..","agent":"researcher","brief":".."}]}}（brief 可选），
     * 旧格式对象无 brief（brief=null）、纯字符串数组 {@code {"subtasks":[".."]}}（agent=null、brief=null）；
     * agent 名不在白名单一律回退 null；brief 空白归一化为 null。
     */
    static List<Subtask> parseSubtasks(String content) {
        if (content == null || content.isBlank()) {
            return new ArrayList<>();
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(content);
            List<Subtask> out = new ArrayList<>();
            for (JsonNode s : node.path("subtasks")) {
                if (s.isTextual()) {
                    out.add(new Subtask(s.asText(), null, null)); // 旧格式：纯字符串
                } else {
                    String desc = s.path("desc").asText("");
                    String agent = normalizeAgent(s.path("agent").asText(null));
                    String brief = normalizeBrief(s.path("brief").asText(null));
                    out.add(new Subtask(desc, agent, brief));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("[multi-agent] 拆解返回非法 JSON，回退处理：{}", safe(e));
            return new ArrayList<>();
        }
    }

    /** 专家名归一化：空白视为未指派；不在白名单的回退 null（执行时走默认客户端）。 */
    private static String normalizeAgent(String agent) {
        if (agent == null || agent.isBlank()) {
            return null;
        }
        String name = agent.trim();
        return EXPERT_WHITELIST.contains(name) ? name : null;
    }

    /** 任务书归一化：null/空白串视为未提供（null），执行时退化为 desc。 */
    private static String normalizeBrief(String brief) {
        return (brief == null || brief.isBlank()) ? null : brief;
    }

    private static String safe(Throwable t) {
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
