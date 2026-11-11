package com.dark.javaHarness.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dark.javaHarness.domain.entity.ScheduleEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * agent_schedule 表 Mapper（MyBatis-Plus 提供 CRUD）。
 */
@Mapper
public interface ScheduleMapper extends BaseMapper<ScheduleEntity> {
}
