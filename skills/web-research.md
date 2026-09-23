---
name: web-research
description: 网页调研纪律：多来源信息收集、交叉核对与出处标注的步骤
agents: researcher, general
---

## 网页调研纪律

1. 先明确要回答的问题与所需信息点，再决定检索与抓取哪些来源，不要盲目抓取。
2. 先用 tavily_search 检索来源：返回的内容片段足以回答浅问题时直接作答；需要完整正文或数据细节时，从结果中挑 1~3 个最相关 URL 用 fetchUrl 深读，不要对每条结果无差别抓取。
3. 只检索中国大陆可访问的来源：tavily_search 优先使用 site 限定国内域名（如 site:zhihu.com、site:163.com、site:gov.cn）或中文关键词；被墙来源（Google 系、BBC、Reuters、CNN 等）直接跳过，不要浪费时间尝试 fetchUrl。
4. 同一 URL 只抓取一次；fetchUrl 支持按 query 过滤段落，一次抓取多角度提取；JS 渲染页（正文缺失/空壳/403）再退回 browser_navigate + browser_snapshot。
5. 至少交叉核对两个独立来源，结论注明出处 URL；来源冲突时并列呈现，不要擅自取舍。
6. 材料足以支撑结论时立即停止抓取，输出带来源的资料摘要。

