package com.dark.javaHarness.channel.feishu.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 飞书单聊会话绑定：open_id + chat_id → 项目 session_id。
 *
 * <p>唯一键放在 (open_id, chat_id)：单人可以同时有单聊与将来的群聊，各自独立会话；
 * 单聊里 chat_id 与 open_id 一一对应，加 chat_id 只是为扩展留位。
 */
@TableName("feishu_session_binding")
public class FeishuSessionBinding {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String openId;

    private String chatId;

    private Long sessionId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOpenId() {
        return openId;
    }

    public void setOpenId(String openId) {
        this.openId = openId;
    }

    public String getChatId() {
        return chatId;
    }

    public void setChatId(String chatId) {
        this.chatId = chatId;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
