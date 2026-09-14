-- pgvector 扩展需超级用户在建表前预装：
-- 应用侧 PgVectorStore.initializeSchema 只建 vector_store_harness 表，不建扩展；
-- 扩展缺失时 POST /api/knowledge/sync 直接报错（项目硬约束）。
-- pgvector/pgvector 镜像已内置扩展文件，此处仅启用；挂载到 /docker-entrypoint-initdb.d 首次建库时执行
CREATE EXTENSION IF NOT EXISTS vector;
