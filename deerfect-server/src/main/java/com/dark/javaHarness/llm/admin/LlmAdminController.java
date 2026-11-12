package com.dark.javaHarness.llm.admin;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * LLM 管理接口（供应商 / 模型 / 路由 / Agent 绑定 / 连通性测试）。
 *
 * <p>写操作先校验再落库，落库后触发热更新；校验失败返回 400（GlobalExceptionHandler
 * 统一 {code,message}），配置校验失败同样回滚并返回原因。
 */
@RestController
@RequestMapping("/api/admin")
public class LlmAdminController {

    private final LlmAdminService service;

    public LlmAdminController(LlmAdminService service) {
        this.service = service;
    }

    @GetMapping("/providers")
    public List<LlmAdminModels.ProviderView> listProviders() {
        return service.listProviders();
    }

    @PostMapping("/providers")
    public LlmAdminModels.ProviderView createProvider(
            @RequestBody LlmAdminModels.ProviderUpsert request) {
        return service.createProvider(request);
    }

    @PutMapping("/providers/{id}")
    public LlmAdminModels.ProviderView updateProvider(
            @PathVariable Long id, @RequestBody LlmAdminModels.ProviderUpsert request) {
        return service.updateProvider(id, request);
    }

    @GetMapping("/models")
    public List<LlmAdminModels.ModelView> listModels() {
        return service.listModels();
    }

    @PostMapping("/models")
    public LlmAdminModels.ModelView createModel(
            @RequestBody LlmAdminModels.ModelUpsert request) {
        return service.createModel(request);
    }

    @PutMapping("/models/{id}")
    public LlmAdminModels.ModelView updateModel(
            @PathVariable Long id, @RequestBody LlmAdminModels.ModelUpsert request) {
        return service.updateModel(id, request);
    }

    @GetMapping("/routes")
    public List<LlmAdminModels.RouteView> listRoutes() {
        return service.listRoutes();
    }

    @PostMapping("/routes")
    public LlmAdminModels.RouteView createRoute(
            @RequestBody LlmAdminModels.RouteUpsert request) {
        return service.createRoute(request);
    }

    @PutMapping("/routes/{id}")
    public LlmAdminModels.RouteView updateRoute(
            @PathVariable Long id, @RequestBody LlmAdminModels.RouteUpsert request) {
        return service.updateRoute(id, request);
    }

    /** 按成员顺序做一次真实连通性测试：返回每个成员的延迟与错误分类 */
    @PostMapping("/routes/{id}/test")
    public List<LlmAdminModels.RouteTestResult> testRoute(@PathVariable Long id) {
        return service.testRoute(id);
    }

    @PutMapping("/agents/{id}/route")
    public void updateAgentRoute(@PathVariable Long id,
                                 @RequestBody LlmAdminModels.AgentRouteUpdate request) {
        service.updateAgentRoute(id, request);
    }
}
