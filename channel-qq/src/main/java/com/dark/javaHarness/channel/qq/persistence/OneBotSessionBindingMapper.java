package com.dark.javaHarness.channel.qq.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * onebot_session_binding 表 Mapper（MyBatis-Plus 提供 CRUD）。
 * QQ 渠道私有持久化：随实体收编进 channel/qq 包（经
 * {@code @MapperScan} 显式纳入扫描），core 不直接依赖。
 */
@Mapper
public interface OneBotSessionBindingMapper extends BaseMapper<OneBotSessionBinding> {
}
