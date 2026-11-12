-- LLM 三层模型：供应商 / 模型部署 / 路由与成员
-- 设计文档：docs/design/2026-11-12-llm-provider-model-routing.md
-- 本迁移只新增表与 Agent 绑定列，不删除旧 model_provider（旧路径在后续版本移除）

CREATE TABLE llm_provider (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  code           VARCHAR(32)  NOT NULL COMMENT '供应商代码，如 dashscope / deepseek / tecent',
  base_url       VARCHAR(255) NOT NULL COMMENT 'OpenAI 兼容端点根地址',
  credential_ref VARCHAR(128) NOT NULL COMMENT '凭证引用（环境变量名或密钥别名），不落明文',
  protocol       VARCHAR(32)  NOT NULL DEFAULT 'openai' COMMENT '协议类型，当前仅 openai',
  enabled        TINYINT      NOT NULL DEFAULT 1,
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME     NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_provider_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='LLM 供应商：端点与凭证';

CREATE TABLE llm_model (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  provider_id    BIGINT       NOT NULL,
  upstream_name  VARCHAR(128) NOT NULL COMMENT '请求体中下发的模型名',
  display_name   VARCHAR(128) NOT NULL COMMENT '界面展示名，不参与查找',
  capabilities   JSON         NOT NULL COMMENT '{tool_calling,vision,thinking,json_mode,context_window}',
  default_params JSON         NULL COMMENT '{temperature,max_tokens,top_p,...}',
  enabled        TINYINT      NOT NULL DEFAULT 1,
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME     NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_model_provider_name (provider_id, upstream_name),
  KEY idx_model_provider (provider_id),
  CONSTRAINT fk_model_provider FOREIGN KEY (provider_id) REFERENCES llm_provider (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型部署：一个供应商下的一个可调用模型';

CREATE TABLE llm_route (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  code        VARCHAR(64)  NOT NULL COMMENT '稳定别名：main / fast / judge / multi ...',
  description VARCHAR(255) NULL,
  strategy    VARCHAR(16)  NOT NULL DEFAULT 'priority' COMMENT '当前仅 priority',
  enabled     TINYINT      NOT NULL DEFAULT 1,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_route_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型路由：Agent 唯一绑定的稳定别名';

CREATE TABLE llm_route_member (
  route_id   BIGINT  NOT NULL,
  model_id   BIGINT  NOT NULL,
  priority   INT     NOT NULL COMMENT '越小越优先',
  enabled    TINYINT NOT NULL DEFAULT 1,
  PRIMARY KEY (route_id, model_id),
  UNIQUE KEY uk_route_priority (route_id, priority),
  KEY idx_member_model (model_id),
  CONSTRAINT fk_member_route FOREIGN KEY (route_id) REFERENCES llm_route (id),
  CONSTRAINT fk_member_model FOREIGN KEY (model_id) REFERENCES llm_model (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='路由成员：有序候选与失败链';

ALTER TABLE agent
  ADD COLUMN route_id BIGINT NULL AFTER description,
  ADD COLUMN params JSON NULL COMMENT 'Agent 级参数覆盖 {temperature,max_tokens,thinking_mode,show_thinking}',
  ADD KEY idx_agent_route (route_id),
  ADD CONSTRAINT fk_agent_route FOREIGN KEY (route_id) REFERENCES llm_route (id);

-- ---------------------------------------------------------------------------
-- 从旧 model_provider / agent 回填（保留旧模型 id，便于 Agent 绑定映射）
-- ---------------------------------------------------------------------------

INSERT INTO llm_provider (code, base_url, credential_ref)
SELECT provider, MIN(api_url), CONCAT(UPPER(provider), '_API_KEY')
FROM model_provider
GROUP BY provider;

INSERT INTO llm_model (id, provider_id, upstream_name, display_name, capabilities, default_params, enabled, created_at, updated_at)
SELECT mp.id,
       lp.id,
       mp.model,
       mp.model,
       JSON_OBJECT(
         'tool_calling', TRUE,
         'vision', FALSE,
         'thinking', TRUE,
         'json_mode', TRUE,
         'context_window', 65536
       ),
       NULL,
       mp.status,
       mp.created_at,
       mp.updated_at
FROM model_provider mp
JOIN llm_provider lp ON lp.code = mp.provider;

INSERT INTO llm_route (code, description) VALUES
  ('main',  '主对话模型：当前绑定 deepseek-v4.1-flash-1017'),
  ('fast',  '轻量内部调用：当前绑定 qwen3.8-flash'),
  ('judge', '路由判定：当前绑定 qwen3.8-27b'),
  ('multi', '多 Agent 编排：当前绑定 qwen3.7-flash');

INSERT INTO llm_route_member (route_id, model_id, priority, enabled)
SELECT r.id, m.id, 10, 1
FROM llm_route r
JOIN llm_model m
WHERE (r.code = 'main'  AND m.id = 12)
   OR (r.code = 'fast'  AND m.id = 11)
   OR (r.code = 'judge' AND m.id = 5)
   OR (r.code = 'multi' AND m.id = 4);

UPDATE agent a
JOIN llm_route r ON r.code = CASE a.model_provider_id
                               WHEN 12 THEN 'main'
                               WHEN 11 THEN 'fast'
                               WHEN 5  THEN 'judge'
                               WHEN 4  THEN 'multi'
                               ELSE 'main'
                             END
SET a.route_id = r.id
WHERE a.route_id IS NULL;
