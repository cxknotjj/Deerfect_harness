package com.dark.javaHarness.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dark.javaHarness.domain.entity.SessionSummaryEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * session_summary 表 Mapper（MyBatis-Plus 提供 CRUD）。
 */
@Mapper
public interface SessionSummaryMapper extends BaseMapper<SessionSummaryEntity> {
}
