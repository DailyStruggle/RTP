#!/usr/bin/env python3
"""
Tests for Customer Setup Reconstruction (`definite` vs `best_approximation`),
the 4 Verification Tools (`ConsoleLogTool`, `LocalWebpageTool`, `ScreenshottingTool`,
`MineflayerBotTool`), and the Discord Server Versioned Artifact Cache.
"""

from __future__ import annotations

import pathlib
import sys
import tempfile
import unittest
from typing import Any

TEST_DIR = pathlib.Path(__file__).resolve().parent
BOT_DIR = TEST_DIR.parent
if str(BOT_DIR) not in sys.path:
    sys.path.insert(0, str(BOT_DIR))

from bot_verification_tools import (  # noqa: E402
    BotVerificationWorkbench,
    DiscordVersionedArtifactCache,
    capture_verification_visuals,
    gather_console_and_logs,
    read_repo_plugin_version,
    reconstruct_customer_setup,
    run_local_webpage_operations,
    run_mineflayer_probe,
)
from discord_support_bot import (  # noqa: E402
    DiscordForumPoller,
    ForumPostContext,
    LocalLlmSynthesizer,
    SupportBotEngine,
)


class BotVerificationToolsAndDiscordCacheTest(unittest.TestCase):
    def test_best_approximation_vs_definite_customer_setup_reconstruction(self) -> None:
        repo_ver = read_repo_plugin_version()

        # 1. Partial question only -> best_approximation setup
        approx = reconstruct_customer_setup(
            conn=None,
            query_text="How do I remove the hole in the middle of my circle region?",
        )
        self.assertEqual("best_approximation", approx.setup_mode)
        self.assertEqual(repo_ver, approx.plugin_version)
        self.assertEqual("repo_default", approx.version_source)
        self.assertEqual("Paper", approx.platform)
        self.assertTrue(any(r["key_path"] == "centerRadius" and r["new_value"] == "0" for r in approx.candidate_recommendations))

        # 2. Explicit version + platform + YAML overrides -> definite setup
        definite = reconstruct_customer_setup(
            conn=None,
            query_text="Running LeafRTP v3.3.1 on Folia 1.21.4. My circle region has a hole.",
            config_yaml="radius: 256\ncenterRadius: 500\n",
        )
        self.assertEqual("definite", definite.setup_mode)
        self.assertEqual("3.3.1", definite.plugin_version)
        self.assertEqual("customer", definite.version_source)
        self.assertEqual("Folia", definite.platform)
        self.assertEqual("customer", definite.platform_source)
        self.assertEqual(256, definite.effective_region["radius"])
        self.assertEqual(500, definite.effective_region["centerRadius"])

    def test_console_tool_self_corrects_cross_key_contradiction_and_gathers_logs(self) -> None:
        # Customer sets centerRadius: 500 while radius: 256 -> ConsoleLogTool catches & self-corrects
        profile = reconstruct_customer_setup(
            conn=None,
            query_text="LeafRTP v3.3.1 on Paper 1.21.4: set centerRadius: 500 and run `/rtp scan start region=default`",
            config_yaml="radius: 256\ncenterRadius: 500\n",
        )
        console_res = gather_console_and_logs(profile)
        self.assertTrue(console_res.passed)
        self.assertTrue(any("centerRadius" in c and "radius" in c for c in console_res.self_corrections))
        joined_logs = "\n".join(console_res.console_transcript)
        self.assertIn("DEFINITE SETUP: LeafRTP v3.3.1 on Paper 1.21.4", joined_logs)
        self.assertIn("REGION_GEOMETRY_INVALID", joined_logs)
        self.assertIn("ScanTask started for region `default`", joined_logs)

    def test_local_webpage_tool_and_screenshotting_multi_format_artifacts(self) -> None:
        profile = reconstruct_customer_setup(
            conn=None,
            query_text="How do I remove the hole in the middle of my circle region and check warmup countdown?",
        )
        console_res = gather_console_and_logs(profile)
        webpage_res = run_local_webpage_operations(profile, console_res)

        self.assertEqual("regions/default.yml", webpage_res.target_file)
        self.assertEqual("centerRadius", webpage_res.target_key)
        self.assertIn("file=regions%2Fdefault.yml", webpage_res.editor_deep_link)
        self.assertIn("key=centerRadius", webpage_res.editor_deep_link)
        self.assertEqual(0.0, webpage_res.region_curve_preview["voidPercentage"])
        self.assertEqual(24, len(webpage_res.region_curve_preview["samplePoints"]))

        mineflayer_res = run_mineflayer_probe(profile, webpage_res)
        self.assertTrue(mineflayer_res.executed)
        self.assertEqual("protocol_simulation", mineflayer_res.mode)
        self.assertTrue(any("forcedMove" in ev for ev in mineflayer_res.events))

        visual = capture_verification_visuals(profile, console_res, webpage_res, mineflayer_res)
        self.assertTrue(visual.image_tag.startswith(f"rtp-cache:v{profile.plugin_version}:{profile.schema_hash8}:"))
        self.assertIn(visual.image_tag, visual.image_description)
        # Verify PNG signature and chunks
        self.assertTrue(visual.png_bytes.startswith(b"\x89PNG\r\n\x1a\n"))
        self.assertIn(b"tEXtDescription", visual.png_bytes)
        # Verify APNG signature and animation control chunks (`acTL`, `fcTL`, `fdAT`)
        self.assertTrue(visual.apng_bytes.startswith(b"\x89PNG\r\n\x1a\n"))
        self.assertIn(b"acTL", visual.apng_bytes)
        self.assertIn(b"fcTL", visual.apng_bytes)
        self.assertIn(b"fdAT", visual.apng_bytes)
        # Verify GIF89a signature and looping NETSCAPE2.0 extension
        self.assertTrue(visual.gif_bytes.startswith(b"GIF89a"))
        self.assertIn(b"NETSCAPE2.0", visual.gif_bytes)

    def test_discord_server_as_versioned_cache_reuses_by_link_and_invalidates_on_version_change(self) -> None:
        cache = DiscordVersionedArtifactCache()
        workbench = BotVerificationWorkbench(cache)

        # 1. Initial run: cache miss -> generates visual artifact
        r1 = workbench.run_verification(
            conn=None,
            query_text="How do I remove the hole in the middle of my circle region?",
        )
        self.assertEqual("miss", r1.cache_status)
        self.assertIsNotNone(r1.visual_artifact)
        self.assertIsNone(r1.reused_image_url)

        # 2. Simulate Discord server returning the uploaded attachment message with tag + description
        discord_msg = {
            "id": "msg_9001",
            "channel_id": "th_support_1",
            "author": {"id": "bot_1"},
            "content": "Here is how to set centerRadius=0.",
            "attachments": [
                {
                    "id": "att_555",
                    "filename": r1.visual_artifact.filename_png,
                    "description": r1.image_description,
                    "url": "https://cdn.discordapp.com/attachments/111222333/444555666/rtp-centerradius.png",
                }
            ],
        }
        harvested = cache.harvest_from_discord_messages([discord_msg], channel_id="th_support_1", bot_user_id="bot_1")
        self.assertEqual(1, harvested)

        # 3. Second run on the same version & schema -> Cache HIT! Reuses Discord CDN link without re-rendering
        r2 = workbench.run_verification(
            conn=None,
            query_text="How do I remove the hole in the middle of my circle region?",
        )
        self.assertEqual("hit_exact", r2.cache_status)
        self.assertIsNone(r2.visual_artifact)
        self.assertEqual(
            "https://cdn.discordapp.com/attachments/111222333/444555666/rtp-centerradius.png",
            r2.reused_image_url,
        )

        # 4. Version difference (customer or release moves to v3.4.0) -> Automatic cache invalidation!
        r3 = workbench.run_verification(
            conn=None,
            query_text="LeafRTP v3.4.0: How do I remove the hole in the middle of my circle region?",
        )
        self.assertTrue(r3.cache_status.startswith("invalidated_version_mismatch"))
        self.assertIsNotNone(r3.visual_artifact)
        self.assertIsNone(r3.reused_image_url)

    def test_poller_harvests_discord_messages_and_reuses_cached_image_links(self) -> None:
        engine = SupportBotEngine(cooldown_seconds=0.0)
        self.addCleanup(engine.close)

        class FakeMultipartDiscordClient:
            def __init__(self) -> None:
                self.multipart_calls: list[tuple[str, dict[str, Any], list[tuple[str, bytes, str]]]] = []
                self.json_posts: list[tuple[str, dict[str, Any]]] = []
                self.threads = [
                    {
                        "id": "th_cache_1",
                        "name": "Remove center hole in circle region",
                        "parent_id": "ch_support",
                        "owner_id": "u_101",
                        "flags": 0,
                        "applied_tags": ["tag_rtp"],
                        "thread_metadata": {"archived": False},
                    },
                    {
                        "id": "th_cache_2",
                        "name": "Circle region has a hole in middle",
                        "parent_id": "ch_support",
                        "owner_id": "u_102",
                        "flags": 0,
                        "applied_tags": ["tag_rtp"],
                        "thread_metadata": {"archived": False},
                    },
                ]
                self.messages: dict[str, list[dict[str, Any]]] = {
                    "th_cache_1": [
                        {
                            "id": "m_1",
                            "author": {"id": "u_101", "username": "alice"},
                            "content": "How do I remove the hole in the middle of my circle region?",
                            "timestamp": "1970-01-01T00:00:10+00:00",
                        }
                    ],
                    "th_cache_2": [
                        {
                            "id": "m_2",
                            "author": {"id": "u_102", "username": "bob"},
                            "content": "How do I remove the hole in the middle of my circle region?",
                            "timestamp": "1970-01-01T00:00:12+00:00",
                        }
                    ],
                }

            def request(self, method: str, path: str, payload: dict[str, Any] | None = None) -> Any:
                if method == "GET" and path == "/users/@me":
                    return {"id": "bot_99"}
                if method == "GET" and path == "/guilds/g_1/channels":
                    return [
                        {
                            "id": "ch_support",
                            "name": "support",
                            "type": 15,
                            "available_tags": [{"id": "tag_rtp", "name": "🌿 LeafRTP"}],
                        }
                    ]
                if method == "GET" and path == "/guilds/g_1/roles":
                    return []
                if method == "GET" and path.startswith("/guilds/g_1/members/"):
                    return {"roles": []}
                if method == "GET" and path == "/guilds/g_1/threads/active":
                    return {"threads": self.threads}
                if method == "GET" and path.startswith("/channels/") and "/messages" in path:
                    tid = path.split("/")[2]
                    return self.messages.get(tid, [])
                if method == "POST" and path.startswith("/channels/") and path.endswith("/messages"):
                    tid = path.split("/")[2]
                    assert payload is not None
                    self.json_posts.append((tid, payload))
                    msg_obj = {
                        "id": f"bot_json_{len(self.json_posts)}",
                        "author": {"id": "bot_99", "username": "LeafBot"},
                        "content": payload.get("content", ""),
                        "embeds": payload.get("embeds", []),
                        "timestamp": "1970-01-01T00:01:00+00:00",
                    }
                    self.messages[tid].append(msg_obj)
                    return msg_obj
                return {}

            def request_multipart(
                self,
                method: str,
                path: str,
                payload: dict[str, Any],
                files: list[tuple[str, bytes, str]],
            ) -> Any:
                tid = path.split("/")[2]
                self.multipart_calls.append((tid, payload, files))
                att_meta = (payload.get("attachments") or [{}])[0]
                fn = files[0][0]
                cdn_url = f"https://cdn.discordapp.com/attachments/{tid}/987654321/{fn}"
                msg_obj = {
                    "id": f"bot_mp_{len(self.multipart_calls)}",
                    "author": {"id": "bot_99", "username": "LeafBot"},
                    "content": payload.get("content", ""),
                    "attachments": [
                        {
                            "id": "987654321",
                            "filename": fn,
                            "description": att_meta.get("description", ""),
                            "url": cdn_url,
                        }
                    ],
                    "embeds": payload.get("embeds", []),
                    "timestamp": "1970-01-01T00:01:00+00:00",
                }
                self.messages[tid].append(msg_obj)
                return msg_obj

        with tempfile.TemporaryDirectory() as tmp:
            client = FakeMultipartDiscordClient()
            poller = DiscordForumPoller(
                client=client,  # type: ignore[arg-type]
                engine=engine,
                guild_id="g_1",
                state_path=pathlib.Path(tmp) / "bot-state.json",
                settle_seconds=0.0,
                upload_visual_attachments=True,
                now_fn=lambda: 100.0,
            )
            actions = poller.poll_once()
            self.assertEqual(2, len(actions))
            # Thread 1 was a cache miss -> uploaded via multipart once
            self.assertEqual(1, len(client.multipart_calls))
            self.assertEqual("th_cache_1", client.multipart_calls[0][0])
            # Thread 2 hit the versioned Discord cache harvested from Thread 1 -> sent via JSON POST reusing the Discord CDN image URL!
            self.assertEqual(1, len(client.json_posts))
            self.assertEqual("th_cache_2", client.json_posts[0][0])
            reused_embeds = client.json_posts[0][1].get("embeds") or []
            self.assertEqual(1, len(reused_embeds))
            self.assertTrue(
                reused_embeds[0]["image"]["url"].startswith("https://cdn.discordapp.com/attachments/th_cache_1/987654321/")
            )

    def test_ast_cross_reference_map_and_five_minute_repo_tool_planner(self) -> None:
        engine = SupportBotEngine(cooldown_seconds=0.0)
        self.addCleanup(engine.close)

        post = ForumPostContext(
            thread_id="th_ast_plan_1",
            author_id="u_501",
            author_name="dev_operator",
            title="EditorChannel pacing and AbstractVerticalAdjustor maxSlope with syncLoading",
            body=(
                "We saw `at io.github.dailystruggle.rtp.common.commands.editor.channel.EditorChannel.send(EditorChannel.java:120)` "
                "and `AbstractVerticalAdjustor.maxHillAngle(AbstractVerticalAdjustor.java:89)` while testing `maxSlope: 2.5` "
                "and `syncLoading: true` during warmup countdown."
            ),
            applied_tags=("🌿 LeafRTP", "Paper / Spigot"),
            timestamp=200.0,
        )
        report = engine.workbench.run_verification(
            conn=engine.conn,
            query_text=f"{post.title}\n{post.body}",
            include_gradle_tests=True,
            force_mineflayer=True,
            window_budget_seconds=300.0,
        )
        xref = report.ast_cross_references
        self.assertTrue(len(xref.ast_edges_traversed) > 0)
        self.assertIn("EditorChannel", xref.linked_java_classes)
        self.assertTrue(any("maxSlope" in k for k in xref.linked_config_keys))
        self.assertTrue(len(xref.ast_conflicts) > 0)
        self.assertTrue(
            any("EditorChannel" in t["test_class"] and t["module"] == ":rtp-core" for t in xref.targeted_gradle_tests)
        )
        # Verify 5-minute (300s) tool plan includes cache, ast, console, local_webpage, screenshotting, mineflayer, and gradle_test
        self.assertEqual(300.0, report.window_budget_seconds)
        categories = [s.category for s in report.tool_plan if s.selected]
        for expected_cat in ("cache", "ast", "console", "local_webpage", "screenshotting", "mineflayer", "gradle_test"):
            self.assertIn(expected_cat, categories)

    def test_llm_optional_workbench_tool_loop_and_submit_reply_flexibility(self) -> None:
        # 1. Purely doc-driven question: tools are offered ("auto"), but the model answers directly in 1 pass with 0 tool calls
        doc_payloads: list[dict[str, Any]] = []

        def single_pass_doc_llm(url: str, payload: dict[str, Any], timeout: float) -> dict[str, Any]:
            doc_payloads.append(payload)
            return {
                "choices": [
                    {
                        "message": {
                            "content": (
                                "<think>Purely doc-driven question about Bare /rtp GUI toggle.</think>\n"
                                "- **Disable GUI on Bare `/rtp`:** Set `openOnBareRtp: false` in `addons/guimenu.yml` "
                                "using `/rtp config guimenu openOnBareRtp=false` - "
                                "<https://dailystruggle.github.io/RTP/admin/configuration/ADDONS/>"
                            )
                        }
                    }
                ]
            }

        doc_synth = LocalLlmSynthesizer(
            endpoint="http://127.0.0.1:11434/v1/chat/completions",
            http_post=single_pass_doc_llm,
        )
        doc_engine = SupportBotEngine(cooldown_seconds=0.0, llm_synthesizer=doc_synth)
        self.addCleanup(doc_engine.close)

        d_doc = doc_engine.handle_new_thread(
            ForumPostContext(
                thread_id="th_doc_only_1",
                author_id="u_doc_1",
                author_name="admin_doc",
                title="Disable GUI menu on bare /rtp",
                body="How do I disable the GUI menu when players run bare /rtp?",
                applied_tags=("🌿 LeafRTP", "Paper / Spigot"),
                timestamp=300.0,
            )
        )
        self.assertEqual(1, len(doc_payloads))
        self.assertEqual("auto", doc_payloads[0].get("tool_choice"))
        tool_names = [t["function"]["name"] for t in doc_payloads[0].get("tools", [])]
        self.assertEqual(
            [
                "console_log_sandbox",
                "local_webpage_operations",
                "multi_format_screenshotting",
                "mineflayer_protocol_bot",
                "submit_reply",
            ],
            tool_names,
        )
        self.assertEqual([], doc_synth.last_tool_calls)
        self.assertIn("openOnBareRtp: false", d_doc.reply_text)

        # 2. Multi-turn verification tool loop + `submit_reply` handoff:
        # Turn 1: Model calls `console_log_sandbox` and `local_webpage_operations`
        # Turn 2: Model inspects the sandbox tool output and calls `submit_reply`
        tool_turn_payloads: list[dict[str, Any]] = []

        def multi_turn_tool_llm(url: str, payload: dict[str, Any], timeout: float) -> dict[str, Any]:
            tool_turn_payloads.append(dict(payload, messages=list(payload.get("messages", []))))
            if len(tool_turn_payloads) == 1:
                return {
                    "choices": [
                        {
                            "message": {
                                "content": "<think>Let's test centerRadius=0 in the sandbox and stage the Web Editor preview.</think>",
                                "tool_calls": [
                                    {
                                        "id": "call_sandbox_1",
                                        "type": "function",
                                        "function": {
                                            "name": "console_log_sandbox",
                                            "arguments": '{"commands": ["/rtp config default.yml centerRadius=0"], "yaml_overrides": {"centerRadius": "0"}}',
                                        },
                                    },
                                    {
                                        "id": "call_web_2",
                                        "type": "function",
                                        "function": {
                                            "name": "local_webpage_operations",
                                            "arguments": '{"target_file": "definitions/regions/default.yml", "target_key": "shape.centerRadius", "recommended_value": "0"}',
                                        },
                                    },
                                ],
                            }
                        }
                    ]
                }
            # Turn 2: verify that `role: "tool"` outputs were appended to messages
            tool_msgs = [m for m in payload["messages"] if m.get("role") == "tool"]
            assert len(tool_msgs) == 2
            web_res = next(m for m in tool_msgs if m.get("name") == "local_webpage_operations")
            assert "https://dailystruggle.github.io/RTP/editor/" in web_res["content"]
            return {
                "choices": [
                    {
                        "message": {
                            "content": "<think>Both sandbox and Web Editor preview succeeded.</think>",
                            "tool_calls": [
                                {
                                    "id": "call_submit_3",
                                    "type": "function",
                                    "function": {
                                        "name": "submit_reply",
                                        "arguments": (
                                            '{"bullets": ['
                                            '"- **Remove Inner Spawn Hole (`centerRadius`):** Set `shape.centerRadius: 0` in `definitions/regions/default.yml` via `/rtp config regions default shape.centerRadius=0` - <https://dailystruggle.github.io/RTP/admin/configuration/REGIONS/#circle>",'
                                            '"- **Interactive Web Editor Preview:** Open the staged region preview at <https://dailystruggle.github.io/RTP/editor/?file=regions%2Fdefault.yml&key=centerRadius&value=0&search=centerRadius&intent=circle-region>"'
                                            '], "attach_verification_report": true}'
                                        ),
                                    },
                                }
                            ],
                        }
                    }
                ]
            }

        tool_synth = LocalLlmSynthesizer(
            endpoint="http://127.0.0.1:11434/v1/chat/completions",
            http_post=multi_turn_tool_llm,
        )
        tool_engine = SupportBotEngine(cooldown_seconds=0.0, llm_synthesizer=tool_synth)
        self.addCleanup(tool_engine.close)

        d_tool = tool_engine.handle_new_thread(
            ForumPostContext(
                thread_id="th_tool_loop_1",
                author_id="u_tool_1",
                author_name="admin_tool",
                title="Remove hole in middle of circle region",
                body="How do I remove the hole in the middle of my circle region?",
                applied_tags=("🌿 LeafRTP", "Paper / Spigot"),
                timestamp=400.0,
            )
        )
        self.assertEqual(2, len(tool_turn_payloads))
        self.assertEqual(
            ["console_log_sandbox", "local_webpage_operations", "submit_reply"],
            [c["name"] for c in tool_synth.last_tool_calls],
        )
        self.assertIn("shape.centerRadius=0", d_tool.reply_text)
        self.assertIn("https://dailystruggle.github.io/RTP/editor/?file=", d_tool.reply_text)
        self.assertIsNotNone(d_tool.verification_report)
        self.assertTrue(d_tool.editor_deep_link.startswith("https://dailystruggle.github.io/RTP/editor/"))


if __name__ == "__main__":
    unittest.main()
