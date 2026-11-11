#!/bin/bash
# ============================================================
# AI Exam Bank — End-to-End Test Script
# 一键验证所有组件是否正常工作
#
# Usage: bash scripts/test-all.sh
# ============================================================
set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

PASS=0
FAIL=0
WARN=0

log_section()  { echo -e "\n${BLUE}━━━ $1 ━━━${NC}"; }
log_pass()     { echo -e "  ${GREEN}[PASS]${NC} $1"; PASS=$((PASS + 1)); }
log_fail()     { echo -e "  ${RED}[FAIL]${NC} $1"; FAIL=$((FAIL + 1)); }
log_warn()     { echo -e "  ${YELLOW}[WARN]${NC} $1"; WARN=$((WARN + 1)); }
log_info()     { echo -e "  [INFO] $1"; }

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
export SDKMAN_DIR="$HOME/.sdkman"
export NVM_DIR="${NVM_DIR:-$HOME/.var/app/com.visualstudio.code/config/nvm}"
[ -s "$SDKMAN_DIR/bin/sdkman-init.sh" ] && source "$SDKMAN_DIR/bin/sdkman-init.sh"
[ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"

echo ""
echo -e "${BLUE}╔══════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║   AI Exam Bank — E2E Verification Suite     ║${NC}"
echo -e "${BLUE}╚══════════════════════════════════════════════╝${NC}"

# ============================================
# 0. Service token
# ============================================
if [ -z "$API_SERVICE_TOKEN" ]; then
    log_warn "API_SERVICE_TOKEN 未设置，生成临时 token 用于本脚本测试（不会写入 .env）"
    export API_SERVICE_TOKEN=$(openssl rand -hex 32)
fi

# ============================================
# 1. Environment Check
# ============================================
log_section "1. Environment Check"

java --version >/dev/null 2>&1 && log_pass "Java: $(java --version 2>&1 | head -1)" || log_fail "Java not found"
node --version >/dev/null 2>&1 && log_pass "Node.js: $(node --version)" || log_fail "Node.js not found"
python3 --version >/dev/null 2>&1 && log_pass "Python: $(python3 --version 2>&1)" || log_fail "Python not found"
mvn --version >/dev/null 2>&1 && log_pass "Maven: $(mvn --version 2>&1 | head -1)" || log_fail "Maven not found"

# Check test data
[ -f "$PROJECT_DIR/data/exam-bank/计组题目新.docx" ] && log_pass "Test data exists" || log_warn "Test data missing"

# Check DeepSeek API key
[ -n "$DEEPSEEK_API_KEY" ] && log_pass "DEEPSEEK_API_KEY is set" || log_warn "DEEPSEEK_API_KEY not set (chat/study won't work)"

# ============================================
# 2. Backend Build Check
# ============================================
log_section "2. Backend Build & Install"

cd "$PROJECT_DIR/backend"
mvn compile -q 2>&1 && log_pass "Backend compiles" || log_fail "Backend compilation failed"
mvn install -DskipTests -q 2>&1 && log_pass "Backend installed to local Maven repo" || {
    log_warn "Backend install failed, integration tests may not work"
}

# ============================================
# 3. Backend Integration Test (dev profile H2)
# ============================================
log_section "3. Backend Integration Test (H2, no middleware)"

mvn -pl document-service test -Dspring.profiles.active=dev 2>&1 | tail -25
INTEGRATION_OK=${PIPESTATUS[0]}
if [ $INTEGRATION_OK -eq 0 ]; then
    log_pass "Integration tests pass"
else
    log_fail "Integration tests failed (see above)"
fi

# ============================================
# 4. Python OCR Service Smoke Test
# ============================================
log_section "4. OCR Service Test"

# Try to import key modules
python3 -c "import fitz; print('PyMuPDF:', fitz.version)" 2>/dev/null && \
    log_pass "PyMuPDF available" || log_warn "PyMuPDF not available (OCR Layer 1 won't work)"

python3 -c "from paddleocr import PaddleOCR; print('PaddleOCR OK')" 2>/dev/null && \
    log_pass "PaddleOCR available" || log_warn "PaddleOCR not available (OCR Layer 3 won't work)"

python3 -c "import markitdown; print('markitdown OK')" 2>/dev/null && \
    log_pass "markitdown available (OCR Layer 1)" || log_warn "markitdown not available (解析主引擎缺失)"

# Test OCR with a real file
if python3 -c "import fitz" 2>/dev/null; then
    python3 "$PROJECT_DIR/ai-services/ocr-service/main.py" &
    OCR_PID=$!
    sleep 2

    if curl -s http://localhost:8001/health | grep -q '"status":"ok"'; then
        log_pass "OCR service responds on port 8001"

        # Test parsing a real file
        TEST_FILE="$PROJECT_DIR/data/exam-bank/计组题目新.docx"
        PARSE_RESULT=$(curl -s -X POST http://localhost:8001/parse \
            -H "Content-Type: application/json" \
            -H "X-Api-Token: $API_SERVICE_TOKEN" \
            -d "{\"filePath\": \"$TEST_FILE\"}")

        if echo "$PARSE_RESULT" | grep -q '"parseMethod"'; then
            log_pass "OCR service parses TXT file successfully"
            METHOD=$(echo "$PARSE_RESULT" | python3 -c "import sys,json; print(json.load(sys.stdin)['parseMethod'])")
            PAGES=$(echo "$PARSE_RESULT" | python3 -c "import sys,json; print(json.load(sys.stdin)['totalPages'])")
            log_info "  Parse method: $METHOD, Pages: $PAGES"
        else
            log_fail "OCR service failed to parse file"
        fi
    else
        log_fail "OCR service health check failed"
    fi

    kill $OCR_PID 2>/dev/null || true
fi

# ============================================
# 5. Python Embedding Service Test
# ============================================
log_section "5. Embedding Service Test"

python3 -c "from openai import OpenAI; print('openai SDK OK')" 2>/dev/null && \
    log_pass "openai SDK available (embedding API 客户端)" || log_warn "openai SDK not available (embedding 服务无法启动)"

# ============================================
# 6. Python LLM Gateway Test
# ============================================
log_section "6. LLM Gateway (DeepSeek) Test"

if [ -n "$DEEPSEEK_API_KEY" ]; then
    python3 "$PROJECT_DIR/ai-services/llm-gateway/main.py" &
    LLM_PID=$!
    sleep 2

    if curl -s http://localhost:8003/health | grep -q '"default_provider"'; then
        log_pass "LLM Gateway responds, provider registry loaded"

        # Test streaming chat (SSE) with service token
        CHAT_RESULT=$(curl -sN -X POST http://localhost:8003/chat/stream \
            -H "Content-Type: application/json" \
            -H "X-Api-Token: $API_SERVICE_TOKEN" \
            -d '{"messages":[{"role":"user","content":"Say hello in one word"}],"max_tokens":20,"stream":true}')
        if echo "$CHAT_RESULT" | grep -q 'event: delta' || echo "$CHAT_RESULT" | grep -q '"content"'; then
            log_pass "LLM gateway SSE chat works"
        else
            log_fail "LLM gateway SSE returned unexpected: $(echo $CHAT_RESULT | head -c 300)"
        fi
    else
        log_fail "LLM Gateway health check failed"
    fi

    kill $LLM_PID 2>/dev/null || true
else
    log_warn "Skipped — set DEEPSEEK_API_KEY to test"
fi

# ============================================
# 7. Frontend Check
# ============================================
log_section "7. Frontend Build & Dev Server"

cd "$PROJECT_DIR/frontend"
npm run build 2>&1 | tail -1 && log_pass "Frontend compiles (TypeScript + Vite)" || log_fail "Frontend build failed"

# Try starting dev server
fuser -k 5173/tcp 2>/dev/null || true
nohup npx vite --host 0.0.0.0 > /tmp/vite-test.log 2>&1 &
VITE_PID=$!
sleep 3

if curl -s -o /dev/null -w "%{http_code}" http://localhost:5173 | grep -q "200"; then
    log_pass "Frontend dev server running on http://localhost:5173"
else
    log_fail "Frontend dev server not responding"
fi

kill $VITE_PID 2>/dev/null || true

# ============================================
# 8. Security Checks
# ============================================
log_section "8. Security Checks"

# Verify CSP header in built frontend
if grep -q "Content-Security-Policy" "$PROJECT_DIR/frontend/index.html"; then
    log_pass "CSP header present in index.html"
else
    log_warn "CSP header missing"
fi

# Check no hardcoded secrets
if grep -rn "sk-[a-zA-Z0-9]\{20,\}" "$PROJECT_DIR/frontend/src/" 2>/dev/null; then
    log_fail "Hardcoded API key found in frontend!"
else
    log_pass "No hardcoded keys in frontend source"
fi

# Check XSS filters exist
grep -q "sanitizeText\|moderateContent" "$PROJECT_DIR/frontend/src/lib/security.ts" 2>/dev/null && \
    log_pass "Frontend XSS/content filters present" || log_warn "Frontend security module missing"

# ============================================
# Summary
# ============================================
echo ""
echo -e "${BLUE}╔══════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║               Test Summary                   ║${NC}"
echo -e "${BLUE}╚══════════════════════════════════════════════╝${NC}"
echo -e "  ${GREEN}Passed: $PASS${NC}"
echo -e "  ${RED}Failed: $FAIL${NC}"
echo -e "  ${YELLOW}Warnings: $WARN${NC}"
echo ""

if [ $FAIL -gt 0 ]; then
    echo -e "${RED}Some tests FAILED. See details above.${NC}"
    exit 1
elif [ $WARN -gt 0 ]; then
    echo -e "${YELLOW}All critical tests passed with $WARN warnings.${NC}"
    echo ""
    echo "To enable full functionality:"
    [ -z "$DEEPSEEK_API_KEY" ] && echo "  • Set DEEPSEEK_API_KEY for chat/study features"
    echo "  • Install MySQL/Redis/RabbitMQ for production deployment"
    echo "  • Install PaddleOCR for image PDF support"
    echo ""
    echo "Dev mode (no middleware needed):"
    echo "  cd backend && mvn -pl document-service spring-boot:run -Dspring-boot.run.profiles=dev"
    exit 0
else
    echo -e "${GREEN}All tests passed!${NC}"
    exit 0
fi
