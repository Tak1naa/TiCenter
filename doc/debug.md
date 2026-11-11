# 调试手册

本机调试（curl / 脚本）的完整速查。所有示例假设服务在本机（dev 端口）。

## 0. 前置：环境准备

```bash
# 服务间 token（与 start-all.sh 一致）
export API_SERVICE_TOKEN=$(openssl rand -hex 32)
export ADMIN_SEED_PASSWORD='<从本地秘密文件读取，不要写入文档或 shell 历史>'
export EMBEDDING_API_KEY='<供应商测试 key，测试后轮换>'

# 启动全部服务（或单服务）
./scripts/start-all.sh            # 全部
./scripts/start-all.sh llm        # 单个
./scripts/start-all.sh health     # 健康检查
./scripts/start-all.sh stop       # 停止
```

> Flatpak 沙箱内（VSCode 终端）：`lsof`/`fuser` 看不到 host 进程，需 `flatpak-spawn --host` 包装。

## 1. 获取认证 token

### 学生 token
```bash
# 登录（用已注册学号；测试账号 20240001 / pass1234）
STOKEN=$(curl -s -X POST http://localhost:5173/api/student/login \
  -H "Content-Type: application/json" \
  -d '{"username":"20240001","password":"pass1234"}' \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['token'])")
echo $STOKEN
```

### Admin token
```bash
ATOKEN=$(curl -s -X POST http://localhost:5173/api/admin/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"admin\",\"password\":\"$ADMIN_SEED_PASSWORD\"}" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['token'])")
```

## 2. 聊天（SSE 流式）调试

### 直接 curl 看 SSE 原始流
```bash
curl -N -X POST http://localhost:5173/api/chat/ask \
  -H "Content-Type: application/json" \
  -H "X-Student-Token: $STOKEN" \
  -d '{
    "question": "总线一帧多少位？",
    "sessionId": "20240001_dbg1",
    "selectedFiles": ["/home/takina/AiQuestionBank/data/exam-bank/计组题目新.docx"],
    "provider": "deepseek",
    "model": "deepseek-chat",
    "apiKey": "sk-你的key",
    "baseUrl": "https://api.deepseek.com",
    "maxTokens": 8192,
    "multimodal": false
  }'
```

### 关键参数
| 参数 | 说明 |
|---|---|
| `sessionId` | **必须以学号开头**（如 `20240001_xxx`），否则 403 |
| `selectedFiles` | 绝对路径数组；勾选后走关键词匹配 |
| `provider/model/apiKey/baseUrl` | 从"模型选择"复制；`maxTokens` 思考型 ≥16384 |
| `multimodal` | true 时页图转 base64 给供应商（多模态模型） |

### SSE 事件预期
```
event: reason   ← 思考（deepseek-chat 无）
event: answer   ← 正文逐字（JSON {"content":...}）
event: done     ← {"references":[...], "truncated":false}
event: error    ← {"error":"...","code":"AUTH_ERR|PROVIDER_ERR|EMPTY_REPLY"}
```

### 常见错误排查
```
401 "未登录"          → X-Student-Token 缺失/过期（重登）
403 "会话不属于当前账号" → sessionId 没用学号前缀
"Error code: 401"    → 模型 key 无效（业务错误，非系统故障）
event:error 连接被拒绝 → llm-gateway(8003) 没起
EMPTY_REPLY          → 思考模型空流，换 deepseek-chat 或增大 maxTokens
```

## 3. 服务健康与状态

```bash
# 全部服务
for p in 8001 8002 8003 39181 39182 39183; do
  echo -n "$p: "; curl -s -m 2 http://localhost:$p/health && echo
done

# embedding 模式（api / hash-fallback）
curl -s http://localhost:8002/health | python3 -m json.tool

# 向量索引状态
curl -s -X POST http://localhost:5173/api/vector/status
```

## 4. 日志实时追踪

```bash
# AI 请求（网关）
tail -f data/logs/llm-gateway.log
# 关键行: [chat/stream] OK content_chars=X reason_chars=Y finish=stop
#         [chat/stream] EMPTY reply ... / failed (auth) ...

# 后端
tail -f data/logs/chat-service.log
# 关键行: [ALARM] 邮件已发送 / [MAIL] 邮件已发送 (smtp|student_smtp)
#         Startup indexer done: N/M indexed（vector）

# OCR 解析
tail -f data/logs/ocr-service.log
# 关键行: [ocr] page N OCR failed（单页 OCR 失败，已降级不中断）
```

## 5. 文件解析调试

```bash
# 手动触发解析（force 重解析）
curl -s -X POST http://localhost:5173/api/document/scan \
  -H "Content-Type: application/json" \
  -d '{"path":"/home/takina/AiQuestionBank/data/exam-bank","forceReParse":true}'

# 查解析状态
curl -s "http://localhost:5173/api/document/status?path=/home/takina/AiQuestionBank/data/exam-bank/%E8%AE%A1%E7%BB%84%E9%A2%98%E7%9B%AE%E6%96%B0.docx"

# 直接调 OCR 解析（绕过 Java，测解析管线）
curl -s -X POST http://localhost:8001/parse \
  -H "Content-Type: application/json" \
  -H "X-Api-Token: $API_SERVICE_TOKEN" \
  -d '{"filePath":"/home/takina/AiQuestionBank/data/exam-bank/计组题目新.docx","forceReParse":false}'
```

## 6. 数据库查询

```bash
MYSQL="mysql -u exam_bank exam_bank"
$MYSQL -e "SELECT id,file_name,parse_status,total_pages FROM exam_files ORDER BY id DESC LIMIT 5;"
$MYSQL -e "SELECT COUNT(*) FROM text_chunks;"
$MYSQL -e "SELECT LEFT(content,50) FROM chat_history WHERE role='assistant' ORDER BY id DESC LIMIT 3;"
$MYSQL -e "SELECT config_key,LEFT(config_value,6) FROM system_configs;"
```

## 7. 向量索引调试

```bash
# 重建索引（手动）
curl -s -X POST http://localhost:5173/api/vector/index-all

# 验证语义检索：query 向量 → search
python3 - <<'EOF'
import json, urllib.request
TOKEN = "$API_SERVICE_TOKEN"
def embed(text):
    req = urllib.request.Request("http://localhost:8002/embed_query",
        data=json.dumps({"text": text}).encode(),
        headers={"Content-Type":"application/json","X-Api-Token":TOKEN})
    return json.loads(urllib.request.urlopen(req, timeout=20).read())["embeddings"][0]
v = embed("总线 异步串行传输 波特率")
req = urllib.request.Request("http://localhost:39182/api/vector/search",
    data=json.dumps({"vector": v, "topK": 3}).encode(),
    headers={"Content-Type":"application/json"})
for h in json.loads(urllib.request.urlopen(req, timeout=20).read())["hits"]:
    print(f"{h['score']:.3f} | {h['content'][:40]}")
EOF
```

## 8. 邮件调试

```bash
# 触发找回密码（发邮件）
curl -s -X POST http://localhost:5173/api/student/forgot-password \
  -H "Content-Type: application/json" -d '{"email":"t@t.com"}'

# 从 DB 取 token 模拟点链接
MYSQL_PWD="$DB_PASSWORD" flatpak-spawn --host mysql -u exam_bank exam_bank \
  -N -e "SELECT reset_token FROM students WHERE email='t@t.com' AND reset_token IS NOT NULL LIMIT 1;"

# 手动触发告警：停掉一个服务，等 60s 轮询
./scripts/start-all.sh stop   # 全停
# 观察 chat-service 日志 [ALARM]
```

## 9. 多模态调试

```bash
# 验证网关 base64 代抓（图 URL 转 data:image）
curl -s -X POST http://localhost:8003/chat/stream \
  -H "Content-Type: application/json" -H "X-Api-Token: $API_SERVICE_TOKEN" \
  -d '{
    "messages": [{"role":"user","content":[
      {"type":"text","text":"看图"},
      {"type":"image_url","image_url":{"url":"http://localhost:8001/render-page?filePath=/home/takina/AiQuestionBank/data/exam-bank/计组期末2022-2023.pdf&page=1"}}
    ]}],
    "provider":"openrouter","model":"google/gemini-2.5-flash",
    "api_key":"sk-key","base_url":"https://openrouter.ai/api/v1",
    "stream":true
  }'
```

## 10. 常见坑速查

| 坑 | 原因 |
|---|---|
| `401 未登录` 调 chat/ask | 忘了 X-Student-Token；token 30 分钟过期 |
| `000` curl 超时 | 服务没起 / 防火墙；检查 health |
| SSE 内容错乱 | 老版本后端（answer 未 JSON 包裹）——重启最新 jar |
| `NoClassDefFoundError: Aggregate$1` | H2 版本问题——本项目已用 MySQL，确认 datasource url |
| 前端白屏控制台报 worker 错 | pdfjs worker 路径——dev 用 `?url`，build 由 Vite 处理 |
| 上传 400 | 文件名含非法字符（净化白名单）或超 100MB |
| embedding 相似度全接近 0 | 没配 `EMBEDDING_API_KEY`（hash 降级）——`/health` 看 mode |
