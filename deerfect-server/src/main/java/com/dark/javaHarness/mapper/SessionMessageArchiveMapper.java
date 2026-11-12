package com.dark.javaHarness.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dark.javaHarness.domain.entity.SessionMessageArchiveEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * session_message_archive 表 Mapper（MyBatis-Plus 提供 CRUD）。
 */
@Mapper
public interface SessionMessageArchiveMapper extends BaseMapper<SessionMessageArchiveEntity> {
}
