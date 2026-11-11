# 配置说明

## 一、环境变量（启动时注入）

### 必填
| 变量 | 说明 | 用于 |
|---|---|---|
| `API_SERVICE_TOKEN` | 服务间共享 token（`openssl rand -hex 32` 生成） | 全部服务鉴权（X-Api-Token） |
| `ADMIN_SEED_PASSWORD` | 首次建 admin 账号的密码（仅 seed 用） | chat-service |
| `CONFIG_ENCRYPTION_KEY` | AES-256-GCM 配置主密钥（`openssl rand -base64 32`） | core/chat-service |
| `QDRANT_API_KEY` | Qdrant 服务端与客户端共享的随机访问密钥 | Qdrant/vector/core |

### 可选
| 变量 | 默认 | 说明 |
|---|---|---|
| `DB_PASSWORD` | 无（必须设置） | MySQL 应用账号密码 |
| `DEEPSEEK_API_KEY` | 空 | llm-gateway 兜底 key（用户未配时用） |
| `ZHIPU_API_KEY` | 空 | 智谱 GLM 服务端 key；浏览器请求中的智谱 key 会被忽略 |
| `EMBEDDING_API_KEY` | 空 | embedding 供应商 key（硅基流动） |
| `EMBEDDING_BASE_URL` | https://api.siliconflow.cn/v1 | embedding 供应商地址 |
| `EMBED_MODEL` | BAAI/bge-m3 | 嵌入模型 |
| `EXAM_BANK_ROOT` / `APP_EXAMBANKROOTPATH` | /home/takina/AiQuestionBank/data/exam-bank | 题库根路径 |
| `TRUST_PROXY_HEADERS` | 空（false） | 部署在 nginx 后置 true：才信任 X-Real-IP/X-Forwarded-For（限流/审计用真实 IP，防直连伪造） |
| `SOFFICE_BIN` | soffice | OCR 服务 LibreOffice 命令（本地 Flatpak：flatpak-spawn --host soffice；Docker 内：soffice） |
| `LO_TMP_DIR` | /tmp/lo-tmp | LibreOffice 转换临时目录（本地 dev 建议指到 data/.lo-tmp） |
| `LLM_DEBUG` | 空 | 置 1 时 llm-gateway 打印首块结构等调试日志 |
| `APP_VECTOR_SNAPSHOT_PATH` | 空（禁用） | vector-service 向量快照文件路径（Docker 已挂载 /data/vector-snapshot.bin，重启免全量重嵌入） |
| `MYSQL_ROOT_PASSWORD` | 无（必须设置） | MySQL root（仅 Docker 首次初始化） |

> **注意**：`EMBEDDING_API_KEY` 未设时只允许 lexical-only 降级；生产默认禁止 hash 伪嵌入参与排序。配置后服务启动会调用一次 BGE-M3，并强制验证返回维度为 1024。


### MinerU（可选，ocr-service）

| 变量 | 默认 | 说明 |
|---|---|---|
| `MINERU_URL` | 空（禁用） | MinerU API 地址，如 `http://mineru:8000`；启用后 PDF/图片优先走 MinerU |
| `MINERU_BACKEND` | `pipeline` | 解析后端；有 GPU 可换 `hybrid-engine` 等 |
| `MINERU_TIMEOUT` | `900` | MinerU 调用读超时（秒） |

### RAG 检索（chat-service，application.yml `app.rag.*`）

| 键 | 默认 | 说明 |
|---|---|---|
| `app.rag.rrf-k` | 60 | RRF 融合常数（越大两路排名越平权） |
| `app.rag.fuse-top-n` | 8 | 融合后参与页聚合的 chunk 数 |
| `app.rag.vector-fetch` | 32 | 向量侧全局召回条数（勾选文件时从中过滤） |
| `app.rag.keyword-pool-max` | 5000 | BM25 候选池上限（超出跳过关键词路） |

## 二、Admin 设置（SystemConfig 表，界面在 Admin → 设置）

### 邮件告警（服务下线/解析失败/聊天失败）
| key | 说明 |
|---|---|
| `smtp_host` | 告警 SMTP 服务器 |
| `smtp_port` | 告警端口（默认 465 SSL） |
| `smtp_user` | 告警发件账号 |
| `smtp_pass` | 告警授权码（AES-256-GCM 加密存储；接口只返回 ******） |
| `alarm_emails` | 告警收件人（逗号分隔多邮箱） |
| `alarm_enabled` | 告警总开关（true/false） |

### 学生邮件（找回密码）
| key | 说明 |
|---|---|
| `student_smtp_host` | 学生邮件 SMTP（独立于告警） |
| `student_smtp_port` | 端口（默认 465） |
| `student_smtp_user` | 发件账号 |
| `student_smtp_pass` | 授权码（AES-256-GCM 加密存储；接口只返回 ******） |

> 学生 SMTP 未配置时自动降级到告警 SMTP；若仍无法发送，只记录失败，不记录重置链接或 token。

### 站点
| key | 说明 |
|---|---|
| `app_base_url` | 邮件重置链接的前缀（生产设 `https://你的域名`） |

## 三、前端配置

| key | 内容 |
|---|---|
| `ai-config` | 仅持久化非敏感模型配置；`apiKey` 只保存在当前页面内存，刷新后清除 |
| `exam_bank_session_id` | 会话 ID（`学号_随机`，后端强制归属校验） |
| `student-token` / `admin-token` | 仅保存字面标记 `cookie`；真实会话在 HttpOnly Cookie |
| `theme-mode` | 主题：light / dark / auto |

> AI 供应商选择在 **个人中心 → 模型选择**：56 个预设 + 自定义；思考型模型推荐 maxTokens 16384，非思考 8192；多模态模型标注（看图不走 OCR）。

## 四、vite / nginx 代理（前端 API 路由）

dev（vite proxy）与 prod（nginx）等价映射：

| 前端路径 | 后端 |
|---|---|
| /api/chat/*、/api/student/*、/api/admin/* | chat-service:39183 |
| /api/document/* | document-service:39181 |
| /api/vector/* | vector-service:39182 |
| /api/llm/* | llm-gateway:8003（注入 X-Api-Token） |
| /api/render-page、/api/count-pages | ocr-service:8001（注入 X-Api-Token） |
