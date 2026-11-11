#!/bin/bash
# AI Exam Bank - One-click start script (dev 本地模式)
# Docker 部署见 docker-compose.yml
# 前置: export API_SERVICE_TOKEN=xxx  (服务间鉴权)
#       export ADMIN_SEED_PASSWORD=xxx
#       export EMBEDDING_API_KEY=sk-xxx  (硅基流动)
#       export DEEPSEEK_API_KEY=sk-xxx  (可选，网关兜底)

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
LOG_DIR="$PROJECT_DIR/data/logs"
mkdir -p "$LOG_DIR"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
log() { echo -e "${GREEN}[$(date '+%H:%M:%S')]${NC} $1"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; }

# ── 校验必需环境变量 ──
if [ -z "$API_SERVICE_TOKEN" ]; then
    error "API_SERVICE_TOKEN 未设置。生成: export API_SERVICE_TOKEN=\$(openssl rand -hex 32)"
    exit 1
fi
if [ -z "$ADMIN_SEED_PASSWORD" ]; then
    warn "ADMIN_SEED_PASSWORD 未设置（仅首次建 admin 需要）"
fi
if [ -z "$EMBEDDING_API_KEY" ]; then
    warn "EMBEDDING_API_KEY 未设置，embedding 将降级为 hash 伪随机（语义检索无效）"
fi

# Source env
export NVM_DIR="${NVM_DIR:-$HOME/.var/app/com.visualstudio.code/config/nvm}"
[ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"

# ── 杀端口占用（host 模式）──
kill_port() {
    local port=$1
    local pid=$(lsof -ti:$port 2>/dev/null || true)
    if [ -n "$pid" ]; then kill -9 $pid 2>/dev/null || true; log "Killed process on port $port"; fi
}

# ── Python 服务 ──
start_python() {
    local name=$1 dir=$2 port=$3
    log "Starting $name (port $port)..."
    cd "$PROJECT_DIR/ai-services/$dir"
    local EXTRA_ENV=()
    if [ "$name" = "OCR Service" ]; then
        # 本机 Flatpak 沙箱需包装 soffice；临时目录放项目 data 下（Docker 由 compose 注入）
        EXTRA_ENV=(SOFFICE_BIN="${SOFFICE_BIN:-flatpak-spawn --host soffice}"
                   LO_TMP_DIR="${LO_TMP_DIR:-$PROJECT_DIR/data/.lo-tmp}")
    fi
    API_SERVICE_TOKEN="$API_SERVICE_TOKEN" \
    DEEPSEEK_API_KEY="$EMBEDDING_API_KEY" \
    DEEPSEEK_BASE_URL="${EMBEDDING_BASE_URL:-https://api.siliconflow.cn/v1}" \
    EMBED_MODEL="${EMBED_MODEL:-BAAI/bge-m3}" \
    "${EXTRA_ENV[@]}" \
        python3 main.py > "$LOG_DIR/$dir.log" 2>&1 &
    sleep 3
}

# ── Java 服务（先构建）──
start_java() {
    local name=$1 jar=$2 port=$3
    log "Starting $name (port $port)..."
    cd "$PROJECT_DIR/backend"
    API_SERVICE_TOKEN="$API_SERVICE_TOKEN" \
    ADMIN_SEED_PASSWORD="$ADMIN_SEED_PASSWORD" \
        java -jar "$jar" --spring.profiles.active=dev > "$LOG_DIR/$name.log" 2>&1 &
    sleep 5
}

start_all() {
    # 构建
    log "Building backend (Maven)..."
    cd "$PROJECT_DIR/backend"
    mvn clean package -DskipTests -q 2>&1 | tail -3 || true

    # Python
    start_python "OCR Service" ocr-service 8001
    start_python "Embedding Service" embedding-service 8002
    start_python "LLM Gateway" llm-gateway 8003

    # Java
    start_java "document-service" "document-service/target/document-service-*.jar" 39181
    start_java "vector-service" "vector-service/target/vector-service-*.jar" 39182
    start_java "chat-service" "chat-service/target/chat-service-*.jar" 39183

    # 前端
    log "Starting Frontend (port 5173)..."
    cd "$PROJECT_DIR/frontend"
    kill_port 5173
    npm run dev > "$LOG_DIR/frontend.log" 2>&1 &
    sleep 3
}

# ── 健康检查 ──
check_health() {
    echo ""
    log "服务健康检查:"
    for entry in "8001:OCR" "8002:Embedding" "8003:LLM Gateway" "39181:Document" "39182:Vector" "39183:Chat"; do
        port="${entry%%:*}"; name="${entry##*:}"
        if curl -s -m 3 "http://localhost:$port/health" > /dev/null 2>&1; then
            echo -e "  ${GREEN}✓${NC} $name ($port)"
        else
            echo -e "  ${RED}✗${NC} $name ($port)"
        fi
    done
}

case "${1:-all}" in
    ocr) start_python "OCR Service" ocr-service 8001 ;;
    embedding) start_python "Embedding Service" embedding-service 8002 ;;
    llm) start_python "LLM Gateway" llm-gateway 8003 ;;
    backend) start_java "document-service" "document-service/target/document-service-*.jar" 39181; start_java "vector-service" "vector-service/target/vector-service-*.jar" 39182; start_java "chat-service" "chat-service/target/chat-service-*.jar" 39183 ;;
    frontend) cd "$PROJECT_DIR/frontend"; kill_port 5173; npm run dev > "$LOG_DIR/frontend.log" 2>&1 & ;;
    health) check_health ;;
    stop)
        for port in 5173 8001 8002 8003 39181 39182 39183; do kill_port $port; done
        log "All stopped"
        ;;
    all|*)
        start_all
        check_health
        echo ""
        echo -e "  Frontend: ${BLUE}http://localhost:5173${NC}"
        echo -e "  Admin:    ${BLUE}http://localhost:5173/admin${NC}"
        ;;
esac
