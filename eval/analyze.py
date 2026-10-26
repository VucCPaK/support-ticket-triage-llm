import json
import os
import re
import sys
from pathlib import Path

import pandas as pd
import psycopg
from psycopg.rows import dict_row
from sklearn.metrics import (accuracy_score, classification_report, fbeta_score,
                             mean_absolute_error, precision_score, recall_score)

EVAL_DIR = Path(__file__).parent
TICKETS_FILE = EVAL_DIR / "tickets.jsonl"
RUNS_DIR = EVAL_DIR / "runs"
# Local Docker Postgres; the host port is random, check it with: docker compose port postgres 5432
DB_URL = os.environ.get("EVAL_DB_URL", "postgresql://ticket_triage:local-dev-only@localhost:52349/ticket_triage")

PRIORITY_LEVEL = {"LOW": 0, "MEDIUM": 1, "HIGH": 2, "URGENT": 3}
CATEGORIES = ["ORDER", "BILLING", "RETURN_REFUND", "ACCOUNT", "FEEDBACK", "OTHER"]
PRIORITIES = list(PRIORITY_LEVEL)
ESCALATE = [True, False]
FAILED = "FAILED"  # stands for a failed triage in the matrices, so it shows up as an error


def read_jsonl(path: Path) -> list[dict]:
    with open(path, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def load(run_file: Path) -> pd.DataFrame:
    """One row per ticket: the label next to what the service answered."""
    expected = pd.json_normalize(read_jsonl(TICKETS_FILE))
    actual = pd.json_normalize(read_jsonl(run_file))

    # Keep only the columns the metrics need and give them flat, unambiguous names
    expected = expected.rename(
        columns={
            "expected.category": "category_expected",
            "expected.priority": "priority_expected",
            "expected.escalate": "escalate_expected",
            "expected.orderNumber": "order_expected",
        }
    )[
        [
            "id",
            "split",
            "customerEmail",
            "subject",
            "body",
            "note",
            "category_expected",
            "priority_expected",
            "escalate_expected",
            "order_expected",
        ]
    ]

    actual = actual.rename(
        columns={
            "response.id": "ticket_id",
            "response.status": "status",
            "response.triage.category": "category_actual",
            "response.triage.priority": "priority_actual",
            "response.triage.escalate": "escalate_actual",
            "response.triage.orderNumber": "order_actual",
            "latencyMs": "client_latency_ms",
        }
    )
    # A failed triage has no triage fields at all, so the columns may be missing entirely
    for column in [
        "category_actual",
        "priority_actual",
        "escalate_actual",
        "order_actual",
    ]:
        if column not in actual:
            actual[column] = None
    actual = actual[
        [
            "id",
            "statusCode",
            "error",
            "ticket_id",
            "status",
            "client_latency_ms",
            "category_actual",
            "priority_actual",
            "escalate_actual",
            "order_actual",
        ]
    ]

    # A run may cover only dev: compare against the splits it covers, so the other split does not
    # count as failed, while a ticket missing inside a covered split still does
    run_splits = set(expected.loc[expected["id"].isin(actual["id"]), "split"])
    expected = expected[expected["split"].isin(run_splits)]

    # validate="one_to_one" fails loudly if an id is duplicated in either file
    df = expected.merge(actual, on="id", how="left", validate="one_to_one")

    missing = df[df["statusCode"].isna() & df["error"].isna()]
    if not missing.empty:
        print(
            f"WARNING: {len(missing)} tickets have no result in the run: {list(missing['id'])}"
        )
    return df


def normalize_order(value) -> str | None:
    """'a-123', '#A123' and ' A 123 ' all mean A123; missing values mean 'no order number'."""
    if pd.isna(value):
        return None
    cleaned = re.sub(r"[^A-Z0-9]", "", str(value).upper())
    return cleaned or None


def normalize(df: pd.DataFrame) -> pd.DataFrame:
    df = df.copy()  # do not change the caller's frame

    for side in ("expected", "actual"):
        df[f"order_{side}_norm"] = df[f"order_{side}"].map(normalize_order)
        # A missing priority (failed triage) maps to NaN and never matches a label
        df[f"priority_{side}_level"] = df[f"priority_{side}"].map(PRIORITY_LEVEL)

    # Compared row by row: in pandas None == None in two columns may come out False
    df["order_ok"] = [
        e == a for e, a in zip(df["order_expected_norm"], df["order_actual_norm"])
    ]
    df["category_ok"] = df["category_expected"] == df["category_actual"]
    df["priority_ok"] = df["priority_expected"] == df["priority_actual"]
    df["priority_off_by_one"] = (
        df["priority_expected_level"] - df["priority_actual_level"]
    ).abs() <= 1
    # A failed triage has escalate None; it is a miss, not a match
    # Parentheses matter: in Python & binds tighter than ==
    escalate_actual = df["escalate_actual"]
    df["escalate_ok"] = escalate_actual.notna() & (
        df["escalate_expected"] == escalate_actual.fillna(False).astype(bool)
    )
    return df


def matrix(expected, actual, labels):
    """Rows are the labels, columns are the model's answers; the diagonal is correct."""
    expected = pd.Categorical(expected, categories=labels)
    actual = pd.Categorical(actual, categories=labels + [FAILED])
    return pd.crosstab(expected, actual, rownames=["expected"], colnames=["actual"], dropna=False)


def quality_report(df, name, show_errors):
    category_actual = df["category_actual"].fillna(FAILED)
    priority_actual = df["priority_actual"].fillna(FAILED)
    # A failed triage escalated nothing, so for the escalation metrics it counts as "no"
    escalate_actual = df["escalate_actual"].fillna(False).astype(bool)
    escalate_expected = df["escalate_expected"].astype(bool)

    print(f"\n{'=' * 25} {name}: {len(df)} tickets {'=' * 25}")
    print(f"triage failed: {(category_actual == FAILED).sum()}")

    print(f"\n[category] accuracy: {accuracy_score(df['category_expected'], category_actual):.3f}")
    print(classification_report(df["category_expected"], category_actual, labels=CATEGORIES, zero_division=0))
    print(matrix(df["category_expected"], category_actual, CATEGORIES))

    # Priority is ordinal: how far off and in which direction matters, not only whether it matched
    answered = df["priority_actual_level"].notna()
    diff = df["priority_actual_level"] - df["priority_expected_level"]
    mae = mean_absolute_error(df.loc[answered, "priority_expected_level"], df.loc[answered, "priority_actual_level"])
    print(f"\n[priority] exact: {df['priority_ok'].mean():.3f}, within one level: {df['priority_off_by_one'].mean():.3f}, "
          f"mean error: {mae:.2f} levels")
    print(f"           too low: {(diff < 0).mean():.3f} (urgent tickets wait), too high: {(diff > 0).mean():.3f}")
    print(matrix(df["priority_expected"], priority_actual, PRIORITIES))

    # Escalation: recall first, a missed legal threat costs more than a false alarm
    print(f"\n[escalate] recall: {recall_score(escalate_expected, escalate_actual, zero_division=0):.3f}, "
          f"precision: {precision_score(escalate_expected, escalate_actual, zero_division=0):.3f}, "
          f"F2: {fbeta_score(escalate_expected, escalate_actual, beta=2, zero_division=0):.3f}")
    print(matrix(escalate_expected, escalate_actual, ESCALATE))

    # Order number: an invented number is worse than a missed one
    has_expected = df["order_expected_norm"].notna()
    has_actual = df["order_actual_norm"].notna()
    invented = (~has_expected & has_actual).sum()
    missed = (has_expected & ~has_actual).sum()
    wrong = (has_expected & has_actual & ~df["order_ok"]).sum()
    print(f"\n[order number] accuracy: {df['order_ok'].mean():.3f}, "
          f"invented: {invented}, missed: {missed}, wrong: {wrong}")

    # The rows to read when writing the next prompt version; never shown for test
    if show_errors:
        pd.set_option("display.max_colwidth", 80)
        pd.set_option("display.width", 250)
        checks = {
            "category": ~df["category_ok"],
            "priority": ~df["priority_ok"],
            "escalate": ~df["escalate_ok"],
            "order": ~df["order_ok"],
        }
        for field, wrong_rows in checks.items():
            rows = df[wrong_rows]
            if rows.empty:
                continue
            print(f"\n--- {field} errors ({len(rows)}) ---")
            print(rows[["id", f"{field}_expected", f"{field}_actual", "body", "note"]].to_string(index=False))



LLM_COLUMNS = "ticket_id::text AS ticket_id, model, prompt_version, input_tokens, output_tokens, " \
              "reasoning_tokens, usd_cost, latency_ms"


def load_llm_metrics(ticket_ids: list[str]) -> tuple[pd.DataFrame, pd.DataFrame]:
    """Per-call metrics the API does not expose: one query per table, not one per ticket."""
    with psycopg.connect(DB_URL) as conn, conn.cursor(row_factory=dict_row) as cur:
        cur.execute(f"SELECT {LLM_COLUMNS} FROM ticket_triage WHERE ticket_id = ANY(%s::uuid[])", (ticket_ids,))
        triage = pd.DataFrame(cur.fetchall())
        cur.execute(f"SELECT {LLM_COLUMNS}, tool_calls FROM ticket_draft_reply WHERE ticket_id = ANY(%s::uuid[])",
                    (ticket_ids,))
        draft = pd.DataFrame(cur.fetchall())
    for metrics in (triage, draft):
        metrics["usd_cost"] = metrics["usd_cost"].astype(float)  # numeric comes back as Decimal
    return triage, draft


def latency_line(label: str, latency: pd.Series) -> str:
    p50, p95 = latency.quantile([0.5, 0.95])
    return f"{label}: p50 {p50 / 1000:.2f}s, p95 {p95 / 1000:.2f}s"


def cost_report(metrics: pd.DataFrame, name: str) -> None:
    cost = metrics["usd_cost"]
    versions = ", ".join(f"{m} v{v}" for m, v in metrics[["model", "prompt_version"]].drop_duplicates().itertuples(index=False))
    print(f"\n[{name}] {len(metrics)} calls ({versions})")
    print("  " + latency_line("latency", metrics["latency_ms"]))
    # Cost is additive (the bill is the sum), so the mean is the right average here, unlike for latency
    print(f"  cost: total ${cost.sum():.4f}, per ticket ${cost.mean():.6f}, per 10,000 tickets ${cost.mean() * 10_000:.2f}")
    print(f"  tokens per call: input {metrics['input_tokens'].mean():.0f}, output {metrics['output_tokens'].mean():.0f}")


def tool_report(draft: pd.DataFrame) -> None:
    used = draft["tool_calls"] > 0
    with_tool, without_tool = draft[used], draft[~used]
    print(f"\n[draft tool] used in {used.mean() * 100:.0f}% of drafts ({used.sum()} of {len(draft)})")
    for label, part in (("with tool", with_tool), ("without tool", without_tool)):
        if not part.empty:
            print(f"  {label}: cost ${part['usd_cost'].mean():.6f}, " + latency_line("latency", part["latency_ms"]))
    if not with_tool.empty and not without_tool.empty:
        print(f"  a draft with the tool costs {with_tool['usd_cost'].mean() / without_tool['usd_cost'].mean():.1f}x more")


def performance_report(df: pd.DataFrame) -> None:
    """Latency and cost do not depend on the labels, so they are measured on the whole run."""
    print(f"\n{'=' * 25} latency and cost: {len(df)} tickets {'=' * 25}")
    print(latency_line("[end to end, client]", df["client_latency_ms"]))
    triage, draft = load_llm_metrics(df["ticket_id"].dropna().tolist())
    cost_report(triage, "triage")
    cost_report(draft, "draft")
    tool_report(draft)
    per_ticket = triage["usd_cost"].sum() + draft["usd_cost"].sum()
    print(f"\n[total] ${per_ticket:.4f} for the run, ${per_ticket / len(df):.6f} per ticket, "
          f"${per_ticket / len(df) * 10_000:.2f} per 10,000 tickets")


if __name__ == "__main__":
    # A run file can be passed explicitly; otherwise the latest one is used
    # (file names start with a timestamp, so max() is the latest)
    run_file = Path(sys.argv[1]) if len(sys.argv) > 1 else max(RUNS_DIR.glob("*.jsonl"))
    df = normalize(load(run_file))
    print(f"run: {run_file.name}, rows: {len(df)}")

    for split in ("dev", "test"):
        if (df["split"] == split).any():
            quality_report(df[df["split"] == split], split, show_errors=(split == "dev"))

    performance_report(df)
