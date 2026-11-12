package com.dark.javaHarness.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 模型部署：一个供应商下的一个可调用模型。
 *
 * <p>唯一键是 {@code (providerId, upstreamName)}；同名模型允许存在于不同供应商，
 * 各自有独立 id，代码只按 id 访问。
 */
@Data
@TableName("llm_model")
public class LlmModelEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long providerId;

    /** 请求体中下发的模型名 */
    private String upstreamName;

    /** 界面展示名，不参与查找 */
    private String displayName;

    /** 能力 JSON：tool_calling / vision / thinking / json_mode / context_window */
    private String capabilities;

    /** 默认参数 JSON：temperature / max_tokens / top_p 等 */
    private String defaultParams;

    /** 1-启用 0-禁用 */
    private Integer enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
