package com.dark.javaHarness.agent;

import com.dark.javaHarness.config.ContextBudgetProperties;
import com.dark.javaHarness.prompt.MemoryPolicy;
import com.dark.javaHarness.prompt.PromptAssembler;
import com.dark.javaHarness.service.SessionService;

/**
 * 角色策略装配（自 {@link AgentChatCaller} 拆出，超长类拆分 2026-09-25）：
 * 编排三节点现状语义的 {@link AgentRequestSpecFactory.Assembly} 生成
 * （记忆按 {@link MemoryPolicy} 判定仅 lead 注入、频率惩罚与工具硬预算启用、
 * maxTokens 按角色档位）与观测名单计算。不并入 {@link AgentRequestSpecFactory}——
 * 该工厂 javadoc 明确「不感知编排角色语义」，装配策略独立成类维持既有边界。
 */
final class CallSpecAssembler {

    private final PromptAssembler promptAssembler;
    /** 记忆注入策略：按角色名判定是否挂载会话记忆 advisor（仅 lead） */
    private final MemoryPolicy memoryPolicy = new MemoryPolicy();
    /** 会话记忆源（SessionService，与路径 A GeneralAssistantAgent 同源）；null 时不注入（单测场景） */
    private final SessionService memoryStore;
    /** 上下文预算配置（工具次数/结果预算等；null 时用内置默认值，单测场景） */
    private final ContextBudgetProperties budgets;

    CallSpecAssembler(PromptAssembler promptAssembler,
                      SessionService memoryStore,
                      ContextBudgetProperties budgets) {
        this.promptAssembler = promptAssembler;
        this.memoryStore = memoryStore;
        this.budgets = budgets;
    }

    /**
     * 角色策略装配（路径 B 三节点现状语义，与历史 buildSpec 内联版逐字段等价）：
     * 记忆按 {@link MemoryPolicy} 判定（仅 lead）、频率惩罚与工具硬预算启用、maxTokens 按角色档位。
     */
    AgentRequestSpecFactory.Assembly assemblyForRole(String forAgent, String sessionId,
                                                     java.util.function.Consumer<String> toolEmitter,
                                                     boolean disableTools) {
        return new AgentRequestSpecFactory.Assembly(toolEmitter, disableTools,
                memoryStore != null && memoryPolicy.shouldInject(forAgent, sessionId),
                true, true, maxTokensForRole(forAgent));
    }

    /** 观测名单计算（llm_call_log 装配名单列）：assembly.disableTools 时工具/子集置空、技能保留 */
    PromptAssembler.PromptAttachments attachmentsFor(String forAgent,
            AgentRequestSpecFactory.Assembly assembly) {
        PromptAssembler.PromptAttachments att = promptAssembler.attachmentsOf(forAgent);
        return assembly != null && assembly.disableTools() ? att.blankTools() : att;
    }

    /**
     * 输出封顶档位映射（消费侧 maxTokens，0 = 不限制）：
     * lead 拆解 → lead 档（JSON 中间产物本就该短）；aggregator → final 档
     * （与路径 A 直出对话同为直出用户的最终回答，共用一档）；其余（编排子任务专家
     * researcher/coder/analyst/writer/general）→ expert 档。
     * 角色名字面量与 {@link MemoryPolicy} / MultiAgentGraphAgent 的编排角色名同源。
     */
    private int maxTokensForRole(String forAgent) {
        if ("lead".equals(forAgent)) {
            return budgets.getMaxTokensLead();
        }
        if ("aggregator".equals(forAgent)) {
            return budgets.getMaxTokensFinal();
        }
        return budgets.getMaxTokensExpert();
    }
}
