package com.dark.javaHarness.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * Agent 实体，对应表 agent。
 * 记录 Agent 名称、描述、绑定的模型与系统提示词。
 */
@Data
@TableName("agent")
public class AgentEntity {

    /** Agent 主键ID */
    @TableId(type = IdType.AUTO)
    private Long agentId;

    /** Agent 名称（注册与路由用，如 general） */
    private String agentName;

    /** Agent 描述 */
    private String description;

    /** 绑定的模型路由（llm_route.id）：Agent 只认路由，不直接认模型 */
    private Long routeId;

    /** Agent 级参数覆盖 JSON：temperature / max_tokens / thinking_mode / show_thinking */
    private String params;

    /** 系统提示词（System Prompt） */
    private String prompt;

    /** 分配的工具（逗号分隔：组名 web/demo/sandbox.* 或精确工具名；NULL/空白回退代码内置分配） */
    private String tools;

    /** 绑定的知识库（逗号分隔 kb 标识=knowledge/ 一级子目录名；NULL/空白 = 未绑定，不做知识检索） */
    private String knowledge;

    /**
     * 思考显示开关（仅控制是否显示思考内容，并不是控制模型是否思考——模型思考由
     * llm_model.default_params.thinking_mode 决定）：NULL/0 = 不透传前端，1 = 透传
     */
    private Integer thinking;

    /** 状态：1-启用 0-禁用 */
    private Integer status;

    /** 内部角色标志：1-编排内部角色（不作为对话 Agent 注册） 0-对话 Agent */
    private Integer isInternal;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
