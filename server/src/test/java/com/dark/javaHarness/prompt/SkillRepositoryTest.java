package com.dark.javaHarness.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SkillRepository 单测（prompt 动态加载·子项 1）：
 * - 目录式技能：`<skill>/SKILL.md` 与 `<skill>/<skill>.md` 两种入口
 * - front-matter 解析：name/description/agents 显式声明与缺省回退（name 缺省=子目录名）
 * - agents 过滤：声明了 agents 的技能仅对清单内 agent 可见，缺省全可见
 * - mtime 热重载：文件修改后下一次扫描即生效（免重启）
 * - 旧结构移除：顶层 .md 与非技能子目录不参与索引；辅助 .md 不当作独立技能
 * - 容错：目录缺失/坏文件跳过不抛
 */
class SkillRepositoryTest {

    @TempDir
    Path dir;

    private SkillRepository repo() {
        return new SkillRepository(dir.toString());
    }

    /** 在技能根目录下建一个技能子目录并写入入口文件 */
    private Path skill(String dirName, String entryName, String content) throws Exception {
        Path skillDir = Files.createDirectories(dir.resolve(dirName));
        Path entry = skillDir.resolve(entryName);
        Files.writeString(entry, content);
        return entry;
    }

    @Test
    void explicitFrontMatter_parsed() throws Exception {
        skill("my-skill", "SKILL.md", """
                ---
                name: my-skill
                description: 自定义描述
                agents: researcher, general
                ---
                正文第一行
                其余正文
                """);
        List<SkillRepository.Skill> skills = repo().skillsFor("researcher");
        assertEquals(1, skills.size());
        SkillRepository.Skill s = skills.get(0);
        assertEquals("my-skill", s.name());
        assertEquals("自定义描述", s.description());
        assertEquals("正文第一行\n其余正文", s.body());
        assertTrue(s.visibleTo("researcher"));
        assertTrue(s.visibleTo("general"));
        assertTrue(!s.visibleTo("coder"));
    }

    @Test
    void namedEntry_defaults_nameFromDirectory_descriptionFromBody_agentsAllVisible() throws Exception {
        skill("web-research", "web-research.md", """
                # 先空两行前的空行也应跳过

                第二行
                """);
        SkillRepository.Skill s = repo().skillsFor("coder").get(0);
        assertEquals("web-research", s.name(), "name 缺省=技能子目录名");
        assertEquals("# 先空两行前的空行也应跳过", s.description(), "description 缺省=正文首个非空行");
        assertNull(s.agents(), "agents 缺省=null（全部可见）");
        assertTrue(s.visibleTo("任意agent"));
    }

    @Test
    void skillMd_withoutName_usesDirectoryName() throws Exception {
        skill("alpha", "SKILL.md", "---\ndescription: 只有描述\n---\n正文");
        SkillRepository.Skill s = repo().skillsFor("x").get(0);
        assertEquals("alpha", s.name(), "SKILL.md 无 name 时回退子目录名，而不是文件名 SKILL");
    }

    @Test
    void agentsFilter_onlyListedAgentsSee() throws Exception {
        skill("only-coder", "SKILL.md", """
                ---
                description: coder 专属
                agents: coder
                ---
                正文
                """);
        assertEquals(1, repo().skillsFor("coder").size());
        assertTrue(repo().skillsFor("researcher").isEmpty());
        assertTrue(repo().find("only-coder", "researcher").isEmpty(), "越权名视为不存在");
        assertTrue(repo().find("only-coder", "coder").isPresent());
    }

    @Test
    void hotReload_modifiedFile_reflectedOnNextScan() throws Exception {
        Path entry = skill("s", "SKILL.md", "---\nname: s\ndescription: v1\n---\nbody");
        SkillRepository repo = repo();
        assertEquals("v1", repo.skillsFor("x").get(0).description());
        // 修改内容 + 显式推进 mtime（文件系统 mtime 粒度可能秒级，同毫秒写入不可靠）
        Files.writeString(entry, "---\nname: s\ndescription: v2\n---\nbody");
        Files.setLastModifiedTime(entry, FileTime.fromMillis(System.currentTimeMillis() + 5000));
        assertEquals("v2", repo.skillsFor("x").get(0).description(), "mtime 变化后热重载");
    }

    @Test
    void unclosedFrontMatter_treatedAsPlainBody() throws Exception {
        skill("broken", "SKILL.md", "---\nname: unclosed\n正文没有闭合线");
        SkillRepository.Skill s = repo().skillsFor("x").get(0);
        assertEquals("broken", s.name(), "未闭合 front-matter 整体按正文处理（meta 清空），name=子目录名");
        assertEquals(1, repo().skillsFor("x").size());
    }

    @Test
    void missingDir_returnsEmpty() {
        SkillRepository missing = new SkillRepository(dir.resolve("no-such").toString());
        assertTrue(missing.skillsFor("general").isEmpty());
        assertTrue(missing.find("x", "general").isEmpty());
    }

    @Test
    void skillsSortedByName() throws Exception {
        skill("b", "SKILL.md", "---\nname: beta\n---\n1");
        skill("a", "SKILL.md", "---\nname: alpha\n---\n2");
        List<SkillRepository.Skill> skills = repo().skillsFor("x");
        assertEquals(List.of("alpha", "beta"), skills.stream().map(SkillRepository.Skill::name).toList());
    }

    @Test
    void legacyTopLevelFile_andNonSkillDirectory_ignored() throws Exception {
        Files.writeString(dir.resolve("legacy.md"), "---\nname: legacy\n---\n顶层旧结构");
        Path assets = Files.createDirectories(dir.resolve("assets"));
        Files.writeString(assets.resolve("notes.md"), "非技能目录");

        assertTrue(repo().skillsFor("x").isEmpty(), "顶层 .md 与没有入口文件的子目录都不参与扫描");
    }

    @Test
    void auxiliaryFiles_inSkillDirectory_notParsedAsSeparateSkills() throws Exception {
        skill("multi", "SKILL.md", "---\nname: multi\n---\n入口正文");
        Files.writeString(dir.resolve("multi").resolve("helper.md"), "辅助文档");

        List<SkillRepository.Skill> skills = repo().skillsFor("x");
        assertEquals(1, skills.size(), "技能目录内的辅助 .md 不应被当成独立技能");
        assertEquals("multi", skills.get(0).name());
    }

    @Test
    void skillMd_preferredOverNamedEntry() throws Exception {
        skill("both", "SKILL.md", "---\nname: standard-entry\n---\n1");
        Files.writeString(dir.resolve("both").resolve("both.md"), "---\nname: named-entry\n---\n2");

        assertEquals("standard-entry", repo().skillsFor("x").get(0).name(),
                "同时存在时优先 SKILL.md");
    }
}
