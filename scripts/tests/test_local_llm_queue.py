#!/usr/bin/env python3
"""Unit tests for `scripts/local_llm_queue.py` priority broker and staged verification funnel."""

from __future__ import annotations

import io
import json
import pathlib
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from typing import Any

SCRIPTS_DIR = pathlib.Path(__file__).resolve().parents[1]
REPO_ROOT = SCRIPTS_DIR.parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import local_llm_queue as llq  # noqa: E402


class DummyTaskHandler:
    """Programmable handler used to test the Stage 1 priority broker and staged funnel engine."""

    task_type = "translate-bundle"
    default_priority = llq.PRIORITY_P2

    def __init__(self, out_dir: pathlib.Path) -> None:
        self.out_dir = out_dir
        self.applied_items: list[tuple[dict[str, Any], dict[str, Any]]] = []
        self.critic_calls: list[str] = []

    def split_chunks(self, payload: dict[str, Any]) -> list[list[dict[str, Any]]]:
        items = list(payload.get("items") or [])
        chunk_size = int(payload.get("chunk_size", 2))
        return [items[i : i + chunk_size] for i in range(0, len(items), chunk_size)]

    def build_forward_messages(self, chunk: list[dict[str, Any]]) -> list[dict[str, str]]:
        return [
            {"role": "system", "content": "Translate items preserving <<P0>> sentinels."},
            {"role": "user", "content": json.dumps(chunk)},
        ]

    def parse_forward_response(
        self,
        chunk: list[dict[str, Any]],
        raw_response: str,
    ) -> list[dict[str, Any]]:
        parsed = llq.extract_json_payload(raw_response)
        if isinstance(parsed, list):
            return [dict(x) for x in parsed]
        return [dict(parsed)]

    def verify_deterministic(
        self,
        item: dict[str, Any],
        draft: dict[str, Any],
    ) -> tuple[bool, str]:
        expected_sentinel = str(item.get("required_sentinel") or "")
        text = str(draft.get("text") or "")
        if not text:
            return False, "empty draft text"
        if expected_sentinel and expected_sentinel not in text:
            return False, f"missing sentinel {expected_sentinel}"
        return True, "ok"

    def build_critic_messages(
        self,
        item: dict[str, Any],
        draft: dict[str, Any],
    ) -> list[dict[str, str]]:
        item_id = str(item.get("item_id") or "")
        self.critic_calls.append(item_id)
        return [
            {"role": "system", "content": "Semantic critic."},
            {"role": "user", "content": json.dumps({"item": item, "draft": draft})},
        ]

    def parse_critic_verdict(self, raw_response: str) -> tuple[bool, str]:
        return llq.parse_standard_critic_verdict(raw_response)

    def build_repair_messages(
        self,
        item: dict[str, Any],
        draft: dict[str, Any],
        reason: str,
    ) -> list[dict[str, str]]:
        return [
            {"role": "system", "content": "Repair draft based on gate/critic feedback."},
            {"role": "user", "content": json.dumps({"item": item, "draft": draft, "reason": reason})},
        ]

    def parse_repair_response(
        self,
        item: dict[str, Any],
        raw_response: str,
    ) -> dict[str, Any]:
        parsed = llq.extract_json_payload(raw_response)
        return dict(parsed) if isinstance(parsed, dict) else {}

    def apply_verified(
        self,
        verified_items: list[tuple[dict[str, Any], dict[str, Any]]],
    ) -> int:
        self.applied_items.extend(verified_items)
        return len(verified_items)

    def review_file_path(self, payload: dict[str, Any]) -> pathlib.Path:
        return self.out_dir / "review-translate-bundle.md"

    def format_quarantine_entry(
        self,
        item: dict[str, Any],
        draft: dict[str, Any],
        stage: str,
        reason: str,
    ) -> str:
        return f"- `{item.get('item_id')}` (`{stage}`): {reason} | draft=`{draft.get('text', '')}`"


class TestLocalLlmQueueStage1(unittest.TestCase):
    """Tests for `REQ-LLM-Q-001`..`REQ-LLM-Q-004` priority broker and staged funnel."""

    def test_endpoint_safety_think_stripping_and_secret_redaction(self) -> None:
        self.assertTrue(llq.is_safe_llm_endpoint("http://127.0.0.1:11434/v1/chat/completions"))
        self.assertTrue(llq.is_safe_llm_endpoint("http://localhost:8080/v1/chat/completions"))
        self.assertTrue(llq.is_safe_llm_endpoint("http://192.168.1.50:11434/v1/chat/completions"))
        self.assertTrue(llq.is_safe_llm_endpoint("http://gpu-node.lan:11434/v1/chat/completions"))
        self.assertFalse(llq.is_safe_llm_endpoint("https://api.openai.com/v1/chat/completions"))
        self.assertFalse(llq.is_safe_llm_endpoint("http://8.8.8.8:11434/v1/chat/completions"))
        self.assertFalse(llq.is_safe_llm_endpoint("ftp://127.0.0.1:11434"))

        raw = "<think>\nstep 1: check sentinels\n</think>\n```json\n{\"verdict\": \"PASS\"}\n```"
        self.assertEqual("```json\n{\"verdict\": \"PASS\"}\n```", llq.strip_think_tags(raw))
        self.assertEqual({"verdict": "PASS"}, llq.extract_json_payload(raw))

        redacted = llq.redact_secrets("api_key = 'sk-secret-123456789' and ghp_1234567890abcdefghijklmnop")
        self.assertNotIn("sk-secret-123456789", redacted)
        self.assertNotIn("ghp_1234567890abcdefghijklmnop", redacted)
        self.assertIn("[REDACTED_SECRET]", redacted)

        unsafe_client = llq.LocalLlmClient(endpoint="https://api.example.com/v1/chat/completions")
        with self.assertRaises(llq.LocalLlmError):
            unsafe_client.chat_forward([{"role": "user", "content": "hi"}])

    def test_priority_preemption_p0_p1_p2_and_expired_lease_recovery(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = pathlib.Path(tmp)
            db_path = tmp_path / "llm-queue.sqlite"
            broker = llq.LlmQueueBroker(db_path)
            handler = DummyTaskHandler(tmp_path)
            handlers: dict[str, llq.OffloadTaskHandler] = {
                "translate-bundle": handler,
                "critique-diff": handler,
            }

            p2_items = [
                {"item_id": "row_1", "required_sentinel": "<<P0>>"},
                {"item_id": "row_2", "required_sentinel": "<<P0>>"},
                {"item_id": "row_3", "required_sentinel": "<<P0>>"},
                {"item_id": "row_4", "required_sentinel": "<<P0>>"},
            ]
            p2_job_id = broker.enqueue(
                task_type="translate-bundle",
                target_key="de",
                payload={"items": p2_items, "chunk_size": 2},
                priority=llq.PRIORITY_P2,
                total_items=4,
                now_ms=1_000,
            )

            p1_job_id: int | None = None

            def fake_http(url: str, payload: dict[str, Any], timeout: float) -> dict[str, Any]:
                nonlocal p1_job_id
                phase = (payload.get("metadata") or {}).get("phase", "forward")
                user_msg = payload["messages"][-1]["content"]
                if phase == "forward":
                    chunk = json.loads(user_msg)
                    # While chunk 0 of P2 is running, a P1 job and P0 lease arrive!
                    if chunk and chunk[0]["item_id"] == "row_1" and p1_job_id is None:
                        p1_job_id = broker.enqueue(
                            task_type="critique-diff",
                            target_key="HEAD",
                            payload={
                                "items": [{"item_id": "diff_1", "required_sentinel": "<<P0>>"}],
                                "chunk_size": 1,
                            },
                            priority=llq.PRIORITY_P1,
                            total_items=1,
                            now_ms=1_500,
                        )
                        broker.acquire_p0_lease("discord-support-bot", ttl_ms=10_000, now_ms=1_500)
                    out = [{"item_id": it["item_id"], "text": f"ok {it['required_sentinel']}"} for it in chunk]
                    return {"choices": [{"message": {"content": f"<think>ok</think>{json.dumps(out)}"}}]}
                return {"choices": [{"message": {"content": "PASS"}}]}

            client = llq.LocalLlmClient(http_post=fake_http)
            runner = llq.StagedFunnelRunner(client=client, broker=broker, out_dir=tmp_path)

            # 1. Start P2 job: should process chunk 0 (2 items), see P0/P1 pending, and yield ("PREEMPTED")
            step1 = llq.run_worker_step(broker, runner, handlers, now_fn=lambda: 2_000)
            self.assertIsNotNone(step1)
            assert step1 is not None
            self.assertEqual(p2_job_id, step1["job_id"])
            self.assertEqual("PREEMPTED", step1["status"])
            self.assertEqual(2, step1["completed_items"])
            self.assertEqual(1, step1["next_chunk_index"])

            # 2. While P0 lease is active (until t=11,500), neither P1 nor P2 may be claimed
            blocked = llq.run_worker_step(broker, runner, handlers, now_fn=lambda: 3_000)
            self.assertIsNone(blocked)

            # 3. Release P0 lease: P1 job must be claimed and completed BEFORE P2 chunk 1
            broker.release_p0_lease("discord-support-bot")
            step2 = llq.run_worker_step(broker, runner, handlers, now_fn=lambda: 4_000)
            self.assertIsNotNone(step2)
            assert step2 is not None
            self.assertEqual(p1_job_id, step2["job_id"])
            self.assertEqual("COMPLETED", step2["status"])

            # 4. Simulate a worker crash on P2 by claiming it with a short lease and letting it expire
            claimed_p2 = broker.claim_next_job(lease_ttl_ms=5_000, now_ms=5_000)
            self.assertIsNotNone(claimed_p2)
            assert claimed_p2 is not None
            self.assertEqual(p2_job_id, claimed_p2["job_id"])
            self.assertEqual("RUNNING", claimed_p2["status"])

            reclaimed = broker.reclaim_expired_leases(now_ms=11_000)
            self.assertEqual(1, reclaimed)

            # 5. Next worker poll resumes P2 from chunk 1 (`_next_chunk_index=1`) and finishes all 4 items
            step3 = llq.run_worker_step(broker, runner, handlers, now_fn=lambda: 12_000)
            self.assertIsNotNone(step3)
            assert step3 is not None
            self.assertEqual(p2_job_id, step3["job_id"])
            self.assertEqual("COMPLETED", step3["status"])
            self.assertEqual(4, step3["completed_items"])

    def test_staged_funnel_deterministic_gate_critic_repair_and_quarantine(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = pathlib.Path(tmp)
            db_path = tmp_path / "llm-queue.sqlite"
            broker = llq.LlmQueueBroker(db_path)
            handler = DummyTaskHandler(tmp_path)

            # 4 items in 1 chunk:
            # - item_happy: passes deterministic + critic on 1st try
            # - item_det_fix: fails deterministic on 1st try (missing <<P0>>), skips critic_1, repaired on retry
            # - item_crit_fix: passes deterministic, fails critic_1 ("DRIFT: wrong term"), repaired on retry
            # - item_quarantine: fails critic even after repair -> quarantined to review-translate-bundle.md
            chunk_items = [
                {"item_id": "item_happy", "required_sentinel": "<<P0>>"},
                {"item_id": "item_det_fix", "required_sentinel": "<<P0>>"},
                {"item_id": "item_crit_fix", "required_sentinel": "<<P0>>"},
                {"item_id": "item_quarantine", "required_sentinel": "<<P0>>"},
            ]
            job_id = broker.enqueue(
                task_type="translate-bundle",
                target_key="fr",
                payload={"items": chunk_items, "chunk_size": 4},
                total_items=4,
            )

            critic_seen_counts: dict[str, int] = {}

            def fake_http(url: str, payload: dict[str, Any], timeout: float) -> dict[str, Any]:
                phase = (payload.get("metadata") or {}).get("phase", "forward")
                user_content = json.loads(payload["messages"][-1]["content"])
                if phase == "forward":
                    drafts = [
                        {"item_id": "item_happy", "text": "Bonjour <<P0>>"},
                        {"item_id": "item_det_fix", "text": "Dropped sentinel"},
                        {"item_id": "item_crit_fix", "text": "Bad term <<P0>>"},
                        {"item_id": "item_quarantine", "text": "Still bad <<P0>>"},
                    ]
                    return {"choices": [{"message": {"content": json.dumps(drafts)}}]}
                if phase == "critic":
                    item_id = user_content["item"]["item_id"]
                    critic_seen_counts[item_id] = critic_seen_counts.get(item_id, 0) + 1
                    draft_text = user_content["draft"]["text"]
                    if "Bad term" in draft_text or "Still bad" in draft_text:
                        return {"choices": [{"message": {"content": "<think>check</think>DRIFT: mistranslated term"}}]}
                    return {"choices": [{"message": {"content": "PASS"}}]}
                if phase == "repair":
                    item_id = user_content["item"]["item_id"]
                    if item_id == "item_det_fix":
                        return {"choices": [{"message": {"content": json.dumps({"item_id": item_id, "text": "Fixed <<P0>>"})}}]}
                    if item_id == "item_crit_fix":
                        return {"choices": [{"message": {"content": json.dumps({"item_id": item_id, "text": "Good term <<P0>>"})}}]}
                    return {"choices": [{"message": {"content": json.dumps({"item_id": item_id, "text": "Still bad <<P0>>"})}}]}
                raise AssertionError(f"Unexpected phase {phase}")

            client = llq.LocalLlmClient(http_post=fake_http)
            runner = llq.StagedFunnelRunner(client=client, broker=broker, out_dir=tmp_path)
            res = llq.run_worker_step(broker, runner, {"translate-bundle": handler})
            self.assertIsNotNone(res)
            assert res is not None

            # Verify `item_det_fix` only called the critic ONCE (after repair, NOT before deterministic gate failed)
            self.assertEqual(1, critic_seen_counts["item_happy"])
            self.assertEqual(1, critic_seen_counts["item_det_fix"])
            self.assertEqual(2, critic_seen_counts["item_crit_fix"])
            self.assertEqual(2, critic_seen_counts["item_quarantine"])

            self.assertEqual("NEEDS_REVIEW", res["status"])
            self.assertEqual(3, res["completed_items"])
            self.assertEqual(2, res["retried_items"])
            self.assertEqual(1, res["quarantined_items"])

            review_file = tmp_path / "review-translate-bundle.md"
            self.assertTrue(review_file.exists())
            review_text = review_file.read_text(encoding="utf-8")
            self.assertIn("item_quarantine", review_text)
            self.assertIn("mistranslated term", review_text)
            self.assertNotIn("item_happy", review_text)

            # Verify CLI status & cancel subcommands
            buf = io.StringIO()
            with redirect_stdout(buf):
                rc = llq.main(["--db-path", str(db_path), "status", "--job-id", str(job_id)])
            self.assertEqual(0, rc)
            status_md = buf.getvalue()
            self.assertIn("Local LLM Priority Queue Digest", status_md)
            self.assertIn("NEEDS_REVIEW", status_md)
            self.assertLessEqual(len(status_md.splitlines()), 40)

            enq_buf = io.StringIO()
            with redirect_stdout(enq_buf):
                llq.main(["--db-path", str(db_path), "enqueue", "--task-type", "translate-doc", "--target", "FAQ.md"])
            cancel_buf = io.StringIO()
            with redirect_stdout(cancel_buf):
                rc_can = llq.main(["--db-path", str(db_path), "cancel", "--job-id", "2"])
            self.assertEqual(0, rc_can)

            # Verify `p0_gpu_lease` context manager acquires and releases P0 lease cleanly
            self.assertFalse(broker.has_active_p0_lease())
            with llq.p0_gpu_lease(db_path=db_path, holder_id="discord-support-bot"):
                self.assertTrue(broker.has_active_p0_lease())
                self.assertTrue(broker.yield_if_higher_priority_pending(llq.PRIORITY_P2))
            self.assertFalse(broker.has_active_p0_lease())

    def test_translate_bundle_handler_sentinel_mojibake_critic_and_tsv_patch(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out_dir = pathlib.Path(tmp)
            db_path = out_dir / "llm-queue.sqlite"
            broker = llq.LlmQueueBroker(db_path)

            header = "relpath\tparent_path\tkey\tindex\tvalue\tcomment\tblank_before\tbase_key"
            row1 = (
                "lang/de/messages.yml\tteleport\tteleporting\t0\t"
                "&aTeleporting [player] in [time]s\t# @type: string\\n# Wait for chunk load\t0\tteleporting"
            )
            row2 = (
                "lang/de/config.yml\tperformance\tsyncLoading\t1\t"
                "false\t# @type: boolean\\n# Set to false to avoid main-thread lag\t0\tsyncLoading"
            )
            row3 = (
                "lang/de/config.yml\tregion\tradius\t2\t"
                "1000\t# @type: integer\\n# Radius in chunks\t0\tradius"
            )
            (out_dir / "bleed-de.tsv").write_text("\n".join([header, row1, row2, row3]) + "\n", encoding="utf-8")
            (out_dir / "locale-de.tsv").write_text("\n".join([header, row1, row2, row3]) + "\n", encoding="utf-8")

            handler = llq.TranslateBundleHandler(out_dir=out_dir)
            job_id = broker.enqueue(
                task_type="translate-bundle",
                target_key="de",
                payload={"target": "de", "chunk_size": 8},
            )

            mojibake_seq = "\u00e2\u20ac\u201d"

            def fake_http(url: str, payload: dict[str, Any], timeout: float) -> dict[str, Any]:
                phase = (payload.get("metadata") or {}).get("phase", "forward")
                user_obj = json.loads(payload["messages"][-1]["content"])
                if phase == "forward":
                    # row1: drops <<P1>> sentinel on first pass (caught by deterministic gate)
                    # row2: has mojibake on first pass (caught by deterministic gate)
                    # row3: mistranslates "chunks" as "bread slices" (caught by Opinion 2 critic, fails repair -> quarantined)
                    drafts = [
                        {
                            "item_id": user_obj[0]["item_id"],
                            "key_locale": "teleporting",
                            "value_locale": "<<P0>>Teleportiere <<P1>> in s",  # missing <<P2>>
                            "comment_locale": "# <<P3>>\n# Warte auf Chunk-Laden",
                        },
                        {
                            "item_id": user_obj[1]["item_id"],
                            "key_locale": "syncLoading",
                            "value_locale": "false",
                            "comment_locale": f"# <<P0>>\n# Auf false setzen {mojibake_seq}",
                        },
                        {
                            "item_id": user_obj[2]["item_id"],
                            "key_locale": "radius",
                            "value_locale": "1000",
                            "comment_locale": "# <<P0>>\n# Radius in Brotscheiben",
                        },
                    ]
                    return {"choices": [{"message": {"content": json.dumps(drafts)}}]}
                if phase == "critic":
                    cmt = str(user_obj.get("comment_locale") or "")
                    if "Brotscheiben" in cmt:
                        return {"choices": [{"message": {"content": "DRIFT: translated chunks as bread slices"}}]}
                    return {"choices": [{"message": {"content": "PASS"}}]}
                if phase == "repair":
                    item = user_obj["item"]
                    bkey = item["base_key"]
                    if bkey == "teleporting":
                        return {
                            "choices": [
                                {
                                    "message": {
                                        "content": json.dumps(
                                            {
                                                "item_id": item["item_id"],
                                                "key_locale": "teleporting",
                                                "value_locale": "<<P0>>Teleportiere <<P1>> in <<P2>>s",
                                                "comment_locale": "# <<P3>>\n# Warte auf Chunk-Laden",
                                            }
                                        )
                                    }
                                }
                            ]
                        }
                    if bkey == "syncLoading":
                        return {
                            "choices": [
                                {
                                    "message": {
                                        "content": json.dumps(
                                            {
                                                "item_id": item["item_id"],
                                                "key_locale": "syncLoading",
                                                "value_locale": "false",
                                                "comment_locale": "# <<P0>>\n# Auf false setzen gegen Hauptthread-Lag",
                                            }
                                        )
                                    }
                                }
                            ]
                        }
                    return {
                        "choices": [
                            {
                                "message": {
                                    "content": json.dumps(
                                        {
                                            "item_id": item["item_id"],
                                            "key_locale": "radius",
                                            "value_locale": "1000",
                                            "comment_locale": "# <<P0>>\n# Radius in Brotscheiben",
                                        }
                                    )
                                }
                            }
                        ]
                    }
                raise AssertionError(f"Unexpected phase {phase}")

            client = llq.LocalLlmClient(http_post=fake_http)
            runner = llq.StagedFunnelRunner(client=client, broker=broker, out_dir=out_dir)
            res = llq.run_worker_step(broker, runner, {"translate-bundle": handler}, job_id=job_id)
            self.assertIsNotNone(res)
            assert res is not None
            self.assertEqual("NEEDS_REVIEW", res["status"])
            self.assertEqual(2, res["completed_items"])
            self.assertEqual(2, res["retried_items"])
            self.assertEqual(1, res["quarantined_items"])

            # Verify .bak was created and locale-de.tsv unmasked sentinels properly
            self.assertTrue((out_dir / "locale-de.tsv.bak").exists())
            patched_tsv = (out_dir / "locale-de.tsv").read_text(encoding="utf-8")
            self.assertIn("&aTeleportiere [player] in [time]s", patched_tsv)
            self.assertIn("# @type: boolean\\n# Auf false setzen gegen Hauptthread-Lag", patched_tsv)
            # Quarantined row3 remains unmodified in locale-de.tsv and is logged to review-locale-de.md
            self.assertIn("# @type: integer\\n# Radius in chunks", patched_tsv)
            review_de = (out_dir / "review-locale-de.md").read_text(encoding="utf-8")
            self.assertIn("bread slices", review_de)

    def test_translate_doc_handler_sentinel_and_heading_parity(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = pathlib.Path(tmp)
            db_path = tmp_path / "llm-queue.sqlite"
            broker = llq.LlmQueueBroker(db_path)

            doc_src = tmp_path / "FAQ.md"
            doc_src.write_text(
                "## Region Setup\n"
                "Use `/rtp editor` or edit `definitions/regions/default.yml`. See [Guide](https://dailystruggle.github.io/RTP/faq).\n\n"
                "```yaml\n"
                "shape:\n"
                "  radius: 5000b\n"
                "```\n",
                encoding="utf-8",
            )
            out_doc = tmp_path / "out-de" / "FAQ.md"
            handler = llq.TranslateDocHandler(out_dir=tmp_path, repo_root=tmp_path)
            job_id = broker.enqueue(
                task_type="translate-doc",
                target_key="FAQ.md:de",
                payload={"doc_path": str(doc_src), "locale": "de", "output_path": str(out_doc)},
            )

            def fake_http(url: str, payload: dict[str, Any], timeout: float) -> dict[str, Any]:
                phase = (payload.get("metadata") or {}).get("phase", "forward")
                user_obj = json.loads(payload["messages"][-1]["content"])
                if phase == "forward":
                    # Wrong heading level `###` instead of `##` on first try -> caught by deterministic gate
                    return {
                        "choices": [
                            {
                                "message": {
                                    "content": json.dumps(
                                        [
                                            {
                                                "item_id": user_obj[0]["item_id"],
                                                "markdown_locale": (
                                                    "### Regionen Einrichtung\n"
                                                    "Nutze <<P1>> oder bearbeite <<P2>>. Siehe [Anleitung](<<P3>>).\n\n<<P0>>"
                                                ),
                                            }
                                        ]
                                    )
                                }
                            }
                        ]
                    }
                if phase == "repair":
                    item = user_obj["item"]
                    return {
                        "choices": [
                            {
                                "message": {
                                    "content": json.dumps(
                                        {
                                            "item_id": item["item_id"],
                                            "markdown_locale": (
                                                "## Regionen Einrichtung\n"
                                                "Nutze <<P1>> oder bearbeite <<P2>>. Siehe [Anleitung](<<P3>>).\n\n<<P0>>"
                                            ),
                                        }
                                    )
                                }
                            }
                        ]
                    }
                return {"choices": [{"message": {"content": "PASS"}}]}

            client = llq.LocalLlmClient(http_post=fake_http)
            runner = llq.StagedFunnelRunner(client=client, broker=broker, out_dir=tmp_path)
            res = llq.run_worker_step(broker, runner, {"translate-doc": handler}, job_id=job_id)
            self.assertIsNotNone(res)
            assert res is not None
            self.assertEqual("COMPLETED", res["status"])
            self.assertEqual(1, res["completed_items"])
            self.assertEqual(1, res["retried_items"])

            rendered = out_doc.read_text(encoding="utf-8")
            self.assertIn("## Regionen Einrichtung", rendered)
            self.assertIn("`/rtp editor`", rendered)
            self.assertIn("`definitions/regions/default.yml`", rendered)
            self.assertIn("[Anleitung](https://dailystruggle.github.io/RTP/faq)", rendered)
            self.assertIn("radius: 5000b", rendered)

    def test_critique_diff_handler_gov_c_hallucination_and_false_positive_filtering(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = pathlib.Path(tmp)
            db_path = tmp_path / "llm-queue.sqlite"
            broker = llq.LlmQueueBroker(db_path)

            synthetic_diff = (
                "diff --git a/rtp-core/src/main/java/io/github/dailystruggle/rtp/common/selection/region/RegionCacheTask.java "
                "b/rtp-core/src/main/java/io/github/dailystruggle/rtp/common/selection/region/RegionCacheTask.java\n"
                "--- a/rtp-core/src/main/java/io/github/dailystruggle/rtp/common/selection/region/RegionCacheTask.java\n"
                "+++ b/rtp-core/src/main/java/io/github/dailystruggle/rtp/common/selection/region/RegionCacheTask.java\n"
                "@@ -120,3 +120,5 @@\n"
                "+        ticket.close();\n"
                "+        ticket.close(); // double release\n"
                "diff --git a/rtp-core/src/main/java/io/github/dailystruggle/rtp/common/governor/ComputeGovernor.java "
                "b/rtp-core/src/main/java/io/github/dailystruggle/rtp/common/governor/ComputeGovernor.java\n"
                "--- a/rtp-core/src/main/java/io/github/dailystruggle/rtp/common/governor/ComputeGovernor.java\n"
                "+++ b/rtp-core/src/main/java/io/github/dailystruggle/rtp/common/governor/ComputeGovernor.java\n"
                "@@ -80,2 +80,3 @@\n"
                "+        deltaTAsync.observe(nanos);\n"
            )

            handler = llq.CritiqueDiffHandler(out_dir=tmp_path, repo_root=REPO_ROOT)
            job_id = broker.enqueue(
                task_type="critique-diff",
                target_key="HEAD",
                payload={"diff_text": synthetic_diff, "chunk_size": 4},
                priority=llq.PRIORITY_P1,
            )

            def fake_http(url: str, payload: dict[str, Any], timeout: float) -> dict[str, Any]:
                phase = (payload.get("metadata") or {}).get("phase", "forward")
                user_obj = json.loads(payload["messages"][-1]["content"])
                if phase == "forward":
                    # Item 0 (`RegionCacheTask`): fabricates `ADR-999` on first try -> caught by deterministic gate!
                    # Item 1 (`ComputeGovernor`): claims false-positive `S-005` on `deltaTAsync.observe(nanos);` -> caught by Opinion 2 critic!
                    return {
                        "choices": [
                            {
                                "message": {
                                    "content": json.dumps(
                                        [
                                            {
                                                "item_id": user_obj[0]["item_id"],
                                                "class_name": "RegionCacheTask",
                                                "findings": [
                                                    {
                                                        "rule_id": "ADR-999",
                                                        "cited_line": "ticket.close(); // double release",
                                                        "severity": "HIGH",
                                                        "summary": "Double ticket release.",
                                                    }
                                                ],
                                            },
                                            {
                                                "item_id": user_obj[1]["item_id"],
                                                "class_name": "ComputeGovernor",
                                                "findings": [
                                                    {
                                                        "rule_id": "S-005",
                                                        "cited_line": "deltaTAsync.observe(nanos);",
                                                        "severity": "HIGH",
                                                        "summary": "Speculative main-thread block.",
                                                    }
                                                ],
                                            },
                                        ]
                                    )
                                }
                            }
                        ]
                    }
                if phase == "critic":
                    cls_name = user_obj.get("class_name")
                    findings = (user_obj.get("draft") or {}).get("findings") or []
                    if cls_name == "ComputeGovernor" and findings:
                        return {"choices": [{"message": {"content": "DRIFT: observe(nanos) is lock-free EWMA math, not chunk I/O"}}]}
                    return {"choices": [{"message": {"content": "PASS"}}]}
                if phase == "repair":
                    cls_name = user_obj["item"]["class_name"]
                    if cls_name == "RegionCacheTask":
                        return {
                            "choices": [
                                {
                                    "message": {
                                        "content": json.dumps(
                                            {
                                                "item_id": "diff:RegionCacheTask",
                                                "class_name": "RegionCacheTask",
                                                "findings": [
                                                    {
                                                        "rule_id": "S-002",
                                                        "cited_line": "ticket.close(); // double release",
                                                        "severity": "HIGH",
                                                        "summary": "Extra close() on chunk ticket causes double-release.",
                                                    }
                                                ],
                                            }
                                        )
                                    }
                                }
                            ]
                        }
                    return {
                        "choices": [
                            {
                                "message": {
                                    "content": json.dumps(
                                        {
                                            "item_id": "diff:ComputeGovernor",
                                            "class_name": "ComputeGovernor",
                                            "findings": [],
                                        }
                                    )
                                }
                            }
                        ]
                    }
                raise AssertionError(f"Unexpected phase {phase}")

            client = llq.LocalLlmClient(http_post=fake_http)
            runner = llq.StagedFunnelRunner(client=client, broker=broker, out_dir=tmp_path)
            res = llq.run_worker_step(broker, runner, {"critique-diff": handler}, job_id=job_id)
            self.assertIsNotNone(res)
            assert res is not None
            self.assertEqual("COMPLETED", res["status"])
            self.assertEqual(2, res["completed_items"])
            self.assertEqual(2, res["retried_items"])
            self.assertEqual(0, res["quarantined_items"])

            digest_file = tmp_path / "critique-diff-latest.md"
            self.assertTrue(digest_file.exists())
            digest_text = digest_file.read_text(encoding="utf-8")
            self.assertIn("`S-002` in `RegionCacheTask`", digest_text)
            self.assertNotIn("ADR-999", digest_text)
            self.assertIn("`ComputeGovernor`", digest_text)
            self.assertIn("`0` invariant violations", digest_text)
            self.assertLessEqual(len(digest_text.splitlines()), 30)

    def test_backfill_javadoc_handler_d010_process_notes_and_adr_req_validation(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = pathlib.Path(tmp)
            db_path = tmp_path / "llm-queue.sqlite"
            broker = llq.LlmQueueBroker(db_path)

            handler = llq.BackfillJavadocHandler(out_dir=tmp_path, repo_root=REPO_ROOT)
            items = [
                {
                    "item_id": "javadoc:RTPScheduler",
                    "class_name": "RTPScheduler",
                    "path": "rtp-api/src/main/java/io/github/dailystruggle/rtp/api/scheduler/RTPScheduler.java",
                    "module_name": ":rtp-api",
                    "dag_tier": "TIER_1_SPI_PURE",
                    "methods": "runAsync runSyncRepeating isOwnedByCurrentRegion",
                    "gov_c": {"adrs": ["ADR-115"], "reqs": ["REQ-RTP-SYS-001"], "safety_prohibitions": ["S-005"]},
                    "valid_adrs": ["ADR-115"],
                    "valid_reqs": ["REQ-RTP-SYS-001"],
                },
                {
                    "item_id": "javadoc:RTPServerAccessor",
                    "class_name": "RTPServerAccessor",
                    "path": "rtp-api/src/main/java/io/github/dailystruggle/rtp/api/RTPServerAccessor.java",
                    "module_name": ":rtp-api",
                    "dag_tier": "TIER_1_SPI_PURE",
                    "methods": "getPlatformFamily getServerIntVersion isCompatible getRTPWorlds",
                    "gov_c": {"adrs": ["ADR-115"], "reqs": ["REQ-RTP-SYS-001"], "safety_prohibitions": ["S-006"]},
                    "valid_adrs": ["ADR-115"],
                    "valid_reqs": ["REQ-RTP-SYS-001"],
                },
            ]
            # Verify deterministic gate directly on >8 lines and hallucinated REQ-UNKNOWN-999
            too_long_draft = {"javadoc": "/**\n" + "\n".join(f" * Line {i}" for i in range(9)) + "\n */"}
            ok_len, reason_len = handler.verify_deterministic(items[0], too_long_draft)
            self.assertFalse(ok_len)
            self.assertIn("<= 8 lines", reason_len)

            bad_req_draft = {"javadoc": "/**\n * Platform scheduler SPI.\n * @trace REQ-UNKNOWN-999\n */"}
            ok_req, reason_req = handler.verify_deterministic(items[0], bad_req_draft)
            self.assertFalse(ok_req)
            self.assertIn("REQ-UNKNOWN-999", reason_req)

            job_id = broker.enqueue(
                task_type="backfill-javadoc",
                target_key="rtp-api",
                payload={"items": items, "chunk_size": 6},
            )

            proc_note = "Phase" + " 2e"

            def fake_http(url: str, payload: dict[str, Any], timeout: float) -> dict[str, Any]:
                phase = (payload.get("metadata") or {}).get("phase", "forward")
                user_obj = json.loads(payload["messages"][-1]["content"])
                if phase == "forward":
                    # RTPScheduler: includes forbidden process note on first pass -> caught by deterministic gate!
                    # RTPServerAccessor: clean <=8 line Javadoc with valid @governedBy ADR-115 and @trace REQ-RTP-SYS-001
                    return {
                        "choices": [
                            {
                                "message": {
                                    "content": json.dumps(
                                        [
                                            {
                                                "item_id": "javadoc:RTPScheduler",
                                                "class_name": "RTPScheduler",
                                                "javadoc": (
                                                    f"/**\n * Regional scheduler SPI introduced in {proc_note}.\n"
                                                    " * @governedBy ADR-115\n */"
                                                ),
                                            },
                                            {
                                                "item_id": "javadoc:RTPServerAccessor",
                                                "class_name": "RTPServerAccessor",
                                                "javadoc": (
                                                    "/**\n"
                                                    " * Platform compatibility and world lookup SPI.\n"
                                                    " * @governedBy ADR-115\n"
                                                    " * @trace REQ-RTP-SYS-001\n"
                                                    " */"
                                                ),
                                            },
                                        ]
                                    )
                                }
                            }
                        ]
                    }
                if phase == "repair":
                    return {
                        "choices": [
                            {
                                "message": {
                                    "content": json.dumps(
                                        {
                                            "item_id": "javadoc:RTPScheduler",
                                            "class_name": "RTPScheduler",
                                            "javadoc": (
                                                "/**\n"
                                                " * Regional and async task scheduling SPI.\n"
                                                " * @governedBy ADR-115\n"
                                                " * @trace REQ-RTP-SYS-001\n"
                                                " */"
                                            ),
                                        }
                                    )
                                }
                            }
                        ]
                    }
                return {"choices": [{"message": {"content": "PASS"}}]}

            client = llq.LocalLlmClient(http_post=fake_http)
            runner = llq.StagedFunnelRunner(client=client, broker=broker, out_dir=tmp_path)
            res = llq.run_worker_step(broker, runner, {"backfill-javadoc": handler}, job_id=job_id)
            self.assertIsNotNone(res)
            assert res is not None
            self.assertEqual("COMPLETED", res["status"])
            self.assertEqual(2, res["completed_items"])
            self.assertEqual(1, res["retried_items"])

            drafts_md = (tmp_path / "javadoc-drafts.md").read_text(encoding="utf-8")
            self.assertIn("RTPScheduler", drafts_md)
            self.assertIn("RTPServerAccessor", drafts_md)
            self.assertIn("@governedBy ADR-115", drafts_md)
            self.assertNotIn(proc_note, drafts_md)

    def test_execute_offload_action_critique_diff_and_offline_fallback(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = pathlib.Path(tmp)
            db_path = tmp_path / "llm-queue.sqlite"

            synthetic_diff = (
                "diff --git a/rtp-core/src/main/java/io/github/dailystruggle/rtp/common/selection/region/RegionCacheTask.java "
                "b/rtp-core/src/main/java/io/github/dailystruggle/rtp/common/selection/region/RegionCacheTask.java\n"
                "--- a/rtp-core/src/main/java/io/github/dailystruggle/rtp/common/selection/region/RegionCacheTask.java\n"
                "+++ b/rtp-core/src/main/java/io/github/dailystruggle/rtp/common/selection/region/RegionCacheTask.java\n"
                "@@ -10,2 +10,3 @@\n"
                "+        world.getChunkAt(x, z);\n"
            )

            # 1. Offline local endpoint -> fails closed, leaves job PENDING, returns 1-line offline note
            def offline_http(url: str, payload: dict[str, Any], timeout: float) -> dict[str, Any]:
                raise OSError("Connection refused")

            offline_res = llq.execute_offload_action(
                action="critique_diff",
                target="HEAD",
                db_path=db_path,
                out_dir=tmp_path,
                repo_root=REPO_ROOT,
                http_post=offline_http,
                payload_extra={"diff_text": synthetic_diff},
            )
            self.assertIn("Local LLM endpoint offline", offline_res["markdown"])
            self.assertEqual(1, offline_res["pending_jobs"])

            # 2. Online local endpoint via run_once drains the queued P1 diff critique
            def online_http(url: str, payload: dict[str, Any], timeout: float) -> dict[str, Any]:
                phase = (payload.get("metadata") or {}).get("phase", "forward")
                if phase == "forward":
                    return {
                        "choices": [
                            {
                                "message": {
                                    "content": json.dumps(
                                        [
                                            {
                                                "item_id": "diff:RegionCacheTask",
                                                "class_name": "RegionCacheTask",
                                                "findings": [
                                                    {
                                                        "rule_id": "S-005",
                                                        "cited_line": "world.getChunkAt(x, z);",
                                                        "severity": "HIGH",
                                                        "summary": "Synchronous chunk load on main thread.",
                                                    }
                                                ],
                                            }
                                        ]
                                    )
                                }
                            }
                        ]
                    }
                return {"choices": [{"message": {"content": "PASS"}}]}

            online_res = llq.execute_offload_action(
                action="run_once",
                target="1",
                db_path=db_path,
                out_dir=tmp_path,
                repo_root=REPO_ROOT,
                http_post=online_http,
            )
            self.assertEqual(1, online_res["completed_jobs"])
            self.assertEqual(1, online_res["auto_applied"])


if __name__ == "__main__":
    unittest.main()
