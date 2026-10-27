#!/usr/bin/env python3
"""Unit and integration tests for `scripts/repo_ast.py` (Stage 2)."""
from __future__ import annotations

import io
import json
import pathlib
import sqlite3
import sys
import tempfile
import unittest
from contextlib import redirect_stdout

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPTS_DIR = REPO_ROOT / "scripts"
ATLAS_DIR = REPO_ROOT / "corpus-atlas"
for _p in (SCRIPTS_DIR, ATLAS_DIR):
    if str(_p) not in sys.path:
        sys.path.insert(0, str(_p))

import repo_ast  # noqa: E402


class TestRepoAstCli(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.conn, _ = repo_ast.ensure_db_connection(
            db_path=repo_ast.DEFAULT_DB_PATH,
            repo_root=REPO_ROOT,
            auto_sync=False,
        )

    @classmethod
    def tearDownClass(cls) -> None:
        cls.conn.close()

    def test_context_class_req_adr_and_config_key(self) -> None:
        # 1. Java class context returns ADR-115 Gov(c), DAG tier, hierarchy, and Gradle test command
        ctx_tpt = repo_ast.query_context(self.conn, "TeleportPipelineTask")
        self.assertTrue(ctx_tpt["found"])
        self.assertEqual("TIER_2_CORE_DOMAIN", ctx_tpt["primary_class"]["dag_tier"])
        self.assertIn("RTPRunnable", ctx_tpt["hierarchy"]["super_classes"])
        self.assertTrue(any(r.startswith("REQ-RTP-") for r in ctx_tpt["gov_c"]["reqs"]))
        self.assertIn("S-005", ctx_tpt["gov_c"]["safety_prohibitions"])
        self.assertTrue(any(cmd.startswith("./gradlew :rtp-core:test") for cmd in ctx_tpt["gradle_test_commands"]))

        md_tpt = repo_ast.format_context_markdown(ctx_tpt)
        self.assertLessEqual(len(md_tpt.splitlines()), 40)
        self.assertIn("Gov(c) (`ADR-115`)", md_tpt)

        # 2. Requirement ID context resolves TRACEABILITY.md row, implementing classes, and tests
        ctx_req = repo_ast.query_context(self.conn, "REQ-RTP-F-002")
        self.assertTrue(ctx_req["found"])
        self.assertIsNotNone(ctx_req["traceability_row"])
        self.assertIn("Circle", ctx_req["hierarchy"]["subclasses_and_implementors"])
        self.assertIn("MemoryShapeTest", ctx_req["verified_by_tests"])

        # 3. ADR context resolves arch_sections path and governed classes
        ctx_adr = repo_ast.query_context(self.conn, "ADR-115")
        self.assertTrue(ctx_adr["found"])
        self.assertIsNotNone(ctx_adr["adr_doc"])
        self.assertTrue(ctx_adr["adr_doc"]["path"].endswith("ADR-115-package-dag-disentanglement-and-decision-preservation-hypergraph.md"))

        # 4. Config key context resolves defaults, RegionConfigConstraints, ADR-121, and ConfigConstraintCheckTest
        ctx_cfg = repo_ast.query_context(self.conn, "shape.centerRadius")
        self.assertTrue(ctx_cfg["found"])
        self.assertTrue(any("centerRadius" in k for k in ctx_cfg["config_keys"]))
        self.assertIn("RegionConfigConstraints", ctx_cfg["dependencies"]["inbound_callers"])
        self.assertIn("ADR-121", ctx_cfg["gov_c"]["adrs"])
        self.assertTrue(any("ConfigConstraintCheckTest" in cmd for cmd in ctx_cfg["gradle_test_commands"]))

        # 5. Cross-module SPI impact resolves all 5 platform adapters and their dedicated scheduler test suites
        imp_sched = repo_ast.query_impact(self.conn, "RTPScheduler")
        for expected_mod in (
            ":rtp-core",
            ":rtp-bukkit:rtp-bukkit-common",
            ":rtp-paper:rtp-paper-common",
            ":rtp-folia:rtp-folia-common",
            ":rtp-fabric:rtp-fabric-common",
            ":rtp-neoforge:rtp-neoforge-common",
        ):
            self.assertIn(expected_mod, imp_sched["affected_modules"])
        self.assertTrue(any("ProfilingRTPSchedulerTest" in cmd for cmd in imp_sched["gradle_test_commands"]))
        self.assertTrue(any("BukkitSchedulerImplTest" in cmd for cmd in imp_sched["gradle_test_commands"]))

        # 6. Audit distinguishes in-file @governedBy tags from inferred governance and excludes @interface PublicApi
        aud = repo_ast.audit_corpus_searchability(self.conn, limit=10)
        spi_names = [item["class_name"] for item in aud["tier1_spi_missing_javadoc_or_gov"]]
        self.assertIn("RTPPlayer", spi_names[:3])
        self.assertNotIn("PublicApi", spi_names)
        md_aud = repo_ast.format_audit_markdown(aud)
        self.assertIn("governed_by_tag=False", md_aud)

    def test_search_tier_1_2_anti_redundancy_ranking(self) -> None:
        cases = [
            ("exponential moving average estimator", "EwmaEstimator"),
            ("adaptive comparative cost governor", "ComparativeCostGovernor"),
            ("duration string parser temporal unit", "DurationParser"),
            ("chunk ticket memory lifecycle tracker", "MemoryTracker"),
            ("folia region scheduler task abstraction", "RTPScheduler"),
            ("server platform family compatibility accessor", "RTPServerAccessor"),
            ("built in test finding code registry", "BitCodeRegistry"),
            ("command tree parameter tab complete", "TreeCommand"),
            ("anvil region file nbt chunk prefilter", "AnvilPrefilter"),
            ("cartography map heatmap renderer", "HeatmapRenderer"),
        ]
        for query, expected_cls in cases:
            res = repo_ast.query_search(self.conn, query, limit=5)
            top3 = [r["class_name"] for r in res["results"][:3]]
            self.assertIn(
                expected_cls,
                top3,
                f"Expected {expected_cls!r} in top 3 for {query!r}, got {top3}",
            )

        # Method-name token match (`ewmaNanosPerRead`, `ewmaMillis` on StorageLatencyProbe)
        # outranks single-token Porter-stem class collisions (`moving` -> `move` on PlayerMoveEvent),
        # and `format_search_markdown` honors `limit > 8` without truncating hits 9..N.
        ewma_res = repo_ast.query_search(self.conn, "ewma exponential moving average", limit=12)
        self.assertEqual(12, len(ewma_res["results"]))
        top8_names = [r["class_name"] for r in ewma_res["results"][:8]]
        self.assertIn("StorageLatencyProbe", top8_names)
        for move_cls in ("PlayerMoveEvent", "PlayerMoveDispatcher"):
            if move_cls in [r["class_name"] for r in ewma_res["results"]]:
                all_names = [r["class_name"] for r in ewma_res["results"]]
                self.assertLess(all_names.index("StorageLatencyProbe"), all_names.index(move_cls))
        ewma_md = repo_ast.format_search_markdown(ewma_res)
        self.assertIn("12. **`", ewma_md)

        # Member-level Javadoc/comment/field harvesting surfaces inline EWMA implementations
        # (`ScanTask`, `Region`) and collaborator consumers (`BudgetPacer`, `ComputeGovernor`).
        ewma_all = repo_ast.query_search(self.conn, "ewma", limit=20)
        ewma_all_names = [r["class_name"] for r in ewma_all["results"]]
        for expected_ewma_cls in ("EwmaEstimator", "ScanTask", "Region", "BudgetPacer", "ComputeGovernor"):
            self.assertIn(expected_ewma_cls, ewma_all_names)

        # IDF-weighted query coordination demotes partial generic matches (`WeightedAverageBackendSelector`)
        # below anchor-term EWMA classes and surfaces deduplicated `dev_sections` + `arch_sections` docs.
        ewma_full = repo_ast.query_search(self.conn, "ewma exponential weighted moving average", limit=12)
        full_names = [r["class_name"] for r in ewma_full["results"]]
        if "WeightedAverageBackendSelector" in full_names:
            w_idx = full_names.index("WeightedAverageBackendSelector")
            for core_ewma in ("EwmaEstimator", "WorkAttributionLedger", "EwmaLinearFit", "ImmediateLoadGovernor"):
                self.assertLess(full_names.index(core_ewma), w_idx)
        doc_paths = [d["path"] for d in ewma_full["doc_hits"]]
        self.assertEqual(len(doc_paths), len(set(doc_paths)), "doc_hits must be deduplicated by file path")
        self.assertIn("api/predict-api/README.md", doc_paths)

        # Call-site line numbers & invoked method hints are captured on `ast_edges` and rendered in context.
        ctx_ewma = repo_ast.query_context(self.conn, "EwmaEstimator")
        caller_sites = ctx_ewma["dependencies"]["inbound_caller_sites"]
        self.assertTrue(any(s.startswith("`BudgetPacer:") and "ofTaskPipe" in s for s in caller_sites))
        self.assertTrue(any(s.startswith("`ComputeGovernor:") and "ofAlpha" in s for s in caller_sites))
        ctx_ewma_md = repo_ast.format_context_markdown(ctx_ewma)
        self.assertIn("`BudgetPacer:", ctx_ewma_md)
        self.assertIn("standardErrorSquared", ctx_ewma_md)

    def test_impact_analysis_and_cli_json_and_zero_stale_reads(self) -> None:
        imp = repo_ast.query_impact(self.conn, "EwmaEstimator")
        self.assertTrue(imp["found"])
        self.assertIn("ComputeGovernor", imp["direct_dependents"])
        self.assertIn(":rtp-core", imp["affected_modules"])
        self.assertTrue(any("EwmaEstimatorTest" in cmd for cmd in imp["gradle_test_commands"]))

        # Verify CLI --json and zero-stale-read auto-sync on a temporary fixture repo
        with tempfile.TemporaryDirectory() as tmp:
            fix_root = pathlib.Path(tmp)
            (fix_root / "docs" / "admin").mkdir(parents=True)
            (fix_root / "docs" / "dev").mkdir(parents=True)
            (fix_root / "api" / "predict-api" / "src" / "main" / "java" / "pkg").mkdir(parents=True)
            (fix_root / "docs" / "admin" / "GUIDE.md").write_text("# Guide\n## Overview\nAdmin guide.\n", encoding="utf-8")
            (fix_root / "docs" / "dev" / "TRACEABILITY.md").write_text(
                "# Traceability\n| Req ID | Summary | Design Ref | Implementing Class(es) | Test(s) |\n"
                "|---|---|---|---|---|\n"
                "| REQ-RTP-S-005 | No sync chunk I/O | ADR-015 | `AlphaService` | `AlphaServiceTest` |\n",
                encoding="utf-8",
            )
            java_file = fix_root / "api" / "predict-api" / "src" / "main" / "java" / "pkg" / "AlphaService.java"
            java_file.write_text(
                "package pkg;\n/** @governedBy ADR-015 */\npublic class AlphaService { public void runAlpha() {} }\n",
                encoding="utf-8",
            )
            db_file = fix_root / "ast.sqlite"

            buf = io.StringIO()
            with redirect_stdout(buf):
                rc = repo_ast.main(["--db", str(db_file), "--repo-root", str(fix_root), "--json", "context", "AlphaService"])
            self.assertEqual(0, rc)
            payload1 = json.loads(buf.getvalue())
            self.assertIn("ADR-015", payload1["gov_c"]["adrs"])
            self.assertNotIn("ADR-115", payload1["gov_c"]["adrs"])

            # Mutate AlphaService.java and immediately query without manual sync -> zero stale read
            java_file.write_text(
                "package pkg;\n/** @governedBy ADR-015 ADR-115 */\npublic class AlphaService { public void runAlphaV2() {} }\n",
                encoding="utf-8",
            )
            buf2 = io.StringIO()
            with redirect_stdout(buf2):
                rc2 = repo_ast.main(["--db", str(db_file), "--repo-root", str(fix_root), "--json", "context", "AlphaService"])
            self.assertEqual(0, rc2)
            payload2 = json.loads(buf2.getvalue())
            self.assertEqual(1, payload2["sync"]["dirty_count"])
            self.assertIn("ADR-115", payload2["gov_c"]["adrs"])
            self.assertIn("runAlphaV2", payload2["primary_class"]["methods"])

            # Verify MCP stdio server (both newline-delimited and Content-Length framing) + zero-stale-read sync
            import mcp_server

            srv = mcp_server.CorpusAtlasMcpServer(db_path=db_file, repo_root=fix_root)
            try:
                init_resp = srv.handle_jsonrpc_message({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}})
                self.assertEqual("corpus-atlas", init_resp["result"]["serverInfo"]["name"])

                list_resp = srv.handle_jsonrpc_message({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
                tool_names = [t["name"] for t in list_resp["result"]["tools"]]
                self.assertEqual(
                    [
                        "repo_ast_context",
                        "repo_ast_search",
                        "repo_ast_impact",
                        "repo_ast_audit",
                        "repo_ast_sync",
                        "repo_ast_offload",
                    ],
                    tool_names,
                )

                # Verify repo_ast_offload MCP + CLI parity (enqueue + status)
                off_enq = srv.handle_jsonrpc_message({
                    "jsonrpc": "2.0",
                    "id": 20,
                    "method": "tools/call",
                    "params": {
                        "name": "repo_ast_offload",
                        "arguments": {
                            "action": "enqueue",
                            "task_type": "translate-bundle",
                            "target": "de",
                            "format": "json",
                        },
                    },
                })
                self.assertFalse(off_enq["result"]["isError"])
                off_payload = json.loads(off_enq["result"]["content"][0]["text"])
                self.assertEqual("enqueue", off_payload["action"])
                self.assertEqual(1, off_payload["enqueued_job_id"])

                off_stat_md = srv.handle_jsonrpc_message({
                    "jsonrpc": "2.0",
                    "id": 21,
                    "method": "tools/call",
                    "params": {
                        "name": "repo_ast_offload",
                        "arguments": {"action": "status", "target": "1"},
                    },
                })
                self.assertFalse(off_stat_md["result"]["isError"])
                mcp_off_md = off_stat_md["result"]["content"][0]["text"]
                self.assertIn("Local LLM Priority Queue Digest", mcp_off_md)

                cli_off_buf = io.StringIO()
                with redirect_stdout(cli_off_buf):
                    rc_off = repo_ast.main([
                        "--db",
                        str(db_file),
                        "--repo-root",
                        str(fix_root),
                        "offload",
                        "status",
                        "--target",
                        "1",
                    ])
                self.assertEqual(0, rc_off)
                self.assertEqual(mcp_off_md.strip(), cli_off_buf.getvalue().strip())

                # Mutate AlphaService.java again and call repo_ast_context via MCP -> zero stale read
                java_file.write_text(
                    "package pkg;\n/** @governedBy ADR-015 ADR-115 ADR-121 */\npublic class AlphaService { public void runAlphaV3() {} }\n",
                    encoding="utf-8",
                )
                call_resp = srv.handle_jsonrpc_message({
                    "jsonrpc": "2.0",
                    "id": 3,
                    "method": "tools/call",
                    "params": {"name": "repo_ast_context", "arguments": {"target": "AlphaService", "format": "json"}},
                })
                self.assertFalse(call_resp["result"]["isError"])
                ctx_mcp = json.loads(call_resp["result"]["content"][0]["text"])
                self.assertEqual(1, ctx_mcp["sync"]["dirty_count"])
                self.assertIn("ADR-121", ctx_mcp["gov_c"]["adrs"])
                self.assertIn("runAlphaV3", ctx_mcp["primary_class"]["methods"])

                # Verify stdio stream framing (both newline and Content-Length)
                req_line = b'{"jsonrpc":"2.0","id":10,"method":"ping"}\n'
                body_hdr = b'{"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"repo_ast_sync","arguments":{"check_only":true}}}'
                req_hdr = f"Content-Length: {len(body_hdr)}\r\n\r\n".encode("ascii") + body_hdr
                in_bio = io.BytesIO(req_line + req_hdr)
                out_bio = io.BytesIO()
                mcp_server.serve_stdio(srv, in_stream=in_bio, out_stream=out_bio)
                out_bio.seek(0)
                msg1, mode1 = mcp_server._read_next_rpc_message(out_bio)
                msg2, mode2 = mcp_server._read_next_rpc_message(out_bio)
                self.assertEqual("line", mode1)
                self.assertEqual(10, msg1["id"])
                self.assertEqual("header", mode2)
                self.assertEqual(11, msg2["id"])
                self.assertIn("AST Incremental Check", msg2["result"]["content"][0]["text"])
            finally:
                srv.close()


if __name__ == "__main__":
    unittest.main()
