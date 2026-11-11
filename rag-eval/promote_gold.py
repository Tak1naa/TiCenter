#!/usr/bin/env python3
"""Copy only human-approved silver rows into the minimal gold set."""
from __future__ import annotations
import argparse, json
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("silver", type=Path)
    parser.add_argument("gold", type=Path)
    parser.add_argument("--minimum", type=int, default=20)
    args = parser.parse_args()
    rows = json.loads(args.silver.read_text(encoding="utf-8"))
    approved = [row for row in rows if row.get("annotation", {}).get("status") == "APPROVED"]
    if len(approved) < args.minimum:
        print(f"not writing gold: {len(approved)} approved rows, need {args.minimum}")
        return 2
    args.gold.write_text(json.dumps(approved, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": "ok", "approved": len(approved), "output": str(args.gold)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
