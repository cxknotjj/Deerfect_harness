# Checklist

## P0 前端展示
- [ ] 轨迹行展示调用绝对时间(当日 HH:mm:ss,跨日含日期)
- [ ] 统计区显示「输入 X tok」与「输出 Y tok」分向 chip,合计 chip 移除
- [ ] 缓存命中率 = Σcached/Σprompt,一位小数;无缓存数据不显示该 chip
- [ ] mock 模式浏览器验证:时间/分向 token/缓存命中率均可见
- [ ] typecheck + build 通过

## P1 重试可见性
- [ ] V21 迁移成功,attempt/max_attempts 全 NULL 兼容存量
- [ ] call/stream 重试路径:每次尝试落库带正确 attempt 与 max_attempts
- [ ] invokeAndRecord 记 1/1;路径 A 记 1/1;route-judge 与画像为 null
- [ ] 轨迹行仅 attempt>1 显示「尝试 n/m」,无重试行不显示
- [ ] server 全量测试通过(含新断言)
- [ ] 真实触发一次重试后轨迹页可见「尝试 2/N」
