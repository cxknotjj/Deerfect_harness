package com.dark.javaHarness.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dark.javaHarness.domain.entity.DeliveryOutboxEntity;
import org.apache.ibatis.annotations.Mapper;

/** 主动投递 outbox Mapper（MyBatis-Plus 单表操作；领取的条件更新在服务层用 UpdateWrapper 表达） */
@Mapper
public interface DeliveryOutboxMapper extends BaseMapper<DeliveryOutboxEntity> {
}
