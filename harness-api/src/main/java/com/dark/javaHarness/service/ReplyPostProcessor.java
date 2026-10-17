package com.dark.javaHarness.service;

import java.util.Set;

/**
 * 回复后处理扩展点（渠道无关的通用 SPI）：core 在「写回会话记忆」与「构造 ChatResponse」
 * 之前，按注册顺序依次调用所有实现，用其返回的文本落库与出参，并把声明出的标志透出。
 *
 * <p>约定：实现方必须是**无副作用、幂等**的纯文本处理；不得抛异常（core 会捕获并跳过）。
 * 典型用途：渠道剥离自己约定的控制标记（如 QQ 渠道的 [[voice]]）并声明 flag。
 */
public interface ReplyPostProcessor {

    /** 处理结果：@param text 清洗后的文本（写记忆与出参用） @param flags 声明出的标志（如 "voice"） */
    record Processed(String text, Set<String> flags) {
    }

    /** 处理原始回复文本；返回清洗后的文本与标志。入参非 null。 */
    Processed process(String rawReply);
}
