-- V2 - QQ 奶龙接入文字转语音工具
-- 仅用户明确要求语音时调用 text_to_speech；工具只负责合成，QQ 渠道负责发送。
UPDATE `agent`
SET `tools` = 'text_to_speech',
    `prompt` = CONCAT(`prompt`, '\n\n【语音规则】当用户明确要求“用语音说 / 念一下 / 语音回答”时：先调用 text_to_speech 工具，把你要回答的内容合成为语音（1~200 字）；工具返回“语音已生成”后，直接用一句简短口语确认即可，不要再重复朗读内容。语音生成失败时，照常把内容用文字说出来。')
WHERE `agent_name` = 'nailong';
