#!/usr/bin/env python3
"""10-case GLM-4.6V-Flash smoke test. Secrets are read only from process env."""
from __future__ import annotations
import argparse, base64, io, json, os, subprocess, tempfile, time, urllib.request
from datetime import datetime, timezone
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageEnhance


FIXTURES = [
    ("中文文本", ["虚拟内存", "地址空间"], "图中定义的技术是什么？请简要说明。"),
    ("公式", ["v", "at"], "写出图中的速度公式。"),
    ("表格", ["王明", "88"], "表格中数据库课程得分最高的是谁，多少分？"),
    ("选择题", ["B", "1024"], "图中选择题的正确答案是什么？"),
    ("低对比噪声", ["校验码", "错误"], "低对比度图片描述的用途是什么？"),
]

REAL = [
    (1, ["40A0H"], "表1中地址 2250H 存放的内容是什么？"),
    (2, ["1s", "1秒"], "题2中假定各中断源处理时间是多少？"),
    (3, ["预处理", "数据传送", "后处理"], "DMA 传送过程分为哪三个阶段？"),
    (4, ["前三个节拍", "前三节拍"], "图3中寄存器堆输出三态门在何时打开？"),
    (5, ["间接寻址"], "无条件跳转指令 JMP *D 采用什么寻址方式？"),
]


def font(size: int):
    candidates = [Path("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"),
                  Path("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc")]
    for path in candidates:
        if path.exists(): return ImageFont.truetype(str(path), size)
    return ImageFont.load_default()


def fixture_images(root: Path) -> list[Path]:
    canvases: list[Image.Image] = []
    lines = [
        "虚拟内存把物理内存与进程地址空间分离。",
        "匀加速运动：v = v₀ + at",
        "姓名    课程      分数\n李华    数据库    82\n王明    数据库    88",
        "1 KiB 等于多少字节？\nA. 1000  B. 1024  C. 2048  D. 4096",
        "校验码用于检测数据传输中的错误。",
    ]
    for index, text in enumerate(lines):
        image = Image.new("RGB", (1200, 720), "white")
        draw = ImageDraw.Draw(image)
        draw.multiline_text((70, 100), text, font=font(48), fill=(25, 25, 25), spacing=30)
        if index == 4:
            image = ImageEnhance.Contrast(image).enhance(.22)
            # Deterministic line noise without introducing random/non-reproducible output.
            draw = ImageDraw.Draw(image)
            for x in range(0, 1200, 37): draw.line((x, 0, x + 140, 720), fill=(220, 220, 220), width=1)
        path = root / f"fixture-{index + 1}.png"
        image.save(path)
        canvases.append(image)
    return [root / f"fixture-{i}.png" for i in range(1, 6)]


def render_real_pages(pdf: Path, root: Path) -> list[Path]:
    prefix = root / "physical"
    subprocess.run(["pdftoppm", "-png", "-r", "150", "-f", "1", "-l", "3",
                    str(pdf), str(prefix)], check=True, stdout=subprocess.DEVNULL)
    logical: list[Path] = []
    for physical in sorted(root.glob("physical-*.png")):
        image = Image.open(physical).convert("RGB").rotate(90, expand=True)
        middle = image.width // 2
        for crop in (image.crop((0, 0, middle, image.height)),
                     image.crop((middle, 0, image.width, image.height))):
            path = root / f"real-{len(logical) + 1}.jpg"
            crop.save(path, quality=88, optimize=True)
            logical.append(path)
    if len(logical) < 5: raise RuntimeError("题库 PDF 无法生成 5 个独立视觉样本")
    return logical[:5]


def data_url(path: Path) -> tuple[str, int]:
    raw = path.read_bytes()
    return "data:image/" + path.suffix.lstrip(".") + ";base64," + base64.b64encode(raw).decode(), len(raw)


def stream_request(url: str, body: dict, headers: dict) -> dict:
    request = urllib.request.Request(url, data=json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json", **headers})
    started = time.monotonic(); first = None; answer: list[str] = []; sources = []; events = []; current = ""
    with urllib.request.urlopen(request, timeout=300) as response:
        for raw in response:
            line = raw.decode("utf-8", "replace").strip()
            if line.startswith("event:"): current = line[6:].strip(); events.append(current)
            elif line.startswith("data:"):
                payload = json.loads(line[5:].strip())
                if current in ("delta", "answer") and payload.get("content"):
                    if first is None: first = time.monotonic()
                    answer.append(payload["content"])
                elif current == "source": sources.append(payload)
                elif current == "error": raise RuntimeError(payload.get("code") or payload.get("error"))
    ended = time.monotonic()
    return {"answer": "".join(answer), "sources": sources, "events": events,
            "first_token_ms": None if first is None else round((first - started) * 1000),
            "total_ms": round((ended - started) * 1000)}


def evaluate_case(name: str, expected: list[str], response: dict, image_bytes: int) -> dict:
    answer = response.pop("answer")
    passed = all(point.lower() in answer.lower() for point in expected)
    return {"name": name, "passed": passed, "expected_points": expected,
            "answer_excerpt": answer[:500], "image_count": 1, "image_bytes": image_bytes,
            "http_429_count": 0, **response}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--gateway-url", default=os.environ.get("LLM_GATEWAY_URL", "http://127.0.0.1:8003"))
    parser.add_argument("--web-url", default=os.environ.get("EVAL_BASE_URL"))
    parser.add_argument("--file-id", type=int)
    parser.add_argument("--pdf", type=Path, default=Path("data/exam-bank/计组期末2022-2023.pdf"))
    parser.add_argument("--output", type=Path, default=Path("reports/glm-vision-smoke.json"))
    args = parser.parse_args()
    if not os.environ.get("ZHIPU_API_KEY"):
        raise SystemExit("ZHIPU_API_KEY 未设置：请在 llm-gateway 进程环境中设置后再运行；脚本不会读取命令行 Key")
    service_token = os.environ.get("API_SERVICE_TOKEN")
    if not service_token: raise SystemExit("API_SERVICE_TOKEN 未设置")
    if not args.pdf.exists(): raise SystemExit(f"PDF 不存在: {args.pdf}")

    rows = []
    with tempfile.TemporaryDirectory(prefix="aiqb-glm-vision-") as tmp:
        root = Path(tmp)
        images = fixture_images(root) + render_real_pages(args.pdf, root)
        specs = [(name, points, question) for name, points, question in FIXTURES]
        specs += [(f"真实题库逻辑页{page}", points, question) for page, points, question in REAL]
        for (name, points, question), path in zip(specs, images):
            image, size = data_url(path)
            result = stream_request(args.gateway_url.rstrip("/") + "/chat/stream", {
                "provider": "zhipu", "model": "glm-4.6v-flash", "max_tokens": 1000,
                "messages": [{"role": "user", "content": [{"type": "text", "text": question},
                    {"type": "image_url", "image_url": {"url": image}}]}]},
                {"X-Api-Token": service_token})
            rows.append(evaluate_case(name, points, result, size))

    # Optional Web closure for the five real questions. It validates retrieval-selected image pages and citations.
    web_rows = []
    if args.web_url:
        if not args.file_id: raise SystemExit("--web-url 同时要求 --file-id")
        cookie = os.environ.get("EVAL_SESSION_COOKIE", "")
        csrf = os.environ.get("EVAL_CSRF_TOKEN", "")
        for page, points, question in REAL:
            result = stream_request(args.web_url.rstrip("/") + "/api/chat/ask", {
                "question": question, "sessionId": os.environ.get("EVAL_SESSION_ID"),
                "selectedFileIds": [args.file_id], "provider": "zhipu",
                "model": "glm-4.6v-flash", "multimodal": True},
                {"Cookie": cookie, "X-CSRF-Token": csrf})
            source_pages = {int(source.get("page", -1)) for source in result.get("sources", [])}
            row = evaluate_case(f"Web真实题库逻辑页{page}", points, result, 0)
            row["citation_page_consistent"] = (page + 1) // 2 in source_pages
            row["passed"] = row["passed"] and row["citation_page_consistent"]
            web_rows.append(row)

    passed = sum(row["passed"] for row in rows)
    report = {"model": "glm-4.6v-flash", "created_at": datetime.now(timezone.utc).isoformat(),
              "key_persisted": False, "direct": rows, "web": web_rows,
              "direct_passed": passed, "direct_total": len(rows),
              "default_multimodal_enabled": passed >= 8 and (not web_rows or all(r["passed"] for r in web_rows))}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: report[k] for k in ("direct_passed", "direct_total", "default_multimodal_enabled")}, ensure_ascii=False))
    return 0 if report["default_multimodal_enabled"] else 1


if __name__ == "__main__": raise SystemExit(main())
