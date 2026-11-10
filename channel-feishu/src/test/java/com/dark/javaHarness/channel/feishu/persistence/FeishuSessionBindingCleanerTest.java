package com.dark.javaHarness.channel.feishu.persistence;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 删会话时的绑定清理：按 session_id 删，不能误删别人的绑定 */
@ExtendWith(MockitoExtension.class)
class FeishuSessionBindingCleanerTest {

    @Mock
    private FeishuSessionBindingMapper bindingMapper;

    @Test
    void deleteBySessionId_targetsSessionColumn() {
        new FeishuSessionBindingCleaner(bindingMapper).deleteBySessionId(258L);

        ArgumentCaptor<Wrapper<FeishuSessionBinding>> captor = wrapperCaptor();
        verify(bindingMapper).delete(captor.capture());
        String sql = ((AbstractWrapper<FeishuSessionBinding, ?, ?>) captor.getValue()).getSqlSegment();
        assertTrue(sql.contains("session_id"), "必须按 session_id 过滤，否则会清掉所有绑定：" + sql);
        Map<String, Object> params = ((AbstractWrapper<FeishuSessionBinding, ?, ?>) captor.getValue())
                .getParamNameValuePairs();
        assertTrue(params.containsValue(258L), "过滤参数应是被删的会话 id：" + params);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Wrapper<FeishuSessionBinding>> wrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }
}
