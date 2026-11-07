package com.dark.javaHarness.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dark.javaHarness.domain.entity.MemoryEntity;
import org.apache.ibatis.annotations.Mapper;

/** 显式记忆 Mapper（MyBatis-Plus 单表操作） */
@Mapper
public interface MemoryMapper extends BaseMapper<MemoryEntity> {
}
