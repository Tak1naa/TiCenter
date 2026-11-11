# 系统架构

## 拓扑

```
┌─────────────────────────────────────────────────────────────────┐
│  前端 (React + Vite)                                            │
│  ┌─────────┬──────────────┬──────────────┐                      │
│  │ 题库来源 │  聊天面板     │  个人中心     │  <-- NotebookPage     │
│  │ Sources │  ChatPanel   │  Profile     │                      │
│  └─────────┴──────────────┴──────────────┘                      │
│  /login /reset-password /admin/*                                │
└──────┬──────────────────────────────────────────────────────────┘
       │ vite proxy (dev) / nginx (prod, 80)
       ▼
┌──────────────────────────────────────────────────────────────────┐
│  chat-service :39183   (Java 25, Spring Boot)                    │
│  ├ RAG 问答（关键词匹配 + 语义检索）                               │
│  ├ 学生认证（学号/密码/邮箱/找回）                                  │
│  ├ Admin 后台（RBAC/审计/监控/邮件告警/CSV）                        │
│  └ 多模态图转发（base64 代抓）                                     │
│  document-service :39181 (文件树/上传/解析状态/raw 下载)           │
│  vector-service :39182  (向量索引/搜索，内存库启动自建)             │
└──────┬───────────┬───────────┬───────────────────────────────────┘
       │           │           │
┌──────▼───┐ ┌─────▼────┐ ┌───▼─────────┐   ┌──────────────┐
│ llm-gw   │ │ embedding│ │ ocr-service │   │ MySQL :3306  │
│ :8003    │ │ :8002    │ │ :8001       │   │ exam_bank    │
│ 56 供应商 │ │ bge-m3   │ │ MarkItDown  │   │ 7 张表       │
│ SSE 流式  │ │ 语义向量  │ │ LibreOffice │   │              │
└──────────┘ └──────────┘ │ PaddleOCR   │   └──────────────┘
                          └─────────────┘
```

## 服务职责

| 服务 | 端口 | 技术 | 职责 |
|---|---|---|---|
| frontend | 5173 (dev) / 80 (prod) | React 19 + Vite | 学生端三栏 + Admin 后台 + 登录/重置 |
| chat-service | 39183 | Java 25 + Spring Boot | 核心：RAG、学生认证、Admin、监控告警、邮件 |
| document-service | 39181 | Java 25 | 题库文件管理：树/上传/解析调度/raw 下载 |
| vector-service | 39182 | Java 25 | 向量索引（内存）+ 语义搜索；启动自动索引 |
| ocr-service | 8001 | Python FastAPI | 文档解析：MarkItDown → LibreOffice → PaddleOCR 三级降级 |
| embedding-service | 8002 | Python FastAPI | 文本嵌入（硅基流动 bge-m3），key 缺失时 hash 降级 |
| llm-gateway | 8003 | Python FastAPI | AI 供应商代理：流式/多模态/base64 图代抓/限流 |
| mysql | 3306 | MySQL 8.4 | 统一数据库 exam_bank |

## 数据流

### 1. 提问（RAG 全链路，BM25+向量 RRF 混合检索）
```
学生提问 → chat-service retrieveContext
  ├─ 候选池：勾选文件的全部 chunk / 未勾选则全库
  ├─ ranker A：BM25（2-gram 分词，k1=1.5 b=0.75；池 >5000 chunk 时跳过）
  ├─ ranker B：embedQuery(8002) → vector-search(39182) top32 → 阈值 0.3 + 勾选文件过滤
  └─ RRF 融合：score = Σ 1/(60+rank) → top8 → 按页聚合整页
  降级链：embedding 不可用 → 仅 BM25（沿用弱信号守卫：命中<3/top3 弱 → 全量页）
         双路均无信号 → 勾选模式给全部页 / 全库告知未检索到
→ 上下文注入 user 消息（[来源: 文件 第X页] + 整页文本）
→ llm-gateway(8003) → 供应商 → SSE 流式回前端
→ 引用按 AI 回答中 [文件名 第X页] 正则过滤（交集）
```

### 1b. 智能题库闭环（刷题）
```
学生勾选文件 + 出题参数 → /api/quiz/generate
  → RagService.buildGenerationContext（有 topic 时 BM25 选页，否则前 12 页）
  → llm-gateway 生成严格 JSON 题目数组 → questions 表入库
作答 → /api/quiz/attempts → 归一化判分 → quiz_attempts 表
错题本 = 每人每题最新一次作答为错（答对自动移出）
错题 AI 讲解 → /api/quiz/explain（结合错误作答针对性讲解）
```

### 2. 多模态提问
```
multimodal=true → RagService 把引用页拼成 image_url
  → llm-gateway `_localize_image_urls`：localhost 图代抓转 base64（≤6MB）
  → OpenAI 多模态格式 content:[{type:text},{type:image_url}] → 供应商看图
```

### 3. 文档解析入库
```
上传 → document-service 存文件 → scan → ocr-service /parse
  → PDF/图片且配置 MINERU_URL：MinerU(content_list 按页分组, 公式→LaTeX/表格→HTML)
  → 否则/失败降级：MarkItDown(md) → LibreOffice→PDF → 逐页 get_text → 无文本 → PaddleOCR
  → 按页存 text_chunks（500 字符分块）
```

### 4. 向量索引
```
vector-service 启动 → StartupIndexer 遍历全部 chunk
  → embed(8002, bge-m3) → 内存 ConcurrentHashMap<chunkId, vec>
  → 重启自动重建（内存库，106 chunks 约 20s）
```

## 数据库表（exam_bank）

| 表 | 说明 |
|---|---|
| exam_files | 题库文件（路径/解析状态/页数/hash） |
| text_chunks | 每页文本分块（file_id/page_number/content/chunk_index） |
| chat_history | 对话记录（session_id/role/content/references_json） |
| students | 学生（学号/密码 hash/邮箱/重置 token） |
| admin_users | 管理员（用户名/密码 hash/角色/状态） |
| audit_logs | 审计日志（who/when/action/target） |
| system_configs | 系统配置 KV（SMTP/告警/站点） |
| questions | AI 生成的题目（题干/选项/答案/解析/来源页） |
| quiz_attempts | 学生答题记录（错题本按每人每题最新作答推导） |

## 关键设计决策

1. **选中文件用关键词匹配**（非向量）：无中文分词器，2-gram 滑窗 + 停用词整 token 过滤，按页聚合整页文本——避免"OCR 拆碎"感知
2. **引用只发实际用到的页**：AI 回答里 `[文件名 第X页]` 正则匹配 → 交集过滤 → 前端最多显示 8 条
3. **思考过程实时下发**：`event:reason` SSE，前端折叠块（流式展开、完成折叠）
4. **单机内存库**：会话/向量/限流均内存实现（本机/单容器场景），无 Redis 依赖
5. **服务间共享 token**：`API_SERVICE_TOKEN`（X-Api-Token 头），Python 用 `secrets.compare_digest`，Java 用 RestTemplate/WebClient 拦截器
