import argparse
import time
import httpx
import json
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime
from pathlib import Path

BASE_URL = "http://127.0.0.1:8080"
EVAL_DIR = Path(__file__).parent
RUN_FILE_DIR = EVAL_DIR / "runs"
TICKETS_FILE_JSON = EVAL_DIR / "tickets.jsonl"

parser = argparse.ArgumentParser(description="Send the eval tickets to the running service and save the answers")
# dev by default: every look at test numbers while tuning the prompt leaks the test set into the decisions
parser.add_argument("--split", choices=["dev", "test", "all"], default="dev")
parser.add_argument("--label", default="run", help="what this run tries, e.g. triage-v2 or reasoning-low")
# A few at a time: more would hit the OpenAI rate limit and the service's DB pool.
# Client latency grows with parallelism; compare experiments by the per-call latency_ms from the DB instead.
parser.add_argument("--workers", type=int, default=4)
args = parser.parse_args()

timestamp = datetime.now().strftime("%Y-%m-%dT%H-%M-%S")  # 2026-10-22T18-30-05
# The timestamp stays first, so the newest run is still the max() file name
run_file = Path(RUN_FILE_DIR) / f"{timestamp}_{args.label}_{args.split}.jsonl"
run_file.parent.mkdir(parents=True, exist_ok=True)  # create eval/runs if missing

with open(TICKETS_FILE_JSON, encoding="utf-8") as f:
    tickets = [json.loads(line) for line in f if line.strip()]
if args.split != "all":
    tickets = [t for t in tickets if t["split"] == args.split]
print(f"{len(tickets)} tickets ({args.split}), {args.workers} at a time -> {run_file.name}")


def send_ticket(client: httpx.Client, ticket: dict) -> dict:
    payload = {
        "customerEmail": ticket["customerEmail"],
        "subject": ticket["subject"],
        "body": ticket["body"],
    }

    start = time.perf_counter()
    try:
        response = client.post("/tickets", json=payload)
        return {
            "id": ticket["id"],
            "statusCode": response.status_code,
            "latencyMs": round((time.perf_counter() - start) * 1000),
            "response": response.json() if response.is_success else None,
            "error": None if response.is_success else response.text,
        }
    except httpx.HTTPError as e:
        return {
            "id": ticket["id"],
            "statusCode": None,
            "latencyMs": round((time.perf_counter() - start) * 1000),
            "response": None,
            "error": f"{type(e).__name__}: {e}",
        }


started = time.perf_counter()
# httpx.Client is thread-safe, so the threads share one client and its connection pool
with httpx.Client(base_url=BASE_URL, timeout=60) as client, ThreadPoolExecutor(max_workers=args.workers) as pool:
    futures = [pool.submit(send_ticket, client, ticket) for ticket in tickets]
    # Results come back in completion order; only the main thread writes the file, so no locking is needed
    with open(run_file, "a", encoding="utf-8") as f:
        for done, future in enumerate(as_completed(futures), start=1):
            result = future.result()
            f.write(json.dumps(result, ensure_ascii=False) + "\n")
            f.flush()  # keep finished tickets on disk even if the run is interrupted
            print(f"[{done}/{len(tickets)}] {result['id']} {result['statusCode']} {result['latencyMs']}ms")

print(f"done in {time.perf_counter() - started:.0f}s")
