"""
OCR Service — 统一文档解析：MinerU（可选）→ MarkItDown → LibreOffice → PaddleOCR 降级链。

配置 MINERU_URL 后 PDF/图片优先走 MinerU（公式/表格/扫描件质量最佳），
失败或未配置时自动降级到原有三级链路。

POST /parse  —  将任意文档转为 Markdown 格式文本
GET  /health —  健康检查
"""
import hashlib
import json
import os
import time
from pathlib import Path
from typing import Optional

import fitz  # PyMuPDF, for PDF page counting + rendering
from PIL import Image
from fastapi import FastAPI, HTTPException, Query, Header, Depends, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import Response
from markitdown import MarkItDown
from pydantic import BaseModel
import io
from xiaomi_adapter import image as xiaomi_image

OCR_ENGINE = os.environ.get("OCR_ENGINE", "xiaomi")

app = FastAPI(title="OCR Service", version="2.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173", "http://127.0.0.1:5173"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ── 共享 token 鉴权（与 llm-gateway 同一环境变量）──
import secrets as _secrets

API_SERVICE_TOKEN = os.environ.get("API_SERVICE_TOKEN", "")

def require_api_token(request: Request = None, x_api_token: str | None = Header(None)):
    if not API_SERVICE_TOKEN:
        raise HTTPException(status_code=503, detail="服务未配置 API_SERVICE_TOKEN")
    auth = x_api_token or ""
    if not _secrets.compare_digest(auth, API_SERVICE_TOKEN):
        raise HTTPException(status_code=401, detail="无效的服务 token")
    return True

CACHE_DIR = os.environ.get("OCR_CACHE_DIR", "/tmp/ocr-cache")
# LibreOffice 命令：本地 Flatpak 沙箱用 "flatpak-spawn --host soffice"，Docker 内用 "soffice"
SOFFICE_BIN = os.environ.get("SOFFICE_BIN", "soffice")
# LibreOffice 转换临时目录（避免硬编码本机路径，Docker 内用 /tmp 即可）
LO_TMP_DIR = os.environ.get("LO_TMP_DIR", "/tmp/lo-tmp")
# MinerU API 地址（如 http://mineru:8000）。为空 = 不启用，走原降级链
MINERU_URL = os.environ.get("MINERU_URL", "").rstrip("/")
# MinerU 解析后端：pipeline = 纯 CPU 无幻觉；有 GPU 时可换 hybrid-engine 等
MINERU_BACKEND = os.environ.get("MINERU_BACKEND", "pipeline")
# MinerU 读超时（秒）：CPU 上大 PDF 逐页解析可能要几分钟
MINERU_TIMEOUT = int(os.environ.get("MINERU_TIMEOUT", "900"))
os.makedirs(CACHE_DIR, exist_ok=True)
CACHE_DIR_PDF = os.path.join(CACHE_DIR, "pdf-cache")
os.makedirs(CACHE_DIR_PDF, exist_ok=True)

# Lazy-loaded PaddleOCR (for image-only PDF pages)
_ocr_instance = None

def get_ocr():
    global _ocr_instance
    if _ocr_instance is None:
        from paddleocr import PaddleOCR
        _ocr_instance = PaddleOCR(use_angle_cls=True, lang='ch', use_gpu=False, show_log=False)
    return _ocr_instance

_md_converter = None

def get_md():
    global _md_converter
    if _md_converter is None:
        _md_converter = MarkItDown()
    return _md_converter


class ParseRequest(BaseModel):
    filePath: str
    forceReParse: bool = False

class PageResult(BaseModel):
    pageNumber: int
    text: str
    method: str
    confidence: Optional[float] = None
    imagePath: Optional[str] = None
    blocks: list[dict] = []
    locationPrecision: str = "page"

class ParseResult(BaseModel):
    filePath: str
    fileName: str
    parseMethod: str
    pages: list[PageResult]
    totalPages: int
    parseTime: str
    fileHash: str


def _compute_file_hash(file_path: str) -> str:
    sha = hashlib.sha256()
    with open(file_path, "rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            sha.update(chunk)
    return sha.hexdigest()


def _markitdown_parse(file_path: str) -> str:
    """MarkItDown 转换，失败时返回空字符串（不抛异常）。"""
    try:
        result = get_md().convert(file_path)
        return result.text_content
    except Exception:
        return ""


# ── MinerU（可选前置解析）──

def _mineru_item_to_md(item: dict) -> str:
    """content_list 单项 → Markdown 片段。表格保留 HTML（LLM 可直接读），公式转 LaTeX。"""
    t = item.get("type")
    if t == "text":
        return str(item.get("text") or "")
    if t == "equation":
        latex = str(item.get("text") or "")
        return f"$${latex}$$" if latex else ""
    if t == "table":
        parts = [str(c) for c in (item.get("table_caption") or [])]
        if item.get("table_body"):
            parts.append(str(item["table_body"]))
        parts += [str(f) for f in (item.get("table_footnote") or [])]
        return "\n\n".join(p for p in parts if p)
    if t == "image":
        parts = [str(c) for c in (item.get("image_caption") or [])]
        parts += [str(f) for f in (item.get("image_footnote") or [])]
        return "\n\n".join(p for p in parts if p)
    return str(item.get("text") or "")


def _mineru_parse(file_path: str) -> Optional[ParseResult]:
    """调用 MinerU /file_parse 解析 PDF/图片。

    返回 None 表示不可用/失败/产出为空，调用方降级到原链路。
    content_list 的 page_idx（0 基）分组还原每页文本，保持按页存储模型。
    """
    if not MINERU_URL:
        return None
    import requests

    file_name = Path(file_path).name
    try:
        with open(file_path, "rb") as f:
            resp = requests.post(
                MINERU_URL + "/file_parse",
                files={"files": (file_name, f)},
                data={
                    "lang_list": "ch",
                    "backend": MINERU_BACKEND,
                    "parse_method": "auto",
                    "formula_enable": "true",
                    "table_enable": "true",
                    "return_md": "true",
                    "return_content_list": "true",
                },
                timeout=(10, MINERU_TIMEOUT),
            )
        resp.raise_for_status()
        payload = resp.json()
    except Exception as e:
        print(f"[mineru] parse failed for {file_name}: {type(e).__name__}: {str(e)[:200]}", flush=True)
        return None

    results = payload.get("results") or {}
    entry = next(iter(results.values()), None) if results else None
    if not entry:
        return None

    pages: dict[int, list[str]] = {}
    content_list = entry.get("content_list")
    if content_list:
        for item in content_list:
            if not isinstance(item, dict):
                continue
            try:
                idx = int(item.get("page_idx", 0) or 0)
            except (TypeError, ValueError):
                idx = 0
            pages.setdefault(idx, []).append(_mineru_item_to_md(item))
    elif entry.get("md_content"):
        pages.setdefault(0, []).append(str(entry["md_content"]))
    else:
        return None

    page_results = []
    total = max(pages) + 1
    for i in range(total):
        text = "\n\n".join(p for p in pages.get(i, []) if p and p.strip())
        if total > 1:
            text = f"## {file_name} — 第{i+1}页\n\n{text}"
        page_results.append(PageResult(pageNumber=i + 1, text=text,
                                       method="mineru", confidence=0.95))
    if not any(p.text.strip() for p in page_results):
        return None
    return ParseResult(
        filePath=file_path, fileName=file_name, parseMethod="mineru",
        pages=page_results, totalPages=total,
        parseTime=time.strftime("%Y-%m-%d %H:%M:%S"),
        fileHash=_compute_file_hash(file_path))


def _libreoffice_to_pdf(file_path: str) -> str:
    """将 Office 文件转 PDF，带文件哈希缓存。"""
    cache_key = _compute_file_hash(file_path)[:16]
    cached = os.path.join(CACHE_DIR_PDF, f"{cache_key}.pdf")
    if os.path.exists(cached) and os.path.getsize(cached) > 0:
        return cached

    import subprocess, tempfile, shutil, shlex
    os.makedirs(LO_TMP_DIR, exist_ok=True)
    tmpdir = tempfile.mkdtemp(prefix="lo-", dir=LO_TMP_DIR)
    try:
        cmd = shlex.split(SOFFICE_BIN) + ["--headless",
             "--convert-to", "pdf", file_path, "--outdir", tmpdir]
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
        if result.returncode != 0:
            return ""
        pdf_files = list(Path(tmpdir).glob("*.pdf"))
        if not pdf_files:
            return ""
        shutil.move(str(pdf_files[0]), cached)
        return cached
    except Exception:
        return ""
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)


def _lo_fallback_parse(file_path: str) -> str:
    """LibreOffice → PDF → 逐页提取（文本优先，无文本则 OCR）。"""
    pdf_path = _libreoffice_to_pdf(file_path)
    if not pdf_path:
        return ""
    doc = fitz.open(pdf_path)
    try:
        parts = []
        file_name = Path(file_path).name
        total = len(doc)
        for i in range(total):
            page = doc[i]
            raw = page.get_text().strip()
            if raw and len(raw) > 30:
                parts.append(f"## {file_name} — 第{i+1}页\n\n{raw}")
            else:
                rot = page.rotation
                mat = fitz.Matrix(2.0, 2.0)
                if rot != 0: mat = mat * fitz.Matrix(rot)
                pix = page.get_pixmap(matrix=mat)
                img_path = os.path.join(CACHE_DIR, f"lo_{Path(file_path).stem}_p{i}.png")
                pix.save(img_path)
                if rot != 0:
                    img = Image.open(img_path)
                    if rot == 90: img = img.rotate(-90, expand=True)
                    elif rot == 180: img = img.rotate(180, expand=True)
                    elif rot == 270: img = img.rotate(90, expand=True)
                    img.save(img_path)
                ocr_result = get_ocr().ocr(img_path, cls=True)
                text, _ = _extract_ocr_result(ocr_result)
                parts.append(f"## {file_name} — 第{i+1}页\n\n{text}")
        return "\n\n".join(parts)
    finally:
        doc.close()


def _ocr_page_text(file_path: str, page_num: int) -> str:
    if OCR_ENGINE == "xiaomi":
        return xiaomi_image(_render_single_page_png(file_path, page_num))
    """对 PDF 单页做 OCR，处理旋转。单页 OCR 失败不中断整个文件。"""
    doc = fitz.open(file_path)
    try:
        if page_num < 1 or page_num > len(doc):
            return ""
        page = doc[page_num - 1]
        rotation = page.rotation
        mat = fitz.Matrix(2.0, 2.0)
        if rotation != 0:
            mat = mat * fitz.Matrix(rotation)
        pix = page.get_pixmap(matrix=mat)
        img_path = os.path.join(CACHE_DIR, f"{Path(file_path).stem}_p{page_num}.png")
        pix.save(img_path)
        if rotation != 0:
            img = Image.open(img_path)
            if rotation == 90: img = img.rotate(-90, expand=True)
            elif rotation == 180: img = img.rotate(180, expand=True)
            elif rotation == 270: img = img.rotate(90, expand=True)
            img.save(img_path)
        try:
            ocr = get_ocr()
            result = ocr.ocr(img_path, cls=True)
            text, _ = _extract_ocr_result(result)
            return text
        except Exception as e:
            print(f"[ocr] page {page_num} OCR failed: {type(e).__name__}: {str(e)[:150]}", flush=True)
            return ""
    finally:
        doc.close()


def _extract_ocr_result(ocr_result) -> tuple[str, float]:
    """PaddleOCR 3.x 结果提取。"""
    if not ocr_result or not ocr_result[0]:
        return "", 0.0
    detections = ocr_result[0]
    lines = []
    confidences = []
    for item in detections:
        try:
            if not isinstance(item, (list, tuple)) or len(item) < 2:
                continue
            text_tuple = item[1]
            if isinstance(text_tuple, (list, tuple)) and len(text_tuple) >= 1:
                text = str(text_tuple[0]) if text_tuple[0] is not None else ""
                conf = float(text_tuple[1]) if len(text_tuple) > 1 and text_tuple[1] is not None else 1.0
            else:
                text = str(text_tuple) if text_tuple is not None else ""
                conf = 1.0
            if text.strip():
                lines.append(text)
                confidences.append(conf)
        except Exception:
            continue
    text = "\n".join(lines)
    confidence = sum(confidences) / len(confidences) if confidences else 0.0
    return text, round(confidence, 4)


def _parse_universal(file_path: str) -> ParseResult:
    """统一解析：MinerU（可选）→ MarkItDown / 逐页 fitz → LibreOffice → PaddleOCR。

    PDF/图片优先尝试 MinerU（公式/表格/扫描件质量最佳），
    失败或未配置自动降级到原有链路。
    """
    file_name = Path(file_path).name
    ext = Path(file_path).suffix.lower()
    is_pdf = ext == ".pdf"
    is_image = ext in (".png", ".jpg", ".jpeg", ".bmp", ".tiff")

    # ── MinerU 优先（PDF/图片）：失败返回 None 继续走原链 ──
    if (is_pdf or is_image) and MINERU_URL and OCR_ENGINE == "legacy":
        result = _mineru_parse(file_path)
        if result is not None:
            return result

    # ── MarkItDown 尝试（PDF 走下方逐页 fitz，跳过 MarkItDown 省一次重复转换）──
    md_text = ""
    md_method = "text"
    if not is_pdf and not is_image:
        try:
            md_text = _markitdown_parse(file_path)
            if md_text and len(md_text.strip()) > 10:
                md_method = "markitdown"
        except Exception:
            pass

    # ── PDF 逐页处理：有文字直接用，无文字 OCR ──
    if is_pdf:
        doc = fitz.open(file_path)
        total_pages = len(doc)
        pages = []
        for i in range(total_pages):
            page = doc[i]
            raw = page.get_text().strip()
            if raw and len(raw) > 50:
                heading = f"## {file_name} — 第{i+1}页\n\n" if total_pages > 1 else ""
                text = heading + raw
                method = "text"
                confidence = 1.0
            else:
                raw = _ocr_page_text(file_path, i + 1)
                heading = f"## {file_name} — 第{i+1}页\n\n" if total_pages > 1 else ""
                text = heading + raw
                method = "xiaomi" if OCR_ENGINE == "xiaomi" else "ocr"
                confidence = None
            blocks = []
            if method == "text":
                for order, block in enumerate(page.get_text("blocks")):
                    if len(block) > 4 and str(block[4]).strip():
                        blocks.append({"text": str(block[4]).strip(), "type": "paragraph", "order": order,
                            "bbox": [block[0] / page.rect.width, block[1] / page.rect.height,
                                     block[2] / page.rect.width, block[3] / page.rect.height]})
            pages.append(PageResult(
                pageNumber=i + 1, text=text, method=method, confidence=confidence, blocks=blocks,
                locationPrecision="block" if blocks else "page"))
        doc.close()
        return ParseResult(
            filePath=file_path, fileName=file_name, parseMethod="text" if all(p.method == "text" for p in pages) else ("xiaomi" if OCR_ENGINE == "xiaomi" else "ocr"),
            pages=pages, totalPages=total_pages,
            parseTime=time.strftime("%Y-%m-%d %H:%M:%S"), fileHash=_compute_file_hash(file_path))

    # ── 图片：MarkItDown 不行就 OCR ──
    if is_image:
        if md_text and len(md_text.strip()) > 10:
            text = md_text
            method = md_method
            confidence = 1.0
        else:
            text = _ocr_page_text(file_path, 1)
            method = "xiaomi" if OCR_ENGINE == "xiaomi" else "ocr"
            confidence = None
        return ParseResult(
            filePath=file_path, fileName=file_name, parseMethod=method,
            pages=[PageResult(pageNumber=1, text=text, method=method, confidence=confidence)],
            totalPages=1, parseTime=time.strftime("%Y-%m-%d %H:%M:%S"), fileHash=_compute_file_hash(file_path))

    # ── 其他格式 (docx, pptx, xlsx, txt, md, html, csv, json, xml, zip, epub): MarkItDown, 失败则 LO ──
    if md_text and len(md_text.strip()) > 10:
        text = md_text
        method = md_method
    else:
        if ext in (".docx", ".doc", ".pptx", ".ppt", ".xlsx", ".xls"):
            # LO → PDF → 逐页解析
            pdf_path = _libreoffice_to_pdf(file_path)
            if pdf_path:
                doc = fitz.open(pdf_path)
                total = len(doc)
                pages = []
                for i in range(total):
                    page = doc[i]
                    raw = page.get_text().strip()
                    if not raw or len(raw) < 30:
                        rot = page.rotation
                        mat = fitz.Matrix(2.0, 2.0)
                        if rot != 0: mat = mat * fitz.Matrix(rot)
                        pix = page.get_pixmap(matrix=mat)
                        img_path = os.path.join(CACHE_DIR, f"lo_{Path(file_path).stem}_p{i}.png")
                        pix.save(img_path)
                        if rot != 0:
                            img = Image.open(img_path)
                            if rot == 90: img = img.rotate(-90, expand=True)
                            elif rot == 180: img = img.rotate(180, expand=True)
                            elif rot == 270: img = img.rotate(90, expand=True)
                            img.save(img_path)
                        try:
                            if OCR_ENGINE == "xiaomi":
                                raw = xiaomi_image(Path(img_path).read_bytes())
                            else:
                                ocr_result = get_ocr().ocr(img_path, cls=True)
                                raw, _ = _extract_ocr_result(ocr_result)
                        except Exception as e:
                            print(f"[ocr] page {i} OCR failed: {type(e).__name__}: {str(e)[:150]}", flush=True)
                            raw = ""
                    heading = f"## {file_name} — 第{i+1}页\n\n" if total > 1 else ""
                    pages.append(PageResult(
                        pageNumber=i + 1, text=heading + raw, method="libreoffice", confidence=0.8))
                doc.close()
                if pages:
                    return ParseResult(
                        filePath=file_path, fileName=file_name, parseMethod="libreoffice",
                        pages=pages, totalPages=len(pages),
                        parseTime=time.strftime("%Y-%m-%d %H:%M:%S"), fileHash=_compute_file_hash(file_path))
            text = "(无法解析此文件)"
            method = "markitdown"
        else:
            text = "(无法解析此文件)"
            method = "markitdown"

    return ParseResult(
        filePath=file_path, fileName=file_name, parseMethod=method,
        pages=[PageResult(pageNumber=1, text=text, method=method, confidence=0.9)],
        totalPages=1, parseTime=time.strftime("%Y-%m-%d %H:%M:%S"), fileHash=_compute_file_hash(file_path))


# ── 向后兼容端点 ──

EXAM_BANK_ROOT = os.environ.get("EXAM_BANK_ROOT", "/home/takina/AiQuestionBank/data/exam-bank")

def _validate_path(file_path: str):
    requested = Path(file_path).resolve()
    allowed = Path(EXAM_BANK_ROOT).resolve()
    if not requested.is_relative_to(allowed):
        raise HTTPException(status_code=403, detail="拒绝访问：路径不在题库目录范围内")
    return str(requested)


def _cleanup_temp_images(max_age_hours: int = 24):
    """清理渲染/OCR 产生的临时 PNG（缓存目录无限累积会吃满磁盘）。"""
    try:
        now = time.time()
        for f in os.listdir(CACHE_DIR):
            if not f.endswith(".png"):
                continue
            p = os.path.join(CACHE_DIR, f)
            try:
                if now - os.path.getmtime(p) > max_age_hours * 3600:
                    os.remove(p)
            except OSError:
                pass
    except OSError:
        pass


@app.post("/parse", response_model=ParseResult)
def parse_document(req: ParseRequest, _: bool = Depends(require_api_token)):
    _cleanup_temp_images()
    file_path = _validate_path(req.filePath)
    if not os.path.exists(file_path):
        raise HTTPException(status_code=404, detail=f"文件未找到: {file_path}")
    # 资源上限：防 CPU/磁盘 DoS
    try:
        size = os.path.getsize(file_path)
        if size > 100 * 1024 * 1024:
            raise HTTPException(status_code=413, detail="文件超过 100MB 上限")
    except OSError:
        raise HTTPException(status_code=404, detail="文件读取失败")
    ext = Path(file_path).suffix.lower()
    if ext not in (".pdf", ".png", ".jpg", ".jpeg", ".bmp", ".tiff",
                    ".txt", ".md", ".docx", ".doc", ".pptx", ".ppt",
                    ".xlsx", ".xls", ".html", ".htm", ".csv", ".json", ".xml", ".zip", ".epub"):
        raise HTTPException(status_code=400, detail=f"不支持的文件类型: {ext}")
    try:
        return _parse_universal(file_path)
    except Exception as e:
        raise HTTPException(status_code=500, detail="解析失败")


# ── 渲染端点 (保持兼容) ──

def _render_pdf_page_png(doc, page_number: int) -> bytes:
    if page_number < 1 or page_number > len(doc):
        raise ValueError(f"页码越界: {page_number}")
    page = doc[page_number - 1]
    mat = fitz.Matrix(1.5, 1.5)
    pix = page.get_pixmap(matrix=mat)
    img = Image.frombytes("RGB", [pix.width, pix.height], pix.samples)
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return buf.getvalue()


def _render_single_page_png(file_path: str, page_number: int) -> bytes:
    ext = Path(file_path).suffix.lower()
    if ext == ".pdf":
        doc = fitz.open(file_path)
        try:
            return _render_pdf_page_png(doc, page_number)
        finally:
            doc.close()
    if ext in (".png", ".jpg", ".jpeg", ".bmp", ".tiff"):
        with open(file_path, "rb") as f:
            return f.read()
    # Office/文本格式：优先用 LibreOffice 转换后的 PDF（保留原始排版与页数）
    try:
        pdf_path = _libreoffice_to_pdf(file_path)
        doc = fitz.open(pdf_path)
        try:
            return _render_pdf_page_png(doc, page_number)
        finally:
            doc.close()
    except Exception:
        pass
    # MarkItDown 能处理的各种格式：统一用 MarkItDown → 排版为图片回退
    doc = fitz.open()
    text = _markitdown_parse(file_path) if Path(file_path).suffix.lower() != ".pdf" else ""
    font_size = 12; line_height = 18; margin = 54
    page_w, page_h = 595, 842
    current_page = doc.new_page(width=page_w, height=page_h)
    y = margin
    usable_w = page_w - 2 * margin
    for line in text.split("\n"):
        if y + line_height > page_h - margin:
            current_page = doc.new_page(width=page_w, height=page_h)
            y = margin
        try:
            tw = fitz.TextWriter(current_page.rect)
            tw.append((margin, y), line[:90], font=fitz.Font("helv"), fontsize=font_size)
            tw.write_text(current_page)
        except Exception:
            pass
        y += line_height
    page = doc[min(page_number - 1, len(doc) - 1)]
    mat = fitz.Matrix(1.5, 1.5)
    pix = page.get_pixmap(matrix=mat)
    img = Image.frombytes("RGB", [pix.width, pix.height], pix.samples)
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    doc.close()
    return buf.getvalue()

@app.get("/render-page")
def render_page(filePath: str = Query(...), page: int = Query(1), _: bool = Depends(require_api_token)):
    file_path = _validate_path(filePath)
    if not os.path.exists(file_path):
        raise HTTPException(status_code=404, detail="文件未找到")
    # 仅允许渲染已知文档/图片格式，堵住 MarkItDown 任意文件渲染泄露
    ext = Path(file_path).suffix.lower()
    if ext not in (".pdf", ".png", ".jpg", ".jpeg", ".bmp", ".tiff",
                   ".docx", ".doc", ".pptx", ".ppt", ".xlsx", ".xls",
                   ".txt", ".md", ".html", ".csv"):
        raise HTTPException(status_code=403, detail="不允许渲染该文件类型")
    try:
        png = _render_single_page_png(file_path, page)
        return Response(content=png, media_type="image/png")
    except Exception as e:
        raise HTTPException(status_code=500, detail="渲染失败")

@app.get("/count-pages")
def count_pages(filePath: str = Query(...), _: bool = Depends(require_api_token)):
    file_path = _validate_path(filePath)
    if not os.path.exists(file_path):
        raise HTTPException(status_code=404, detail="文件未找到")
    ext = Path(file_path).suffix.lower()
    if ext == ".pdf":
        doc = fitz.open(file_path)
        total = len(doc)
        doc.close()
        return {"totalPages": total, "fileName": os.path.basename(file_path)}
    if ext in (".png", ".jpg", ".jpeg", ".bmp", ".tiff"):
        return {"totalPages": 1, "fileName": os.path.basename(file_path)}
    # Office/文本格式：优先用 LibreOffice 转换后的 PDF 页数（保留原始分页）
    try:
        pdf_path = _libreoffice_to_pdf(file_path)
        doc = fitz.open(pdf_path)
        total = len(doc)
        doc.close()
        return {"totalPages": total, "fileName": os.path.basename(file_path)}
    except Exception:
        pass
    # MarkItDown formats: treated as single page
    return {"totalPages": 1, "fileName": os.path.basename(file_path)}

@app.get("/health")
def health():
    return {"status": "ok", "cacheDir": CACHE_DIR, "version": "2.1.0",
            "engine": OCR_ENGINE, "mineru": MINERU_URL or "disabled",
            "xiaomiMode": os.environ.get("XIAOMI_OCR_MODE", "region"),
            "xiaomiConfigured": os.path.isfile(os.path.join(os.environ.get("XIAOMI_OCR_REPO", "/opt/Xiaomi-OCR-0"), "skills/xiaomi-ocr/mcp_ocr_server.py"))}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8001)
