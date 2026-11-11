#!/usr/bin/env python3
from __future__ import annotations

import argparse, json, re, sys
from collections import Counter
from datetime import datetime
from pathlib import Path

CATEGORIES = {"fact", "cross_page", "formula", "table", "ocr_noise", "unanswerable", "similar_concept", "prompt_injection"}
REQUIRED = {"id", "question", "category", "relevant_sources", "answer_points", "reference_answer", "should_refuse", "annotation"}


def validate_row(row: object, index: int) -> list[str]:
    prefix = str(index)
    if not isinstance(row, dict): return [f"{prefix}: must be an object"]
    errors = [f"{prefix}.{key}: required" for key in REQUIRED - row.keys()]
    if not re.fullmatch(r"[a-zA-Z0-9_-]+", str(row.get("id", ""))): errors.append(f"{prefix}.id: use letters, numbers, _ or -")
    if not isinstance(row.get("question"), str) or len(row.get("question", "")) < 2: errors.append(f"{prefix}.question: too short")
    if row.get("category") not in CATEGORIES: errors.append(f"{prefix}.category: invalid")
    sources = row.get("relevant_sources")
    points = row.get("answer_points")
    if not isinstance(sources, list): errors.append(f"{prefix}.relevant_sources: must be an array"); sources = []
    if not isinstance(points, list) or any(not isinstance(point, str) or not point.strip() for point in points or []):
        errors.append(f"{prefix}.answer_points: must contain non-empty strings"); points = []
    for source_index, source in enumerate(sources):
        required_source = {"file_id", "file_name", "document_version", "page", "evidence"}
        if not isinstance(source, dict): errors.append(f"{prefix}.relevant_sources.{source_index}: must be an object"); continue
        missing = required_source - source.keys()
        errors.extend(f"{prefix}.relevant_sources.{source_index}.{key}: required" for key in missing)
        if not isinstance(source.get("file_id"), int) or source.get("file_id", 0) < 1: errors.append(f"{prefix}.relevant_sources.{source_index}.file_id: invalid")
        if not isinstance(source.get("page"), int) or source.get("page", 0) < 1: errors.append(f"{prefix}.relevant_sources.{source_index}.page: invalid")
        for key in ("file_name", "document_version", "evidence"):
            if not isinstance(source.get(key), str) or not source.get(key, "").strip(): errors.append(f"{prefix}.relevant_sources.{source_index}.{key}: empty")
    refused = row.get("should_refuse")
    if not isinstance(refused, bool): errors.append(f"{prefix}.should_refuse: must be boolean")
    if refused and (sources or points): errors.append(f"{prefix}: refused rows must have empty sources and answer_points")
    if refused and not str(row.get("refusal_reason") or "").strip(): errors.append(f"{prefix}.refusal_reason: required when refusing")
    if refused is False and (not sources or not points): errors.append(f"{prefix}: answerable rows require sources and answer_points")
    if not isinstance(row.get("reference_answer"), str) or not row.get("reference_answer", "").strip(): errors.append(f"{prefix}.reference_answer: empty")
    annotation = row.get("annotation")
    if not isinstance(annotation, dict): return errors + [f"{prefix}.annotation: must be an object"]
    if annotation.get("status") not in {"DRAFT", "REVIEWED", "APPROVED"}: errors.append(f"{prefix}.annotation.status: invalid")
    if not str(annotation.get("annotated_by") or "").strip(): errors.append(f"{prefix}.annotation.annotated_by: required")
    try: datetime.fromisoformat(annotation.get("annotated_at", ""))
    except (TypeError, ValueError): errors.append(f"{prefix}.annotation.annotated_at: use ISO-8601 date-time")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate gold data and release gates")
    parser.add_argument("gold", type=Path)
    parser.add_argument("--release", action="store_true", help="require >=300 approved, independently reviewed rows")
    parser.add_argument("--smoke", action="store_true", help="require >=20 APPROVED rows for local regression")
    parser.add_argument("--minimum", type=int, default=None)
    args = parser.parse_args()
    data = json.loads(args.gold.read_text(encoding="utf-8"))
    if not isinstance(data, list):
        print("Gold validation failed:\n- <root>: must be a JSON array", file=sys.stderr)
        return 1
    messages = [message for index, row in enumerate(data) for message in validate_row(row, index)]
    ids = [row.get("id") for row in data if isinstance(row, dict)]
    duplicates = [item for item, count in Counter(ids).items() if count > 1]
    if duplicates: messages.append("duplicate ids: " + ", ".join(map(str, duplicates)))
    minimum = args.minimum if args.minimum is not None else (300 if args.release else (20 if args.smoke else 1))
    if len(data) < minimum: messages.append(f"only {len(data)} rows; minimum is {minimum}")
    if args.release or args.smoke:
        for index, row in enumerate(data):
            annotation = row.get("annotation", {})
            if annotation.get("status") != "APPROVED": messages.append(f"{index}.annotation.status: must be APPROVED")
            if not annotation.get("reviewed_by"): messages.append(f"{index}.annotation.reviewed_by: required")
            if annotation.get("reviewed_by") == annotation.get("annotated_by"):
                messages.append(f"{index}.annotation: reviewer must differ from annotator")
            if not annotation.get("reviewed_at"): messages.append(f"{index}.annotation.reviewed_at: required")
    if messages:
        print("Gold validation failed:", file=sys.stderr)
        print("\n".join(f"- {message}" for message in messages), file=sys.stderr)
        return 1
    print(json.dumps({"status": "ok", "rows": len(data),
                      "categories": dict(Counter(row["category"] for row in data))}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
