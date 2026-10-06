package com.dark.javaHarness.agent.orchestrate;

import com.dark.javaHarness.tool.TextSanitizer;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 编排提示词构建（从 {@link MultiAgentGraphAgent} 提取的纯函数/常量）：
 * lead/aggregator 兜底提示词 + 聚合用户 prompt 拼接 + 「【子任务N】」节头。
 * 正常情况提示词以 agent 表配置为准，兜底仅表缺行时生效（含「禁止凑数拆解」纪律）。
 */
final class OrchestrationPrompts {

    private OrchestrationPrompts() {
    }

    /** lead 拆解兜底提示词（agent 表无 lead 行时使用；正常情况以表配置为准） */
    static final String LEAD_FALLBACK_PROMPT =
            "你是多 Agent 的 Lead 拆解器。把用户复杂目标拆解为若干条可并行执行的子任务，"
                    + "并为每条子任务指派最合适的专家执行。可选专家（只能用这些名字）："
                    + "researcher（资料调研）、coder（代码编写/修复）、analyst（数据分析）、writer（汇总撰写）、general（通用兜底）。"
                    + "拆解数量必须与任务难度匹配，禁止凑数：至多 4 条；简单任务只拆 1 条，中等任务 2~3 条，"
                    + "只有确实存在多个可独立并行、且各自对最终结果都有贡献的部分时才拆满；"
                    + "任何一条子任务如果只是原任务换个说法，就不要拆。"
                    + "只输出一行 JSON，格式："
                    + "{\"subtasks\":[{\"desc\":\"子任务描述\",\"agent\":\"专家名\"}]}，不要任何解释。";

    /** 聚合兜底提示词（agent 表无 aggregator 行时使用；正常情况以表配置为准）。
     *  防循环纪律与 V39 表内口径一致：小模型思考模式读到乱码/URL 长串会退化复读（实证） */
    static final String AGGREGATOR_FALLBACK_PROMPT =
            "你是聚合汇总的 AI 助手，依据多个子结果的最终回答可直接呈现给用户。"
                    + "汇总时不要逐字罗列来源 URL，标注域名或标题即可；"
                    + "遇到无法识别的乱码片段直接跳过，禁止复述。";

    /** 聚合 user 内容的子任务节头（与 {@link #aggregateUserPrompt} 的拼接格式对应） */
    static final Pattern AGG_SECTION_HEADER = Pattern.compile("【子任务\\d+】");

    /** 聚合输入单条子任务结果的字符上限：URL/长文噪音是小模型思考复读循环的触发源（0 = 不限） */
    static final int AGG_RESULT_MAX_CHARS = 6000;

    /**
     * 聚合请求的 user 内容：各子任务结果顺序拼接（阻塞/流式两版共用）。
     * 每条结果先经 {@link TextSanitizer#forAggregateInput} 净化——坏字符剥离 + URL
     * 去重去查询参数 + 单条长度封顶（URL 噪音实证会诱发聚合小模型思考复读循环）。
     */
    static String aggregateUserPrompt(List<String> results) {
        StringBuilder sb = new StringBuilder("以下是各子任务结果，请汇总为一份完整、连贯的最终回答：\n");
        for (int i = 0; i < results.size(); i++) {
            String result = TextSanitizer.forAggregateInput(results.get(i), AGG_RESULT_MAX_CHARS);
            sb.append("【子任务").append(i + 1).append("】\n").append(result).append("\n\n");
        }
        return sb.toString();
    }
}
