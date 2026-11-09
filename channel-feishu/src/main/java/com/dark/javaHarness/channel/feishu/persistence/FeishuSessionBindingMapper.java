package com.dark.javaHarness.channel.feishu.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * feishu_session_binding 表 Mapper。
 *
 * <p>渠道私有持久化：由 {@code FeishuWsConfig} 上的 {@code @MapperScan} 显式纳入扫描，
 * core 的 {@code @MapperScan("...mapper")} 不会覆盖到这里。
 */
@Mapper
public interface FeishuSessionBindingMapper extends BaseMapper<FeishuSessionBinding> {
}
