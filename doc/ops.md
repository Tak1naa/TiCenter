# 运维监控

## 一、监控中心（Admin → 监控）

入口：`http://<host>/admin/monitor`（需 admin 登录）

页面内容：
- **6 服务状态卡片**：chat/document/vector/ocr/embedding/llm-gateway，绿点=UP + 延迟 ms，红点=DOWN
- **失败统计**：聊天失败数 / 解析失败数 / 登录失败数（进程内存计数，重启归零）
- **最近事件**：有界环形队列（200 条），含时间/类型/详情
- 前端每 15s 轮询 `/api/admin/monitor/status`

数据来源：
- 服务探测：chat-service 用 WebClient 并发探测 5 个远端 /health（2s 超时），chat 自身算 UP
- 解析失败：查 `exam_files.parse_status='failed'`（24h 内，共享库）
- 聊天/登录失败：ChatController 错误出口 + StudentController 登录失败埋点 → MonitorStatsService

## 二、邮件告警

### 触发条件（AlarmService，每 60s 轮询）
1. **服务下线**：任一服务探测失败 → `【AI题库告警】服务下线`
2. **解析失败**：新出现 `parse_status='failed'` 的文件
3. **连续聊天失败**：聊天失败增量 ≥3 次/轮询周期

### 去重限频
- `ConcurrentHashMap<类型:目标, 最后告警时间>`，同一告警 30 分钟内只发一封
- 服务恢复后清除该 key（可再次告警）
- 注意：告警邮件发送**失败**后 30 分钟内不会重试（限频在发送前记录）

### 降级链
`alarm_enabled=false` 或无收件人 → 日志告警（`[ALARM]` 前缀）
未配 SMTP → 同样降级日志

### 配置（Admin → 设置）
`smtp_host/port/user/pass` + `alarm_emails` + `alarm_enabled`（详见 configuration.md）

## 三、日志

| 服务 | 本地路径（dev） | Docker |
|---|---|---|
| chat-service | data/logs/chat-service.log | `docker compose logs chat-service` |
| document-service | data/logs/document-service.log | 同上 |
| vector-service | data/logs/vector-service.log | 同上 |
| ocr-service | data/logs/ocr-service.log | 同上 |
| embedding-service | data/logs/embedding-service.log | 同上 |
| llm-gateway | data/logs/llm-gateway.log | 同上 |
| frontend (vite) | data/logs/frontend.log | `docker compose logs frontend` |

### 关键日志锚点
```
[chat/stream] OK content_chars=X reason_chars=Y finish=stop   ← 网关：AI 正常回复
[chat/stream] EMPTY reply ...                                 ← 空流（思考耗尽）
[chat/stream] failed (auth) ...                               ← key 无效
[ALARM] 邮件已发送: 【AI题库告警】服务下线                       ← 告警
[MAIL] 邮件已发送 (smtp|student_smtp): ...                    ← 学生邮件
[embedding] API failed: ...                                   ← embedding key 问题
Startup indexer done: N/M indexed                             ← 向量索引完成
Vector snapshot loaded: N vectors ...                          ← 快照加载（免重嵌入）
Vector snapshot saved: N vectors -> ...                        ← 快照保存
```

## 四、故障排查速查

| 症状 | 排查 |
|---|---|
| AI 不回复 / 空白气泡 | 网关日志 `[chat/stream]`；前端应显示错误事件（不再静默） |
| 回复截断 | `truncated:true` → 增大 Max Tokens（思考型 16384） |
| "模型未返回任何正文" | 思考型模型空流（EMPTY_REPLY）→ 换非思考模型或调低上下文 |
| 检索不到题库内容 | ① 勾选文件路径是否在题库根 ② embedding 是否配 key（`/health` mode）③ 向量索引是否完成 |
| 文件解析 failed | document-service 日志；OCR 服务是否存活 |
| 预览打不开 | `/api/document/raw` 可达性；前端渲染库依赖（pdfjs worker） |
| 邮件没收到 | system_configs 里 smtp_* 配置；日志 `[MAIL]` 是否显示发送失败 |
| 服务全挂 | `./scripts/start-all.sh health` 或监控中心；MySQL 是否存活（各 Java 服务依赖） |

## 五、备份建议

- **MySQL**：`mysqldump -u exam_bank -p exam_bank > backup.sql`（或定时任务）
- **题库文件**：`data/exam-bank/` 目录备份
- **敏感配置**：`.env` 与 system_configs（SMTP 授权码）单独加密保存
- 恢复：Docker 卷还原 + `docker compose up -d`（向量索引自动重建）
