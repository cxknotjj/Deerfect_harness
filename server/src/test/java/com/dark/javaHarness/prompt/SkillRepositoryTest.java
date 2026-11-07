package com.dark.javaHarness.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Set;
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
        return new SkillRepository(dir.toString(), 0);
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
        SkillRepository missing = new SkillRepository(dir.resolve("no-such").toString(), 0);
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

    @Test
    void duplicateNames_deduped_deterministicallyByDirectoryOrder() throws Exception {
        skill("a-dup", "SKILL.md", "---\nname: dup\n---\n正文A");
        skill("b-dup", "SKILL.md", "---\nname: DUP\n---\n正文B");

        SkillRepository repo = repo();
        List<SkillRepository.Skill> skills = repo.skillsFor("x");
        assertEquals(1, skills.size(), "同名技能只保留一条");
        assertEquals("dup", skills.get(0).name());
        assertEquals("正文A", skills.get(0).body(), "保留目录名排序第一条");
        assertEquals("正文A", repo.find("dup", "x").orElseThrow().body());
        assertEquals("正文A", repo.find("DUP", "x").orElseThrow().body(), "索引与 find 指向同一正文");
    }

    @Test
    void bomFrontMatter_parsed() throws Exception {
        skill("bom", "SKILL.md",
                "\uFEFF---\nname: bom-skill\ndescription: 带BOM\nagents: Researcher, GENERAL\n---\n正文");

        SkillRepository.Skill s = repo().skillsFor("researcher").get(0);
        assertEquals("bom-skill", s.name(), "BOM 不应阻止 front-matter 解析");
        assertEquals("带BOM", s.description());
        assertEquals(Set.of("researcher", "general"), s.agents(), "agents 统一转小写");
        assertTrue(s.visibleTo("researcher"));
        assertTrue(s.visibleTo("general"));
        assertFalse(s.visibleTo("coder"));
    }

    @Test
    void agentsCaseInsensitive_visibleToLowercaseAgent() throws Exception {
        skill("case", "SKILL.md", "---\nname: case\nagents: Researcher\n---\n正文");

        SkillRepository.Skill s = repo().skillsFor("researcher").get(0);
        assertEquals(Set.of("researcher"), s.agents());
        assertTrue(s.visibleTo("researcher"));
    }

    @Test
    void cacheFingerprint_sizeChangeWithSameMtime_reloads() throws Exception {
        Path entry = skill("s", "SKILL.md", "---\nname: s\ndescription: v1\n---\nbody");
        SkillRepository repo = repo();
        assertEquals("v1", repo.skillsFor("x").get(0).description());

        long mtime = Files.getLastModifiedTime(entry).toMillis();
        Path replacement = dir.resolve("replacement.md");
        Files.writeString(replacement, "---\nname: s\ndescription: v2-longer\n---\nbody");
        Files.copy(replacement, entry, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        Files.setLastModifiedTime(entry, FileTime.fromMillis(mtime));

        assertEquals("v2-longer", repo.skillsFor("x").get(0).description(),
                "mtime 不变但 size 变化应重新解析");
    }

    @Test
    void inspect_reportsDiagnostics() throws Exception {
        skill("ok", "SKILL.md", "---\nname: ok\ndescription: 正常\n---\n正文");
        skill("unclosed", "SKILL.md", "---\nname: unclosed\n正文没有闭合线");
        skill("empty", "SKILL.md", "---\nname: empty\n---\n");
        Files.createDirectories(dir.resolve("no-entry"));
        skill("dup1", "SKILL.md", "---\nname: dup\n---\nA");
        skill("dup2", "SKILL.md", "---\nname: DUP\n---\nB");

        List<SkillRepository.SkillInfo> infos = repo().inspect();
        SkillRepository.SkillInfo ok = info(infos, "ok");
        assertEquals("正常", ok.description());
        assertNull(ok.error(), "正常技能不应有问题");
        assertTrue(info(infos, "unclosed").error().contains("未闭合"));
        assertTrue(info(infos, "empty").error().contains("正文为空"));
        assertTrue(info(infos, "no-entry").error().contains("缺少入口文件"));
        long duplicates = infos.stream()
                .filter(i -> i.error() != null && i.error().contains("重复"))
                .count();
        assertEquals(1, duplicates, "重名只应标记后出现的那一项");
    }

    @Test
    void inspect_reportsBomAndOversizedFile() throws Exception {
        skill("bom", "SKILL.md", "\uFEFF---\nname: bom\n---\n正文");
        skill("huge", "SKILL.md", "---\nname: huge\n---\n"
                + "x".repeat((int) SkillRepository.MAX_ENTRY_BYTES + 1));

        SkillRepository repo = repo();
        List<SkillRepository.SkillInfo> infos = repo.inspect();
        assertTrue(info(infos, "bom").error().contains("BOM"));
        assertTrue(info(infos, "huge").error().contains("文件过大"));
        assertTrue(repo.skillsFor("x").stream().noneMatch(s -> s.name().equals("huge")),
                "超大文件应在解析前被拒绝");
    }

    private static SkillRepository.SkillInfo info(List<SkillRepository.SkillInfo> infos, String name) {
        return infos.stream()
                .filter(i -> i.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void scanCache_reusesWithinTtl_andRefreshesAfterExpiry() throws Exception {
        Path entry = skill("s", "SKILL.md", "---\nname: s\ndescription: v1\n---\nbody");
        // 注入可控纳秒时钟：TTL=100ms，推进时钟即可触发重扫，不用 sleep 等真实时间
        java.util.concurrent.atomic.AtomicLong nanos = new java.util.concurrent.atomic.AtomicLong();
        SkillRepository repo = new SkillRepository(dir.toString(), 100, nanos::get);
        assertEquals("v1", repo.skillsFor("x").get(0).description());

        Files.writeString(entry, "---\nname: s\ndescription: v2\n---\nbody");
        Files.setLastModifiedTime(entry, FileTime.fromMillis(System.currentTimeMillis() + 5000));
        nanos.addAndGet(50_000_000L); // +50ms：仍在 TTL 内
        assertEquals("v1", repo.skillsFor("x").get(0).description(), "TTL 内复用扫描快照");

        nanos.addAndGet(60_000_000L); // 累计 +110ms：越过 100ms TTL
        assertEquals("v2", repo.skillsFor("x").get(0).description(), "TTL 过期后重新扫描并热重载");
    }

    @Test
    void cacheEviction_deletedThenRecreatedWithSameFingerprint_reloads() throws Exception {
        Path entry = skill("s", "SKILL.md", "---\nname: s\ndescription: v1\n---\nbody");
        long mtime = Files.getLastModifiedTime(entry).toMillis();
        long size = Files.size(entry);
        SkillRepository repo = repo();
        assertEquals("v1", repo.skillsFor("x").get(0).description());

        Files.delete(entry);
        assertTrue(repo.skillsFor("x").isEmpty(), "删除入口后扫描为空");

        Files.writeString(entry, "---\nname: s\ndescription: v2\n---\nbody");
        Files.setLastModifiedTime(entry, FileTime.fromMillis(mtime));
        assertEquals(size, Files.size(entry), "重建文件保持相同指纹（mtime+size）");

        assertEquals("v2", repo.skillsFor("x").get(0).description(),
                "删除后缓存应被清理，重建文件应重新解析");
    }
}
