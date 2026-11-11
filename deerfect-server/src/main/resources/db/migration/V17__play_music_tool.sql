-- 点歌工具：给默认助手与奶龙各加一条 play_music。
--
-- 为什么是这两个：general 是默认会话用的助手，用户说「放首歌」最可能落到它头上；
-- nailong 本来就带 text_to_speech（语音链路），点歌与念稿属同一类能力。
-- 其它专家（coder / analyst / researcher…）在干具体活，不该突然开始放歌。
--
-- 按 agent_name 而不是 id 更新：id 依赖初始插入顺序，换个环境就对不上。
-- 只在还没有该工具时追加，迁移可重复执行，也不会覆盖后来手工配的清单。
UPDATE `agent`
SET `tools` = CONCAT(`tools`, ',play_music')
WHERE `agent_name` IN ('general', 'nailong')
  AND `tools` IS NOT NULL
  AND `tools` NOT LIKE '%play_music%';
