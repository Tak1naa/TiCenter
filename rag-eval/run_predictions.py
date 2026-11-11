#!/usr/bin/env python3
"""Run approved questions through the same public SSE chat API and save JSONL predictions."""
from __future__ import annotations
import argparse, json, os, urllib.request
from pathlib import Path


def ask(base: str, row: dict) -> dict:
    cookie = os.environ.get("EVAL_SESSION_COOKIE", "")
    csrf = os.environ.get("EVAL_CSRF_TOKEN", "")
    body = json.dumps({"question": row["question"], "sessionId": os.environ.get("EVAL_SESSION_ID"),
                       "selectedFileIds": sorted({s["file_id"] for s in row.get("relevant_sources", [])}),
                       "provider": os.environ.get("EVAL_PROVIDER", "zhipu"),
                       "model": os.environ.get("EVAL_MODEL", "glm-4.6v-flash")}).encode()
    request = urllib.request.Request(base.rstrip("/") + "/api/chat/ask", data=body,
        headers={"Content-Type": "application/json", "Cookie": cookie, "X-CSRF-Token": csrf})
    answer, sources, event, meta, done = [], [], "", {}, {}
    with urllib.request.urlopen(request, timeout=300) as response:
        for raw in response:
            line = raw.decode("utf-8", "replace").strip()
            if line.startswith("event:"): event = line[6:].strip()
            elif line.startswith("data:"):
                data = json.loads(line[5:].strip())
                if event == "answer": answer.append(data.get("content", ""))
                elif event == "source": sources.append(data)
                elif event == "meta": meta.update(data)
                elif event == "done": done.update(data)
                elif event == "error": raise RuntimeError(data.get("error", "SSE error"))
    answer_text = "".join(answer)
    refusal_markers = ("题库资料不足", "资料不足", "无法可靠回答", "无法回答")
    def source(value):
        return {"source_id": value.get("source_id") or value.get("sourceId"),
                "file_id": value.get("file_id") or value.get("fileId"),
                "page": value.get("page"), "chunk_id": value.get("chunk_id") or value.get("chunkId"),
                "document_version": value.get("document_version") or value.get("documentVersion"),
                "generation_id": value.get("generation_id") or value.get("generationId")}
    cited = [source(value) for value in sources]
    retrieved = [source(value) for value in meta.get("retrievedSources", [])]
    return {"id": row["id"], "answer": answer_text,
            "refused": any(marker in answer_text for marker in refusal_markers),
            "sources": cited, "retrieved_sources": retrieved,
            "retrieval_mode": meta.get("retrievalMode"),
            "document_version": meta.get("documentVersion"),
            "active_generation_ids": meta.get("activeGenerationIds", []),
            "prompt_version": meta.get("promptVersion"),
            "retrieval_cache_hit": meta.get("retrievalCacheHit", False),
            "input_tokens": done.get("inputTokens") or done.get("inputTokensEstimate", 0),
            "usage_source": done.get("usageSource", "estimate"),
            "provider_cached_tokens": done.get("providerCachedTokens", 0)}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--gold", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--base-url", default=os.environ.get("EVAL_BASE_URL", "http://127.0.0.1"))
    args = parser.parse_args()
    rows = json.loads(args.gold.read_text(encoding="utf-8"))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(ask(args.base_url, row), ensure_ascii=False) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
