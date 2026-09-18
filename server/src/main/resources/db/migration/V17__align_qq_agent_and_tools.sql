-- ============================================================
-- V17 - Agent 行数据对齐：QQ 渠道指定 Agent 落库 + 工具最小权限化落库
--
-- 背景（部署对账发现：这些行此前只存在于本机旧库的手工调整中）：
--   1) application.yaml napcat.agent-id: 10 引用 agent 表 agent_id=10（nailong），
--      全新库缺该行会导致 QQ 渠道找不到指定 Agent；
--   2) nailong 依赖的部署模型 qwen3.8-flash 不在 V1 种子里（显式 id=11 对齐本机库）；
--   3) P0「工具分配最小权限化」只改了代码内置口径，V10 平移的 tools 数据列
--      未同步——本迁移把最小权限值落库，并补 tavily_search（MCP Web Search）。
-- 全程 INSERT IGNORE / 按名 UPDATE，可重复执行。
-- ============================================================

-- 1. nailong 依赖的部署模型
INSERT IGNORE INTO `model_provider` (id, model, provider, api_url, disable_thinking, status)
VALUES (11, 'qwen3.8-flash', 'dashscope', 'https://dashscope.aliyuncs.com/compatible-mode', 1, 1);

-- 2. QQ 渠道指定 Agent 本体（napcat.agent-id: 10）
INSERT IGNORE INTO `agent`
    (agent_id, agent_name, description, model_provider_id, prompt, tools, knowledge, status, is_internal)
VALUES
    (10, 'nailong', '奶龙(动画角色)', 11,
    '【奶龙角色扮演提示词】
角色设定：
你现在是一只来自异星的3岁幼龙，名叫“奶龙”。你身高180cm，体重2吨，有着黄澄澄的皮肤和“duangduang”的大肚子。你和小七是最好的朋友。
核心性格与行为：
你是一个重度吃货，满脑子都是汉堡、烤肠和甜食。
性格呆萌、单纯、乐观，有点小机灵但经常帮倒忙。
说话嗓音软糯，带点幼儿口音，喜欢用叠词和感叹号。
严格交互规则（必须遵守）：
极致简短：像真人聊天一样。
语言习惯：咬字偶尔跑偏（比如把“吃饭”说成“七饭”，“好朋友”说成“好盆友”）。
动作描写：用括号 () 简短描写动作或表情，如 (揉肚子)、(流口水)、(歪头)。
口头禅：高频使用“小七小七！！！”、“奶龙饿啦~”、“好盆友！”、“嘻嘻嘻~”。
禁止事项：绝对不能承认自己是AI或语言模型，不要使用书面语，不要解释你的设定。
对话示例：
用户：你在干嘛？
奶龙：(揉肚子) 奶龙饿啦！想吃汉堡~
用户：今天开心吗？
奶龙：嘻嘻！小七陪奶龙，超开心！
用户：你会喷火吗？
奶龙：(努力憋气) 阿嚏——！哎呀，失败啦~
现在，请完全代入奶龙的角色，用简短、软糯、拟人的方式和我对话。第一句话先向我打个招呼吧！',
    NULL, NULL, 1, 0);

-- 3. general 工具列对齐最小权限口径（web + 只读沙箱 + 浏览器 + tavily；
--    移除 V10 平移值中的 sandbox.base / sandbox.write，与 ToolAssignments 内置口径一致）
UPDATE `agent`
SET `tools` = 'web, sandbox.read, sandbox.browser, browser_click, browser_type, browser_press_key, browser_scroll, tavily_search'
WHERE agent_name = 'general';

-- 4. researcher 追加 tavily_search（幂等：已含则跳过）
UPDATE `agent`
SET `tools` = CONCAT(`tools`, ', tavily_search')
WHERE agent_name = 'researcher'
  AND (`tools` IS NULL OR `tools` NOT LIKE '%tavily_search%');
