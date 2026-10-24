#!/usr/bin/env python3
"""
Safe, read-only Discord `#support` Forum documentation retrieval and triage bot.

Capabilities:
  1. Pro Supporter Priority (`#bbb-logs` / `@Pro Supporter`):
     Detects when a thread author holds `@Pro Supporter`, attaches the
     `💎 Pro Priority` Forum tag, and routes to `#bot-triage-queue`.
  2. Deterministic `[RTP bit]` & `mclo.gs` Triage:
     Scans new Forum threads and linked `https://mclo.gs/<id>` logs (via
     read-only `https://api.mclo.gs/1/raw/<id>`) for `[RTP bit]` findings
     and replies immediately with the exact `BIT_CODES.md` remediation.
  3. Closed-World FTS5 Doc & Config Answering:
     Queries `doc-index.sqlite` (`doc_sections` + `config_keys`) for setup and
     configuration questions, replying in Rule D-006 first-person voice with
     exact commands (`/rtp editor`, `/rtp admin setup`, `/rtp config`), YAML
     paths, canonical `<https://dailystruggle.github.io/RTP/...>` links, and
     `[✅ Solved]` / `[❓ Still need help]` buttons.
  4. Two-Stage Maintainer Escalation Gate:
     Never pings the maintainer on initial question intake unless the post is
     from a `@Pro Supporter` or already includes `/rtp version`, `/version`,
     `/rtp test` output, and a log link. Applies `⏳ Needs Info / Logs` when
     diagnostics are missing and posts a 4-line digest in `#bot-triage-queue`
     once supplied.
  5. Security & Prompt-Injection Hardening:
     Zero shell or filesystem write tools, `AllowedMentions(everyone=False, roles=False)`,
     strict outgoing URL domain allowlist, automatic secret/password paste warning,
     and per-user cooldowns.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sqlite3
import time
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from typing import Any, Callable

from scripts.discord.build_doc_index import (
    DEFAULT_DB_PATH,
    ROOT,
    build_index,
    extract_bit_codes,
    search_config_keys,
    search_docs,
)

ALLOWED_DOMAINS = frozenset({
    "dailystruggle.github.io",
    "github.com",
    "www.github.com",
    "modrinth.com",
    "www.modrinth.com",
    "spigotmc.org",
    "www.spigotmc.org",
    "builtbybit.com",
    "www.builtbybit.com",
    "mclo.gs",
    "api.mclo.gs",
})

TAG_SOLVED = "✅ Solved"
TAG_NEEDS_INFO = "⏳ Needs Info / Logs"
TAG_CONFIRMED_BUG = "🐛 Confirmed Bug"
TAG_PRO_PRIORITY = "💎 Pro Priority"

BUTTON_SOLVED_ID = "rtp_solved"
BUTTON_NEED_HELP_ID = "rtp_need_help"

MCLOGS_URL_RE = re.compile(r"https?://(?:www\.)?mclo\.gs/([A-Za-z0-9]{5,32})")
ANY_URL_RE = re.compile(r"https?://[^\s<>)\]]+")
ROLE_OR_EVERYONE_MENTION_RE = re.compile(r"@(?:everyone|here)|<@&\d+>")

SECRET_PATTERNS = (
    re.compile(r"(?i)\b(?:password|passwd|redis_password|db_password)\s*[:=]\s*['\"]?([^\s'\"]{4,})['\"]?"),
    re.compile(r"(?i)\b(?:secret|net_secret|jwt_secret|token)\s*[:=]\s*['\"]?(?!RTP_NET_SECRET\b)([^\s'\"]{6,})['\"]?"),
    re.compile(r"(?i)\bRTP_NET_SECRET\s*=\s*['\"]?([^\s'\"]{4,})['\"]?"),
    re.compile(r"rediss?://[^/\s:@]*:([^@\s]{4,})@"),
)

BUG_REPORT_KEYWORDS = re.compile(
    r"(?i)\b(bug|crash|exception|stack\s*trace|error|lag|stutter|freeze|stall|broken|doesn'?t\s+work|not\s+working|watchdog|leak)\b"
)
HOWTO_KEYWORDS = re.compile(
    r"(?i)\b(how\s+to|how\s+do\s+i|how\s+can\s+i|where\s+do\s+i|configure|setting|radius|spawn|border|gui|menu|openonbarertp|import|migrate|betterrtp|ezrtp|justrtp|cooldown|delay|price|economy|biome|folia|fabric|neoforge|velocity)\b"
)


@dataclass(frozen=True)
class AllowedMentions:
    everyone: bool = False
    roles: bool = False
    users: tuple[str, ...] = ()

    def to_discord_payload(self) -> dict[str, Any]:
        return {
            "parse": [],
            "users": list(self.users),
            "roles": [],
            "replied_user": False,
        }


@dataclass(frozen=True)
class ForumPostContext:
    thread_id: str
    author_id: str
    author_name: str
    title: str
    body: str
    author_roles: tuple[str, ...] = ()
    applied_tags: tuple[str, ...] = ()
    timestamp: float = field(default_factory=time.time)


@dataclass(frozen=True)
class BotDecision:
    reply_text: str
    applied_tags: tuple[str, ...]
    components: list[dict[str, Any]]
    allowed_mentions: AllowedMentions
    triage_digest: str | None = None
    ping_maintainer: bool = False
    secret_warning: bool = False
    rate_limited: bool = False
    archived: bool = False


def build_resolution_buttons() -> list[dict[str, Any]]:
    """Builds the Discord message component row with `[✅ Solved]` and `[❓ Still need help]` buttons."""
    return [
        {
            "type": 1,
            "components": [
                {
                    "type": 2,
                    "style": 3,
                    "label": "Solved",
                    "emoji": {"name": "✅"},
                    "custom_id": BUTTON_SOLVED_ID,
                },
                {
                    "type": 2,
                    "style": 2,
                    "label": "Still need help",
                    "emoji": {"name": "❓"},
                    "custom_id": BUTTON_NEED_HELP_ID,
                },
            ],
        }
    ]


def sanitize_bot_output(text: str) -> str:
    """
    Enforces prompt-injection and output safety guardrails:
      1. Strips `@everyone`, `@here`, and `<@&role>` mentions.
      2. Strips any URL whose domain is outside `ALLOWED_DOMAINS`.
      3. Replaces em/en dashes with ASCII `-` per Rule D-006.
    """
    cleaned = ROLE_OR_EVERYONE_MENTION_RE.sub("[mention-removed]", text)
    cleaned = cleaned.replace("\u2014", "-").replace("\u2013", "-")

    def check_url(match: re.Match[str]) -> str:
        raw_url = match.group(0)
        trailing = ""
        while raw_url and raw_url[-1] in ".,;:!?)":
            trailing = raw_url[-1] + trailing
            raw_url = raw_url[:-1]
        try:
            parsed = urllib.parse.urlparse(raw_url)
            host = (parsed.hostname or "").lower()
        except ValueError:
            return "[external-link-removed]" + trailing
        if host in ALLOWED_DOMAINS:
            return raw_url + trailing
        return "[external-link-removed]" + trailing

    return ANY_URL_RE.sub(check_url, cleaned)


def detect_and_redact_secrets(text: str) -> tuple[bool, str]:
    """
    Detects pasted credentials (`network.yml` secrets, database/Redis passwords)
    and redacts the secret value so it is never echoed back.
    """
    found = False
    redacted = text
    for pat in SECRET_PATTERNS:
        if pat.search(redacted):
            found = True
            redacted = pat.sub(
                lambda m: m.group(0).replace(m.group(1), "[REDACTED-SECRET]"),
                redacted,
            )
    return found, redacted


def extract_mclogs_ids(text: str) -> list[str]:
    """Extracts unique `mclo.gs` paste IDs from a forum post."""
    ids: list[str] = []
    for m in MCLOGS_URL_RE.finditer(text):
        paste_id = m.group(1)
        if paste_id not in ids:
            ids.append(paste_id)
    return ids


def fetch_mclogs_raw(paste_id: str, timeout: float = 5.0, max_bytes: int = 65536) -> str:
    """Fetches raw log text read-only from `https://api.mclo.gs/1/raw/<id>`."""
    if not re.fullmatch(r"[A-Za-z0-9]{5,32}", paste_id):
        return ""
    url = f"https://api.mclo.gs/1/raw/{paste_id}"
    req = urllib.request.Request(
        url,
        method="GET",
        headers={"User-Agent": "LeafRTP-SupportBot/1.0 (+https://github.com/DailyStruggle/RTP)"},
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.read(max_bytes).decode("utf-8", errors="replace")
    except OSError:
        return ""


def inspect_diagnostics(text: str, fetched_logs: str = "") -> dict[str, bool]:
    """
    Checks whether a post (plus any fetched `mclo.gs` log) includes the 4
    diagnostic items required before pinging the maintainer on a bug report:
      1. `rtp_version`: `/rtp version` output or `LeafRTP` build string
      2. `server_version`: `/version` output or server platform/version line
      3. `rtp_test`: `/rtp test` or `[RTP bit]` output
      4. `log_link`: `https://mclo.gs/...` URL or attached log paste
    """
    combined = f"{text}\n{fetched_logs}"
    has_rtp_version = bool(
        re.search(r"(?i)(?:/rtp\s+version|leafrtp\s+v?\d+\.\d+|rtp\s+version\s*[:=]?\s*\d+\.\d+)", combined)
    )
    has_server_version = bool(
        re.search(
            r"(?i)(?:/version\b|this\s+server\s+is\s+running|paper\s+(?:version\s+)?(?:1\.\d+|26\.\d+)|folia\s+(?:1\.\d+|26\.\d+)|fabric\s+(?:1\.\d+|26\.\d+)|neoforge\s+(?:1\.\d+|26\.\d+))",
            combined,
        )
    )
    has_rtp_test = bool(
        re.search(r"(?i)(?:/rtp\s+test\b|\[rtp\s+bit\]|\b(?:ACTION|BIT|HEATMAP|PROBE|RTP|TEST)[-_]\d{1,4}\b|\bRTP_[A-Z0-9_]+\b)", combined)
    )
    has_log_link = bool(MCLOGS_URL_RE.search(text) or fetched_logs.strip())

    return {
        "rtp_version": has_rtp_version,
        "server_version": has_server_version,
        "rtp_test": has_rtp_test,
        "log_link": has_log_link,
    }


def missing_diagnostic_items(diag: dict[str, bool]) -> list[str]:
    """Returns human-readable descriptions of any missing diagnostic items."""
    missing: list[str] = []
    if not diag.get("rtp_version"):
        missing.append("`/rtp version` output")
    if not diag.get("server_version"):
        missing.append("server `/version` output (Paper, Folia, Spigot, Fabric, or NeoForge version)")
    if not diag.get("rtp_test"):
        missing.append("`/rtp test` self-test output")
    if not diag.get("log_link"):
        missing.append("full `logs/latest.log` uploaded to <https://mclo.gs/>")
    return missing


def is_pro_supporter(roles: tuple[str, ...] | list[str]) -> bool:
    """Returns True if the author holds the `@Pro Supporter` role (`#bbb-logs`)."""
    for r in roles:
        low = r.strip().lstrip("@").lower()
        if low in ("pro supporter", "leafrtp pro", "pro"):
            return True
    return False


def _recommend_command_for_key(file_path: str, key_path: str) -> str:
    if file_path == "definitions/regions/default.yml" and key_path.startswith("shape."):
        return f"`/rtp config regions default {key_path}=<value>` (or drag the border in `/rtp editor` / `/rtp admin setup`)"
    if file_path == "addons/guimenu.yml" and key_path == "openOnBareRtp":
        return "set `openOnBareRtp: false` in `addons/guimenu.yml` and run `/rtp reload` (or toggle it in `/rtp admin setup`)"
    section = file_path[:-4] if file_path.endswith(".yml") else file_path
    return f"`/rtp config {section} {key_path}=<value>` (or edit `{file_path}` in `/rtp editor`)"


class SupportBotEngine:
    """
    Stateful, deterministic Forum triage engine backed by `doc-index.sqlite`.
    Operates with zero shell or filesystem-write tools.
    """

    def __init__(
        self,
        conn: sqlite3.Connection | None = None,
        db_path: pathlib.Path = DEFAULT_DB_PATH,
        maintainer_id: str = "999999999999999999",
        cooldown_seconds: float = 10.0,
        log_fetcher: Callable[[str], str] | None = None,
    ) -> None:
        if conn is not None:
            self.conn = conn
            self._owns_conn = False
        else:
            if not db_path.is_file():
                self.conn = build_index(ROOT, db_path)
            else:
                self.conn = sqlite3.connect(str(db_path))
                self.conn.row_factory = sqlite3.Row
            self._owns_conn = True

        self.maintainer_id = str(maintainer_id)
        self.cooldown_seconds = cooldown_seconds
        self.log_fetcher = log_fetcher or fetch_mclogs_raw
        self.escalated_threads: set[str] = set()
        self.user_last_action: dict[str, float] = {}

    def close(self) -> None:
        if self._owns_conn:
            self.conn.close()

    def _check_cooldown(self, user_id: str, now: float) -> bool:
        last = self.user_last_action.get(user_id)
        if last is not None and (now - last) < self.cooldown_seconds:
            return True
        self.user_last_action[user_id] = now
        return False

    def _format_triage_digest(
        self,
        post: ForumPostContext,
        pro_priority: bool,
        bit_keys: list[str],
        mclogs_ids: list[str],
    ) -> str:
        tier = "💎 PRO PRIORITY" if pro_priority else "🐛 VERIFIED INTAKE"
        tags_str = ", ".join(post.applied_tags) if post.applied_tags else "untagged"
        bit_str = ", ".join(bit_keys) if bit_keys else "none"
        logs_str = ", ".join(f"<https://mclo.gs/{pid}>" for pid in mclogs_ids) if mclogs_ids else "inline"
        lines = [
            f"**[{tier}]** Thread `{post.thread_id}` by <@{post.author_id}> ({post.author_name}) - <@{self.maintainer_id}>",
            f"- **Title & Tags:** {post.title.strip()} ({tags_str})",
            f"- **BIT Findings:** `{bit_str}`",
            f"- **Logs:** {logs_str}",
        ]
        return sanitize_bot_output("\n".join(lines))

    def handle_new_thread(self, post: ForumPostContext) -> BotDecision:
        """Evaluates a newly created `#support` Forum thread."""
        tags = list(post.applied_tags)
        pro = is_pro_supporter(post.author_roles)
        if pro and TAG_PRO_PRIORITY not in tags:
            tags.append(TAG_PRO_PRIORITY)

        if self._check_cooldown(post.author_id, post.timestamp):
            return BotDecision(
                reply_text=sanitize_bot_output(
                    "I received your post - please wait a few seconds before sending another request."
                ),
                applied_tags=tuple(tags),
                components=[],
                allowed_mentions=AllowedMentions(users=(post.author_id,)),
                rate_limited=True,
            )

        combined_user_text = f"{post.title}\n{post.body}"
        mclogs_ids = extract_mclogs_ids(combined_user_text)
        fetched_logs = "\n".join(self.log_fetcher(pid) for pid in mclogs_ids if pid)

        has_secret, _ = detect_and_redact_secrets(f"{combined_user_text}\n{fetched_logs}")
        secret_notice = ""
        if has_secret:
            secret_notice = (
                "**Security warning:** It looks like your post or log contains a password or "
                "`network.yml` secret (`RTP_NET_SECRET` / Redis / SQL credential). Please edit "
                "or delete the paste and rotate that secret right away.\n\n"
            )

        # Step 1: Deterministic [RTP bit] & BitCode lookup
        bit_matches = extract_bit_codes(self.conn, f"{combined_user_text}\n{fetched_logs}")
        diag = inspect_diagnostics(combined_user_text, fetched_logs)
        missing_diag = missing_diagnostic_items(diag)
        all_diag_present = len(missing_diag) == 0

        sections: list[str] = []
        if secret_notice:
            sections.append(secret_notice.strip())

        if bit_matches:
            bit_lines = ["I matched the following `[RTP bit]` finding(s) in your post:"]
            for bm in bit_matches[:4]:
                bit_lines.append(
                    f"- **`{bm['key']}`** (`{bm['namespace']}-{int(bm['id']):04d}`, severity `{bm['severity']}`): "
                    f"{bm['remediation']}"
                )
            bit_lines.append(
                "Full catalog: <https://dailystruggle.github.io/RTP/dev/BIT_CODES/>"
            )
            sections.append("\n".join(bit_lines))

        # Step 2: Closed-world FTS5 Doc & Config search for how-to/configuration questions
        has_bug_kw = bool(BUG_REPORT_KEYWORDS.search(combined_user_text))
        is_howto = (bool(HOWTO_KEYWORDS.search(combined_user_text)) or ("?" in combined_user_text)) and not has_bug_kw
        is_bug = has_bug_kw

        if is_howto or not is_bug:
            cfg_hits = search_config_keys(self.conn, combined_user_text, limit=2)
            doc_hits = search_docs(self.conn, combined_user_text, limit=2)
            if cfg_hits or doc_hits:
                ans_lines = ["Here is what I found in the configuration schema and operator docs:"]
                for ch in cfg_hits[:2]:
                    cmd_hint = _recommend_command_for_key(ch["file_path"], ch["key_path"])
                    default_note = f" (default `{ch['default_value']}`)" if ch["default_value"] else ""
                    ans_lines.append(
                        f"- **`{ch['file_path']}` -> `{ch['key_path']}`**{default_note}: "
                        f"use {cmd_hint}."
                    )
                for dh in doc_hits[:2]:
                    ans_lines.append(
                        f"- **{dh['title']} ({dh['heading']}):** <{dh['url']}>"
                    )
                sections.append("\n".join(ans_lines))

        # Step 3: Two-Stage Maintainer Escalation Gate
        should_escalate_now = False
        if pro:
            should_escalate_now = True
        elif all_diag_present and (is_bug or bit_matches):
            should_escalate_now = True

        if not sections or (is_bug and not all_diag_present and not pro):
            if TAG_NEEDS_INFO not in tags and TAG_SOLVED not in tags:
                tags.append(TAG_NEEDS_INFO)
            missing_bullets = "\n".join(f"- {item}" for item in missing_diag)
            sections.append(
                "To investigate a bug or server issue, I need the remaining diagnostic items before I ping the maintainer:\n"
                f"{missing_bullets}\n"
                "Once you reply with those, I will queue this thread in `#bot-triage-queue` right away."
            )
        else:
            sections.append(
                "If this answered your question, click **[✅ Solved]** below. "
                "If you still need help or want to report a bug, click **[❓ Still need help]**."
            )

        triage_digest: str | None = None
        ping_maintainer = False
        if should_escalate_now and post.thread_id not in self.escalated_threads:
            self.escalated_threads.add(post.thread_id)
            ping_maintainer = True
            triage_digest = self._format_triage_digest(
                post=ForumPostContext(
                    thread_id=post.thread_id,
                    author_id=post.author_id,
                    author_name=post.author_name,
                    title=post.title,
                    body=post.body,
                    author_roles=post.author_roles,
                    applied_tags=tuple(tags),
                    timestamp=post.timestamp,
                ),
                pro_priority=pro,
                bit_keys=[str(b["key"]) for b in bit_matches],
                mclogs_ids=mclogs_ids,
            )

        reply = sanitize_bot_output("\n\n".join(sections))
        return BotDecision(
            reply_text=reply,
            applied_tags=tuple(tags),
            components=build_resolution_buttons(),
            allowed_mentions=AllowedMentions(users=(post.author_id,)),
            triage_digest=triage_digest,
            ping_maintainer=ping_maintainer,
            secret_warning=has_secret,
            rate_limited=False,
        )

    def handle_followup_message(self, post: ForumPostContext, message_body: str) -> BotDecision:
        """
        Handles a follow-up message in a thread waiting on `⏳ Needs Info / Logs`.
        Once `/rtp version`, `/version`, `/rtp test`, and `mclo.gs` are present,
        removes `⏳ Needs Info / Logs` and posts the 4-line digest to `#bot-triage-queue`.
        """
        combined = f"{post.title}\n{post.body}\n{message_body}"
        mclogs_ids = extract_mclogs_ids(combined)
        fetched_logs = "\n".join(self.log_fetcher(pid) for pid in mclogs_ids if pid)
        has_secret, _ = detect_and_redact_secrets(f"{message_body}\n{fetched_logs}")
        bit_matches = extract_bit_codes(self.conn, f"{message_body}\n{fetched_logs}")

        diag = inspect_diagnostics(combined, fetched_logs)
        missing_diag = missing_diagnostic_items(diag)
        pro = is_pro_supporter(post.author_roles)

        tags = [t for t in post.applied_tags if t != TAG_NEEDS_INFO]
        if pro and TAG_PRO_PRIORITY not in tags:
            tags.append(TAG_PRO_PRIORITY)

        if missing_diag and not pro:
            tags.append(TAG_NEEDS_INFO)
            missing_bullets = "\n".join(f"- {item}" for item in missing_diag)
            reply = sanitize_bot_output(
                "Thanks - I still need the following diagnostic item(s) before I can escalate this thread:\n"
                f"{missing_bullets}"
            )
            return BotDecision(
                reply_text=reply,
                applied_tags=tuple(tags),
                components=build_resolution_buttons(),
                allowed_mentions=AllowedMentions(users=(post.author_id,)),
                triage_digest=None,
                ping_maintainer=False,
                secret_warning=has_secret,
            )

        triage_digest: str | None = None
        ping_maintainer = False
        if post.thread_id not in self.escalated_threads:
            self.escalated_threads.add(post.thread_id)
            ping_maintainer = True
            triage_digest = self._format_triage_digest(
                post=ForumPostContext(
                    thread_id=post.thread_id,
                    author_id=post.author_id,
                    author_name=post.author_name,
                    title=post.title,
                    body=combined,
                    author_roles=post.author_roles,
                    applied_tags=tuple(tags),
                    timestamp=post.timestamp,
                ),
                pro_priority=pro,
                bit_keys=[str(b["key"]) for b in bit_matches],
                mclogs_ids=mclogs_ids,
            )

        reply = sanitize_bot_output(
            "Thanks - all diagnostic checks (`/rtp version`, `/version`, `/rtp test`, and logs) "
            "are present. I have queued this thread in `#bot-triage-queue` for maintainer review."
        )
        return BotDecision(
            reply_text=reply,
            applied_tags=tuple(tags),
            components=build_resolution_buttons(),
            allowed_mentions=AllowedMentions(users=(post.author_id,)),
            triage_digest=triage_digest,
            ping_maintainer=ping_maintainer,
            secret_warning=has_secret,
        )

    def handle_button_interaction(
        self,
        post: ForumPostContext,
        custom_id: str,
        actor_id: str,
    ) -> BotDecision:
        """Handles `[✅ Solved]` and `[❓ Still need help]` button clicks."""
        tags = list(post.applied_tags)
        pro = is_pro_supporter(post.author_roles)
        if pro and TAG_PRO_PRIORITY not in tags:
            tags.append(TAG_PRO_PRIORITY)

        if custom_id == BUTTON_SOLVED_ID:
            tags = [t for t in tags if t != TAG_NEEDS_INFO]
            if TAG_SOLVED not in tags:
                tags.append(TAG_SOLVED)
            return BotDecision(
                reply_text=sanitize_bot_output(
                    f"Marked as **{TAG_SOLVED}** by <@{actor_id}>. Locking and archiving thread - "
                    "open a new post in `#support` any time you need anything else."
                ),
                applied_tags=tuple(tags),
                components=[],
                allowed_mentions=AllowedMentions(users=(actor_id,)),
                triage_digest=None,
                ping_maintainer=False,
                archived=True,
            )

        # BUTTON_NEED_HELP_ID
        combined = f"{post.title}\n{post.body}"
        mclogs_ids = extract_mclogs_ids(combined)
        fetched_logs = "\n".join(self.log_fetcher(pid) for pid in mclogs_ids if pid)
        diag = inspect_diagnostics(combined, fetched_logs)
        missing_diag = missing_diagnostic_items(diag)

        if missing_diag and not pro:
            if TAG_NEEDS_INFO not in tags:
                tags.append(TAG_NEEDS_INFO)
            missing_bullets = "\n".join(f"- {item}" for item in missing_diag)
            return BotDecision(
                reply_text=sanitize_bot_output(
                    "No problem - before I ping the maintainer, please reply in this thread with:\n"
                    f"{missing_bullets}"
                ),
                applied_tags=tuple(tags),
                components=build_resolution_buttons(),
                allowed_mentions=AllowedMentions(users=(post.author_id,)),
                triage_digest=None,
                ping_maintainer=False,
            )

        bit_matches = extract_bit_codes(self.conn, f"{combined}\n{fetched_logs}")
        triage_digest: str | None = None
        ping_maintainer = False
        if post.thread_id not in self.escalated_threads:
            self.escalated_threads.add(post.thread_id)
            ping_maintainer = True
            triage_digest = self._format_triage_digest(
                post=post,
                pro_priority=pro,
                bit_keys=[str(b["key"]) for b in bit_matches],
                mclogs_ids=mclogs_ids,
            )
        return BotDecision(
            reply_text=sanitize_bot_output(
                "I have queued this thread in `#bot-triage-queue` and notified the maintainer."
            ),
            applied_tags=tuple(tags),
            components=[],
            allowed_mentions=AllowedMentions(users=(post.author_id,)),
            triage_digest=triage_digest,
            ping_maintainer=ping_maintainer,
        )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Safe Discord #support Forum triage bot.")
    parser.add_argument(
        "--db",
        type=pathlib.Path,
        default=DEFAULT_DB_PATH,
        help="Path to SQLite FTS5 doc-index.sqlite database.",
    )
    parser.add_argument(
        "--query",
        type=str,
        default="",
        help="Run a single offline triage evaluation for the given thread question and print the response.",
    )
    parser.add_argument(
        "--title",
        type=str,
        default="Support Question",
        help="Thread title when running with --query.",
    )
    args = parser.parse_args(argv)

    engine = SupportBotEngine(db_path=args.db)
    try:
        if args.query:
            decision = engine.handle_new_thread(
                ForumPostContext(
                    thread_id="cli_preview",
                    author_id="100001",
                    author_name="operator",
                    title=args.title,
                    body=args.query,
                    applied_tags=("🌿 LeafRTP", "Paper / Spigot"),
                )
            )
            print(decision.reply_text)
        else:
            print("SupportBotEngine initialized with read-only SQLite FTS5 index.")
    finally:
        engine.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
