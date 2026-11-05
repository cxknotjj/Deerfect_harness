package com.dark.javaHarness.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 分层依赖规则守卫：把 principal §3 的包依赖方向固化为可执行断言。
 *
 * <p>新增违规会让本测试失败；已登记例外在规则里显式标注，避免规则靠冻结基线静默放行。
 */
@AnalyzeClasses(packages = "com.dark.javaHarness", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule agent_must_not_depend_on_graph =
            noClasses().that().resideInAPackage("com.dark.javaHarness.agent..")
                    .should().dependOnClassesThat().resideInAPackage("com.dark.javaHarness.graph..")
                    .because("principal §3：agent 不得 import graph，共用常量应下沉到 agent");

    @ArchTest
    static final ArchRule runtime_must_not_depend_on_service_impl =
            noClasses().that().resideInAnyPackage(
                            "com.dark.javaHarness.agent..",
                            "com.dark.javaHarness.graph..")
                    .should().dependOnClassesThat().resideInAPackage("com.dark.javaHarness.service.impl..")
                    .because("principal §3：运行时包只依赖 service 接口，不得反向依赖 service 实现");

    @ArchTest
    static final ArchRule controller_must_not_depend_on_mapper =
            noClasses().that().resideInAPackage("com.dark.javaHarness.controller..")
                    .should().dependOnClassesThat().resideInAPackage("com.dark.javaHarness.mapper..")
                    .because("principal §3：controller 只做参数接收与响应组装，不直连 Mapper");

    @ArchTest
    static final ArchRule tool_must_not_depend_on_service_impl =
            noClasses().that().resideInAPackage("com.dark.javaHarness.tool..")
                    .and().doNotHaveFullyQualifiedName(
                            "com.dark.javaHarness.tool.builtin.history.RecallHistoryTools")
                    .should().dependOnClassesThat().resideInAPackage("com.dark.javaHarness.service.impl..")
                    .because("工具层不依赖 service 实现；RecallHistoryTools 读取归档服务为已登记例外");

    @ArchTest
    static final ArchRule validation_must_not_depend_on_runtime =
            noClasses().that().resideInAPackage("com.dark.javaHarness.validation..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.dark.javaHarness.agent..",
                            "com.dark.javaHarness.advisor..",
                            "com.dark.javaHarness.config..",
                            "com.dark.javaHarness.controller..",
                            "com.dark.javaHarness.graph..",
                            "com.dark.javaHarness.knowledge..",
                            "com.dark.javaHarness.mapper..",
                            "com.dark.javaHarness.observability..",
                            "com.dark.javaHarness.prompt..",
                            "com.dark.javaHarness.service..",
                            "com.dark.javaHarness.state..",
                            "com.dark.javaHarness.tool..")
                    .because("principal §3：validation 必须是纯函数，不依赖任何运行时包");
}
