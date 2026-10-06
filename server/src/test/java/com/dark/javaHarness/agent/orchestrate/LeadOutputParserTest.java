package com.dark.javaHarness.agent.orchestrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * LeadOutputParser 纯静态单测（无 Spring/Mockito）：lead 拆解产物新旧格式解析——
 * 新格式四字段（desc/agent/brief/toolPacks，brief/toolPacks 可选且分别归一化）、旧格式对象
 * （brief=null、packs=空名单）与纯字符串数组（agent=null、brief=null、packs=空名单）、
 * 非法/空输入回退空表、专家名白名单归一化（wms 不回退、白名单外与空白回退 null）、
 * toolPacks 数组逐项 trim/去空白项/保序、缺省与非数组归一化为空名单。
 */
class LeadOutputParserTest {

    /** 新格式三字段全解析：desc/agent/brief 逐字段断言（brief 内 \n 转义应还原为真实换行） */
    @Test
    void parse_newFormat_fullThreeFields() {
        String content = "{\"subtasks\":[{\"desc\":\"调研竞品定价\",\"agent\":\"researcher\","
                + "\"brief\":\"目标：对比定价\\n背景：竞品 A/B/C\\n约束：只看国内市场\\n交付物：报告\"}]}";

        List<LeadOutputParser.Subtask> subtasks = LeadOutputParser.parseSubtasks(content);

        assertEquals(1, subtasks.size());
        LeadOutputParser.Subtask s = subtasks.get(0);
        assertEquals("调研竞品定价", s.desc());
        assertEquals("researcher", s.agent());
        assertEquals("目标：对比定价\n背景：竞品 A/B/C\n约束：只看国内市场\n交付物：报告", s.brief());
    }

    /** 新格式缺 brief 字段 → brief=null（执行时退化为 desc） */
    @Test
    void parse_newFormat_missingBrief_briefNull() {
        String content = "{\"subtasks\":[{\"desc\":\"撰写摘要\",\"agent\":\"writer\"}]}";

        List<LeadOutputParser.Subtask> subtasks = LeadOutputParser.parseSubtasks(content);

        assertEquals(1, subtasks.size());
        assertEquals("撰写摘要", subtasks.get(0).desc());
        assertEquals("writer", subtasks.get(0).agent());
        assertNull(subtasks.get(0).brief(), "缺 brief 字段应归一化为 null");
    }

    /** brief 为空白串 → 归一化为 null */
    @Test
    void parse_newFormat_blankBrief_normalizedToNull() {
        String content = "{\"subtasks\":[{\"desc\":\"调研竞品\",\"agent\":\"researcher\",\"brief\":\"  \"}]}";

        List<LeadOutputParser.Subtask> subtasks = LeadOutputParser.parseSubtasks(content);

        assertEquals(1, subtasks.size());
        assertNull(subtasks.get(0).brief(), "空白 brief 应归一化为 null");
    }

    /** 旧格式对象（无 brief 字段）→ brief=null，desc/agent 照常解析 */
    @Test
    void parse_legacyObjectFormat_briefNull() {
        String content = "{\"subtasks\":[{\"desc\":\"调研竞品\",\"agent\":\"researcher\"}]}";

        List<LeadOutputParser.Subtask> subtasks = LeadOutputParser.parseSubtasks(content);

        assertEquals(1, subtasks.size());
        assertEquals("调研竞品", subtasks.get(0).desc());
        assertEquals("researcher", subtasks.get(0).agent());
        assertNull(subtasks.get(0).brief(), "旧格式对象 brief 应为 null");
    }

    /** 旧格式纯字符串数组 → desc=文本、agent=null、brief=null */
    @Test
    void parse_legacyStringArray_descOnly() {
        String content = "{\"subtasks\":[\"调研 X\"]}";

        List<LeadOutputParser.Subtask> subtasks = LeadOutputParser.parseSubtasks(content);

        assertEquals(1, subtasks.size());
        LeadOutputParser.Subtask s = subtasks.get(0);
        assertEquals("调研 X", s.desc());
        assertNull(s.agent(), "旧格式纯字符串 agent 应为 null");
        assertNull(s.brief(), "旧格式纯字符串 brief 应为 null");
    }

    /** 非法 JSON → 空表（不抛异常） */
    @Test
    void parse_invalidJson_returnsEmptyList() {
        List<LeadOutputParser.Subtask> subtasks = LeadOutputParser.parseSubtasks("not-json");

        assertTrue(subtasks.isEmpty(), "非法 JSON 应回退空表");
    }

    /** null / 空白输入 → 空表 */
    @Test
    void parse_nullOrBlankInput_returnsEmptyList() {
        assertTrue(LeadOutputParser.parseSubtasks(null).isEmpty(), "null 输入应回退空表");
        assertTrue(LeadOutputParser.parseSubtasks("   ").isEmpty(), "空白输入应回退空表");
    }

    /** agent=wms（领域模板白名单）→ 归一化为 "wms"，不回退 null */
    @Test
    void parse_wmsAgent_whitelistedKept() {
        String content = "{\"subtasks\":[{\"desc\":\"查库存\",\"agent\":\"wms\"}]}";

        List<LeadOutputParser.Subtask> subtasks = LeadOutputParser.parseSubtasks(content);

        assertEquals(1, subtasks.size());
        assertEquals("wms", subtasks.get(0).agent(), "wms 在白名单内应原样保留");
    }

    /** agent=白名单外名字 → 回退 null（执行时走默认客户端） */
    @Test
    void parse_unknownAgent_fallsBackToNull() {
        String content = "{\"subtasks\":[{\"desc\":\"任务X\",\"agent\":\"unknown-x\"}]}";

        List<LeadOutputParser.Subtask> subtasks = LeadOutputParser.parseSubtasks(content);

        assertEquals(1, subtasks.size());
        assertNull(subtasks.get(0).agent(), "白名单外 agent 应回退 null");
    }

    /** agent 空白（含缺字段）→ null */
    @Test
    void parse_blankOrMissingAgent_null() {
        String content = "{\"subtasks\":[{\"desc\":\"任务A\",\"agent\":\"  \"},{\"desc\":\"任务B\"}]}";

        List<LeadOutputParser.Subtask> subtasks = LeadOutputParser.parseSubtasks(content);

        assertEquals(2, subtasks.size());
        assertNull(subtasks.get(0).agent(), "空白 agent 应归一化为 null");
        assertNull(subtasks.get(1).agent(), "缺 agent 字段应为 null");
    }

    /* ---------------- toolPacks 工具包名单解析 ---------------- */

    /** toolPacks 数组解析：逐项 trim、丢弃空白项、保持声明顺序（能否授出由执行期按 lead tools 列裁决） */
    @Test
    void parse_toolPacks_trimmedBlankDroppedOrderKept() {
        String content = "{\"subtasks\":[{\"desc\":\"查库存\",\"agent\":\"general\","
                + "\"toolPacks\":[\" wms \",\"\",\"  \",\"erp\"]}]}";

        List<LeadOutputParser.Subtask> subtasks = LeadOutputParser.parseSubtasks(content);

        assertEquals(1, subtasks.size());
        assertEquals(List.of("wms", "erp"), subtasks.get(0).packs(),
                "空白项丢弃、首尾空白 trim、声明顺序保持");
    }

    /** toolPacks 缺省/非数组 → 空名单（不可变） */
    @Test
    void parse_toolPacksMissingOrNonArray_emptyList() {
        String missing = "{\"subtasks\":[{\"desc\":\"查库存\",\"agent\":\"general\"}]}";
        String nonArray = "{\"subtasks\":[{\"desc\":\"查库存\",\"agent\":\"general\",\"toolPacks\":\"wms\"}]}";

        List<LeadOutputParser.Subtask> fromMissing = LeadOutputParser.parseSubtasks(missing);
        List<LeadOutputParser.Subtask> fromNonArray = LeadOutputParser.parseSubtasks(nonArray);

        assertEquals(List.of(), fromMissing.get(0).packs(), "缺 toolPacks 字段应归一化为空名单");
        assertEquals(List.of(), fromNonArray.get(0).packs(), "toolPacks 非数组应归一化为空名单");
    }

    /** 旧格式兼容不变：旧格式对象与纯字符串数组的 packs 均为空名单，desc/agent/brief 解析不受影响 */
    @Test
    void parse_legacyFormats_packsEmptyUnchanged() {
        String legacyObject = "{\"subtasks\":[{\"desc\":\"调研竞品\",\"agent\":\"researcher\"}]}";
        String legacyStringArray = "{\"subtasks\":[\"调研 X\"]}";

        List<LeadOutputParser.Subtask> fromObject = LeadOutputParser.parseSubtasks(legacyObject);
        List<LeadOutputParser.Subtask> fromStrings = LeadOutputParser.parseSubtasks(legacyStringArray);

        assertEquals("researcher", fromObject.get(0).agent());
        assertNull(fromObject.get(0).brief());
        assertEquals(List.of(), fromObject.get(0).packs(), "旧格式对象 packs 应为空名单");
        assertEquals("调研 X", fromStrings.get(0).desc());
        assertEquals(List.of(), fromStrings.get(0).packs(), "旧格式纯字符串 packs 应为空名单");
    }
}
