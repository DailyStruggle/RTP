#!/usr/bin/env python3
"""Unit tests for the 4-front differential benchmark and Go/Hold gate (`scripts/bench_repo_ast.py`)."""
from __future__ import annotations

import pathlib
import sys
import unittest

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPTS_DIR = REPO_ROOT / "scripts"
ATLAS_DIR = REPO_ROOT / "corpus-atlas"
for _p in (SCRIPTS_DIR, ATLAS_DIR):
    if str(_p) not in sys.path:
        sys.path.insert(0, str(_p))

import bench_repo_ast  # noqa: E402


class TestBenchRepoAstGate(unittest.TestCase):
    def test_evaluate_gate_metrics_pass_and_synthetic_regressions(self) -> None:
        # 1. All 4 fronts meet thresholds -> GO_PROMOTE
        ok_report = bench_repo_ast.evaluate_gate_metrics(
            front1_call_reduction_pct=80.0,
            front1_token_reduction_pct=85.0,
            front2_traceability_recall_top5=1.0,
            front2_gov_recall_top5=0.95,
            front2_req_disc_003_leaks=0,
            front3_query_p95_ms=8.5,
            front3_incremental_sync_p95_ms=25.0,
            front3_stale_reads=0,
            front4_spi_top3_hit_rate=1.0,
        )
        self.assertTrue(ok_report.passed_all_fronts)
        self.assertEqual("GO_PROMOTE", ok_report.decision)
        self.assertEqual((), ok_report.failed_reasons)

        # 2. Synthetic regression on Front 1 (token reduction < 40%) -> HOLD_FOR_REFINEMENT
        f1_fail = bench_repo_ast.evaluate_gate_metrics(
            front1_call_reduction_pct=80.0,
            front1_token_reduction_pct=35.0,
            front2_traceability_recall_top5=1.0,
            front2_gov_recall_top5=1.0,
            front2_req_disc_003_leaks=0,
            front3_query_p95_ms=8.5,
            front3_incremental_sync_p95_ms=25.0,
            front3_stale_reads=0,
            front4_spi_top3_hit_rate=1.0,
        )
        self.assertFalse(f1_fail.passed_all_fronts)
        self.assertEqual("HOLD_FOR_REFINEMENT", f1_fail.decision)
        self.assertTrue(any("Front 1 token/byte reduction" in r for r in f1_fail.failed_reasons))

        # 3. Synthetic regression on Front 2 (REQ-DISC-003 leak > 0 or recall < 90%) -> HOLD_FOR_REFINEMENT
        f2_fail = bench_repo_ast.evaluate_gate_metrics(
            front1_call_reduction_pct=80.0,
            front1_token_reduction_pct=85.0,
            front2_traceability_recall_top5=0.85,
            front2_gov_recall_top5=1.0,
            front2_req_disc_003_leaks=1,
            front3_query_p95_ms=8.5,
            front3_incremental_sync_p95_ms=25.0,
            front3_stale_reads=0,
            front4_spi_top3_hit_rate=1.0,
        )
        self.assertFalse(f2_fail.passed_all_fronts)
        self.assertEqual("HOLD_FOR_REFINEMENT", f2_fail.decision)
        self.assertEqual(2, len(f2_fail.failed_reasons))

        # 4. Synthetic regression on Front 3 (query p95 >= 50ms or stale reads > 0) -> HOLD_FOR_REFINEMENT
        f3_fail = bench_repo_ast.evaluate_gate_metrics(
            front1_call_reduction_pct=80.0,
            front1_token_reduction_pct=85.0,
            front2_traceability_recall_top5=1.0,
            front2_gov_recall_top5=1.0,
            front2_req_disc_003_leaks=0,
            front3_query_p95_ms=55.0,
            front3_incremental_sync_p95_ms=25.0,
            front3_stale_reads=1,
            front4_spi_top3_hit_rate=1.0,
        )
        self.assertFalse(f3_fail.passed_all_fronts)
        self.assertEqual("HOLD_FOR_REFINEMENT", f3_fail.decision)
        self.assertEqual(2, len(f3_fail.failed_reasons))

        # 5. Synthetic regression on Front 4 (SPI top-3 hit rate < 90%) -> HOLD_FOR_REFINEMENT
        f4_fail = bench_repo_ast.evaluate_gate_metrics(
            front1_call_reduction_pct=80.0,
            front1_token_reduction_pct=85.0,
            front2_traceability_recall_top5=1.0,
            front2_gov_recall_top5=1.0,
            front2_req_disc_003_leaks=0,
            front3_query_p95_ms=8.5,
            front3_incremental_sync_p95_ms=25.0,
            front3_stale_reads=0,
            front4_spi_top3_hit_rate=0.80,
        )
        self.assertFalse(f4_fail.passed_all_fronts)
        self.assertEqual("HOLD_FOR_REFINEMENT", f4_fail.decision)
        self.assertTrue(any("Front 4 Tier 1/2 SPI" in r for r in f4_fail.failed_reasons))

        # 6. Synthetic regression on Front 5 (offload defect escapes > 0 or token reduction < 85%) -> HOLD_FOR_REFINEMENT
        f5_fail = bench_repo_ast.evaluate_gate_metrics(
            front1_call_reduction_pct=80.0,
            front1_token_reduction_pct=85.0,
            front2_traceability_recall_top5=1.0,
            front2_gov_recall_top5=1.0,
            front2_req_disc_003_leaks=0,
            front3_query_p95_ms=8.5,
            front3_incremental_sync_p95_ms=25.0,
            front3_stale_reads=0,
            front4_spi_top3_hit_rate=1.0,
            details={
                "offload_cost_benefit": {
                    "regime_c_staged_funnel": {"defect_escapes": 1},
                    "remote_context_savings": {"remote_token_reduction_pct": 70.0},
                    "deterministic_gate_latency_ms": {"p95": 6.2},
                }
            },
        )
        self.assertFalse(f5_fail.passed_all_fronts)
        self.assertEqual("HOLD_FOR_REFINEMENT", f5_fail.decision)
        self.assertEqual(3, len(f5_fail.failed_reasons))

    def test_live_repo_benchmark_passes_all_4_fronts(self) -> None:
        report = bench_repo_ast.run_full_benchmark(iterations=2)
        self.assertTrue(
            report.passed_all_fronts,
            f"Expected GO_PROMOTE across all fronts, got {report.decision}: {report.failed_reasons}\n"
            f"Details: {report.details.get('front2')}",
        )
        self.assertEqual("GO_PROMOTE", report.decision)
        mcp_cmp = report.details.get("mcp_vs_cli", {})
        self.assertEqual(1.0, mcp_cmp.get("output_parity_rate"))
        self.assertGreater(mcp_cmp.get("mean_speedup_x", 0.0), 3.0)
        self.assertLess(mcp_cmp.get("mcp_schema_est_tokens", 9999), 650)

        off_cb = report.details.get("offload_cost_benefit", {})
        self.assertEqual(0, off_cb["regime_c_staged_funnel"]["defect_escapes"])
        self.assertEqual(100.0, off_cb["regime_c_staged_funnel"]["repairable_salvage_rate_pct"])
        self.assertEqual(100.0, off_cb["regime_c_staged_funnel"]["first_pass_critic_calls_saved_pct"])
        self.assertGreaterEqual(off_cb["remote_context_savings"]["remote_token_reduction_pct"], 85.0)
        self.assertLess(off_cb["deterministic_gate_latency_ms"]["p95"], 5.0)
        self.assertEqual(1, off_cb["p0_preemption"]["chunks_waited_before_p0_yield"])

        md = bench_repo_ast.format_report_markdown(report)
        self.assertIn("GO_PROMOTE", md)
        self.assertIn("Front 1: Tool Call Savings", md)
        self.assertIn("Front 4: Anti-Redundancy SPI", md)
        self.assertIn("Front 5: Offload Token Savings", md)
        self.assertIn("Front 5: Staged Funnel Quality", md)
        self.assertIn("MCP (`stdio`) vs CLI Subprocess", md)


if __name__ == "__main__":
    unittest.main()
