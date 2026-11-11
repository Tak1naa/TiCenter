# 接口示例（curl 可直接复制）

> 先设置变量（从 debug.md 获取 token）：
> ```bash
> STOKEN=你的学生token
> ATOKEN=你的admin token
> ```

## 学生认证

```bash
# 注册
curl -s -X POST http://localhost:5173/api/student/register \
  -H "Content-Type: application/json" \
  -d '{"studentNo":"20240099","password":"TestPass@1","email":"stu@example.com"}'

# 登录
curl -s -X POST http://localhost:5173/api/student/login \
  -H "Content-Type: application/json" \
  -d '{"username":"20240001","password":"pass1234"}'

# 改密码
curl -s -X POST http://localhost:5173/api/student/change-password \
  -H "Content-Type: application/json" -H "X-Student-Token: $STOKEN" \
  -d '{"oldPassword":"旧密码","newPassword":"新密码"}'

# 数据统计
curl -s http://localhost:5173/api/student/data-stats \
  -H "X-Student-Token: $STOKEN"

# 删除全部聊天
curl -s -X POST http://localhost:5173/api/student/delete-chats \
  -H "X-Student-Token: $STOKEN"
```

## 文档

```bash
# 文件树
curl -s "http://localhost:5173/api/document/tree"

# 原始文件下载（预览用）
curl -s -o /tmp/test.pdf \
  "http://localhost:5173/api/document/raw?path=$(python3 -c 'import urllib.parse;print(urllib.parse.quote("/home/takina/AiQuestionBank/data/exam-bank/计组期末2022-2023.pdf"))')"
file /tmp/test.pdf   # 应输出 PDF document

# 上传（multipart）
curl -s -X POST http://localhost:5173/api/document/upload \
  -F "file=@/path/to/你的文件.docx"

# 强制重解析
curl -s -X POST http://localhost:5173/api/document/reparse \
  -H "Content-Type: application/json" \
  -d '{"path":"/home/takina/AiQuestionBank/data/exam-bank/计组题目新.docx"}'
```

## Admin

```bash
# 登录
curl -s -X POST http://localhost:5173/api/admin/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"你的admin密码"}'

# 仪表盘统计
curl -s http://localhost:5173/api/admin/stats -H "X-Admin-Token: $ATOKEN"

# 监控状态
curl -s http://localhost:5173/api/admin/monitor/status -H "X-Admin-Token: $ATOKEN"

# 聊天记录（分页）
curl -s "http://localhost:5173/api/admin/chat-logs?page=0&size=20" -H "X-Admin-Token: $ATOKEN"

# 学生列表
curl -s "http://localhost:5173/api/admin/students" -H "X-Admin-Token: $ATOKEN"

# 审计日志
curl -s "http://localhost:5173/api/admin/logs?page=0&size=20" -H "X-Admin-Token: $ATOKEN"

# 读取配置（密码类脱敏）
curl -s http://localhost:5173/api/admin/config -H "X-Admin-Token: $ATOKEN"

# 更新配置（例：开关告警）
curl -s -X PUT http://localhost:5173/api/admin/config/alarm_enabled \
  -H "Content-Type: application/json" -H "X-Admin-Token: $ATOKEN" \
  -d '{"value":"true"}'

# 上传文件（admin）
curl -s -X POST http://localhost:5173/api/admin/files/upload \
  -H "X-Admin-Token: $ATOKEN" -F "file=@/path/to/file.pdf"

# 导出学生 CSV
curl -s "http://localhost:5173/api/admin/data/export/students" -H "X-Admin-Token: $ATOKEN"
```

## AI 服务（需 X-Api-Token）

```bash
export TOKEN="$API_SERVICE_TOKEN"

# 列出供应商（含 reasoning/multimodal 标记）
curl -s http://localhost:8003/providers | python3 -m json.tool | head -20

# 测试连接
curl -s -X POST http://localhost:8003/test-connection \
  -H "Content-Type: application/json" -H "X-Api-Token: $TOKEN" \
  -d '{"provider":"deepseek","apiKey":"sk-key","model":"deepseek-chat"}'

# 流式（最小示例）
curl -N -X POST http://localhost:8003/chat/stream \
  -H "Content-Type: application/json" -H "X-Api-Token: $TOKEN" \
  -d '{
    "messages":[{"role":"user","content":"你好"}],
    "provider":"deepseek","model":"deepseek-chat",
    "api_key":"sk-key","base_url":"https://api.deepseek.com",
    "stream":true
  }'

# 嵌入（单条）
curl -s -X POST http://localhost:8002/embed_query \
  -H "Content-Type: application/json" -H "X-Api-Token: $TOKEN" \
  -d '{"text":"总线 波特率"}' | head -c 100

# OCR 解析
curl -s -X POST http://localhost:8001/parse \
  -H "Content-Type: application/json" -H "X-Api-Token: $TOKEN" \
  -d '{"filePath":"/home/takina/AiQuestionBank/data/exam-bank/高等数学笔记.docx"}'
```

## 完整 RAG 问答（带文件 + 多模态）

```bash
# 关键词 RAG（勾选文件）
curl -N -X POST http://localhost:5173/api/chat/ask \
  -H "Content-Type: application/json" -H "X-Student-Token: $STOKEN" \
  -d '{
    "question": "帮我讲解计组题目的总线部分",
    "sessionId": "20240001_example",
    "selectedFiles": ["/home/takina/AiQuestionBank/data/exam-bank/计组题目新.docx"],
    "provider": "deepseek", "model": "deepseek-chat",
    "apiKey": "sk-key", "baseUrl": "https://api.deepseek.com",
    "maxTokens": 16384
  }'

# 多模态（看图回答，需多模态模型 key）
curl -N -X POST http://localhost:5173/api/chat/ask \
  -H "Content-Type: application/json" -H "X-Student-Token: $STOKEN" \
  -d '{
    "question": "这页题目考什么",
    "sessionId": "20240001_example2",
    "selectedFiles": ["/home/takina/AiQuestionBank/data/exam-bank/计组期末2022-2023.pdf"],
    "provider": "openrouter", "model": "google/gemini-2.5-flash",
    "apiKey": "sk-key", "baseUrl": "https://openrouter.ai/api/v1",
    "maxTokens": 16384, "multimodal": true
  }'
```

## 安全测试用例（验证防护）

```bash
# 1. 路径穿越 → 应 403
curl -s -o /dev/null -w "%{http_code}\n" \
  "http://localhost:5173/api/document/raw?path=$(python3 -c 'import urllib.parse;print(urllib.parse.quote("/etc/passwd"))')"

# 2. 越权 sessionId → 应 403
curl -s -X POST http://localhost:5173/api/chat/ask \
  -H "Content-Type: application/json" -H "X-Student-Token: $STOKEN" \
  -d '{"question":"x","sessionId":"99999999_evil","selectedFiles":[]}' | head -c 80

# 3. 无 token 调 admin → 应 401
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5173/api/admin/stats

# 4. llm-gateway 无 token → 应 401
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8003/test-connection \
  -H "Content-Type: application/json" -d '{"provider":"deepseek","apiKey":"x"}'

# 5. base_url 白名单（SSRF）→ 应 403
curl -s -X POST http://localhost:8003/chat/stream \
  -H "Content-Type: application/json" -H "X-Api-Token: $TOKEN" \
  -d '{"messages":[{"role":"user","content":"x"}],"provider":"custom","api_key":"k","base_url":"http://169.254.169.254"}' | head -c 80
```
