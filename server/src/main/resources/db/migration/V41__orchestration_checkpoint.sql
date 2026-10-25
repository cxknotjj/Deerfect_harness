CREATE TABLE orchestration_checkpoint (
    id BIGINT NOT NULL AUTO_INCREMENT,
    goal_id VARCHAR(64) NOT NULL,
    checkpoint_id VARCHAR(64) NOT NULL,
    stage VARCHAR(32) NOT NULL,
    state_json MEDIUMTEXT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_goal_created (goal_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
