# Checklist

## S1 Tavily 405
- [x] logging.level 含 LifecycleInitializer: ERROR，注释说明缘由；server 全量测试绿

## S2 观测表清理
- [x] V24 迁移：session(profile_extracted,last_active_at) + 两观测表(created_at) 索引生效
- [x] 清理任务分批删除、retention-days=0 禁用、失败仅 warn；单测覆盖禁用/删除/保留边界

## S3 QQ 线程池
- [x] 内部 newFixedThreadPool 删除，统一 onebotExecutor；cancel(true)；既有测试绿

## S4 API 鉴权
- [x] Filter 常量时间比较、/onebot/** 豁免、空配置不启用；单测覆盖启用/禁用/豁免/401
- [x] web client.ts REST+SSE 均带 X-API-Token（空值不加头）

## W1-W3 web
- [x] 列表/agents 加载失败有轻提示，不静默
- [x] 会话项可 Tab 聚焦、Enter/Space 选中、有 aria-label
- [x] isAbortError 单点导出；data 载荷容忍 \r\n；build 串 typecheck；CSS 两处杂项修复
- [x] 每项独立 commit，message 走 -F 文件
