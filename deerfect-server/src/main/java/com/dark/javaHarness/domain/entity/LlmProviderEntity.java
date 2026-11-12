package com.dark.javaHarness.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * LLM 供应商：端点与凭证引用。
 *
 * <p>凭证不落库，{@code credentialRef} 只存环境变量名或密钥别名，由
 * {@code ProviderCatalog} 在启动/热加载时解析。
 */
@Data
@TableName("llm_provider")
public class LlmProviderEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 供应商代码（唯一）：dashscope / deepseek / tecent */
    private String code;

    /** OpenAI 兼容端点根地址 */
    private String baseUrl;

    /** 凭证引用（环境变量名或密钥别名） */
    private String credentialRef;

    /** 协议类型，当前仅 openai */
    private String protocol;

    /** 1-启用 0-禁用 */
    private Integer enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
