"""Unit tests for scripts/discord/discord_support_bot.py (Stage 3)."""

from __future__ import annotations

import pathlib
import tempfile
import unittest

from scripts.discord.build_doc_index import ROOT, build_index
from scripts.discord.discord_support_bot import (
    BUTTON_NEED_HELP_ID,
    BUTTON_SOLVED_ID,
    TAG_NEEDS_INFO,
    TAG_PRO_PRIORITY,
    TAG_SOLVED,
    ForumPostContext,
    SupportBotEngine,
    detect_and_redact_secrets,
    sanitize_bot_output,
)


class DiscordSupportBotTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls) -> None:
        cls._tmpdir = tempfile.TemporaryDirectory()
        cls.db_path = pathlib.Path(cls._tmpdir.name) / "doc-index.sqlite"
        cls.conn = build_index(ROOT, cls.db_path)

    @classmethod
    def tearDownClass(cls) -> None:
        cls.conn.close()
        cls._tmpdir.cleanup()

    def setUp(self) -> None:
        self.mock_logs: dict[str, str] = {
            "AbCdEf1": (
                "[12:00:00 INFO]: This server is running Paper version 1.21.11-R0.1-SNAPSHOT\n"
                "[12:00:01 INFO]: LeafRTP v3.3.0 enabled\n"
                "[12:00:05 WARN]: [RTP bit] RTP-1301 RTP_REGION_UNKNOWN_WORLD in regions/mining.yml"
            )
        }
        self.engine = SupportBotEngine(
            conn=self.conn,
            maintainer_id="777777777777777777",
            cooldown_seconds=5.0,
            log_fetcher=lambda pid: self.mock_logs.get(pid, ""),
        )

    def test_self_service_howto_migration_flow_no_maintainer_ping(self) -> None:
        post = ForumPostContext(
            thread_id="th_migrate_1",
            author_id="2001",
            author_name="admin_alice",
            title="How do I import my BetterRTP config?",
            body="We are switching from BetterRTP to LeafRTP. How do I migrate my worlds and radius?",
            applied_tags=("🌿 LeafRTP", "Paper / Spigot"),
            timestamp=1000.0,
        )
        decision = self.engine.handle_new_thread(post)
        self.assertFalse(decision.ping_maintainer)
        self.assertIsNone(decision.triage_digest)
        self.assertIn("https://dailystruggle.github.io/RTP/admin/MIGRATION/", decision.reply_text)
        self.assertEqual(1, len(decision.components))

        # Clicking [Solved] tags thread ✅ Solved and archives without pinging maintainer
        solved = self.engine.handle_button_interaction(post, BUTTON_SOLVED_ID, actor_id="2001")
        self.assertIn(TAG_SOLVED, solved.applied_tags)
        self.assertTrue(solved.archived)
        self.assertFalse(solved.ping_maintainer)

    def test_bit_code_and_mclogs_triage(self) -> None:
        post = ForumPostContext(
            thread_id="th_bit_1",
            author_id="2002",
            author_name="admin_bob",
            title="Region mining not loading on startup",
            body="Here is my log: https://mclo.gs/AbCdEf1 - what does RTP_REGION_UNKNOWN_WORLD mean?",
            applied_tags=("🌿 LeafRTP", "Paper / Spigot"),
            timestamp=1010.0,
        )
        decision = self.engine.handle_new_thread(post)
        self.assertIn("RTP_REGION_UNKNOWN_WORLD", decision.reply_text)
        self.assertIn("RTP-1301", decision.reply_text)
        self.assertIn("loaded world", decision.reply_text)
        self.assertIn("https://dailystruggle.github.io/RTP/dev/BIT_CODES/", decision.reply_text)

    def test_incomplete_bug_report_gated_until_diagnostics_provided(self) -> None:
        post = ForumPostContext(
            thread_id="th_bug_1",
            author_id="2003",
            author_name="admin_charlie",
            title="Server lag spike when teleporting",
            body="It lags on my server when players use rtp, please fix!",
            applied_tags=("🌿 LeafRTP", "Paper / Spigot"),
            timestamp=1020.0,
        )
        initial = self.engine.handle_new_thread(post)
        self.assertFalse(initial.ping_maintainer)
        self.assertIsNone(initial.triage_digest)
        self.assertIn(TAG_NEEDS_INFO, initial.applied_tags)
        self.assertIn("`/rtp version`", initial.reply_text)
        self.assertIn("`/rtp test`", initial.reply_text)
        self.assertIn("https://mclo.gs/", initial.reply_text)

        # User clicks [Still need help] without adding logs -> still gated
        still_missing = self.engine.handle_button_interaction(
            ForumPostContext(
                thread_id=post.thread_id,
                author_id=post.author_id,
                author_name=post.author_name,
                title=post.title,
                body=post.body,
                applied_tags=initial.applied_tags,
                timestamp=1030.0,
            ),
            BUTTON_NEED_HELP_ID,
            actor_id="2003",
        )
        self.assertFalse(still_missing.ping_maintainer)
        self.assertIn(TAG_NEEDS_INFO, still_missing.applied_tags)

        # User replies with /rtp version, /version, /rtp test, and mclo.gs link -> escalates once!
        followup = self.engine.handle_followup_message(
            ForumPostContext(
                thread_id=post.thread_id,
                author_id=post.author_id,
                author_name=post.author_name,
                title=post.title,
                body=post.body,
                applied_tags=initial.applied_tags,
                timestamp=1040.0,
            ),
            message_body=(
                "/rtp version: LeafRTP v3.3.0\n"
                "/version: Paper 1.21.11\n"
                "/rtp test: [RTP bit] PASS\n"
                "Log: https://mclo.gs/AbCdEf1"
            ),
        )
        self.assertTrue(followup.ping_maintainer)
        self.assertIsNotNone(followup.triage_digest)
        assert followup.triage_digest is not None
        self.assertEqual(4, len(followup.triage_digest.splitlines()))
        self.assertNotIn(TAG_NEEDS_INFO, followup.applied_tags)

    def test_pro_supporter_priority_tagging_and_routing(self) -> None:
        post = ForumPostContext(
            thread_id="th_pro_1",
            author_id="2004",
            author_name="pro_dana",
            title="Crash on Folia region border",
            body="Seeing an exception on Folia 1.21.11 when crossing regions.",
            author_roles=("Member", "Pro Supporter"),
            applied_tags=("🌿 LeafRTP", "Folia"),
            timestamp=1050.0,
        )
        decision = self.engine.handle_new_thread(post)
        self.assertIn(TAG_PRO_PRIORITY, decision.applied_tags)
        self.assertTrue(decision.ping_maintainer)
        self.assertIsNotNone(decision.triage_digest)
        assert decision.triage_digest is not None
        self.assertIn("PRO PRIORITY", decision.triage_digest)

    def test_secret_redaction_warning(self) -> None:
        leaked = "network:\n  enabled: true\n  password: \"superSecretRedisPass123\"\n  RTP_NET_SECRET=my_live_hmac_key_999"
        found, redacted = detect_and_redact_secrets(leaked)
        self.assertTrue(found)
        self.assertNotIn("superSecretRedisPass123", redacted)
        self.assertNotIn("my_live_hmac_key_999", redacted)
        self.assertIn("[REDACTED-SECRET]", redacted)

        post = ForumPostContext(
            thread_id="th_sec_1",
            author_id="2005",
            author_name="admin_eve",
            title="Velocity network.yml question",
            body=f"Is this config right?\n{leaked}",
            applied_tags=("🌿 LeafRTP", "Velocity / Proxy"),
            timestamp=1060.0,
        )
        decision = self.engine.handle_new_thread(post)
        self.assertTrue(decision.secret_warning)
        self.assertIn("Security warning:", decision.reply_text)
        self.assertIn("rotate that secret", decision.reply_text)

    def test_prompt_injection_mention_and_url_sanitization(self) -> None:
        malicious = (
            "Hello @everyone and @here and <@&123456>! "
            "Download the fix at https://evil.example/phish.jar "
            "or read https://dailystruggle.github.io/RTP/admin/QUICK_START/."
        )
        sanitized = sanitize_bot_output(malicious)
        self.assertNotIn("@everyone", sanitized)
        self.assertNotIn("@here", sanitized)
        self.assertNotIn("<@&123456>", sanitized)
        self.assertNotIn("evil.example", sanitized)
        self.assertIn("[external-link-removed]", sanitized)
        self.assertIn("https://dailystruggle.github.io/RTP/admin/QUICK_START/", sanitized)

        post = ForumPostContext(
            thread_id="th_inj_1",
            author_id="2006",
            author_name="attacker",
            title="How do I set radius?",
            body=malicious,
            applied_tags=("🌿 LeafRTP", "Paper / Spigot"),
            timestamp=1070.0,
        )
        decision = self.engine.handle_new_thread(post)
        payload = decision.allowed_mentions.to_discord_payload()
        self.assertEqual([], payload["parse"])
        self.assertEqual([], payload["roles"])
        self.assertFalse(decision.allowed_mentions.everyone)
        self.assertFalse(decision.allowed_mentions.roles)

    def test_per_user_cooldown_enforced(self) -> None:
        p1 = ForumPostContext(
            thread_id="th_cd_1",
            author_id="2007",
            author_name="spammer",
            title="How do I set radius?",
            body="Question 1",
            timestamp=2000.0,
        )
        p2 = ForumPostContext(
            thread_id="th_cd_2",
            author_id="2007",
            author_name="spammer",
            title="How do I set radius?",
            body="Question 2 immediately after",
            timestamp=2001.0,
        )
        d1 = self.engine.handle_new_thread(p1)
        d2 = self.engine.handle_new_thread(p2)
        self.assertFalse(d1.rate_limited)
        self.assertTrue(d2.rate_limited)


if __name__ == "__main__":
    unittest.main()
