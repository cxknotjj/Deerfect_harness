package com.dark.javaHarness.graph.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 编排状态容器：替代 SAA {@code OverAllState}，保留 {@code value(key, type)} 读取语义。
 */
public final class OrchestrationState {

    private final String goalId;
    private final Map<String, Object> data;

    public OrchestrationState(String goalId, Map<String, Object> initial) {
        this.goalId = goalId;
        this.data = new LinkedHashMap<>(initial);
    }

    public String goalId() {
        return goalId;
    }

    public <T> Optional<T> value(String key, Class<T> type) {
        Object value = data.get(key);
        return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.empty();
    }

    public void put(String key, Object value) {
        data.put(key, value);
    }

    public void putAll(Map<String, Object> delta) {
        data.putAll(delta);
    }

    public Map<String, Object> snapshot() {
        return Collections.unmodifiableMap(data);
    }
}
