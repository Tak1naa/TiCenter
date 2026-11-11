"""Call the upstream Xiaomi MCP task; preprocessing/prompts/postprocessing stay upstream."""
from __future__ import annotations
import base64
import json
import os
from pathlib import Path
import subprocess
import threading

_slots = threading.BoundedSemaphore(max(1, int(os.environ.get('XIAOMI_OCR_JOBS', '1'))))

def task(name: str, arguments: dict) -> str:
    root = Path(os.environ.get('XIAOMI_OCR_REPO', '/opt/Xiaomi-OCR-0'))
    script = root / 'skills/xiaomi-ocr/mcp_ocr_server.py'
    python = os.environ.get('XIAOMI_OCR_PYTHON', str(root / 'skills/xiaomi-ocr/.venv/bin/python'))
    if not script.is_file() or not Path(python).is_file():
        raise RuntimeError('Xiaomi MCP is not configured: set XIAOMI_OCR_REPO and XIAOMI_OCR_PYTHON')
    env = {k: v for k, v in os.environ.items() if k.startswith(('XIAOMI_', 'HF_', 'PADDLE_'))
           or k in {'PATH', 'HOME', 'LANG', 'LD_LIBRARY_PATH', 'TMPDIR', 'NO_PROXY', 'no_proxy'}}
    env['XIAOMI_OCR_REPO'] = str(root)
    messages = [
        {'jsonrpc': '2.0', 'id': 1, 'method': 'initialize', 'params': {'protocolVersion': '2024-11-05', 'capabilities': {}, 'clientInfo': {'name': 'aiqb-ocr', 'version': '1'}}},
        {'jsonrpc': '2.0', 'method': 'notifications/initialized'},
        {'jsonrpc': '2.0', 'id': 2, 'method': 'tools/call', 'params': {'name': name, 'arguments': arguments}},
    ]
    with _slots:
        try:
            result = subprocess.run([python, str(script)], input=''.join(json.dumps(m, ensure_ascii=False) + '\n' for m in messages),
                text=True, capture_output=True, timeout=int(os.environ.get('XIAOMI_OCR_TIMEOUT', '600')), env=env, cwd=root)
        except subprocess.TimeoutExpired as exc:
            raise RuntimeError('Xiaomi OCR timed out') from exc
    # Never expose upstream stderr/prompt/image bytes to application logs or API callers.
    for line in reversed(result.stdout.splitlines()):
        try:
            response = json.loads(line)
        except json.JSONDecodeError:
            continue
        if response.get('id') != 2:
            continue
        body = response.get('result', {})
        if body.get('isError') or 'error' in response:
            raise RuntimeError('Xiaomi OCR task failed; inspect the local engine health and layout dependencies')
        text = '\n'.join(c.get('text', '') for c in body.get('content', []) if c.get('type') == 'text')
        if not text.strip():
            raise RuntimeError('Xiaomi OCR returned empty content')
        return text
    raise RuntimeError('Xiaomi MCP did not return a task result')

def image(png: bytes) -> str:
    return task('ocr_image', {'image_base64': base64.b64encode(png).decode(),
        'mode': os.environ.get('XIAOMI_OCR_MODE', 'region'),
        'max_tokens': int(os.environ.get('XIAOMI_OCR_MAX_TOKENS', '4096'))})
