package com.dark.javaHarness.channel.feishu.ws;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 处理回执：慢轮次贴、快轮次不贴、结束必撤、失败不崩 */
@ExtendWith(MockitoExtension.class)
class FeishuReceiptTest {

    @Mock
    private FeishuApiClient api;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void slowTurn_addsThenRemoves() {
        when(api.addReaction("om_1", "OnIt"))
                .thenReturn(new FeishuApiClient.Reaction(true, "re_1", null));
        when(api.deleteReaction("om_1", "re_1"))
                .thenReturn(new FeishuApiClient.Reaction(true, "re_1", null));

        FeishuReceipt receipt = new FeishuReceipt(executor, api, "om_1", "OnIt", 20);
        verify(api, timeout(2000)).addReaction("om_1", "OnIt");

        receipt.clear();
        verify(api, timeout(2000)).deleteReaction("om_1", "re_1");
    }

    @Test
    void fastTurn_neverAdds() throws Exception {
        FeishuReceipt receipt = new FeishuReceipt(executor, api, "om_2", "OnIt", 200);

        receipt.clear();
        Thread.sleep(400); // 给延迟线程足够时间醒来：它应该看到 finished 后直接退出

        verify(api, never()).addReaction(any(), any());
    }

    @Test
    void addFails_noDeleteAndNoThrow() {
        when(api.addReaction(any(), any()))
                .thenReturn(new FeishuApiClient.Reaction(false, null, "HTTP 403 no permission"));

        FeishuReceipt receipt = new FeishuReceipt(executor, api, "om_3", "OnIt", 10);
        verify(api, timeout(2000)).addReaction("om_3", "OnIt");

        receipt.clear();
        verify(api, never()).deleteReaction(any(), any());
    }
}
