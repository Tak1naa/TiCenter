#!/usr/bin/env python3
"""Gate a candidate run against baseline quality and median input-token usage."""
import argparse, json, statistics
from pathlib import Path
from evaluate import evaluate


def jsonl(path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--gold", type=Path, required=True)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    args = parser.parse_args()
    gold = json.loads(args.gold.read_text(encoding="utf-8"))
    baseline_rows, candidate_rows = jsonl(args.baseline), jsonl(args.candidate)
    baseline = evaluate(gold, {r["id"]: r for r in baseline_rows})
    candidate = evaluate(gold, {r["id"]: r for r in candidate_rows})
    b_tokens = statistics.median(r.get("input_tokens", 0) for r in baseline_rows)
    c_tokens = statistics.median(r.get("input_tokens", 0) for r in candidate_rows)
    reduction = 0 if not b_tokens else 1 - c_tokens / b_tokens
    metrics = ("recall_at_20", "citation_precision", "refusal_accuracy")
    quality_ok = all(candidate[name] >= baseline[name] - .02 for name in metrics)
    token_ok = reduction >= .20
    result = {"baseline": baseline, "candidate": candidate,
              "baseline_median_input_tokens": b_tokens, "candidate_median_input_tokens": c_tokens,
              "token_reduction": reduction, "quality_ok": quality_ok, "token_ok": token_ok,
              "enable_prompt_v2": quality_ok and token_ok}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["enable_prompt_v2"] else 1


if __name__ == "__main__": raise SystemExit(main())
