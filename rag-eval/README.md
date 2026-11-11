# RAG 离线评测

评测数据分三层：`silver.draft.json` 是 AI 预填的 24 条候选，不可作为质量结论；`gold.min.json` 只包含人工批准的数据；`runs/*.jsonl` 是每次检索/模型运行产物且不提交 Git。生产题库与 MySQL 不会被这些脚本修改。

先从当前活动的公开题库生成候选（密钥和数据库密码只从进程环境读取）：

```bash
DB_PASSWORD=... ZHIPU_API_KEY=... python bootstrap_silver.py
```

按 `ANNOTATION_GUIDE.md` 人工复核后，将需要批准的条目改为 `APPROVED` 并填写独立复核人，再生成最小金标：

```bash
python promote_gold.py --draft silver.draft.json --output gold.min.json
python validate_gold.py gold.min.json --smoke
```

每次实验把逐题结果保存为 JSONL；每行包含答案、拒答判断、实际来源、检索模式、promptVersion 和活动代次。

```bash
python validate_gold.py gold.min.json --smoke
python validate_gold.py gold.release.json --release
```

```bash
python run_predictions.py --gold gold.min.json --output runs/baseline.jsonl
python evaluate.py --profile smoke --gold gold.min.json --predictions runs/baseline.jsonl
```

Prompt v2 只有在主要质量指标下降不超过 2 个百分点且中位输入 token 至少下降 20% 时才通过：

```bash
python compare_runs.py --gold gold.min.json --baseline runs/baseline.jsonl --candidate runs/prompt-v2.jsonl
```

脚本不依赖模型即可稳定计算 Recall@20、MRR、nDCG@10、引用精确率和拒答准确率。忠实度、上下文精确率/召回率和答案相关性使用 Ragas 另行计算，并把模型、提示词、索引版本、token、延迟与本文件的结果一起归档。严禁自动生成标准答案冒充金标。

```bash
pip install -r requirements.txt
OPENAI_API_KEY=... python ragas_evaluate.py --gold gold.release.json \
  --predictions runs/qdrant-hybrid.jsonl --output runs/qdrant-hybrid-ragas.json
```
