"""
LLM Gateway — Multi-provider AI proxy with SSE streaming.

Supports 50+ preset providers (OpenAI-compatible + Anthropic + Gemini).
POST /chat/stream  —  stream chat completion via SSE
GET  /providers     —  list all preset providers
POST /test-connection — validate API key + model
GET  /health        —  health check
"""
import json
import os
import random
import time
import threading
from typing import Optional

from fastapi import FastAPI, HTTPException, Request, Depends
from fastapi.middleware.cors import CORSMiddleware
from openai import OpenAI
from pydantic import BaseModel
from sse_starlette.sse import EventSourceResponse

# ── Provider Registry ──────────────────────────────────────────────

class ProviderPreset:
    def __init__(self, id: str, name: str, api_type: str,
                 base_url: str, default_model: str, models: list[str],
                 region: str = "", reasoning_models: list[str] | None = None,
                 multimodal_models: list[str] | None = None):
        self.id = id
        self.name = name
        self.api_type = api_type        # "openai" | "anthropic" | "gemini"
        self.base_url = base_url
        self.default_model = default_model
        self.models = models
        self.region = region
        self.reasoning_models = reasoning_models or []
        self.multimodal_models = multimodal_models or []

    def to_dict(self):
        return {
            "id": self.id,
            "name": self.name,
            "api_type": self.api_type,
            "default_base_url": self.base_url,
            "default_model": self.default_model,
            "known_models": self.models,
            "region": self.region,
            "reasoning_models": self.reasoning_models,
            "multimodal_models": self.multimodal_models,
        }

# ~50 providers across OpenAI-compatible, Anthropic, and Gemini
PROVIDERS: dict[str, ProviderPreset] = {
    # ── OpenAI-compatible proxy providers (~45) ──
    "deepseek":     ProviderPreset("deepseek", "DeepSeek", "openai",
                    "https://api.deepseek.com", "deepseek-v4-pro",
                    ["deepseek-v4-pro", "deepseek-v4-flash"],
                    reasoning_models=["deepseek-v4-flash"]),
    "siliconflow":  ProviderPreset("siliconflow", "SiliconFlow", "openai",
                    "https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V4-Pro",
                    ["deepseek-ai/DeepSeek-V4-Pro", "deepseek-ai/DeepSeek-V4-Flash",
                     "deepseek-ai/DeepSeek-V3", "deepseek-ai/DeepSeek-R1",
                     "Qwen/Qwen3-235B-Instruct", "meta-llama/Llama-4-70B-Instruct"],
                    reasoning_models=["deepseek-ai/DeepSeek-V4-Flash", "deepseek-ai/DeepSeek-R1"]),
    "siliconflow_en": ProviderPreset("siliconflow_en", "SiliconFlow EN", "openai",
                    "https://api.siliconflow.cn/v1", "meta-llama/Llama-4-70B-Instruct",
                    ["meta-llama/Llama-4-70B-Instruct", "deepseek-ai/DeepSeek-V4-Pro"]),
    "openrouter":   ProviderPreset("openrouter", "OpenRouter", "openai",
                    "https://openrouter.ai/api/v1", "openai/gpt-4o",
                    ["openai/gpt-4o", "anthropic/claude-sonnet-4",
                     "google/gemini-2.5-flash", "deepseek/deepseek-v4-pro",
                     "meta-llama/llama-4-70b-instruct"],
                    multimodal_models=["openai/gpt-4o", "google/gemini-2.5-flash",
                                      "meta-llama/llama-4-70b-instruct"]),
    "the_router":   ProviderPreset("the_router", "TheRouter", "openai",
                    "https://api.therouter.com/v1", "openai/gpt-4o",
                    ["openai/gpt-4o", "claude-sonnet-4"]),
    "kimi":         ProviderPreset("kimi", "Kimi", "openai",
                    "https://api.moonshot.cn/v1", "kimi-k3",
                    ["kimi-k3", "kimi-k2.7-code", "kimi-k2"],
                    reasoning_models=["kimi-k3"]),
    "kimi_coding":  ProviderPreset("kimi_coding", "Kimi For Coding", "openai",
                    "https://api.moonshot.cn/v1", "kimi-k2.7-code",
                    ["kimi-k2.7-code", "kimi-k3"]),
    "zhipu":        ProviderPreset("zhipu", "Zhipu GLM", "openai",
                    "https://open.bigmodel.cn/api/paas/v4", "glm-4.7",
                    ["glm-4.7", "glm-4.6", "glm-4.6v-flash", "glm-4.5-flash", "glm-4.5"],
                    reasoning_models=["glm-4.7", "glm-4.6"],
                    multimodal_models=["glm-4.6v-flash"]),
    "zhipu_en":     ProviderPreset("zhipu_en", "Zhipu GLM EN", "openai",
                    "https://open.bigmodel.cn/api/paas/v4", "glm-4.7",
                    ["glm-4.7", "glm-4.6", "glm-4.6v-flash"],
                    reasoning_models=["glm-4.7", "glm-4.6"],
                    multimodal_models=["glm-4.6v-flash"]),
    "baidu_qianfan": ProviderPreset("baidu_qianfan", "Baidu Qianfan Coding Plan", "openai",
                    "https://qianfan.baidubce.com/v2", "ernie-4.5-turbo-128k",
                    ["ernie-4.5-turbo-128k", "ernie-speed-pro-128k", "ernie-4.0-turbo-128k"]),
    "bailian":      ProviderPreset("bailian", "Bailian", "openai",
                    "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3-plus",
                    ["qwen3-plus", "qwen3-max", "qwen3-turbo", "qwen3-235b-instruct"]),
    "bailian_coding": ProviderPreset("bailian_coding", "Bailian For Coding", "openai",
                    "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3-plus",
                    ["qwen3-plus", "qwen3-max"]),
    "doubao":       ProviderPreset("doubao", "DouBaoSeed", "openai",
                    "https://ark.cn-beijing.volces.com/api/v3", "doubao-seed-1.6-thinking",
                    ["doubao-seed-1.6-thinking", "doubao-seed-1.6-flash", "doubao-seed-1.6"],
                    reasoning_models=["doubao-seed-1.6-thinking"]),
    "byteplus":     ProviderPreset("byteplus", "BytePlus", "openai",
                    "https://ark.cn-beijing.volces.com/api/v3", "doubao-seed-1.6",
                    ["doubao-seed-1.6", "doubao-seed-1.6-flash"]),
    "volc_agentplan": ProviderPreset("volc_agentplan", "火山Agentplan", "openai",
                    "https://ark.cn-beijing.volces.com/api/v3", "doubao-seed-1.6-thinking",
                    ["doubao-seed-1.6-thinking"]),
    "stepfun":      ProviderPreset("stepfun", "StepFun", "openai",
                    "https://api.stepfun.com/v1", "step-3.7-flash",
                    ["step-3.7-flash", "step-3.7", "step-3"]),
    "stepfun_en":   ProviderPreset("stepfun_en", "StepFun EN", "openai",
                    "https://api.stepfun.com/v1", "step-3.7-flash",
                    ["step-3.7-flash"]),
    "modelscope":   ProviderPreset("modelscope", "ModelScope", "openai",
                    "https://api-inference.modelscope.cn/v1", "Qwen/Qwen3-235B-Instruct",
                    ["Qwen/Qwen3-235B-Instruct", "deepseek-ai/DeepSeek-V4-Pro"]),
    "minimax":      ProviderPreset("minimax", "MiniMax", "openai",
                    "https://api.minimax.chat/v1", "abab7-chat",
                    ["abab7-chat", "abab6.5s-chat"]),
    "minimax_en":   ProviderPreset("minimax_en", "MiniMax EN", "openai",
                    "https://api.minimax.chat/v1", "abab7-chat",
                    ["abab7-chat"]),
    "bailing":      ProviderPreset("bailing", "BaiLing", "openai",
                    "https://api.bailing.ai/v1", "bailing-1.0",
                    ["bailing-1.0"]),
    "aihubmix":     ProviderPreset("aihubmix", "AiHubMix", "openai",
                    "https://aihubmix.com/v1", "gpt-4o",
                    ["gpt-4o", "claude-sonnet-4", "gemini-2.5-pro", "deepseek-v4-pro"]),
    "dmxapi":       ProviderPreset("dmxapi", "DMXAPI", "openai",
                    "https://api.dmxapi.com/v1", "gpt-4o",
                    ["gpt-4o", "claude-sonnet-4"]),
    "novita":       ProviderPreset("novita", "Novita AI", "openai",
                    "https://api.novita.ai/v3/openai", "meta-llama/llama-4-70b-instruct",
                    ["meta-llama/llama-4-70b-instruct", "deepseek-ai/DeepSeek-V4-Pro"]),
    "github_copilot": ProviderPreset("github_copilot", "GitHub Copilot", "openai",
                    "https://api.githubcopilot.com/v1", "gpt-4o",
                    ["gpt-4o", "claude-sonnet-4"]),
    "codex":        ProviderPreset("codex", "Codex", "openai",
                    "https://api.codex.ai/v1", "gpt-4o",
                    ["gpt-4o"]),
    "lemon_data":   ProviderPreset("lemon_data", "LemonData", "openai",
                    "https://api.lemondata.com/v1", "gpt-4o",
                    ["gpt-4o"]),
    "nvidia":       ProviderPreset("nvidia", "Nvidia", "openai",
                    "https://integrate.api.nvidia.com/v1", "nvidia/llama-4-nemotron-70b-instruct",
                    ["nvidia/llama-4-nemotron-70b-instruct", "meta/llama-4-70b-instruct"]),
    "pipellm":      ProviderPreset("pipellm", "PIPELLM", "openai",
                    "https://api.pipellm.com/v1", "gpt-4o",
                    ["gpt-4o"]),
    "mimo":         ProviderPreset("mimo", "Xiaomi MiMo", "openai",
                    "https://api.mimo.xiaomi.com/v1", "mimo-1.0",
                    ["mimo-1.0"]),
    "aws_bedrock_aksk": ProviderPreset("aws_bedrock_aksk", "AWS Bedrock (AKSK)", "openai",
                    "https://bedrock-runtime.us-east-1.amazonaws.com", "claude-sonnet-4",
                    ["claude-sonnet-4", "claude-haiku-4.5", "llama-4-70b"]),
    "aws_bedrock_apikey": ProviderPreset("aws_bedrock_apikey", "AWS Bedrock (API Key)", "openai",
                    "https://bedrock-runtime.us-east-1.amazonaws.com", "claude-sonnet-4",
                    ["claude-sonnet-4"]),
    "kat_coder":    ProviderPreset("kat_coder", "KAT-Coder", "openai",
                    "https://api.katcoder.com/v1", "kat-coder-v1",
                    ["kat-coder-v1"]),
    "longcat":      ProviderPreset("longcat", "Longcat", "openai",
                    "https://api.longcat.ai/v1", "longcat-flash",
                    ["longcat-flash"]),
    "packycode":    ProviderPreset("packycode", "PackyCode", "openai",
                    "https://api.packycode.com/v1", "gpt-4o",
                    ["gpt-4o", "claude-sonnet-4"]),
    "runapi":       ProviderPreset("runapi", "RunAPI", "openai",
                    "https://api.runapi.com/v1", "gpt-4o",
                    ["gpt-4o"]),
    "relaxycode":   ProviderPreset("relaxycode", "RelaxyCode", "openai",
                    "https://api.relaxycode.com/v1", "gpt-4o",
                    ["gpt-4o"]),
    "cubence":      ProviderPreset("cubence", "Cubence", "openai",
                    "https://api.cubence.com/v1", "gpt-4o",
                    ["gpt-4o"]),
    "aigocode":     ProviderPreset("aigocode", "AIGoCode", "openai",
                    "https://api.aigocode.com/v1", "gpt-4o",
                    ["gpt-4o"]),
    "rightcode":    ProviderPreset("rightcode", "RightCode", "openai",
                    "https://api.rightcode.com/v1", "gpt-4o",
                    ["gpt-4o"]),
    "aicodemirror": ProviderPreset("aicodemirror", "AICodeMirror", "openai",
                    "https://api.aicodemirror.com/v1", "gpt-4o",
                    ["gpt-4o"]),
    "aicoding":     ProviderPreset("aicoding", "AICoding", "openai",
                    "https://api.aicoding.com/v1", "gpt-4o",
                    ["gpt-4o"]),
    "crazyrouter":  ProviderPreset("crazyrouter", "CrazyRouter", "openai",
                    "https://api.crazyrouter.com/v1", "gpt-4o",
                    ["gpt-4o"]),
    "sssaicode":    ProviderPreset("sssaicode", "SSSAiCode", "openai",
                    "https://api.sssaicode.com/v1", "gpt-4o",
                    ["gpt-4o"]),
    "youyun":       ProviderPreset("youyun", "优云智算", "openai",
                    "https://api.youyun.com/v1", "deepseek-ai/DeepSeek-V4-Pro",
                    ["deepseek-ai/DeepSeek-V4-Pro"]),
    "youyun_coding": ProviderPreset("youyun_coding", "优云智算 Coding Plan", "openai",
                    "https://api.youyun.com/v1", "deepseek-ai/DeepSeek-V4-Pro",
                    ["deepseek-ai/DeepSeek-V4-Pro"]),
    "micu":         ProviderPreset("micu", "Micu", "openai",
                    "https://api.micu.com/v1", "gpt-4o",
                    ["gpt-4o"]),
    "ctok":         ProviderPreset("ctok", "CTok.ai", "openai",
                    "https://api.ctok.ai/v1", "gpt-4o",
                    ["gpt-4o"]),
    "eflowcode":    ProviderPreset("eflowcode", "E-FlowCode", "openai",
                    "https://api.eflowcode.com/v1", "gpt-4o",
                    ["gpt-4o"]),
    "lionccapi":    ProviderPreset("lionccapi", "LionCCAPI", "openai",
                    "https://api.lionccapi.com/v1", "gpt-4o",
                    ["gpt-4o"]),
    "claudeapi":    ProviderPreset("claudeapi", "ClaudeAPI", "openai",
                    "https://api.claudeapi.com/v1", "claude-sonnet-4",
                    ["claude-sonnet-4"]),
    "claudecn":     ProviderPreset("claudecn", "ClaudeCN", "openai",
                    "https://api.claudecn.com/v1", "claude-sonnet-4",
                    ["claude-sonnet-4"]),
    "shengshanyun": ProviderPreset("shengshanyun", "胜算云", "openai",
                    "https://api.shengshanyun.com/v1", "gpt-4o",
                    ["gpt-4o"]),
    "patewayai":    ProviderPreset("patewayai", "PatewayAI", "openai",
                    "https://api.patewayai.com/v1", "gpt-4o",
                    ["gpt-4o"]),

    # ── Native Anthropic ──
    "claude_official": ProviderPreset("claude_official", "Claude Official", "anthropic",
                    "https://api.anthropic.com", "claude-sonnet-4-20250514",
                    ["claude-sonnet-4-20250514", "claude-opus-4-20250514",
                     "claude-haiku-4-5-20251001"]),

    # ── Native Gemini ──
    "gemini":       ProviderPreset("gemini", "Gemini Native", "gemini",
                    "https://generativelanguage.googleapis.com", "gemini-2.5-flash",
                    ["gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.5-flash-lite"],
                    multimodal_models=["gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.5-flash-lite"]),
}

# ── App ────────────────────────────────────────────────────────────

app = FastAPI(title="LLM Gateway", version="2.0.0")

# CORS：仅允许前端开发服务器（本机），不携带凭据
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173", "http://127.0.0.1:5173"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ── 安全：共享 token 鉴权 + base_url 白名单 + 限流 ──

import secrets as _secrets
import urllib.parse as _urlparse
import socket as _socket
import ipaddress as _ipaddress
from collections import deque as _deque
import time as _time

API_SERVICE_TOKEN = os.environ.get("API_SERVICE_TOKEN", "")
if not API_SERVICE_TOKEN:
    print("[security] WARNING: API_SERVICE_TOKEN 未设置，服务将拒绝所有请求", flush=True)

# 预设 provider 允许的域名白名单（base_url 只能精确命中这些）
_ALLOWED_BASE_HOSTS = set()
for _p in PROVIDERS.values():
    if _p.base_url:
        _ALLOWED_BASE_HOSTS.add(_urlparse.urlparse(_p.base_url).netloc)

# 简单滑动窗口限流：IP -> deque of timestamps
_RATE_WINDOW = 60.0
_RATE_MAX = 60
_rate_map: dict[str, _deque] = {}
ALLOW_CUSTOM_PROVIDER_DOMAINS = os.environ.get("ALLOW_CUSTOM_PROVIDER_DOMAINS", "false").lower() == "true"
ALLOW_PROXY_FAKE_IP_DNS = os.environ.get("ALLOW_PROXY_FAKE_IP_DNS", "false").lower() == "true"

def _check_rate_limit(client_ip: str):
    now = _time.monotonic()
    dq = _rate_map.setdefault(client_ip, _deque())
    while dq and now - dq[0] > _RATE_WINDOW:
        dq.popleft()
    if len(dq) >= _RATE_MAX:
        return False
    dq.append(now)
    # 定期清理空条目
    if len(_rate_map) > 1000:
        for k in list(_rate_map):
            if not _rate_map[k]:
                del _rate_map[k]
    return True

def require_api_token(request: Request):
    """共享 token 鉴权（X-Api-Token 头）。未配置 token 时拒绝一切。"""
    if not API_SERVICE_TOKEN:
        raise HTTPException(status_code=503, detail="服务未配置 API_SERVICE_TOKEN")
    auth = request.headers.get("X-Api-Token", "")
    if not _secrets.compare_digest(auth, API_SERVICE_TOKEN):
        raise HTTPException(status_code=401, detail="无效的服务 token")
    client_ip = request.client.host if request.client else "unknown"
    if not _check_rate_limit(client_ip):
        raise HTTPException(status_code=429, detail="请求过于频繁")

def _assert_base_url(base: str, provider: str):
    """base_url 校验：
    - 预设供应商：严格域名白名单（防 SSRF + 自定义 host 转发 key）
    - 自定义供应商（不在 PROVIDERS 中）：允许任意公网 https 域名，但禁止
      IP 字面量 / 私网段 / localhost / 云元数据地址（保留 SSRF 防护）。
    """
    if not base:
        return
    parsed = _urlparse.urlparse(base)
    if parsed.scheme != "https":
        raise HTTPException(status_code=403, detail="base_url 必须为 https")
    host = (parsed.hostname or "").lower()
    if parsed.username or parsed.password or (parsed.port not in (None, 443)):
        raise HTTPException(status_code=403, detail="base_url 不允许凭据或非 443 端口")
    if provider not in PROVIDERS and not ALLOW_CUSTOM_PROVIDER_DOMAINS:
        raise HTTPException(status_code=403, detail="生产环境未启用自定义供应商域名")
    if provider in PROVIDERS:
        if host != _urlparse.urlparse(PROVIDERS[provider].base_url).hostname:
            raise HTTPException(status_code=403, detail="base_url 不在允许的供应商域名白名单内")
        if ALLOW_PROXY_FAKE_IP_DNS and _resolves_only_to_proxy_fake_ips(host):
            return
    _assert_public_dns(host)
    return


def _assert_public_dns(host: str):
    if not host or host == "localhost" or host.endswith(".local"):
        raise HTTPException(status_code=403, detail="base_url 不允许本机域名")
    try:
        literal = _ipaddress.ip_address(host)
        addresses = [literal]
    except ValueError:
        try:
            addresses = [_ipaddress.ip_address(item[4][0])
                         for item in _socket.getaddrinfo(host, 443, type=_socket.SOCK_STREAM)]
        except Exception:
            raise HTTPException(status_code=403, detail="base_url 域名无法安全解析")
    if not addresses or any(not address.is_global for address in addresses):
        raise HTTPException(status_code=403, detail="base_url 解析到私网、保留或本机地址")


def _resolves_only_to_proxy_fake_ips(host: str) -> bool:
    """Development-only support for proxy fake-IP DNS; never enabled by default."""
    fake_range = _ipaddress.ip_network("198.18.0.0/15")
    try:
        addresses = [_ipaddress.ip_address(item[4][0])
                     for item in _socket.getaddrinfo(host, 443, type=_socket.SOCK_STREAM)]
    except Exception:
        return False
    return bool(addresses) and all(address in fake_range for address in addresses)


def _assert_resolved_base(base: str, provider: str):
    _assert_base_url(base, provider)

class Message(BaseModel):
    role: str
    content: str | list = ""  # 多模态：list of {type:text/image_url}

class ProviderRequest(BaseModel):
    messages: list[Message]
    provider: str = "deepseek"
    model: str = "deepseek-chat"
    api_key: str = ""
    base_url: str = ""
    temperature: float = 0.3
    max_tokens: int = 2048
    stream: bool = True
    prompt_version: str = ""
    cache_key: str = ""

class TestRequest(BaseModel):
    provider: str = ""
    apiKey: str = ""
    baseUrl: str = ""
    model: str = ""


_prefix_seen: dict[str, float] = {}
_prefix_lock = threading.Lock()


def _estimate_tokens(messages: list[Message]) -> int:
    """Cheap language-aware estimate for budgets/telemetry; never presented as provider billing usage."""
    han = 0
    other = 0
    images = 0
    for message in messages:
        parts = [message.content] if isinstance(message.content, str) else message.content
        for part in parts:
            if isinstance(part, dict) and part.get("type") == "image_url":
                images += 1
                continue
            text = part if isinstance(part, str) else str(part.get("text", "")) if isinstance(part, dict) else ""
            text_han = sum(1 for char in text if "\u3400" <= char <= "\u9fff")
            han += text_han
            other += len(text) - text_han
    return han + (other + 3) // 4 + images * 800 + len(messages) * 4


def _mark_prefix_candidate(req: ProviderRequest) -> bool:
    if not req.cache_key:
        return False
    now = time.time()
    key = f"{req.provider}|{req.model}|{req.prompt_version}|{req.cache_key}"
    with _prefix_lock:
        previous = _prefix_seen.get(key)
        _prefix_seen[key] = now
        if len(_prefix_seen) > 10_000:
            expired = [item for item, seen in _prefix_seen.items() if now - seen > 3600]
            for item in expired:
                _prefix_seen.pop(item, None)
            while len(_prefix_seen) > 10_000:
                _prefix_seen.pop(next(iter(_prefix_seen)))
    return previous is not None and now - previous <= 3600


def _get_preset(provider_id: str) -> Optional[ProviderPreset]:
    return PROVIDERS.get(provider_id)

def _validate_multimodal(req: ProviderRequest):
    has_images = any(isinstance(message.content, list) and any(
        isinstance(part, dict) and part.get("type") == "image_url" for part in message.content)
        for message in req.messages)
    preset = PROVIDERS.get(req.provider)
    if has_images and preset is not None and preset.multimodal_models:
        if not any(req.model.lower() == model.lower() for model in preset.multimodal_models):
            raise HTTPException(status_code=400, detail="所选模型未声明图像输入能力")

def _stream_openai_once(req: ProviderRequest, key: str, base: str, stats: dict):
    """单次上游调用：yield delta 事件；统计与 reasoning 文本写入 stats dict（避免 yield 非事件对象）。"""
    import httpx
    http_client = httpx.Client(follow_redirects=False, timeout=60.0)
    client = OpenAI(api_key=key, base_url=base, timeout=60.0, http_client=http_client)
    create_args = dict(model=req.model, messages=[m.model_dump() for m in req.messages],
                       temperature=req.temperature, max_tokens=req.max_tokens, stream=True)
    usage_capable = {"deepseek", "siliconflow", "siliconflow_en", "zhipu", "zhipu_en", "openrouter"}
    if req.provider in usage_capable:
        create_args["stream_options"] = {"include_usage": True}
    try:
        response = client.chat.completions.create(**create_args)
    except Exception as exc:
        message = str(exc).lower()
        if "stream_options" not in create_args or not any(word in message for word in ("stream_options", "unsupported", "unknown parameter")):
            raise
        create_args.pop("stream_options", None)
        response = client.chat.completions.create(**create_args)
    first_chunk = True
    stats["finish_reason"] = None
    for chunk in response:
        usage = getattr(chunk, "usage", None)
        if usage is not None:
            stats["input_tokens"] = int(getattr(usage, "prompt_tokens", 0) or 0)
            stats["output_tokens"] = int(getattr(usage, "completion_tokens", 0) or 0)
            details = getattr(usage, "prompt_tokens_details", None)
            stats["cached_tokens"] = int((details.get("cached_tokens", 0) if isinstance(details, dict)
                                           else getattr(details, "cached_tokens", 0)) or 0)
        if first_chunk:
            # 调试（LLM_DEBUG=1 才打印）：首个 chunk 结构，确认正文/思考字段
            if os.environ.get("LLM_DEBUG"):
                try:
                    c0 = chunk.choices[0].delta if chunk.choices else None
                    keys = c0.__dict__.keys() if c0 is not None else []
                    print(f"[chat/stream] first delta keys={list(keys)} model={getattr(chunk, 'model', '?')}", flush=True)
                except Exception:
                    pass
            first_chunk = False
        if chunk.choices:
            fr = chunk.choices[0].finish_reason
            if fr:
                stats["finish_reason"] = fr
        delta = chunk.choices[0].delta if chunk.choices else None
        if not delta:
            continue
        if delta.content:
            stats["content_chars"] += len(delta.content)
            yield {"event": "delta", "data": json.dumps({"content": delta.content})}
        # 思考过程实时下发（reasoning_content / thinking 双字段防御）
        reason = getattr(delta, "reasoning_content", None) or getattr(delta, "thinking", None)
        if reason:
            stats["reason_chars"] += len(reason)
            stats["reasoning_parts"].append(reason)
            yield {"event": "reason", "data": json.dumps({"content": reason})}


import urllib.request as _urllib_request
from urllib.error import HTTPError as _HTTPError

def _localize_image_urls(messages: list):
    """Fetch only the internal OCR renderer with strict page and byte budgets."""
    from urllib.parse import urlparse
    allowed_hosts = {"localhost", "127.0.0.1", "ocr-service"}
    allowed_hosts.update(h.strip() for h in os.environ.get("INTERNAL_IMAGE_HOSTS", "").split(",") if h.strip())
    class _NoRedirect(_urllib_request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None
    opener = _urllib_request.build_opener(_NoRedirect)
    page_count = 0
    requested_count = 0
    total_bytes = 0
    for m in messages:
        if not isinstance(m.content, list):
            continue
        for part in m.content:
            if not isinstance(part, dict) or part.get("type") != "image_url":
                continue
            requested_count += 1
            url = (part.get("image_url") or {}).get("url", "")
            if not url:
                raise HTTPException(status_code=400, detail="图片输入不能为空")
            if url.startswith("data:"):
                try:
                    import base64 as _b64
                    header, encoded = url.split(",", 1)
                    if not header.startswith("data:image/"):
                        raise ValueError("unsupported data URL")
                    size = len(_b64.b64decode(encoded, validate=True))
                except Exception as exc:
                    raise HTTPException(status_code=400, detail="图片 data URL 无效") from exc
                if page_count >= 6 or size > 6 * 1024 * 1024 or total_bytes + size > 20 * 1024 * 1024:
                    raise HTTPException(status_code=413, detail="图片页数或大小超过限制")
                page_count += 1
                total_bytes += size
                continue
            parsed = urlparse(url)
            if (parsed.scheme != "http" or parsed.hostname not in allowed_hosts
                    or parsed.username or parsed.password or parsed.path != "/render-page"
                    or (parsed.port is not None and parsed.port != 8001) or page_count >= 6):
                raise HTTPException(status_code=400, detail="图片地址不在允许的内部渲染范围")
            try:
                req = _urllib_request.Request(url)
                if API_SERVICE_TOKEN:
                    req.add_header("X-Api-Token", API_SERVICE_TOKEN)
                with opener.open(req, timeout=15) as resp:
                    data = resp.read(6 * 1024 * 1024 + 1)
                if len(data) > 6 * 1024 * 1024 or total_bytes + len(data) > 20 * 1024 * 1024:
                    raise HTTPException(status_code=413, detail="图片页数或大小超过限制")
                total_bytes += len(data)
                page_count += 1
                import base64 as _b64
                part["image_url"]["url"] = "data:image/png;base64," + _b64.b64encode(data).decode()
            except HTTPException:
                raise
            except Exception as e:
                print(f"[chat/stream] image fetch failed: {url[:80]} - {str(e)[:100]}", flush=True)
                raise HTTPException(status_code=422, detail="命中页图片读取失败") from e
    if requested_count and page_count == 0:
        raise HTTPException(status_code=400, detail="没有有效图片可发送")

def _stream_anthropic_once(req: ProviderRequest, key: str, base: str, stats: dict):
    """Anthropic Messages API 原生流式（SSE：content_block_delta / message_delta）。"""
    url = base.rstrip("/") + "/v1/messages"
    system = "\n".join(m.content for m in req.messages
                        if m.role == "system" and isinstance(m.content, str)) or None
    msgs = []
    for m in req.messages:
        if m.role == "system":
            continue
        if isinstance(m.content, str):
            msgs.append({"role": m.role, "content": m.content})
        else:
            blocks = []
            for part in m.content:
                if not isinstance(part, dict):
                    continue
                if part.get("type") == "text":
                    blocks.append({"type": "text", "text": part.get("text", "")})
                elif part.get("type") == "image_url":
                    u = (part.get("image_url") or {}).get("url", "")
                    if u.startswith("data:image/png;base64,"):
                        blocks.append({"type": "image", "source": {
                            "type": "base64", "media_type": "image/png",
                            "data": u.split(",", 1)[1]}})
            if blocks:
                msgs.append({"role": m.role, "content": blocks})
    body = {"model": req.model, "max_tokens": req.max_tokens,
            "stream": True, "messages": msgs}
    if system:
        body["system"] = system
    if req.temperature is not None:
        body["temperature"] = req.temperature
    data = json.dumps(body).encode("utf-8")
    r = _urllib_request.Request(url, data=data, method="POST", headers={
        "content-type": "application/json",
        "x-api-key": key,
        "anthropic-version": "2023-06-01",
    })
    try:
        resp = _urllib_request.urlopen(r, timeout=120)
    except _HTTPError as e:
        raise RuntimeError(f"anthropic HTTP {e.code}: {e.read(200).decode('utf-8', 'replace')}") from e
    with resp:
        for raw in resp:
            line = raw.decode("utf-8", "replace").rstrip("\n")
            if not line.startswith("data:"):
                continue
            payload = line[5:].strip()
            if not payload or payload == "[DONE]":
                continue
            try:
                ev = json.loads(payload)
            except Exception:
                continue
            etype = ev.get("type")
            if etype == "content_block_delta":
                d = ev.get("delta") or {}
                if d.get("type") == "text_delta" and d.get("text"):
                    stats["content_chars"] += len(d["text"])
                    yield {"event": "delta", "data": json.dumps({"content": d["text"]})}
                elif d.get("type") == "thinking_delta" and d.get("thinking"):
                    stats["reason_chars"] += len(d["thinking"])
                    stats["reasoning_parts"].append(d["thinking"])
                    yield {"event": "reason", "data": json.dumps({"content": d["thinking"]})}
            elif etype == "message_delta":
                stop = (ev.get("delta") or {}).get("stop_reason")
                if stop:
                    stats["finish_reason"] = stop


def _stream_anthropic(req: ProviderRequest):
    _assert_base_url(req.base_url, req.provider)
    key, base = _resolve_key_base(req, "https://api.anthropic.com")
    _assert_resolved_base(base, req.provider)
    _validate_multimodal(req)
    _localize_image_urls(req.messages)
    _log_request(req, "request" if req.api_key else "env", base)
    yield from _stream_with_retry(_stream_anthropic_once, req, key, base, "anthropic")


def _stream_gemini_once(req: ProviderRequest, key: str, base: str, stats: dict):
    """Gemini API 原生流式（streamGenerateContent，alt=sse）。"""
    url = base.rstrip("/") + f"/v1beta/models/{req.model}:streamGenerateContent?alt=sse"
    system_text = "\n".join(m.content for m in req.messages
                             if m.role == "system" and isinstance(m.content, str)) or None
    contents = []
    for m in req.messages:
        if m.role == "system":
            continue
        role = "model" if m.role == "assistant" else "user"
        if isinstance(m.content, str):
            contents.append({"role": role, "parts": [{"text": m.content}]})
        else:
            parts = []
            for part in m.content:
                if not isinstance(part, dict):
                    continue
                if part.get("type") == "text":
                    parts.append({"text": part.get("text", "")})
                elif part.get("type") == "image_url":
                    u = (part.get("image_url") or {}).get("url", "")
                    if u.startswith("data:image/"):
                        mime = u[5:u.find(";")] if ";" in u else "image/png"
                        parts.append({"inline_data": {"mime_type": mime,
                                                      "data": u.split(",", 1)[1]}})
            if parts:
                contents.append({"role": role, "parts": parts})
    body = {"contents": contents,
            "generationConfig": {"temperature": req.temperature,
                                 "maxOutputTokens": req.max_tokens}}
    if system_text:
        body["systemInstruction"] = {"parts": [{"text": system_text}]}
    data = json.dumps(body).encode("utf-8")
    r = _urllib_request.Request(url, data=data, method="POST", headers={
        "content-type": "application/json",
        "x-goog-api-key": key,
    })
    try:
        resp = _urllib_request.urlopen(r, timeout=120)
    except _HTTPError as e:
        raise RuntimeError(f"gemini HTTP {e.code}: {e.read(200).decode('utf-8', 'replace')}") from e
    with resp:
        for raw in resp:
            line = raw.decode("utf-8", "replace").rstrip("\n")
            if not line.startswith("data:"):
                continue
            payload = line[5:].strip()
            if not payload:
                continue
            try:
                ev = json.loads(payload)
            except Exception:
                continue
            cands = ev.get("candidates") or []
            if not cands:
                continue
            fr = cands[0].get("finishReason")
            if fr:
                stats["finish_reason"] = fr
            for part in ((cands[0].get("content") or {}).get("parts") or []):
                if not isinstance(part, dict):
                    continue
                text = part.get("text")
                if not text:
                    continue
                if part.get("thought"):
                    stats["reason_chars"] += len(text)
                    stats["reasoning_parts"].append(text)
                    yield {"event": "reason", "data": json.dumps({"content": text})}
                else:
                    stats["content_chars"] += len(text)
                    yield {"event": "delta", "data": json.dumps({"content": text})}


def _stream_gemini(req: ProviderRequest):
    _assert_base_url(req.base_url, req.provider)
    key, base = _resolve_key_base(req, "https://generativelanguage.googleapis.com")
    _assert_resolved_base(base, req.provider)
    _validate_multimodal(req)
    _localize_image_urls(req.messages)
    _log_request(req, "request" if req.api_key else "env", base)
    yield from _stream_with_retry(_stream_gemini_once, req, key, base, "gemini")


def _is_truncated(finish_reason) -> bool:
    """各家 finish_reason 命名不同：openai=length / anthropic=max_tokens / gemini=MAX_TOKENS"""
    return finish_reason in ("length", "max_tokens", "MAX_TOKENS")


def _stream_with_retry(once, req: ProviderRequest, key: str, base: str, label: str):
    """统一的重试/空流/错误包装；429 遵守 Retry-After，最多尝试三次。
    once(req, key, base, stats) 为同步生成器，向 stats 写统计、yield SSE 事件。
    """
    input_tokens_estimate = _estimate_tokens(req.messages)
    prefix_cache_candidate = _mark_prefix_candidate(req)
    for attempt in (1, 2, 3):
        stats = {"content_chars": 0, "reason_chars": 0, "reasoning_parts": [], "finish_reason": None,
                 "input_tokens": 0, "output_tokens": 0, "cached_tokens": 0}
        try:
            for item in once(req, key, base, stats):
                yield item
            # 上游流正常结束但没有任何正文：思考型模型常见（reasoning 占满或被 max_tokens 截断）
            if stats["content_chars"] == 0:
                truncated = _is_truncated(stats["finish_reason"])
                print(f"[chat/stream] EMPTY reply attempt={attempt} reason_chars={stats['reason_chars']} finish={stats['finish_reason']}, retrying...", flush=True)
                if attempt == 3:
                    # 二次仍空：done+empty（reason 已实时发出，不再拼正文避免重复显示）
                    yield {"event": "done", "data": json.dumps({
                        "status": "completed", "empty": True,
                        "truncated": truncated,
                        "reason_chars": stats["reason_chars"],
                        "input_tokens_estimate": input_tokens_estimate,
                        "output_tokens_estimate": 0,
                        "usage_source": "estimate",
                        "provider_cached_tokens": 0,
                        "prefix_cache_candidate": prefix_cache_candidate,
                    })}
                    return
                continue
            truncated = _is_truncated(stats["finish_reason"])
            print(f"[chat/stream] OK content_chars={stats['content_chars']} reason_chars={stats['reason_chars']} finish={stats['finish_reason']}", flush=True)
            output_tokens_estimate = max(1, (stats["content_chars"] + stats["reason_chars"] + 2) // 3)
            print(f"[prompt] version={req.prompt_version or '-'} input_est={input_tokens_estimate} output_est={output_tokens_estimate} prefix_candidate={prefix_cache_candidate}", flush=True)
            yield {"event": "done", "data": json.dumps({
                "status": "completed", "truncated": truncated,
                "input_tokens_estimate": input_tokens_estimate,
                "output_tokens_estimate": output_tokens_estimate,
                "input_tokens": stats["input_tokens"],
                "output_tokens": stats["output_tokens"],
                "provider_cached_tokens": stats["cached_tokens"],
                "usage_source": "provider" if stats["input_tokens"] else "estimate",
                "prefix_cache_candidate": prefix_cache_candidate,
            })}
            return
        except HTTPException:
            raise
        except Exception as e:
            error_msg = str(e)
            code = getattr(e, "status_code", None) or getattr(e, "code", None)
            # 认证类错误重试无意义，直接放弃（openai SDK 报 "Error code: 401"；urllib 抛 HTTPError.code）
            if "Error code: 401" in error_msg or "Error code: 403" in error_msg or code in (401, 403):
                print(f"[chat/stream] failed (auth): {error_msg[:200]}, giving up", flush=True)
                yield {"event": "error", "data": json.dumps({"error": "上游认证失败", "code": "AUTH_ERR"})}
                return
            # 已经输出过内容再重试会导致前端收到重复内容，直接报错
            if stats.get("content_chars", 0) > 0:
                print(f"[chat/stream] failed after {stats['content_chars']} chars: {error_msg[:200]}, giving up", flush=True)
                yield {"event": "error", "data": json.dumps({"error": "上游流在响应中断开", "code": "PROVIDER_ERR"})}
                return
            if code == 429 and attempt < 3:
                retry_after = 1.0
                response = getattr(e, "response", None)
                try:
                    retry_after = float(response.headers.get("Retry-After", "1"))
                except (AttributeError, TypeError, ValueError):
                    pass
                retry_after = min(max(retry_after, 0.25), 30.0) + random.uniform(0.05, 0.35)
                print(f"[chat/stream] upstream capacity attempt={attempt}, retrying after {retry_after:.2f}s", flush=True)
                time.sleep(retry_after)
                continue
            if attempt < 3:
                print(f"[chat/stream] attempt={attempt} failed: {error_msg[:200]}, retrying...", flush=True)
                continue
            print(f"[chat/stream] attempt={attempt} failed: {error_msg[:200]}, giving up", flush=True)
            public_code = "BLOCKED_UPSTREAM_CAPACITY" if code == 429 else "PROVIDER_ERR"
            public_error = "上游容量持续受限，请稍后重试" if code == 429 else "上游模型调用失败"
            yield {"event": "error", "data": json.dumps({"error": public_error, "code": public_code})}
            return

    # 三次都空流
    yield {"event": "error", "data": json.dumps({
        "error": "模型未返回任何正文内容（可能是思考过程占满了输出长度）。请增大 Max Tokens 或换用非思考模型。",
        "code": "EMPTY_REPLY",
    })}


def _resolve_key_base(req: ProviderRequest, default_base: str) -> tuple[str, str]:
    """key 回退收紧：仅预设 provider 允许 env key 兜底；否则无 key 直接 401。"""
    preset = PROVIDERS.get(req.provider)
    env_name = {"zhipu": "ZHIPU_API_KEY", "zhipu_en": "ZHIPU_API_KEY",
                "siliconflow": "SILICONFLOW_API_KEY", "siliconflow_en": "SILICONFLOW_API_KEY",
                "deepseek": "DEEPSEEK_API_KEY"}.get(req.provider)
    env_key = os.environ.get(env_name, "") if env_name else ""
    if req.provider in ("zhipu", "zhipu_en"):
        if not env_key:
            raise HTTPException(status_code=401, detail="缺少 ZHIPU_API_KEY 环境变量")
        return env_key, preset.base_url
    if not req.api_key and not env_key:
        raise HTTPException(status_code=401, detail=f"缺少 {env_name or 'provider'} 环境变量/API key")
    key = req.api_key or env_key
    base = preset.base_url if preset else (req.base_url or default_base)
    return key, base


def _log_request(req: ProviderRequest, key_src: str, base: str):
    msg_roles = ",".join(m.role for m in req.messages)
    img_count = 0
    for m in req.messages:
        if isinstance(m.content, list):
            img_count += sum(1 for p in m.content if isinstance(p, dict) and p.get("type") == "image_url")
    print(f"[chat/stream] provider={req.provider} model={req.model} key_src={key_src} base={base} max_tokens={req.max_tokens} msgs={len(req.messages)} roles={msg_roles} images={img_count}", flush=True)


def _stream_openai(req: ProviderRequest):
    # base_url 校验（防 SSRF + 自定义 host 转发 key）
    _assert_base_url(req.base_url, req.provider)
    key, base = _resolve_key_base(req, "https://api.deepseek.com")
    _assert_resolved_base(base, req.provider)
    # 多模态图片代抓：localhost 图转 base64（云端部署供应商取不到本机图）
    _validate_multimodal(req)
    _localize_image_urls(req.messages)
    _log_request(req, "request" if req.api_key else "env", base)
    yield from _stream_with_retry(_stream_openai_once, req, key, base, "openai")


@app.post("/chat/stream")
async def chat_stream(req: ProviderRequest, _: None = Depends(require_api_token)):
    api_type = "openai"
    if req.provider and req.provider in PROVIDERS:
        api_type = PROVIDERS[req.provider].api_type

    if api_type == "openai":
        return EventSourceResponse(_stream_openai(req))
    if api_type == "anthropic":
        return EventSourceResponse(_stream_anthropic(req))
    if api_type == "gemini":
        return EventSourceResponse(_stream_gemini(req))
    raise HTTPException(status_code=501, detail=f"Provider type '{api_type}' is not yet supported for streaming.")


@app.get("/providers")
def list_providers(_: None = Depends(require_api_token)):
    return [p.to_dict() for p in PROVIDERS.values()]


@app.post("/test-connection")
def test_connection(req: TestRequest, _: None = Depends(require_api_token)):
    """Test if the provider connection works by making a quick non-streaming call."""
    _assert_base_url(req.baseUrl, req.provider)
    provider_id = req.provider
    preset = PROVIDERS.get(provider_id)
    api_type = preset.api_type if preset else "openai"
    env_name = {"zhipu": "ZHIPU_API_KEY", "zhipu_en": "ZHIPU_API_KEY",
                "siliconflow": "SILICONFLOW_API_KEY", "siliconflow_en": "SILICONFLOW_API_KEY",
                "deepseek": "DEEPSEEK_API_KEY"}.get(provider_id)
    env_key = os.environ.get(env_name, "") if env_name else ""
    if provider_id in ("zhipu", "zhipu_en") and not env_key:
        return {"ok": False, "error": "缺少 ZHIPU_API_KEY 环境变量", "latencyMs": 0}
    if not req.apiKey and not env_key:
        return {"ok": False, "error": "缺少 API key", "latencyMs": 0}
    key = env_key if provider_id in ("zhipu", "zhipu_en") else (req.apiKey or env_key)
    base = preset.base_url if preset else (req.baseUrl or "https://api.deepseek.com")
    _assert_resolved_base(base, req.provider)
    model = req.model or (preset.default_model if preset else "deepseek-chat")

    if not key:
        return {"ok": False, "error": "API Key is required", "latencyMs": 0}

    if api_type in ("openai",):
        try:
            start = time.time()
            import httpx
            client = OpenAI(api_key=key, base_url=base, timeout=10.0,
                            http_client=httpx.Client(follow_redirects=False, timeout=10.0))
            client.chat.completions.create(
                model=model,
                messages=[{"role": "user", "content": "hi"}],
                max_tokens=5,
                stream=False,
            )
            latency = int((time.time() - start) * 1000)
            return {"ok": True, "latencyMs": latency}
        except Exception as e:
            return {"ok": False, "error": str(e)[:200], "latencyMs": 0}

    if api_type == "anthropic":
        try:
            start = time.time()
            import urllib.request as _urllib_request
            from urllib.error import HTTPError as _HTTPError
            body = json.dumps({"model": model, "max_tokens": 5,
                               "messages": [{"role": "user", "content": "hi"}]}).encode("utf-8")
            r = _urllib_request.Request(base.rstrip("/") + "/v1/messages", data=body, method="POST", headers={
                "content-type": "application/json", "x-api-key": key,
                "anthropic-version": "2023-06-01"})
            try:
                with _urllib_request.urlopen(r, timeout=10) as resp:
                    resp.read()
            except _HTTPError as e:
                return {"ok": False, "error": e.read(200).decode("utf-8", "replace")[:200], "latencyMs": 0}
            latency = int((time.time() - start) * 1000)
            return {"ok": True, "latencyMs": latency}
        except Exception as e:
            return {"ok": False, "error": str(e)[:200], "latencyMs": 0}

    if api_type == "gemini":
        try:
            start = time.time()
            import urllib.request as _urllib_request
            from urllib.error import HTTPError as _HTTPError
            url = base.rstrip("/") + f"/v1beta/models/{model}:generateContent"
            body = json.dumps({"contents": [{"role": "user", "parts": [{"text": "hi"}]}],
                               "generationConfig": {"maxOutputTokens": 5}}).encode("utf-8")
            r = _urllib_request.Request(url, data=body, method="POST", headers={
                "content-type": "application/json", "x-goog-api-key": key})
            try:
                with _urllib_request.urlopen(r, timeout=10) as resp:
                    resp.read()
            except _HTTPError as e:
                return {"ok": False, "error": e.read(200).decode("utf-8", "replace")[:200], "latencyMs": 0}
            latency = int((time.time() - start) * 1000)
            return {"ok": True, "latencyMs": latency}
        except Exception as e:
            return {"ok": False, "error": str(e)[:200], "latencyMs": 0}

    return {"ok": False, "error": f"Provider '{api_type}' test not supported yet", "latencyMs": 0}


@app.get("/health")
def health():
    return {
        "status": "ok",
        "providers": len(PROVIDERS),
        "version": "2.0.0",
        "default_provider": "deepseek",
    }


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8003)
