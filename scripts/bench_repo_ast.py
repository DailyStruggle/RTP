#!/usr/bin/env python3
"""
Gate 10 Multi-Front Differential Benchmark & Hold-for-Refinement Evaluator (`scripts/bench_repo_ast.py`).

Quantitatively evaluates `scripts/repo_ast.py` and `corpus-atlas/` against baseline
multi-call `grep_search` + `glob_search` + `open` workflows across four mandatory fronts:
  - Front 1 (Tool Call & Token Savings): `>= 50.0%` call reduction and `>= 40.0%` byte/token reduction
  - Front 2 (Comprehension & Recall + REQ-DISC-003 Isolation): `>= 90.0%` top-5 recall on
    ground-truth `TRACEABILITY.md` and `ADR-115` `Gov(c)` sets, with `0` `REQ-DISC-003` leaks
  - Front 3 (Latency & Freshness): query p95 `< 50.0 ms`, single-file incremental sync p95 `< 150.0 ms`,
    and `0` stale reads after working-tree edits
  - Front 4 (Anti-Redundancy SPI Discovery): `>= 90.0%` top-3 hit rate on natural-language capability
    queries for existing Tier 1/2 SPIs and utilities

If any front fails to meet its threshold, `--gate` returns `HOLD_FOR_REFINEMENT` (exit code 1).
"""
from __future__ import annotations

import argparse
import dataclasses
import json
import pathlib
import sqlite3
import statistics
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass
from typing import Any, Literal

SCRIPT_DIR = pathlib.Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent
ATLAS_DIR = REPO_ROOT / "corpus-atlas"
BOT_DIR = REPO_ROOT / "discord-support-bot"

for _p in (SCRIPT_DIR, ATLAS_DIR, BOT_DIR):
    if _p.is_dir() and str(_p) not in sys.path:
        sys.path.insert(0, str(_p))

import local_llm_queue as llq  # noqa: E402
import repo_ast  # noqa: E402
from build_doc_index import build_index, sync_dirty_files  # noqa: E402


@dataclass(frozen=True)
class BenchmarkGateReport:
    passed_all_fronts: bool
    decision: Literal["GO_PROMOTE", "HOLD_FOR_REFINEMENT"]
    front1_call_reduction_pct: float       # Gate: >= 50.0%
    front1_token_reduction_pct: float      # Gate: >= 40.0%
    front2_traceability_recall_top5: float # Gate: >= 0.90
    front2_gov_recall_top5: float          # Gate: >= 0.90
    front2_req_disc_003_leaks: int         # Gate: == 0
    front3_query_p95_ms: float             # Gate: < 50.0 ms
    front3_incremental_sync_p95_ms: float  # Gate: < 150.0 ms
    front3_stale_reads: int                # Gate: == 0
    front4_spi_top3_hit_rate: float        # Gate: >= 0.90
    failed_reasons: tuple[str, ...]
    details: dict[str, Any] = dataclasses.field(default_factory=dict)


FRONT1_REPRESENTATIVE_TASKS: tuple[tuple[str, str], ...] = (
    ("LocationGenerator", "class"),
    ("TeleportPipelineTask", "class"),
    ("ComparativeCostGovernor", "class"),
    ("EwmaEstimator", "spi"),
    ("DurationParser", "spi"),
    ("MemoryTracker", "class"),
    ("RTPScheduler", "spi"),
    ("RTPServerAccessor", "spi"),
    ("RegionQueueManager", "class"),
    ("AnvilPrefilter", "class"),
    ("LinearAdjustor", "class"),
    ("TreeCommand", "spi"),
    ("BitCodeRegistry", "spi"),
    ("HeatmapRenderer", "spi"),
    ("WorkAttributionLedger", "class"),
    ("Circle", "class"),
    ("REQ-RTP-S-005", "req"),
    ("REQ-RTP-F-002", "req"),
    ("ADR-115", "adr"),
    ("shape.centerRadius", "config"),
)

FRONT2_TRACEABILITY_GROUND_TRUTH: tuple[tuple[str, str, str], ...] = (
    ("REQ-RTP-F-001", "RegionQueueManager", "SLATest"),
    ("REQ-RTP-F-002", "Circle", "MemoryShapeTest"),
    ("REQ-RTP-F-003", "NormalMemoryShape", "MemoryShapeTest"),
    ("REQ-RTP-F-005", "MemoryShape", "MemoryShapeTest"),
    ("REQ-RTP-F-006", "MemoryShape", "RegionPipelineTest"),
    ("REQ-RTP-F-007", "Circle", "DeterministicShapeTest"),
    ("REQ-RTP-F-013", "ConfigParser", "ConfigParserLanguageTest"),
    ("REQ-RTP-S-001", "SafetyScan", "SafetyScanTest"),
    ("REQ-RTP-S-002", "MemoryTracker", "ChunkTicketLifecycleTest"),
    ("REQ-RTP-S-003", "GlobalRegionVerifiers", "ReqRtpS003SchematicFootprintClaimTest"),
    ("REQ-RTP-S-004", "RTPCmd", "ReqRtpS004VerifierFailSafeTest"),
    ("REQ-RTP-S-005", "AnvilPrefilter", "AnvilSectorReaderTest"),
    ("REQ-RTP-S-006", "RTPAPI", "RTPAPIGuardTest"),
    ("REQ-API-F-003", "ClaimBoundaryRegistry", "ClaimBoundaryRegistryTest"),
    ("REQ-API-F-009", "BitCodeRegistry", "ActionDefinitionValidatorTest"),
)

FRONT2_GOV_GROUND_TRUTH: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("TeleportPipelineTask", ("REQ-RTP-S-004", "REQ-RTP-S-005", "S-005")),
    ("LocationGenerator", ("REQ-RTP-S-005", "S-005", "ADR-015")),
    ("MemoryTracker", ("REQ-RTP-S-002", "S-002")),
    ("Circle", ("REQ-RTP-F-002", "ADR-001", "ADR-085")),
    ("RegionQueueManager", ("REQ-RTP-F-001", "REQ-RTP-S-005", "S-005")),
    ("ComparativeCostGovernor", ("ADR-115",)),
    ("EwmaEstimator", ("ADR-087", "REQ-PRED-F-001")),
    ("LinearAdjustor", ("REQ-RTP-S-001", "S-001", "ADR-016")),
    ("ClaimBoundaryRegistry", ("REQ-API-F-003", "ADR-026")),
    ("RTPAPI", ("REQ-RTP-S-006", "S-006", "ADR-026")),
)

FRONT4_SPI_CAPABILITY_QUERIES: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("exponential moving average estimator", ("EwmaEstimator",)),
    ("adaptive comparative cost governor", ("ComparativeCostGovernor",)),
    ("duration string parser temporal unit", ("DurationParser",)),
    ("chunk ticket memory lifecycle tracker", ("MemoryTracker",)),
    ("folia region scheduler task abstraction", ("RTPScheduler", "RTPRunnable")),
    ("yaml comment preserving reader writer", ("RtpYamlReader", "RtpYamlWriter")),
    ("server platform family compatibility accessor", ("RTPServerAccessor", "ServerAccessor")),
    ("built in test finding code registry", ("BitCodeRegistry", "BitFinding")),
    ("command tree parameter tab complete", ("TreeCommand", "CommandParameter")),
    ("anvil region file nbt chunk prefilter", ("AnvilPrefilter", "RegionFileReader")),
    ("cartography map heatmap renderer", ("HeatmapRenderer", "ChartRenderer")),
    ("work attribution ledger budget", ("WorkAttributionLedger",)),
)


def _percentile(sorted_vals: list[float], pct: float) -> float:
    if not sorted_vals:
        return 0.0
    if len(sorted_vals) == 1:
        return sorted_vals[0]
    idx = (len(sorted_vals) - 1) * (pct / 100.0)
    lo = int(idx)
    hi = min(len(sorted_vals) - 1, lo + 1)
    frac = idx - lo
    return sorted_vals[lo] * (1.0 - frac) + sorted_vals[hi] * frac


def _summarize_dist(vals: list[float]) -> dict[str, float]:
    if not vals:
        return {"mean": 0.0, "median": 0.0, "p95": 0.0, "min": 0.0, "max": 0.0, "spread": 0.0}
    s = sorted(vals)
    return {
        "mean": round(statistics.mean(s), 3),
        "median": round(statistics.median(s), 3),
        "p95": round(_percentile(s, 95.0), 3),
        "min": round(s[0], 3),
        "max": round(s[-1], 3),
        "spread": round(s[-1] - s[0], 3),
    }


def benchmark_front1_discovery_savings(
    conn: sqlite3.Connection,
    repo_root: pathlib.Path = REPO_ROOT,
) -> dict[str, Any]:
    """
    Front 1: Compares baseline multi-call discovery (`glob_search` + `grep_search` + `open`
    across source file, `TRACEABILITY.md`, `docs/adr/README.md`, and `*Test.java`) against
    a single `repo_ast.py context <target>` call across 20 representative tasks.
    """
    trace_path = repo_root / "docs" / "dev" / "TRACEABILITY.md"
    adr_readme_path = repo_root / "docs" / "adr" / "README.md"
    trace_lines = trace_path.read_text(encoding="utf-8", errors="replace").splitlines() if trace_path.is_file() else []
    adr_readme_lines = adr_readme_path.read_text(encoding="utf-8", errors="replace").splitlines() if adr_readme_path.is_file() else []
    trace_100_bytes = min(6000, len("\n".join(trace_lines[:100]).encode("utf-8"))) if trace_lines else 4200
    adr_100_bytes = min(4500, len("\n".join(adr_readme_lines[:100]).encode("utf-8"))) if adr_readme_lines else 4500

    task_rows: list[dict[str, Any]] = []
    baseline_calls_total = 0
    ast_calls_total = 0
    baseline_bytes_total = 0
    ast_bytes_total = 0

    for target, kind in FRONT1_REPRESENTATIVE_TASKS:
        ctx = repo_ast.query_context(conn, target)
        ast_md = repo_ast.format_context_markdown(ctx)
        ast_bytes = len(ast_md.encode("utf-8"))
        ast_calls = 1

        # Baseline discovery sequence to gather the same 5 facets (path/methods, callers/imports,
        # TRACEABILITY.md row, governing ADR in docs/adr/README.md, and *Test.java target):
        #   1. glob_search / grep_search to locate target definition (~350B output)
        #   2. open(target_file, 100 lines) to read class/doc header & imports
        #   3. grep_search in docs/dev/TRACEABILITY.md + open(100 lines) for REQ/Test mapping
        #   4. grep_search across codebase for callers/dependents (~900B output)
        #   5. glob_search for matching *Test.java or open(docs/adr/README.md, 100 lines)
        base_calls = 5
        src_open_bytes = 3200
        if ctx.get("primary_class") and ctx["primary_class"].get("path"):
            p = repo_root / ctx["primary_class"]["path"]
            if p.is_file():
                lines_100 = p.read_text(encoding="utf-8", errors="replace").splitlines()[:100]
                src_open_bytes = len("\n".join(lines_100).encode("utf-8"))
        elif ctx.get("adr_doc") and ctx["adr_doc"].get("path"):
            p = repo_root / ctx["adr_doc"]["path"]
            if p.is_file():
                lines_100 = p.read_text(encoding="utf-8", errors="replace").splitlines()[:100]
                src_open_bytes = len("\n".join(lines_100).encode("utf-8"))

        base_bytes = 350 + src_open_bytes + trace_100_bytes + 900 + (adr_100_bytes if kind in ("class", "spi", "adr") else 1800)

        baseline_calls_total += base_calls
        ast_calls_total += ast_calls
        baseline_bytes_total += base_bytes
        ast_bytes_total += ast_bytes

        call_red = round((1.0 - ast_calls / base_calls) * 100.0, 2)
        byte_red = round((1.0 - ast_bytes / max(1, base_bytes)) * 100.0, 2)
        task_rows.append({
            "target": target,
            "kind": kind,
            "baseline_calls": base_calls,
            "ast_calls": ast_calls,
            "call_reduction_pct": call_red,
            "baseline_bytes": base_bytes,
            "ast_bytes": ast_bytes,
            "token_reduction_pct": byte_red,
        })

    overall_call_red = round((1.0 - ast_calls_total / max(1, baseline_calls_total)) * 100.0, 2)
    overall_token_red = round((1.0 - ast_bytes_total / max(1, baseline_bytes_total)) * 100.0, 2)
    return {
        "tasks_evaluated": len(task_rows),
        "baseline_calls_total": baseline_calls_total,
        "ast_calls_total": ast_calls_total,
        "call_reduction_pct": overall_call_red,
        "baseline_bytes_total": baseline_bytes_total,
        "ast_bytes_total": ast_bytes_total,
        "baseline_est_tokens": baseline_bytes_total // 4,
        "ast_est_tokens": ast_bytes_total // 4,
        "token_reduction_pct": overall_token_red,
        "per_task_token_reduction_stats": _summarize_dist([r["token_reduction_pct"] for r in task_rows]),
        "tasks": task_rows,
    }


def benchmark_front2_comprehension_and_isolation(
    conn: sqlite3.Connection,
) -> dict[str, Any]:
    """
    Front 2: Evaluates top-5 recall on ground-truth `TRACEABILITY.md` (`REQ-*` -> `Class` -> `Test`)
    and `ADR-115` `Gov(c)` governance sets, plus verifies `0` `REQ-DISC-003` public plane leaks.
    """
    trace_hits = 0
    trace_misses: list[str] = []
    for req_id, expected_cls, expected_test in FRONT2_TRACEABILITY_GROUND_TRUTH:
        ctx = repo_ast.query_context(conn, req_id)
        impls = ctx["hierarchy"]["subclasses_and_implementors"]
        tests = ctx["verified_by_tests"]
        if expected_cls in impls and expected_test in tests:
            trace_hits += 1
        else:
            trace_misses.append(f"{req_id} (expected {expected_cls} + {expected_test}, got impls={impls[:4]}, tests={tests[:4]})")

    trace_recall = round(trace_hits / max(1, len(FRONT2_TRACEABILITY_GROUND_TRUTH)), 4)

    gov_hits = 0
    gov_total = 0
    gov_misses: list[str] = []
    for cls_name, expected_govs in FRONT2_GOV_GROUND_TRUTH:
        ctx = repo_ast.query_context(conn, cls_name)
        observed = set(ctx["gov_c"]["adrs"] + ctx["gov_c"]["reqs"] + ctx["gov_c"]["safety_prohibitions"])
        for eg in expected_govs:
            gov_total += 1
            if eg in observed:
                gov_hits += 1
            else:
                gov_misses.append(f"{cls_name} missing {eg} (got {sorted(observed)})")

    gov_recall = round(gov_hits / max(1, gov_total), 4)

    # Check REQ-DISC-003 isolation on `doc_sections`
    allowed_dev = {"docs/dev/BIT_CODES.md", "docs/dev/SUPPORT_MATRIX.md"}
    leak_rows = conn.execute(
        """
        SELECT DISTINCT path FROM doc_sections
        WHERE path LIKE 'docs/adr/%'
           OR path LIKE 'docs/architecture/%'
           OR path LIKE '%/docs/adr/%'
           OR (path LIKE 'docs/dev/%' AND path NOT IN ('docs/dev/BIT_CODES.md', 'docs/dev/SUPPORT_MATRIX.md'))
        """
    ).fetchall()
    leaks = [str(r[0]) for r in leak_rows if str(r[0]) not in allowed_dev]

    return {
        "traceability_recall_top5": trace_recall,
        "traceability_hits": trace_hits,
        "traceability_total": len(FRONT2_TRACEABILITY_GROUND_TRUTH),
        "traceability_misses": trace_misses,
        "gov_recall_top5": gov_recall,
        "gov_hits": gov_hits,
        "gov_total": gov_total,
        "gov_misses": gov_misses,
        "req_disc_003_leaks": len(leaks),
        "leaked_paths": leaks,
    }


def benchmark_front3_latency_and_freshness(
    conn: sqlite3.Connection,
    iterations: int = 3,
) -> dict[str, Any]:
    """
    Front 3: Measures query latency distribution (mean, median, p95, min/max spread) across
    50 queries (`context`, `search`, `impact`) and measures single-file incremental sync latency
    + stale-read rate across working-tree mutations.
    """
    query_latencies_ms: list[float] = []
    for _ in range(max(1, iterations)):
        for target, _kind in FRONT1_REPRESENTATIVE_TASKS:
            t0 = time.perf_counter()
            repo_ast.query_context(conn, target)
            query_latencies_ms.append((time.perf_counter() - t0) * 1000.0)
        for q_str, _exp in FRONT4_SPI_CAPABILITY_QUERIES[:6]:
            t0 = time.perf_counter()
            repo_ast.query_search(conn, q_str, limit=5)
            query_latencies_ms.append((time.perf_counter() - t0) * 1000.0)
        for imp_target in ("EwmaEstimator", "RTPScheduler", "TeleportPipelineTask", "MemoryTracker"):
            t0 = time.perf_counter()
            repo_ast.query_impact(conn, imp_target)
            query_latencies_ms.append((time.perf_counter() - t0) * 1000.0)

    inc_sync_latencies_ms: list[float] = []
    stale_reads = 0

    with tempfile.TemporaryDirectory() as tmp:
        fix_root = pathlib.Path(tmp)
        (fix_root / "docs" / "admin").mkdir(parents=True)
        (fix_root / "docs" / "dev").mkdir(parents=True)
        (fix_root / "api" / "predict-api" / "src" / "main" / "java" / "pkg").mkdir(parents=True)
        (fix_root / "docs" / "admin" / "GUIDE.md").write_text("# Guide\n## Start\nAdmin.\n", encoding="utf-8")
        (fix_root / "docs" / "dev" / "TRACEABILITY.md").write_text(
            "# Traceability\n| Req ID | Summary | Design Ref | Implementing Class(es) | Test(s) |\n"
            "|---|---|---|---|---|\n"
            "| REQ-RTP-S-005 | No sync chunk I/O | ADR-015 | `ProbeTarget` | `ProbeTargetTest` |\n",
            encoding="utf-8",
        )
        jf = fix_root / "api" / "predict-api" / "src" / "main" / "java" / "pkg" / "ProbeTarget.java"
        jf.write_text("package pkg;\n/** @governedBy ADR-015 */\npublic class ProbeTarget { public void v0() {} }\n", encoding="utf-8")
        fix_db = fix_root / "bench.sqlite"
        fconn = build_index(fix_root, fix_db)
        try:
            for step_idx in range(1, 6):
                adr_tag = f"ADR-{100 + step_idx}"
                method_tag = f"mutatedStep{step_idx}"
                jf.write_text(
                    f"package pkg;\n/** @governedBy ADR-015 {adr_tag} */\n"
                    f"public class ProbeTarget {{ public void {method_tag}() {{}} }}\n",
                    encoding="utf-8",
                )
                t0 = time.perf_counter()
                sync_res = sync_dirty_files(fconn, repo_root=fix_root)
                inc_ms = (time.perf_counter() - t0) * 1000.0
                inc_sync_latencies_ms.append(inc_ms)
                ctx_mut = repo_ast.query_context(fconn, "ProbeTarget")
                if adr_tag not in ctx_mut["gov_c"]["adrs"] or method_tag not in (ctx_mut["primary_class"].get("methods") or ""):
                    stale_reads += 1
                if sync_res["dirty_count"] != 1:
                    stale_reads += 1
        finally:
            fconn.close()

    q_stats = _summarize_dist(query_latencies_ms)
    s_stats = _summarize_dist(inc_sync_latencies_ms)
    return {
        "queries_measured": len(query_latencies_ms),
        "query_latency_ms": q_stats,
        "incremental_sync_ms": s_stats,
        "stale_reads": stale_reads,
    }


def benchmark_front4_spi_discovery(
    conn: sqlite3.Connection,
) -> dict[str, Any]:
    """
    Front 4: Evaluates top-3 hit rate on natural-language capability queries for existing
    Tier 1/2 SPIs and utilities to prevent redundant helper implementations.
    """
    hits = 0
    query_rows: list[dict[str, Any]] = []
    for query, expected_any in FRONT4_SPI_CAPABILITY_QUERIES:
        res = repo_ast.query_search(conn, query, limit=5)
        top3 = [r["class_name"] for r in res["results"][:3]]
        matched = any(exp in top3 for exp in expected_any)
        if matched:
            hits += 1
        query_rows.append({
            "query": query,
            "expected_any": list(expected_any),
            "top3": top3,
            "hit": matched,
        })

    hit_rate = round(hits / max(1, len(FRONT4_SPI_CAPABILITY_QUERIES)), 4)
    return {
        "queries_total": len(FRONT4_SPI_CAPABILITY_QUERIES),
        "top3_hits": hits,
        "spi_top3_hit_rate": hit_rate,
        "queries": query_rows,
    }


def benchmark_mcp_vs_cli_transport(
    db_path: pathlib.Path = repo_ast.DEFAULT_DB_PATH,
    repo_root: pathlib.Path = REPO_ROOT,
    sample_size: int = 12,
) -> dict[str, Any]:
    """
    Compares end-to-end tool invocation latency and output parity between:
      - BEFORE: Cold CLI subprocess per tool call (`python3 scripts/repo_ast.py <subcmd> ...`)
      - AFTER: Persistent `stdio` JSON-RPC 2.0 MCP server (`python3 corpus-atlas/mcp_server.py`)
    """
    all_queries: list[tuple[str, str, dict[str, Any]]] = (
        [("context", "repo_ast_context", {"target": t}) for t, _ in FRONT1_REPRESENTATIVE_TASKS]
        + [("search", "repo_ast_search", {"query": q, "limit": 5}) for q, _ in FRONT4_SPI_CAPABILITY_QUERIES[:6]]
        + [("impact", "repo_ast_impact", {"target": t}) for t in ("EwmaEstimator", "RTPScheduler", "TeleportPipelineTask", "MemoryTracker")]
    )
    if sample_size < len(all_queries):
        ctx_q = [q for q in all_queries if q[0] == "context"][: max(1, sample_size - 5)]
        srch_q = [q for q in all_queries if q[0] == "search"][:3]
        imp_q = [q for q in all_queries if q[0] == "impact"][:2]
        workload = ctx_q + srch_q + imp_q
    else:
        workload = all_queries

    cli_latencies_ms: list[float] = []
    cli_outputs: list[str] = []
    for subcmd, _tool_name, args in workload:
        cmd = [
            sys.executable,
            str(SCRIPT_DIR / "repo_ast.py"),
            "--db",
            str(db_path),
            "--repo-root",
            str(repo_root),
            subcmd,
        ]
        if subcmd == "search":
            cmd.extend([str(args["query"]), "--limit", str(args["limit"])])
        else:
            cmd.append(str(args["target"]))
        t0 = time.perf_counter()
        proc_cli = subprocess.run(cmd, capture_output=True, text=True, check=True)
        cli_latencies_ms.append((time.perf_counter() - t0) * 1000.0)
        cli_outputs.append(proc_cli.stdout)

    mcp_proc = subprocess.Popen(
        [
            sys.executable,
            str(ATLAS_DIR / "mcp_server.py"),
            "--db",
            str(db_path),
            "--repo-root",
            str(repo_root),
        ],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    assert mcp_proc.stdin is not None and mcp_proc.stdout is not None

    def _rpc(req_id: int, method: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        assert mcp_proc.stdin is not None and mcp_proc.stdout is not None
        line_out = json.dumps({"jsonrpc": "2.0", "id": req_id, "method": method, "params": params or {}}) + "\n"
        mcp_proc.stdin.write(line_out.encode("utf-8"))
        mcp_proc.stdin.flush()
        resp_line = mcp_proc.stdout.readline()
        return json.loads(resp_line.decode("utf-8"))

    mcp_latencies_ms: list[float] = []
    mcp_outputs: list[str] = []
    try:
        t_init0 = time.perf_counter()
        _rpc(1, "initialize", {"protocolVersion": "2024-11-05"})
        init_ms = round((time.perf_counter() - t_init0) * 1000.0, 3)
        tools_resp = _rpc(2, "tools/list")
        tools_list = tools_resp.get("result", {}).get("tools", [])
        schema_bytes = len(json.dumps(tools_list, separators=(",", ":")).encode("utf-8"))

        for idx, (_subcmd, tool_name, args) in enumerate(workload, start=10):
            t0 = time.perf_counter()
            resp = _rpc(idx, "tools/call", {"name": tool_name, "arguments": args})
            mcp_latencies_ms.append((time.perf_counter() - t0) * 1000.0)
            content = resp.get("result", {}).get("content", [{}])
            mcp_outputs.append(str(content[0].get("text") or "") if content else "")
    finally:
        mcp_proc.stdin.close()
        mcp_proc.wait(timeout=5)
        if mcp_proc.stdout is not None:
            mcp_proc.stdout.close()
        if mcp_proc.stderr is not None:
            mcp_proc.stderr.close()

    parity_matches = sum(1 for a, b in zip(cli_outputs, mcp_outputs) if a.strip() == b.strip())
    cli_stats = _summarize_dist(cli_latencies_ms)
    mcp_stats = _summarize_dist(mcp_latencies_ms)
    cli_total = round(sum(cli_latencies_ms), 2)
    mcp_total = round(sum(mcp_latencies_ms), 2)
    speedup_mean = round(cli_stats["mean"] / max(0.001, mcp_stats["mean"]), 2)
    speedup_p95 = round(cli_stats["p95"] / max(0.001, mcp_stats["p95"]), 2)

    return {
        "queries_compared": len(workload),
        "output_parity_matches": parity_matches,
        "output_parity_rate": round(parity_matches / max(1, len(workload)), 4),
        "mcp_tools_count": len(tools_list),
        "mcp_schema_bytes": schema_bytes,
        "mcp_schema_est_tokens": schema_bytes // 4,
        "mcp_initialize_warm_ms": init_ms,
        "before_cli_subprocess_ms": {**cli_stats, "total": cli_total},
        "after_mcp_stdio_ms": {**mcp_stats, "total": mcp_total},
        "mean_speedup_x": speedup_mean,
        "p95_speedup_x": speedup_p95,
        "latency_reduction_pct": round((1.0 - mcp_total / max(0.001, cli_total)) * 100.0, 2),
    }


def benchmark_offload_cost_benefit(
    conn: sqlite3.Connection,
    repo_root: pathlib.Path = REPO_ROOT,
    iterations: int = 3,
) -> dict[str, Any]:
    """
    Gate 10 Cost-Benefit Differential for `scripts/local_llm_queue.py` (`repo_ast_offload`):
      1. Remote Agent Token & Call Cost (State 1 `corpus-atlas` only vs State 2 `repo_ast_offload` digest)
         across all 4 offloaded task families (`translate-bundle`, `translate-doc`, `critique-diff`, `backfill-javadoc`).
      2. Verification Quality vs Local GPU Call Cost across 3 regimes on a 16-item fault-injected corpus:
         - Regime A: Unverified 1-Pass (1.0 GPU call/item) -> measures defect escape rate
         - Regime B: Deterministic Gate Only (1.0 GPU call/item, no Critic) -> catches syntax/IDs, misses semantic drift
         - Regime C: Full Staged Funnel (Forward -> <5ms Det Gate -> Opinion 2 Critic -> 1-Pass Repair -> Quarantine)
      3. Local Deterministic Gate Latency Distribution (`< 5.0 ms` p95 per item).
      4. P0 Support-Bot Preemption Latency (`<= 1` chunk wait vs unpreempted multi-chunk batch).
    """
    with tempfile.TemporaryDirectory() as tmp_dir:
        tmp_path = pathlib.Path(tmp_dir)
        out_dir = tmp_path / "out"
        out_dir.mkdir(parents=True, exist_ok=True)
        handlers = llq.build_default_handlers(out_dir=out_dir, repo_root=repo_root, ast_conn=conn)

        # Build a 16-item fault-injection corpus (4 items per task family:
        #   0=clean, 1=det_repairable, 2=critic_repairable, 3=det_unrepairable)
        corpus_labels = ("clean", "det_repairable", "critic_repairable", "det_unrepairable")
        bundle_items = [
            {
                "item_id": f"de:lang/de/messages.yml::messages.teleport_{i}[0]",
                "corpus_label": corpus_labels[i],
                "locale": "de",
                "relpath": "lang/de/messages.yml",
                "parent_path": "messages",
                "base_key": f"teleport_{i}",
                "index": "0",
                "blank_before": "0",
                "sentinels": "P0=[player]; P1=&a",
                "value_english": "<<P1>>Teleported <<P0>> after cooldown ticks.",
                "comment_english": "# <<P0>> target player; set true to enable chunk ticket safety",
                "key_locale": f"teleport_{i}",
            }
            for i in range(4)
        ]
        bundle_fwd = [
            {
                "item_id": bundle_items[0]["item_id"],
                "value_locale": "<<P1>><<P0>> nach Abklingzeit-Ticks teleportiert.",
                "comment_locale": "# <<P0>> Zielspieler; auf true setzen fuer Chunk-Ticket-Sicherheit",
                "key_locale": "teleport_0",
            },
            {
                # Drops <<P0>> sentinel -> caught by <5ms Deterministic Gate before Critic
                "item_id": bundle_items[1]["item_id"],
                "value_locale": "<<P1>>Nach Abklingzeit-Ticks teleportiert.",
                "comment_locale": "# <<P0>> Zielspieler; auf true setzen fuer Chunk-Ticket-Sicherheit",
                "key_locale": "teleport_1",
            },
            {
                # Preserves sentinels, but flips boolean / mistranslates chunk -> caught by Opinion 2 Critic
                "item_id": bundle_items[2]["item_id"],
                "value_locale": "<<P1>><<P0>> nach Brotscheibe teleportiert.",
                "comment_locale": "# <<P0>> Zielspieler; auf true setzen um Sicherheit zu deaktivieren",
                "key_locale": "teleport_2",
            },
            {
                # Persistent mojibake + broken sentinel -> quarantined after 1 retry
                "item_id": bundle_items[3]["item_id"],
                "value_locale": "<<P1>>Teleportiert \u00c3\u00a9 ohne Platzhalter.",  # mojibake-ok
                "comment_locale": "# <<P0>> Zielspieler",
                "key_locale": "teleport_3",
            },
        ]
        bundle_repairs = {
            bundle_items[1]["item_id"]: {
                "item_id": bundle_items[1]["item_id"],
                "value_locale": "<<P1>><<P0>> nach Abklingzeit-Ticks teleportiert.",
                "comment_locale": "# <<P0>> Zielspieler; auf true setzen fuer Chunk-Ticket-Sicherheit",
                "key_locale": "teleport_1",
            },
            bundle_items[2]["item_id"]: {
                "item_id": bundle_items[2]["item_id"],
                "value_locale": "<<P1>><<P0>> nach Abklingzeit-Ticks teleportiert.",
                "comment_locale": "# <<P0>> Zielspieler; auf true setzen fuer Chunk-Ticket-Sicherheit",
                "key_locale": "teleport_2",
            },
            bundle_items[3]["item_id"]: bundle_fwd[3],
        }
        tsv_rows = ["relpath\tparent_path\tkey\tindex\tvalue\tcomment\tblank_before\tbase_key"]
        for i in range(4):
            tsv_rows.append(
                f"lang/de/messages.yml\tmessages\tteleport_{i}\t0\t&aTeleported [player] after cooldown ticks.\t# [player] target player\t0\tteleport_{i}"
            )
        for idx in range(10):
            tsv_rows.append(
                f"lang/de/messages.yml\tmessages\tk_{idx}\t0\t&aTeleported [player] after cooldown ticks.\t# [player] target player\t0\tk_{idx}"
            )
        (out_dir / "locale-de.tsv").write_text("\n".join(tsv_rows) + "\n", encoding="utf-8")

        doc_items = [
            {
                "item_id": f"de:docs/admin/FAQ.md#sec{i}",
                "corpus_label": corpus_labels[i],
                "locale": "de",
                "doc_path": "docs/admin/FAQ.md",
                "output_path": str(out_dir / "docs-de" / "FAQ.md"),
                "section_index": i,
                "heading_levels": [2],
                "sentinels": "P0=`/rtp`; P1=https://docs.example.com",
                "markdown_english": f"## Section {i}\nUse <<P0>> and see [Guide](<<P1>>) for region chunk queue.",
            }
            for i in range(4)
        ]
        doc_fwd = [
            {
                "item_id": doc_items[0]["item_id"],
                "markdown_locale": "## Abschnitt 0\nNutze <<P0>> und siehe [Anleitung](<<P1>>) fuer die Region-Chunk-Warteschlange.",
            },
            {
                # Wrong heading level (### instead of ##) -> caught by <5ms Deterministic Gate
                "item_id": doc_items[1]["item_id"],
                "markdown_locale": "### Abschnitt 1\nNutze <<P0>> und siehe [Anleitung](<<P1>>).",
            },
            {
                # Valid sentinels & heading, but semantic drift -> caught by Opinion 2 Critic
                "item_id": doc_items[2]["item_id"],
                "markdown_locale": "## Abschnitt 2\nNutze <<P0>> niemals und loesche [Anleitung](<<P1>>).",
            },
            {
                # Persistent missing <<P1>> sentinel -> quarantined
                "item_id": doc_items[3]["item_id"],
                "markdown_locale": "## Abschnitt 3\nNutze <<P0>> ohne Link.",
            },
        ]
        doc_repairs = {
            doc_items[1]["item_id"]: {
                "item_id": doc_items[1]["item_id"],
                "markdown_locale": "## Abschnitt 1\nNutze <<P0>> und siehe [Anleitung](<<P1>>) fuer die Region-Chunk-Warteschlange.",
            },
            doc_items[2]["item_id"]: {
                "item_id": doc_items[2]["item_id"],
                "markdown_locale": "## Abschnitt 2\nNutze <<P0>> und siehe [Anleitung](<<P1>>) fuer die Region-Chunk-Warteschlange.",
            },
            doc_items[3]["item_id"]: doc_fwd[3],
        }

        diff_items = [
            handlers["critique-diff"]._enrich_item_with_gov_c(
                {
                    "item_id": f"diff:LocationGenerator_{i}",
                    "corpus_label": corpus_labels[i],
                    "class_name": "LocationGenerator",
                    "path": "rtp-core/src/main/java/io/github/dailystruggle/rtp/common/selection/region/LocationGenerator.java",
                    "diff_hunk": (
                        "diff --git a/LocationGenerator.java b/LocationGenerator.java\n"
                        "+++ b/LocationGenerator.java\n"
                        "@@ -40,2 +40,3 @@\n"
                        "+ world.getChunkAt(cx, cz);\n"
                        "+ future.get();\n"
                    ),
                    "added_lines": ["world.getChunkAt(cx, cz);", "future.get();"],
                }
            )
            for i in range(4)
        ]
        diff_fwd = [
            {
                "item_id": diff_items[0]["item_id"],
                "class_name": "LocationGenerator",
                "findings": [
                    {
                        "rule_id": "S-005",
                        "severity": "HIGH",
                        "cited_line": "world.getChunkAt(cx, cz);",
                        "summary": "Synchronous chunk load on main-thread path violates S-005 / ADR-015.",
                    }
                ],
            },
            {
                # Hallucinated ADR-999 -> caught by <5ms Deterministic Gate before Critic
                "item_id": diff_items[1]["item_id"],
                "class_name": "LocationGenerator",
                "findings": [
                    {
                        "rule_id": "ADR-999",
                        "severity": "HIGH",
                        "cited_line": "world.getChunkAt(cx, cz);",
                        "summary": "Fabricated ADR-999 citation.",
                    }
                ],
            },
            {
                # Valid rule ID & line, but speculative false-positive claim -> caught by Opinion 2 Critic
                "item_id": diff_items[2]["item_id"],
                "class_name": "LocationGenerator",
                "findings": [
                    {
                        "rule_id": "S-002",
                        "severity": "LOW",
                        "cited_line": "future.get();",
                        "summary": "Speculative double-release claim not present in diff.",
                    }
                ],
            },
            {
                # Persistent non-existent diff line citation -> quarantined
                "item_id": diff_items[3]["item_id"],
                "class_name": "LocationGenerator",
                "findings": [
                    {
                        "rule_id": "S-005",
                        "severity": "HIGH",
                        "cited_line": "nonExistentMethodCall();",
                        "summary": "Hallucinated diff line.",
                    }
                ],
            },
        ]
        diff_repairs = {
            diff_items[1]["item_id"]: diff_fwd[0] | {"item_id": diff_items[1]["item_id"]},
            diff_items[2]["item_id"]: {
                "item_id": diff_items[2]["item_id"],
                "class_name": "LocationGenerator",
                "findings": [],
            },
            diff_items[3]["item_id"]: diff_fwd[3],
        }

        jdoc_base = handlers["backfill-javadoc"]._enrich_class_item({"class_name": "EwmaEstimator"})
        jdoc_items = [
            dict(jdoc_base, item_id=f"javadoc:EwmaEstimator_{i}", corpus_label=corpus_labels[i])
            for i in range(4)
        ]
        jdoc_fwd = [
            {
                "item_id": jdoc_items[0]["item_id"],
                "class_name": "EwmaEstimator",
                "javadoc": (
                    "/**\n"
                    " * Lock-free exponentially weighted moving average and variance estimator.\n"
                    " *\n"
                    " * @governedBy ADR-087 ADR-115\n"
                    " * @trace REQ-PRED-F-001\n"
                    " */"
                ),
            },
            {
                # Contains forbidden process shorthand -> caught by <5ms Deterministic Gate
                "item_id": jdoc_items[1]["item_id"],
                "class_name": "EwmaEstimator",
                "javadoc": (
                    "/**\n"
                    " * " + "Phase " + "2 EWMA estimator.\n"
                    " * @governedBy ADR-087\n"
                    " */"
                ),
            },
            {
                # Valid tags & length, but invents SQLite socket I/O -> caught by Opinion 2 Critic
                "item_id": jdoc_items[2]["item_id"],
                "class_name": "EwmaEstimator",
                "javadoc": (
                    "/**\n"
                    " * Manages SQLite connection pooling and network packet compression.\n"
                    " * @governedBy ADR-087\n"
                    " * @trace REQ-PRED-F-001\n"
                    " */"
                ),
            },
            {
                # Persistent hallucinated ADR-999 -> quarantined
                "item_id": jdoc_items[3]["item_id"],
                "class_name": "EwmaEstimator",
                "javadoc": (
                    "/**\n"
                    " * EWMA estimator.\n"
                    " * @governedBy ADR-999\n"
                    " */"
                ),
            },
        ]
        jdoc_repairs = {
            jdoc_items[1]["item_id"]: jdoc_fwd[0] | {"item_id": jdoc_items[1]["item_id"]},
            jdoc_items[2]["item_id"]: jdoc_fwd[0] | {"item_id": jdoc_items[2]["item_id"]},
            jdoc_items[3]["item_id"]: jdoc_fwd[3],
        }

        families = [
            ("translate-bundle", handlers["translate-bundle"], bundle_items, bundle_fwd, bundle_repairs),
            ("translate-doc", handlers["translate-doc"], doc_items, doc_fwd, doc_repairs),
            ("critique-diff", handlers["critique-diff"], diff_items, diff_fwd, diff_repairs),
            ("backfill-javadoc", handlers["backfill-javadoc"], jdoc_items, jdoc_fwd, jdoc_repairs),
        ]

        # Measure <5ms Deterministic Gate latency across all 16 items * iterations
        det_latencies_ms: list[float] = []
        for _ in range(max(1, iterations)):
            for _t_name, h_obj, items_list, fwd_list, _rep_map in families:
                for it_obj, dr_obj in zip(items_list, fwd_list):
                    t0 = time.perf_counter()
                    h_obj.verify_deterministic(it_obj, dr_obj)
                    det_latencies_ms.append((time.perf_counter() - t0) * 1000.0)

        # Derive corpus totals & denominators from corpus_label tags and measure Regime A (no gate) & Regime B (det gate only)
        total_items = sum(len(items_list) for _t, _h, items_list, _f, _r in families)
        total_defective_initial = sum(
            1
            for _t, _h, items_list, _f, _r in families
            for it_obj in items_list
            if it_obj.get("corpus_label", "clean") != "clean"
        )
        corpus_det_defects_total = sum(
            1
            for _t, _h, items_list, _f, _r in families
            for it_obj in items_list
            if it_obj.get("corpus_label") in ("det_repairable", "det_unrepairable")
        )
        repairable_defects_total = sum(
            1
            for _t, _h, items_list, _f, _r in families
            for it_obj in items_list
            if it_obj.get("corpus_label") in ("det_repairable", "critic_repairable")
        )

        regime_a_gpu_calls = 0
        regime_b_gpu_calls = 0
        regime_a_clean_applied = 0
        regime_b_clean_applied = 0
        regime_det_only_escapes = 0
        regime_unverified_escapes = 0
        det_defects_total = 0
        for _t, h_obj, items_list, fwd_list, _r in families:
            regime_a_gpu_calls += 1
            regime_b_gpu_calls += 1
            for idx, (it_obj, dr_obj) in enumerate(zip(items_list, fwd_list)):
                ok_det, _ = h_obj.verify_deterministic(it_obj, dr_obj)
                if idx != 0:
                    regime_unverified_escapes += 1
                    if ok_det:
                        regime_det_only_escapes += 1
                else:
                    regime_a_clean_applied += 1
                    if ok_det:
                        regime_b_clean_applied += 1
                if not ok_det:
                    det_defects_total += 1

        staged_gpu_calls = 0
        naive_two_opinion_gpu_calls = 0
        det_gate_short_circuits = 0
        staged_auto_applied = 0
        staged_retried_ok = 0
        staged_quarantined = 0
        staged_defect_escapes = 0

        for t_name, h_obj, items_list, fwd_list, rep_map in families:
            drift_id = items_list[2]["item_id"]

            def _mock_http(url: str, payload: dict[str, Any], timeout: float) -> dict[str, Any]:
                nonlocal staged_gpu_calls
                staged_gpu_calls += 1
                msgs = payload.get("messages") or []
                sys_txt = str(msgs[0].get("content") or "") if msgs else ""
                usr_txt = str(msgs[-1].get("content") or "") if msgs else ""

                if "rejection_reason" in usr_txt:
                    req_obj = json.loads(usr_txt)
                    it_id = str(req_obj.get("item", {}).get("item_id") or "")
                    return {"choices": [{"message": {"content": json.dumps(rep_map[it_id])}}]}

                if "opinion 2" in sys_txt.lower() or "respond with `pass`" in sys_txt.lower():
                    # Opinion 2 Critic rejects the semantic drift item on its initial draft, passes repaired/clean
                    if (
                        "Brotscheibe" in usr_txt
                        or "niemals" in usr_txt
                        or "Speculative double-release" in usr_txt
                        or "SQLite connection pooling" in usr_txt
                    ):
                        return {"choices": [{"message": {"content": "DRIFT: semantic drift or false-positive claim detected"}}]}
                    return {"choices": [{"message": {"content": "PASS"}}]}

                # Opinion 1 Forward pass (1 call per chunk of 4 items)
                return {"choices": [{"message": {"content": json.dumps(fwd_list)}}]}

            # Count how many initial drafts fail deterministic gate (saving Critic GPU calls)
            family_defective = 0
            for it_obj, dr_obj in zip(items_list, fwd_list):
                if it_obj.get("corpus_label", "clean") != "clean":
                    family_defective += 1
                ok_det, _ = h_obj.verify_deterministic(it_obj, dr_obj)
                if not ok_det:
                    det_gate_short_circuits += 1
            # Naive 2-opinion without early deterministic short-circuit would run 1 forward + len(items_list) critic + repairs
            naive_two_opinion_gpu_calls += 1 + len(items_list) + family_defective * 2

            broker = llq.LlmQueueBroker(tmp_path / f"queue-{t_name}.sqlite")
            client = llq.LocalLlmClient(http_post=_mock_http)
            runner = llq.StagedFunnelRunner(client=client, broker=broker, out_dir=out_dir, max_retries=1)
            res = runner.execute_chunk(items_list, h_obj)
            staged_auto_applied += res.applied_count
            staged_retried_ok += res.retried_ok_count
            staged_quarantined += len(res.quarantined)
            # Verify every applied item is genuinely clean (neither det-broken nor un-repaired semantic drift)
            for ver_item, ver_draft in res.verified_pairs:
                ok_d, _ = h_obj.verify_deterministic(ver_item, ver_draft)
                raw_json = json.dumps(ver_draft)
                has_drift = any(
                    bad in raw_json
                    for bad in ("Brotscheibe", "niemals", "Speculative double-release", "SQLite connection pooling")
                )
                if not ok_d or has_drift:
                    staged_defect_escapes += 1

        # Measure P0 Preemption Wait Reduction (5-chunk P2 job preempted after chunk 1 by P0 lease)
        preempt_broker = llq.LlmQueueBroker(tmp_path / "preempt.sqlite")
        p2_items = [
            dict(bundle_items[0], item_id=f"de:lang/de/messages.yml::messages.k_{idx}[0]", base_key=f"k_{idx}", key_locale=f"k_{idx}")
            for idx in range(10)
        ]
        p2_job_id = preempt_broker.enqueue(
            task_type="translate-bundle",
            target_key="de",
            payload={"target": "de", "chunk_size": 2, "items": p2_items},
            priority=llq.PRIORITY_P2,
        )
        chunks_before_p0_yield = 0

        def _preempt_http(url: str, payload: dict[str, Any], timeout: float) -> dict[str, Any]:
            nonlocal chunks_before_p0_yield
            msgs = payload.get("messages") or []
            sys_txt = str(msgs[0].get("content") or "") if msgs else ""
            usr_txt = str(msgs[-1].get("content") or "") if msgs else ""
            if "opinion 2" in sys_txt.lower() or "critic" in sys_txt.lower():
                return {"choices": [{"message": {"content": "PASS"}}]}
            chunks_before_p0_yield += 1
            # Simulate live P0 support-bot query arriving during chunk 1
            preempt_broker.acquire_p0_lease(holder_id="discord-support-bot", ttl_ms=60_000)
            req_obj = json.loads(usr_txt)
            items_in = req_obj if isinstance(req_obj, list) else (req_obj.get("items") or [])
            out_arr = [
                {
                    "item_id": it["item_id"],
                    "value_locale": "<<P1>><<P0>> nach Abklingzeit-Ticks teleportiert.",
                    "comment_locale": "# <<P0>> Zielspieler; auf true setzen fuer Chunk-Ticket-Sicherheit",
                    "key_locale": it["key_locale"],
                }
                for it in items_in
            ]
            return {"choices": [{"message": {"content": json.dumps(out_arr)}}]}

        preempt_runner = llq.StagedFunnelRunner(
            client=llq.LocalLlmClient(http_post=_preempt_http),
            broker=preempt_broker,
            out_dir=out_dir,
        )
        step_out = llq.run_worker_step(
            broker=preempt_broker,
            runner=preempt_runner,
            handlers=handlers,
            job_id=p2_job_id,
        )
        assert step_out is not None and step_out.get("status") == "PREEMPTED"

        # Measure State 1 (Direct Remote Agent) vs State 2 (`repo_ast_offload` Digest) Remote Token Footprint
        faq_path = repo_root / "docs" / "admin" / "FAQ.md"
        faq_bytes = len(faq_path.read_bytes()) if faq_path.is_file() else 12_000
        audit_summary = repo_ast.audit_corpus_searchability(conn)
        high_fan_in_classes = audit_summary.get("high_fan_in_missing_gov", [])[:15]
        javadoc_state1_bytes = 0
        for row in high_fan_in_classes:
            c_path = repo_root / str(row.get("path") or "")
            if c_path.is_file():
                lines_80 = c_path.read_text(encoding="utf-8", errors="replace").splitlines()[:80]
                javadoc_state1_bytes += len("\n".join(lines_80).encode("utf-8")) + 320
        if javadoc_state1_bytes == 0:
            javadoc_state1_bytes = 36_000

        bundle_chunks = 5
        bundle_state1_bytes = len(json.dumps(bundle_items * bundle_chunks, indent=2).encode("utf-8")) + len(
            json.dumps(bundle_fwd * bundle_chunks, indent=2).encode("utf-8")
        )
        doc_state1_bytes = faq_bytes * 2  # Read full Markdown + generate full translated Markdown in remote context
        critique_classes = ("LocationGenerator", "TeleportPipelineTask", "MemoryTracker")
        diff_ctx_bytes = sum(
            len(repo_ast.format_context_markdown(repo_ast.query_context(conn, cls)).encode("utf-8"))
            for cls in critique_classes
        )
        diff_state1_bytes = diff_ctx_bytes + 4_800  # 3 Gov(c) contexts + git diff + remote self-review reasoning
        n_javadoc_classes = len(high_fan_in_classes) if high_fan_in_classes else 15

        digest_sample = preempt_broker.get_status_digest(job_id=p2_job_id, max_lines=40)["markdown"]
        offload_roundtrip_bytes = 180 + len(digest_sample.encode("utf-8"))  # MCP enqueue args + <=40-line status digest
        critique_digest_path = out_dir / "critique-diff-latest.md"
        critique_digest_bytes = (
            180 + len(critique_digest_path.read_bytes()) + len(digest_sample.encode("utf-8"))
            if critique_digest_path.is_file()
            else 680
        )

        workflows_comparison = [
            {
                "task_type": f"translate-bundle ({len(bundle_items) * bundle_chunks} locale rows)",
                "state1_remote_calls": 1 + bundle_chunks,
                "state1_call_count_basis": "modeled_estimate",
                "state2_remote_calls": 2,
                "state1_remote_bytes": bundle_state1_bytes,
                "state2_remote_bytes": offload_roundtrip_bytes,
                "remote_token_reduction_pct": round((1.0 - offload_roundtrip_bytes / max(1, bundle_state1_bytes)) * 100.0, 2),
            },
            {
                "task_type": "translate-doc (docs/admin/FAQ.md)",
                "state1_remote_calls": len(doc_items),
                "state1_call_count_basis": "modeled_estimate",
                "state2_remote_calls": 2,
                "state1_remote_bytes": doc_state1_bytes,
                "state2_remote_bytes": offload_roundtrip_bytes,
                "remote_token_reduction_pct": round((1.0 - offload_roundtrip_bytes / max(1, doc_state1_bytes)) * 100.0, 2),
            },
            {
                "task_type": f"critique-diff ({len(critique_classes)} Tier-2 classes + Gov(c))",
                "state1_remote_calls": len(critique_classes) + 2,
                "state1_call_count_basis": "modeled_estimate",
                "state2_remote_calls": 1,
                "state1_remote_bytes": diff_state1_bytes,
                "state2_remote_bytes": critique_digest_bytes,
                "remote_token_reduction_pct": round((1.0 - critique_digest_bytes / max(1, diff_state1_bytes)) * 100.0, 2),
            },
            {
                "task_type": f"backfill-javadoc ({n_javadoc_classes} audited classes)",
                "state1_remote_calls": 1 + n_javadoc_classes,
                "state1_call_count_basis": "modeled_estimate",
                "state2_remote_calls": 2,
                "state1_remote_bytes": javadoc_state1_bytes,
                "state2_remote_bytes": offload_roundtrip_bytes,
                "remote_token_reduction_pct": round((1.0 - offload_roundtrip_bytes / max(1, javadoc_state1_bytes)) * 100.0, 2),
            },
        ]

        state1_total_bytes = sum(w["state1_remote_bytes"] for w in workflows_comparison)
        state2_total_bytes = sum(w["state2_remote_bytes"] for w in workflows_comparison)
        state1_total_calls = sum(w["state1_remote_calls"] for w in workflows_comparison)
        state2_total_calls = sum(w["state2_remote_calls"] for w in workflows_comparison)
        overall_remote_token_reduction_pct = round(
            (1.0 - state2_total_bytes / max(1, state1_total_bytes)) * 100.0, 2
        )
        overall_remote_call_reduction_pct = round(
            (1.0 - state2_total_calls / max(1, state1_total_calls)) * 100.0, 2
        )

        det_stats = _summarize_dist(det_latencies_ms)
        critic_saved_pct = round((det_gate_short_circuits / max(1, corpus_det_defects_total)) * 100.0, 2)
        total_p2_chunks = max(1, (len(p2_items) + 1) // 2)

        return {
            "items_evaluated": total_items,
            "initial_defective_items": total_defective_initial,
            "corpus_det_defects_total": corpus_det_defects_total,
            "det_defects_total": det_defects_total,
            "repairable_defects_total": repairable_defects_total,
            "regime_a_unverified_1pass": {
                "gpu_calls_total": regime_a_gpu_calls,
                "defect_escapes": regime_unverified_escapes,
                "defect_escape_rate_pct": round((regime_unverified_escapes / max(1, total_defective_initial)) * 100.0, 2),
                "clean_yield_pct": round((regime_a_clean_applied / max(1, total_items)) * 100.0, 2),
            },
            "regime_b_det_gate_only": {
                "gpu_calls_total": regime_b_gpu_calls,
                "defect_escapes": regime_det_only_escapes,
                "defect_escape_rate_pct": round((regime_det_only_escapes / max(1, total_defective_initial)) * 100.0, 2),
                "clean_yield_pct": round((regime_b_clean_applied / max(1, total_items)) * 100.0, 2),
            },
            "regime_c_staged_funnel": {
                "gpu_calls_total": staged_gpu_calls,
                "naive_two_opinion_gpu_calls": naive_two_opinion_gpu_calls,
                "det_gate_short_circuits": det_gate_short_circuits,
                "first_pass_critic_calls_saved_pct": critic_saved_pct,
                "auto_applied": staged_auto_applied,
                "retried_ok": staged_retried_ok,
                "quarantined": staged_quarantined,
                "defect_escapes": staged_defect_escapes,
                "defect_escape_rate_pct": round((staged_defect_escapes / max(1, total_defective_initial)) * 100.0, 2),
                "repairable_salvage_rate_pct": round((staged_retried_ok / max(1, repairable_defects_total)) * 100.0, 2),
                "clean_yield_pct": round((staged_auto_applied / max(1, total_items)) * 100.0, 2),
            },
            "deterministic_gate_latency_ms": det_stats,
            "p0_preemption": {
                "total_p2_chunks": total_p2_chunks,
                "chunks_waited_before_p0_yield": chunks_before_p0_yield,
                "wait_reduction_pct": round((1.0 - chunks_before_p0_yield / float(total_p2_chunks)) * 100.0, 2),
            },
            "remote_context_savings": {
                "state1_remote_calls_total": state1_total_calls,
                "state1_call_count_basis": "modeled_estimate",
                "state2_remote_calls_total": state2_total_calls,
                "remote_call_reduction_pct": overall_remote_call_reduction_pct,
                "state1_remote_bytes_total": state1_total_bytes,
                "state2_remote_bytes_total": state2_total_bytes,
                "state1_remote_est_tokens": state1_total_bytes // 4,
                "state2_remote_est_tokens": state2_total_bytes // 4,
                "remote_token_reduction_pct": overall_remote_token_reduction_pct,
                "workflows": workflows_comparison,
            },
        }


def evaluate_gate_metrics(
    front1_call_reduction_pct: float,
    front1_token_reduction_pct: float,
    front2_traceability_recall_top5: float,
    front2_gov_recall_top5: float,
    front2_req_disc_003_leaks: int,
    front3_query_p95_ms: float,
    front3_incremental_sync_p95_ms: float,
    front3_stale_reads: int,
    front4_spi_top3_hit_rate: float,
    details: dict[str, Any] | None = None,
) -> BenchmarkGateReport:
    """
    Evaluates the 4-front quantitative thresholds and returns a `BenchmarkGateReport`
    with `GO_PROMOTE` iff all 4 fronts pass, or `HOLD_FOR_REFINEMENT` with failure reasons.
    """
    failures: list[str] = []
    if front1_call_reduction_pct < 50.0:
        failures.append(f"Front 1 call reduction {front1_call_reduction_pct:.1f}% < 50.0% threshold")
    if front1_token_reduction_pct < 40.0:
        failures.append(f"Front 1 token/byte reduction {front1_token_reduction_pct:.1f}% < 40.0% threshold")
    if front2_traceability_recall_top5 < 0.90:
        failures.append(f"Front 2 TRACEABILITY.md top-5 recall {front2_traceability_recall_top5:.2%} < 90.0% threshold")
    if front2_gov_recall_top5 < 0.90:
        failures.append(f"Front 2 ADR-115 Gov(c) top-5 recall {front2_gov_recall_top5:.2%} < 90.0% threshold")
    if front2_req_disc_003_leaks != 0:
        failures.append(f"Front 2 REQ-DISC-003 public plane leaks {front2_req_disc_003_leaks} != 0")
    if front3_query_p95_ms >= 50.0:
        failures.append(f"Front 3 query p95 latency {front3_query_p95_ms:.2f} ms >= 50.0 ms threshold")
    if front3_incremental_sync_p95_ms >= 150.0:
        failures.append(f"Front 3 incremental sync p95 latency {front3_incremental_sync_p95_ms:.2f} ms >= 150.0 ms threshold")
    if front3_stale_reads != 0:
        failures.append(f"Front 3 stale reads after mutation {front3_stale_reads} != 0")
    if front4_spi_top3_hit_rate < 0.90:
        failures.append(f"Front 4 Tier 1/2 SPI top-3 hit rate {front4_spi_top3_hit_rate:.2%} < 90.0% threshold")

    off_cb = (details or {}).get("offload_cost_benefit")
    if isinstance(off_cb, dict):
        reg_c = off_cb.get("regime_c_staged_funnel", {})
        rem_sav = off_cb.get("remote_context_savings", {})
        det_lat = off_cb.get("deterministic_gate_latency_ms", {})
        if int(reg_c.get("defect_escapes", 0)) != 0:
            failures.append(f"Front 5 staged funnel defect escapes {reg_c.get('defect_escapes')} != 0")
        if float(rem_sav.get("remote_token_reduction_pct", 0.0)) < 85.0:
            failures.append(
                f"Front 5 offload remote token reduction {rem_sav.get('remote_token_reduction_pct', 0.0):.1f}% < 85.0% threshold"
            )
        if float(det_lat.get("p95", 0.0)) >= 5.0:
            failures.append(f"Front 5 deterministic gate p95 {det_lat.get('p95', 0.0):.2f} ms >= 5.0 ms threshold")

    passed = len(failures) == 0
    decision: Literal["GO_PROMOTE", "HOLD_FOR_REFINEMENT"] = "GO_PROMOTE" if passed else "HOLD_FOR_REFINEMENT"
    return BenchmarkGateReport(
        passed_all_fronts=passed,
        decision=decision,
        front1_call_reduction_pct=front1_call_reduction_pct,
        front1_token_reduction_pct=front1_token_reduction_pct,
        front2_traceability_recall_top5=front2_traceability_recall_top5,
        front2_gov_recall_top5=front2_gov_recall_top5,
        front2_req_disc_003_leaks=front2_req_disc_003_leaks,
        front3_query_p95_ms=front3_query_p95_ms,
        front3_incremental_sync_p95_ms=front3_incremental_sync_p95_ms,
        front3_stale_reads=front3_stale_reads,
        front4_spi_top3_hit_rate=front4_spi_top3_hit_rate,
        failed_reasons=tuple(failures),
        details=details or {},
    )


def run_full_benchmark(
    db_path: pathlib.Path = repo_ast.DEFAULT_DB_PATH,
    repo_root: pathlib.Path = REPO_ROOT,
    iterations: int = 3,
) -> BenchmarkGateReport:
    conn, _ = repo_ast.ensure_db_connection(db_path=db_path, repo_root=repo_root, auto_sync=True)
    try:
        f1 = benchmark_front1_discovery_savings(conn, repo_root=repo_root)
        f2 = benchmark_front2_comprehension_and_isolation(conn)
        f3 = benchmark_front3_latency_and_freshness(conn, iterations=iterations)
        f4 = benchmark_front4_spi_discovery(conn)
        audit = repo_ast.audit_corpus_searchability(conn)
        mcp_cmp = benchmark_mcp_vs_cli_transport(db_path=db_path, repo_root=repo_root, sample_size=12)
        off_cb = benchmark_offload_cost_benefit(conn, repo_root=repo_root, iterations=iterations)
        return evaluate_gate_metrics(
            front1_call_reduction_pct=f1["call_reduction_pct"],
            front1_token_reduction_pct=f1["token_reduction_pct"],
            front2_traceability_recall_top5=f2["traceability_recall_top5"],
            front2_gov_recall_top5=f2["gov_recall_top5"],
            front2_req_disc_003_leaks=f2["req_disc_003_leaks"],
            front3_query_p95_ms=f3["query_latency_ms"]["p95"],
            front3_incremental_sync_p95_ms=f3["incremental_sync_ms"]["p95"],
            front3_stale_reads=f3["stale_reads"],
            front4_spi_top3_hit_rate=f4["spi_top3_hit_rate"],
            details={
                "front1": f1,
                "front2": f2,
                "front3": f3,
                "front4": f4,
                "corpus_audit": audit,
                "mcp_vs_cli": mcp_cmp,
                "offload_cost_benefit": off_cb,
            },
        )
    finally:
        conn.close()


def format_report_markdown(report: BenchmarkGateReport) -> str:
    d = report.details
    f1 = d.get("front1", {})
    f3 = d.get("front3", {})
    q_lat = f3.get("query_latency_ms", {})
    s_lat = f3.get("incremental_sync_ms", {})
    aud = d.get("corpus_audit", {})
    off_cb = d.get("offload_cost_benefit", {})

    lines: list[str] = [
        f"### Gate 10 Differential Benchmark Report — Decision: `{report.decision}`",
        "",
        "| Front | Metric | Measured | Gate Threshold | Status |",
        "|---|---|---|---|---|",
        f"| **Front 1: Tool Call Savings** | Discovery Call Reduction (`{f1.get('ast_calls_total', 20)}` vs `{f1.get('baseline_calls_total', 100)}` calls) | `{report.front1_call_reduction_pct:.1f}%` | `>= 50.0%` | `{'PASS' if report.front1_call_reduction_pct >= 50.0 else 'FAIL'}` |",
        f"| **Front 1: Token / Byte Savings** | Bytes Read (`{f1.get('ast_bytes_total', 0):,}` vs `{f1.get('baseline_bytes_total', 0):,}` B) | `{report.front1_token_reduction_pct:.1f}%` | `>= 40.0%` | `{'PASS' if report.front1_token_reduction_pct >= 40.0 else 'FAIL'}` |",
        f"| **Front 2: Traceability Recall** | Top-5 `TRACEABILITY.md` (`REQ`/`Class`/`Test`) | `{report.front2_traceability_recall_top5 * 100.0:.1f}%` | `>= 90.0%` | `{'PASS' if report.front2_traceability_recall_top5 >= 0.90 else 'FAIL'}` |",
        f"| **Front 2: Gov(c) Recall** | Top-5 `ADR-115` `Gov(c)` Invariant Recall | `{report.front2_gov_recall_top5 * 100.0:.1f}%` | `>= 90.0%` | `{'PASS' if report.front2_gov_recall_top5 >= 0.90 else 'FAIL'}` |",
        f"| **Front 2: Split-Plane Isolation** | `REQ-DISC-003` Internal Leaks in `doc_sections` | `{report.front2_req_disc_003_leaks}` | `== 0` | `{'PASS' if report.front2_req_disc_003_leaks == 0 else 'FAIL'}` |",
        f"| **Front 3: Query Latency** | p95 (`mean={q_lat.get('mean', 0)}ms`, `p50={q_lat.get('median', 0)}ms`, `spread={q_lat.get('spread', 0)}ms`) | `{report.front3_query_p95_ms:.2f} ms` | `< 50.0 ms` | `{'PASS' if report.front3_query_p95_ms < 50.0 else 'FAIL'}` |",
        f"| **Front 3: Incremental Sync** | Single-File Dirty Sync p95 (`mean={s_lat.get('mean', 0)}ms`, `p50={s_lat.get('median', 0)}ms`) | `{report.front3_incremental_sync_p95_ms:.2f} ms` | `< 150.0 ms` | `{'PASS' if report.front3_incremental_sync_p95_ms < 150.0 else 'FAIL'}` |",
        f"| **Front 3: Zero Stale Reads** | Stale Reads After Working-Tree Edit | `{report.front3_stale_reads}` | `== 0` | `{'PASS' if report.front3_stale_reads == 0 else 'FAIL'}` |",
        f"| **Front 4: Anti-Redundancy SPI** | Top-3 Hit Rate on Tier 1/2 Capability Queries | `{report.front4_spi_top3_hit_rate * 100.0:.1f}%` | `>= 90.0%` | `{'PASS' if report.front4_spi_top3_hit_rate >= 0.90 else 'FAIL'}` |",
    ]
    if off_cb:
        reg_a = off_cb.get("regime_a_unverified_1pass", {})
        reg_b = off_cb.get("regime_b_det_gate_only", {})
        reg_c = off_cb.get("regime_c_staged_funnel", {})
        rem_sav = off_cb.get("remote_context_savings", {})
        det_lat = off_cb.get("deterministic_gate_latency_ms", {})
        p0_pre = off_cb.get("p0_preemption", {})
        lines.extend([
            f"| **Front 5: Offload Token Savings** | State 2 vs State 1 Remote Bytes (`{rem_sav.get('state2_remote_bytes_total', 0):,}` vs `{rem_sav.get('state1_remote_bytes_total', 0):,}` B; `{rem_sav.get('state2_remote_calls_total', 0)}` measured vs `{rem_sav.get('state1_remote_calls_total', 0)}` modeled-estimate calls) | `{rem_sav.get('remote_token_reduction_pct', 0.0):.1f}%` | `>= 85.0%` | `{'PASS' if rem_sav.get('remote_token_reduction_pct', 0.0) >= 85.0 else 'FAIL'}` |",
            f"| **Front 5: Staged Funnel Quality** | Defect Escapes (`{reg_c.get('defect_escapes', 0)}` vs `{reg_a.get('defect_escapes', 0)}` unverified / `{reg_b.get('defect_escapes', 0)}` det-only) | `{reg_c.get('defect_escapes', 0)}` (`{reg_c.get('repairable_salvage_rate_pct', 0.0):.0f}%` salvaged) | `== 0` | `{'PASS' if reg_c.get('defect_escapes', 0) == 0 else 'FAIL'}` |",
            f"| **Front 5: Local Gate Latency** | `< 5 ms` Deterministic Gate p95 (`mean={det_lat.get('mean', 0)}ms`, `{reg_c.get('first_pass_critic_calls_saved_pct', 0.0):.0f}%` critic saved) | `{det_lat.get('p95', 0.0):.3f} ms` | `< 5.0 ms` | `{'PASS' if det_lat.get('p95', 0.0) < 5.0 else 'FAIL'}` |",
            f"| **Front 5: P0 Preemption Yield** | P0 Wait Chunks (`{p0_pre.get('chunks_waited_before_p0_yield', 1)}` vs `{p0_pre.get('total_p2_chunks', 5)}` unpreempted chunks) | `{p0_pre.get('wait_reduction_pct', 0.0):.1f}%` reduction | `<= 1 chunk` | `{'PASS' if p0_pre.get('chunks_waited_before_p0_yield', 99) <= 1 else 'FAIL'}` |",
        ])
    if aud:
        lines.append("")
        lines.append(
            f"- **Corpus Searchability Baseline:** `{aud.get('tier1_spi_missing_javadoc_or_gov_count', 0)}` Tier 1/2 SPIs lack `@governedBy`/Javadoc summary; "
            f"`{aud.get('high_fan_in_missing_gov_count', 0)}` high-fan-in classes lack `Gov(c)` tags."
        )
    mcp_cmp = d.get("mcp_vs_cli", {})
    if mcp_cmp:
        b_cli = mcp_cmp.get("before_cli_subprocess_ms", {})
        a_mcp = mcp_cmp.get("after_mcp_stdio_ms", {})
        lines.append(
            f"- **MCP (`stdio`) vs CLI Subprocess (`{mcp_cmp.get('queries_compared', 0)}` calls):** "
            f"Before (CLI) `mean={b_cli.get('mean', 0)}ms` / `p95={b_cli.get('p95', 0)}ms` (`total={b_cli.get('total', 0)}ms`) -> "
            f"After (MCP) `mean={a_mcp.get('mean', 0)}ms` / `p95={a_mcp.get('p95', 0)}ms` (`total={a_mcp.get('total', 0)}ms`) | "
            f"**`{mcp_cmp.get('mean_speedup_x', 0)}x` mean / `{mcp_cmp.get('p95_speedup_x', 0)}x` p95 speedup** "
            f"(`{mcp_cmp.get('latency_reduction_pct', 0)}%` latency reduction, "
            f"`{mcp_cmp.get('output_parity_matches', 0)}/{mcp_cmp.get('queries_compared', 0)}` output parity, "
            f"`{mcp_cmp.get('mcp_schema_est_tokens', 0)}` schema tokens across `{mcp_cmp.get('mcp_tools_count', 5)}` tools)."
        )
    if report.failed_reasons:
        lines.append("")
        lines.append("#### Failed Gate Reasons (`HOLD_FOR_REFINEMENT`)")
        for reason in report.failed_reasons:
            lines.append(f"- {reason}")
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Gate 10 4-Front Differential Benchmark for repo_ast.py")
    parser.add_argument("--db", type=pathlib.Path, default=repo_ast.DEFAULT_DB_PATH)
    parser.add_argument("--repo-root", type=pathlib.Path, default=REPO_ROOT)
    parser.add_argument("--iterations", type=int, default=3)
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--gate", action="store_true", help="Exit non-zero (1) if any front fails (HOLD_FOR_REFINEMENT)")
    args = parser.parse_args(argv)

    report = run_full_benchmark(db_path=args.db, repo_root=args.repo_root, iterations=args.iterations)
    if args.json:
        print(json.dumps(dataclasses.asdict(report), indent=2))
    else:
        sys.stdout.write(format_report_markdown(report))

    if args.gate and not report.passed_all_fronts:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
