#!/usr/bin/env python3
"""Model-judged RAG metrics. Requires OPENAI_API_KEY or evaluator-provider configuration."""
from __future__ import annotations

import argparse, json
from pathlib import Path

from ragas import EvaluationDataset, SingleTurnSample, evaluate
from ragas.metrics import Faithfulness, ResponseRelevancy, LLMContextPrecisionWithReference, LLMContextRecall


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--gold", type=Path, required=True)
    parser.add_argument("--predictions", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    gold = {row["id"]: row for row in json.loads(args.gold.read_text(encoding="utf-8"))}
    predictions = [json.loads(line) for line in args.predictions.read_text(encoding="utf-8").splitlines() if line.strip()]
    samples = []
    for pred in predictions:
        item = gold[pred["id"]]
        samples.append(SingleTurnSample(
            user_input=item["question"],
            response=pred.get("answer", ""),
            retrieved_contexts=[s.get("content", "") for s in pred.get("sources", [])],
            reference="；".join(item.get("answer_points", [])),
        ))
    result = evaluate(EvaluationDataset(samples=samples), metrics=[
        Faithfulness(), ResponseRelevancy(),
        LLMContextPrecisionWithReference(), LLMContextRecall(),
    ])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result.to_pandas().to_dict(orient="records"), ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
