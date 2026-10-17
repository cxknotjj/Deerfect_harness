package com.dark.javaHarness.channel.qq.event;

import com.dark.javaHarness.service.ReplyPostProcessor;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 渠道侧回复后处理器：剥离 [[voice]] 标记并声明 voice 标志。
 *  由 core 在写回记忆/构造响应前调用（见 core 的 ReplyPostProcessor SPI）。 */
@Component
public class QqReplyProcessor implements ReplyPostProcessor {

    @Override
    public Processed process(String rawReply) {
        ReplyMarker.Parsed p = ReplyMarker.parse(rawReply);
        return new Processed(p.text(), p.voice() ? Set.of("voice") : Set.of());
    }
}
