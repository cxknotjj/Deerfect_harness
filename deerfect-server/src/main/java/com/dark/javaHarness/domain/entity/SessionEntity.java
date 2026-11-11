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

    /** 最近活跃时间（画像提炼扫描依据；建档初始化，每轮写回刷新） */
    private java.time.LocalDateTime lastActiveAt;

    /**
     * 增量提炼游标：已并入画像的最后一条快照消息 id。
     *
     * <p>为空 = 从未提炼（首次会喂整段快照）。用消息 id 而不是条数：快照裁剪会丢弃最旧的
     * 消息，按条数记录会在裁剪后错位，按 id 查找则「找不到即说明已被裁掉，剩余都是新增」。
     */
    private String lastExtractedMessageId;

    /**
     * 上次提炼完成时对应的 {@link #lastActiveAt} 值（不是提炼时刻）。
     *
     * <p>判定「有待提炼内容」用它而不是布尔标记：写回路径无需维护任何开关，
     * 新消息把 last_active_at 推后即自然重新进入待提炼集合；提炼期间又来了新消息
     * 也不会被误标为已处理（记录的是开始处理那一刻的活跃时间）。
     */
    private java.time.LocalDateTime profileExtractedAt;
}