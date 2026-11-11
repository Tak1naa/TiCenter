# 生产化升级说明

## 默认架构

默认 Compose 使用一个 `core-service` JVM 承载 chat/document/vector 三个代码模块；MySQL、Redis、Qdrant、OCR、Embedding 与 LLM Gateway 仍为独立进程。原三个可执行服务保留在 `legacy-services` profile 中用于排障，但不建议在 16 GB 单机同时启动。

检索链路为 BGE-M3 dense + FastEmbed `Qdrant/bm25` sparse → Qdrant RRF（20 候选）→ 可选外部 BGE reranker（8 候选）→ 稳定 `[S1]` 引用。dense 不可用时只进入 lexical-only，禁止随机向量参与生产排序。Qdrant payload 包含课程、访问范围、文档版本、页码、结构类型和父块。

## 首次部署

1. 复制 `.env.example` 为 `.env`，替换全部 `change-me`。生产保持 `COOKIE_SECURE=true`，并在上游负载均衡器或 Nginx 前启用 TLS。
2. 执行 `docker compose up -d --build`。只有前端 80 对外；Grafana 仅绑定回环地址且需 `observability` profile。
3. 检查 `/actuator/health/liveness`、`readiness` 和 Prometheus。Java 启动时 Flyway 自动执行 V2/V3，Hibernate 只做 `validate`。
4. 新旧索引切换前先保持 `RAG_QDRANT_ENABLED=false` 验证迁移，再构建 pending chunk 并核对 Qdrant point 数。异常时关闭开关回退受限的 legacy 检索。

## 评测与容量

- `rag-eval/` 定义至少 300 条人工金标格式、确定性 IR 指标和 Ragas 指标。切块、模型、阈值、重排或提示词变更必须复用同一数据集。
- `load-tests/chat-sse.js` 验证 1000 在线用户及“成功或带 Retry-After 的 429”。真实供应商额度、首 token 延迟需单独压测。
- 默认准入为 128 活跃流、512 等待、30 秒；Redis Bucket4j 同时限制学生、IP 和供应商。
- 考试周设置 `PARSE_PAUSED=true`、`RERANK_ENABLED=false`，预热 Qdrant/Caffeine 并冻结大版本。

## Prompt Harness

- 所有提示词集中在版本化 `PromptCatalog`，实验记录必须包含 `promptVersion`。
- 消息顺序固定为“稳定 system → 稳定资料块 → 短历史 → 当前问题”，让相同文档召回共享尽可能长的供应商缓存前缀。
- 默认上下文最多 12000 字符、历史最多 4000 字符/4 轮；聊天默认输出 4096 tokens，错题讲解 2048，出题按题数动态分配。
- SSE `meta/done` 和 `chat_request_traces` 记录 prompt cache key、输入/输出 token 估算及一小时内前缀复用候选。这里明确标为 estimate/candidate，不冒充供应商账单或真实缓存命中。
- 每次 prompt 变更先用同一金标集比较忠实度、拒答、引用和 token，再提升 `PromptCatalog.VERSION`；质量下降时不得只因 token 更少而发布。

## 安全边界

浏览器会话使用 HttpOnly/Secure/SameSite Cookie 与双提交 CSRF，前端不再持久化真实 token。内部检索不经 Nginx 暴露。上传先进入隔离文件，执行 Tika 魔数、Office 解压预算和可选 clamd 扫描后才原子发布。题库上下文按不可信数据隔离，服务端只接受实际存在的稳定来源 ID。

单机仍不能抵抗主机、磁盘或机房故障。真正高可用必须增加第二台核心节点，并为 MySQL/Qdrant 配置跨机副本或托管服务；快照也必须异机保存并每月演练恢复。
