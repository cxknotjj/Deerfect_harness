# Tasks
- [ ] 逐处替换 5 个弱默认为 env 占位（保留回退默认 + 注释部署指引）
- [ ] server 全量测试绿
# Checklist
- [ ] 5 处全部 env 化且本地回退默认不变
- [ ] `mvn -pl server -am test` 绿（含上下文加载测试）
- [ ] 独立 commit（-F 文件防乱码）
