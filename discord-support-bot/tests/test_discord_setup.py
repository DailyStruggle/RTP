"""Unit tests for discord-support-bot/discord_setup.py and discord-support-bot/embeds.json (Stage 2)."""

from __future__ import annotations

import copy
import pathlib
import sys
import tempfile
import unittest
from unittest.mock import patch

BOT_DIR = pathlib.Path(__file__).resolve().parent.parent
if str(BOT_DIR) not in sys.path:
    sys.path.insert(0, str(BOT_DIR))

from discord_setup import (
    CHANNEL_FLAG_PINNED,
    CHANNEL_FLAG_REQUIRE_TAG,
    CHANNEL_TYPES,
    PERM_READ_MESSAGE_HISTORY,
    PERM_SEND_MESSAGES,
    PERM_VIEW_CHANNEL,
    build_archive_overwrites,
    build_forum_available_tags,
    compute_provisioning_plan,
    default_legacy_guild_snapshot,
    load_spec,
    main,
    reset_test_server,
    resolve_discord_bot_token,
    resolve_discord_guild_id,
    resolve_discord_maintainer_id,
    validate_rule_d006_copy,
)


class DiscordSetupTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls) -> None:
        cls.spec = load_spec()
        cls.guild_id = "123456789012345678"

    def test_rule_d006_copy_hygiene(self) -> None:
        violations = validate_rule_d006_copy(self.spec)
        self.assertEqual([], violations, f"Rule D-006 violations found: {violations}")

        # Verify that em-dash or hype buzzword is caught mechanically
        bad_spec = copy.deepcopy(self.spec)
        bad_spec["channel_embeds"]["start-here"][0]["description"] += " — blazingly fast!"
        caught = validate_rule_d006_copy(bad_spec)
        self.assertGreaterEqual(len(caught), 2)

    def test_five_pinned_forum_threads_defined(self) -> None:
        pinned = self.spec.get("pinned_forum_threads", [])
        self.assertEqual(5, len(pinned))
        titles = [p["title"] for p in pinned]
        self.assertTrue(any("/rtp test" in t for t in titles))
        self.assertTrue(any("Radius" in t for t in titles))
        self.assertTrue(any("openOnBareRtp" in t for t in titles))
        self.assertTrue(any("/rtp config import" in t for t in titles))
        self.assertTrue(any("Folia, Fabric & NeoForge" in t for t in titles))

    def test_forum_tags_cover_project_platform_and_status(self) -> None:
        tags = build_forum_available_tags(self.spec["forum_tags"])
        rendered_labels = {
            f"{t['emoji_name']} {t['name']}" if t.get("emoji_name") else t["name"]
            for t in tags
        }
        for t in tags:
            if t.get("emoji_name"):
                self.assertFalse(
                    t["name"].startswith(t["emoji_name"]),
                    f"Tag name {t['name']!r} duplicates emoji_name {t['emoji_name']!r}",
                )
        for expected in (
            "🌿 LeafRTP",
            "💤 OnePlayerSleep",
            "🌲 TreeGen",
            "🔬 Server Fuzzer / Tools",
            "📦 API / Addons",
            "Paper / Spigot",
            "Folia",
            "Fabric",
            "NeoForge",
            "Velocity / Proxy",
            "✅ Solved",
            "⏳ Needs Info / Logs",
            "🐛 Confirmed Bug",
            "💎 Pro Priority",
        ):
            self.assertIn(expected, rendered_labels)

    def test_dry_run_diff_against_legacy_leafcraft_guild(self) -> None:
        legacy_channels = default_legacy_guild_snapshot(self.guild_id)
        plan = compute_provisioning_plan(
            current_channels=legacy_channels,
            current_roles=[],
            existing_threads=[],
            spec=self.spec,
            guild_id=self.guild_id,
        )

        # 1. Never deletes any channel
        ops = [a.op for a in plan.actions]
        self.assertNotIn("DELETE_CHANNEL", ops)

        # 2. Moves legacy flat channels into ARCHIVE (Read-Only) with VIEW=true, HISTORY=true, SEND=false
        archived = {a.target: a for a in plan.actions if a.op == "ARCHIVE_CHANNEL"}
        for expected_legacy in (
            "rules",
            "welcome",
            "role-assignment",
            "info",
            "faq",
            "recommended-specs",
            "issues",
            "support",
            "feature-requests",
            "ops-support",
            "treegen-support",
        ):
            self.assertIn(expected_legacy, archived)

        # Flat #support is renamed to #old-support when archived
        self.assertEqual("old-support", archived["support"].payload["name"])

        overwrite = archived["support"].payload["permission_overwrites"][0]
        self.assertEqual(self.guild_id, overwrite["id"])
        allow_bits = int(overwrite["allow"])
        deny_bits = int(overwrite["deny"])
        self.assertTrue(allow_bits & PERM_VIEW_CHANNEL)
        self.assertTrue(allow_bits & PERM_READ_MESSAGE_HISTORY)
        self.assertTrue(deny_bits & PERM_SEND_MESSAGES)

        # 3. Creates #support as GUILD_FORUM with REQUIRE_TAG flag
        forum_actions = [a for a in plan.actions if a.op == "CREATE_FORUM_CHANNEL"]
        self.assertEqual(1, len(forum_actions))
        forum_payload = forum_actions[0].payload
        self.assertEqual(CHANNEL_TYPES["GUILD_FORUM"], forum_payload["type"])
        self.assertTrue(forum_payload["flags"] & CHANNEL_FLAG_REQUIRE_TAG)
        self.assertEqual("✅", forum_payload["default_reaction_emoji"]["emoji_name"])

        # 4. Preserves #bbb-logs in ADMIN without recreating or archiving it
        self.assertNotIn("bbb-logs", archived)
        created_targets = {a.target for a in plan.actions if a.op == "CREATE_CHANNEL"}
        self.assertNotIn("bbb-logs", created_targets)
        self.assertIn("bot-triage-queue", created_targets)
        self.assertIn("start-here", created_targets)
        self.assertIn("benchmarks", created_targets)

        # 5. Creates all 5 Forum reference threads, pinning only the primary "Read First" guide
        # (Discord GUILD_FORUM channels enforce a 1-pinned-post limit per forum channel).
        pinned_actions = [a for a in plan.actions if a.op == "CREATE_PINNED_THREAD"]
        self.assertEqual(5, len(pinned_actions))
        self.assertEqual(CHANNEL_FLAG_PINNED, pinned_actions[0].payload["flags"])
        for action in pinned_actions[1:]:
            self.assertEqual(0, action.payload["flags"])

    def test_idempotency_on_already_provisioned_guild(self) -> None:
        # Construct an already-provisioned guild state matching the spec
        archive_overwrites = build_archive_overwrites(self.guild_id)
        provisioned_channels = [
            {
                "id": "cat_archive",
                "name": self.spec["archive_category"],
                "type": CHANNEL_TYPES["GUILD_CATEGORY"],
                "permission_overwrites": archive_overwrites,
            },
            {
                "id": "ch_old_support",
                "name": "old-support",
                "type": CHANNEL_TYPES["GUILD_TEXT"],
                "parent_id": "cat_archive",
                "permission_overwrites": archive_overwrites,
            },
        ]

        for cat_spec in self.spec["categories"]:
            cat_id = f"cat_{cat_spec['name']}"
            cat_overwrites = (
                [{"id": self.guild_id, "type": 0, "allow": "0", "deny": str(PERM_VIEW_CHANNEL)}]
                if cat_spec.get("private")
                else []
            )
            provisioned_channels.append(
                {
                    "id": cat_id,
                    "name": cat_spec["name"],
                    "type": CHANNEL_TYPES["GUILD_CATEGORY"],
                    "permission_overwrites": cat_overwrites,
                }
            )
            for ch_spec in cat_spec["channels"]:
                ch_type = CHANNEL_TYPES[ch_spec["type"]]
                if ch_type == CHANNEL_TYPES["GUILD_FORUM"]:
                    provisioned_channels.append(
                        {
                            "id": "ch_support_forum",
                            "name": ch_spec["name"],
                            "type": ch_type,
                            "parent_id": cat_id,
                            "topic": ch_spec["topic"],
                            "flags": CHANNEL_FLAG_REQUIRE_TAG,
                            "available_tags": build_forum_available_tags(self.spec["forum_tags"]),
                        }
                    )
                else:
                    if cat_spec.get("private"):
                        overwrites = cat_overwrites
                    elif ch_spec.get("read_only"):
                        overwrites = archive_overwrites
                    else:
                        overwrites = []
                    provisioned_channels.append(
                        {
                            "id": f"ch_{ch_spec['name']}",
                            "name": ch_spec["name"],
                            "type": ch_type,
                            "parent_id": cat_id,
                            "topic": ch_spec["topic"],
                            "permission_overwrites": overwrites,
                        }
                    )

        provisioned_roles = [{"id": "role_pro", "name": "Pro Supporter"}]
        provisioned_threads = [
            {
                "id": f"th_{i}",
                "name": p["title"],
                "flags": CHANNEL_FLAG_PINNED if i == 0 else 0,
            }
            for i, p in enumerate(self.spec["pinned_forum_threads"])
        ]

        plan = compute_provisioning_plan(
            current_channels=provisioned_channels,
            current_roles=provisioned_roles,
            existing_threads=provisioned_threads,
            spec=self.spec,
            guild_id=self.guild_id,
            published_embed_channels={"start-here", "benchmarks"},
        )
        self.assertEqual([], plan.actions, f"Expected 0 actions on re-run, got: {plan.actions}")

    def test_cli_dry_run_exits_zero(self) -> None:
        rc = main(["--dry-run"])
        self.assertEqual(0, rc)

    def test_resolve_credentials_and_reset_test_server(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_home = pathlib.Path(tmp)
            (tmp_home / ".DISCORD_BOT_TOKEN").write_text("test_bot_token_123\n", encoding="utf-8")
            (tmp_home / ".DISCORD_TEST_SERVER_ID").write_text("222333444555666777\n", encoding="utf-8")
            (tmp_home / ".DISCORD_MAINTAINER_ID").write_text("DISCORD_MAINTAINER_ID='888999000111222333'\n", encoding="utf-8")
            state_file = tmp_home / "bot-state.json"
            state_file.write_text("{}", encoding="utf-8")

            with patch.dict("os.environ", {}, clear=True), patch(
                "discord_setup.pathlib.Path.expanduser",
                side_effect=lambda self_path=None: tmp_home / str(self_path).lstrip("~/") if self_path else tmp_home,
            ):
                # Test direct file reading via patched expanduser on Path instances
                pass

            with patch.dict("os.environ", {"HOME": str(tmp_home), "USERPROFILE": str(tmp_home)}, clear=True):
                self.assertEqual("test_bot_token_123", resolve_discord_bot_token())
                self.assertEqual("222333444555666777", resolve_discord_guild_id())
                self.assertEqual("888999000111222333", resolve_discord_maintainer_id())

            deleted_paths: list[str] = []
            ref_title = self.spec["pinned_forum_threads"][0]["title"]

            class _FakeResetClient:
                def request(self, method: str, path: str, payload: object = None) -> object:
                    if method == "GET" and path.endswith("/channels"):
                        return [
                            {"id": "ch_sup", "name": "support", "type": CHANNEL_TYPES["GUILD_FORUM"]},
                            {"id": "ch_tri", "name": "bot-triage-queue", "type": CHANNEL_TYPES["GUILD_TEXT"]},
                        ]
                    if method == "GET" and path.endswith("/threads/active"):
                        return {
                            "threads": [
                                {"id": "th_ref_1", "parent_id": "ch_sup", "name": ref_title, "flags": CHANNEL_FLAG_PINNED},
                                {"id": "th_user_1", "parent_id": "ch_sup", "name": "User test thread 1", "flags": 0},
                            ]
                        }
                    if method == "GET" and "/threads/archived/public" in path:
                        return {
                            "threads": [
                                {"id": "th_user_2", "parent_id": "ch_sup", "name": "Archived user test thread", "flags": 0},
                            ]
                        }
                    if method == "GET" and "/channels/ch_tri/messages" in path:
                        return [{"id": "msg_tri_1"}]
                    if method == "DELETE":
                        deleted_paths.append(path)
                        return {}
                    return {}

            summary = reset_test_server(
                _FakeResetClient(),  # type: ignore[arg-type]
                self.guild_id,
                self.spec,
                state_path=state_file,
            )
            self.assertEqual(2, summary["deleted_threads"])
            self.assertEqual(1, summary["deleted_triage_messages"])
            self.assertEqual(1, summary["removed_state_files"])
            self.assertIn("/channels/th_user_1", deleted_paths)
            self.assertIn("/channels/th_user_2", deleted_paths)
            self.assertNotIn("/channels/th_ref_1", deleted_paths)
            self.assertFalse(state_file.exists())


if __name__ == "__main__":
    unittest.main()
