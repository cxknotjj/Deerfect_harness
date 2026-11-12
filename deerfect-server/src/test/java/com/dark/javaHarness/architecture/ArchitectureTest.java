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

    /** LLM 解析链（目录/路由/校验）只处理配置，不直接持有 ChatClient——客户端统一由 llm.client 构建 */
    @ArchTest
    static final ArchRule llm_config_resolution_must_not_depend_on_chat_client =
            noClasses().that().resideInAnyPackage(
                            "com.dark.javaHarness.llm.catalog..",
                            "com.dark.javaHarness.llm.routing..",
                            "com.dark.javaHarness.llm.validation..",
                            "com.dark.javaHarness.llm.model..",
                            "com.dark.javaHarness.llm.call..")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("org.springframework.ai.chat.client.ChatClient")
                    .because("LLM 配置解析不得绕过 llm.client 直接依赖 ChatClient");

    /** 新三层表只允许目录 / 管理面 / 路由解析访问，调用链不得直连 Mapper */
    @ArchTest
    static final ArchRule llm_tables_only_accessed_by_catalog_admin_routing =
            noClasses().that().resideOutsideOfPackages(
                            "com.dark.javaHarness.llm.catalog..",
                            "com.dark.javaHarness.llm.admin..",
                            "com.dark.javaHarness.llm.routing..")
                    .should().dependOnClassesThat().haveFullyQualifiedName(
                            "com.dark.javaHarness.mapper.LlmProviderMapper")
                    .orShould().dependOnClassesThat().haveFullyQualifiedName(
                            "com.dark.javaHarness.mapper.LlmModelMapper")
                    .orShould().dependOnClassesThat().haveFullyQualifiedName(
                            "com.dark.javaHarness.mapper.LlmRouteMapper")
                    .orShould().dependOnClassesThat().haveFullyQualifiedName(
                            "com.dark.javaHarness.mapper.LlmRouteMemberMapper")
                    .because("llm_provider / llm_model / llm_route 只允许 catalog/admin/routing 访问");

    /** 路由解析与绑定校验不得创建客户端（客户端缓存只属于 llm.client） */
    @ArchTest
    static final ArchRule llm_routing_and_validation_must_not_depend_on_client_registry =
            noClasses().that().resideInAnyPackage(
                            "com.dark.javaHarness.llm.routing..",
                            "com.dark.javaHarness.llm.validation..")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("com.dark.javaHarness.llm.client.LlmClientRegistry")
                    .because("路由解析/校验只产出配置，客户端构建与缓存收敛在 llm.client");
}
