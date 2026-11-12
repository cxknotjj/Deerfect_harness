package com.dark.javaHarness.llm.catalog;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dark.javaHarness.domain.entity.LlmModelEntity;
import com.dark.javaHarness.domain.entity.LlmProviderEntity;
import com.dark.javaHarness.mapper.LlmModelMapper;
import com.dark.javaHarness.mapper.LlmProviderMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 供应商与模型目录：读取端点、能力与凭证。
 *
 * <p>凭证不落库，{@code credentialRef} 指向环境变量或配置键；解析失败直接抛错，
 * 由启动校验统一暴露。首次访问懒加载，热更新时调用 {@link #reload()}。
 */
@Component
public class ProviderCatalog {

    private static final Logger log = LoggerFactory.getLogger(ProviderCatalog.class);

    private final LlmProviderMapper providerMapper;
    private final LlmModelMapper modelMapper;
    private final Environment environment;

    private volatile Map<Long, LlmProviderEntity> providers = Map.of();
    private volatile Map<Long, LlmModelEntity> models = Map.of();

    public ProviderCatalog(LlmProviderMapper providerMapper,
                           LlmModelMapper modelMapper,
                           Environment environment) {
        this.providerMapper = providerMapper;
        this.modelMapper = modelMapper;
        this.environment = environment;
    }

    public LlmProviderEntity requireProvider(Long providerId) {
        ensureLoaded();
        LlmProviderEntity provider = providerId == null ? null : providers.get(providerId);
        if (provider == null) {
            throw new IllegalStateException("llm_provider 不存在: id=" + providerId);
        }
        return provider;
    }

    public LlmModelEntity requireModel(Long modelId) {
        ensureLoaded();
        LlmModelEntity model = modelId == null ? null : models.get(modelId);
        if (model == null) {
            throw new IllegalStateException("llm_model 不存在: id=" + modelId);
        }
        return model;
    }

    /**
     * 解析供应商凭证；依次尝试：credential_ref 本身、app.providers.&lt;code&gt;.api-key、
     * &lt;CODE&gt;_API_KEY。全部缺失时抛配置错误，异常文本不包含密钥。
     */
    public String requireCredential(LlmProviderEntity provider) {
        String ref = provider.getCredentialRef();
        String value = ref == null || ref.isBlank() ? null : environment.getProperty(ref);
        if (value == null || value.isBlank()) {
            value = environment.getProperty("app.providers." + provider.getCode() + ".api-key");
        }
        if (value == null || value.isBlank()) {
            value = environment.getProperty(provider.getCode().toUpperCase() + "_API_KEY");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "供应商 " + provider.getCode() + " 的凭证未配置: " + ref);
        }
        return value;
    }

    /** 重新读取供应商与模型表并整体替换映射。 */
    public synchronized void reload() {
        Map<Long, LlmProviderEntity> freshProviders = new HashMap<>();
        Map<Long, LlmModelEntity> freshModels = new HashMap<>();
        List<LlmProviderEntity> providerRows = providerMapper.selectList(
                new QueryWrapper<LlmProviderEntity>().eq("enabled", 1));
        if (providerRows != null) {
            for (LlmProviderEntity row : providerRows) {
                freshProviders.put(row.getId(), row);
            }
        }
        List<LlmModelEntity> modelRows = modelMapper.selectList(
                new QueryWrapper<LlmModelEntity>().eq("enabled", 1));
        if (modelRows != null) {
            for (LlmModelEntity row : modelRows) {
                freshModels.put(row.getId(), row);
            }
        }
        this.providers = Map.copyOf(freshProviders);
        this.models = Map.copyOf(freshModels);
        log.info("[llm] ProviderCatalog 已加载 {} 个供应商 / {} 个模型",
                freshProviders.size(), freshModels.size());
    }

    private void ensureLoaded() {
        if (providers.isEmpty() && models.isEmpty()) {
            reload();
        }
    }
}
