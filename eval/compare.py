"""Compare two eval runs: metric deltas, guardrails, and which tickets got fixed or broken.

    python eval/compare.py eval/runs/<base>.jsonl eval/runs/<candidate>.jsonl
"""
import argparse
from pathlib import Path

import pandas as pd
from sklearn.metrics import f1_score, precision_score, recall_score

from analyze import CATEGORIES, FAILED, load, load_llm_metrics, normalize

FIELDS = ["category", "priority", "escalate", "order"]


def quality(df: pd.DataFrame) -> dict[str, float]:
    escalate_expected = df["escalate_expected"].astype(bool)
    escalate_actual = df["escalate_actual"].fillna(False).astype(bool)
    is_urgent = df["priority_expected"] == "URGENT"
    diff = df["priority_actual_level"] - df["priority_expected_level"]
    return {
        "triage failed": df["category_actual"].isna().sum(),
        "category accuracy": df["category_ok"].mean(),
        "category macro F1": f1_score(df["category_expected"], df["category_actual"].fillna(FAILED),
                                      labels=CATEGORIES, average="macro", zero_division=0),
        "priority exact": df["priority_ok"].mean(),
        "priority too low": (diff < 0).mean(),
        "URGENT recall": (df.loc[is_urgent, "priority_actual"] == "URGENT").mean() if is_urgent.any() else 1.0,
        "escalate recall": recall_score(escalate_expected, escalate_actual, zero_division=0),
        "escalate precision": precision_score(escalate_expected, escalate_actual, zero_division=0),
        "order accuracy": df["order_ok"].mean(),
        "order invented": (df["order_expected_norm"].isna() & df["order_actual_norm"].notna()).sum(),
    }


def performance(df: pd.DataFrame) -> dict[str, float]:
    triage, draft = load_llm_metrics(df["ticket_id"].dropna().tolist())
    return {
        "triage latency p50, s": triage["latency_ms"].quantile(0.5) / 1000,
        "triage latency p95, s": triage["latency_ms"].quantile(0.95) / 1000,
        "triage cost per 10k tickets, $": triage["usd_cost"].mean() * 10_000,
        "draft latency p95, s": draft["latency_ms"].quantile(0.95) / 1000,
        "draft cost per 10k tickets, $": draft["usd_cost"].mean() * 10_000,
    }


# Guardrails: a candidate that breaks any of these is rejected whatever else it improves
GUARDRAILS = {
    "triage failed": lambda base, cand: cand <= base,
    "escalate recall": lambda base, cand: cand >= base,
    "URGENT recall": lambda base, cand: cand >= base,
    "priority too low": lambda base, cand: cand <= base,
    "order invented": lambda base, cand: cand == 0,
}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("base", type=Path)
    parser.add_argument("candidate", type=Path)
    args = parser.parse_args()

    base, cand = normalize(load(args.base)), normalize(load(args.candidate))
    # Compare only the tickets both runs answered, e.g. a full baseline against a dev-only candidate
    common = sorted(set(base["id"]) & set(cand["id"]))
    base = base[base["id"].isin(common)].set_index("id").sort_index()
    cand = cand[cand["id"].isin(common)].set_index("id").sort_index()
    splits = sorted(base["split"].unique())
    print(f"base:      {args.base.name}\ncandidate: {args.candidate.name}\n{len(common)} common tickets, splits: {splits}")

    base_metrics = {**quality(base), **performance(base)}
    cand_metrics = {**quality(cand), **performance(cand)}
    table = pd.DataFrame({"base": base_metrics, "candidate": cand_metrics})
    table["diff"] = table["candidate"] - table["base"]
    print("\n" + table.to_string(float_format=lambda x: f"{x:.4f}"))
    print(f"(on {len(common)} tickets one ticket is {1 / len(common):.3f}, so smaller differences are noise)")

    print("\nguardrails:")
    for metric, ok in GUARDRAILS.items():
        status = "ok  " if ok(base_metrics[metric], cand_metrics[metric]) else "FAIL"
        print(f"  {status} {metric}: {base_metrics[metric]:.3f} -> {cand_metrics[metric]:.3f}")

    # Per-ticket changes are the real signal; they are shown for dev only, reading test rows burns the test set
    dev = base["split"] == "dev"
    pd.set_option("display.max_colwidth", 70)
    pd.set_option("display.width", 250)
    for field in FIELDS:
        fixed = ~base[f"{field}_ok"] & cand[f"{field}_ok"]
        broken = base[f"{field}_ok"] & ~cand[f"{field}_ok"]
        print(f"\n[{field}] fixed: {fixed.sum()}, broken: {broken.sum()}")
        for label, mask in (("fixed", fixed & dev), ("broken", broken & dev)):
            if mask.any():
                rows = pd.DataFrame({
                    "expected": base.loc[mask, f"{field}_expected"],
                    "base": base.loc[mask, f"{field}_actual"],
                    "candidate": cand.loc[mask, f"{field}_actual"],
                    "body": base.loc[mask, "body"],
                })
                print(f"  {label}:\n{rows.to_string()}")


if __name__ == "__main__":
    main()
