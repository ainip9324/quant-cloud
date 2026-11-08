"""Compare the current public context with local vector retrieval."""

import argparse
from hashlib import sha256
import json
from math import ceil, fsum, sqrt
import os
from pathlib import Path
import sys
from time import perf_counter

from retrieval import CATALOG, DEFAULT_MODEL, DEFAULT_MODEL_CACHE, load_index, open_pgvector, query_vector

HERE = Path(__file__).resolve().parent
STATUSES = {"ANSWERED", "GENERAL", "CLARIFY", "OUT_OF_SCOPE", "INSUFFICIENT"}
DEFAULT_TOP_K = 3


def load_cases(source_ids):
    """Check that every positive label points to current public material."""
    cases = json.loads((HERE / "cases.json").read_text(encoding="utf-8"))
    seen = set()
    for case in cases:
        expected = case["expectedSourceIds"]
        status = case["expectedStatus"]
        if (
            case["id"] in seen
            or not 1 <= len(case["question"].strip()) <= 300
            or len(set(expected)) != len(expected)
            or not set(expected) <= source_ids
            or status not in STATUSES
            or bool(expected) != (status == "ANSWERED")
        ):
            raise ValueError(f"Invalid eval case: {case['id']}")
        seen.add(case["id"])
    if not any(case["expectedSourceIds"] for case in cases):
        raise ValueError("The eval set needs questions with reference sources")
    return cases


def cosine_similarity(left, right):
    dot = fsum(a * b for a, b in zip(left, right, strict=True))
    return dot / sqrt(fsum(a * a for a in left) * fsum(b * b for b in right))


def measure(cases, model, dimension, lookup):
    """Measure each original question from embedding through ranked IDs."""
    results = {}
    for case in cases:
        started = perf_counter()
        vector = query_vector(model, case["question"], dimension)
        results[case["id"]] = {
            "sourceIds": lookup(vector),
            "retrievalMs": round((perf_counter() - started) * 1000, 2),
        }
    return results


def retrieve(cases, sources, args):
    """Run the comparison through memory ranking or the shared pgvector index."""
    model, indexed, dimension = load_index(sources, args.model, args.model_cache, args.allow_model_download)

    if args.memory:
        def lookup(query):
            return sorted(indexed, key=lambda key: (-cosine_similarity(query, indexed[key]), key))[:args.top_k]

        return measure(cases, model, dimension, lookup)

    with open_pgvector(indexed, args.pg_port) as lookup:
        return measure(cases, model, dimension, lambda query: lookup(query, args.top_k))


def summarize(cases, sources, lookups):
    """Measure retrieval only. Negative cases belong to the answer evaluation."""
    sources_by_id = {source["id"]: source for source in sources}
    rows = []
    for case in cases:
        lookup = lookups[case["id"]]
        ids = lookup["sourceIds"]
        context = [sources_by_id[source_id] for source_id in ids]
        packed = json.dumps(context, ensure_ascii=False, separators=(",", ":"))
        expected = case["expectedSourceIds"]
        matched = set(expected).intersection(ids)
        rows.append({
            **case,
            **lookup,
            "contextBytes": len(packed.encode("utf-8")),
            "recall": len(matched) / len(expected) if expected else None,
        })

    labeled = [row for row in rows if row["recall"] is not None]
    latency = sorted(row["retrievalMs"] for row in rows if row["retrievalMs"] is not None)
    return {
        "questionsWithReferences": len(labeled),
        "meanRecall": sum(row["recall"] for row in labeled) / len(labeled),
        "fullRecallCount": sum(row["recall"] == 1 for row in labeled),
        "meanContextBytes": round(sum(row["contextBytes"] for row in rows) / len(rows), 2),
        "p95RetrievalMs": latency[ceil(0.95 * len(latency)) - 1] if latency else None,
        "cases": rows,
    }


def score_answers(cases, answers):
    """Compare model status and citations with the labels and actual supplied context."""
    if set(answers) != {case["id"] for case in cases}:
        raise ValueError("The answer file must contain one answer per evaluation case")

    status_matches = 0
    citation_matches = 0
    valid_responses = 0
    failures = []
    for case in cases:
        answer = answers[case["id"]]
        status = answer["status"]
        actual = answer["sourceIds"]
        if not isinstance(actual, list) or any(not isinstance(source_id, str) for source_id in actual):
            raise ValueError(f"Invalid answer sourceIds: {case['id']}")

        status_ok = status == case["expectedStatus"]
        actual_ids = set(actual)
        valid = (
            isinstance(status, str)
            and status in STATUSES
            and bool(actual) == (status == "ANSWERED")
            and len(actual_ids) == len(actual)
            and actual_ids <= set(case["sourceIds"])
        )
        citation_ok = valid and actual_ids == set(case["expectedSourceIds"])
        valid_responses += valid
        status_matches += status_ok
        citation_matches += citation_ok
        if not status_ok or not citation_ok:
            failures.append({"id": case["id"], "statusMatch": status_ok, "citationMatch": citation_ok})

    return {
        "validResponses": valid_responses,
        "statusMatches": status_matches,
        "citationMatches": citation_matches,
        "failures": failures,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-only", action="store_true", help="run without any dependency or network access")
    backend = parser.add_mutually_exclusive_group()
    backend.add_argument("--memory", action="store_true", help="rank the small public corpus without PostgreSQL")
    backend.add_argument("--pg-port", type=int, help="loopback port of the dedicated eval database")
    parser.add_argument("--top-k", type=int, default=DEFAULT_TOP_K)
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--model-cache", type=Path, default=DEFAULT_MODEL_CACHE)
    parser.add_argument("--allow-model-download", action="store_true")
    parser.add_argument("--retrieval-report", type=Path, help="read a previous comparison without rerunning retrieval")
    parser.add_argument("--answers", type=Path, help="score captured answers by mode and case ID")
    args = parser.parse_args()
    if bool(args.retrieval_report) != bool(args.answers):
        parser.error("answer scoring requires both --retrieval-report and --answers")
    if args.retrieval_report is not None:
        report = json.loads(args.retrieval_report.read_text(encoding="utf-8"))
        answers = json.loads(args.answers.read_text(encoding="utf-8"))
        modes = ["baseline", "vector"] if "vector" in report else ["baseline"]
        if set(answers) != set(modes):
            raise ValueError("Answer modes must match the recorded comparison")
        for mode in modes:
            report[mode]["answers"] = score_answers(report[mode]["cases"], answers[mode])
        print(json.dumps(report, ensure_ascii=True, indent=2))
        return

    if not args.baseline_only and not args.memory and (
        args.pg_port is None
        or not 1 <= args.pg_port <= 65535
        or not os.environ.get("KNOWLEDGE_EVAL_PG_PASSWORD")
    ):
        parser.error("vector comparison requires valid ports and KNOWLEDGE_EVAL_PG_PASSWORD")

    catalog = CATALOG.read_bytes()
    sources = json.loads(catalog)
    source_ids = [source["id"] for source in sources]
    if not source_ids or len(set(source_ids)) != len(source_ids):
        raise ValueError("The public catalog must have unique source IDs")
    if not args.baseline_only and not 1 <= args.top_k <= len(sources):
        parser.error(f"--top-k must be between 1 and {len(sources)}")
    cases = load_cases(set(source_ids))

    baseline = {case["id"]: {"sourceIds": source_ids, "retrievalMs": None} for case in cases}
    report = {
        "catalogSha256": sha256(catalog).hexdigest(),
        "baseline": summarize(cases, sources, baseline),
    }
    if not args.baseline_only:
        report["embeddingModel"] = args.model
        report["retrievalBackend"] = "memory" if args.memory else "pgvector"
        report["topK"] = args.top_k
        report["vector"] = summarize(cases, sources, retrieve(cases, sources, args))
    print(json.dumps(report, ensure_ascii=True, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, ValueError, ImportError) as error:
        sys.exit(f"Knowledge eval failed: {error}")
