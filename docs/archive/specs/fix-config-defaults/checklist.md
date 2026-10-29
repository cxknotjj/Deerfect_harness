# Checklist（R1）
- [ ] 5 处弱默认全部 env 化（MYSQL_PASSWORD / NAPCAT_API_TOKEN / NAPCAT_EVENT_SECRET / QQ_SELF_ID / PGVECTOR_PASSWORD）
- [ ] 本地回退默认不变（NapCat token 仍 napcat123 等，本地 QQ 不断）
- [ ] 每处带部署指引注释
- [ ] `mvn -pl server -am test` 0 失败 0 错误
- [ ] 独立 commit
