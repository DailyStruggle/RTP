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

# Ensure the repo root is on sys.path so `from scripts.discord.*` resolves
# when the script is run directly (e.g. `python3 scripts/discord/discord_support_bot.py`).
_repo_root = pathlib.Path(__file__).resolve().parents[2]
if str(_repo_root) not in sys.path:
    sys.path.insert(0, str(_repo_root))

from scripts.discord.build_doc_index import (
    DEFAULT_DB_PATH,
    DEFAULT_PROJECT,
    ROOT,
    build_index,
    build_starting_reference_context,
    combinatorial_retrieve,
    decompound_ocr_text,
    extract_bit_codes,
    extract_doc_snippet,
    extract_grounded_query_signals,
    extract_specific_tokens,
    has_project_docs,
    normalize_multilingual_query,
    resolve_post_project,
    score_translation_candidate,
    search_arch_sections,
    search_code_sections,
    search_config_keys,
    search_docs,
    select_best_translation,
)
from scripts.discord.discord_setup import (
    CHANNEL_FLAG_PINNED,
    CHANNEL_TYPES,
    DiscordRestClient,
    strip_leading_emoji_label,
)

DEFAULT_STATE_PATH = ROOT / "scripts" / "discord" / "generated" / "bot-state.json"

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
    r"(?i)\b(bug(?:s)?|crash(?:es|ed|ing)?|exception(?:s)?|stack\s*trace|error(?:s)?|lag(?:s|ging|ged|gy)?|spike(?:s|d)?|stutter(?:s|ing)?|freeze(?:s|d|ing)?|stall(?:s|ed|ing)?|hang(?:s|ing)?|broken|doesn'?t\s+work|not\s+working|watchdog|leak(?:s|ing)?)\b"
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
    attachments: tuple[str, ...] = ()


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
      3. Normalizes any accidental `.md` suffix on `dailystruggle.github.io` doc URLs.
      4. Replaces em/en dashes with ASCII `-` per Rule D-006.
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
            if host == "dailystruggle.github.io" and ".md" in raw_url:
                raw_url = re.sub(r"\.md(?=/|#|$)", "/", raw_url)
                raw_url = raw_url.replace("//#", "/#")
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
            if parsed.scheme in ("http", "https") and host in DISCORD_ATTACHMENT_DOMAINS:
                if any(path_low.endswith(ext) for ext in IMAGE_EXTENSIONS) or "/attachments/" in path_low:
                    urls.append(clean)
        except ValueError:
            return

    for item in messages_or_attachments:
        if isinstance(item, str):
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


def extract_mclogs_ids(text: str, max_pastes: int = MAX_MCLOGS_PASTES_PER_POST) -> list[str]:
    """Extracts up to `max_pastes` unique `mclo.gs` paste IDs from a forum post."""
    ids: list[str] = []
    for m in MCLOGS_URL_RE.finditer(text):
        paste_id = m.group(1)
        if paste_id not in ids:
            ids.append(paste_id)
            if len(ids) >= max_pastes:
                break
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


PROTECTED_TECH_SPAN_RE = re.compile(
    r"(`[^`\n]{1,120}`|https?://[^\s<>)\]]+|/[A-Za-z0-9_.-]+(?:\s+[A-Za-z0-9_.=-]+)*|\b[A-Za-z0-9_-]+\.(?:yml|yaml|json|md|jar|sqlite)\b|\b[A-Za-z_][A-Za-z0-9_]*\.[A-Za-z_][A-Za-z0-9_.]*\b)"
)


def _mask_protected_tech_spans(
    text: str,
    max_chars: int = 900,
) -> tuple[str, str, list[str]]:
    """
    Redacts secrets and masks technical spans (`PROTECTED_TECH_SPAN_RE`) with
    `__RTP_SPAN_0__`..`__RTP_SPAN_n__` placeholders before calling external
    translation services.
    """
    cleaned = (text or "").strip()
    if not cleaned:
        return "", "", []
    _, safe_text = detect_and_redact_secrets(cleaned[:max_chars])
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


def missing_diagnostic_items(diag: dict[str, bool], is_startup_crash: bool = False) -> list[str]:
    """Returns human-readable descriptions of any missing diagnostic items."""
    missing: list[str] = []
    if is_startup_crash:
        if not diag.get("server_version"):
            missing.append("server `/version` or platform/Minecraft version (Paper, Folia, Spigot, Fabric, or NeoForge)")
        if not diag.get("log_link"):
            missing.append(
                "full `logs/latest.log` or crash report uploaded to <https://mclo.gs/> "
                "(in-game `/rtp test` is not required when the server cannot boot)"
            )
        return missing

    if not diag.get("rtp_version"):
        missing.append("`/rtp version` output")
    if not diag.get("server_version"):
        missing.append("server `/version` output (Paper, Folia, Spigot, Fabric, or NeoForge version)")
    if not diag.get("rtp_test"):
        missing.append("`/rtp test` self-test output")
    if not diag.get("log_link"):
        missing.append("full `logs/latest.log` uploaded to <https://mclo.gs/>")
    return missing


def present_diagnostic_items(diag: dict[str, bool]) -> list[str]:
    """Returns human-readable names of diagnostic items already detected."""
    present: list[str] = []
    if diag.get("rtp_version"):
        present.append("`/rtp version`")
    if diag.get("server_version"):
        present.append("server `/version`")
    if diag.get("rtp_test"):
        present.append("`/rtp test`")
    if diag.get("log_link"):
        present.append("`mclo.gs` log")
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
    r"lag(?:s|ging|ged|gy)?|tps\s+drop|drop\s+tps|mspt|freeze(?:s|d|ing)?|stutter(?:s|ing)?|"
    r"stall(?:s|ed|ing)?|hang(?:s|ing)?|unresponsive|time(?:s|\s+everyone)?\s+out|"
    r"null\s+(?:error|response)|throws?\s+up\s+a\s+null|already\s+teleporting|"
    r"very\s+high\s+up|mid\s*air|midair|"
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
    r"(?i)\b(?:clumsy\s+hands|learn(?:ing)?\s+to\s+code|deteriorating|ping\s+with\s+answer|officially\s+dead)\b"
)

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
    r"duration\s+unit\s+is\s+broken|deop(?:ping|ped)?|works\s+(?:as\s+normal|great|fine)\s+when|"
    r"in-game\s+but\s+for\s+some\s+reason|looking\s+up\s+those\s+methods\s+on\s+(?:the\s+)?github|"
    r"tab\s+completion\s+works|migrating\s+from\s+betterrtp.*exception|%rtp_[a-z0-9_]+%"
    r")\b"
)

BUILDER_API_RE = re.compile(
    r"(?i)\b(?:"
    r"developing\s+my\s+(?:server\s+)?plugin|using\s+(?:code|the\s+api)|rtp-api|rtpapi|"
    r"custom\s+shape|rtpaddon|addshape|bed-respawn|anchor-respawn|getbiome\(\)\s+function"
    r")\b"
)


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
) -> bool:
    """
    Deterministic Information-Sufficiency & Abstention Gate:
    Returns True when a post reports a vague runtime symptom (`lag`, `freeze`,
    `null error`, `already teleporting`, `very high up`, `not working`, etc.)
    with ZERO concrete diagnostics and without naming a specific configuration
    target or quoted RTP log message.
    """
    if has_concrete_diagnostics(raw_text, fetched_logs, diag, bit_matches):
        return False
    combined = f"{raw_text}\n{normalized_text}"
    if SPECIFIC_CONFIG_TARGET_RE.search(combined):
        return False
    if VAGUE_SYMPTOM_RE.search(combined) or BUG_REPORT_KEYWORDS.search(combined):
        return True
    return False


def classify_interaction_potential(
    combined_text: str,
    diag: dict[str, bool],
    bit_matches: list[dict[str, object]],
) -> dict[str, Any]:
    """
    Maintainer-Delight / Positive-Interaction Classifier derived from studying
    7,228 historical messages across `#general`, `#issues`, and `#support`.
    Identifies threads where the author is:
      1. `shop_talk`: asking about RTP's internal architecture, queue/cache cycle,
         spatial memory/spiral math, benchmarks, or multi-server Velocity/Redis setup;
      2. `empirical_repro`: sharing a methodical A/B reproduction test, config + log
         evidence, or source-code hypothesis (`Crumb`, `rhmpzw`, `EchoLalia`, `DDDDN`);
      3. `builder_api`: integrating against `rtp-api`, custom `Shape<?>`, or event hooks.
    """
    if HOSTILE_POST_RE.search(combined_text):
        return {"is_high_signal": False, "categories": [], "reasons": []}

    categories: list[str] = []
    reasons: list[str] = []

    if SHOP_TALK_ARCH_RE.search(combined_text):
        categories.append("shop_talk")
        reasons.append("architecture / systems shop-talk")

    has_ev = has_concrete_diagnostics(combined_text, "", diag, bit_matches)
    if EMPIRICAL_REPRO_RE.search(combined_text) and (has_ev or ":" in combined_text or "player:" in combined_text.lower()):
        categories.append("empirical_repro")
        reasons.append("empirical isolation / config + log repro")

    if BUILDER_API_RE.search(combined_text):
        categories.append("builder_api")
        reasons.append("API / addon / plugin integration")

    return {
        "is_high_signal": len(categories) >= 1,
        "categories": categories,
        "reasons": reasons,
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
        return bool(ip.is_loopback or ip.is_private)
    except ValueError:
        return False


def get_project_allowed_domains(conn: sqlite3.Connection | None = None) -> set[str]:
    """
    Returns `ALLOWED_DOMAINS` unioned with any documentation hostnames indexed
    in `doc_sections.url` so `--project <slug>=<path>@<site_url>` works across
    custom project documentation domains without editing `ALLOWED_DOMAINS`.
    """
    domains = set(ALLOWED_DOMAINS)
    if conn is not None:
        try:
            for r in conn.execute("SELECT DISTINCT url FROM doc_sections LIMIT 200").fetchall():
                host = (urllib.parse.urlparse(str(r[0])).hostname or "").lower()
                if host:
                    domains.add(host)
        except sqlite3.OperationalError:
            pass
    return domains


def verify_llm_draft_against_schema(
    conn: sqlite3.Connection,
    draft: str,
    project: str = "leafrtp",
    retrieved_text: str = "",
) -> tuple[bool, str]:
    """
    Deterministic anti-hallucination firewall (`REQ-DISC-003`, `REQ-DISC-005`):
      1. Rejects empty or overly long (>1600 char) drafts.
      2. Rejects `@everyone`, `@here`, `<@&role>` mentions or URLs outside `get_project_allowed_domains(conn)`.
      3. Verifies every backticked `/rtp <subcmd>` against `VALID_RTP_SUBCOMMANDS`.
      4. Verifies every backticked dotted config key against `config_keys` in
         `doc-index.sqlite` (or `retrieved_text` / YAML file stems).
      5. Verifies numeric `key=value` assignments against `# @range: [min, max]`
         directives and string enum assignments against `# @options: [...]` directives.
    """
    cleaned = draft.strip()
    if not cleaned:
        return False, "empty draft"
    if len(cleaned) > 1600:
        return False, "draft exceeds 1600 chars"
    if ROLE_OR_EVERYONE_MENTION_RE.search(cleaned):
        return False, "contains forbidden role or @everyone mention"

    allowed_domains = get_project_allowed_domains(conn)
    for m in ANY_URL_RE.finditer(cleaned):
        raw_url = m.group(0).rstrip(".,;:!?)>")
        try:
            host = (urllib.parse.urlparse(raw_url).hostname or "").lower()
        except ValueError:
            return False, f"invalid URL: {raw_url}"
        if host not in allowed_domains:
            return False, f"disallowed URL domain: {host}"

    # Verify backticked /rtp commands
    if project.strip().lower() == "leafrtp":
        for cmd_match in re.finditer(r"`/rtp(?:\s+([^\s`=]+))?", cleaned):
            sub = (cmd_match.group(1) or "").strip().lower()
            if not sub or "=" in sub or sub.startswith(("<", "[")):
                continue
            if sub not in VALID_RTP_SUBCOMMANDS:
                return False, f"hallucinated /rtp subcommand: {sub}"

    retrieved_lower = retrieved_text.lower()
    proj_lower = project.strip().lower()

    # Verify backticked dotted identifiers that look like config keys
    for span in re.findall(r"`([^`\n]{2,80})`", cleaned):
        token = span.strip().split("=")[0].split(":")[0].strip()
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_-]*(?:\.[A-Za-z0-9_-]+)+", token):
            continue
        tok_lower = token.lower()
        if tok_lower.endswith(NON_CONFIG_DOTTED_SUFFIXES):
            continue
        if tok_lower.startswith(NON_CONFIG_DOTTED_PREFIXES):
            continue
        if tok_lower in retrieved_lower:
            continue
        row = conn.execute(
            """
            SELECT 1 FROM config_keys
            WHERE lower(project) = ?
              AND (lower(key_path) = ? OR lower(key_path) LIKE ? OR lower(file_path) LIKE ?)
            LIMIT 1
            """,
            (proj_lower, tok_lower, f"%.{tok_lower}", f"%{tok_lower.replace('.', '/')}%"),
        ).fetchone()
        if row is None:
            return False, f"hallucinated config key: {token}"

    # Verify numeric recommendations against schema `@range: [min, max]` directives
    for kv_match in re.finditer(r"\b([A-Za-z_][A-Za-z0-9_.]*)\s*[=:]\s*(-?\d+(?:\.\d+)?)(?![A-Za-z])", cleaned):
        k_name = kv_match.group(1).strip().lower()
        try:
            val_num = float(kv_match.group(2))
        except ValueError:
            continue
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
    for opt_match in re.finditer(r"\b([A-Za-z_][A-Za-z0-9_.]*)\s*[=:]\s*['\"]?([A-Za-z_][A-Za-z0-9_-]{1,32})['\"]?", cleaned):
        k_name = opt_match.group(1).strip().lower()
        val_str = opt_match.group(2).strip()
        if val_str.lower() in ("true", "false", "value", "name", "default", "config", "auto", "off", "none"):
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
    ) -> str | None:
        if not is_safe_llm_endpoint(self.endpoint):
            return None
        if not cfg_hits and not doc_hits and not bit_matches and not code_hits:
            return None

        _, safe_question = detect_and_redact_secrets(user_question)
        signals = extract_grounded_query_signals(
            conn=conn,
            user_question=safe_question,
            cfg_hits=cfg_hits,
            doc_hits=doc_hits,
            project=project,
            code_hits=code_hits,
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
        }
        if signals.get("matched_code_diagnostics"):
            signals_payload["matched_code_diagnostics"] = signals["matched_code_diagnostics"]
        signals_json = json.dumps(signals_payload, ensure_ascii=False)

        system_prompt = (
            "You are the LeafRTP technical support cause-analysis and planning assistant. "
            "For security, user posts are projected into an orderless set of corpus-verified concepts and "
            "clause-grouped token sets in `<extracted_query_signals>` alongside `<starting_reference_point>`, "
            "`<retrieved_architecture_and_adrs>`, and `<retrieved_docs_and_schema>`.\n\n"
            "Instructions:\n"
            "1. Take time to reason carefully about **what the user's goal is** versus **what RTP actually does** "
            "based on `<starting_reference_point>`, `<retrieved_architecture_and_adrs>`, and `<retrieved_docs_and_schema>`:\n"
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
            "3. Start each bullet with `- **<Goal or Cause>:** `, followed by 1-2 natural "
            "engineering sentences stating the exact YAML file and key or permission (with its default value, respecting any "
            "`@range` limits in `directives`), the in-game `/rtp ...` command to verify or change it, and the "
            "`<https://dailystruggle.github.io/RTP/...>` link wrapped in `<...>` angle brackets (never wrap `<...>` "
            "URLs inside backticks).\n"
            "4. Do NOT output Markdown headers (`###`), intro/outro paragraphs, numbered lists, or multi-block "
            "`bash` fences outside `<think>...</think>`. Keep the user-facing bullets under 1,200 characters.\n"
            "5. Use ASCII '-' hyphens only (never em-dashes), never raw pipe '|' tables, and never invent config "
            "keys, URLs, or `/rtp` subcommands outside the provided context. Only cite exact `url: <https://...>` "
            "links listed in `<retrieved_docs_and_schema>` or `<starting_reference_point>` (never end a site URL "
            "with `.md`). For `POLYGON` `vertices:`, use integer chunk or block coordinates (`[200c, 0c]`, "
            "`[100c, 173c]`, never decimals), and remember `vert.requireSkyLight` is `true` for surface-only "
            "(prevents caves) and `false` in the Nether."
        )
        user_prompt = (
            f"<starting_reference_point>\n{starting_ref}\n</starting_reference_point>\n\n"
            f"<retrieved_architecture_and_adrs>\n{arch_blob}\n</retrieved_architecture_and_adrs>\n\n"
            f"<retrieved_code_context_second_class>\n{code_blob}\n</retrieved_code_context_second_class>\n\n"
            f"<retrieved_docs_and_schema>\n{retrieved_blob}\n</retrieved_docs_and_schema>\n\n"
            f"<extracted_query_signals>\n{signals_json}\n</extracted_query_signals>\n\n"
            "Think step-by-step about what the user wants versus what RTP does architecturally (checking both "
            "permission/usage anti-patterns in `what-not-to-do.md` such as `rtp.unqueued` / `rtp.params`, "
            "second-class source code exception/log diagnostics in `<retrieved_code_context_second_class>`, and "
            "YAML configuration keys, and ensuring every recommendation helps the user's goal rather than making it "
            "worse). Then output ONLY 2 to 4 actionable plans ordered from highest to lowest confidence as "
            "`- **<Goal or Cause>:** ...` bullets, where every bullet includes the exact YAML file/key or permission, "
            "the `/rtp ...` command, and the `<https://dailystruggle.github.io/RTP/...>` link (no intro/outro text, "
            "no `(High/Medium confidence)` labels, no `###` headers, respect `@range` limits, <1200 chars):"
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
        # Convert `[Label](https://...)` markdown links into Discord `<https://...>` angle-bracket links
        content = re.sub(r"\[[^\]]+\]\((https?://[^\s)]+)\)", r"<\1>", content)
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
        self.llm_synthesizer = llm_synthesizer
        self.translator = translator
        self.ocr_extractor = ocr_extractor or ocr_image_source
        self.escalated_threads: set[str] = set()
        self.investigative_threads: set[str] = set()
        self.followup_answered_threads: set[str] = set()
        self.user_last_action: dict[str, float] = {}

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
            if not att:
                continue
            raw_ocr = self.ocr_extractor(att)
            if raw_ocr and raw_ocr.strip():
                decompounded = decompound_ocr_text(self.conn, raw_ocr.strip(), project=project)
                chunks.append(decompounded)
        return "\n".join(chunks)

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
        should_query_code = has_explicit_error_signal or bool(BUG_REPORT_KEYWORDS.search(combined_probe))
        if not should_query_code:
            return [], cfg_hits

        code_hits = search_code_sections(self.conn, combined_probe, limit=4, project=project)
        if not code_hits:
            return [], cfg_hits

        if not has_explicit_error_signal and not cfg_hits:
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
        project = resolve_post_project(post.applied_tags)
        if not has_project_docs(self.conn, project):
            return False
        if self.llm_synthesizer is None:
            return False
        combined = f"{post.title}\n{post.body}\n{followup_text}".strip()
        all_att = tuple(post.attachments) + tuple(followup_attachments)
        if all_att or extract_mclogs_ids(combined) or is_verified_or_pro(post.author_roles):
            return True
        diag = inspect_diagnostics(combined, "")
        bit_matches = extract_bit_codes(self.conn, combined, project=project)
        return not should_abstain_for_missing_info(
            normalized_text=combined,
            raw_text=combined,
            fetched_logs="",
            diag=diag,
            bit_matches=bit_matches,
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

    def _build_abstention_reply(
        self,
        combined_text: str,
        normalized_text: str,
        diag: dict[str, bool],
        missing_diag: list[str],
        is_startup_crash: bool,
    ) -> str:
        """
        Builds a concise, non-preachy abstention reply when a user reports a
        vague runtime symptom without diagnostic context, explaining why the
        bot is holding off on config recommendations until it sees their setup.
        """
        q_lower = f"{combined_text}\n{normalized_text}".lower()
        present = present_diagnostic_items(diag)
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
            intro = (
                f"{ack_prefix}Teleport lag or server slowdowns usually come from one of three places: "
                "a permission setup bypassing the background location cache (`rtp.unqueued`, `rtp.params`, "
                "or wildcard `rtp.*` granted to regular players), on-demand chunk generation in an "
                "unpregenerated world, or custom `advanced/performance.yml` overrides (`syncLoading`, "
                "`viewDistanceSelect`, or JVM heap pressure).\n\n"
                "I do not want to recommend a config change that targets the wrong cause or makes your tick "
                "time worse. Please reply with:"
            )
            bullets = [
                "- What `/rtp info` reports for hot/cold queue fill and active chunk tickets when the slowdown happens"
            ]
        else:
            intro = (
                f"{ack_prefix}A failed `/rtp` command or unexpected placement usually comes down to a "
                "permission node override (`rtp.use`, `rtp.notme` on operators, or region `requirePermission`), "
                "a missing world-to-region mapping in `definitions/worlds/` and `definitions/regions/`, "
                "or a console exception.\n\n"
                "Rather than guessing at settings without seeing your setup, please reply with:"
            )
            bullets = [
                "- The exact command you ran, which world/region you are in, and whether the player has OP or a specific LuckPerms group"
            ]

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
    ) -> str:
        q_lower = combined_text.lower()
        present = present_diagnostic_items(diag)
        ack_prefix = f"I already noted your {', '.join(present)}. " if present else ""

        questions: list[str] = []
        if is_startup_crash:
            questions.append(
                "Since the server is failing on startup/enable, you do not need to run in-game `/rtp test`."
            )
            for item in missing_diag:
                questions.append(f"- {item}")
            return (
                f"{ack_prefix}To narrow down this startup issue before I ping the maintainer, please share:\n"
                + "\n".join(questions)
                + "\nOnce you reply with the log, I will queue this thread in `#bot-triage-queue` right away."
            )

        if any(w in q_lower for w in ("radius", "border", "center", "spawn", "region", "nether", "end", "cave", "miny", "maxy", "biome")):
            questions.append(
                "- Which file did you edit (`plugins/RTP/config.yml` vs `definitions/regions/<region>.yml`), and does that region file use `shape: \"@config\"` or an inline `shape:` block?"
            )
        elif any(w in q_lower for w in ("gui", "menu", "openonbarertp", "permission", "luckperms", "cooldown", "delay", "price", "cost", "economy", "vault", "shop", "action")):
            questions.append(
                "- What exact command or setup are you testing, and is the player testing with OP or a standard permission group (`rtp.use`, `rtp.gui`)?"
            )
        elif any(w in q_lower for w in ("lag", "slow", "tps", "mspt", "chunk", "queue", "memory", "heap", "stall", "freeze", "folia")):
            questions.append(
                "- What does `/rtp info` report for hot/cold queue fill and active chunk tickets when the slowdown happens?"
            )
        else:
            questions.append(
                "- What exact behavior or integration are you trying to achieve, and what config changes or commands have you tried so far?"
            )

        for item in missing_diag:
            questions.append(f"- {item}")

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

        project = resolve_post_project(post.applied_tags)
        project_has_docs = has_project_docs(self.conn, project)

        att_urls = list(dict.fromkeys(list(post.attachments) + extract_image_attachment_urls((), inline_text=f"{post.title}\n{post.body}")))
        ocr_text = self._extract_attachments_ocr(att_urls, project=project)
        combined_user_text = f"{post.title}\n{post.body}"
        if ocr_text:
            combined_user_text = f"{combined_user_text}\n{ocr_text}".strip()
        mclogs_ids = extract_mclogs_ids(combined_user_text)
        fetched_logs = "\n".join(self.log_fetcher(pid) for pid in mclogs_ids if pid)
        if ocr_text and not fetched_logs and (
            CONCRETE_EVIDENCE_RE.search(ocr_text)
            or CODE_OR_ERROR_SIGNAL_RE.search(ocr_text)
            or len(ocr_text.splitlines()) >= 4
        ):
            # Allow console/chat screenshots with concrete diagnostic output to count alongside logs
            fetched_logs = ocr_text

        has_secret, _ = detect_and_redact_secrets(f"{combined_user_text}\n{fetched_logs}")
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
        bit_matches = extract_bit_codes(self.conn, f"{combined_user_text}\n{fetched_logs}", project=project)
        normalized_user_text = self._prepare_multilingual_text(combined_user_text, project=project)
        is_startup_crash = bool(STARTUP_CRASH_KEYWORDS.search(normalized_user_text))
        diag = inspect_diagnostics(normalized_user_text, fetched_logs)
        missing_diag = missing_diagnostic_items(diag, is_startup_crash=is_startup_crash)
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

        # Step 2: Deterministic Information-Sufficiency & Abstention Gate + Positive-Interaction Matchmaking
        has_bug_kw = bool(
            BUG_REPORT_KEYWORDS.search(normalized_user_text)
            or CODE_OR_ERROR_SIGNAL_RE.search(combined_user_text)
        )
        is_bug = has_bug_kw
        specific_tokens = extract_specific_tokens(normalized_user_text, conn=self.conn, project=project)
        verified_user = is_verified_or_pro(post.author_roles)
        has_diag_evidence = has_concrete_diagnostics(combined_user_text, fetched_logs, diag, bit_matches)
        should_abstain = should_abstain_for_missing_info(
            normalized_text=normalized_user_text,
            raw_text=combined_user_text,
            fetched_logs=fetched_logs,
            diag=diag,
            bit_matches=bit_matches,
        )
        interaction = classify_interaction_potential(
            combined_text=f"{combined_user_text}\n{normalized_user_text}",
            diag=diag,
            bit_matches=bit_matches,
        )

        # Unverified users posting a vague symptom with zero diagnostics are stopped
        # deterministically before any LLM call or speculative config recommendation.
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
                )
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
            )

        cfg_hits, doc_hits = combinatorial_retrieve(
            self.conn,
            normalized_user_text,
            cfg_limit=7,
            doc_limit=5,
            project=project,
        )

        has_high_conf_doc = any(h.get("confidence") == "high" for h in cfg_hits[:3]) or any(
            h.get("confidence") == "high" for h in doc_hits[:3]
        )
        code_hits, cfg_hits = self._retrieve_second_class_code_hits(
            raw_and_logs=f"{combined_user_text}\n{fetched_logs}",
            normalized_text=normalized_user_text,
            cfg_hits=cfg_hits,
            has_high_conf_doc=has_high_conf_doc,
            project=project,
        )
        has_any_doc = bool(cfg_hits or doc_hits)
        has_medium_only_doc = has_any_doc and not has_high_conf_doc

        # Avoid dumping weak/medium doc hits on pure bug reports unless a high-confidence setting matched
        if is_bug and has_medium_only_doc and not HOWTO_KEYWORDS.search(normalized_user_text) and "?" not in normalized_user_text and "？" not in normalized_user_text:
            cfg_hits = []
            doc_hits = []
            has_any_doc = False
            has_medium_only_doc = False

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
            if llm_reply:
                sections.append(llm_reply)
            elif cfg_hits or doc_hits:
                if has_high_conf_doc and not is_bug:
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
                        "builder_api" in interaction["categories"]
                        and not any(ext in normalized_user_text.lower() for ext in (".yml", "shape", "vert", "radius", "config"))
                    )
                    or (
                        is_op_notme_query
                        and not any(ext in normalized_user_text.lower() for ext in (".yml", "config", "integrations"))
                    )
                )
                if show_cfg:
                    for ch in select_diverse_config_hits(cfg_hits, max_hits=3, max_per_file=3):
                        ans_lines.append(_format_config_hit(ch, project=project))
                for dh in select_diverse_doc_hits(doc_hits, max_hits=(2 if not show_cfg else 2), max_per_doc=1):
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
            )

        # Determine whether the bot is unsure if a doc solves the problem and should enter Investigative Mode
        is_unsure = (
            has_medium_only_doc
            or (is_bug and not all_diag_present)
            or (not has_any_doc and not bit_matches and bool(specific_tokens))
        )

        # Step 3: Two-Stage Maintainer Escalation Gate, Investigative Triage & Positive-Interaction Matchmaking
        worth_a_look_reasons: list[str] = list(interaction["reasons"]) if interaction["is_high_signal"] else []
        should_escalate_now = False
        if pro:
            should_escalate_now = True
        elif all_diag_present and (is_bug or bit_matches):
            should_escalate_now = True
        elif bool(worth_a_look_reasons):
            should_escalate_now = True

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
                    has_doc_or_cfg=bool(cfg_hits or doc_hits or bit_matches),
                    is_bug=is_bug,
                    is_startup_crash=is_startup_crash,
                )
            )
        else:
            if not sections and should_escalate_now:
                sections.append(
                    "I have queued this thread in `#bot-triage-queue` for priority maintainer review."
                )
            sections.append(
                "If this answered your question, react with ✅ (or reply `solved`) to close this thread. "
                "If you still need help or want to report a bug, react with ❓ (or reply with what happened)."
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
                worth_a_look_reasons=worth_a_look_reasons or None,
                code_classes=[f"{c['class_name']}.java" for c in code_hits[:3]] if (is_bug or bit_matches) and code_hits else None,
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
        )

    def handle_followup_message(
        self,
        post: ForumPostContext,
        message_body: str,
        followup_attachments: tuple[str, ...] = (),
    ) -> BotDecision:
        """
        Handles a follow-up message in a thread waiting on `⏳ Needs Info / Logs`,
        or a follow-up where the user says the suggested doc did not solve their issue.
        Investigates up to the point where sufficient context/diagnostics are gathered,
        then removes `⏳ Needs Info / Logs` and posts the 4-line digest to `#bot-triage-queue`.
        """
        project = resolve_post_project(post.applied_tags)
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
        followup_ocr = self._extract_attachments_ocr(f_att_urls, project=project)
        effective_message_body = f"{message_body}\n{followup_ocr}".strip() if followup_ocr else message_body

        combined = f"{post.title}\n{post.body}\n{effective_message_body}"
        followup_mclogs = extract_mclogs_ids(effective_message_body)
        followup_logs = "\n".join(self.log_fetcher(pid) for pid in followup_mclogs if pid)
        if followup_ocr and not followup_logs:
            followup_logs = followup_ocr
        has_secret, _ = detect_and_redact_secrets(f"{effective_message_body}\n{followup_logs}")
        bit_matches = extract_bit_codes(self.conn, f"{effective_message_body}\n{followup_logs}", project=project)
        normalized_followup = self._prepare_multilingual_text(effective_message_body, project=project)
        needs_followup_investigation = bool(
            FOLLOWUP_INVESTIGATE_RE.search(normalized_followup)
            or "？" in normalized_followup
            or followup_mclogs
            or bool(followup_ocr)
            or bit_matches
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
                and post.thread_id not in self.followup_answered_threads
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
                if has_high_followup:
                    self.followup_answered_threads.add(post.thread_id)
                    f_sections: list[str] = []
                    if has_secret:
                        f_sections.append(
                            "**Security warning:** It looks like your post or log contains a password or "
                            "`network.yml` secret (`RTP_NET_SECRET` / Redis / SQL credential). Please edit "
                            "or delete the paste and rotate that secret right away."
                        )
                    llm_followup: str | None = None
                    if self.llm_synthesizer is not None:
                        llm_followup = self.llm_synthesizer.synthesize(
                            conn=self.conn,
                            user_question=f"{post.title}\n{normalized_followup}",
                            cfg_hits=f_cfg,
                            doc_hits=f_docs,
                            bit_matches=[],
                            project=project,
                        )
                    if llm_followup:
                        f_sections.append(llm_followup)
                    else:
                        f_lines = ["Here is what the configuration schema and docs say for that follow-up:"]
                        for ch in select_diverse_config_hits(f_cfg, max_hits=3, max_per_file=2):
                            f_lines.append(_format_config_hit(ch, project=project))
                        for dh in select_diverse_doc_hits(f_docs, max_hits=2, max_per_doc=1):
                            f_lines.append(_format_doc_hit(dh))
                        f_sections.append("\n".join(f_lines))
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

        mclogs_ids = extract_mclogs_ids(combined)
        fetched_logs = "\n".join(self.log_fetcher(pid) for pid in mclogs_ids if pid)
        if followup_ocr and not fetched_logs:
            fetched_logs = followup_ocr
        all_bit_matches = extract_bit_codes(self.conn, f"{combined}\n{fetched_logs}", project=project)

        is_startup_crash = bool(STARTUP_CRASH_KEYWORDS.search(combined))
        diag = inspect_diagnostics(combined, fetched_logs)
        missing_diag = missing_diagnostic_items(diag, is_startup_crash=is_startup_crash)
        pro = is_pro_supporter(post.author_roles)

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
        if bit_matches:
            bit_lines = ["I matched the following `[RTP bit]` finding(s) in your follow-up:"]
            for bm in bit_matches[:4]:
                bit_lines.append(
                    f"- **`{bm['key']}`** (`{bm['namespace']}-{int(bm['id']):04d}`, severity `{bm['severity']}`): "
                    f"{bm['remediation']}"
                )
            bit_lines.append("Full catalog: <https://dailystruggle.github.io/RTP/dev/BIT_CODES/>")
            sections.append("\n".join(bit_lines))

        ready_to_escalate = pro or self._is_investigation_complete(
            combined_text=combined,
            diag=diag,
            missing_diag=missing_diag,
            is_startup_crash=is_startup_crash,
            is_investigative=(post.thread_id in self.investigative_threads),
            followup_text=effective_message_body,
        )

        if not ready_to_escalate:
            tags.append(TAG_NEEDS_INFO)
            # Check if the follow-up introduced a new specific config key that wasn't in the initial post
            followup_cfg = search_config_keys(self.conn, effective_message_body, limit=1, project=project)
            if followup_cfg and followup_cfg[0].get("confidence") == "high":
                ch = followup_cfg[0]
                if ch["key_path"].lower() not in post.body.lower():
                    sections.append(
                        "One related setting to check based on your reply:\n"
                        + _format_config_hit(ch, project=project)
                    )

            present = present_diagnostic_items(diag)
            ack = f"Thanks - I recorded your {', '.join(present)}. " if present else "Thanks for the follow-up. "
            missing_bullets = "\n".join(f"- {item}" for item in missing_diag)
            sections.append(
                f"{ack}To finish narrowing this down before I ping the maintainer, please also share:\n"
                f"{missing_bullets}"
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
            )

        f_code_hits, _ = self._retrieve_second_class_code_hits(
            raw_and_logs=f"{combined}\n{fetched_logs}",
            normalized_text=normalized_followup,
            cfg_hits=[],
            has_high_conf_doc=False,
            project=project,
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
                bit_keys=[str(b["key"]) for b in all_bit_matches],
                mclogs_ids=mclogs_ids,
                investigative_note=effective_message_body,
                code_classes=[f"{c['class_name']}.java" for c in f_code_hits[:3]] if f_code_hits else None,
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
        combined = f"{post.title}\n{post.body}"
        mclogs_ids = extract_mclogs_ids(combined)
        fetched_logs = "\n".join(self.log_fetcher(pid) for pid in mclogs_ids if pid)
        is_startup_crash = bool(STARTUP_CRASH_KEYWORDS.search(combined))
        diag = inspect_diagnostics(combined, fetched_logs)
        missing_diag = missing_diagnostic_items(diag, is_startup_crash=is_startup_crash)
        ready = pro or self._is_investigation_complete(
            combined_text=combined,
            diag=diag,
            missing_diag=missing_diag,
            is_startup_crash=is_startup_crash,
            is_investigative=(post.thread_id in self.investigative_threads),
            followup_text="",
        )

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
            )
            return BotDecision(
                reply_text=sanitize_bot_output(f"No problem - {prompt}"),
                applied_tags=tuple(tags),
                components=build_resolution_buttons(),
                allowed_mentions=AllowedMentions(users=(post.author_id,)),
                triage_digest=None,
                ping_maintainer=False,
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
    r"(?im)^\s*(?:!?\s*solved|!?\s*fixed|issue\s+solved|issue\s+fixed|problem\s+solved|all\s+fixed|that\s+fixed\s+it)\b"
)
NEED_HELP_TEXT_RE = re.compile(
    r"(?im)^\s*(?:!?\s*need\s+help|!?\s*still\s+need\s+help|!?\s*escalate)\b"
)


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

        self._load_state()

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

        msg_text = decision.reply_text.strip()
        if msg_text and decision.should_reply:
            if not self.include_buttons and decision.components:
                msg_text = msg_text.replace(
                    "click **[✅ Solved]** below. If you still need help or want to report a bug, click **[❓ Still need help]**.",
                    "react with ✅ (or reply `solved`) to close this thread, or react with ❓ (or reply with the diagnostics) if you still need help.",
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
                if sent_msg is None:
                    sent_msg = {"id": progress_message_id}
            else:
                sent_msg = self.client.request("POST", f"/channels/{thread_id}/messages", msg_payload)

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

        self._save_state()
        return {
            "thread_id": thread_id,
            "applied_tags": decision.applied_tags,
            "ping_maintainer": decision.ping_maintainer,
            "archived": decision.archived,
        }

    def poll_once(self) -> list[dict[str, Any]]:
        """
        Executes one sequential poll pass over active `#support` Forum threads.
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
        now = self.now_fn()

        for th in support_threads:
            thread_id = str(th["id"])
            title = str(th.get("name", th.get("title", "Support Thread")))
            owner_id = str(th.get("owner_id", ""))
            raw_tag_ids = [str(t) for t in th.get("applied_tags", [])]
            tag_names = self._tags_to_names(raw_tag_ids)

            if TAG_SOLVED in tag_names:
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

            # Skip threads created by the maintainer or by the bot itself (e.g. reference guides)
            if owner_id in (self.engine.maintainer_id, self.bot_user_id):
                continue

            # Check settle window on the newest non-bot message so back-to-back posts coalesce
            non_bot_msgs = [
                m for m in messages if str((m.get("author") or {}).get("id", "")) != self.bot_user_id
            ]
            if non_bot_msgs and self.settle_seconds > 0:
                newest_ts = parse_discord_timestamp(non_bot_msgs[-1].get("timestamp"), now)
                if (now - newest_ts) < self.settle_seconds:
                    continue

            bot_indices = [
                idx
                for idx, m in enumerate(messages)
                if str((m.get("author") or {}).get("id", "")) == self.bot_user_id
            ]

            author_roles = self._resolve_author_roles(owner_id)

            # Case 1: Bot has not replied in this thread yet -> coalesce all author messages
            if not bot_indices:
                # If the maintainer or another human already replied before the bot polled, stay silent
                human_repliers = [
                    m
                    for m in messages
                    if str((m.get("author") or {}).get("id", "")) not in ("", owner_id, self.bot_user_id)
                ]
                if human_repliers:
                    continue

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
                )
                progress_msg_id = (
                    self._post_progress_message(thread_id)
                    if self.engine.will_run_deep_search(post)
                    else None
                )
                decision = self.engine.handle_new_thread(post)
                if decision.should_reply or progress_msg_id:
                    actions.append(
                        self._apply_decision(
                            thread_id,
                            raw_tag_ids,
                            decision,
                            progress_message_id=progress_msg_id,
                        )
                    )
                continue

            # Case 2: Bot has already replied -> check reactions or coalesced follow-up messages
            last_bot_idx = bot_indices[-1]
            last_bot_msg = messages[last_bot_idx]
            last_bot_msg_id = str(last_bot_msg["id"])

            prior_author_msgs = [
                m
                for m in messages[:last_bot_idx]
                if str((m.get("author") or {}).get("id", "")) == owner_id
            ]
            prior_author_body = "\n\n".join(
                str(m.get("content", "")).strip()
                for m in prior_author_msgs
                if str(m.get("content", "")).strip()
            )
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
            after_author_msg_objs = [
                m
                for m in messages[last_bot_idx + 1 :]
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
            if not after_author_msgs and not followup_attachments:
                continue

            coalesced_followup = "\n\n".join(after_author_msgs)

            if coalesced_followup and SOLVED_TEXT_RE.search(coalesced_followup):
                decision = self.engine.handle_button_interaction(post, BUTTON_SOLVED_ID, actor_id=owner_id)
                actions.append(self._apply_decision(thread_id, raw_tag_ids, decision, seed_reactions=False))
                continue

            if coalesced_followup and NEED_HELP_TEXT_RE.search(coalesced_followup) and TAG_NEEDS_INFO not in tag_names:
                decision = self.engine.handle_button_interaction(post, BUTTON_NEED_HELP_ID, actor_id=owner_id)
                actions.append(
                    self._apply_decision(thread_id, raw_tag_ids, decision, seed_reactions=not decision.archived)
                )
                continue

            # If a human/maintainer already stepped in after the bot's last message, stay out of the conversation
            human_replies_after_bot = [
                m
                for m in messages[last_bot_idx + 1 :]
                if str((m.get("author") or {}).get("id", "")) not in ("", owner_id, self.bot_user_id)
            ]
            if human_replies_after_bot:
                continue

            # Cap total automated bot replies per thread so a single thread cannot loop indefinitely
            if len(bot_indices) >= MAX_BOT_REPLIES_PER_THREAD:
                continue

            if TAG_NEEDS_INFO in tag_names or thread_id not in self.engine.escalated_threads:
                progress_msg_id = (
                    self._post_progress_message(thread_id)
                    if self.engine.will_run_deep_search(
                        post,
                        followup_text=coalesced_followup,
                        followup_attachments=followup_attachments,
                    )
                    else None
                )
                decision = self.engine.handle_followup_message(
                    post,
                    coalesced_followup,
                    followup_attachments=followup_attachments,
                )
                if decision.should_reply or progress_msg_id:
                    actions.append(
                        self._apply_decision(
                            thread_id,
                            raw_tag_ids,
                            decision,
                            seed_reactions=not decision.archived,
                            progress_message_id=progress_msg_id,
                        )
                    )

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
                except OSError as exc:
                    print(f"[poll] transient network/API error: {exc}", file=sys.stderr)
                time.sleep(max(args.interval, 2.0))
            return 0

        print("SupportBotEngine initialized with read-only SQLite FTS5 index.")
    finally:
        engine.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
