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
import concurrent.futures
from datetime import datetime, timezone
import ipaddress
import json
import os
import pathlib
import re
import sqlite3
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from typing import Any, Callable

# Ensure the standalone bot directory is on sys.path so sibling modules resolve
# whether run directly (`python3 discord-support-bot/discord_support_bot.py`) or imported.
BOT_DIR = pathlib.Path(__file__).resolve().parent
if str(BOT_DIR) not in sys.path:
    sys.path.insert(0, str(BOT_DIR))

from build_doc_index import (
    DEFAULT_DB_PATH,
    DEFAULT_MANIFEST_PATH,
    DEFAULT_PROJECT,
    ROOT,
    build_index,
    build_starting_reference_context,
    classify_query_intent,
    combinatorial_retrieve,
    decompound_ocr_text,
    extract_bit_codes,
    extract_doc_snippet,
    extract_grounded_query_signals,
    extract_non_default_yaml_overrides,
    extract_specific_tokens,
    get_manifest_tag_map,
    has_project_docs,
    is_schema_or_heading_facet,
    load_support_bot_manifest,
    match_corpus_messages,
    normalize_multilingual_query,
    parse_simple_yaml,
    porter_stem_word,
    resolve_post_project,
    score_translation_candidate,
    search_arch_sections,
    search_code_sections,
    search_config_keys,
    search_docs,
    select_best_translation,
    unwrap_console_paste,
)
from discord_setup import (
    CHANNEL_FLAG_PINNED,
    CHANNEL_TYPES,
    DiscordRestClient,
    strip_leading_emoji_label,
)

DEFAULT_STATE_PATH = BOT_DIR / "generated" / "bot-state.json"

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

MCLOGS_URL_RE = re.compile(r"(?i)https?://(?:www\.)?mclo\.gs/([A-Za-z0-9]{5,32})")
ANY_URL_RE = re.compile(r"(?i)https?://[^\s<>)\]]+")
ROLE_OR_EVERYONE_MENTION_RE = re.compile(r"@(?:everyone|here)|<@&\d+>")

SECRET_PATTERNS = (
    re.compile(
        r"(?i)(?:^|[\s'\"_.-])(?:password|passwd|secret|token|api_key|apikey|access_key|private_key)"
        r"\s*[:=]\s*['\"]?(?!RTP_NET_SECRET\b|true\b|false\b|null\b|none\b|<|\$\{)([^\s'\"]{4,})['\"]?"
    ),
    re.compile(r"(?i)\bRTP_NET_SECRET\s*=\s*['\"]?([^\s'\"]{4,})['\"]?"),
    re.compile(r"(?i)(?:jdbc:)?(?:rediss?|mysql|postgresql|postgres|mariadb|mongodb(?:\+srv)?)://[^/\s:@]*:([^@\s]{4,})@"),
    re.compile(r"\b([MN][A-Za-z\d]{23,28}\.[\w-]{6}\.[\w-]{20,40})\b"),
)

BUG_REPORT_KEYWORDS = re.compile(
    r"(?i)\b(bug(?:s)?|crash(?:es|ed|ing)?|exception(?:s)?|stack\s*trace|error(?:s)?|lag(?:s|ging|ged|gy)?|tps\s+drops?|drops?\s+tps|spike(?:s|d)?|stutter(?:s|ing)?|freeze(?:s|d|ing)?|stall(?:s|ed|ing)?|hang(?:s|ing)?|broken|doesn'?t\s+work|not\s+working|watchdog|leak(?:s|ing)?|die(?:s|d)?|dying|suffocat(?:e|es|ed|ing))\b"
)
SEVERE_RUNTIME_FAULT_RE = re.compile(
    r"(?i)\b(?:"
    r"lag(?:s|ging|ged|gy)?|tps\s+drops?|drops?\s+tps|mspt|freeze(?:s|d|ing)?|stutter(?:s|ing)?|"
    r"stall(?:s|ed|ing)?|hang(?:s|ing)?|unresponsive|time(?:s|\s+everyone)?\s+out|"
    r"crash(?:es|ed|ing)?|exception(?:s)?|stack\s*trace|null\s+(?:error|response)|"
    r"throws?\s+up\s+a\s+null|watchdog|noclassdeffounderror|nullpointerexception"
    r")\b"
)
HOWTO_KEYWORDS = re.compile(
    r"(?i)\b(how\s+to|how\s+do\s+i|how\s+can\s+i|where\s+do\s+i|configure|setting|radius|spawn|border|gui|menu|openonbarertp|import|migrate|betterrtp|ezrtp|justrtp|cooldown|delay|price|economy|biome|folia|fabric|neoforge|velocity)\b"
)
STARTUP_CRASH_KEYWORDS = re.compile(
    r"(?i)\b(won'?t\s+(?:start|boot|load|enable)|fails?\s+to\s+(?:start|boot|load|enable)|"
    r"crash(?:es|ed|ing)?\s+on\s+(?:startup|boot|load)|onEnable|NoClassDefFoundError|"
    r"UnsupportedClassVersionError|MixinApplyError|server\s+crash(?:es|ed)?)\b"
)
FOLLOWUP_INVESTIGATE_RE = re.compile(
    r"(?i)(?:\?|\b(?:didn'?t\s+work|does\s+not\s+work|not\s+working|still\s+(?:not|happening|broken|teleporting|opening|getting|same|does)|"
    r"tried\s+that|already\s+(?:set|tried|did|have)|but\s+it|error|exception|crash|"
    r"config\.yml|default\.yml|guimenu\.yml|/rtp\s+(?:version|test|info)|"
    r"leafrtp\s+v?\d|paper\s+1\.|paper\s+26\.|folia\s+1\.|folia\s+26\.|fabric\s+1\.|neoforge\s+1\.)\b)"
)
DIAGNOSTIC_REFUSAL_RE = re.compile(
    r"(?i)(?:"
    r"\b(?:can'?t|cannot|won'?t|will\s+not|not\s+going\s+to|refuse\s+to|don'?t\s+want\s+to)\s+"
    r"(?:(?:to\s+)?(?:share|upload|post|send|give|provide|paste)\s+)?(?:(?:my|the|any|private|server|full|latest|console|confidential|sensitive)\s+)*(?:logs?|console|config|files?|mclo\.gs)|"
    r"\blogs?\s+(?:are|is)\s+(?:private|confidential|sensitive)|"
    r"\b(?:not\s+uploading|not\s+sharing)\s+(?:(?:private|my|the|any|server|full|latest|console)\s+)*logs?|"
    r"\b(?:on\s+my\s+phone|away\s+from\s+(?:the\s+|my\s+)?(?:console|pc|computer|server)|not\s+at\s+(?:my\s+)?(?:pc|computer|console))|"
    r"\b(?:stop\s+asking|quit\s+asking|don'?t\s+ask\s+for\s+logs?)|"
    r"\b(?:just\s+tell\s+me\s+(?:the\s+fix|how\s+to\s+fix|what\s+to\s+change|the\s+answer)|just\s+answer\s+the\s+question)|"
    r"\b(?:not\s+doing\s+all\s+that|ain'?t\s+doing\s+all\s+that|too\s+much\s+work|i\s+don'?t\s+have\s+time\s+for\s+that)"
    r")"
)
DANGEROUS_ADVICE_RE = re.compile(
    r"(?i)(?:"
    r"\bsyncLoading\s*[:=]\s*true\b|"
    r"\bviewDistanceSelect\s*[:=]\s*[1-9]\d*\b|"
    r"\bmaxMSPT\s*[:=]\s*(?:[5-9]\d|\d{3,})(?:\.\d+)?\b|"
    r"\bminTPS\s*[:=]\s*(?:1[0-8]|[0-9])(?:\.\d+)?\b|"
    r"\bcacheCap\s*[:=]\s*0\b|"
    r"/rtp\s+config\s+(?:set|get|edit)\b"
    r")"
)
STAFF_ROLE_NAMES = frozenset({
    "maintainer",
    "staff",
    "admin",
    "administrator",
    "moderator",
    "mod",
    "developer",
    "support staff",
    "helper",
})


def is_staff_or_maintainer(
    user_id: str,
    maintainer_id: str,
    roles: tuple[str, ...] | list[str] = (),
) -> bool:
    """Returns True if `user_id` is `maintainer_id` or holds a staff/maintainer role."""
    if user_id and maintainer_id and str(user_id) == str(maintainer_id):
        return True
    for r in roles:
        low = r.strip().lstrip("@").lower()
        if low in STAFF_ROLE_NAMES:
            return True
    return False


_NEGATION_PREFIX_RE = re.compile(
    r"(?i)\b(?:not|never|don'?t|do\s+not|avoid|instead\s+of|without|warn(?:ing)?\s+against)\b"
)


def _has_unnegated_dangerous_match(text: str, pattern: str) -> bool:
    """Returns True if `pattern` matches in `text` without a preceding negation in the same clause."""
    for m in re.finditer(pattern, text):
        line_start = text.rfind("\n", 0, m.start()) + 1
        prefix = text[line_start:m.start()]
        clause_prefix = re.split(r"[,;!?]|\bbut\b", prefix, flags=re.IGNORECASE)[-1]
        if not _NEGATION_PREFIX_RE.search(clause_prefix):
            return True
    return False


def build_dangerous_advice_warning(text: str) -> str:
    """
    Scans `text` (author post, follow-up, or non-staff bystander reply) for known
    server-harming configuration advice (`DANGEROUS_ADVICE_RE`) and returns a calm
    Rule D-006 configuration safety note explaining why those settings hurt the
    server and what safe values/syntax to use instead.
    """
    if not text or not DANGEROUS_ADVICE_RE.search(text):
        return ""
    notes: list[str] = []
    if _has_unnegated_dangerous_match(text, r"(?i)\bsyncLoading\s*[:=]\s*true\b"):
        notes.append(
            "- Keep `syncLoading` set to `false` in `advanced/performance.yml`: enabling synchronous chunk loading "
            "forces main-thread chunk I/O (`S-005`) and stalls server ticks."
        )
    if _has_unnegated_dangerous_match(text, r"(?i)\bviewDistanceSelect\s*[:=]\s*[1-9]\d*\b"):
        notes.append(
            "- Keep `viewDistanceSelect` set to `0` in `advanced/performance.yml`: positive values load surrounding "
            "view-distance chunks on every candidate check and multiply chunk I/O."
        )
    if _has_unnegated_dangerous_match(text, r"(?i)\bmaxMSPT\s*[:=]\s*(?:[5-9]\d|\d{3,})(?:\.\d+)?\b"):
        notes.append(
            "- Keep `maxMSPT` at or below `45.0` ms (`@range: [0.0, 50.0]` in `advanced/performance.yml`, or lower toward "
            "`35.0`-`40.0` ms when reducing lag): values at or above `50.0` ms allow background refills to run during overloaded ticks."
        )
    if _has_unnegated_dangerous_match(text, r"(?i)\bminTPS\s*[:=]\s*(?:1[0-8]|[0-9])(?:\.\d+)?\b"):
        notes.append(
            "- Keep `minTPS` at `19.0` or higher in `advanced/performance.yml`: lowering `minTPS` below `19.0` "
            "masks tick degradation instead of pausing background work when the server is struggling."
        )
    if _has_unnegated_dangerous_match(text, r"(?i)\bcacheCap\s*[:=]\s*0\b"):
        notes.append(
            "- Keep `cacheCap` above `0` (default `10`) in `advanced/performance.yml`: a zero cache cap disables "
            "pre-generated location buffering and forces live on-demand searches."
        )
    if _has_unnegated_dangerous_match(text, r"(?i)/rtp\s+config\s+(?:set|get|edit)\b"):
        notes.append(
            "- Note on command syntax: `set` is not a `/rtp config` subcommand (invalid syntax) - use "
            "`/rtp config <file_alias> <key>=<value>` (for example `/rtp config performance period=10`) or `/rtp editor`."
        )
    if not notes:
        return ""
    return (
        "**Configuration safety note:**\n"
        + "\n".join(notes)
        + "\nReference: <https://dailystruggle.github.io/RTP/site/what-not-to-do/>"
    )


@dataclass(frozen=True)
class AllowedMentions:
    everyone: bool = False
    roles: bool = False
    users: tuple[str, ...] = ()
    parse: tuple[str, ...] = ()

    def to_discord_payload(self) -> dict[str, Any]:
        return {
            "parse": list(self.parse),
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
    attachments: tuple[str, ...] = ()
    bystander_messages: tuple[str, ...] = ()


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
    should_reply: bool = True
    refrained_status: str | None = None
    detected_intent: str | None = None
    doc_gap_digest: str | None = None
    worth_a_look_reasons: tuple[str, ...] = ()


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


def _trim_section_bullets(section: str, max_bullets: int) -> str:
    """
    Trims a multi-bullet section to at most `max_bullets` bullets while keeping its
    header/footer lines and preserving at least one documentation URL bullet when
    both config-key bullets and doc-section bullets are present.
    """
    lines = section.splitlines()
    bullet_indices = [i for i, ln in enumerate(lines) if ln.lstrip().startswith("- ")]
    if len(bullet_indices) <= max_bullets:
        return section
    doc_bullets = [i for i in bullet_indices if "<http" in lines[i].lower() and " -> `" not in lines[i]]
    cfg_bullets = [i for i in bullet_indices if i not in doc_bullets]
    if doc_bullets and cfg_bullets and max_bullets >= 2:
        keep_indices = set(cfg_bullets[: max_bullets - 1] + doc_bullets[:1])
    elif doc_bullets and max_bullets == 1:
        keep_indices = set(doc_bullets[:1])
    else:
        keep_indices = set(bullet_indices[:max_bullets])
    drop_set = set(bullet_indices) - keep_indices
    kept = [ln for i, ln in enumerate(lines) if i not in drop_set]
    return "\n".join(kept)


def _priority_trim_reply_to_budget(text: str, max_chars: int = 1950) -> str:
    """
    Enforces Discord's `<= 2,000`-character limit (`max_chars = 1950`) by
    priority-trimming lower-priority middle bullets (config/doc hits, then extra
    safety/BIT bullets) first so the top security warning and bottom diagnostic
    intake / resolution footer are always preserved intact.
    """
    if len(text) <= max_chars:
        return text
    blocks = [b for b in text.split("\n\n") if b.strip()]

    def _is_doc_or_cfg_block(blk: str) -> bool:
        return blk.startswith((
            "Here is what I found",
            "Here are the documentation",
            "Here is the developer API",
            "Here is the architectural overview",
            "Here is the setting and documentation",
            "I am not 100% sure the standard docs",
            "Based on that additional context",
            "One related setting to check",
            "- **",
        ))

    # Pass 1: Trim bullets in config/doc blocks down to 2, then 1, then drop if deterministic BIT/safety blocks exist
    for target_bullets in (2, 1, 0):
        for idx, blk in enumerate(blocks):
            if len("\n\n".join(blocks)) <= max_chars:
                break
            if _is_doc_or_cfg_block(blk):
                if target_bullets == 0 and any(
                    b.startswith(("**Configuration safety note:**", "I matched the following `[RTP bit]`"))
                    for b in blocks
                ):
                    blocks[idx] = ""
                elif target_bullets > 0:
                    blocks[idx] = _trim_section_bullets(blk, target_bullets)
        blocks = [b for b in blocks if b.strip()]
        if len("\n\n".join(blocks)) <= max_chars:
            return "\n\n".join(blocks)

    # Pass 2: Trim extra bullets in Configuration safety note and [RTP bit] blocks (keep top 2, then 1)
    for target_bullets in (2, 1):
        for idx, blk in enumerate(blocks):
            if len("\n\n".join(blocks)) <= max_chars:
                break
            if blk.startswith(("**Configuration safety note:**", "I matched the following `[RTP bit]`")):
                blocks[idx] = _trim_section_bullets(blk, target_bullets)
        if len("\n\n".join(blocks)) <= max_chars:
            return "\n\n".join(blocks)

    # Pass 3: Hard line-boundary backstop while preserving the final block (diagnostic prompt / reaction footer)
    joined = "\n\n".join(blocks)
    if len(joined) <= max_chars:
        return joined
    if len(blocks) >= 2 and len(blocks[-1]) < max_chars - 200:
        footer = blocks[-1]
        head_budget = max_chars - len(footer) - 2
        head_text = "\n\n".join(blocks[:-1])
        head_lines: list[str] = []
        used = 0
        for ln in head_text.splitlines():
            add_len = len(ln) + (1 if head_lines else 0)
            if used + add_len > head_budget:
                break
            head_lines.append(ln)
            used += add_len
        if head_lines:
            return "\n".join(head_lines).rstrip() + "\n\n" + footer
    out_lines: list[str] = []
    used_len = 0
    for ln in joined.splitlines():
        add_len = len(ln) + (1 if out_lines else 0)
        if used_len + add_len > max_chars:
            break
        out_lines.append(ln)
        used_len += add_len
    return "\n".join(out_lines) if out_lines else joined[:max_chars]


def sanitize_bot_output(text: str, max_chars: int = 1950) -> str:
    """
    Enforces prompt-injection, non-negative tone, and output safety guardrails:
      1. Strips `@everyone`, `@here`, and `<@&role>` mentions.
      2. Strips any URL whose domain is outside `ALLOWED_DOMAINS`.
      3. Normalizes any accidental `.md` suffix on `dailystruggle.github.io` doc URLs.
      4. Replaces em/en dashes with ASCII `-` per Rule D-006 and normalizes lecturing/AI phrases.
      5. Priority-trims multi-section replies to `<= max_chars` (`1,950` chars) so Discord
         REST API v10 never rejects a message with `HTTP 400 (code 50035: > 2000 chars)`.
    """
    cleaned = ROLE_OR_EVERYONE_MENTION_RE.sub("[mention-removed]", text)
    cleaned = cleaned.replace("\u2014", "-").replace("\u2013", "-")
    cleaned = re.sub(r"(?i)\byou\s+need\s+to\b", "you can", cleaned)
    cleaned = re.sub(r"(?i)\bread\s+the\s+docs\b", "check the documentation", cleaned)
    cleaned = re.sub(r"(?i)\b(?:calm\s+down|as\s+i\s+already(?:\s+said|\s+explained|\s+mentioned)?|as\s+an\s+ai(?:\s+language\s+model)?)\b[,:\s]*", "", cleaned)

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
            if host == "dailystruggle.github.io" and ".md" in raw_url:
                raw_url = re.sub(r"\.md(?=/|#|$)", "/", raw_url)
                raw_url = raw_url.replace("//#", "/#")
            return raw_url + trailing
        return "[external-link-removed]" + trailing

    sanitized = ANY_URL_RE.sub(check_url, cleaned)
    return _priority_trim_reply_to_budget(sanitized, max_chars=max_chars)


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


MAX_MCLOGS_PASTES_PER_POST = 3
MAX_IMAGE_ATTACHMENTS_PER_POST = 3
MAX_IMAGE_BYTES = 5 * 1024 * 1024
MAX_BOT_REPLIES_PER_THREAD = 3
DEEP_SEARCH_TIMEOUT_SECONDS = 300.0

DISCORD_ATTACHMENT_DOMAINS = frozenset({
    "cdn.discordapp.com",
    "media.discordapp.net",
})
IMAGE_EXTENSIONS = (".png", ".jpg", ".jpeg", ".webp", ".bmp")
TEXT_ATTACHMENT_EXTENSIONS = (".log", ".txt", ".yml", ".yaml")
MAX_TEXT_ATTACHMENT_BYTES = 256 * 1024
DISCORD_CDN_IMAGE_URL_RE = re.compile(
    r"https?://(?:cdn\.discordapp\.com|media\.discordapp\.net)/attachments/[^\s<>)\]]+"
)
CODE_OR_ERROR_SIGNAL_RE = re.compile(
    r"(?i)(?:\b(?:exception|nullpointerexception|illegalstateexception|illegalargumentexception|"
    r"nosuchmethoderror|noclassdeffounderror|unsupportedclassversionerror|concurrentmodificationexception|"
    r"sqlexception|timeoutexception|classcastexception|indexoutofboundsexception|"
    r"stack\s*trace|caused\s+by|at\s+io\.github\.dailystruggle|asynchronous\s+effect\s+add)\b|\.java:\d+)"
)

_RAPID_OCR_INSTANCE: Any = None
_RAPID_OCR_UNAVAILABLE = False


def extract_image_attachment_urls(
    messages_or_attachments: list[dict[str, Any]] | tuple[Any, ...],
    inline_text: str = "",
    max_images: int = MAX_IMAGE_ATTACHMENTS_PER_POST,
) -> list[str]:
    """
    Extracts up to `max_images` unique image attachment URLs from Discord REST
    message dicts (or attachment dicts) and inline `cdn.discordapp.com` /
    `media.discordapp.net` links.
    """
    urls: list[str] = []

    def _add_url(candidate: str) -> None:
        clean = candidate.strip()
        if not clean or clean in urls or len(urls) >= max_images:
            return
        try:
            parsed = urllib.parse.urlparse(clean)
            host = (parsed.hostname or "").lower()
            path_low = (parsed.path or "").lower()
            if any(path_low.endswith(ext) for ext in TEXT_ATTACHMENT_EXTENSIONS):
                return
            if parsed.scheme in ("http", "https") and host in DISCORD_ATTACHMENT_DOMAINS:
                if any(path_low.endswith(ext) for ext in IMAGE_EXTENSIONS) or "/attachments/" in path_low:
                    urls.append(clean)
        except ValueError:
            return

    for item in messages_or_attachments:
        if isinstance(item, str):
            if not any(item.strip().lower().endswith(ext) for ext in TEXT_ATTACHMENT_EXTENSIONS):
                _add_url(item)
            continue
        if not isinstance(item, dict):
            continue
        att_list = item.get("attachments") if "attachments" in item else [item]
        for att in att_list or ():
            if not isinstance(att, dict):
                continue
            ctype = str(att.get("content_type") or "").lower()
            fname = str(att.get("filename") or "").lower()
            url = str(att.get("url") or att.get("proxy_url") or "").strip()
            if url and (ctype.startswith("image/") or any(fname.endswith(ext) for ext in IMAGE_EXTENSIONS)):
                _add_url(url)

    if inline_text and len(urls) < max_images:
        for m in DISCORD_CDN_IMAGE_URL_RE.finditer(inline_text):
            _add_url(m.group(0))

    return urls[:max_images]


def extract_text_attachment_urls(
    messages_or_attachments: list[dict[str, Any]] | tuple[Any, ...],
    inline_text: str = "",
    max_files: int = 3,
) -> list[str]:
    """
    Extracts up to `max_files` unique `.log`, `.txt`, `.yml`, or `.yaml` attachment
    URLs (or local file paths in tests) from Discord REST message dicts, attachment
    tuples, and inline `cdn.discordapp.com` / `media.discordapp.net` links.
    """
    urls: list[str] = []

    def _add_candidate(candidate: str) -> None:
        clean = candidate.strip()
        if not clean or clean in urls or len(urls) >= max_files:
            return
        if clean.startswith(("http://", "https://")):
            try:
                parsed = urllib.parse.urlparse(clean)
                host = (parsed.hostname or "").lower()
                path_low = (parsed.path or "").lower()
                if parsed.scheme == "https" and host in DISCORD_ATTACHMENT_DOMAINS:
                    if any(path_low.endswith(ext) for ext in TEXT_ATTACHMENT_EXTENSIONS):
                        urls.append(clean)
            except ValueError:
                return
            return
        if any(clean.lower().endswith(ext) for ext in TEXT_ATTACHMENT_EXTENSIONS):
            urls.append(clean)

    for item in messages_or_attachments:
        if isinstance(item, str):
            _add_candidate(item)
            continue
        if not isinstance(item, dict):
            continue
        att_list = item.get("attachments") if "attachments" in item else [item]
        for att in att_list or ():
            if not isinstance(att, dict):
                continue
            fname = str(att.get("filename") or "").lower()
            url = str(att.get("url") or att.get("proxy_url") or "").strip()
            if url and any(fname.endswith(ext) for ext in TEXT_ATTACHMENT_EXTENSIONS):
                _add_candidate(url)

    if inline_text and len(urls) < max_files:
        for m in DISCORD_CDN_IMAGE_URL_RE.finditer(inline_text):
            _add_candidate(m.group(0))

    return urls[:max_files]


def read_text_attachment_source(
    source: str,
    timeout: float = 4.0,
    max_bytes: int = MAX_TEXT_ATTACHMENT_BYTES,
) -> str:
    """
    Bounded read (`<= 256 KB`) of a `.log`, `.txt`, `.yml`, or `.yaml` attachment
    from `cdn.discordapp.com` / `media.discordapp.net` (or a local file path in tests).
    """
    clean = (source or "").strip()
    if not clean:
        return ""
    if clean.startswith(("http://", "https://")):
        try:
            parsed = urllib.parse.urlparse(clean)
            host = (parsed.hostname or "").lower()
            path_low = (parsed.path or "").lower()
            if (
                parsed.scheme != "https"
                or host not in DISCORD_ATTACHMENT_DOMAINS
                or not any(path_low.endswith(ext) for ext in TEXT_ATTACHMENT_EXTENSIONS)
            ):
                return ""
            req = urllib.request.Request(
                clean,
                method="GET",
                headers={"User-Agent": "LeafRTP-SupportBot/1.0 (+https://github.com/DailyStruggle/RTP)"},
            )
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                raw_bytes = resp.read(max_bytes)
            return raw_bytes.decode("utf-8", errors="replace")
        except (OSError, ValueError):
            return ""

    local_path = pathlib.Path(clean)
    if local_path.is_file() and local_path.suffix.lower() in TEXT_ATTACHMENT_EXTENSIONS:
        try:
            raw_bytes = local_path.read_bytes()[:max_bytes]
            return raw_bytes.decode("utf-8", errors="replace")
        except OSError:
            return ""
    return ""


def extract_text_from_image_bytes(
    image_bytes: bytes,
    min_confidence: float = 0.50,
) -> str:
    """
    Non-LLM Optical Character Recognition (`RapidOCR` ONNX CPU detector + CTC
    character recognizer). Converts screenshot pixels into plain text on CPU
    without any instruction-following VLM, then feeds the existing deterministic
    and corpus-facet pipeline.
    """
    global _RAPID_OCR_INSTANCE, _RAPID_OCR_UNAVAILABLE
    if not image_bytes or _RAPID_OCR_UNAVAILABLE:
        return ""
    try:
        import io
        from PIL import Image, ImageOps
        if _RAPID_OCR_INSTANCE is None:
            from rapidocr_onnxruntime import RapidOCR
            _RAPID_OCR_INSTANCE = RapidOCR()

        with Image.open(io.BytesIO(image_bytes)) as raw_img:
            img = raw_img.convert("RGB")
            w, h = img.size
            if (w < 800 or h < 180) and max(w, h) < 1400:
                img = img.resize((w * 2, h * 2), Image.Resampling.BICUBIC)
            pad_y = max(32, (200 - img.size[1]) // 2)
            img = ImageOps.expand(img, border=(32, pad_y, 32, pad_y), fill=(18, 18, 22))
            buf = io.BytesIO()
            img.save(buf, format="PNG")
            prepped_bytes = buf.getvalue()

        ocr_res, _ = _RAPID_OCR_INSTANCE(prepped_bytes)
        if not ocr_res:
            return ""
        lines: list[str] = []
        for item in ocr_res:
            if not isinstance(item, (list, tuple)) or len(item) < 3:
                continue
            line_text = str(item[1]).strip()
            try:
                conf = float(item[2])
            except (ValueError, TypeError):
                conf = 1.0
            if line_text and conf >= min_confidence:
                lines.append(line_text)
        return "\n".join(lines)
    except ImportError:
        _RAPID_OCR_UNAVAILABLE = True
        return ""
    except Exception:
        return ""


def ocr_image_source(
    source: str,
    timeout: float = 4.0,
    max_bytes: int = MAX_IMAGE_BYTES,
) -> str:
    """
    Reads an image attachment from a `cdn.discordapp.com` / `media.discordapp.net`
    URL (or a local image file path when invoked via CLI `--image` / unit tests)
    and extracts visible text via `extract_text_from_image_bytes`.
    """
    clean = (source or "").strip()
    if not clean:
        return ""
    if clean.startswith(("http://", "https://")):
        try:
            parsed = urllib.parse.urlparse(clean)
            host = (parsed.hostname or "").lower()
            if parsed.scheme != "https" or host not in DISCORD_ATTACHMENT_DOMAINS:
                return ""
            req = urllib.request.Request(
                clean,
                method="GET",
                headers={"User-Agent": "LeafRTP-SupportBot/1.0 (+https://github.com/DailyStruggle/RTP)"},
            )
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                raw_bytes = resp.read(max_bytes)
            return extract_text_from_image_bytes(raw_bytes)
        except (OSError, ValueError):
            return ""

    local_path = pathlib.Path(clean)
    if local_path.is_file() and local_path.suffix.lower() in IMAGE_EXTENSIONS:
        try:
            raw_bytes = local_path.read_bytes()[:max_bytes]
            return extract_text_from_image_bytes(raw_bytes)
        except OSError:
            return ""
    return ""


_PASTE_PROVIDER_TEMPLATES: dict[str, str] = {}


def extract_mclogs_ids(
    text: str,
    max_pastes: int = MAX_MCLOGS_PASTES_PER_POST,
    manifest: pathlib.Path | str | dict[str, Any] | None = None,
) -> list[str]:
    """
    Extracts up to `max_pastes` unique log paste IDs from a forum post across
    `security.log_paste_providers` in `support-bot.yml` (defaulting to `mclo.gs`).
    """
    ids: list[str] = []
    cfg = load_support_bot_manifest(manifest)
    providers = (cfg.get("security") or {}).get("log_paste_providers")
    pattern_entries: list[tuple[re.Pattern[str], str]] = [
        (MCLOGS_URL_RE, "https://api.mclo.gs/1/raw/{id}")
    ]
    if isinstance(providers, dict):
        for p_cfg in providers.values():
            if isinstance(p_cfg, dict) and p_cfg.get("url_pattern"):
                raw_tpl = str(p_cfg.get("raw_url_template") or "https://api.mclo.gs/1/raw/{id}")
                try:
                    pattern_entries.append((re.compile(str(p_cfg["url_pattern"])), raw_tpl))
                except re.error:
                    pass
    for pat, raw_tpl in pattern_entries:
        for m in pat.finditer(text):
            paste_id = m.group(1) if m.lastindex else m.group(0)
            _PASTE_PROVIDER_TEMPLATES[paste_id] = raw_tpl
            if paste_id not in ids:
                ids.append(paste_id)
                if len(ids) >= max_pastes:
                    return ids
    return ids


def resolve_log_paste_raw_url(
    paste_id: str,
    manifest: pathlib.Path | str | dict[str, Any] | None = None,
) -> str:
    """
    Resolves the raw fetch URL for `paste_id` using the provider matched during
    `extract_mclogs_ids` or by matching `paste_id` against `security.log_paste_providers`.
    """
    default_tpl = "https://api.mclo.gs/1/raw/{id}"
    cfg = load_support_bot_manifest(manifest)
    providers = (cfg.get("security") or {}).get("log_paste_providers")
    if isinstance(providers, dict) and providers:
        allowed_tpls = {
            str(p_cfg["raw_url_template"])
            for p_cfg in providers.values()
            if isinstance(p_cfg, dict) and p_cfg.get("raw_url_template")
        }
        cached_tpl = _PASTE_PROVIDER_TEMPLATES.get(paste_id)
        if cached_tpl and cached_tpl in allowed_tpls:
            return cached_tpl.replace("{id}", paste_id)
        for p_cfg in providers.values():
            if not isinstance(p_cfg, dict) or not p_cfg.get("raw_url_template"):
                continue
            raw_tpl = str(p_cfg["raw_url_template"])
            url_pat = str(p_cfg.get("url_pattern") or "")
            grp_m = re.search(r"\((?!\?)([^()]+)\)", url_pat)
            if grp_m:
                try:
                    if re.fullmatch(grp_m.group(1), paste_id):
                        return raw_tpl.replace("{id}", paste_id)
                except re.error:
                    pass
        first_tpl = next(iter(allowed_tpls), default_tpl)
        return first_tpl.replace("{id}", paste_id)
    cached_tpl = _PASTE_PROVIDER_TEMPLATES.get(paste_id, default_tpl)
    return cached_tpl.replace("{id}", paste_id)


def fetch_mclogs_raw(
    paste_id: str,
    timeout: float = 5.0,
    max_bytes: int = 65536,
    manifest: pathlib.Path | str | dict[str, Any] | None = None,
) -> str:
    """
    Fetches raw log text read-only from `security.log_paste_providers` raw-URL
    template in `support-bot.yml` (defaulting to `https://api.mclo.gs/1/raw/<id>`).
    """
    if not re.fullmatch(r"[A-Za-z0-9_-]{4,64}", paste_id):
        return ""
    url = resolve_log_paste_raw_url(paste_id, manifest=manifest)
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


PROTECTED_TECH_SPAN_RE = re.compile(
    r"(?i)(`[^`\n]{1,120}`|https?://[^\s<>)\]]+|/[A-Za-z0-9_.-]+(?:\s+[A-Za-z0-9_.=-]+)*|\b[A-Za-z0-9_-]+\.(?:yml|yaml|json|md|jar|sqlite)\b|\b[A-Za-z_][A-Za-z0-9_]*\.[A-Za-z_][A-Za-z0-9_.]*\b)"
)


def _mask_protected_tech_spans(
    text: str,
    max_chars: int = 900,
) -> tuple[str, str, list[str]]:
    """
    Redacts secrets, strips comment/log/fenced-code noise when input exceeds
    `max_chars`, and masks technical spans (`PROTECTED_TECH_SPAN_RE`) with
    `__RTP_SPAN_0__`..`__RTP_SPAN_n__` placeholders before calling external
    translation services.
    """
    cleaned = (text or "").strip()
    if not cleaned:
        return "", "", []
    candidate = cleaned
    if len(candidate) > max_chars:
        # Strip fenced code blocks, YAML comment lines, and timestamped log lines first
        # so foreign-language questions written below a config/log paste are not truncated.
        stripped_blocks = re.sub(r"(?s)```.*?```", " ", candidate)
        kept_lines = [
            ln
            for ln in stripped_blocks.splitlines()
            if ln.strip()
            and not ln.lstrip().startswith("#")
            and not re.match(r"^\[?\d{2}:\d{2}:\d{2}\s+(?:INFO|WARN|ERROR)", ln.lstrip())
        ]
        compact = "\n".join(kept_lines).strip()
        if compact:
            candidate = compact
        if len(candidate) > max_chars:
            # Keep both the leading and trailing prose segments around the question
            half = max_chars // 2
            candidate = f"{candidate[:half]}\n{candidate[-half:]}"
    _, safe_text = detect_and_redact_secrets(candidate[:max_chars])
    placeholders: list[str] = []

    def _mask_span(match: re.Match[str]) -> str:
        idx = len(placeholders)
        placeholders.append(match.group(0))
        return f"__RTP_SPAN_{idx}__"

    masked_text = PROTECTED_TECH_SPAN_RE.sub(_mask_span, safe_text)
    return cleaned, masked_text, placeholders


def _unmask_protected_tech_spans(translated: str, placeholders: list[str]) -> str:
    """Restores `__RTP_SPAN_0__`..`__RTP_SPAN_n__` placeholders after translation."""
    restored = translated
    for idx, orig_span in enumerate(placeholders):
        restored = re.sub(
            rf"__\s*RTP_SPAN_{idx}\s*__",
            lambda _, s=orig_span: s,
            restored,
            flags=re.IGNORECASE,
        )
    return restored


def translate_via_google_nmt(
    text: str,
    timeout: float = 3.0,
    max_chars: int = 900,
) -> tuple[str, str]:
    """
    Service 1 (Neural Machine Translation - Transformer Seq2Seq):
    Translates `text` to English via `https://translate.googleapis.com/translate_a/single`.
    Excels at whole-sentence context, missing diacritics, and colloquial phrasing.
    """
    cleaned, masked_text, placeholders = _mask_protected_tech_spans(text, max_chars=max_chars)
    if not cleaned:
        return "", "en"
    query_params = urllib.parse.urlencode(
        {
            "client": "gtx",
            "sl": "auto",
            "tl": "en",
            "dt": "t",
            "q": masked_text,
        }
    )
    url = f"https://translate.googleapis.com/translate_a/single?{query_params}"
    req = urllib.request.Request(
        url,
        method="GET",
        headers={"User-Agent": "LeafRTP-SupportBot/1.0"},
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read(32768).decode("utf-8", errors="replace")
        payload = json.loads(raw)
        if not isinstance(payload, list) or not payload or not isinstance(payload[0], list):
            return cleaned, "en"
        segments: list[str] = []
        for seg in payload[0]:
            if isinstance(seg, list) and seg and isinstance(seg[0], str):
                segments.append(seg[0])
        translated = _unmask_protected_tech_spans("".join(segments).strip(), placeholders)
        detected_lang = str(payload[2]).strip().lower() if len(payload) > 2 and payload[2] else "en"
        return (translated or cleaned), detected_lang
    except (OSError, ValueError, TypeError, IndexError):
        return cleaned, "en"


def translate_via_mymemory_tm(
    text: str,
    timeout: float = 3.0,
    max_chars: int = 500,
) -> tuple[str, str]:
    """
    Service 2 (Translation Memory + Hybrid Statistical/Neural MT):
    Translates `text` to English via `https://api.mymemory.translated.net/get?langpair=Autodetect|en`.
    Functions differently from Google NMT by querying a bilingual Translation
    Memory (TM) database of human-translated software/localization segments
    backed by hybrid MT, and serves as an independent non-Google fallback.
    """
    cleaned, masked_text, placeholders = _mask_protected_tech_spans(text, max_chars=max_chars)
    if not cleaned:
        return "", "en"
    query_params = urllib.parse.urlencode(
        {
            "q": masked_text,
            "langpair": "Autodetect|en",
        }
    )
    url = f"https://api.mymemory.translated.net/get?{query_params}"
    req = urllib.request.Request(
        url,
        method="GET",
        headers={"User-Agent": "LeafRTP-SupportBot/1.0"},
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read(32768).decode("utf-8", errors="replace")
        payload = json.loads(raw)
        if not isinstance(payload, dict):
            return cleaned, "en"
        status = payload.get("responseStatus")
        resp_data = payload.get("responseData")
        if not isinstance(resp_data, dict):
            return cleaned, "en"
        raw_translated = str(resp_data.get("translatedText") or "").strip()
        upper_trans = raw_translated.upper()
        # MyMemory returns 403 "PLEASE SELECT TWO DISTINCT LANGUAGES" when
        # Autodetect determines the input is already English (`en|en`).
        if (
            status != 200
            or not raw_translated
            or "PLEASE SELECT TWO DISTINCT LANGUAGES" in upper_trans
            or "MYMEMORY WARNING" in upper_trans
            or "INVALID LANGUAGE PAIR" in upper_trans
        ):
            return cleaned, "en"
        detected_lang = str(resp_data.get("detectedLanguage") or "en").strip().lower()
        translated = _unmask_protected_tech_spans(raw_translated, placeholders)
        return (translated or cleaned), detected_lang
    except (OSError, ValueError, TypeError, IndexError):
        return cleaned, "en"


def translate_candidates_to_english(
    text: str,
    timeout: float = 3.0,
    max_chars: int = 900,
) -> list[tuple[str, str, str]]:
    """
    Queries two differently functioning translation services concurrently:
      1. `google_nmt`: Google Translate neural sequence-to-sequence Transformer
      2. `mymemory_tm`: MyMemory bilingual Translation Memory + hybrid MT
    Returns `[("google_nmt", text_1, lang_1), ("mymemory_tm", text_2, lang_2)]`.
    If one service times out or errors, the other acts as a live fallback.
    """
    cleaned = (text or "").strip()
    if not cleaned:
        return [("none", "", "en")]
    services: list[tuple[str, Callable[[], tuple[str, str]]]] = [
        ("google_nmt", lambda: translate_via_google_nmt(cleaned, timeout=timeout, max_chars=max_chars)),
        ("mymemory_tm", lambda: translate_via_mymemory_tm(cleaned, timeout=timeout, max_chars=min(max_chars, 500))),
    ]
    candidates: list[tuple[str, str, str]] = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        future_map = {name: pool.submit(fn) for name, fn in services}
        for name, _ in services:
            fut = future_map[name]
            try:
                t_text, t_lang = fut.result(timeout=timeout + 0.5)
                candidates.append((name, t_text, t_lang))
            except Exception:
                candidates.append((name, cleaned, "en"))
    return candidates


def autotranslate_to_english(
    text: str,
    timeout: float = 3.0,
    max_chars: int = 900,
    conn: sqlite3.Connection | None = None,
    project: str = DEFAULT_PROJECT,
) -> tuple[str, str]:
    """
    LLM-independent dual-service autotranslation to English.
    Runs against two differently functioning translation services in parallel:
      1. `google_nmt` (`translate.googleapis.com` neural seq2seq Transformer)
      2. `mymemory_tm` (`api.mymemory.translated.net` Translation Memory + hybrid MT)
    Scores both candidates against `doc-index.sqlite` (`select_best_translation`)
    and returns `(best_translated_text, detected_source_lang)`.
    """
    cleaned = (text or "").strip()
    if not cleaned:
        return "", "en"
    candidates = translate_candidates_to_english(cleaned, timeout=timeout, max_chars=max_chars)
    if conn is not None:
        best_text, best_lang, _, _ = select_best_translation(conn, cleaned, candidates, project=project)
        return best_text, best_lang
    for _, cand_text, cand_lang in candidates:
        if cand_text and cand_text.strip() and cand_text.strip().lower() != cleaned.lower():
            return cand_text.strip(), cand_lang
    return candidates[0][1], candidates[0][2]


PROJECT_DIAGNOSTIC_PROMPTS: dict[str, dict[str, str]] = {
    "leafrtp": {
        "display_name": "LeafRTP",
        "version_missing": "`/rtp version` output",
        "version_present": "`/rtp version`",
        "test_missing": "`/rtp test` self-test output",
        "test_present": "`/rtp test`",
        "info_cmd": "`/rtp info`",
        "startup_note": "(in-game `/rtp test` is not required when the server cannot boot)",
        "lag_intro": (
            "Teleport lag or server slowdowns usually come from one of three places: "
            "a permission setup bypassing the background location cache (`rtp.unqueued`, `rtp.params`, "
            "or wildcard `rtp.*` granted to regular players), on-demand chunk generation in an "
            "unpregenerated world, or custom `advanced/performance.yml` overrides (`syncLoading`, "
            "`viewDistanceSelect`, or JVM heap pressure).\n\n"
            "I do not want to recommend a config change that targets the wrong cause or makes your tick "
            "time worse. Please reply with:"
        ),
        "lag_bullet": "- What `/rtp info` reports for hot/cold queue fill and active chunk tickets when the slowdown happens",
        "cmd_intro": (
            "A failed `/rtp` command or unexpected placement usually comes down to a "
            "permission node override (`rtp.use`, `rtp.notme` on operators, or region `requirePermission`), "
            "a missing world-to-region mapping in `definitions/worlds/` and `definitions/regions/`, "
            "or a console exception.\n\n"
            "Rather than guessing at settings without seeing your setup, please reply with:"
        ),
        "cmd_bullet": "- The exact command you ran, which world/region you are in, and whether the player has OP or a specific LuckPerms group",
    },
    "oneplayersleep": {
        "display_name": "OnePlayerSleep",
        "version_missing": "`/ops version` (or `/sleep version`) output",
        "version_present": "`/ops version`",
        "test_missing": "`/ops test` (or `plugins/OnePlayerSleep/config.yml`) output",
        "test_present": "`/ops test`",
        "info_cmd": "`/ops status`",
        "startup_note": "(in-game `/ops test` is not required when the server cannot boot)",
        "lag_intro": (
            "Sleep-skip or wakeup delays in `OnePlayerSleep` usually come from world-time sync hooks, "
            "cooldown/bed-check settings in `plugins/OnePlayerSleep/config.yml`, or another sleep/time plugin conflict.\n\n"
            "Rather than guessing at settings without seeing your setup, please reply with:"
        ),
        "lag_bullet": "- Which world and sleep/wakeup settings are active in `plugins/OnePlayerSleep/config.yml` when the issue happens",
        "cmd_intro": (
            "An unexpected sleep-skip or `/sleep` / `/ops` command behavior usually comes down to a "
            "permission override (`oneplayersleep.*`), a world filter in `plugins/OnePlayerSleep/config.yml`, "
            "or a console exception.\n\n"
            "Rather than guessing at settings without seeing your setup, please reply with:"
        ),
        "cmd_bullet": "- The exact command or bed action you tested, which world you are in, and whether the player has OP or a specific permission group",
    },
    "treegen": {
        "display_name": "TreeGen",
        "version_missing": "`/treegen version` output",
        "version_present": "`/treegen version`",
        "test_missing": "`/treegen test` self-test output",
        "test_present": "`/treegen test`",
        "info_cmd": "`/treegen info`",
        "startup_note": "(in-game `/treegen test` is not required when the server cannot boot)",
        "lag_intro": (
            "Tree generation slowdowns in `TreeGen` usually come from large schematic/populator batch sizes, "
            "synchronous chunk population in ungenerated terrain, or custom `plugins/TreeGen/config.yml` overrides.\n\n"
            "Rather than guessing at settings without seeing your setup, please reply with:"
        ),
        "lag_bullet": "- What `/treegen info` reports and which world/biome generator settings are active when the slowdown happens",
        "cmd_intro": (
            "A failed `/treegen` command or unexpected tree placement usually comes down to a "
            "biome/region filter in `plugins/TreeGen/`, a permission node override, or a console exception.\n\n"
            "Rather than guessing at settings without seeing your setup, please reply with:"
        ),
        "cmd_bullet": "- The exact `/treegen` command or generator config you tested and which world/biome you are in",
    },
}


def get_project_diagnostic_prompts(
    project: str = DEFAULT_PROJECT,
    manifest: pathlib.Path | str | dict[str, Any] | None = None,
) -> dict[str, str]:
    """
    Returns the project-scoped diagnostic prompt dictionary from
    `projects.<slug>.diagnostics` in `support-bot.yml` (falling back to
    `PROJECT_DIAGNOSTIC_PROMPTS` and generic CLI defaults).
    """
    proj = (project or DEFAULT_PROJECT).strip().lower()
    base: dict[str, str] = {
        "display_name": proj,
        "version_missing": f"`/{proj} version` output",
        "version_present": f"`/{proj} version`",
        "runtime_missing": "server `/version` output (Paper, Folia, Spigot, Fabric, or NeoForge version)",
        "runtime_present": "server `/version`",
        "test_missing": f"`/{proj} test` self-test output",
        "test_present": f"`/{proj} test`",
        "log_missing": "full `logs/latest.log` uploaded to <https://mclo.gs/>",
        "log_present": "`mclo.gs` log",
        "info_cmd": f"`/{proj} info`",
        "startup_note": f"(in-game `/{proj} test` is not required when the server cannot boot)",
        "lag_intro": (
            f"Slowdowns in `{proj}` usually come from configuration overrides or unpregenerated chunks.\n\n"
            "Rather than guessing at settings without seeing your setup, please reply with:"
        ),
        "lag_bullet": f"- What `/{proj} info` reports when the slowdown happens",
        "cmd_intro": (
            f"An unexpected `{proj}` command or configuration behavior usually comes down to a permission override "
            "or a console exception.\n\nRather than guessing at settings without seeing your setup, please reply with:"
        ),
        "cmd_bullet": "- The exact command you ran and which world you are in",
    }
    if proj in PROJECT_DIAGNOSTIC_PROMPTS:
        base.update(PROJECT_DIAGNOSTIC_PROMPTS[proj])
    cfg = load_support_bot_manifest(manifest)
    proj_cfg = (cfg.get("projects") or {}).get(proj)
    if isinstance(proj_cfg, dict):
        if proj_cfg.get("display_name"):
            base["display_name"] = str(proj_cfg["display_name"])
        diag_cfg = proj_cfg.get("diagnostics")
        if isinstance(diag_cfg, dict):
            for k, v in diag_cfg.items():
                if v is not None and str(v).strip():
                    base[str(k)] = str(v)
    return base


def inspect_diagnostics(
    text: str,
    fetched_logs: str = "",
    project: str = DEFAULT_PROJECT,
    manifest: pathlib.Path | str | dict[str, Any] | None = None,
) -> dict[str, bool]:
    """
    Checks whether a post (plus any fetched log paste) includes the 4
    diagnostic items required before pinging the maintainer on a bug report,
    driven by `projects.<slug>.diagnostics` in `support-bot.yml`:
      1. `rtp_version`: project version output (`version_pattern`)
      2. `server_version`: runtime/platform version (`runtime_pattern`)
      3. `rtp_test`: health-check / self-test / diagnostic code output (`test_pattern`)
      4. `log_link`: log paste URL (`security.log_paste_providers`) or attached log
    """
    combined = f"{text}\n{fetched_logs}"
    proj = (project or DEFAULT_PROJECT).strip().lower()
    prompts = get_project_diagnostic_prompts(proj, manifest=manifest)

    ver_pat = prompts.get("version_pattern") or (
        rf"(?i)(?:/(?:rtp|ops|sleep|treegen|{re.escape(proj)})\s+version|"
        rf"(?:leafrtp|oneplayersleep|treegen|{re.escape(proj)})\s+v?\d+\.\d+|"
        rf"(?:rtp|ops|treegen|{re.escape(proj)})\s+version\s*[:=]?\s*\d+\.\d+)"
    )
    run_pat = prompts.get("runtime_pattern") or (
        r"(?i)(?:/version\b|this\s+server\s+is\s+running|paper\s+(?:version\s+)?(?:1\.\d+|26\.\d+)|folia\s+(?:1\.\d+|26\.\d+)|fabric\s+(?:1\.\d+|26\.\d+)|neoforge\s+(?:1\.\d+|26\.\d+))"
    )
    tst_pat = prompts.get("test_pattern") or (
        rf"(?i)(?:/(?:rtp|ops|sleep|treegen|{re.escape(proj)})\s+test\b|"
        r"\[(?:rtp|ops|treegen)\s+bit\]|"
        r"\b(?:ACTION|BIT|HEATMAP|PROBE|RTP|TEST|OPS|TREEGEN)[-_]\d{1,4}\b)|"
        r"(?<!%)(?-i:\b(?:RTP|OPS|TREEGEN)_[A-Z0-9_]+\b)"
    )

    has_rtp_version = bool(re.search(ver_pat, combined))
    has_server_version = bool(re.search(run_pat, combined))
    has_rtp_test = bool(re.search(tst_pat, combined))
    has_log_link = bool(extract_mclogs_ids(text, manifest=manifest) or fetched_logs.strip())

    return {
        "rtp_version": has_rtp_version,
        "server_version": has_server_version,
        "rtp_test": has_rtp_test,
        "log_link": has_log_link,
    }


def missing_diagnostic_items(
    diag: dict[str, bool],
    is_startup_crash: bool = False,
    project: str = DEFAULT_PROJECT,
    manifest: pathlib.Path | str | dict[str, Any] | None = None,
) -> list[str]:
    """Returns project-parameterized human-readable descriptions of any missing diagnostic items."""
    prompts = get_project_diagnostic_prompts(project, manifest=manifest)
    missing: list[str] = []
    if is_startup_crash:
        if not diag.get("server_version"):
            missing.append(prompts.get("runtime_missing") or "server `/version` or platform/Minecraft version (Paper, Folia, Spigot, Fabric, or NeoForge)")
        if not diag.get("log_link"):
            missing.append(
                f"{prompts.get('log_missing') or 'full `logs/latest.log` or crash report uploaded to <https://mclo.gs/>'} "
                f"{prompts['startup_note']}"
            )
        return missing

    if not diag.get("rtp_version"):
        missing.append(prompts["version_missing"])
    if not diag.get("server_version"):
        missing.append(prompts.get("runtime_missing") or "server `/version` output (Paper, Folia, Spigot, Fabric, or NeoForge version)")
    if not diag.get("rtp_test"):
        missing.append(prompts["test_missing"])
    if not diag.get("log_link"):
        missing.append(prompts.get("log_missing") or "full `logs/latest.log` uploaded to <https://mclo.gs/>")
    return missing


def contextual_missing_diagnostics(
    combined_text: str,
    diag: dict[str, bool],
    cfg_hits: list[dict[str, str]] | None = None,
    doc_hits: list[dict[str, str]] | None = None,
    code_hits: list[dict[str, str]] | None = None,
    is_startup_crash: bool = False,
    project: str = DEFAULT_PROJECT,
    manifest: pathlib.Path | str | dict[str, Any] | None = None,
) -> list[str]:
    """
    Selects the minimum distinguishing evidence for the active hypothesis plane
    instead of demanding all 4 diagnostic items indiscriminately:
      1. Startup / Enable Crash (`is_startup_crash`): `log_link` (or inline stack
         trace / OCR console log) + version context (`rtp_version` or `server_version`).
         Never demands in-game `/rtp test`.
      2. Placement / World / Shape / Y-Bounds Plane: asks which world/region file
         was edited (`definitions/regions/<region>.yml` vs `config.yml`) or what
         coordinates/behavior they see — never demands `mclo.gs` unless an
         exception/crash is reported.
      3. Access / Cooldown / Permission / Menu Trigger Plane: asks whether they
         tested with OP / `rtp.*` vs a standard player permission group (`rtp.use`)
         and which command/addon is active.
      4. Performance / Queue / Lag Plane: asks for `/rtp info` (hot/cold queue
         fill & chunk tickets) or `/rtp test` + platform (`Paper`/`Folia`/`Fabric`/`NeoForge`),
         alongside `logs/latest.log` if ticks are stalling.
      5. Runtime Exception / Unknown Fault Plane: requires `log_link` + `/rtp version`
         + `/version` + `/rtp test`.
    """
    prompts = get_project_diagnostic_prompts(project, manifest=manifest)
    text_low = (combined_text or "").lower()
    cfg_list = list(cfg_hits or [])
    code_list = list(code_hits or [])
    missing: list[str] = []

    if is_startup_crash or STARTUP_CRASH_KEYWORDS.search(combined_text or ""):
        if not (diag.get("rtp_version") or diag.get("server_version")):
            missing.append(
                prompts.get("runtime_missing")
                or "server `/version` output (Paper, Folia, Spigot, Fabric, or NeoForge version)"
            )
        has_inline_trace = bool(
            CONCRETE_EVIDENCE_RE.search(combined_text or "")
            or re.search(r"(?i)\b(?:Exception|Error)\b.*?at\s+[a-z0-9_$.]+\(", combined_text or "", flags=re.DOTALL)
        )
        if not diag.get("log_link") and not has_inline_trace:
            missing.append(
                f"{prompts.get('log_missing') or 'full `logs/latest.log` or crash report uploaded to <https://mclo.gs/>'} "
                f"{prompts['startup_note']}"
            )
        return missing

    has_exception_or_crash = bool(
        code_list
        or re.search(
            r"(?i)\b(?:exception|stack\s*trace|crash(?:es|ed|ing)?|null\s+(?:error|response)|"
            r"throws?\s+up\s+a\s+null|watchdog|noclassdeffounderror|nullpointerexception)\b",
            combined_text or "",
        )
    )
    if has_exception_or_crash:
        return missing_diagnostic_items(diag, is_startup_crash=False, project=project, manifest=manifest)

    top_cfg_files = [str(c.get("file_path") or "") for c in cfg_list[:3]]
    top_cfg_keys = [str(c.get("key_path") or "").replace("defaults.", "") for c in cfg_list[:3]]

    is_placement_plane = bool(
        any(fp.startswith(("definitions/regions/", "definitions/worlds/")) for fp in top_cfg_files)
        or any(kp.startswith(("shape.", "vert.")) for kp in top_cfg_keys)
        or re.search(
            r"(?i)\b(?:nether|roof|radius|border|worldborder|center|centerradius|miny|maxy|"
            r"biome|biomes|world_nether|world_the_end|overworld|caves?|underground|bedrock)\b",
            text_low,
        )
    )
    is_access_plane = bool(
        any(fp in ("addons/guimenu.yml", "economy.yml") for fp in top_cfg_files)
        or any(kp in ("teleportDelay", "teleportCooldown", "cancelDistance", "openOnBareRtp", "invulnerabilityTime") for kp in top_cfg_keys)
        or re.search(
            r"(?i)\b(?:cooldown|delay|cancel|canceldistance|permission|permissions|luckperms|"
            r"op|deop|gui|menu|openonbarertp|price|cost|economy|vault)\b",
            text_low,
        )
    )
    is_perf_plane = bool(
        any(fp == "advanced/performance.yml" for fp in top_cfg_files)
        or re.search(
            r"(?i)\b(?:lag(?:s|ging|ged|gy)?|tps|mspt|slow(?:down)?|queued|freeze(?:s|d|ing)?|"
            r"stutter(?:s|ing)?|stall(?:s|ed|ing)?|hang(?:s|ing)?|unresponsive|chunk(?:s)?)\b",
            text_low,
        )
    )

    if is_perf_plane:
        has_queue_or_test = bool(
            diag.get("rtp_test")
            or re.search(
                r"(?i)(?:/rtp\s+info\b|\bhot_queue\b|\bcold_queue\b|\bqueue\s+fill\b|\bchunk\s+tickets?\b|\bcandidates\s*=\s*\d+)",
                combined_text or "",
            )
        )
        is_stalling = bool(
            re.search(
                r"(?i)\b(?:lag(?:s|ging|ged|gy)?|tps|mspt|freeze(?:s|d|ing)?|stutter(?:s|ing)?|"
                r"stall(?:s|ed|ing)?|hang(?:s|ing)?|unresponsive|time(?:s|\s+everyone)?\s+out)\b",
                text_low,
            )
        )
        if not has_queue_or_test:
            missing.append(f"{prompts['info_cmd']} (hot/cold queue fill & chunk tickets) or {prompts['test_missing']}")
        if not diag.get("rtp_version") and not has_queue_or_test:
            missing.append(prompts["version_missing"])
        if not diag.get("server_version"):
            missing.append(
                prompts.get("runtime_missing")
                or "server `/version` output (Paper, Folia, Spigot, Fabric, or NeoForge version)"
            )
        if is_stalling and not diag.get("log_link"):
            missing.append(prompts.get("log_missing") or "full `logs/latest.log` uploaded to <https://mclo.gs/>")
        return missing

    if is_placement_plane:
        has_file_ctx = bool(
            re.search(
                r"(?i)(?:definitions/(?:regions|worlds)/|\b(?:default|nether|end|config)\.yml\b|\b(?:shape|vert)\s*:)",
                combined_text or "",
            )
        )
        has_coord_ctx = bool(
            re.search(
                r"(?i)(?:\by\s*[=<>:]\s*-?\d+|\b(?:shape|vert)\.[a-z0-9_]+\s*[=:]|\bcoordinates?\b|\bcoords?\b)",
                combined_text or "",
            )
        )
        if not (has_file_ctx and (has_coord_ctx or diag.get("rtp_version") or diag.get("rtp_test"))):
            missing.append(
                "which world/region file you edited (`definitions/regions/<region>.yml` vs `config.yml`) "
                "and what coordinates or landing behavior you see"
            )
        return missing

    if is_access_plane:
        has_perm_group_ctx = bool(
            re.search(r"(?i)\b(?:op|deop|non-op|luckperms|lp|default\s+group|regular\s+players?|rtp\.\*)\b", text_low)
        )
        has_cmd_or_addon_ctx = bool(
            re.search(r"(?i)(?:/rtp\s+[a-z]+|\bguimenu\.yml\b|\bopenonbarertp\b|\bvault\b|\beconomy\.yml\b)", text_low)
        )
        if not (has_perm_group_ctx and has_cmd_or_addon_ctx):
            missing.append(
                "whether you tested with OP / `rtp.*` vs a standard player permission group (`rtp.use`) "
                "and which `/rtp` command or addon is active"
            )
        return missing

    return missing_diagnostic_items(diag, is_startup_crash=False, project=project, manifest=manifest)


def present_diagnostic_items(
    diag: dict[str, bool],
    project: str = DEFAULT_PROJECT,
    manifest: pathlib.Path | str | dict[str, Any] | None = None,
) -> list[str]:
    """Returns project-parameterized human-readable names of diagnostic items already detected."""
    prompts = get_project_diagnostic_prompts(project, manifest=manifest)
    present: list[str] = []
    if diag.get("rtp_version"):
        present.append(prompts["version_present"])
    if diag.get("server_version"):
        present.append(prompts.get("runtime_present") or "server `/version`")
    if diag.get("rtp_test"):
        present.append(prompts["test_present"])
    if diag.get("log_link"):
        present.append(prompts.get("log_present") or "`mclo.gs` log")
    return present


def is_pro_supporter(roles: tuple[str, ...] | list[str]) -> bool:
    """Returns True if the author holds the `@Pro Supporter` role (`#bbb-logs`)."""
    for r in roles:
        low = r.strip().lstrip("@").lower()
        if low in ("pro supporter", "leafrtp pro", "pro"):
            return True
    return False


def is_verified_or_pro(roles: tuple[str, ...] | list[str]) -> bool:
    """
    Returns True if the author holds `@Pro Supporter` or a verified community role
    eligible for the involved causal-analysis answer tier.
    """
    if is_pro_supporter(roles):
        return True
    for r in roles:
        low = r.strip().lstrip("@").lower()
        if low in ("verified", "verified admin", "contributor", "trusted", "supporter", "beta tester"):
            return True
    return False


CONCRETE_EVIDENCE_RE = re.compile(
    r"(?im)(?:"
    r"\[\d{2}:\d{2}:\d{2}\s+(?:INFO|WARN|ERROR)|"
    r"\b(?:java\.lang\.|org\.bukkit\.|io\.github\.dailystruggle)[A-Za-z0-9_$.]+|"
    r"^\s*(?:shape|vert|lockAfterUses|lockAfterResetSeconds|biomeWhitelist|maxHeapPercent|cacheCap|network)\s*:|"
    r"\bcandidates\s*=\s*\d+|\bhot_queue\b|/rtp\s+info\b"
    r")"
)

VAGUE_SYMPTOM_RE = re.compile(
    r"(?i)\b(?:"
    r"lag(?:s|ging|ged|gy)?|tps\s+drops?|drops?\s+tps|mspt|freeze(?:s|d|ing)?|stutter(?:s|ing)?|"
    r"stall(?:s|ed|ing)?|hang(?:s|ing)?|unresponsive|time(?:s|\s+everyone)?\s+out|"
    r"null\s+(?:error|response)|throws?\s+up\s+a\s+null|already\s+teleporting|"
    r"very\s+high\s+up|mid\s*air|midair|"
    r"die(?:s|d)?|dying|suffocat(?:e|es|ed|ing)|"
    r"rtp\.use\s+is\s+(?:not|noct)\s+wo[rt]king|says\s+it'?s\s+disabled|"
    r"error\s+message\s+during\s+random\s+teleport"
    r")\b"
)

SPECIFIC_CONFIG_TARGET_RE = re.compile(
    r"(?i)(?:"
    r"\b(?:gui|book\s+menu|openonbarertp|countdown|delaycountdown|"
    r"radius|centerradius|worldborder|world\s*border|worldborderoverride|"
    r"rectangle|polygon|hexagon|circle|square|ellipse|width|height|"
    r"caves?|underground|requireskylight|nether\s+roof|bedrock\s+roof|miny|maxy|"
    r"ocean|deep_ocean|biomewhitelist|"
    r"invalid\s+command\s+argument|notme|"
    r"maxheappercent|heap\s+usage\s+\d|"
    r"failed\s+to\s+generate\s+a\s+location|could\s+not\s+find\s+a\s+safe|no\s+safe\s+(?:spot|location)|"
    r"teleportdelay|teleportcooldown|canceldistance|fall\s+damage|invulnerabilitytime|platformradius|platformmaterial|"
    r"betterrtp|ezrtp|justrtp|import|migrate|vault|economy|price|placeholder|remaininglockuses|lockafteruses|lockafterresetseconds)\b|"
    r"\b(?:world|region|player)\s*[:=]\s*[A-Za-z0-9_<\[]"
    r")"
)

HOSTILE_POST_RE = re.compile(
    r"(?i)\b(?:"
    r"clumsy\s+hands|learn(?:ing)?\s+to\s+code|deteriorating|ping\s+with\s+answer|officially\s+dead|"
    r"(?:garbage|trash|useless|worst|pathetic|dogshit|horseshit|shit|crap|terrible|horrible|awful|worthless|junk)\s+"
    r"(?:plugin|mod|dev|developer|author|code|support|bot|resource)|"
    r"(?:plugin|mod|code|support|bot)\s+is\s+(?:garbage|trash|useless|worthless|dogshit|shit|crap|a\s+joke|a\s+scam|broken\s+trash)|"
    r"dev(?:eloper)?s?\s+(?:is|are)\s+(?:lazy|incompetent|useless|dead|clueless|stupid|idiot(?:s)?|moron(?:s)?|ignoring|asleep|a\s+scammer)|"
    r"fix\s+your\s+(?:broken\s+|trash\s+|garbage\s+|shit\s+|useless\s+|stupid\s+|damn\s+|fucking\s+)?(?:plugin|mod|code|shit|garbage)|"
    r"1[-\s]*star\s*(?:review|rating)?|one[-\s]*star\s+(?:review|rating)|leaving\s+a\s+(?:1[-\s]*star|bad|negative)\s+review|"
    r"chargeback|demanding\s+a\s+refund|want\s+my\s+money\s+back|waste\s+of\s+money|"
    r"fuck(?:ing|ed|er)?|bullshit|dogshit|horseshit|piece\s+of\s+shit|wtf\s+is\s+this|stfu|dumbass|asshole|idiot(?:s|ic)?|moron(?:s|ic)?|incompetent|scam(?:mer|med)?|"
    r"answer\s+me\s+(?:now|already|immediately)|respond\s+(?:to\s+me\s+)?now|reply\s+(?:to\s+me\s+)?now|wake\s+up\s+dev|stop\s+ignoring\s+me|any\s+staff\s+alive"
    r")\b|"
    r"(?:\bhello+\s*\?{3,}|\?{4,}|!{4,})"
)


def is_hostile_post(
    text: str,
    manifest: pathlib.Path | str | dict[str, Any] | None = None,
) -> bool:
    """Returns True if `text` matches `HOSTILE_POST_RE` or `triage.hostile_pattern` in `support-bot.yml`."""
    if not text:
        return False
    if HOSTILE_POST_RE.search(text):
        return True
    cfg = load_support_bot_manifest(manifest)
    triage_cfg = cfg.get("triage") if isinstance(cfg.get("triage"), dict) else {}
    hostile_pat = str(triage_cfg.get("hostile_pattern") or "").strip()
    if hostile_pat:
        try:
            if re.search(hostile_pat, text):
                return True
        except re.error:
            pass
    return False

SHOP_TALK_ARCH_RE = re.compile(
    r"(?i)\b(?:"
    r"how\s+does\s+the\s+queue\s+work|cache\s+cycle|under\s+the\s+hood|architectur(?:e|al|ally)|"
    r"adr-\d{3}|spatial\s*resolution|spatial\s+memory|archimedean|pipelined\s+(?:parallel|location)|"
    r"parallel\s+location\s+searching|region-file\s+binning|benchmark(?:s|ed|ing)?|tickrate\s+degradation|"
    r"candidates\s*=\s*0|no\s+qualifying\s+backend|network\.enabled|proxy-direct|devstack"
    r")\b"
)

EMPIRICAL_REPRO_RE = re.compile(
    r"(?i)\b(?:"
    r"duration\s+unit\s+is\s+broken|deop(?:ping|ped)?|"
    r"works\s+(?:as\s+normal|great|fine|properly|correctly)\s+(?:when|for|if|without|in)\b|"
    r"fails\s+(?:when|only\s+when|if)\b.*?\bworks\s+(?:when|if)\b|"
    r"in-game\s+but\s+for\s+some\s+reason|"
    r"looking\s+up\s+those\s+methods\s+on\s+(?:the\s+)?github|"
    r"source\s+code\s+on\s+github|"
    r"tab\s+completion\s+works|"
    r"migrating\s+from\s+(?:betterrtp|ezrtp|justrtp).*?(?:exception|error)|"
    r"%rtp_[a-z0-9_]+%"
    r")\b"
)

BUILDER_API_RE = re.compile(
    r"(?i)\b(?:"
    r"developing\s+my\s+(?:server\s+)?plugin|using\s+(?:code|the\s+api)|rtp-api|rtpapi|"
    r"custom\s+shape|rtpaddon|addshape|bed-respawn|anchor-respawn|getbiome\(\)\s+function"
    r")\b"
)

GENERIC_SECTION_AND_SYMPTOM_TOKENS = frozenset({
    "console",
    "startup",
    "enabled",
    "info",
    "version",
    "mode",
    "name",
    "type",
    "time",
    "world",
    "region",
    "teleport",
    "delay",
    "queue",
    "error",
    "null",
    "default",
    "player",
})


def has_corpus_config_target(
    conn: sqlite3.Connection | None,
    text: str,
    project: str = DEFAULT_PROJECT,
) -> bool:
    """
    Data-driven configuration-target detector backed by `config_keys` and
    `corpus_vocab` (`kf > 0` key-name frequency) in `doc-index.sqlite`.

    Why this complements `SPECIFIC_CONFIG_TARGET_RE`:
      - When new YAML keys (e.g. `maxSlope`, `maxHillAngle`, `preferRoadBiomes`)
        are added in future releases, `build_doc_index.py` indexes them into
        `config_keys` and `corpus_vocab` (`kf > 0`, `is_facet = 1`).
      - Even if a user writes a novel config key without an `= value` assignment
        (e.g. `"maxSlope is not working"` or `"how do I set maxHillAngle"`),
        this check recognizes it directly from `doc-index.sqlite` without
        requiring manual edits to `SPECIFIC_CONFIG_TARGET_RE`.
    """
    if conn is None or not text or not text.strip():
        return False
    proj = (project or DEFAULT_PROJECT).strip().lower()
    raw_tokens = re.findall(r"[A-Za-z_][A-Za-z0-9_.-]{2,}", text)
    for tok in raw_tokens:
        t_clean = tok.strip(".-_")
        t_low = t_clean.lower()
        if len(t_low) < 4 or len(t_low) > 128 or t_low in GENERIC_SECTION_AND_SYMPTOM_TOKENS:
            continue
        try:
            row = conn.execute(
                """
                SELECT key_path, file_path
                FROM config_keys
                WHERE lower(project) = ?
                  AND (lower(key_path) = ? OR lower(key_path) LIKE ?)
                  AND file_path NOT LIKE 'definitions/actions/%'
                  AND file_path NOT LIKE 'definitions/effects/%'
                LIMIT 1
                """,
                (proj, t_low, f"%.{t_low}"),
            ).fetchone()
        except sqlite3.OperationalError:
            row = None

        if row is not None:
            kp = str(row["key_path"])
            leaf = kp.split(".")[-1]
            # 1. Any dotted key (`shape.radius`) or camelCase/snake_case schema key (`openOnBareRtp`, `maxSlope`)
            if ("." in t_clean) or any(c.isupper() for c in leaf[1:]) or ("_" in leaf):
                return True
            # 2. Focused single-word key stem in `corpus_vocab` with high key concentration (`1 <= kf <= 2`)
            st = porter_stem_word(t_low)
            try:
                v_row = conn.execute(
                    """
                    SELECT kf, ck, df, is_facet
                    FROM corpus_vocab
                    WHERE stem = ? AND lower(project) = ?
                    LIMIT 1
                    """,
                    (st, proj),
                ).fetchone()
            except sqlite3.OperationalError:
                v_row = None
            if (
                v_row is not None
                and int(v_row["is_facet"]) == 1
                and 1 <= int(v_row["kf"]) <= 2
                and int(v_row["ck"]) <= 6
                and int(v_row["df"]) <= 12
            ):
                return True

        # 3. Compound camelCase/snake_case token in user text whose sub-word stems
        #    match >= 2 `kf > 0` facet stems in `corpus_vocab` (e.g. `maxHillAngle` or `cancelDistance`)
        if any(c.isupper() for c in t_clean[1:]) or "_" in t_clean:
            expanded = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", t_clean)
            sub_stems = [
                porter_stem_word(w)
                for w in re.findall(r"[A-Za-z0-9]{2,}", expanded.lower())
            ]
            if len(sub_stems) >= 2:
                kf_matches = 0
                for sst in dict.fromkeys(sub_stems):
                    try:
                        has_kf = conn.execute(
                            """
                            SELECT 1 FROM corpus_vocab
                            WHERE stem = ? AND lower(project) = ? AND kf > 0 AND is_facet = 1
                            LIMIT 1
                            """,
                            (sst, proj),
                        ).fetchone()
                    except sqlite3.OperationalError:
                        has_kf = None
                    if has_kf is not None:
                        kf_matches += 1
                if kf_matches >= 2:
                    return True

    # 4. Corpus-driven in-game / console message template match (`match_corpus_messages`)
    #    where the matched message's YAML description bridges to an indexed non-message
    #    configuration key in `config_keys` (e.g. `lockedAfterUses` -> `lockAfterUses`,
    #    `lockAfterResetSeconds`).
    for cm in match_corpus_messages(conn, text, project=proj):
        for bf in cm.get("bridged_facets") or []:
            bf_low = str(bf).lower()
            if len(bf_low) < 4 or bf_low in GENERIC_SECTION_AND_SYMPTOM_TOKENS:
                continue
            try:
                cfg_row = conn.execute(
                    """
                    SELECT key_path FROM config_keys
                    WHERE lower(project) = ?
                      AND (lower(key_path) = ? OR lower(key_path) LIKE ?)
                      AND file_path NOT LIKE '%messages/%'
                      AND file_path NOT LIKE 'definitions/actions/%'
                      AND file_path NOT LIKE 'definitions/effects/%'
                    LIMIT 1
                    """,
                    (proj, bf_low, f"%.{bf_low}"),
                ).fetchone()
            except sqlite3.OperationalError:
                cfg_row = None
            if cfg_row is not None:
                kp_match = str(cfg_row["key_path"])
                leaf_match = kp_match.split(".")[-1]
                if any(c.isupper() for c in leaf_match[1:]) or "_" in leaf_match or "." in kp_match:
                    return True
    return False


def has_concrete_diagnostics(
    text: str,
    fetched_logs: str,
    diag: dict[str, bool],
    bit_matches: list[dict[str, object]],
) -> bool:
    """
    Returns True if the post contains concrete diagnostic or configuration evidence
    (`mclo.gs` paste, `/rtp test` or `[RTP bit]` finding, `/rtp info`, console stack
    trace, or a multi-line YAML snippet) rather than a bare symptom claim.
    """
    if bit_matches:
        return True
    if diag.get("log_link") or diag.get("rtp_test"):
        return True
    if diag.get("rtp_version") and diag.get("server_version"):
        return True
    combined = f"{text}\n{fetched_logs}"
    return bool(CONCRETE_EVIDENCE_RE.search(combined))


def should_abstain_for_missing_info(
    normalized_text: str,
    raw_text: str,
    fetched_logs: str,
    diag: dict[str, bool],
    bit_matches: list[dict[str, object]],
    conn: sqlite3.Connection | None = None,
    project: str = DEFAULT_PROJECT,
) -> bool:
    """
    Deterministic Information-Sufficiency & Abstention Gate:
    Returns True when a post reports a vague runtime symptom (`lag`, `freeze`,
    `null error`, `already teleporting`, `very high up`, `not working`, etc.)
    with ZERO concrete diagnostics and without naming a specific configuration
    target (checked via both `SPECIFIC_CONFIG_TARGET_RE` and `has_corpus_config_target`
    against `config_keys` / `corpus_vocab` `kf > 0` in `doc-index.sqlite`).
    """
    if has_concrete_diagnostics(raw_text, fetched_logs, diag, bit_matches):
        return False
    combined = f"{raw_text}\n{normalized_text}"
    if SPECIFIC_CONFIG_TARGET_RE.search(combined):
        return False
    if has_corpus_config_target(conn, combined, project=project):
        return False
    if VAGUE_SYMPTOM_RE.search(combined) or BUG_REPORT_KEYWORDS.search(combined):
        return True
    return False


def classify_interaction_potential(
    combined_text: str,
    diag: dict[str, bool],
    bit_matches: list[dict[str, object]],
    manifest: pathlib.Path | str | dict[str, Any] | None = None,
) -> dict[str, Any]:
    """
    Maintainer-Delight / Positive-Interaction Classifier driven by
    `triage.interest_signals` in `support-bot.yml` (originally derived from
    studying 7,228 historical messages across `#general`, `#issues`, and `#support`):
      1. `shop_talk`: asking about internal architecture, queue/cache cycle,
         spatial memory/spiral math, benchmarks, or multi-server setup;
      2. `empirical_repro`: sharing a methodical A/B reproduction test, config + log
         evidence, or source-code hypothesis;
      3. `builder_api`: integrating against the public API, custom shapes, or event hooks.
    """
    cfg = load_support_bot_manifest(manifest)
    triage_cfg = cfg.get("triage") if isinstance(cfg.get("triage"), dict) else {}
    if is_hostile_post(combined_text, manifest=manifest):
        return {"is_high_signal": False, "categories": [], "reasons": ()}

    categories: list[str] = []
    reasons: list[str] = []
    has_ev = has_concrete_diagnostics(combined_text, "", diag, bit_matches)

    signals_cfg = triage_cfg.get("interest_signals")
    if isinstance(signals_cfg, dict) and signals_cfg:
        for sig_key, sig_def in signals_cfg.items():
            if not isinstance(sig_def, dict):
                continue
            pat_str = str(sig_def.get("pattern") or "").strip()
            if not pat_str:
                continue
            label = str(sig_def.get("label") or sig_key)
            req_ev = bool(sig_def.get("requires_evidence", False))
            try:
                matched = bool(re.search(pat_str, combined_text))
            except re.error:
                matched = False
            if matched and (not req_ev or has_ev or ":" in combined_text or "player:" in combined_text.lower()):
                categories.append(str(sig_key))
                reasons.append(label)
    else:
        if SHOP_TALK_ARCH_RE.search(combined_text):
            categories.append("shop_talk")
            reasons.append("architecture / systems shop-talk")
        if EMPIRICAL_REPRO_RE.search(combined_text) and (has_ev or ":" in combined_text or "player:" in combined_text.lower()):
            categories.append("empirical_repro")
            reasons.append("empirical isolation / config + log repro")
        if BUILDER_API_RE.search(combined_text):
            categories.append("builder_api")
            reasons.append("API / addon / plugin integration")

    return {
        "is_high_signal": len(categories) >= 1,
        "categories": categories,
        "reasons": tuple(reasons),
    }


def _recommend_command_for_key(file_path: str, key_path: str, project: str = "leafrtp") -> str:
    if project.strip().lower() != "leafrtp":
        return f"set `{key_path}: <value>` in `{file_path}` and reload"
    if file_path.startswith("definitions/regions/"):
        region_name = pathlib.Path(file_path).stem
        if key_path == "world":
            return (
                "`/rtp editor`, `/rtp admin setup`, or `/rtp config regions <region> world=<world>` "
                "(create a dedicated region in `definitions/regions/<region>.yml` and map the world in `definitions/worlds/<world>.yml`)"
            )
        if key_path == "worldBorderOverride":
            return (
                "`/rtp editor` or `/rtp config regions "
                f"{region_name} shape.radius=<value>` with a unit suffix (e.g. `2500b` or `5km`, preferred over `worldBorderOverride=true`)"
            )
        if key_path in ("shape.radius", "shape.centerRadius"):
            return (
                f"`/rtp editor` (drag the border on the map), `/rtp admin setup`, or "
                f"`/rtp config regions {region_name} {key_path}=<value>` (e.g. `5000b` or `5km`)"
            )
        if key_path.startswith(("shape.", "vert.")):
            return (
                f"`/rtp editor`, `/rtp admin setup`, or "
                f"`/rtp config regions {region_name} {key_path}=<value>` (in `{file_path}`)"
            )
        return f"`/rtp editor` or `/rtp config regions {region_name} {key_path}=<value>` (in `{file_path}`)"
    if file_path.startswith("definitions/worlds/"):
        world_name = pathlib.Path(file_path).stem
        if key_path == "region":
            return (
                "`/rtp editor`, `/rtp admin setup`, or "
                f"`/rtp config worlds <world> region=<region>` (in `definitions/worlds/<world>.yml`)"
            )
        return f"`/rtp editor` or `/rtp config worlds {world_name} {key_path}=<value>`"
    if file_path.startswith("definitions/effects/"):
        effect_name = pathlib.Path(file_path).stem
        return f"`/rtp config effects {effect_name} {key_path}=<value>`"
    if file_path.startswith("definitions/actions/"):
        return f"edit `{key_path}` in `plugins/RTP/{file_path}` and run `/rtp reload`"
    if file_path == "addons/guimenu.yml" and key_path == "openOnBareRtp":
        return (
            "set `openOnBareRtp: false` in `plugins/RTP/addons/guimenu.yml` and run `/rtp reload` "
            "(or toggle it in `/rtp admin setup`)"
        )
    if file_path.startswith("addons/"):
        return f"edit `{key_path}` in `plugins/RTP/{file_path}` and run `/rtp reload`"
    if file_path.startswith("advanced/messages/") or file_path.startswith("messages/"):
        return f"`/rtp config messages {key_path}=<value>` (or edit `{file_path}` in `/rtp admin`)"
    if file_path == "language.yml":
        return "`/rtp config language <locale>`"
    if file_path == "config.yml" and key_path.startswith(("defaults.shape.", "defaults.vert.")):
        short_kp = key_path[len("defaults."):]
        return (
            f"`/rtp editor`, `/rtp admin setup`, or "
            f"`/rtp config regions default {short_kp}=<value>` (`definitions/regions/<region>.yml`; "
            f"starter fallback: `/rtp config config {key_path}=<value>`)"
        )
    section = pathlib.Path(file_path).stem
    return f"`/rtp config {section} {key_path}=<value>` (or edit `{file_path}` in `/rtp editor`)"


def _summarize_config_description(description: str, max_chars: int = 210) -> str:
    """Extracts complete sentences from a YAML key's comment block up to `max_chars`."""
    if not description:
        return ""
    base_desc = description.split("[Doc context:", 1)[0].strip()
    cleaned_parts: list[str] = []
    for part in base_desc.split(" "):
        if part.startswith("@"):
            continue
        cleaned_parts.append(part)
    text = " ".join(cleaned_parts).strip()
    text = re.sub(r"\(Inherited from.*?\)\s*$", "", text).strip()
    if not text:
        return ""
    sentences = [
        s.strip()
        for s in re.split(r"(?<=[.!?])\s+", text)
        if s.strip() and not s.strip().startswith(("Starter fallback defaults", 'A region/world value of "@config"'))
    ]
    if not sentences:
        sentences = [s.strip() for s in re.split(r"(?<=[.!?])\s+", text) if s.strip()]
    if not sentences:
        return ""
    selected: list[str] = [sentences[0]]
    for sent in sentences[1:]:
        candidate = " ".join(selected + [sent])
        if len(candidate) <= max_chars:
            selected.append(sent)
        else:
            break
    summary = " ".join(selected).strip()
    if len(summary) > max_chars:
        summary = summary[:max_chars].rsplit(" ", 1)[0] + "..."
    if summary and not summary.endswith((".", "!", "?")):
        summary += "."
    return summary


def _format_config_hit(ch: dict[str, str], project: str = "leafrtp") -> str:
    fp = ch["file_path"]
    kp = ch["key_path"]
    cmd_hint = _recommend_command_for_key(fp, kp, project=project)
    meta_parts: list[str] = []
    if ch.get("default_value"):
        meta_parts.append(f"default `{ch['default_value']}`")
    if ch.get("directives"):
        compact_dir = ch["directives"].replace("# ", "")
        meta_parts.append(f"`{compact_dir}`")
    meta_str = f" ({', '.join(meta_parts)})" if meta_parts else ""
    desc_summary = _summarize_config_description(ch.get("description", ""))
    desc_str = f" {desc_summary}" if desc_summary else ""
    return f"- **`{fp}` -> `{kp}`**{meta_str}:{desc_str} Use {cmd_hint}."


def _format_doc_hit(dh: dict[str, str]) -> str:
    snippet = dh.get("snippet") or extract_doc_snippet(dh.get("body", ""))
    if snippet:
        return f"- **{dh['title']} ({dh['heading']}):** {snippet} - <{dh['url']}>"
    return f"- **{dh['title']} ({dh['heading']}):** <{dh['url']}>"


VALID_RTP_SUBCOMMANDS = frozenset({
    "admin", "biome", "config", "editor", "fill", "gui", "help",
    "info", "menu", "player", "region", "reload", "scan", "test",
    "version", "world",
})

NON_CONFIG_DOTTED_SUFFIXES = (
    ".yml", ".yaml", ".json", ".md", ".jar", ".xml", ".gradle",
    ".lock", ".sqlite", ".ps1", ".sh", ".py", ".java", ".properties",
    ".log", ".txt", ".png", ".gif", ".io", ".com", ".org", ".gs",
)

NON_CONFIG_DOTTED_PREFIXES = (
    "rtp.", "betterrtp.", "ezrtp.", "justrtp.", "luckperms.",
    "io.github.", "com.github.", "com.example.", "org.bukkit.",
    "net.minecraft.", "java.", "javax.", "meta-inf.",
    "rtp.addshape", "rtp.scheduler", "rtp.serveraccessor",
    "rtp.configs", "rtp.log", "rtpapi.", "rtprunnable.",
    "shape.contains", "super.", "rtpaddon.",
)


def is_safe_llm_endpoint(url: str) -> bool:
    """
    Verifies that an LLM endpoint URL points strictly to a loopback or private
    LAN host (e.g. `http://127.0.0.1:8080/v1/chat/completions` on the local
    Threadripper GPU server) so forum posts and logs are never sent to an
    external cloud API (`REQ-DISC-005`).
    """
    if not url or not url.strip():
        return False
    try:
        parsed = urllib.parse.urlparse(url.strip())
    except ValueError:
        return False
    if parsed.scheme not in ("http", "https"):
        return False
    host = (parsed.hostname or "").strip().lower()
    if not host:
        return False
    if host in ("localhost", "127.0.0.1", "::1") or host.endswith((".local", ".lan")):
        return True
    try:
        ip = ipaddress.ip_address(host)
        return bool(
            (ip.is_loopback or ip.is_private)
            and not ip.is_link_local
            and not ip.is_unspecified
            and not ip.is_multicast
            and not ip.is_reserved
        )
    except ValueError:
        return False


def get_project_allowed_domains(
    conn: sqlite3.Connection | None = None,
    manifest: pathlib.Path | str | dict[str, Any] | None = None,
) -> set[str]:
    """
    Returns `ALLOWED_DOMAINS` unioned with `security.allowed_domains` from
    `support-bot.yml` and any documentation hostnames indexed in
    `doc_sections.url` so `--project <slug>=<path>@<site_url>` works across
    custom project documentation domains without editing Python constants.
    """
    domains = set(ALLOWED_DOMAINS)
    cfg = load_support_bot_manifest(manifest)
    sec_domains = (cfg.get("security") or {}).get("allowed_domains")
    if isinstance(sec_domains, list):
        for d in sec_domains:
            if str(d).strip():
                domains.add(str(d).strip().lower())
    if conn is not None:
        try:
            for r in conn.execute("SELECT DISTINCT url FROM doc_sections LIMIT 200").fetchall():
                host = (urllib.parse.urlparse(str(r[0])).hostname or "").lower()
                if host:
                    domains.add(host)
        except sqlite3.OperationalError:
            pass
    return domains


def _key_matches_yaml_reference(
    file_paths: list[str],
    yml_ref: str,
) -> bool:
    """
    Returns True if `yml_ref` (e.g. `advanced/performance.yml`, `definitions/regions/nether.yml`,
    or a `/rtp config <section>` alias like `performance` or `regions`) is a valid file target
    for a config key whose `config_keys.file_path` entries are `file_paths`.
    """
    ref_low = yml_ref.strip().strip("`'\"").lower()
    if not ref_low or "<" in ref_low or "[" in ref_low:
        return True
    fps_low = [fp.lower() for fp in file_paths]
    if ref_low.endswith((".yml", ".yaml")):
        if ref_low.startswith("definitions/regions/") or ref_low.endswith("/regions.yml"):
            return any(fp.startswith("definitions/regions/") or fp == "config.yml" for fp in fps_low)
        if ref_low.startswith("definitions/worlds/") or ref_low.endswith("/worlds.yml"):
            return any(fp.startswith("definitions/worlds/") or fp == "config.yml" for fp in fps_low)
        if ref_low.startswith("definitions/effects/"):
            return any(fp.startswith("definitions/effects/") for fp in fps_low)
        if ref_low.startswith("definitions/actions/"):
            return any(fp.startswith("definitions/actions/") for fp in fps_low)
        if "messages" in ref_low:
            return any("messages/" in fp for fp in fps_low)
        return any(fp == ref_low or fp.endswith(f"/{ref_low}") for fp in fps_low)

    # `/rtp config <section>` alias
    if ref_low in ("region", "regions"):
        return any(fp.startswith("definitions/regions/") or fp == "config.yml" for fp in fps_low)
    if ref_low in ("world", "worlds"):
        return any(fp.startswith("definitions/worlds/") or fp == "config.yml" for fp in fps_low)
    if ref_low in ("effect", "effects"):
        return any(fp.startswith("definitions/effects/") for fp in fps_low)
    if ref_low in ("action", "actions"):
        return any(fp.startswith("definitions/actions/") for fp in fps_low)
    if ref_low in ("message", "messages"):
        return any("messages/" in fp for fp in fps_low)
    return any(fp == f"{ref_low}.yml" or fp.endswith(f"/{ref_low}.yml") for fp in fps_low)


RTP_RUNTIME_OVERRIDE_KEYS = frozenset({
    "player", "world", "region", "biome", "near",
})


def verify_llm_draft_against_schema(
    conn: sqlite3.Connection,
    draft: str,
    project: str = "leafrtp",
    retrieved_text: str = "",
    manifest: pathlib.Path | str | dict[str, Any] | None = None,
) -> tuple[bool, str]:
    """
    Deterministic anti-hallucination firewall (`REQ-DISC-003`, `REQ-DISC-005`):
      1. Rejects empty or overly long (>1600 char) drafts.
      2. Rejects `@everyone`, `@here`, `<@&role>` mentions, URLs outside
         `get_project_allowed_domains(conn)`, or `dailystruggle.github.io` URL paths
         that do not match an indexed page prefix in `doc_sections.url`.
      3. Verifies every backticked `<cli_prefix> <subcmd>` against
         `projects.<slug>.valid_subcommands` (`VALID_RTP_SUBCOMMANDS`), rejects
         `projects.<slug>.forbidden_command_patterns` from `support-bot.yml`, and
         enforces `/rtp config <file_alias> <key>=<value>` (or `/rtp editor`).
      4. Verifies both dotted (`a.b`) and flat (`key: value`, `key=value`) config
         keys (plus any paired `.yml` file or `/rtp config <section>`) against
         `config_keys` in `doc-index.sqlite`.
      5. Verifies numeric `key=value` / `key: value` assignments against
         `# @range: [min, max]` directives (and lag-floor invariants such as
         `minTPS >= 19.0`) and string enum assignments against `# @options: [...]`.
    """
    cleaned = draft.strip()
    if not cleaned:
        return False, "empty draft"
    if len(cleaned) > 1600:
        return False, "draft exceeds 1600 chars"
    if ROLE_OR_EVERYONE_MENTION_RE.search(cleaned):
        return False, "contains forbidden role or @everyone mention"

    retrieved_lower = retrieved_text.lower()
    proj_lower = project.strip().lower()
    cfg_manifest = load_support_bot_manifest(manifest)
    proj_manifest = (cfg_manifest.get("projects") or {}).get(proj_lower)
    if not isinstance(proj_manifest, dict):
        proj_manifest = {}

    allowed_domains = get_project_allowed_domains(conn, manifest=cfg_manifest)
    proj_doc_hosts: set[str] = set()
    try:
        for r in conn.execute(
            "SELECT DISTINCT url FROM doc_sections WHERE lower(project) = ? LIMIT 50",
            (proj_lower,),
        ).fetchall():
            h = (urllib.parse.urlparse(str(r[0])).hostname or "").lower()
            if h:
                proj_doc_hosts.add(h)
    except sqlite3.OperationalError:
        pass

    for m in ANY_URL_RE.finditer(cleaned):
        raw_url = m.group(0).rstrip(".,;:!?)>")
        try:
            host = (urllib.parse.urlparse(raw_url).hostname or "").lower()
        except ValueError:
            return False, f"invalid URL: {raw_url}"
        if host not in allowed_domains:
            return False, f"disallowed URL domain: {host}"
        if host == "dailystruggle.github.io" or host in proj_doc_hosts:
            page_prefix = raw_url.split("#")[0].rstrip("/")
            url_row = conn.execute(
                """
                SELECT 1 FROM doc_sections
                WHERE lower(project) = ?
                  AND (url = ? OR url LIKE ? OR rtrim(url, '/') = ?)
                LIMIT 1
                """,
                (proj_lower, f"{page_prefix}/", f"{page_prefix}/#%", page_prefix),
            ).fetchone()
            if url_row is None:
                return False, f"hallucinated doc URL path: {raw_url}"

    # Verify forbidden command patterns and valid subcommands from `support-bot.yml`
    forbidden_pats = proj_manifest.get("forbidden_command_patterns")
    if isinstance(forbidden_pats, list):
        for f_pat in forbidden_pats:
            if not str(f_pat).strip():
                continue
            try:
                if re.search(str(f_pat), cleaned, flags=re.IGNORECASE):
                    if proj_lower == "leafrtp":
                        return False, "invalid /rtp config set syntax; use /rtp config <file_alias> <key>=<value>"
                    return False, f"forbidden command pattern: {f_pat}"
            except re.error:
                pass

    cli_prefix = str(proj_manifest.get("cli_prefix") or ("/rtp" if proj_lower == "leafrtp" else "")).strip()
    valid_subs_raw = proj_manifest.get("valid_subcommands")
    valid_subs = (
        {str(s).strip().lower() for s in valid_subs_raw if str(s).strip()}
        if isinstance(valid_subs_raw, list) and valid_subs_raw
        else (set(VALID_RTP_SUBCOMMANDS) if proj_lower == "leafrtp" else set())
    )
    if cli_prefix and valid_subs:
        for cmd_match in re.finditer(rf"`{re.escape(cli_prefix)}(?:\s+([^\s`=]+))?", cleaned):
            sub = (cmd_match.group(1) or "").strip().lower()
            if not sub or "=" in sub or sub.startswith(("<", "[")):
                continue
            if sub not in valid_subs:
                return False, f"hallucinated {cli_prefix} subcommand: {sub}"

    # Verify backticked /rtp commands and reject `/rtp config set ...` syntax
    if proj_lower == "leafrtp":
        if re.search(r"/rtp\s+config\s+(?:set|get|edit)\b", cleaned, flags=re.IGNORECASE):
            return False, "invalid /rtp config set syntax; use /rtp config <file_alias> <key>=<value>"
        for cfg_cmd_m in re.finditer(r"`/rtp\s+config\s+([^`\n]+)`", cleaned, flags=re.IGNORECASE):
            cfg_args = cfg_cmd_m.group(1).strip().split()
            if not cfg_args:
                continue
            section_arg = cfg_args[0].lower()
            if section_arg in ("set", "get", "edit"):
                return False, f"invalid /rtp config {section_arg} syntax; use /rtp config <file_alias> <key>=<value>"
            if section_arg not in ("import", "language") and not section_arg.startswith(("<", "[")) and len(cfg_args) >= 2:
                kv_args = [a for a in cfg_args[1:] if "=" in a]
                if not kv_args:
                    return False, f"invalid /rtp config syntax (missing <key>=<value>): /rtp config {' '.join(cfg_args)}"
                for kv_arg in kv_args:
                    k_tok = kv_arg.split("=", 1)[0].strip()
                    if not k_tok or k_tok.startswith(("<", "[")):
                        continue
                    k_low = k_tok.lower()
                    k_rows = conn.execute(
                        """
                        SELECT file_path FROM config_keys
                        WHERE lower(project) = ?
                          AND (lower(key_path) = ? OR lower(key_path) LIKE ?)
                        """,
                        (proj_lower, k_low, f"%.{k_low}"),
                    ).fetchall()
                    if not k_rows:
                        return False, f"hallucinated config key: {k_tok}"
                    if not _key_matches_yaml_reference([str(r["file_path"]) for r in k_rows], section_arg):
                        return False, f"hallucinated config key: {k_tok} (not in {section_arg})"

    # Verify backticked dotted identifiers (`a.b`) and flat `key: value` / `key=value` spans
    for line in cleaned.splitlines():
        line_yml_refs = re.findall(r"`([A-Za-z0-9_./-]+\.ya?ml)`", line)
        for span in re.findall(r"`([^`\n]{2,100})`", line):
            s_strip = span.strip()
            if s_strip.lower().startswith("/rtp"):
                continue
            kv_m = re.fullmatch(r"([A-Za-z_][A-Za-z0-9_.-]*)\s*([=:])\s*(.+)", s_strip)
            is_kv = kv_m is not None
            token = kv_m.group(1).strip() if kv_m else s_strip.split("=")[0].split(":")[0].strip()
            is_dotted = bool(re.fullmatch(r"[A-Za-z_][A-Za-z0-9_-]*(?:\.[A-Za-z0-9_-]+)+", token))
            is_flat_kv = is_kv and bool(re.fullmatch(r"[A-Za-z_][A-Za-z0-9_-]{1,48}", token))
            if not is_dotted and not is_flat_kv:
                continue
            tok_lower = token.lower()
            if is_dotted:
                if tok_lower.endswith(NON_CONFIG_DOTTED_SUFFIXES):
                    continue
                if tok_lower.startswith(NON_CONFIG_DOTTED_PREFIXES):
                    continue
            if is_flat_kv and tok_lower in RTP_RUNTIME_OVERRIDE_KEYS:
                val_rhs = kv_m.group(3).strip() if kv_m else ""
                if val_rhs.startswith("<") or (tok_lower in ("player", "biome", "near") and not line_yml_refs):
                    continue

            if is_dotted:
                k_rows = conn.execute(
                    """
                    SELECT file_path FROM config_keys
                    WHERE lower(project) = ?
                      AND (lower(key_path) = ? OR lower(key_path) LIKE ? OR lower(file_path) LIKE ?)
                    """,
                    (proj_lower, tok_lower, f"%.{tok_lower}", f"%{tok_lower.replace('.', '/')}%"),
                ).fetchall()
            else:
                k_rows = conn.execute(
                    """
                    SELECT file_path FROM config_keys
                    WHERE lower(project) = ?
                      AND (lower(key_path) = ? OR lower(key_path) LIKE ?)
                    """,
                    (proj_lower, tok_lower, f"%.{tok_lower}"),
                ).fetchall()

            if not k_rows:
                if not is_kv and is_dotted and tok_lower in retrieved_lower:
                    continue
                return False, f"hallucinated config key: {token}"

            fps = [str(r["file_path"]) for r in k_rows]
            if line_yml_refs and not any(_key_matches_yaml_reference(fps, yref) for yref in line_yml_refs):
                return False, f"hallucinated config key: {token} (not in {line_yml_refs[0]})"
            if tok_lower == "showdashboard" and "guimenu.yml" not in line.lower():
                return False, f"hallucinated config key: {token} (only valid in addons/guimenu.yml)"

    # Verify numeric recommendations against schema `@range: [min, max]` directives
    prose_numeric_labels = {
        "candidates", "queue", "activechunks", "hot_queue",
        "y", "x", "z", "tps", "mspt", "step", "steps", "tier", "tiers",
        "java", "mc", "chunk", "chunks", "block", "blocks", "tick", "ticks",
        "player", "players", "count", "phase", "layer", "stage", "line",
        "id", "min", "max", "code", "port", "gb", "mb", "ms", "sec", "seconds",
    }
    for kv_match in re.finditer(r"(`?)\b([A-Za-z_][A-Za-z0-9_.]*)`?\s*[=:]\s*`?(-?\d+(?:\.\d+)?)(?![A-Za-z])`?", cleaned):
        has_leading_bt = bool(kv_match.group(1))
        raw_k = kv_match.group(2).strip()
        k_name = raw_k.lower()
        try:
            val_num = float(kv_match.group(3))
        except ValueError:
            continue
        if k_name not in ("http", "https") and not k_name.isdigit() and len(k_name) <= 128:
            exists_row = conn.execute(
                """
                SELECT 1 FROM config_keys
                WHERE lower(project) = ?
                  AND (lower(key_path) = ? OR lower(key_path) LIKE ?)
                LIMIT 1
                """,
                (proj_lower, k_name, f"%.{k_name}"),
            ).fetchone()
            if exists_row is None and (has_leading_bt or "." in k_name or k_name not in prose_numeric_labels):
                return False, f"hallucinated config key: {raw_k}"
        if k_name == "mintps" and 0.0 < val_num < 19.0:
            return False, f"hallucinated config key: {raw_k}={val_num} (below 19.0 default TPS lag floor)"
        cfg_row = conn.execute(
            """
            SELECT directives FROM config_keys
            WHERE lower(project) = ?
              AND (lower(key_path) = ? OR lower(key_path) LIKE ?)
              AND directives LIKE '%@range:%'
            LIMIT 1
            """,
            (proj_lower, k_name, f"%.{k_name}"),
        ).fetchone()
        if cfg_row is not None:
            r_match = re.search(r"@range:\s*\[\s*([^,\]]+)\s*,\s*([^,\]]+)\s*\]", str(cfg_row["directives"]))
            if r_match:
                min_s, max_s = r_match.group(1).strip().lower(), r_match.group(2).strip().lower()
                if min_s not in ("null", "none", "") and val_num < float(min_s):
                    return False, f"value {k_name}={val_num} below @range minimum {min_s}"
                if max_s not in ("null", "none", "") and val_num > float(max_s):
                    return False, f"value {k_name}={val_num} exceeds @range maximum {max_s}"

    # Verify string enum assignments against schema `@options: [...]` directives
    for opt_match in re.finditer(r"`?\b([A-Za-z_][A-Za-z0-9_.]*)`?\s*[=:]\s*[`'\"]?([A-Za-z_][A-Za-z0-9_-]{1,32})[`'\"]?", cleaned):
        k_name = opt_match.group(1).strip().lower()
        val_str = opt_match.group(2).strip()
        if len(k_name) > 128 or val_str.lower() in ("true", "false", "value", "name", "default", "config", "auto", "off", "none"):
            continue
        opt_row = conn.execute(
            """
            SELECT directives FROM config_keys
            WHERE lower(project) = ?
              AND (lower(key_path) = ? OR lower(key_path) LIKE ?)
              AND directives LIKE '%@options:%'
            LIMIT 1
            """,
            (proj_lower, k_name, f"%.{k_name}"),
        ).fetchone()
        if opt_row is not None:
            o_match = re.search(r"@options:\s*\[([^\]]+)\]", str(opt_row["directives"]))
            if o_match:
                allowed_opts = {
                    item.strip().strip("\"'").lower()
                    for item in o_match.group(1).split(",")
                    if item.strip()
                }
                if allowed_opts and val_str.lower() not in allowed_opts:
                    return False, f"value {k_name}={val_str} not in @options {sorted(allowed_opts)}"

    return True, "ok"


def _default_llm_http_post(url: str, payload: dict[str, Any], timeout: float) -> dict[str, Any]:
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read(65536).decode("utf-8", errors="replace")
        return json.loads(raw)  # type: ignore[no-any-return]


def select_diverse_config_hits(
    cfg_hits: list[dict[str, str]],
    max_hits: int = 5,
    max_per_file: int = 2,
) -> list[dict[str, str]]:
    """
    Selects up to `max_hits` config keys while capping any single YAML file at
    `max_per_file` on the first pass so multi-hypothesis questions (e.g. queue
    capacity in `default.yml` + warmup delay in `config.yml` + refill cadence in
    `performance.yml`) keep coverage across distinct config files.
    """
    selected: list[dict[str, str]] = []
    file_counts: dict[str, int] = {}
    overflow: list[dict[str, str]] = []
    for ch in cfg_hits:
        fp = ch.get("file_path", "")
        if file_counts.get(fp, 0) < max_per_file:
            selected.append(ch)
            file_counts[fp] = file_counts.get(fp, 0) + 1
            if len(selected) >= max_hits:
                return selected
        else:
            overflow.append(ch)
    for ch in overflow:
        selected.append(ch)
        if len(selected) >= max_hits:
            break
    return selected


def select_diverse_doc_hits(
    doc_hits: list[dict[str, str]],
    max_hits: int = 4,
    max_per_doc: int = 2,
) -> list[dict[str, str]]:
    """Selects up to `max_hits` doc sections across distinct Markdown files."""
    selected: list[dict[str, str]] = []
    path_counts: dict[str, int] = {}
    overflow: list[dict[str, str]] = []
    for dh in doc_hits:
        p = dh.get("path", "")
        if path_counts.get(p, 0) < max_per_doc:
            selected.append(dh)
            path_counts[p] = path_counts.get(p, 0) + 1
            if len(selected) >= max_hits:
                return selected
        else:
            overflow.append(dh)
    for dh in overflow:
        selected.append(dh)
        if len(selected) >= max_hits:
            break
    return selected


class LocalLlmSynthesizer:
    """
    Optional schema-grounded local LLM synthesizer (e.g. `vLLM` / `llama-server`
    running on the local Threadripper 2x RTX 3090 server).

    Safety guarantees:
      - Zero tool definitions, zero shell/filesystem access.
      - Connects only to loopback/private LAN endpoints (`is_safe_llm_endpoint`).
      - Never passes raw user prose to the model: user posts are reduced to
        corpus-whitelisted tokens and matched schema signals via
        `extract_grounded_query_signals` so the user cannot directly prompt or
        jailbreak the agent.
      - Feeds both the generated `<starting_reference_point>` from
        `doc-index.sqlite` and `<retrieved_context>` to infer likely user goals
        and derive actionable plans ordered by confidence level.
      - Verifies every cited `/rtp` command and dotted config key against
        `doc-index.sqlite` via `verify_llm_draft_against_schema`, falling back
        to deterministic FTS5 bullets on timeout, error, or schema mismatch.
    """

    def __init__(
        self,
        endpoint: str,
        model: str = "deepseek-r1:32b",
        timeout: float = 60.0,
        deep_timeout: float = DEEP_SEARCH_TIMEOUT_SECONDS,
        http_post: Callable[[str, dict[str, Any], float], dict[str, Any]] | None = None,
    ) -> None:
        self.endpoint = endpoint.strip()
        self.model = model.strip() or "deepseek-r1:32b"
        self.timeout = max(0.5, float(timeout))
        self.deep_timeout = max(self.timeout, float(deep_timeout))
        self.http_post = http_post or _default_llm_http_post
        self._starting_ref_cache: dict[str, str] = {}
        self.last_refrain_reason: str | None = None
        self.last_clarifying_questions: str | None = None

    def _get_starting_reference(self, conn: sqlite3.Connection, project: str) -> str:
        proj = project.strip().lower() or "leafrtp"
        if proj not in self._starting_ref_cache:
            self._starting_ref_cache[proj] = build_starting_reference_context(conn, project=proj)
        return self._starting_ref_cache[proj]

    def synthesize(
        self,
        conn: sqlite3.Connection,
        user_question: str,
        cfg_hits: list[dict[str, str]],
        doc_hits: list[dict[str, str]],
        bit_matches: list[dict[str, object]],
        project: str = "leafrtp",
        code_hits: list[dict[str, str]] | None = None,
        prior_turns: list[str] | tuple[str, ...] = (),
    ) -> str | None:
        self.last_refrain_reason = None
        self.last_clarifying_questions = None
        if not is_safe_llm_endpoint(self.endpoint):
            return None
        if not cfg_hits and not doc_hits and not bit_matches and not code_hits:
            return None

        _, safe_question = detect_and_redact_secrets(user_question)
        safe_prior_turns = [detect_and_redact_secrets(t)[1] for t in prior_turns if t and t.strip()]
        signals = extract_grounded_query_signals(
            conn=conn,
            user_question=safe_question,
            cfg_hits=cfg_hits,
            doc_hits=doc_hits,
            project=project,
            code_hits=code_hits,
            bit_matches=bit_matches,
            prior_turns=safe_prior_turns,
        )

        diverse_cfg = select_diverse_config_hits(cfg_hits, max_hits=6, max_per_file=3)
        diverse_docs = select_diverse_doc_hits(doc_hits, max_hits=4, max_per_doc=2)
        arch_query = " ".join(signals["corpus_terms"]) if signals.get("corpus_terms") else safe_question
        arch_hits = search_arch_sections(conn, arch_query, limit=4, project=project)

        cfg_context_lines: list[str] = []
        for idx, ch in enumerate(diverse_cfg, start=1):
            cmd_hint = _recommend_command_for_key(ch["file_path"], ch["key_path"], project=project)
            facets_tag = f" [matched_facets={ch['matched_facets']}]" if ch.get("matched_facets") else ""
            short_desc = _summarize_config_description(ch.get("description", ""), max_chars=260)
            cfg_context_lines.append(
                f"{idx}.{facets_tag} file: {ch['file_path']} | "
                f"key: {ch['key_path']} | default: {ch.get('default_value', '')} | "
                f"directives: {ch.get('directives', '')} | description: {short_desc} | "
                f"command: {cmd_hint}"
            )

        doc_context_lines: list[str] = []
        for idx, dh in enumerate(diverse_docs, start=1):
            snippet = dh.get("snippet") or extract_doc_snippet(dh.get("body", ""))
            facets_tag = f" [matched_facets={dh['matched_facets']}]" if dh.get("matched_facets") else ""
            doc_context_lines.append(
                f"{idx}.{facets_tag} title: {dh['title']} ({dh['heading']}) | "
                f"url: <{dh['url']}> | summary: {snippet}\n   excerpt: {dh.get('body', '')[:950]}"
            )

        arch_context_lines: list[str] = []
        for idx, ah in enumerate(arch_hits, start=1):
            snippet = ah.get("snippet") or extract_doc_snippet(ah.get("body", ""), max_chars=260)
            arch_context_lines.append(
                f"{idx}. path: `{ah['path']}` | section: {ah['title']} ({ah['heading']}) | "
                f"url: <{ah['url']}> | summary: {snippet}\n   excerpt: {ah.get('body', '')[:950]}"
            )

        bit_context_lines: list[str] = []
        for bm in bit_matches[:3]:
            bit_context_lines.append(
                f"- code: {bm['key']} ({bm['namespace']}-{int(bm['id']):04d}) | "
                f"remediation: {bm['remediation']}"
            )

        code_context_lines: list[str] = []
        for idx, cs in enumerate((code_hits or [])[:4], start=1):
            code_context_lines.append(
                f"{idx}. class: `{cs.get('package_name', '')}.{cs.get('class_name', '')}` (`{cs.get('path', '')}`) | "
                f"methods: `{cs.get('methods', '')}` | config_refs: `{cs.get('config_refs', '')}` | "
                f"diagnostics: {cs.get('diagnostics', '')[:260]} | summary: {cs.get('summary', '')[:220]}"
            )

        starting_ref = self._get_starting_reference(conn, project=project)
        arch_blob = "\n".join(arch_context_lines) if arch_context_lines else "None"
        code_blob = "\n".join(code_context_lines) if code_context_lines else "None"
        retrieved_blob = "\n".join(
            ["### Combinatorial YAML Schema Matches"]
            + cfg_context_lines
            + ["\n### Combinatorial Documentation Sections"]
            + doc_context_lines
            + (["\n### Matched BIT Fault Codes"] + bit_context_lines if bit_context_lines else [])
            + (["\n### Second-Class Source Code Diagnostics"] + code_context_lines if code_context_lines else [])
        )
        verification_blob = f"{starting_ref}\n{arch_blob}\n{code_blob}\n{retrieved_blob}".strip()

        signals_payload: dict[str, Any] = {
            "corpus_terms_sorted": signals["corpus_terms"],
            "clause_projections_sorted": signals.get("clause_projections", []),
            "expanded_concepts_sorted": signals["expanded_concepts"],
            "multi_facet_intersections": signals.get("combinatorial_intersections", []),
            "matched_in_game_messages": signals.get("matched_in_game_messages", []),
            "intent_profile": signals.get("intent_profile", {}),
        }
        if signals.get("cumulative_turn_projections"):
            signals_payload["cumulative_turn_projections"] = signals["cumulative_turn_projections"]
        if signals.get("matched_code_diagnostics"):
            signals_payload["matched_code_diagnostics"] = signals["matched_code_diagnostics"]
        if signals.get("non_default_yaml_overrides"):
            signals_payload["non_default_yaml_overrides"] = signals["non_default_yaml_overrides"]
        signals_json = json.dumps(signals_payload, ensure_ascii=False)

        system_prompt = (
            "You are the LeafRTP technical support cause-analysis and planning assistant. "
            "For security, user posts (and any multi-turn thread history) are projected into an orderless set "
            "of corpus-verified concepts, clause-grouped token sets, and a deterministic `intent_profile` in "
            "`<extracted_query_signals>` alongside `<starting_reference_point>`, `<retrieved_architecture_and_adrs>`, "
            "and `<retrieved_docs_and_schema>`.\n\n"
            "Instructions:\n"
            "1. Take time to reason carefully about **what the user's cumulative goal is** (`intent_profile` and "
            "`cumulative_turn_projections` if present) versus **what RTP actually does** based on "
            "`<starting_reference_point>`, `<retrieved_architecture_and_adrs>`, and `<retrieved_docs_and_schema>`:\n"
            "   - **Respond according to `intent_profile.detected_intent`:**\n"
            "     * `REFERENCE_LOOKUP` (e.g. placeholders catalog, permissions list, command reference, compatibility matrix): "
            "Route the user directly to the matching documentation sections (`<https://dailystruggle.github.io/RTP/...>`) "
            "and summarize the available reference tokens/formats from the excerpt (e.g. PlaceholderAPI `%rtp_<key>%` in "
            "`COMMANDS.md`, `[Pn]` in `advanced/messages/placeholders.yml`, `{status}`/`{cooldown}` in `CUSTOM_ITEMS.md`, "
            "`[player]`/`[world]` in `ACTIONS.md`). Do NOT invent an unrelated YAML config-fix command when the user is "
            "asking where to find documentation or reference tables.\n"
            "     * `CONFIG_HOWTO`: Provide exact YAML file & key recipes (with defaults and `@range`/`@options`), the "
            "`/rtp editor` or `/rtp config <file_alias> <key>=<value>` command, and the doc URL.\n"
            "     * `TROUBLESHOOTING_DIAGNOSIS`: Rank root causes by likelihood, pairing architectural cause (`what-not-to-do.md`, "
            "BIT code, or log exception) with `/rtp info` or `/rtp test` verification and the exact fix.\n"
            "     * `ARCHITECTURE_EXPLANATION`: Explain how the internal mechanism works from `<retrieved_architecture_and_adrs>` "
            "and cite the governing config levers and doc URL.\n"
            "     * `API_INTEGRATION`: Cite the `rtp-api` classes/methods (e.g. `RTPAddon`, `RTP.addShape`) and developer guide URLs.\n"
            "     * **Choosing to ask clarifying questions or refrain:** If the surviving signals are ambiguous across multiple "
            "interpretations, you may include a final `- **Clarifying Question:** ...` bullet asking for the specific missing "
            "detail or `/rtp info` / `/rtp test` output. If the retrieved context does not match the query signals at all, "
            "you may refrain by outputting `[REFRAIN: <brief status reason for maintainer>]` followed by 1-2 `- **Clarifying Question:** ...` "
            "bullets.\n"
            "   - **Match cause analysis to the goal:** Do NOT confuse *speeding up RTP queue refills* (when players see "
            "`\"queued\"`, where lowering `period` or enabling `postTeleportQueueing` in `advanced/performance.yml` refills "
            "the cache faster) with *stopping server lag / TPS drops* (where the goal is reducing main-thread and chunk-load "
            "pressure).\n"
            "   - **Architectural root causes of server lag:** Out of the box, LeafRTP pre-generates locations asynchronously "
            "in the background (`cacheCap` in `definitions/regions/default.yml`, `autoTune: true` in `advanced/performance.yml`). "
            "When a user reports server lag (`lag`/`lags`), reason from `what-not-to-do.md`, `intended-usage.md`, and the ADRs: "
            "server lag is usually triggered by (a) granting `rtp.unqueued` or `rtp.params` (or `rtp.*`) in the permission "
            "plugin (e.g. LuckPerms) to regular players or wiring dynamic override parameters (`shape=`, `radius=`) into "
            "signs/portals, which bypasses the pre-generated cache and forces live on-demand chunk selection/loading (keep "
            "default players on `rtp.use` only); (b) active misconfigurations in `advanced/performance.yml` such as "
            "`syncLoading: true` (must stay `false`; `true` blocks the main thread) or `viewDistanceSelect > 0` (must stay `0`; "
            "higher values load extra chunks during search), or over-restricting biomes in `advanced/biomes.yml`; or (c) "
            "unpregenerated world terrain (mitigated by keeping `autoTune=true`, **lowering** `maxMSPT` to e.g. `35.0`-`40.0` ms "
            "or **lowering** `syncAllottedTime` to `2`-`3` ms so background work pauses earlier, or raising "
            "`pregeneratedPreference` toward `1.0`). Never recommend a change that makes the user's problem worse (never suggest "
            "`syncLoading=true`, `viewDistanceSelect > 0`, or `maxMSPT > 50.0`).\n"
            "   - **Match directional & numeric targets:** Use `clause_projections_sorted` to pair numeric/directional tokens "
            "with their target key (e.g., `[\"128\", \"bedrock\", \"nether\", \"roof\", \"under\"]` -> set `vert.maxY=120` in "
            "`definitions/regions/default.yml`; `[\"disable\", \"fall\", \"damage\"]` -> increase `invulnerabilityTime` or enable "
            "`platformRadius` in `safety.yml`).\n"
            "   - **Configuration workflow & best practices:** Recommend `/rtp editor` (the browser map workspace), "
            "`/rtp admin setup`, and per-region `definitions/regions/<region>.yml` (`shape.*` and `vert.*` settings with "
            "explicit distance units like `5000b` or `5km`) first rather than `config.yml` `defaults.*` (which is only a "
            "starter fallback). For multi-world questions (e.g. `/rtp` in `world_nether` sending players to the overworld), "
            "recommend creating a dedicated region (`definitions/regions/nether.yml`) and mapping `definitions/worlds/world_nether.yml` "
            "to `region: \"nether\"` via `/rtp editor`, `/rtp admin setup`, or `/rtp config`, rather than changing `default.yml` "
            "to send everyone to the nether. Do NOT recommend enabling `worldBorderOverride: true` when setting `shape.radius` "
            "with a unit suffix (`b`, `km`, `c`) will do.\n"
            "2. After your `<think>...</think>` reasoning, output ONLY 2 to 4 concise Markdown bullet points (`- **...**`) "
            "ordered from **highest confidence** to **lower-confidence alternatives** (confidence is conveyed by bullet order, "
            "so do NOT print `(High confidence)` or `(Medium confidence)` in the bullet title).\n"
            "3. Start each bullet with `- **<Goal, Reference Scope, or Cause>:** `, followed by 1-2 natural "
            "engineering sentences matching `intent_profile.detected_intent` (citing exact YAML file/key + `/rtp ...` command "
            "for config/troubleshooting, or concrete reference tokens/sections for `REFERENCE_LOOKUP`/`API_INTEGRATION`) and "
            "the `<https://dailystruggle.github.io/RTP/...>` link wrapped in `<...>` angle brackets (never wrap `<...>` "
            "URLs inside backticks).\n"
            "4. Do NOT output Markdown headers (`###`), intro/outro paragraphs, numbered lists, or multi-block "
            "`bash` fences outside `<think>...</think>`. Keep the user-facing bullets under 1,200 characters.\n"
            "5. Use ASCII '-' hyphens only (never em-dashes), never raw pipe '|' tables, and never invent config "
            "keys, URLs, or `/rtp` subcommands outside the provided context. Never write `/rtp config set ...`; "
            "the command syntax is `/rtp config <file_alias> <key>=<value>` (e.g. `/rtp config performance maxMSPT=35.0` "
            "or `/rtp config regions default shape.radius=5000b`) or `/rtp editor`. Only cite exact `url: <https://...>` "
            "links listed in `<retrieved_docs_and_schema>` or `<starting_reference_point>` (never guess `/RTP/docs/...` "
            "paths and never end a site URL with `.md`). For `POLYGON` `vertices:`, use integer chunk or block "
            "coordinates (`[200c, 0c]`, `[100c, 173c]`, never decimals), and remember `vert.requireSkyLight` is "
            "`true` for surface-only (prevents caves) and `false` in the Nether."
        )
        user_prompt = (
            f"<starting_reference_point>\n{starting_ref}\n</starting_reference_point>\n\n"
            f"<retrieved_architecture_and_adrs>\n{arch_blob}\n</retrieved_architecture_and_adrs>\n\n"
            f"<retrieved_code_context_second_class>\n{code_blob}\n</retrieved_code_context_second_class>\n\n"
            f"<retrieved_docs_and_schema>\n{retrieved_blob}\n</retrieved_docs_and_schema>\n\n"
            f"<extracted_query_signals>\n{signals_json}\n</extracted_query_signals>\n\n"
            "Think step-by-step about what the user wants (`intent_profile` and any `cumulative_turn_projections`) "
            "versus what RTP does architecturally (checking both permission/usage anti-patterns in `what-not-to-do.md` "
            "such as `rtp.unqueued` / `rtp.params`, second-class source code exception/log diagnostics in "
            "`<retrieved_code_context_second_class>`, and YAML configuration keys or documentation reference sections, "
            "and ensuring every recommendation helps the user's goal rather than making it worse). Then output ONLY "
            "2 to 4 bullets ordered from highest to lowest confidence as `- **<Goal, Reference Scope, or Cause>:** ...` "
            "(plus an optional `- **Clarifying Question:** ...` bullet if needed, or `[REFRAIN: <reason>]` if the retrieved "
            "context cannot answer the query safely; no intro/outro text, no `(High/Medium confidence)` labels, no `###` "
            "headers, respect `@range` limits, <1200 chars):"
        )

        payload: dict[str, Any] = {
            "model": self.model,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            "temperature": 0.1,
            "max_tokens": 3072,
        }

        effective_timeout = (
            self.deep_timeout
            if (code_hits or bit_matches or len(arch_hits) >= 2)
            else self.timeout
        )

        try:
            resp = self.http_post(self.endpoint, payload, effective_timeout)
            choices = resp.get("choices") or []
            if not choices or not isinstance(choices, list):
                return None
            msg = choices[0].get("message") or {}
            content = str(msg.get("content") or "").strip()
        except (OSError, ValueError, KeyError, TypeError):
            return None

        # Strip `<think>...</think>` chain-of-thought blocks emitted by reasoning models (e.g. deepseek-r1)
        content = re.sub(r"(?s)<think>.*?</think>\s*", "", content).strip()
        if "<think>" in content:
            content = content.split("</think>")[-1].strip() if "</think>" in content else ""

        refrain_match = re.search(r"\[REFRAIN(?:\s*:\s*([^\]]+))?\]", content, flags=re.IGNORECASE)
        if refrain_match:
            self.last_refrain_reason = (refrain_match.group(1) or "model refrained due to insufficient or ambiguous context").strip()
            content = re.sub(r"\[REFRAIN(?:\s*:\s*[^\]]+)?\]\s*", "", content, flags=re.IGNORECASE).strip()

        # Convert `[Label](https://...)` markdown links into Discord `<https://...>` angle-bracket links
        content = re.sub(r"\[[^\]]+\]\((https?://[^\s)]+)\)", r"<\1>", content, flags=re.IGNORECASE)
        # Keep only the bullet list lines (plus any indented YAML block or single-line lead-in)
        filtered_lines: list[str] = []
        seen_bullet = False
        for line in content.splitlines():
            s = line.strip()
            if not s:
                if seen_bullet:
                    filtered_lines.append("")
                continue
            if s.startswith(("- ", "* ")) or line.startswith(("  ", "\t", "```")):
                seen_bullet = True
                filtered_lines.append(line)
            elif not seen_bullet and s.startswith("You can "):
                filtered_lines.append(line)
        if filtered_lines:
            content = "\n".join(filtered_lines).strip()

        if self.last_refrain_reason is not None:
            if content:
                ok_q, _ = verify_llm_draft_against_schema(
                    conn=conn,
                    draft=content,
                    project=project,
                    retrieved_text=verification_blob,
                )
                if ok_q:
                    self.last_clarifying_questions = sanitize_bot_output(content)
                else:
                    self.last_clarifying_questions = None
            return None

        ok, _ = verify_llm_draft_against_schema(
            conn=conn,
            draft=content,
            project=project,
            retrieved_text=verification_blob,
        )
        if not ok:
            # Partial bullet salvage: if a multi-bullet response has 1 offending bullet
            # while >= 2 bullets pass schema verification individually, retain the valid bullets.
            bullet_blocks = re.split(r"(?m)(?=^-\s+\*\*)", content)
            valid_blocks = [
                b.strip()
                for b in bullet_blocks
                if b.strip().startswith("- **")
                and verify_llm_draft_against_schema(
                    conn=conn,
                    draft=b.strip(),
                    project=project,
                    retrieved_text=verification_blob,
                )[0]
            ]
            if len(valid_blocks) >= 2:
                content = "\n\n".join(valid_blocks)
            else:
                return None
        content = re.sub(
            r"\s*\((?:high|medium|low)\s+confidence\)(?=\*\*|\s*:)",
            "",
            content,
            flags=re.IGNORECASE,
        )
        return sanitize_bot_output(content)


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
        llm_synthesizer: LocalLlmSynthesizer | None = None,
        translator: Callable[[str], tuple[str, str]] | None = None,
        ocr_extractor: Callable[[str], str] | None = None,
        text_attachment_fetcher: Callable[[str], str] | None = None,
        manifest: pathlib.Path | str | dict[str, Any] | None = None,
    ) -> None:
        self.manifest = load_support_bot_manifest(manifest)
        if conn is not None:
            self.conn = conn
            self._owns_conn = False
        else:
            if not db_path.is_file():
                self.conn = build_index(ROOT, db_path, manifest=self.manifest)
            else:
                self.conn = sqlite3.connect(str(db_path))
                self.conn.row_factory = sqlite3.Row
            self._owns_conn = True

        self.maintainer_id = str(maintainer_id)
        self.cooldown_seconds = cooldown_seconds
        self.log_fetcher = log_fetcher or (lambda pid: fetch_mclogs_raw(pid, manifest=self.manifest))
        self.llm_synthesizer = llm_synthesizer
        self.translator = translator
        self.ocr_extractor = ocr_extractor or ocr_image_source
        self.text_attachment_fetcher = text_attachment_fetcher or read_text_attachment_source
        self.escalated_threads: set[str] = set()
        self.investigative_threads: set[str] = set()
        self.model_refrained_threads: set[str] = set()
        self.followup_answered_threads: set[str] = set()
        self.thread_turns: dict[str, list[str]] = {}
        self.thread_last_facets: dict[str, frozenset[str]] = {}
        self.thread_last_diag: dict[str, frozenset[str]] = {}
        self.refusal_grace_threads: set[str] = set()
        self.thread_bot_reply_counts: dict[str, int] = {}
        self.user_last_action: dict[str, float] = {}

    def _extract_corpus_facets(self, text: str, project: str) -> frozenset[str]:
        """Extracts repository-grounded corpus facets (`is_schema_or_heading_facet`) from `text`."""
        conversational_filler = {
            "waiting", "wait", "still", "tell", "asking", "ask", "please", "pls",
            "help", "thanks", "anyone", "there", "update", "bump", "hello",
            "seriously", "useless", "bot", "private", "logs", "log", "phone",
            "away", "console", "time", "work", "happening", "share", "sharing",
            "upload", "uploading", "post", "posting", "send", "sending", "give",
            "provide", "paste", "refuse", "refusing", "want", "cant", "cannot",
            "wont", "dont", "stop", "quit", "files", "file",
        }
        tokens = extract_specific_tokens(text, conn=self.conn, project=project)
        return frozenset(
            tok.lower()
            for tok in tokens
            if tok.lower() not in conversational_filler
            and is_schema_or_heading_facet(self.conn, tok, project=project)
        )

    def _extract_attachments_ocr(self, attachments: tuple[str, ...] | list[str], project: str) -> str:
        """
        Extracts visible text from up to `MAX_IMAGE_ATTACHMENTS_PER_POST` image
        attachments via `self.ocr_extractor` (`RapidOCR` CPU engine) and runs
        corpus-guided token decompounding (`decompound_ocr_text`).
        """
        if not attachments:
            return ""
        chunks: list[str] = []
        for att in list(attachments)[:MAX_IMAGE_ATTACHMENTS_PER_POST]:
            if not att or any(str(att).lower().endswith(ext) for ext in TEXT_ATTACHMENT_EXTENSIONS):
                continue
            raw_ocr = self.ocr_extractor(att)
            if raw_ocr and raw_ocr.strip():
                decompounded = decompound_ocr_text(self.conn, raw_ocr.strip(), project=project)
                chunks.append(decompounded)
        return "\n".join(chunks)

    def _extract_attachments_text(
        self,
        attachments: tuple[str, ...] | list[str],
    ) -> tuple[str, str]:
        """
        Reads up to 3 `.log`/`.txt`/`.yml`/`.yaml` attachments via `self.text_attachment_fetcher`
        and separates them into `(log_text, yaml_text)`.
        """
        if not attachments:
            return "", ""
        log_chunks: list[str] = []
        yaml_chunks: list[str] = []
        for att in list(attachments)[:3]:
            if not att:
                continue
            low = str(att).strip().lower()
            if not any(low.endswith(ext) for ext in TEXT_ATTACHMENT_EXTENSIONS):
                continue
            content = self.text_attachment_fetcher(str(att))
            if not content or not content.strip():
                continue
            if low.endswith((".yml", ".yaml")):
                yaml_chunks.append(content.strip())
            else:
                log_chunks.append(unwrap_console_paste(content.strip()))
        return "\n".join(log_chunks), "\n".join(yaml_chunks)

    def _retrieve_second_class_code_hits(
        self,
        raw_and_logs: str,
        normalized_text: str,
        cfg_hits: list[dict[str, str]],
        has_high_conf_doc: bool,
        project: str,
    ) -> tuple[list[dict[str, str]], list[dict[str, str]]]:
        """
        Queries the second-class `code_sections` corpus when a post, log paste,
        or screenshot contains exceptions, stack traces, runtime error strings,
        or when first-class docs have no high-confidence match, and bridges any
        referenced `config_refs` from matched Java classes into `cfg_hits`.
        """
        combined_probe = f"{raw_and_logs}\n{normalized_text}"
        has_explicit_error_signal = bool(
            CODE_OR_ERROR_SIGNAL_RE.search(combined_probe)
            or STARTUP_CRASH_KEYWORDS.search(combined_probe)
        )
        has_camelcase_ident = bool(
            re.search(r"\b(?!LeafRTP\b|BetterRTP\b|JustRTP\b|OnePlayerSleep\b|TreeGen\b|NeoForge\b|BuiltByBit\b|PlaceholderAPI\b|LuckPerms\b)[A-Z][a-z0-9]{2,}[A-Z][A-Za-z0-9]*\b", combined_probe)
        )
        should_query_code = (
            has_explicit_error_signal
            or bool(BUG_REPORT_KEYWORDS.search(combined_probe))
            or not has_high_conf_doc
            or has_camelcase_ident
        )
        if not should_query_code:
            return [], cfg_hits

        code_hits = search_code_sections(self.conn, combined_probe, limit=4, project=project)
        if not code_hits:
            return [], cfg_hits

        has_explicit_java_ref = bool(
            has_explicit_error_signal
            or ".java" in combined_probe.lower()
            or any(
                len(str(cs.get("class_name") or "")) >= 6
                and re.search(rf"\b{re.escape(str(cs['class_name']))}\b", combined_probe)
                for cs in code_hits[:3]
            )
        )
        if not has_explicit_error_signal:
            if not has_explicit_java_ref:
                return [], cfg_hits
            if not cfg_hits and not any(cs.get("config_refs") for cs in code_hits[:2]):
                return code_hits, cfg_hits

        bridged_cfg = list(cfg_hits)
        seen_keys = {c["key_path"].replace("defaults.", "").lower() for c in bridged_cfg}
        for cs in code_hits[:3]:
            for ref_key in (cs.get("config_refs") or "").split():
                clean_ref = ref_key.strip()
                if len(clean_ref) < 3 or clean_ref.lower() in seen_keys:
                    continue
                ref_matches = search_config_keys(self.conn, clean_ref, limit=1, project=project)
                for rm in ref_matches:
                    canon_k = rm["key_path"].replace("defaults.", "").lower()
                    if canon_k not in seen_keys:
                        seen_keys.add(canon_k)
                        bridged_cfg.append(rm)
                        break
        return code_hits, bridged_cfg

    def will_run_deep_search(
        self,
        post: ForumPostContext,
        followup_text: str = "",
        followup_attachments: tuple[str, ...] = (),
    ) -> bool:
        """
        Returns True if evaluating `post` (or a follow-up) is expected to perform
        a deep search (invoking `LocalLlmSynthesizer` or OCR + log/code analysis)
        so `DiscordForumPoller` can post an immediate "working on it" status
        message and edit it in place once the response is ready.
        """
        if post.author_id == self.maintainer_id:
            return False
        project = resolve_post_project(post.applied_tags, manifest=self.manifest)
        if not has_project_docs(self.conn, project):
            return False
        if self.llm_synthesizer is None:
            return False
        combined = f"{post.title}\n{post.body}\n{followup_text}".strip()
        all_att = tuple(post.attachments) + tuple(followup_attachments)
        if all_att or extract_mclogs_ids(combined, manifest=self.manifest) or is_verified_or_pro(post.author_roles):
            return True
        diag = inspect_diagnostics(combined, "", project=project, manifest=self.manifest)
        bit_matches = extract_bit_codes(self.conn, combined, project=project, manifest=self.manifest)
        return not should_abstain_for_missing_info(
            normalized_text=combined,
            raw_text=combined,
            fetched_logs="",
            diag=diag,
            bit_matches=bit_matches,
            conn=self.conn,
            project=project,
        )

    def _prepare_multilingual_text(self, raw_text: str, project: str) -> str:
        """
        Two-Pass Token Harvesting (`REQ-DISC-005`) with Dual-Translator Selection:
          1. Pass 1 (Pre-Translation Exact Harvest): Scans `raw_text` against
             `locale_aliases` (`doc-index.sqlite`) BEFORE any NMT runs so
             literal `.lang.yml` config keys are locked in immediately.
          2. Pass 2 (Dual-Service Symptom Translation): Runs two differently
             functioning translation services (`google_nmt` and `mymemory_tm`
             via `translate_candidates_to_english` when `self.translator` is
             `autotranslate_to_english`), selects the higher-scoring candidate
             against `doc-index.sqlite` (`select_best_translation`), and unions
             the resulting tokens with Pass 1.
        """
        effective_translator = (
            translate_candidates_to_english
            if self.translator is autotranslate_to_english
            else self.translator
        )
        normalized, _ = normalize_multilingual_query(
            self.conn,
            raw_text,
            project=project,
            translator=effective_translator,
        )
        return normalized

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
        investigative_note: str = "",
        worth_a_look_reasons: list[str] | None = None,
        code_classes: list[str] | None = None,
        cumulative_facets: list[str] | None = None,
    ) -> str:
        if pro_priority:
            tier = "💎 PRO PRIORITY"
        elif worth_a_look_reasons:
            tier = "🤝 WORTH A LOOK"
        else:
            tier = "🐛 VERIFIED INTAKE"
        tags_str = ", ".join(post.applied_tags) if post.applied_tags else "untagged"
        bit_str = ", ".join(bit_keys) if bit_keys else "none"
        logs_str = ", ".join(f"<https://mclo.gs/{pid}>" for pid in mclogs_ids) if mclogs_ids else "inline"
        if worth_a_look_reasons:
            logs_str = f"{logs_str} | Signal: {', '.join(worth_a_look_reasons)}"
        if code_classes:
            logs_str = f"{logs_str} | Code: {', '.join(code_classes[:3])}"
        if cumulative_facets:
            logs_str = f"{logs_str} | Facets: {', '.join(cumulative_facets[:6])}"
        if investigative_note:
            clean_note = re.sub(r"\s+", " ", investigative_note).strip()
            if len(clean_note) > 110:
                clean_note = clean_note[:107] + "..."
            logs_str = f"{logs_str} | Context: {clean_note}"
        lines = [
            f"**[{tier}]** Thread <#{post.thread_id}> (`{post.thread_id}`) by <@{post.author_id}> ({post.author_name}) - <@{self.maintainer_id}>",
            f"- **Title & Tags:** {post.title.strip()} ({tags_str})",
            f"- **BIT Findings:** `{bit_str}`",
            f"- **Logs:** {logs_str}",
        ]
        return sanitize_bot_output("\n".join(lines))

    def _format_doc_gap_digest(
        self,
        post: ForumPostContext,
        corpus_terms: list[str],
        detected_intent: str,
        filled_by: str,
        target_doc: str,
    ) -> str:
        """
        Formats a concise, non-pinging `[📚 DOC GAP]` card for `#bot-triage-queue`
        when a non-hostile user question exposed a documentation gap (either bridged
        by second-class `.java` `code_sections` / `arch_sections` ADRs, or forced
        into investigative clarification on a config/usage topic).
        """
        tags_str = ", ".join(post.applied_tags) if post.applied_tags else "untagged"
        terms_str = ", ".join(corpus_terms[:10]) if corpus_terms else "none"
        lines = [
            f"**[📚 DOC GAP]** Thread <#{post.thread_id}> (`{post.thread_id}`) by <@{post.author_id}> ({post.author_name})",
            f"- **Title & Tags:** {post.title.strip()} ({tags_str})",
            f"- **Cumulative Facets & Intent:** `{terms_str}` (`{detected_intent}`)",
            f"- **Gap Bridge & Doc Target:** Filled by {filled_by} -> Update {target_doc}",
        ]
        return sanitize_bot_output("\n".join(lines))

    def _detect_and_format_doc_gap(
        self,
        post: ForumPostContext,
        specific_tokens: list[str],
        corpus_terms: list[str] | None,
        detected_intent: str,
        cfg_hits: list[dict[str, str]],
        doc_hits: list[dict[str, str]],
        code_hits: list[dict[str, str]],
        arch_hits: list[dict[str, str]] | None = None,
        has_high_conf_doc_section: bool = False,
        has_medium_only_doc: bool = False,
        model_refrained_reason: str | None = None,
        is_startup_crash: bool = False,
        is_hostile: bool = False,
    ) -> str | None:
        """
        Detects when a non-hostile thread exposes an operator documentation gap:
          a. Code/ADR Bridge Used for Missing Operator Doc: `not has_high_conf_doc_section`
             in `doc_sections`, while `code_hits` (`code_sections`) or `arch_hits`
             (`arch_sections`) matched and bridged the answer via `config_refs` / ADR prose, OR
          b. Undocumented Config/Usage Scenario: `detected_intent` is in
             `("CONFIG_HOWTO", "REFERENCE_LOOKUP", "ARCHITECTURE_EXPLANATION", "API_INTEGRATION")`
             (not a runtime crash) where `has_medium_only_doc` or `model_refrained_reason`
             forced an investigative prompt.
        """
        if is_hostile:
            return None
        terms = list(dict.fromkeys((corpus_terms or []) + (specific_tokens or [])))
        if not terms:
            return None

        arch_list = [
            a for a in (arch_hits or [])
            if not str(a.get("path") or "").endswith(("what-not-to-do.md", "intended-usage.md"))
        ]
        code_class_missing_from_docs = bool(
            code_hits
            and any(
                cs.get("class_name")
                and not any(
                    str(cs["class_name"]).lower()
                    in f"{d.get('title', '')} {d.get('heading', '')} {d.get('body', '')}".lower()
                    for d in doc_hits[:3]
                )
                for cs in code_hits[:2]
            )
        )
        has_code_or_adr_bridge = bool(
            (not has_high_conf_doc_section or code_class_missing_from_docs)
            and (
                (code_hits and any(cs.get("config_refs") or cs.get("class_name") for cs in code_hits[:2]))
                or (arch_list and bool(corpus_terms))
            )
            and (bool(corpus_terms) or bool(cfg_hits) or bool(code_hits))
        )
        has_undocumented_usage = bool(
            not is_startup_crash
            and detected_intent in ("CONFIG_HOWTO", "REFERENCE_LOOKUP", "ARCHITECTURE_EXPLANATION", "API_INTEGRATION", "CLARIFY_INTENT")
            and (has_medium_only_doc or model_refrained_reason is not None)
        )
        if not has_code_or_adr_bridge and not has_undocumented_usage:
            return None

        bridge_parts: list[str] = []
        if code_hits:
            classes = [f"`{c['class_name']}.java`" for c in code_hits[:2] if c.get("class_name")]
            refs = [
                r
                for c in code_hits[:2]
                for r in str(c.get("config_refs") or "").split()[:2]
                if r
            ]
            if classes:
                bridge_parts.append("code_sections " + ", ".join(classes))
            if refs:
                bridge_parts.append("config_refs `" + ", ".join(dict.fromkeys(refs)) + "`")
        if cfg_hits:
            top_ck = cfg_hits[0]
            bridge_parts.append(f"config_keys `{top_ck['file_path']} -> {top_ck['key_path']}`")
        if arch_list:
            top_ar = arch_list[0]
            bridge_parts.append(f"arch_sections `{top_ar.get('path', 'docs/adr/')}`")
        if model_refrained_reason and not bridge_parts:
            bridge_parts.append(f"clarifying `[REFRAIN: {model_refrained_reason[:60]}]`")
        filled_by = "; ".join(bridge_parts) if bridge_parts else "medium-confidence schema fallback"

        target_candidates: list[str] = []
        if doc_hits:
            target_candidates.append(f"`{doc_hits[0]['path']}`")
        elif cfg_hits:
            fp = cfg_hits[0]["file_path"]
            kp = cfg_hits[0]["key_path"]
            if fp.startswith("definitions/regions/"):
                target_candidates.append("`docs/admin/configuration/REGIONS.md`")
            elif fp.startswith("definitions/worlds/"):
                target_candidates.append("`docs/admin/configuration/WORLDS.md`")
            elif fp == "advanced/performance.yml":
                target_candidates.append("`docs/admin/configuration/PERFORMANCE.md`")
            elif fp.startswith("advanced/messages/"):
                target_candidates.append("`docs/admin/configuration/MESSAGES.md`")
            else:
                target_candidates.append("`docs/admin/configuration/CONFIGURATION.md`")
            target_candidates.append(f"YAML `# @` comment on `{fp} -> {kp}`")
        else:
            target_candidates.append("`docs/admin/FAQ.md` or `docs/admin/COMMANDS.md`")
        target_doc = " / ".join(dict.fromkeys(target_candidates))

        return self._format_doc_gap_digest(
            post=post,
            corpus_terms=terms,
            detected_intent=detected_intent,
            filled_by=filled_by,
            target_doc=target_doc,
        )

    def _format_refrain_status(
        self,
        post: ForumPostContext,
        reason: str,
        detected_intent: str,
        missing_diag: list[str],
        cumulative_terms: list[str] | None = None,
    ) -> str:
        """
        Formats a non-pinging private status update for `#bot-triage-queue`
        whenever the bot refrains from giving a definitive answer (either via
        deterministic abstention or model-chosen `[REFRAIN: ...]`).
        """
        tags_str = ", ".join(post.applied_tags) if post.applied_tags else "untagged"
        missing_str = ", ".join(missing_diag[:3]) if missing_diag else "clarification"
        terms_str = ", ".join((cumulative_terms or [])[:8]) or "none"
        lines = [
            f"**[⏳ BOT REFRAINED]** Thread <#{post.thread_id}> (`{post.thread_id}`) by <@{post.author_id}> ({post.author_name})",
            f"- **Title & Tags:** {post.title.strip()} ({tags_str})",
            f"- **Intent & Signals:** `{detected_intent}` | Facets: `{terms_str}`",
            f"- **Status:** {reason} (awaiting: {missing_str})",
        ]
        return sanitize_bot_output("\n".join(lines))

    def _build_abstention_reply(
        self,
        combined_text: str,
        normalized_text: str,
        diag: dict[str, bool],
        missing_diag: list[str],
        is_startup_crash: bool,
        project: str = DEFAULT_PROJECT,
    ) -> str:
        """
        Builds a concise, non-preachy abstention reply when a user reports a
        vague runtime symptom without diagnostic context, explaining why the
        bot is holding off on config recommendations until it sees their setup.
        Parameterized by `project` (`leafrtp`, `oneplayersleep`, `treegen`).
        """
        q_lower = f"{combined_text}\n{normalized_text}".lower()
        prompts = get_project_diagnostic_prompts(project, manifest=self.manifest)
        present = present_diagnostic_items(diag, project=project, manifest=self.manifest)
        ack_prefix = f"I already noted your {', '.join(present)}. " if present else ""

        if is_startup_crash:
            bullets = [f"- {item}" for item in missing_diag]
            return (
                f"{ack_prefix}A startup or plugin-enable failure usually points to a server/Java "
                "version mismatch or a corrupted YAML file. Rather than guessing without the stack trace, "
                "please share:\n"
                + "\n".join(bullets)
                + "\nOnce you reply with the log, I will narrow down the cause or queue this thread in `#bot-triage-queue`."
            )

        if any(w in q_lower for w in ("lag", "slow", "tps", "mspt", "freeze", "stutter", "stall", "hang", "unresponsive", "time out", "memory", "ram", "heap")):
            intro = f"{ack_prefix}{prompts['lag_intro']}"
            bullets = [prompts["lag_bullet"]]
        else:
            intro = f"{ack_prefix}{prompts['cmd_intro']}"
            bullets = [prompts["cmd_bullet"]]

        for item in missing_diag:
            bullets.append(f"- {item}")

        return (
            f"{intro}\n"
            + "\n".join(bullets)
            + "\nOnce you reply with those details, I will give you the exact fix or queue this thread in `#bot-triage-queue`."
        )

    def _build_investigative_prompt(
        self,
        combined_text: str,
        diag: dict[str, bool],
        missing_diag: list[str],
        has_doc_or_cfg: bool,
        is_bug: bool,
        is_startup_crash: bool,
        project: str = DEFAULT_PROJECT,
    ) -> str:
        q_lower = combined_text.lower()
        prompts = get_project_diagnostic_prompts(project, manifest=self.manifest)
        present = present_diagnostic_items(diag, project=project, manifest=self.manifest)
        ack_prefix = f"I already noted your {', '.join(present)}. " if present else ""

        questions: list[str] = []
        if is_startup_crash:
            questions.append(
                f"Since the server is failing on startup/enable, you do not need to run in-game {prompts['test_present']}."
            )
            for item in missing_diag:
                questions.append(f"- {item}")
            return (
                f"{ack_prefix}To narrow down this startup issue before I ping the maintainer, please share:\n"
                + "\n".join(questions)
                + "\nOnce you reply with the log, I will queue this thread in `#bot-triage-queue` right away."
            )

        proj_low = (project or DEFAULT_PROJECT).strip().lower()
        missing_low = " ".join(missing_diag).lower()
        if proj_low != "leafrtp":
            if any(w in q_lower for w in ("lag", "slow", "tps", "mspt", "chunk", "queue", "memory", "heap", "stall", "freeze", "folia")):
                questions.append(prompts["lag_bullet"])
            else:
                questions.append(prompts["cmd_bullet"])
        elif any(w in q_lower for w in ("radius", "border", "center", "spawn", "region", "nether", "end", "cave", "miny", "maxy", "biome")):
            if "definitions/regions/" not in missing_low:
                questions.append(
                    "- Which file did you edit (`plugins/RTP/config.yml` vs `definitions/regions/<region>.yml`), and does that region file use `shape: \"@config\"` or an inline `shape:` block?"
                )
        elif any(w in q_lower for w in ("gui", "menu", "openonbarertp", "permission", "luckperms", "cooldown", "delay", "price", "cost", "economy", "vault", "shop", "action")):
            if "rtp.use" not in missing_low and "op" not in missing_low:
                questions.append(
                    "- What exact command or setup are you testing, and is the player testing with OP or a standard permission group (`rtp.use`, `rtp.gui`)?"
                )
        elif any(w in q_lower for w in ("lag", "slow", "tps", "mspt", "chunk", "queue", "memory", "heap", "stall", "freeze", "folia")):
            if "hot/cold queue fill" not in missing_low and "/rtp info" not in missing_low:
                questions.append(
                    "- What does `/rtp info` report for hot/cold queue fill and active chunk tickets when the slowdown happens?"
                )
        elif not missing_diag:
            questions.append(
                "- What exact behavior or integration are you trying to achieve, and what config changes or commands have you tried so far?"
            )

        for item in missing_diag:
            clean_item = item.strip()
            if clean_item and clean_item[0].islower():
                clean_item = clean_item[0].upper() + clean_item[1:]
            bullet = f"- {clean_item}"
            if bullet not in questions:
                questions.append(bullet)

        if has_doc_or_cfg:
            header = (
                f"{ack_prefix}If the reference above solves your issue, react with ✅ (or reply `solved`). "
                "If it does not cover your case, help me narrow this down so the maintainer has everything needed to step in:"
            )
        elif is_bug:
            header = f"{ack_prefix}To investigate this issue and hand a complete report to the maintainer, please reply with:"
        else:
            header = (
                f"{ack_prefix}To help me gather the right context so the maintainer can answer your question directly, please share:"
            )

        return (
            f"{header}\n"
            + "\n".join(questions)
            + "\nOnce you reply with those details (or react with ❓ after sharing your setup), I will queue this thread in `#bot-triage-queue`."
        )

    def _is_investigation_complete(
        self,
        combined_text: str,
        diag: dict[str, bool],
        missing_diag: list[str],
        is_startup_crash: bool,
        is_investigative: bool,
        followup_text: str = "",
    ) -> bool:
        if len(missing_diag) == 0:
            return True
        if is_startup_crash and diag.get("log_link"):
            return True
        if is_investigative and followup_text.strip():
            # For an investigative how-to / uncertain-doc thread (where the user was asked
            # narrowing questions), escalate once the user replies with concrete technical
            # context + at least one version/test/log/config detail, unless it is a pure
            # runtime bug report with zero logs/test output.
            has_bug_kw = bool(BUG_REPORT_KEYWORDS.search(combined_text))
            present_count = sum(1 for v in diag.values() if v)
            followup_tokens = extract_specific_tokens(followup_text)
            has_config_detail = any(
                ext in followup_text.lower()
                for ext in (".yml", "shape:", "radius", "openonbarertp", "region:", "world:", "/rtp ")
            )
            if not has_bug_kw and (present_count >= 1 or has_config_detail) and len(followup_tokens) >= 1:
                return True
            if has_bug_kw and present_count >= 3 and (diag.get("log_link") or diag.get("rtp_test")):
                return True
        return False

    def handle_new_thread(self, post: ForumPostContext) -> BotDecision:
        """Evaluates a newly created `#support` Forum thread."""
        if post.author_id == self.maintainer_id:
            return BotDecision(
                reply_text="",
                applied_tags=post.applied_tags,
                components=[],
                allowed_mentions=AllowedMentions(),
                should_reply=False,
            )

        project = resolve_post_project(post.applied_tags, manifest=self.manifest)
        project_has_docs = has_project_docs(self.conn, project)

        att_urls = list(dict.fromkeys(list(post.attachments) + extract_image_attachment_urls((), inline_text=f"{post.title}\n{post.body}")))
        text_att_urls = extract_text_attachment_urls(post.attachments, inline_text=f"{post.title}\n{post.body}")
        att_log_text, att_yaml_text = self._extract_attachments_text(text_att_urls)
        ocr_text = self._extract_attachments_ocr(att_urls, project=project)
        combined_user_text = unwrap_console_paste(f"{post.title}\n{post.body}")
        if att_yaml_text:
            combined_user_text = f"{combined_user_text}\n{att_yaml_text}".strip()
        if ocr_text:
            combined_user_text = f"{combined_user_text}\n{ocr_text}".strip()
        mclogs_ids = extract_mclogs_ids(combined_user_text, manifest=self.manifest)
        fetched_logs = "\n".join(
            p for p in ([unwrap_console_paste(self.log_fetcher(pid)) for pid in mclogs_ids if pid] + ([att_log_text] if att_log_text else [])) if p
        )
        if ocr_text and not fetched_logs and (
            CONCRETE_EVIDENCE_RE.search(ocr_text)
            or CODE_OR_ERROR_SIGNAL_RE.search(ocr_text)
            or len(ocr_text.splitlines()) >= 4
        ):
            # Allow console/chat screenshots with concrete diagnostic output to count alongside logs
            fetched_logs = ocr_text

        has_secret_u, combined_user_text = detect_and_redact_secrets(combined_user_text)
        has_secret_l, fetched_logs = detect_and_redact_secrets(fetched_logs)
        has_secret = has_secret_u or has_secret_l
        secret_notice = ""
        if has_secret:
            secret_notice = (
                "**Security warning:** It looks like your post or log contains a password or "
                "`network.yml` secret (`RTP_NET_SECRET` / Redis / SQL credential). Please edit "
                "or delete the paste and rotate that secret right away.\n\n"
            )

        # If the thread belongs to a plugin whose docs/configs/BIT codes are not indexed yet,
        # stay silent unless a credential was leaked in the post.
        if not project_has_docs:
            if has_secret:
                return BotDecision(
                    reply_text=sanitize_bot_output(secret_notice.strip()),
                    applied_tags=post.applied_tags,
                    components=[],
                    allowed_mentions=AllowedMentions(users=(post.author_id,)),
                    secret_warning=True,
                    should_reply=True,
                )
            return BotDecision(
                reply_text="",
                applied_tags=post.applied_tags,
                components=[],
                allowed_mentions=AllowedMentions(),
                should_reply=False,
            )

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

        # Step 1: Deterministic [RTP bit] & BitCode lookup scoped to project
        bit_matches = extract_bit_codes(self.conn, f"{combined_user_text}\n{fetched_logs}", project=project, manifest=self.manifest)
        yaml_overrides, diffed_user_text = extract_non_default_yaml_overrides(
            self.conn, combined_user_text, project=project
        )
        normalized_user_text = self._prepare_multilingual_text(diffed_user_text, project=project)
        if yaml_overrides:
            ov_keys = " ".join(ov["key_path"].replace("defaults.", "") for ov in yaml_overrides)
            normalized_user_text = f"{normalized_user_text}\n{ov_keys}".strip()
        is_startup_crash = bool(STARTUP_CRASH_KEYWORDS.search(normalized_user_text))
        diag = inspect_diagnostics(normalized_user_text, fetched_logs, project=project, manifest=self.manifest)
        missing_diag = missing_diagnostic_items(diag, is_startup_crash=is_startup_crash, project=project, manifest=self.manifest)
        all_diag_present = len(missing_diag) == 0

        sections: list[str] = []
        if secret_notice:
            sections.append(secret_notice.strip())

        bystander_joined = "\n".join(b for b in post.bystander_messages if b and b.strip())
        danger_warning = build_dangerous_advice_warning(f"{combined_user_text}\n{bystander_joined}")
        if danger_warning:
            sections.append(danger_warning)

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

        # Step 2: Deterministic Information-Sufficiency & Abstention Gate + Positive-Interaction Matchmaking
        has_bug_kw = bool(
            BUG_REPORT_KEYWORDS.search(normalized_user_text)
            or CODE_OR_ERROR_SIGNAL_RE.search(combined_user_text)
        )
        is_bug = has_bug_kw
        specific_tokens = extract_specific_tokens(normalized_user_text, conn=self.conn, project=project)
        verified_user = is_verified_or_pro(post.author_roles)
        has_diag_evidence = has_concrete_diagnostics(combined_user_text, fetched_logs, diag, bit_matches)
        hostile = is_hostile_post(f"{combined_user_text}\n{normalized_user_text}", manifest=self.manifest)
        should_abstain = should_abstain_for_missing_info(
            normalized_text=normalized_user_text,
            raw_text=combined_user_text,
            fetched_logs=fetched_logs,
            diag=diag,
            bit_matches=bit_matches,
            conn=self.conn,
            project=project,
        )
        interaction = classify_interaction_potential(
            combined_text=f"{combined_user_text}\n{normalized_user_text}",
            diag=diag,
            bit_matches=bit_matches,
            manifest=self.manifest,
        )

        # Unverified users posting a vague symptom with zero diagnostics are stopped
        # deterministically before any LLM call or speculative config recommendation.
        self.thread_turns[post.thread_id] = [normalized_user_text]
        self.thread_last_facets[post.thread_id] = self._extract_corpus_facets(normalized_user_text, project=project)
        self.thread_last_diag[post.thread_id] = frozenset(k for k, v in diag.items() if v)
        if should_abstain and not verified_user:
            self.investigative_threads.add(post.thread_id)
            if TAG_NEEDS_INFO not in tags and TAG_SOLVED not in tags:
                tags.append(TAG_NEEDS_INFO)
            sections.append(
                self._build_abstention_reply(
                    combined_text=combined_user_text,
                    normalized_text=normalized_user_text,
                    diag=diag,
                    missing_diag=missing_diag,
                    is_startup_crash=is_startup_crash,
                    project=project,
                )
            )
            refrained_status = self._format_refrain_status(
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
                reason="deterministic abstention on zero-diagnostic runtime symptom",
                detected_intent="ABSTAIN_NEEDS_INFO",
                missing_diag=missing_diag,
                cumulative_terms=specific_tokens,
            )
            return BotDecision(
                reply_text=sanitize_bot_output("\n\n".join(sections)),
                applied_tags=tuple(tags),
                components=build_resolution_buttons(),
                allowed_mentions=AllowedMentions(users=(post.author_id,)),
                triage_digest=None,
                ping_maintainer=False,
                secret_warning=has_secret,
                rate_limited=False,
                should_reply=True,
                refrained_status=refrained_status,
                detected_intent="ABSTAIN_NEEDS_INFO",
                worth_a_look_reasons=(),
            )

        cfg_hits, doc_hits = combinatorial_retrieve(
            self.conn,
            normalized_user_text,
            cfg_limit=7,
            doc_limit=5,
            project=project,
        )
        if yaml_overrides:
            seen_ov_pairs = {(c["file_path"], c["key_path"]) for c in cfg_hits}
            ov_hits: list[dict[str, str]] = []
            for ov in yaml_overrides:
                for rm in search_config_keys(self.conn, ov["key_path"].replace("defaults.", ""), limit=2, project=project):
                    rm_copy = dict(rm)
                    rm_copy["confidence"] = "high"
                    pair = (rm_copy["file_path"], rm_copy["key_path"])
                    if pair not in seen_ov_pairs:
                        seen_ov_pairs.add(pair)
                        ov_hits.append(rm_copy)
            cfg_hits = ov_hits + cfg_hits

        has_high_conf_doc_section = any(h.get("confidence") == "high" for h in doc_hits[:3])
        has_high_conf_doc = any(h.get("confidence") == "high" for h in cfg_hits[:3]) or has_high_conf_doc_section
        code_hits, cfg_hits = self._retrieve_second_class_code_hits(
            raw_and_logs=f"{combined_user_text}\n{fetched_logs}",
            normalized_text=normalized_user_text,
            cfg_hits=cfg_hits,
            has_high_conf_doc=has_high_conf_doc_section,
            project=project,
        )
        has_high_conf_doc = any(h.get("confidence") == "high" for h in cfg_hits[:3]) or has_high_conf_doc_section
        signals = extract_grounded_query_signals(
            conn=self.conn,
            user_question=normalized_user_text,
            cfg_hits=cfg_hits,
            doc_hits=doc_hits,
            project=project,
            code_hits=code_hits,
            bit_matches=bit_matches,
        )
        self.thread_last_facets[post.thread_id] = (
            self._extract_corpus_facets(normalized_user_text, project=project)
            | frozenset(signals.get("corpus_terms") or ())
        )
        intent_profile = signals.get("intent_profile") or {}
        detected_intent = str(intent_profile.get("detected_intent") or "CONFIG_HOWTO")
        has_any_doc = bool(cfg_hits or doc_hits)
        has_medium_only_doc = has_any_doc and not has_high_conf_doc
        missing_diag = contextual_missing_diagnostics(
            combined_text=combined_user_text,
            diag=diag,
            cfg_hits=cfg_hits,
            doc_hits=doc_hits,
            code_hits=code_hits,
            is_startup_crash=is_startup_crash,
            project=project,
            manifest=self.manifest,
        )
        all_diag_present = len(missing_diag) == 0

        # Avoid dumping weak/medium doc hits on pure bug reports unless a high-confidence setting matched
        if is_bug and has_medium_only_doc and not HOWTO_KEYWORDS.search(normalized_user_text) and "?" not in normalized_user_text and "？" not in normalized_user_text:
            cfg_hits = []
            doc_hits = []
            has_any_doc = False
            has_medium_only_doc = False

        model_refrained_reason: str | None = None
        model_clarifying_bullets: str | None = None
        if cfg_hits or doc_hits or code_hits:
            llm_reply: str | None = None
            if self.llm_synthesizer is not None and (not should_abstain or verified_user or has_diag_evidence):
                llm_reply = self.llm_synthesizer.synthesize(
                    conn=self.conn,
                    user_question=normalized_user_text,
                    cfg_hits=cfg_hits,
                    doc_hits=doc_hits,
                    bit_matches=bit_matches,
                    project=project,
                    code_hits=code_hits,
                )
                model_refrained_reason = self.llm_synthesizer.last_refrain_reason
                model_clarifying_bullets = self.llm_synthesizer.last_clarifying_questions
            if llm_reply:
                sections.append(llm_reply)
            elif model_refrained_reason is not None:
                self.model_refrained_threads.add(post.thread_id)
                if model_clarifying_bullets:
                    sections.append(model_clarifying_bullets)
            elif cfg_hits or doc_hits:
                if detected_intent == "REFERENCE_LOOKUP" and has_high_conf_doc:
                    header = "Here are the documentation and reference sections for that topic:"
                elif detected_intent == "API_INTEGRATION" and has_high_conf_doc:
                    header = "Here is the developer API and addon integration reference:"
                elif detected_intent == "ARCHITECTURE_EXPLANATION" and has_high_conf_doc:
                    header = "Here is the architectural overview and governing settings:"
                elif has_high_conf_doc and not is_bug:
                    header = "Here is what I found in the configuration schema and operator docs:"
                elif has_high_conf_doc and is_bug:
                    header = "Here is the setting and documentation section that governs this behavior:"
                else:
                    header = (
                        "I am not 100% sure the standard docs cover your exact case, "
                        "but here is the closest match to check first:"
                    )
                ans_lines = [header]
                is_op_notme_query = bool(
                    re.search(r"\b(?:notme|invalid\s+command\s+argument\s+player|stopping\s+operators)\b", normalized_user_text.lower())
                    or ("player:" in normalized_user_text.lower() and re.search(r"\b(?:op|operators?)\b", normalized_user_text.lower()))
                )
                show_cfg = not (
                    (
                        ("builder_api" in interaction["categories"] or detected_intent == "API_INTEGRATION")
                        and not any(ext in normalized_user_text.lower() for ext in (".yml", "shape", "vert", "radius", "config"))
                    )
                    or (
                        detected_intent == "REFERENCE_LOOKUP"
                        and not any(ext in normalized_user_text.lower() for ext in (".yml", "config"))
                    )
                    or (
                        is_op_notme_query
                        and not any(ext in normalized_user_text.lower() for ext in (".yml", "config", "integrations"))
                    )
                )
                if show_cfg:
                    for ch in select_diverse_config_hits(cfg_hits, max_hits=3, max_per_file=3):
                        ans_lines.append(_format_config_hit(ch, project=project))
                doc_max = 3 if detected_intent == "REFERENCE_LOOKUP" else 2
                for dh in select_diverse_doc_hits(doc_hits, max_hits=doc_max, max_per_doc=1):
                    ans_lines.append(_format_doc_hit(dh))
                sections.append("\n".join(ans_lines))

        # If the post has zero specific technical tokens, no doc/BIT match, and is not a bug or Pro thread, stay silent
        if not sections and not is_bug and not pro and not specific_tokens and not interaction["is_high_signal"]:
            return BotDecision(
                reply_text="",
                applied_tags=tuple(tags),
                components=[],
                allowed_mentions=AllowedMentions(),
                should_reply=False,
                detected_intent=detected_intent,
            )

        # Determine whether the bot is unsure if a doc solves the problem and should enter Investigative Mode
        is_mortality_only_howto = bool(
            has_high_conf_doc
            and HOWTO_KEYWORDS.search(normalized_user_text)
            and re.search(r"(?i)\b(?:die(?:s|d)?|dying|suffocat(?:e|es|ed|ing))\b", normalized_user_text)
            and not re.search(
                r"(?i)\b(?:bug(?:s)?|crash(?:es|ed|ing)?|exception(?:s)?|stack\s*trace|error(?:s)?|"
                r"lag(?:s|ging|ged|gy)?|tps\s+drops?|drops?\s+tps|spike(?:s|d)?|stutter(?:s|ing)?|"
                r"freeze(?:s|d|ing)?|stall(?:s|ed|ing)?|hang(?:s|ing)?|broken|doesn'?t\s+work|not\s+working|"
                r"watchdog|leak(?:s|ing)?)\b",
                normalized_user_text,
            )
        )
        requires_bug_diagnostics = is_bug and not all_diag_present and not is_mortality_only_howto
        is_unsure = (
            model_refrained_reason is not None
            or has_medium_only_doc
            or requires_bug_diagnostics
            or (not has_any_doc and not bit_matches and bool(specific_tokens))
        )

        # Step 3: Two-Stage Maintainer Escalation Gate, Investigative Triage & 3-Pillar Routing
        worth_a_look_reasons: list[str] = (
            list(interaction["reasons"]) if (interaction["is_high_signal"] and not hostile) else []
        )
        should_escalate_now = False
        if hostile:
            should_escalate_now = False
        elif model_refrained_reason is not None and not pro:
            should_escalate_now = False
        elif pro:
            should_escalate_now = True
        elif all_diag_present and (is_bug or bit_matches):
            should_escalate_now = True
        elif bool(worth_a_look_reasons):
            should_escalate_now = True

        arch_hits_for_gap: list[dict[str, str]] = []
        if not has_high_conf_doc_section and specific_tokens:
            arch_hits_for_gap = search_arch_sections(self.conn, normalized_user_text, limit=3, project=project)

        doc_gap_digest = self._detect_and_format_doc_gap(
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
            specific_tokens=specific_tokens,
            corpus_terms=signals.get("corpus_terms") or specific_tokens,
            detected_intent=detected_intent,
            cfg_hits=cfg_hits,
            doc_hits=doc_hits,
            code_hits=code_hits,
            arch_hits=arch_hits_for_gap,
            has_high_conf_doc_section=has_high_conf_doc_section,
            has_medium_only_doc=has_medium_only_doc,
            model_refrained_reason=model_refrained_reason,
            is_startup_crash=is_startup_crash,
            is_hostile=hostile,
        )

        refrained_status: str | None = None
        if is_unsure and (not should_escalate_now or (pro and is_bug and not all_diag_present)):
            self.investigative_threads.add(post.thread_id)
            if TAG_NEEDS_INFO not in tags and TAG_SOLVED not in tags:
                tags.append(TAG_NEEDS_INFO)
            if not sections and not is_bug:
                sections.append(
                    "I do not have an exact match for that in the documentation index, "
                    "so rather than pointing you at an unrelated guide, let me gather the details for the maintainer."
                )
            sections.append(
                self._build_investigative_prompt(
                    combined_text=combined_user_text,
                    diag=diag,
                    missing_diag=missing_diag,
                    has_doc_or_cfg=bool((cfg_hits or doc_hits or bit_matches) and model_refrained_reason is None),
                    is_bug=is_bug,
                    is_startup_crash=is_startup_crash,
                    project=project,
                )
            )
            refrained_status = self._format_refrain_status(
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
                reason=model_refrained_reason or "investigative mode - gathering context/diagnostics before escalation",
                detected_intent=detected_intent,
                missing_diag=missing_diag,
                cumulative_terms=signals.get("corpus_terms") or specific_tokens,
            )
            if doc_gap_digest:
                refrained_status = f"{refrained_status}\n\n{doc_gap_digest}"
        else:
            if not sections and should_escalate_now:
                sections.append(
                    "I have queued this thread in `#bot-triage-queue` for priority maintainer review."
                )
            sections.append(
                "If this answered your question, react with ✅ (or reply `solved`) to close this thread. "
                "If you still need help or want to report a bug, react with ❓ (or reply with what happened)."
            )
            if doc_gap_digest and not should_escalate_now:
                refrained_status = doc_gap_digest

        triage_digest: str | None = None
        ping_maintainer = False
        if should_escalate_now and not hostile and post.thread_id not in self.escalated_threads:
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
                worth_a_look_reasons=worth_a_look_reasons or None,
                code_classes=[f"{c['class_name']}.java" for c in code_hits[:3]] if (is_bug or bit_matches or code_hits) and code_hits else None,
                cumulative_facets=signals.get("corpus_terms") or specific_tokens,
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
            should_reply=True,
            refrained_status=refrained_status,
            detected_intent=detected_intent,
            doc_gap_digest=doc_gap_digest,
            worth_a_look_reasons=tuple(worth_a_look_reasons),
        )

    def handle_followup_message(
        self,
        post: ForumPostContext,
        message_body: str,
        followup_attachments: tuple[str, ...] = (),
        bystander_text: str = "",
    ) -> BotDecision:
        """
        Handles a follow-up message in a thread waiting on `⏳ Needs Info / Logs`,
        or a follow-up where the user says the suggested doc did not solve their issue.
        Chains all author turns, checks indirect-channel convergence and zero-delta /
        refusal transitions, and escalates to `#bot-triage-queue` once contextual
        diagnostics are satisfied.
        """
        project = resolve_post_project(post.applied_tags, manifest=self.manifest)
        if not has_project_docs(self.conn, project):
            return BotDecision(
                reply_text="",
                applied_tags=post.applied_tags,
                components=[],
                allowed_mentions=AllowedMentions(),
                should_reply=False,
            )

        f_att_urls = list(
            dict.fromkeys(
                list(followup_attachments)
                + extract_image_attachment_urls((), inline_text=message_body)
            )
        )
        f_text_att_urls = extract_text_attachment_urls(followup_attachments, inline_text=message_body)
        f_att_log_text, f_att_yaml_text = self._extract_attachments_text(f_text_att_urls)
        followup_ocr = self._extract_attachments_ocr(f_att_urls, project=project)
        effective_message_body = unwrap_console_paste(message_body)
        if f_att_yaml_text:
            effective_message_body = f"{effective_message_body}\n{f_att_yaml_text}".strip()
        if followup_ocr:
            effective_message_body = f"{effective_message_body}\n{followup_ocr}".strip()
        bystander_joined = "\n".join(
            b for b in (list(post.bystander_messages) + ([bystander_text] if bystander_text else [])) if b and b.strip()
        )
        danger_warning = build_dangerous_advice_warning(f"{effective_message_body}\n{bystander_joined}")

        combined = f"{post.title}\n{post.body}\n{effective_message_body}"
        followup_mclogs = extract_mclogs_ids(effective_message_body, manifest=self.manifest)
        followup_logs = "\n".join(
            p for p in ([unwrap_console_paste(self.log_fetcher(pid)) for pid in followup_mclogs if pid] + ([f_att_log_text] if f_att_log_text else [])) if p
        )
        if followup_ocr and not followup_logs:
            followup_logs = followup_ocr
        has_secret_b, effective_message_body = detect_and_redact_secrets(effective_message_body)
        has_secret_l, followup_logs = detect_and_redact_secrets(followup_logs)
        has_secret = has_secret_b or has_secret_l
        bit_matches = extract_bit_codes(self.conn, f"{effective_message_body}\n{followup_logs}", project=project, manifest=self.manifest)
        f_yaml_overrides, diffed_followup_body = extract_non_default_yaml_overrides(
            self.conn, effective_message_body, project=project
        )
        normalized_initial = self._prepare_multilingual_text(f"{post.title}\n{post.body}", project=project)
        normalized_followup = self._prepare_multilingual_text(diffed_followup_body, project=project)
        if f_yaml_overrides:
            f_ov_keys = " ".join(ov["key_path"].replace("defaults.", "") for ov in f_yaml_overrides)
            normalized_followup = f"{normalized_followup}\n{f_ov_keys}".strip()
        prior_turns = list(self.thread_turns.get(post.thread_id) or [normalized_initial])
        needs_followup_investigation = bool(
            FOLLOWUP_INVESTIGATE_RE.search(normalized_followup)
            or "？" in normalized_followup
            or followup_mclogs
            or bool(followup_ocr)
            or bit_matches
            or bool(danger_warning)
        )

        # If the thread is NOT yet in `⏳ Needs Info / Logs` (e.g. bot answered a high-confidence how-to),
        # stay silent on polite acknowledgments ("oh I see, let me try that tonight"),
        # answer a genuine follow-up config question once, or transition into Investigative Mode if the user says it didn't work!
        if TAG_NEEDS_INFO not in post.applied_tags and post.thread_id not in self.investigative_threads:
            if not has_secret and not needs_followup_investigation:
                return BotDecision(
                    reply_text="",
                    applied_tags=post.applied_tags,
                    components=[],
                    allowed_mentions=AllowedMentions(),
                    should_reply=False,
                )
            if has_secret and not needs_followup_investigation:
                return BotDecision(
                    reply_text=sanitize_bot_output(
                        "**Security warning:** It looks like your post or log contains a password or "
                        "`network.yml` secret (`RTP_NET_SECRET` / Redis / SQL credential). Please edit "
                        "or delete the paste and rotate that secret right away."
                    ),
                    applied_tags=post.applied_tags,
                    components=build_resolution_buttons(),
                    allowed_mentions=AllowedMentions(users=(post.author_id,)),
                    secret_warning=True,
                    should_reply=True,
                )
            if danger_warning and not effective_message_body.strip():
                return BotDecision(
                    reply_text=sanitize_bot_output(danger_warning),
                    applied_tags=post.applied_tags,
                    components=build_resolution_buttons(),
                    allowed_mentions=AllowedMentions(users=(post.author_id,)),
                    should_reply=True,
                )

            if self._check_cooldown(post.author_id, post.timestamp):
                return BotDecision(
                    reply_text=sanitize_bot_output(
                        "I received your follow-up - please wait a few seconds before sending another request."
                    ),
                    applied_tags=post.applied_tags,
                    components=[],
                    allowed_mentions=AllowedMentions(users=(post.author_id,)),
                    rate_limited=True,
                )

            # Check if this is a genuine follow-up configuration question (rather than "didn't work" / bug report)
            is_followup_failure = bool(
                BUG_REPORT_KEYWORDS.search(normalized_followup)
                or re.search(
                    r"(?i)\b(?:didn'?t\s+work|does\s+not\s+work|not\s+working|"
                    r"still\s+(?:not|happening|broken|teleporting|opening|getting|same|does)|"
                    r"tried\s+that|already\s+(?:set|tried|did|have))\b",
                    normalized_followup,
                )
            )
            if (
                not is_followup_failure
                and not bit_matches
                and not followup_mclogs
                and ("?" in normalized_followup or "？" in normalized_followup or bool(HOWTO_KEYWORDS.search(normalized_followup)))
            ):
                f_cfg, f_docs = combinatorial_retrieve(
                    self.conn,
                    normalized_followup,
                    cfg_limit=7,
                    doc_limit=5,
                    project=project,
                )
                has_high_followup = any(h.get("confidence") == "high" for h in f_cfg[:3]) or any(
                    h.get("confidence") == "high" for h in f_docs[:3]
                )
                if not has_high_followup and post.thread_id not in self.followup_answered_threads:
                    f_cfg, f_docs = combinatorial_retrieve(
                        self.conn,
                        f"{normalized_initial}\n{normalized_followup}",
                        cfg_limit=7,
                        doc_limit=5,
                        project=project,
                    )
                    has_high_followup = any(h.get("confidence") == "high" for h in f_cfg[:3]) or any(
                        h.get("confidence") == "high" for h in f_docs[:3]
                    )
                if has_high_followup:
                    self.followup_answered_threads.add(post.thread_id)
                    self.thread_turns[post.thread_id] = prior_turns + [normalized_followup]
                    f_signals = extract_grounded_query_signals(
                        conn=self.conn,
                        user_question=normalized_followup,
                        cfg_hits=f_cfg,
                        doc_hits=f_docs,
                        project=project,
                        prior_turns=prior_turns,
                    )
                    f_intent = str((f_signals.get("intent_profile") or {}).get("detected_intent") or "CONFIG_HOWTO")
                    f_sections: list[str] = []
                    if has_secret:
                        f_sections.append(
                            "**Security warning:** It looks like your post or log contains a password or "
                            "`network.yml` secret (`RTP_NET_SECRET` / Redis / SQL credential). Please edit "
                            "or delete the paste and rotate that secret right away."
                        )
                    if danger_warning:
                        f_sections.append(danger_warning)
                    llm_followup: str | None = None
                    if self.llm_synthesizer is not None:
                        llm_followup = self.llm_synthesizer.synthesize(
                            conn=self.conn,
                            user_question=normalized_followup,
                            cfg_hits=f_cfg,
                            doc_hits=f_docs,
                            bit_matches=[],
                            project=project,
                            prior_turns=prior_turns,
                        )
                    if llm_followup:
                        f_sections.append(llm_followup)
                    elif self.llm_synthesizer is not None and self.llm_synthesizer.last_refrain_reason is not None:
                        self.investigative_threads.add(post.thread_id)
                    else:
                        if f_intent == "REFERENCE_LOOKUP":
                            f_lines = ["Here are the documentation and reference sections for that follow-up:"]
                        else:
                            f_lines = ["Here is what the configuration schema and docs say for that follow-up:"]
                        if f_intent != "REFERENCE_LOOKUP" or any(ext in normalized_followup.lower() for ext in (".yml", "config")):
                            for ch in select_diverse_config_hits(f_cfg, max_hits=3, max_per_file=2):
                                f_lines.append(_format_config_hit(ch, project=project))
                        for dh in select_diverse_doc_hits(f_docs, max_hits=(3 if f_intent == "REFERENCE_LOOKUP" else 2), max_per_doc=1):
                            f_lines.append(_format_doc_hit(dh))
                        f_sections.append("\n".join(f_lines))
                    if f_sections and post.thread_id not in self.investigative_threads:
                        f_sections.append(
                            "If this answered your question, react with ✅ (or reply `solved`) to close this thread. "
                            "If you still need help or want to report a bug, react with ❓ (or reply with what happened)."
                        )
                        f_tags = list(post.applied_tags)
                        if is_pro_supporter(post.author_roles) and TAG_PRO_PRIORITY not in f_tags:
                            f_tags.append(TAG_PRO_PRIORITY)
                        return BotDecision(
                            reply_text=sanitize_bot_output("\n\n".join(f_sections)),
                            applied_tags=tuple(f_tags),
                            components=build_resolution_buttons(),
                            allowed_mentions=AllowedMentions(users=(post.author_id,)),
                            secret_warning=has_secret,
                            should_reply=True,
                            detected_intent=f_intent,
                        )

            self.investigative_threads.add(post.thread_id)
        else:
            if self._check_cooldown(post.author_id, post.timestamp):
                return BotDecision(
                    reply_text=sanitize_bot_output(
                        "I received your follow-up - please wait a few seconds before sending another request."
                    ),
                    applied_tags=post.applied_tags,
                    components=[],
                    allowed_mentions=AllowedMentions(users=(post.author_id,)),
                    rate_limited=True,
                )

        prev_facets = self.thread_last_facets.get(
            post.thread_id,
            self._extract_corpus_facets(normalized_initial, project=project),
        )
        prev_diag = self.thread_last_diag.get(
            post.thread_id,
            frozenset(
                k
                for k, v in inspect_diagnostics(normalized_initial, "", project=project, manifest=self.manifest).items()
                if v
            ),
        )

        self.thread_turns[post.thread_id] = prior_turns + [normalized_followup]
        cumulative_turns_text = "\n".join(self.thread_turns[post.thread_id])
        combined = f"{post.title}\n{cumulative_turns_text}\n{effective_message_body}".strip()
        mclogs_ids = extract_mclogs_ids(combined, manifest=self.manifest)
        fetched_logs = "\n".join(
            p for p in ([unwrap_console_paste(self.log_fetcher(pid)) for pid in mclogs_ids if pid] + ([f_att_log_text] if f_att_log_text else [])) if p
        )
        if followup_ocr and not fetched_logs:
            fetched_logs = followup_ocr
        all_bit_matches = extract_bit_codes(self.conn, f"{combined}\n{fetched_logs}", project=project, manifest=self.manifest)

        is_startup_crash = bool(STARTUP_CRASH_KEYWORDS.search(combined))
        diag = inspect_diagnostics(combined, fetched_logs, project=project, manifest=self.manifest)
        cur_diag_set = frozenset(k for k, v in diag.items() if v)
        pro = is_pro_supporter(post.author_roles)
        hostile = is_hostile_post(f"{combined}\n{normalized_followup}", manifest=self.manifest)

        cum_cfg, cum_docs = combinatorial_retrieve(
            self.conn,
            cumulative_turns_text,
            cfg_limit=7,
            doc_limit=5,
            project=project,
        )
        has_high_cum_doc_section = any(h.get("confidence") == "high" for h in cum_docs[:3])
        has_high_cum = any(h.get("confidence") == "high" for h in cum_cfg[:3]) or has_high_cum_doc_section
        f_code_hits, cum_cfg = self._retrieve_second_class_code_hits(
            raw_and_logs=f"{combined}\n{fetched_logs}",
            normalized_text=cumulative_turns_text,
            cfg_hits=cum_cfg,
            has_high_conf_doc=has_high_cum,
            project=project,
        )
        has_high_cum = any(h.get("confidence") == "high" for h in cum_cfg[:3]) or has_high_cum_doc_section
        cum_signals = extract_grounded_query_signals(
            conn=self.conn,
            user_question=normalized_followup,
            cfg_hits=cum_cfg,
            doc_hits=cum_docs,
            project=project,
            code_hits=f_code_hits,
            bit_matches=all_bit_matches,
            prior_turns=prior_turns,
        )
        cum_intent = str((cum_signals.get("intent_profile") or {}).get("detected_intent") or "TROUBLESHOOTING_DIAGNOSIS")
        is_explicit_refusal = bool(DIAGNOSTIC_REFUSAL_RE.search(effective_message_body))
        followup_corpus_facets = self._extract_corpus_facets(normalized_followup, project=project)
        if is_explicit_refusal:
            new_facets = prev_facets
            has_new_facets = False
        else:
            new_facets = prev_facets | followup_corpus_facets
            has_new_facets = bool(followup_corpus_facets - prev_facets)
        has_new_diag = bool(
            not is_explicit_refusal
            and (
                (cur_diag_set - prev_diag)
                or followup_mclogs
                or bit_matches
                or CONCRETE_EVIDENCE_RE.search(effective_message_body)
            )
        )
        self.thread_last_facets[post.thread_id] = new_facets
        self.thread_last_diag[post.thread_id] = cur_diag_set
        if has_new_facets or has_new_diag:
            self.refusal_grace_threads.discard(post.thread_id)

        missing_diag = contextual_missing_diagnostics(
            combined_text=combined,
            diag=diag,
            cfg_hits=cum_cfg,
            doc_hits=cum_docs,
            code_hits=f_code_hits,
            is_startup_crash=is_startup_crash,
            project=project,
            manifest=self.manifest,
        )

        tags = [t for t in post.applied_tags if t != TAG_NEEDS_INFO]
        if pro and TAG_PRO_PRIORITY not in tags:
            tags.append(TAG_PRO_PRIORITY)

        sections: list[str] = []
        if has_secret:
            sections.append(
                "**Security warning:** It looks like your post or log contains a password or "
                "`network.yml` secret (`RTP_NET_SECRET` / Redis / SQL credential). Please edit "
                "or delete the paste and rotate that secret right away."
            )
        if danger_warning:
            sections.append(danger_warning)
        if bit_matches:
            bit_lines = ["I matched the following `[RTP bit]` finding(s) in your follow-up:"]
            for bm in bit_matches[:4]:
                bit_lines.append(
                    f"- **`{bm['key']}`** (`{bm['namespace']}-{int(bm['id']):04d}`, severity `{bm['severity']}`): "
                    f"{bm['remediation']}"
                )
            bit_lines.append("Full catalog: <https://dailystruggle.github.io/RTP/dev/BIT_CODES/>")
            sections.append("\n".join(bit_lines))

        # 1a. Meta-Clarification During `⏳ Needs Info / Logs`:
        # If the user asks *how* to run `/rtp info`, `/rtp test`, or upload `latest.log` to `mclo.gs`
        # while the thread is awaiting diagnostics for a runtime issue, answer the helper question
        # while keeping `TAG_NEEDS_INFO` and `self.investigative_threads` active.
        is_meta_diagnostic_question = bool(
            ( "?" in normalized_followup or "？" in normalized_followup or re.search(r"(?i)\b(?:how|what|where|which)\b", normalized_followup))
            and re.search(
                r"(?i)(?:/rtp\s+(?:info|test|version)\b|\b(?:permission|node)\b.*?\b(?:info|test)\b|\bmclo\.gs\b|\blatest\.log\b)",
                normalized_followup,
            )
            and not is_diag_bundle
            if (is_diag_bundle := bool((diag.get("rtp_version") and diag.get("server_version")) or followup_mclogs or all_bit_matches)) is not None
            else False
        )
        if is_meta_diagnostic_question and (post.thread_id in self.investigative_threads or TAG_NEEDS_INFO in post.applied_tags):
            tags.append(TAG_NEEDS_INFO)
            self.investigative_threads.add(post.thread_id)
            meta_cfg, meta_docs = combinatorial_retrieve(
                self.conn,
                normalized_followup,
                cfg_limit=3,
                doc_limit=2,
                project=project,
            )
            meta_lines = [
                "You can run `/rtp info` (permission `rtp.info`), `/rtp test` (permission `rtp.test`), and `/rtp version` "
                "directly from the server console or in-game as an operator (`op`)."
            ]
            for dh in select_diverse_doc_hits(meta_docs, max_hits=2, max_per_doc=1):
                meta_lines.append(_format_doc_hit(dh))
            meta_lines.append(
                "Once you have a chance to run that, paste the output here and I will finish narrowing down your original issue."
            )
            sections.append("\n".join(meta_lines))
            return BotDecision(
                reply_text=sanitize_bot_output("\n\n".join(sections)),
                applied_tags=tuple(tags),
                components=build_resolution_buttons(),
                allowed_mentions=AllowedMentions(users=(post.author_id,)),
                triage_digest=None,
                ping_maintainer=False,
                secret_warning=has_secret,
                should_reply=True,
                detected_intent="REFERENCE_LOOKUP",
            )

        # 1b. Indirect-Channel Convergence Check:
        # If the follow-up adds contextual facets that now produce a `high`-confidence
        # `cfg_hits` or `doc_hits` match (and is not a diagnostic escalation bundle or
        # "tried that and still broken" failure report), synthesize and answer directly
        # in-thread, remove `TAG_NEEDS_INFO`, and do not nag for `mclo.gs` or `/version`.
        is_tried_and_failed = bool(
            re.search(
                r"(?i)\b(?:didn'?t\s+work|does\s+not\s+work|still\s+(?:not|happening|broken|teleporting|opening|getting|same|does)|"
                r"tried\s+that|already\s+(?:set|tried|did|have))\b",
                normalized_followup,
            )
        )
        is_diag_bundle = bool((diag.get("rtp_version") and diag.get("server_version")) or followup_mclogs or all_bit_matches)
        added_facets = followup_corpus_facets - prev_facets
        top_hit_blobs = " ".join(
            f"{h.get('key_path', '')} {h.get('file_path', '')} {h.get('description', '')} {h.get('matched_facets', '')}"
            for h in (cum_cfg[:3] + cum_docs[:3])
            if h.get("confidence") == "high"
        ).lower()
        new_facets_match_high_hit = bool(
            added_facets
            and any(
                af in top_hit_blobs or porter_stem_word(af) in top_hit_blobs
                for af in added_facets
            )
        )
        initial_abstained = should_abstain_for_missing_info(
            normalized_text=normalized_initial,
            raw_text=f"{post.title}\n{post.body}",
            fetched_logs="",
            diag={},
            bit_matches=[],
            conn=self.conn,
            project=project,
        )
        cum_still_abstains = should_abstain_for_missing_info(
            normalized_text=cumulative_turns_text,
            raw_text=combined,
            fetched_logs=followup_logs,
            diag=diag,
            bit_matches=all_bit_matches,
            conn=self.conn,
            project=project,
        )
        convergence_passes_abstention = (
            not initial_abstained
            or not cum_still_abstains
            or new_facets_match_high_hit
            or bool(HOWTO_KEYWORDS.search(normalized_followup))
            or "?" in normalized_followup
            or "？" in normalized_followup
        )
        if (
            not is_explicit_refusal
            and (has_new_facets or post.thread_id in self.model_refrained_threads)
            and convergence_passes_abstention
            and (
                has_high_cum
                or (
                    post.thread_id in self.model_refrained_threads
                    and self.llm_synthesizer is not None
                    and bool(cum_cfg or cum_docs)
                )
            )
            and not is_tried_and_failed
            and not is_diag_bundle
            and not is_startup_crash
            and not CODE_OR_ERROR_SIGNAL_RE.search(effective_message_body)
        ):
            llm_cum: str | None = None
            if self.llm_synthesizer is not None:
                llm_cum = self.llm_synthesizer.synthesize(
                    conn=self.conn,
                    user_question=normalized_followup,
                    cfg_hits=cum_cfg,
                    doc_hits=cum_docs,
                    bit_matches=all_bit_matches,
                    project=project,
                    code_hits=f_code_hits,
                    prior_turns=prior_turns,
                )
            if llm_cum:
                sections.append(llm_cum)
            else:
                conv_lines = [
                    "Based on that additional context, here is what governs this in the configuration schema and operator docs:"
                ]
                for ch in select_diverse_config_hits(cum_cfg, max_hits=3, max_per_file=2):
                    conv_lines.append(_format_config_hit(ch, project=project))
                for dh in select_diverse_doc_hits(cum_docs, max_hits=2, max_per_doc=1):
                    conv_lines.append(_format_doc_hit(dh))
                sections.append("\n".join(conv_lines))

            self.model_refrained_threads.discard(post.thread_id)
            self.investigative_threads.discard(post.thread_id)
            self.refusal_grace_threads.discard(post.thread_id)
            sections.append(
                "If this answered your question, react with ✅ (or reply `solved`) to close this thread. "
                "If you still need help or want to report a bug, react with ❓ (or reply with what happened)."
            )
            return BotDecision(
                reply_text=sanitize_bot_output("\n\n".join(sections)),
                applied_tags=tuple(tags),
                components=build_resolution_buttons(),
                allowed_mentions=AllowedMentions(users=(post.author_id,)),
                triage_digest=None,
                ping_maintainer=False,
                secret_warning=has_secret,
                should_reply=True,
                detected_intent=cum_intent,
            )

        ready_to_escalate = (pro and not hostile) or self._is_investigation_complete(
            combined_text=combined,
            diag=diag,
            missing_diag=missing_diag,
            is_startup_crash=is_startup_crash,
            is_investigative=(post.thread_id in self.investigative_threads),
            followup_text=effective_message_body,
        )
        if hostile:
            ready_to_escalate = False

        # 1c. Zero-Delta / Obstinate Refusal State Transition (No Nag Loops):
        is_explicit_refusal = bool(DIAGNOSTIC_REFUSAL_RE.search(effective_message_body))
        is_zero_delta = (not has_new_facets) and (not has_new_diag)
        if not ready_to_escalate and (is_explicit_refusal or is_zero_delta):
            tags.append(TAG_NEEDS_INFO)
            if post.thread_id in self.refusal_grace_threads and is_zero_delta:
                if danger_warning:
                    return BotDecision(
                        reply_text=sanitize_bot_output(danger_warning),
                        applied_tags=tuple(tags),
                        components=build_resolution_buttons(),
                        allowed_mentions=AllowedMentions(users=(post.author_id,)),
                        triage_digest=None,
                        ping_maintainer=False,
                        secret_warning=has_secret,
                        should_reply=True,
                        detected_intent=cum_intent,
                    )
                return BotDecision(
                    reply_text="",
                    applied_tags=tuple(tags),
                    components=[],
                    allowed_mentions=AllowedMentions(),
                    triage_digest=None,
                    ping_maintainer=False,
                    secret_warning=has_secret,
                    should_reply=False,
                    detected_intent=cum_intent,
                )

            self.refusal_grace_threads.add(post.thread_id)
            grace_lines = [
                "No problem - if you are away from the console or prefer not to upload full logs, "
                "here are the closest self-serve references to check at your own pace:"
            ]
            high_grace_cfg = [
                ch for ch in cum_cfg
                if ch.get("confidence") == "high"
                and not str(ch.get("file_path", "")).startswith(("advanced/messages/", "messages/", "addons/integrations.yml"))
            ]
            high_grace_docs = [dh for dh in cum_docs if dh.get("confidence") == "high"]
            if high_grace_cfg or high_grace_docs:
                for ch in select_diverse_config_hits(high_grace_cfg, max_hits=2, max_per_file=1):
                    grace_lines.append(_format_config_hit(ch, project=project))
                for dh in select_diverse_doc_hits(high_grace_docs, max_hits=2, max_per_doc=1):
                    grace_lines.append(_format_doc_hit(dh))
            else:
                init_low = normalized_initial.lower()
                if any(w in init_low for w in ("lag", "tps", "mspt", "freeze", "slow", "stutter", "stall", "hang", "unresponsive")):
                    grace_lines.append(
                        "- **Check player permissions first (`what-not-to-do.md`):** Ensure your default player group only has `rtp.use` "
                        "(not `rtp.unqueued`, `rtp.params`, or `rtp.*`), which otherwise bypasses the background location cache - "
                        "<https://dailystruggle.github.io/RTP/site/what-not-to-do/>"
                    )
                    grace_lines.append(
                        "- **Check `advanced/performance.yml`:** Keep `syncLoading: false` and `viewDistanceSelect: 0`, or lower `maxMSPT` "
                        "toward `35.0`-`40.0` ms and `syncAllottedTime` toward `2`-`3` ms so background work pauses earlier - "
                        "<https://dailystruggle.github.io/RTP/admin/configuration/PERFORMANCE/#server-health-timing>"
                    )
                elif any(w in init_low for w in ("die", "dying", "suffocat", "roof", "cave", "midair", "high up", "water", "ocean")):
                    grace_lines.append(
                        "- **Check vertical & sky-light bounds (`definitions/regions/default.yml`):** Set `vert.maxY: 120` in the Nether "
                        "to stay below the `Y=128` bedrock roof, and keep `vert.requireSkyLight: true` in the Overworld to prevent underground caves - "
                        "<https://dailystruggle.github.io/RTP/admin/configuration/REGIONS/>"
                    )
                    grace_lines.append(
                        "- **Check arrival protection (`safety.yml`):** Review `invulnerabilityTime` (default `5`s) and `platformRadius` "
                        "in `plugins/RTP/safety.yml` - <https://dailystruggle.github.io/RTP/admin/configuration/SAFETY/#landing-protection>"
                    )
                else:
                    grace_lines.append(
                        "- **Run the built-in self-test (`/rtp test`):** Run `/rtp test` and `/rtp info` in-game to highlight unmapped worlds, "
                        "depleted queues, or `[RTP bit]` warnings without uploading logs - "
                        "<https://dailystruggle.github.io/RTP/admin/configuration/BIT/#reading-rtp-test-output>"
                    )
                    grace_lines.append(
                        "- **Operator Troubleshooting Runbook & What Not To Do:** "
                        "<https://dailystruggle.github.io/RTP/admin/RUNBOOK/> and <https://dailystruggle.github.io/RTP/site/what-not-to-do/>"
                    )
            grace_lines.append(
                "This thread will wait quietly - if you decide to share `/rtp test` output or a redacted snippet later, "
                "just post it here and I will pick right back up."
            )
            sections.append("\n".join(grace_lines))
            refrained_status = self._format_refrain_status(
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
                reason="author declined or deferred diagnostics; posted self-serve reference and waiting quietly",
                detected_intent=cum_intent,
                missing_diag=missing_diag,
                cumulative_terms=cum_signals.get("corpus_terms"),
            )
            return BotDecision(
                reply_text=sanitize_bot_output("\n\n".join(sections)),
                applied_tags=tuple(tags),
                components=build_resolution_buttons(),
                allowed_mentions=AllowedMentions(users=(post.author_id,)),
                triage_digest=None,
                ping_maintainer=False,
                secret_warning=has_secret,
                should_reply=True,
                refrained_status=refrained_status,
                detected_intent=cum_intent,
            )

        if not ready_to_escalate:
            tags.append(TAG_NEEDS_INFO)
            # Check if the follow-up introduced a new specific config key that wasn't in the initial post
            followup_cfg = search_config_keys(self.conn, normalized_followup, limit=1, project=project)
            if followup_cfg and followup_cfg[0].get("confidence") == "high":
                ch = followup_cfg[0]
                if ch["key_path"].lower() not in post.body.lower():
                    sections.append(
                        "One related setting to check based on your reply:\n"
                        + _format_config_hit(ch, project=project)
                    )

            present = present_diagnostic_items(diag, project=project)
            ack = f"Thanks - I recorded your {', '.join(present)}. " if present else "Thanks for the follow-up. "
            if missing_diag:
                missing_bullets = "\n".join(f"- {item}" for item in missing_diag)
                sections.append(
                    f"{ack}To finish narrowing this down before I ping the maintainer, please also share:\n"
                    f"{missing_bullets}"
                )
            else:
                sections.append(
                    f"{ack}I have recorded your diagnostic details for this thread."
                )
            refrained_status = self._format_refrain_status(
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
                reason="follow-up received; still awaiting remaining diagnostics before maintainer escalation",
                detected_intent=cum_intent,
                missing_diag=missing_diag,
                cumulative_terms=cum_signals.get("corpus_terms"),
            )
            return BotDecision(
                reply_text=sanitize_bot_output("\n\n".join(sections)),
                applied_tags=tuple(tags),
                components=build_resolution_buttons(),
                allowed_mentions=AllowedMentions(users=(post.author_id,)),
                triage_digest=None,
                ping_maintainer=False,
                secret_warning=has_secret,
                should_reply=True,
                refrained_status=refrained_status,
                detected_intent=cum_intent,
            )

        triage_digest: str | None = None
        ping_maintainer = False
        if post.thread_id not in self.escalated_threads and not hostile:
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
                bit_keys=[str(b["key"]) for b in all_bit_matches],
                mclogs_ids=mclogs_ids,
                investigative_note=effective_message_body,
                code_classes=[f"{c['class_name']}.java" for c in f_code_hits[:3]] if f_code_hits else None,
                cumulative_facets=cum_signals.get("corpus_terms"),
            )

        sections.append(
            "Thanks - I have enough context and diagnostics now. "
            "I have queued this thread in `#bot-triage-queue` and notified the maintainer to step in."
        )
        return BotDecision(
            reply_text=sanitize_bot_output("\n\n".join(sections)),
            applied_tags=tuple(tags),
            components=build_resolution_buttons(),
            allowed_mentions=AllowedMentions(users=(post.author_id,)),
            triage_digest=triage_digest,
            ping_maintainer=ping_maintainer,
            secret_warning=has_secret,
            should_reply=True,
            detected_intent=cum_intent,
        )

    def handle_button_interaction(
        self,
        post: ForumPostContext,
        custom_id: str,
        actor_id: str,
    ) -> BotDecision:
        """Handles `[✅ Solved]` and `[❓ Still need help]` button clicks / reactions."""
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
        project = resolve_post_project(post.applied_tags, manifest=self.manifest)
        turns = self.thread_turns.get(post.thread_id) or []
        combined = f"{post.title}\n{post.body}\n" + "\n".join(turns)
        mclogs_ids = extract_mclogs_ids(combined, manifest=self.manifest)
        fetched_logs = "\n".join(self.log_fetcher(pid) for pid in mclogs_ids if pid)
        is_startup_crash = bool(STARTUP_CRASH_KEYWORDS.search(combined))
        hostile = is_hostile_post(combined, manifest=self.manifest)
        diag = inspect_diagnostics(combined, fetched_logs, project=project, manifest=self.manifest)
        missing_diag = contextual_missing_diagnostics(
            combined_text=combined,
            diag=diag,
            is_startup_crash=is_startup_crash,
            project=project,
            manifest=self.manifest,
        )
        ready = (pro and not hostile) or self._is_investigation_complete(
            combined_text=combined,
            diag=diag,
            missing_diag=missing_diag,
            is_startup_crash=is_startup_crash,
            is_investigative=(post.thread_id in self.investigative_threads),
            followup_text="",
        )
        if hostile:
            ready = False

        if not ready:
            self.investigative_threads.add(post.thread_id)
            if TAG_NEEDS_INFO not in tags:
                tags.append(TAG_NEEDS_INFO)
            prompt = self._build_investigative_prompt(
                combined_text=combined,
                diag=diag,
                missing_diag=missing_diag,
                has_doc_or_cfg=False,
                is_bug=True,
                is_startup_crash=is_startup_crash,
                project=project,
            )
            refrained_status = self._format_refrain_status(
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
                reason="author clicked Still need help without required diagnostics",
                detected_intent="ABSTAIN_NEEDS_INFO",
                missing_diag=missing_diag,
            )
            return BotDecision(
                reply_text=sanitize_bot_output(f"No problem - {prompt}"),
                applied_tags=tuple(tags),
                components=build_resolution_buttons(),
                allowed_mentions=AllowedMentions(users=(post.author_id,)),
                triage_digest=None,
                ping_maintainer=False,
                refrained_status=refrained_status,
                detected_intent="ABSTAIN_NEEDS_INFO",
            )

        tags = [t for t in tags if t != TAG_NEEDS_INFO]
        bit_matches = extract_bit_codes(self.conn, f"{combined}\n{fetched_logs}")
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
                    body=post.body,
                    author_roles=post.author_roles,
                    applied_tags=tuple(tags),
                    timestamp=post.timestamp,
                ),
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


SOLVED_TEXT_RE = re.compile(
    r"(?is)^\s*(?:!?\s*solved|!?\s*fixed|issue\s+(?:is\s+)?(?:solved|fixed)|problem\s+(?:is\s+)?(?:solved|fixed)|all\s+fixed|that\s+fixed\s+it|it'?s\s+(?:solved|fixed)|fixed\s+now|solved\s+now)"
    r"(?:[,!.\s]+(?:thanks(?:\s+a\s+lot|\s+so\s+much)?|thank\s+you(?:\s+so\s+much)?|ty|thx|appreciate\s+it|cheers))?[.!?\s]*$"
)
NEED_HELP_TEXT_RE = re.compile(
    r"(?im)^\s*(?:!?\s*need\s+help|!?\s*still\s+need\s+help|!?\s*escalate)\b"
)
PROGRESS_PLACEHOLDER_PREFIX = "⏳ Scanning documentation"


def parse_discord_timestamp(iso_str: str | None, fallback: float = 0.0) -> float:
    """Parses a Discord ISO-8601 timestamp into Unix epoch seconds."""
    if not iso_str:
        return fallback
    try:
        cleaned = iso_str.strip()
        if cleaned.endswith("Z"):
            cleaned = cleaned[:-1] + "+00:00"
        return datetime.fromisoformat(cleaned).timestamp()
    except ValueError:
        return fallback


class DiscordForumPoller:
    """
    Sequential, zero-dependency REST polling runner for `#support` (`GUILD_FORUM`).

    Why sequential polling:
      - Coalesces rapid back-to-back messages from a thread author (via
        `settle_seconds` and message batching) into a single evaluation pass,
        avoiding race conditions when users post a title and then paste logs
        or follow-up lines seconds later.
      - Inspects actual thread message history (`author.id == bot_user_id`) plus
        `bot-state.json` so restarts never double-reply or double-ping.
      - Seeds `✅` and `❓` reactions on bot replies so thread authors can mark
        a post solved or request escalation without a WebSocket Gateway.
    """

    def __init__(
        self,
        client: DiscordRestClient,
        engine: SupportBotEngine,
        guild_id: str,
        state_path: pathlib.Path = DEFAULT_STATE_PATH,
        settle_seconds: float = 5.0,
        include_buttons: bool = False,
        now_fn: Callable[[], float] = time.time,
    ) -> None:
        self.client = client
        self.engine = engine
        self.guild_id = str(guild_id)
        self.state_path = state_path
        self.settle_seconds = settle_seconds
        self.include_buttons = include_buttons
        self.now_fn = now_fn

        self.bot_user_id: str = ""
        self.support_channel_id: str = ""
        self.triage_channel_id: str = ""
        self.tag_name_to_id: dict[str, str] = {}
        self.tag_id_to_name: dict[str, str] = {}
        self.role_id_to_name: dict[str, str] = {}
        self.member_roles_cache: dict[str, tuple[str, ...]] = {}
        self.handled_reactions: set[str] = set()
        self.reference_thread_titles: set[str] = self._load_reference_thread_titles()

        self._load_state()

    @staticmethod
    def _load_reference_thread_titles() -> set[str]:
        spec_path = BOT_DIR / "embeds.json"
        if not spec_path.is_file():
            return set()
        try:
            spec_data = json.loads(spec_path.read_text(encoding="utf-8"))
            return {
                str(item.get("title", "")).strip()
                for item in spec_data.get("pinned_forum_threads", [])
                if isinstance(item, dict) and str(item.get("title", "")).strip()
            }
        except (OSError, ValueError):
            return set()

    def _load_state(self) -> None:
        if not self.state_path.is_file():
            return
        try:
            data = json.loads(self.state_path.read_text(encoding="utf-8"))
            for tid in data.get("escalated_threads", []):
                self.engine.escalated_threads.add(str(tid))
            for tid in data.get("investigative_threads", []):
                self.engine.investigative_threads.add(str(tid))
            for tid in data.get("followup_answered_threads", []):
                self.engine.followup_answered_threads.add(str(tid))
            for tid in data.get("refusal_grace_threads", []):
                self.engine.refusal_grace_threads.add(str(tid))
            for tid in data.get("model_refrained_threads", []):
                self.engine.model_refrained_threads.add(str(tid))
            raw_turns = data.get("thread_turns", {})
            if isinstance(raw_turns, dict):
                for tid, turns in raw_turns.items():
                    if isinstance(turns, list):
                        self.engine.thread_turns[str(tid)] = [str(t) for t in turns]
            raw_facets = data.get("thread_last_facets", {})
            if isinstance(raw_facets, dict):
                for tid, f_list in raw_facets.items():
                    if isinstance(f_list, list):
                        self.engine.thread_last_facets[str(tid)] = frozenset(str(f) for f in f_list)
            raw_diag = data.get("thread_last_diag", {})
            if isinstance(raw_diag, dict):
                for tid, d_list in raw_diag.items():
                    if isinstance(d_list, list):
                        self.engine.thread_last_diag[str(tid)] = frozenset(str(d) for d in d_list)
            raw_counts = data.get("thread_bot_reply_counts", {})
            if isinstance(raw_counts, dict):
                for tid, cnt in raw_counts.items():
                    try:
                        self.engine.thread_bot_reply_counts[str(tid)] = int(cnt)
                    except (ValueError, TypeError):
                        pass
            for rk in data.get("handled_reactions", []):
                self.handled_reactions.add(str(rk))
        except (OSError, ValueError):
            pass

    def _save_state(self) -> None:
        try:
            self.state_path.parent.mkdir(parents=True, exist_ok=True)
            payload = {
                "escalated_threads": sorted(self.engine.escalated_threads),
                "investigative_threads": sorted(self.engine.investigative_threads),
                "followup_answered_threads": sorted(self.engine.followup_answered_threads),
                "refusal_grace_threads": sorted(self.engine.refusal_grace_threads),
                "model_refrained_threads": sorted(self.engine.model_refrained_threads),
                "thread_turns": {k: list(v) for k, v in sorted(self.engine.thread_turns.items())},
                "thread_last_facets": {k: sorted(v) for k, v in sorted(self.engine.thread_last_facets.items())},
                "thread_last_diag": {k: sorted(v) for k, v in sorted(self.engine.thread_last_diag.items())},
                "thread_bot_reply_counts": dict(sorted(self.engine.thread_bot_reply_counts.items())),
                "handled_reactions": sorted(self.handled_reactions),
            }
            self.state_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
        except OSError:
            pass

    def refresh_guild_metadata(self) -> None:
        """Discovers the bot's user ID, `#support` Forum tags, `#bot-triage-queue`, and guild roles."""
        if not self.bot_user_id:
            me = self.client.request("GET", "/users/@me") or {}
            self.bot_user_id = str(me.get("id", "bot"))

        channels = self.client.request("GET", f"/guilds/{self.guild_id}/channels") or []
        for ch in channels:
            ch_name = str(ch.get("name", "")).lower()
            ch_type = ch.get("type")
            if ch_type == CHANNEL_TYPES["GUILD_FORUM"] and ch_name == "support":
                self.support_channel_id = str(ch["id"])
                self.tag_name_to_id.clear()
                self.tag_id_to_name.clear()
                for tag in ch.get("available_tags", []):
                    t_id = str(tag.get("id", ""))
                    t_name = str(tag.get("name", "")).strip()
                    t_emoji = str(tag.get("emoji_name") or "").strip()
                    if t_id and t_name:
                        _, stripped_name = strip_leading_emoji_label(t_name)
                        canonical_name = (
                            f"{t_emoji} {stripped_name}".strip()
                            if t_emoji
                            else t_name
                        )
                        self.tag_name_to_id[t_name] = t_id
                        self.tag_name_to_id[stripped_name] = t_id
                        self.tag_name_to_id[canonical_name] = t_id
                        self.tag_id_to_name[t_id] = canonical_name
            elif ch_type == CHANNEL_TYPES["GUILD_TEXT"] and ch_name == "bot-triage-queue":
                self.triage_channel_id = str(ch["id"])

        roles = self.client.request("GET", f"/guilds/{self.guild_id}/roles") or []
        self.role_id_to_name = {
            str(r["id"]): str(r.get("name", ""))
            for r in roles
            if "id" in r
        }

    def _resolve_author_roles(self, user_id: str) -> tuple[str, ...]:
        if user_id in self.member_roles_cache:
            return self.member_roles_cache[user_id]
        try:
            member = self.client.request("GET", f"/guilds/{self.guild_id}/members/{user_id}") or {}
            role_ids = member.get("roles", [])
            role_names = tuple(
                self.role_id_to_name[str(rid)]
                for rid in role_ids
                if str(rid) in self.role_id_to_name
            )
            self.member_roles_cache[user_id] = role_names
            return role_names
        except OSError:
            return ()

    def _is_staff_replier(self, user_id: str) -> bool:
        """Returns True if `user_id` is the maintainer or holds a staff/moderator role."""
        if not user_id:
            return False
        if str(user_id) == str(self.engine.maintainer_id):
            return True
        roles = self._resolve_author_roles(str(user_id))
        return is_staff_or_maintainer(str(user_id), self.engine.maintainer_id, roles)

    def _tags_to_names(self, raw_tag_ids: list[str]) -> tuple[str, ...]:
        return tuple(self.tag_id_to_name.get(str(tid), str(tid)) for tid in raw_tag_ids)

    def _names_to_tag_ids(self, tag_names: tuple[str, ...]) -> list[str]:
        result: list[str] = []
        for name in tag_names:
            tid = self.tag_name_to_id.get(name, name)
            if tid not in result:
                result.append(tid)
        return result[:5]  # Discord allows up to 5 applied_tags per Forum thread

    def _find_reaction_actor(
        self,
        thread_id: str,
        message: dict[str, Any],
        emoji_char: str,
        allowed_user_ids: tuple[str, ...],
    ) -> str | None:
        reactions = message.get("reactions") or []
        matched = False
        for r in reactions:
            emoji = r.get("emoji") or {}
            if emoji.get("name") == emoji_char and int(r.get("count", 0)) > 0:
                matched = True
                break
        if not matched:
            return None
        msg_id = str(message["id"])
        encoded = urllib.parse.quote(emoji_char)
        allowed_set = {str(uid) for uid in allowed_user_ids if uid}
        try:
            users = self.client.request(
                "GET",
                f"/channels/{thread_id}/messages/{msg_id}/reactions/{encoded}",
            ) or []
            for u in users:
                if isinstance(u, dict) and str(u.get("id")) in allowed_set:
                    return str(u.get("id"))
            return None
        except OSError:
            return None

    def _has_author_reaction(self, thread_id: str, message: dict[str, Any], emoji_char: str, author_id: str) -> bool:
        return self._find_reaction_actor(thread_id, message, emoji_char, (author_id,)) is not None

    def _post_progress_message(self, thread_id: str) -> str | None:
        """
        Posts an immediate "working on it" status message in the thread before
        running a deep search (which can take up to 5 minutes across docs,
        ADRs, config schemas, and second-class Java code sections), returning
        the message ID so `_apply_decision` can edit it in place once ready.
        """
        progress_payload = {
            "content": (
                "⏳ Scanning documentation, configuration schema, and source code for your report - "
                "I will update this message with my findings shortly (deep search can take up to a few minutes)..."
            ),
            "allowed_mentions": {"parse": [], "users": [], "roles": [], "replied_user": False},
        }
        try:
            sent = self.client.request("POST", f"/channels/{thread_id}/messages", progress_payload)
            if isinstance(sent, dict) and "id" in sent:
                return str(sent["id"])
        except OSError:
            pass
        return None

    def _apply_decision(
        self,
        thread_id: str,
        current_tag_ids: list[str],
        decision: BotDecision,
        seed_reactions: bool = True,
        progress_message_id: str | None = None,
    ) -> dict[str, Any]:
        desired_tag_ids = self._names_to_tag_ids(decision.applied_tags)
        patch_payload: dict[str, Any] = {}
        if desired_tag_ids != [str(t) for t in current_tag_ids]:
            patch_payload["applied_tags"] = desired_tag_ids
        if decision.archived:
            patch_payload["archived"] = True
            patch_payload["locked"] = True

        msg_text = sanitize_bot_output(decision.reply_text.strip())
        if msg_text and decision.should_reply:
            if not self.include_buttons and decision.components:
                msg_text = sanitize_bot_output(
                    msg_text.replace(
                        "click **[✅ Solved]** below. If you still need help or want to report a bug, click **[❓ Still need help]**.",
                        "react with ✅ (or reply `solved`) to close this thread, or react with ❓ (or reply with the diagnostics) if you still need help.",
                    )
                )

            msg_payload: dict[str, Any] = {
                "content": msg_text,
                "allowed_mentions": decision.allowed_mentions.to_discord_payload(),
            }
            if self.include_buttons and decision.components:
                msg_payload["components"] = decision.components

            sent_msg: Any = None
            if progress_message_id:
                try:
                    sent_msg = self.client.request(
                        "PATCH",
                        f"/channels/{thread_id}/messages/{progress_message_id}",
                        msg_payload,
                    )
                except OSError:
                    sent_msg = None
            else:
                sent_msg = self.client.request("POST", f"/channels/{thread_id}/messages", msg_payload)

            if sent_msg is not None:
                self.engine.thread_bot_reply_counts[thread_id] = (
                    self.engine.thread_bot_reply_counts.get(thread_id, 0) + 1
                )
            elif progress_message_id and decision.allowed_mentions.users:
                for uid in decision.allowed_mentions.users:
                    self.engine.user_last_action.pop(uid, None)

            if (
                seed_reactions
                and not self.include_buttons
                and decision.components
                and isinstance(sent_msg, dict)
                and "id" in sent_msg
            ):
                msg_id = str(sent_msg["id"])
                for emoji_char in ("✅", "❓"):
                    encoded = urllib.parse.quote(emoji_char)
                    try:
                        self.client.request(
                            "PUT",
                            f"/channels/{thread_id}/messages/{msg_id}/reactions/{encoded}/@me",
                        )
                    except OSError:
                        pass
        elif progress_message_id:
            try:
                self.client.request("DELETE", f"/channels/{thread_id}/messages/{progress_message_id}")
            except OSError:
                pass

        if patch_payload:
            self.client.request("PATCH", f"/channels/{thread_id}", patch_payload)

        if decision.triage_digest and self.triage_channel_id:
            self.client.request(
                "POST",
                f"/channels/{self.triage_channel_id}/messages",
                {
                    "content": decision.triage_digest,
                    "allowed_mentions": {
                        "parse": [],
                        "users": [self.engine.maintainer_id] if decision.ping_maintainer else [],
                        "roles": [],
                        "replied_user": False,
                    },
                },
            )
        elif decision.refrained_status and self.triage_channel_id:
            self.client.request(
                "POST",
                f"/channels/{self.triage_channel_id}/messages",
                {
                    "content": decision.refrained_status,
                    "allowed_mentions": {
                        "parse": [],
                        "users": [],
                        "roles": [],
                        "replied_user": False,
                    },
                },
            )

        self._save_state()
        return {
            "thread_id": thread_id,
            "applied_tags": decision.applied_tags,
            "ping_maintainer": decision.ping_maintainer,
            "archived": decision.archived,
        }

    def poll_once(self) -> list[dict[str, Any]]:
        """
        Executes one two-phase burst-responsive poll pass over active `#support` Forum threads:
          - Phase 1 (Fast Deterministic Replies & Immediate Progress Placeholders):
            Iterates all settled threads, immediately executes fast deterministic
            actions (`✅`/`❓` reactions, `solved`/`need help` commands, Tier 1
            abstentions, and no-LLM FTS5 replies), and posts the `"⏳ Scanning..."`
            placeholder across EVERY thread that will enter deep LLM synthesis
            before any slow 60-300s LLM call runs.
          - Phase 2 (Deferred Deep LLM Synthesis Queue):
            Runs the slow `LocalLlmSynthesizer` (`deepseek-r1:32b`) synthesis pass
            for each queued deep-search thread and PATCH-edits its `"⏳ Scanning..."`
            placeholder in place once complete.
        Returns a list of action summaries for threads that received a response.
        """
        if not self.support_channel_id or not self.bot_user_id:
            self.refresh_guild_metadata()
        if not self.support_channel_id:
            return []

        active_resp = self.client.request("GET", f"/guilds/{self.guild_id}/threads/active") or {}
        raw_threads = (
            active_resp.get("threads", [])
            if isinstance(active_resp, dict)
            else (active_resp if isinstance(active_resp, list) else [])
        )

        support_threads = [
            th
            for th in raw_threads
            if str(th.get("parent_id", "")) == self.support_channel_id
            and not (int(th.get("flags", 0)) & CHANNEL_FLAG_PINNED)
            and not (th.get("thread_metadata") or {}).get("archived", False)
        ]
        support_threads.sort(key=lambda t: str(t.get("id", "")))

        actions: list[dict[str, Any]] = []
        deferred_deep_tasks: list[dict[str, Any]] = []
        now = self.now_fn()

        for th in support_threads:
            try:
                thread_id = str(th["id"])
                title = str(th.get("name", th.get("title", "Support Thread"))).strip()
                owner_id = str(th.get("owner_id", ""))
                raw_tag_ids = [str(t) for t in th.get("applied_tags", [])]
                tag_names = self._tags_to_names(raw_tag_ids)

                if TAG_SOLVED in tag_names or title in self.reference_thread_titles:
                    continue

                raw_messages = self.client.request("GET", f"/channels/{thread_id}/messages?limit=50") or []
                if not raw_messages:
                    continue

                # Sort messages chronologically (oldest first)
                messages = sorted(
                    raw_messages,
                    key=lambda m: (
                        parse_discord_timestamp(m.get("timestamp"), 0.0),
                        str(m.get("id", "")),
                    ),
                )

                if not owner_id:
                    owner_id = str((messages[0].get("author") or {}).get("id", "unknown"))
                author_name = str((messages[0].get("author") or {}).get("username", "user"))

                # Skip threads created by the maintainer, staff, or the bot itself (e.g. reference guides)
                if owner_id in (self.engine.maintainer_id, self.bot_user_id) or self._is_staff_replier(owner_id):
                    continue

                # Check settle window on the newest non-bot message so back-to-back posts coalesce
                non_bot_msgs = [
                    m for m in messages if str((m.get("author") or {}).get("id", "")) != self.bot_user_id
                ]
                if non_bot_msgs and self.settle_seconds > 0:
                    newest_ts = parse_discord_timestamp(non_bot_msgs[-1].get("timestamp"), now)
                    if (now - newest_ts) < self.settle_seconds:
                        continue

                placeholder_indices = [
                    idx
                    for idx, m in enumerate(messages)
                    if str((m.get("author") or {}).get("id", "")) == self.bot_user_id
                    and str(m.get("content", "")).strip().startswith(PROGRESS_PLACEHOLDER_PREFIX)
                ]
                bot_indices = [
                    idx
                    for idx, m in enumerate(messages)
                    if str((m.get("author") or {}).get("id", "")) == self.bot_user_id
                    and idx not in placeholder_indices
                ]
                existing_placeholder_id: str | None = None
                if placeholder_indices and (not bot_indices or placeholder_indices[-1] > bot_indices[-1]):
                    existing_placeholder_id = str(messages[placeholder_indices[-1]].get("id", "")) or None

                persisted_bot_replies = self.engine.thread_bot_reply_counts.get(thread_id, 0)
                effective_bot_replies = max(len(bot_indices), persisted_bot_replies)
                window_evicted_history = len(raw_messages) >= 50 and not any(
                    str(m.get("id", "")) in (thread_id, "1", "m_1", "1000") or idx == 0 and len(raw_messages) < 50
                    for idx, m in enumerate(messages)
                )
                if len(bot_indices) > persisted_bot_replies:
                    self.engine.thread_bot_reply_counts[thread_id] = len(bot_indices)

                author_roles = self._resolve_author_roles(owner_id)

                # Case 1: Bot has not replied in this thread yet -> coalesce all author messages
                if not bot_indices and effective_bot_replies == 0 and not window_evicted_history:
                    # Staff-Only Backoff: only stay silent if the maintainer or a staff member
                    # already replied before the bot polled (never let a random bystander silence the bot).
                    staff_repliers = [
                        m
                        for m in messages
                        if str((m.get("author") or {}).get("id", "")) not in ("", owner_id, self.bot_user_id)
                        and self._is_staff_replier(str((m.get("author") or {}).get("id", "")))
                    ]
                    if staff_repliers:
                        continue

                    bystander_msgs = [
                        str(m.get("content", "")).strip()
                        for m in messages
                        if str((m.get("author") or {}).get("id", "")) not in ("", owner_id, self.bot_user_id)
                        and str(m.get("content", "")).strip()
                    ]
                    author_msgs = [
                        m
                        for m in messages
                        if str((m.get("author") or {}).get("id", "")) == owner_id
                    ]
                    coalesced_body = "\n\n".join(
                        str(m.get("content", "")).strip()
                        for m in author_msgs
                        if str(m.get("content", "")).strip()
                    )
                    coalesced_attachments = tuple(
                        extract_image_attachment_urls(author_msgs, inline_text=coalesced_body)
                    )
                    post = ForumPostContext(
                        thread_id=thread_id,
                        author_id=owner_id,
                        author_name=author_name,
                        title=title,
                        body=coalesced_body,
                        author_roles=author_roles,
                        applied_tags=tag_names,
                        timestamp=now,
                        attachments=coalesced_attachments,
                        bystander_messages=tuple(bystander_msgs),
                    )
                    if self.engine.will_run_deep_search(post):
                        progress_msg_id = existing_placeholder_id or self._post_progress_message(thread_id)
                        deferred_deep_tasks.append(
                            {
                                "kind": "new",
                                "thread_id": thread_id,
                                "raw_tag_ids": raw_tag_ids,
                                "post": post,
                                "progress_msg_id": progress_msg_id,
                            }
                        )
                    else:
                        decision = self.engine.handle_new_thread(post)
                        if decision.rate_limited:
                            # Defer cooldown-limited thread to next poll cycle instead of stranding it with a dummy reply
                            continue
                        if decision.should_reply:
                            actions.append(
                                self._apply_decision(
                                    thread_id,
                                    raw_tag_ids,
                                    decision,
                                    progress_message_id=existing_placeholder_id,
                                )
                            )
                    continue

                # Case 2: Bot has already replied (or 50+ message window evicted earlier bot replies)
                if bot_indices:
                    last_bot_idx = bot_indices[-1]
                    last_bot_msg = messages[last_bot_idx]
                    last_bot_msg_id = str(last_bot_msg["id"])
                    prior_slice = messages[:last_bot_idx]
                    after_slice = messages[last_bot_idx + 1 :]
                else:
                    last_bot_idx = -1
                    last_bot_msg = {}
                    last_bot_msg_id = ""
                    prior_slice = []
                    after_slice = messages

                prior_author_msgs = [
                    m
                    for m in prior_slice
                    if str((m.get("author") or {}).get("id", "")) == owner_id
                ]
                prior_author_body = "\n\n".join(
                    str(m.get("content", "")).strip()
                    for m in prior_author_msgs
                    if str(m.get("content", "")).strip()
                )
                if not prior_author_body and thread_id in self.engine.thread_turns and self.engine.thread_turns[thread_id]:
                    prior_author_body = self.engine.thread_turns[thread_id][0]
                prior_attachments = tuple(
                    extract_image_attachment_urls(prior_author_msgs, inline_text=prior_author_body)
                )
                post = ForumPostContext(
                    thread_id=thread_id,
                    author_id=owner_id,
                    author_name=author_name,
                    title=title,
                    body=prior_author_body,
                    author_roles=author_roles,
                    applied_tags=tag_names,
                    timestamp=now,
                    attachments=prior_attachments,
                )

                if last_bot_msg_id:
                    # 2a. Check if the author or maintainer clicked ✅ reaction on the bot's reply
                    solved_rk = f"{thread_id}:{last_bot_msg_id}:solved"
                    solved_actor = (
                        self._find_reaction_actor(
                            thread_id,
                            last_bot_msg,
                            "✅",
                            (owner_id, self.engine.maintainer_id),
                        )
                        if solved_rk not in self.handled_reactions
                        else None
                    )
                    if solved_actor is not None:
                        self.handled_reactions.add(solved_rk)
                        decision = self.engine.handle_button_interaction(post, BUTTON_SOLVED_ID, actor_id=solved_actor)
                        actions.append(self._apply_decision(thread_id, raw_tag_ids, decision, seed_reactions=False))
                        continue

                    # 2b. Check if the author clicked ❓ reaction on the bot's reply
                    help_rk = f"{thread_id}:{last_bot_msg_id}:help"
                    if help_rk not in self.handled_reactions and self._has_author_reaction(
                        thread_id, last_bot_msg, "❓", owner_id
                    ):
                        self.handled_reactions.add(help_rk)
                        decision = self.engine.handle_button_interaction(post, BUTTON_NEED_HELP_ID, actor_id=owner_id)
                        actions.append(self._apply_decision(thread_id, raw_tag_ids, decision, seed_reactions=False))
                        continue

                # 2c. Coalesce all author messages posted after the bot's last reply
                # Staff-Only Backoff: only stay out of the conversation if the maintainer
                # or a staff member stepped in after the bot's last message.
                staff_replies_after_bot = [
                    m
                    for m in after_slice
                    if str((m.get("author") or {}).get("id", "")) not in ("", owner_id, self.bot_user_id)
                    and self._is_staff_replier(str((m.get("author") or {}).get("id", "")))
                ]
                if staff_replies_after_bot:
                    continue

                after_bystander_msgs = [
                    str(m.get("content", "")).strip()
                    for m in after_slice
                    if str((m.get("author") or {}).get("id", "")) not in ("", owner_id, self.bot_user_id)
                    and str(m.get("content", "")).strip()
                ]
                bystander_after_text = "\n\n".join(after_bystander_msgs)
                has_bystander_danger = bool(bystander_after_text and build_dangerous_advice_warning(bystander_after_text))

                after_author_msg_objs = [
                    m
                    for m in after_slice
                    if str((m.get("author") or {}).get("id", "")) == owner_id
                ]
                after_author_msgs = [
                    str(m.get("content", "")).strip()
                    for m in after_author_msg_objs
                    if str(m.get("content", "")).strip()
                ]
                followup_attachments = tuple(
                    extract_image_attachment_urls(
                        after_author_msg_objs,
                        inline_text="\n".join(after_author_msgs),
                    )
                )
                if not after_author_msgs and not followup_attachments and not has_bystander_danger:
                    continue

                coalesced_followup = "\n\n".join(after_author_msgs)
                latest_author_msg = after_author_msgs[-1].strip() if after_author_msgs else ""

                if (
                    latest_author_msg
                    and len(latest_author_msg) <= 80
                    and SOLVED_TEXT_RE.match(latest_author_msg)
                ):
                    decision = self.engine.handle_button_interaction(post, BUTTON_SOLVED_ID, actor_id=owner_id)
                    actions.append(self._apply_decision(thread_id, raw_tag_ids, decision, seed_reactions=False))
                    continue

                if coalesced_followup and NEED_HELP_TEXT_RE.search(coalesced_followup) and TAG_NEEDS_INFO not in tag_names:
                    decision = self.engine.handle_button_interaction(post, BUTTON_NEED_HELP_ID, actor_id=owner_id)
                    actions.append(
                        self._apply_decision(thread_id, raw_tag_ids, decision, seed_reactions=not decision.archived)
                    )
                    continue

                # Cap total automated bot replies per thread (`MAX_BOT_REPLIES_PER_THREAD = 3`);
                # after 3 replies (or 50+ message eviction), stop conversational replies and
                # only allow a one-time terminal escalation handoff if required diagnostics arrive.
                if effective_bot_replies >= MAX_BOT_REPLIES_PER_THREAD or window_evicted_history:
                    if (
                        thread_id not in self.engine.escalated_threads
                        and TAG_NEEDS_INFO in tag_names
                        and coalesced_followup
                    ):
                        decision = self.engine.handle_followup_message(
                            post,
                            coalesced_followup,
                            followup_attachments=followup_attachments,
                            bystander_text=bystander_after_text,
                        )
                        if decision.ping_maintainer and decision.should_reply:
                            actions.append(
                                self._apply_decision(
                                    thread_id,
                                    raw_tag_ids,
                                    decision,
                                    seed_reactions=not decision.archived,
                                    progress_message_id=existing_placeholder_id,
                                )
                            )
                    continue

                if TAG_NEEDS_INFO in tag_names or thread_id not in self.engine.escalated_threads or has_bystander_danger:
                    if coalesced_followup and self.engine.will_run_deep_search(
                        post,
                        followup_text=coalesced_followup,
                        followup_attachments=followup_attachments,
                    ):
                        progress_msg_id = existing_placeholder_id or self._post_progress_message(thread_id)
                        deferred_deep_tasks.append(
                            {
                                "kind": "followup",
                                "thread_id": thread_id,
                                "raw_tag_ids": raw_tag_ids,
                                "post": post,
                                "coalesced_followup": coalesced_followup,
                                "followup_attachments": followup_attachments,
                                "bystander_text": bystander_after_text,
                                "progress_msg_id": progress_msg_id,
                            }
                        )
                    else:
                        decision = self.engine.handle_followup_message(
                            post,
                            coalesced_followup,
                            followup_attachments=followup_attachments,
                            bystander_text=bystander_after_text,
                        )
                        if decision.rate_limited:
                            continue
                        if decision.should_reply:
                            actions.append(
                                self._apply_decision(
                                    thread_id,
                                    raw_tag_ids,
                                    decision,
                                    seed_reactions=not decision.archived,
                                    progress_message_id=existing_placeholder_id,
                                )
                            )
            except (OSError, sqlite3.Error):
                continue

        # Phase 2: Execute deferred slow LLM synthesis tasks after all fast replies
        # and "⏳ Scanning..." placeholders have already been posted to Discord.
        for task in deferred_deep_tasks:
            try:
                t_id = str(task["thread_id"])
                t_raw_tags: list[str] = list(task["raw_tag_ids"])
                t_post: ForumPostContext = task["post"]
                t_prog_id: str | None = task["progress_msg_id"]
                if task["kind"] == "new":
                    decision = self.engine.handle_new_thread(t_post)
                    if decision.rate_limited:
                        continue
                    if decision.should_reply or t_prog_id:
                        applied = self._apply_decision(
                            t_id,
                            t_raw_tags,
                            decision,
                            progress_message_id=t_prog_id,
                        )
                        if not t_prog_id or (t_id in self.engine.thread_bot_reply_counts):
                            actions.append(applied)
                else:
                    decision = self.engine.handle_followup_message(
                        t_post,
                        str(task["coalesced_followup"]),
                        followup_attachments=tuple(task["followup_attachments"]),
                        bystander_text=str(task["bystander_text"]),
                    )
                    if decision.rate_limited:
                        continue
                    if decision.should_reply or t_prog_id:
                        applied = self._apply_decision(
                            t_id,
                            t_raw_tags,
                            decision,
                            seed_reactions=not decision.archived,
                            progress_message_id=t_prog_id,
                        )
                        if not t_prog_id or (t_id in self.engine.thread_bot_reply_counts):
                            actions.append(applied)
            except (OSError, sqlite3.Error):
                continue

        return actions


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Safe Discord #support Forum triage bot.")
    parser.add_argument(
        "--db",
        type=pathlib.Path,
        default=DEFAULT_DB_PATH,
        help="Path to SQLite FTS5 doc-index.sqlite database.",
    )
    parser.add_argument(
        "--state",
        type=pathlib.Path,
        default=DEFAULT_STATE_PATH,
        help="Path to persistent bot-state.json file.",
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
    parser.add_argument(
        "--image",
        action="append",
        default=[],
        help="Image attachment path or cdn.discordapp.com URL to OCR when running with --query.",
    )
    parser.add_argument(
        "--once",
        action="store_true",
        help="Run a single sequential REST poll pass against the live guild and exit.",
    )
    parser.add_argument(
        "--listen",
        action="store_true",
        help="Continuously poll #support active threads at --interval seconds using stdlib REST.",
    )
    parser.add_argument(
        "--interval",
        type=float,
        default=15.0,
        help="Polling interval in seconds when running with --listen (default: 15.0).",
    )
    parser.add_argument(
        "--settle-seconds",
        type=float,
        default=5.0,
        help="Wait window in seconds after a user's newest message so back-to-back posts coalesce (default: 5.0).",
    )
    parser.add_argument(
        "--llm-endpoint",
        type=str,
        default=os.environ.get(
            "RTP_DISCORD_LLM_ENDPOINT",
            "http://127.0.0.1:11434/v1/chat/completions",
        ),
        help=(
            "Optional localhost/LAN OpenAI-compatible chat completions URL "
            "(default: http://127.0.0.1:11434/v1/chat/completions; pass 'off' to disable)."
        ),
    )
    parser.add_argument(
        "--llm-model",
        type=str,
        default=os.environ.get("RTP_DISCORD_LLM_MODEL", "deepseek-r1:32b"),
        help="Model identifier for --llm-endpoint (default: deepseek-r1:32b).",
    )
    parser.add_argument(
        "--llm-timeout",
        type=float,
        default=float(os.environ.get("RTP_DISCORD_LLM_TIMEOUT", "60.0")),
        help="Timeout in seconds for standard --llm-endpoint requests (default: 60.0).",
    )
    parser.add_argument(
        "--deep-timeout",
        type=float,
        default=float(os.environ.get("RTP_DISCORD_DEEP_TIMEOUT", str(DEEP_SEARCH_TIMEOUT_SECONDS))),
        help="Maximum timeout in seconds for deep searches across docs, ADRs, and source code (default: 300.0 / 5 minutes).",
    )
    parser.add_argument(
        "--translate",
        type=str,
        default=os.environ.get("RTP_DISCORD_TRANSLATE", "auto"),
        help=(
            "Online NMT autotranslation mode via Google Translate before FTS5 retrieval "
            "(default: 'auto'; pass 'off' to disable online NMT and use only .lang.yml locale_aliases)."
        ),
    )
    parser.add_argument(
        "--manifest",
        type=pathlib.Path,
        default=DEFAULT_MANIFEST_PATH,
        help="Path to declarative support-bot.yml project and security manifest.",
    )
    args = parser.parse_args(argv)

    maintainer_id = os.environ.get("DISCORD_MAINTAINER_ID", "999999999999999999")
    llm_synth: LocalLlmSynthesizer | None = None
    if args.llm_endpoint and args.llm_endpoint.strip().lower() not in ("off", "none", "false", "0"):
        llm_synth = LocalLlmSynthesizer(
            endpoint=args.llm_endpoint,
            model=args.llm_model,
            timeout=args.llm_timeout,
            deep_timeout=args.deep_timeout,
        )
    translator_fn: Callable[[str], tuple[str, str]] | None = None
    if args.translate and args.translate.strip().lower() not in ("off", "none", "false", "0"):
        translator_fn = autotranslate_to_english
    engine = SupportBotEngine(
        db_path=args.db,
        maintainer_id=maintainer_id,
        llm_synthesizer=llm_synth,
        translator=translator_fn,
        manifest=args.manifest,
    )
    try:
        if args.query or args.image:
            decision = engine.handle_new_thread(
                ForumPostContext(
                    thread_id="cli_preview",
                    author_id="100001",
                    author_name="operator",
                    title=args.title,
                    body=args.query,
                    applied_tags=("🌿 LeafRTP", "Paper / Spigot"),
                    attachments=tuple(args.image),
                )
            )
            print(decision.reply_text)
            return 0

        if args.once or args.listen:
            token = os.environ.get("DISCORD_BOT_TOKEN", "")
            guild_id = os.environ.get("DISCORD_GUILD_ID", "")
            if not token or not guild_id:
                print(
                    "Error: --once / --listen requires DISCORD_BOT_TOKEN and DISCORD_GUILD_ID environment variables.",
                    file=sys.stderr,
                )
                return 2

            client = DiscordRestClient(token)
            poller = DiscordForumPoller(
                client=client,
                engine=engine,
                guild_id=guild_id,
                state_path=args.state,
                settle_seconds=args.settle_seconds,
            )
            poller.refresh_guild_metadata()
            print(
                f"Polling #support Forum (channel={poller.support_channel_id or 'not-found'}, "
                f"interval={args.interval}s, settle={args.settle_seconds}s)..."
            )
            if args.once:
                executed = poller.poll_once()
                print(f"Poll complete ({len(executed)} thread action(s)).")
                return 0

            while True:
                try:
                    executed = poller.poll_once()
                    if executed:
                        for item in executed:
                            print(
                                f"[poll] replied to thread {item['thread_id']} "
                                f"(tags={item['applied_tags']}, escalated={item['ping_maintainer']}, "
                                f"archived={item['archived']})"
                            )
                except KeyboardInterrupt:
                    print("\nStopping Discord support bot poller.")
                    break
                except (OSError, sqlite3.Error) as exc:
                    print(f"[poll] transient network/API error: {exc}", file=sys.stderr)
                time.sleep(max(args.interval, 2.0))
            return 0

        print("SupportBotEngine initialized with read-only SQLite FTS5 index.")
    finally:
        engine.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
