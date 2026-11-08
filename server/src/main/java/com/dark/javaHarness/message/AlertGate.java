package com.dark.javaHarness.message;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 告警抑制：避免「每次失败发一条」把自己练成不看告警的人。
 *
 * <p>三条规则：
 * <ol>
 *   <li>连续失败达到 {@code min-failures} 才放第一条第——一次抖动不值得打扰</li>
 *   <li>同类告警 {@code throttle-hours} 内只发一条——持续故障不该每分钟提醒一次</li>
 *   <li>故障恢复补一条「已恢复」——只知道坏了、不知道好了，同样让人不敢信任</li>
 * </ol>
 *
 * <p>状态在内存里，重启重置。这是刻意的：告警抑制的信息价值远低于「重启后重新开始计数」
 * 的成本，把它落库反而引入一张需要清理的表。
 */
@Component
public class AlertGate {

    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final int minFailures;
    private final Duration throttle;
    private final Clock clock;

    // 显式标注：类里还有一个注入 Clock 的测试用构造器，Spring 需要在多个候选里明确选这个
    @Autowired
    public AlertGate(@Value("${app.alert.min-failures:3}") int minFailures,
                     @Value("${app.alert.throttle-hours:24}") long throttleHours) {
        this(minFailures, throttleHours, Clock.systemUTC());
    }

    /** 测试用：注入可控时钟，避免用真实时间等待抑制窗口 */
    public AlertGate(int minFailures, long throttleHours, Clock clock) {
        this.minFailures = Math.max(1, minFailures);
        this.throttle = Duration.ofHours(Math.max(1, throttleHours));
        this.clock = clock;
    }

    /**
     * 记录一次失败，并判断此刻是否该发告警。
     *
     * @param key 告警主体（渠道名 / 任务 id），同类共用一条抑制窗口
     * @return true = 应当发出告警
     */
    public boolean onFailure(String key) {
        Instant now = clock.instant();
        // 用 holder 而不是「比较 lastAlertAt 是否等于 now」：同一毫秒内的两次失败会误判
        boolean[] alertedNow = {false};
        states.compute(key, (k, current) -> {
            int consecutive = (current == null ? 0 : current.consecutive()) + 1;
            Instant lastAlertAt = current == null ? null : current.lastAlertAt();
            if (consecutive < minFailures) {
                return new State(consecutive, lastAlertAt, false);
            }
            if (lastAlertAt != null && Duration.between(lastAlertAt, now).compareTo(throttle) < 0) {
                // 已达次数但在抑制窗口内：继续累计，不再发
                return new State(consecutive, lastAlertAt, true);
            }
            alertedNow[0] = true;
            return new State(consecutive, now, true);
        });
        return alertedNow[0];
    }

    /**
     * 记录一次成功；若此前处于告警态，返回 true 表示应补发「已恢复」。
     */
    public boolean onSuccess(String key) {
        State previous = states.remove(key);
        return previous != null && previous.alerted();
    }

    /** 当前连续失败次数（排障与测试用） */
    public int consecutiveFailures(String key) {
        State state = states.get(key);
        return state == null ? 0 : state.consecutive();
    }

    /** 是否处于已告警状态（已发过告警且尚未恢复） */
    public boolean alerted(String key) {
        State state = states.get(key);
        return state != null && state.alerted();
    }

    /**
     * @param consecutive 连续失败次数
     * @param lastAlertAt 上次放行告警的时刻（null = 从未告警）
     * @param alerted     是否已进入告警态（决定恢复时要不要补发）
     */
    private record State(int consecutive, Instant lastAlertAt, boolean alerted) {
    }
}
