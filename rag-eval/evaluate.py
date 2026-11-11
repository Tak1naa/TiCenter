#!/usr/bin/env python3
"""Deterministic retrieval/citation/refusal metrics; optional Ragas metrics via --ragas."""
from __future__ import annotations

import argparse, json, math, re, sys
from pathlib import Path


def key(source: dict) -> tuple[int, int]:
    return int(source["file_id"]), int(source["page"])


def dcg(rels: list[int]) -> float:
    return sum(rel / math.log2(i + 2) for i, rel in enumerate(rels))


def evaluate(gold: list[dict], predictions: dict[str, dict], k: int = 20) -> dict:
    recall, reciprocal, ndcg, citation, refusal = [], [], [], [], []
    missing = []
    for item in gold:
        pred = predictions.get(item["id"])
        if pred is None:
            missing.append(item["id"]); continue
        expected = {key(s) for s in item["relevant_sources"]}
        retrieved = [key(s) for s in pred.get("retrieved_sources", [])[:k]]
        if expected:
            matched = expected.intersection(retrieved)
            recall.append(len(matched) / len(expected))
            ranks = [i + 1 for i, value in enumerate(retrieved) if value in expected]
            reciprocal.append(1 / min(ranks) if ranks else 0)
            rels = [1 if value in expected else 0 for value in retrieved[:10]]
            ideal = [1] * min(len(expected), 10)
            ndcg.append(dcg(rels) / dcg(ideal) if ideal else 1)
        cited = {str(v) for v in re.findall(r"\[S(\d+)\]", pred.get("answer", ""))}
        source_map = {str(s["source_id"]).removeprefix("S"): key(s)
                      for s in pred.get("sources", []) if "source_id" in s}
        citation.append(sum(source_map.get(c) in expected for c in cited) / len(cited) if cited else (1.0 if not expected else 0.0))
        refused = bool(pred.get("refused", False))
        refusal.append(float(refused == bool(item["should_refuse"])))
    mean = lambda xs: sum(xs) / len(xs) if xs else 0.0
    return {"evaluated": len(gold) - len(missing), "missing": missing,
            "recall_at_20": mean(recall), "mrr": mean(reciprocal),
            "ndcg_at_10": mean(ndcg), "citation_precision": mean(citation),
            "refusal_accuracy": mean(refusal)}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--gold", type=Path, required=True)
    parser.add_argument("--predictions", type=Path, required=True)
    parser.add_argument("--profile", choices=("draft", "smoke", "release"), default="smoke")
    parser.add_argument("--minimum", type=int, default=None)
    parser.add_argument("--enforce-targets", action="store_true")
    args = parser.parse_args()
    gold = json.loads(args.gold.read_text(encoding="utf-8"))
    minimum = args.minimum if args.minimum is not None else {"draft": 1, "smoke": 20, "release": 300}[args.profile]
    if len(gold) < minimum:
        print(f"gold set has {len(gold)} rows; at least {minimum} rows required for {args.profile}", file=sys.stderr)
        return 2
    rows = [json.loads(line) for line in args.predictions.read_text(encoding="utf-8").splitlines() if line.strip()]
    result = evaluate(gold, {row["id"]: row for row in rows})
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if args.enforce_targets:
        targets = {"recall_at_20": .90, "citation_precision": .95, "refusal_accuracy": .95}
        return int(bool(result["missing"]) or any(result[name] < target for name, target in targets.items()))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
