# 部署指南

## 一、Docker 部署（PVE 推荐）

### 前置
- PVE 上安装 Docker + Docker Compose
- 项目代码拷至服务器（不含 `data/`、`target/`、`node_modules/`）

### 步骤

```bash
# 1. 准备 .env（从模板复制并填写）
cp .env.example .env
# 必须填：API_SERVICE_TOKEN、ADMIN_SEED_PASSWORD、CONFIG_ENCRYPTION_KEY、QDRANT_API_KEY
# 生成配置加密主密钥：openssl rand -base64 32
# 可选填：DB_PASSWORD、DEEPSEEK_API_KEY、EMBEDDING_API_KEY（硅基流动）

# 2. 准备题库数据目录（挂载用）
mkdir -p data/exam-bank
# 把题库文件拷入 data/exam-bank/

# 3. 启动
docker compose up -d --build

# 4. 验证
docker compose ps          # 8 个服务应全部 running
curl http://localhost/health   # nginx 前端
curl http://localhost:39183/health   # chat-service
```

### 服务清单（compose）

| 服务 | 镜像 | 暴露端口 |
|---|---|---|
| mysql | mysql:8.4（官方） | —（内网） |
| ocr-service | 自建 | 8001 |
| embedding-service | 自建 | 8002 |
| llm-gateway | 自建 | 8003 |
| document-service | 自建 | 39181 |
| vector-service | 自建 | 39182 |
| chat-service | 自建 | 39183 |
| frontend | 自建（nginx） | 80 |

### 数据持久化
- **MySQL**：命名卷 `mysql-data`（首次启动执行 `scripts/init-db.sql`，含 students/admin_users/audit_logs/system_configs）
- **题库文件**：`./data/exam-bank` 挂载到各服务（只读给 OCR/chat，可读写给 document）
- **向量快照**：命名卷 `vector-data`（`/data/vector-snapshot.bin`，重启免全量重嵌入；删除快照文件即触发重建）

### 环境变量注入（compose 已配置）
- 服务间 token → 所有服务
- `DB_PASSWORD` / `SPRING_DATASOURCE_URL`（指向 mysql 容器名）→ Java 服务
- `APP_OCR/EMBEDDING/LLM/DOCUMENT/VECTOR_SERVICE_URL` → chat-service（容器名互连）
- `APP_EXAMBANKROOTPATH` → chat-service 的题库根路径
- `EMBEDDING_API_KEY/BASE_URL/MODEL` → embedding-service（默认硅基流动 bge-m3）

### 更新部署
```bash
git pull（或重新拷代码）
docker compose up -d --build
```

### 常见问题
| 现象 | 处理 |
|---|---|
| chat-service 起不来 | 看 `docker compose logs chat-service`；多半是 `API_SERVICE_TOKEN` 未设或 MySQL 未健康 |
| 前端 502 | nginx 反代的服务未起，`docker compose ps` 确认 |
| embedding 检索无效 | 未配 `EMBEDDING_API_KEY`（走 hash 降级），日志有 `[embedding] API failed` |
| OCR 解析慢 | PaddleOCR 首次加载模型慢，属正常；LibreOffice 转换 WPS 文件也耗时；容器内 `SOFFICE_BIN` 默认 soffice（compose 已配），本地 Flatpak 环境用 flatpak-spawn 包装 |

## 二、本地开发环境

### 前置依赖
- Java 25（sdkman）、Maven 3.9、Node 22（nvm）、Python 3.12 + pip
- MySQL 8.4（本机）——建库：
  ```sql
  CREATE DATABASE exam_bank DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
  CREATE USER 'exam_bank'@'localhost' IDENTIFIED BY '<从秘密管理器生成的随机密码>';
  GRANT ALL ON exam_bank.* TO 'exam_bank'@'localhost';
  ```

### Python 服务依赖
```bash
pip install -r ai-services/ocr-service/requirements.txt
pip install -r ai-services/embedding-service/requirements.txt
pip install -r ai-services/llm-gateway/requirements.txt
# OCR 额外需要系统级：libreoffice（Office fallback）、libgomp1（PaddleOCR）
```

### 前端依赖
```bash
cd frontend && npm install
# 注意：react-pdf-viewer 要求 pdfjs-dist ^3.x（package.json 已锁 overrides）
```

### 启动（脚本）
```bash
# 生成 token（必填）
export API_SERVICE_TOKEN=$(openssl rand -hex 32)
export ADMIN_SEED_PASSWORD=你的初始admin密码
export EMBEDDING_API_KEY=硅基流动key   # 语义检索

./scripts/start-all.sh          # 全启动（构建+6服务+前端）
./scripts/start-all.sh health   # 只看健康状态
./scripts/start-all.sh stop     # 全部停止
./scripts/start-all.sh llm      # 只起 llm-gateway（单服务调试）
```

> 注意：start-all.sh 用 `lsof` 杀端口（Flatpak 沙箱内需 `flatpak-spawn --host` 包装）。

### 端口占用排查
```bash
lsof -ti:39183          # 查占用
fuser 39183/tcp         # 备选
```

### 前端开发
- `cd frontend && npm run dev`（vite，HMR）
- vite proxy 已配置：`/api/chat|student|admin` → 39183，`/api/document` → 39181，`/api/vector` → 39182，`/api/llm` → 8003，`/api/render-page|count-pages` → 8001
- **生产构建**：`npm run build`（dist 由 nginx 托管，见 Dockerfile）

### 可选：MinerU 高质量解析（profile `mineru`）

默认不启用（走 MarkItDown→LibreOffice→PaddleOCR 原链）。对扫描件/公式/表格多的题库建议启用：

```bash
# 1. .env 中设置
#    MINERU_URL=http://mineru:8000
# 2. 构建并启动（CPU 版镜像；首次解析时自动经 ModelScope 下载模型，数 GB）
docker compose --profile mineru up -d --build mineru
# 3. 重启 ocr-service 使配置生效
docker compose restart ocr-service
```

启用后 PDF/图片优先走 MinerU（公式→LaTeX、表格→HTML，逐页还原），失败自动降级原链。
验证：`docker compose exec ocr-service python -c "import os; print(os.environ.get('MINERU_URL'))"`，
或看 ocr 日志中 `[mineru]` 前缀。注意：CPU 上大 PDF 解析较慢（分钟级），`MINERU_TIMEOUT` 默认 900s。
