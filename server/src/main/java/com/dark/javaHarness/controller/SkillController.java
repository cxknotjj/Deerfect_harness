package com.dark.javaHarness.controller;

import com.dark.javaHarness.prompt.SkillRepository;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Skill 只读校验接口：返回技能目录的解析结果与问题列表。
 * 不读数据库、不触发模型调用，供运维/前端排查技能格式问题。
 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillRepository repository;

    public SkillController(SkillRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<SkillRepository.SkillInfo> list() {
        return repository.inspect();
    }
}
