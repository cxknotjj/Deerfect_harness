package com.dark.javaHarness.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 会话实体，对应表 session。
 * 一个会话对应一次与 Agent 的连续对话；软删除由 is_delete 标记。
 */
@Data
@TableName("session")
public class SessionEntity {

    @TableId(type = IdType.AUTO)
    private Long sessionId;

    /** 关联的 Agent（预留：当前 Agent 未编号，暂以固定值登记） */
    private Integer agentId;

    /** 会话名称（默认取首条提问截断） */
    private String sessionName;

    /** 创建者 */
    private String creator;

    /** 最近一次提问 */
    private String lastQuestion;

    /** 软删除标记：0-正常 1-已删除 */
    @TableLogic
    private Integer isDelete;

    /** 最近活跃时间（画像提取扫描依据；建档初始化，每轮写回刷新） */
    private java.time.LocalDateTime lastActiveAt;

    /** 画像提取标记：0-待提炼 1-已提炼（一次会话仅提炼一次） */
    private Integer profileExtracted;
}