package com.dark.javaHarness.channel.qq.event;

/** QQ 渠道回复标记解析：识别 [[voice]]（渠道私有约定，core 不认识它）。 */
final class ReplyMarker {

    static final String VOICE = "[[voice]]";

    private ReplyMarker() {
    }

    /** @return (剥离标记后的文本, 是否要求语音) */
    static Parsed parse(String raw) {
        if (raw == null) {
            return new Parsed("", false);
        }
        if (!raw.contains(VOICE)) {
            return new Parsed(raw, false);
        }
        return new Parsed(raw.replace(VOICE, "").strip(), true);
    }

    record Parsed(String text, boolean voice) {
    }
}
