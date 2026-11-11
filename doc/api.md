# API 参考

Base URL：dev 经 vite proxy（`/api/*`）；prod 经 nginx（80 端口同路径）。

## 认证方式

| 场景 | Header |
|---|---|
| 学生（已登录） | `X-Student-Token: <token>` |
| Admin（已登录） | `X-Admin-Token: <token>` |
| 服务间调用（Java→Python） | `X-Api-Token: <API_SERVICE_TOKEN>` |

公开端点（免登录）：学生 login/register/forgot-password/reset-password、admin login、`/health`。

## 学生认证（chat-service :39183）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/student/register | 注册：`{studentNo(8位数字), password(≥8含字母数字), email}` |
| POST | /api/student/login | 登录：`{username, password}` → `{token, studentNo, email}`；每 IP 5 次/分限流 |
| POST | /api/student/logout | 登出（X-Student-Token） |
| GET | /api/student/me | 当前学生信息 |
| POST | /api/student/change-password | 改密：`{oldPassword, newPassword}` |
| POST | /api/student/forgot-password | 发重置邮件：`{email}`；每 IP 5 次/分限流 |
| POST | /api/student/reset-password | 重置：`{token, newPassword}`；重置后该学生全部会话失效 |
| POST | /api/student/bind-email | 绑定/更换邮箱 |
| POST | /api/student/verify-email | 邮箱验证 |
| POST | /api/student/delete-chats | 删除本人全部聊天记录 |
| GET | /api/student/data-stats | 数据统计（会话数/消息数） |
| DELETE | /api/student/delete-account | 删除账号及数据 |

## 聊天（chat-service）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/chat/ask | **SSE 流式问答**（需学生 token）。请求：`{question, sessionId(学号_xxx), selectedFiles[], provider, model, apiKey, baseUrl, maxTokens, multimodal}`。sessionId 必须以学号前缀（防 IDOR） |
| GET | /api/chat/history?sessionId= | 拉历史（需 token + 归属校验） |

### SSE 事件格式
```
event: reason   data: {"content":"思考片段"}      ← 思考过程（流式实时）
event: answer   data: {"content":"正文片段"}      ← 正文（JSON 包裹，换行转义）
event: source   data: {"fileName","page","snippet","filePath"}   ← 引用（回答后）
event: done     data: {"references":[], "sessionId","truncated"}
event: error    data: {"error":"...", "code":"AUTH_ERR|PROVIDER_ERR|EMPTY_REPLY"}
```

## 刷题（chat-service）

学生端智能题库闭环：AI 出题 → 刷题判分 → 错题本 → 错题讲解。认证同聊天（`X-Student-Token`）。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/quiz/generate` | AI 按勾选文件出题。body: `{selectedFiles:[路径], topic?, count?, types?:["choice","judge","short"], difficulty?, provider?, model?, apiKey?, baseUrl?, maxTokens?}` |
| GET | `/api/quiz/questions` | 题目列表（带当前学生最新作答；未作答的题隐藏 answer/explanation） |
| POST | `/api/quiz/attempts` | 提交作答并判分。body: `{questionId, answer}` → `{correct, correctAnswer, explanation}` |
| GET | `/api/quiz/mistakes` | 错题本（每人每题最新一次作答为错；答对自动移出） |
| POST | `/api/quiz/explain` | 错题 AI 针对性讲解。body: `{questionId, provider?, model?, ...}` → `{explanation}` |

判分规则：choice 按选项字母（A-D）、judge 按 对/错 归一化比较；short 归一化后全等或互相包含（≥4 字符）。

## Admin（chat-service）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/admin/auth/login | admin 登录 → token |
| POST | /api/admin/auth/logout | 登出（双清会话） |
| GET | /api/admin/auth/me | 当前管理员 |
| POST | /api/admin/auth/change-password | 改密（限流） |
| GET | /api/admin/stats | 仪表盘统计（文件/聊天/存储） |
| GET | /api/admin/chat-logs | 聊天记录（page/size） |
| GET | /api/admin/upload-logs | 上传记录 |
| GET | /api/admin/files | 文件列表 |
| POST | /api/admin/files/upload | 上传（文件名净化） |
| POST | /api/admin/files/delete | 删除文件 |
| GET | /api/admin/students | 学生列表 |
| GET | /api/admin/logs | 审计日志 |
| GET | /api/admin/config | 全部配置（密码类脱敏为 ******） |
| PUT | /api/admin/config/{key} | 更新配置 `{value, description?}` |
| GET | /api/admin/monitor/status | 监控：6 服务状态 + 失败统计 + 最近事件 |
| POST | /api/admin/data/batch/chats | 批量删聊天 |
| POST | /api/admin/data/batch/files | 批量删文件 |
| GET | /api/admin/data/export/chats | 导出聊天 CSV |
| GET | /api/admin/data/export/students | 导出学生 CSV |

## 文档（document-service :39181）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/document/scan | 触发扫描（路径限题库根内） |
| POST | /api/document/reparse | 强制重解析 |
| GET | /api/document/tree | 文件树 |
| GET | /api/document/status?path= | 解析状态 |
| GET | /api/document/preview?path=&page= | 页文本预览 |
| GET | /api/document/raw?path= | **原始文件字节**（前端 pdfjs/docx-preview 渲染用） |
| POST | /api/document/upload | 上传（multipart，≤100MB） |

## 向量（vector-service :39182）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/vector/search | `{vector, topK}` → `{hits:[{chunkId,content,score}]}` |
| POST | /api/vector/index-all | 手动重建索引 |
| GET | /api/vector/status | 索引状态 |

## AI 服务（Python）

### ocr-service :8001（需 X-Api-Token）
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /parse | `{filePath, forceReParse}` → 解析结果（MarkItDown/LibreOffice/OCR 三级） |
| GET | /render-page?filePath=&page= | 页 PNG（路径校验+扩展名白名单） |
| GET | /count-pages?filePath= | 页数（路径校验） |
| GET | /health | 健康 |

### embedding-service :8002（需 X-Api-Token）
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /embed | `{texts[]}` → embeddings（bge-m3，无 key 时 hash 降级） |
| POST | /embed_query | `{text}` → 单条嵌入 |
| GET | /health | 健康（含 mode: api/hash-fallback） |

### llm-gateway :8003（需 X-Api-Token）
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /chat/stream | SSE 流式。`{messages[], provider, model, api_key, base_url, temperature, max_tokens, stream}`。base_url 限预设供应商域名白名单；限流 60 次/分/IP |
| GET | /providers | 56 个预设供应商（含 reasoning_models/multimodal_models） |
| POST | /test-connection | `{provider, apiKey, baseUrl, model}` → `{ok, latencyMs}` |
| GET | /health | 健康 |

## 健康检查（全服务）

| 服务 | URL |
|---|---|
| chat-service | `http://localhost:39183/health` |
| document-service | `http://localhost:39181/health` |
| vector-service | `http://localhost:39182/health` |
| ocr-service | `http://localhost:8001/health` |
| embedding-service | `http://localhost:8002/health` |
| llm-gateway | `http://localhost:8003/health` |
