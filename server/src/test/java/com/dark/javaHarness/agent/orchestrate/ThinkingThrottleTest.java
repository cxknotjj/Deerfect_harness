package com.dark.javaHarness.agent.orchestrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dark.javaHarness.agent.ProgressLine;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ThinkingThrottle 单测（子任务思考透传合帧节流）：
 * - 字数阈值合帧：数千 delta 帧收敛为受控行数
 * - 编码口径：发射行为 ProgressLine(stage, 合帧内容)
 * - 流结束兜底 flush：残余缓冲全部发出
 * - 异常中止丢弃：不 flush 即无发射（缓冲随作用域丢弃）
 */
class ThinkingThrottleTest {

    @Test
    void coalescesByCharThreshold_rowCountBounded() {
        List<String> rows = new ArrayList<>();
        ThinkingThrottle throttle = new ThinkingThrottle("思考1", rows::add);
        // 200 个 delta 帧 × 10 字符 = 2000 字符，阈值 120 → 行数 ≤ 2000/120 + 1
        for (int i = 0; i < 200; i++) {
            throttle.accept("思考片段甲乙丙" + i); // 10 字符/帧
        }
        assertTrue(rows.size() <= 2000 / 120 + 1, "合帧后行数应受控，实际 " + rows.size());
        assertTrue(rows.size() >= 10, "正常速率下不应全部滞留缓冲");
    }

    @Test
    void emitsEncodedProgressLine_withStage() {
        List<String> rows = new ArrayList<>();
        ThinkingThrottle throttle = new ThinkingThrottle("思考2", rows::add);
        for (int i = 0; i < 30; i++) {
            throttle.accept("甲乙丙丁戊己庚辛壬癸"); // 10 字符 × 30 = 300 字符 ≥ 阈值
        }
        assertEquals(2, rows.size(), "300 字符应按 120 阈值合帧发射 2 行（余 60 字符滞留缓冲）");
        assertTrue(rows.size() >= 2, "300 字符应至少发射 2 行");
        ProgressLine.StageRow decoded = ProgressLine.decode(rows.get(0));
        assertEquals("思考2", decoded.stage(), "stage 应携带子任务 1 基序号");
        assertTrue(!decoded.detail().isBlank(), "detail 为合帧思考内容");
    }

    @Test
    void flushEmitsRemainingBuffer_exactlyOnce() {
        List<String> rows = new ArrayList<>();
        ThinkingThrottle throttle = new ThinkingThrottle("思考1", rows::add);
        throttle.accept("残余思考内容"); // 6 字符 < 阈值，不触发自动发射
        assertEquals(0, rows.size(), "未达阈值且未超时间窗不应发射");
        throttle.flush();
        assertEquals(1, rows.size(), "流结束兜底 flush 应发出残余缓冲");
        throttle.flush();
        assertEquals(1, rows.size(), "flush 幂等：空缓冲不重复发射");
        ProgressLine.StageRow decoded = ProgressLine.decode(rows.get(0));
        assertEquals("残余思考内容", decoded.detail());
    }

    @Test
    void abnormalAbort_dropsBuffer_noEmission() {
        List<String> rows = new ArrayList<>();
        ThinkingThrottle throttle = new ThinkingThrottle("思考1", rows::add);
        throttle.accept("未及发射的思考");
        // 模拟异常中止：直接放弃引用，不调用 flush
        assertTrue(rows.isEmpty(), "异常中止未 flush 应零发射（缓冲丢弃）");
    }

    @Test
    void emptyDelta_ignored() {
        List<String> rows = new ArrayList<>();
        ThinkingThrottle throttle = new ThinkingThrottle("思考1", rows::add);
        throttle.accept("");
        throttle.accept(null);
        throttle.flush();
        assertTrue(rows.isEmpty(), "空增量不进缓冲，flush 亦无发射");
    }
}
