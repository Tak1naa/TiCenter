# AI 智能题库 — 项目文档

基于题库内容的 RAG 智能备考平台（BS 架构）。学生端三栏交互问答，独立 Admin 管理后台，支持多模态模型直接读图、语义检索、AI 供应商多路切换。

## 文档索引

| 文档 | 内容 |
|---|---|
| [架构设计](architecture.md) | 系统拓扑、服务划分、数据流、RAG 链路 |
| [部署指南](deployment.md) | Docker（PVE）部署 + 本地开发环境 |
| [API 参考](api.md) | 全部接口：学生/聊天/Admin/文档/健康 |
| [接口示例](api-examples.md) | curl 直接复制的完整示例 + 安全测试用例 |
| [调试手册](debug.md) | 日志锚点、SSE 调试、常见坑、数据库/索引排查 |
| [配置说明](configuration.md) | 环境变量 + Admin 设置（SystemConfig） |
| [安全设计](security.md) | 认证鉴权、漏洞防护、数据安全 |
| [运维监控](ops.md) | 监控中心、邮件告警、日志、故障排查 |
| [用户手册](user-guide.md) | 学生端 / Admin 端操作说明 |

## 技术栈

- **前端**：React 19 + TypeScript + Vite（5173），react-markdown + KaTeX、react-pdf-viewer / docx-preview / exceljs 文件预览
- **后端**：Java 25 + Spring Boot 3.5 模块化单体（默认 `core-app`，保留 chat/document/vector 边界），MySQL 8.4、Redis、Qdrant
- **AI 服务**：Python FastAPI（ocr-service / embedding-service / llm-gateway），56 个预设 AI 供应商（OpenAI 兼容/Anthropic/Gemini）

## 快速启动

```bash
# 本地开发（需先 export API_SERVICE_TOKEN 等，见部署指南）
./scripts/start-all.sh
# 访问 http://localhost:5173（学生端）/ http://localhost:5173/admin（管理端）

# Docker 部署（PVE）
cp .env.example .env && docker compose up -d --build
# 访问 http://<host>/（nginx 80 端口）
```

生产升级、RAG 评测、容量与回退流程见 [production-upgrade.md](production-upgrade.md)。
