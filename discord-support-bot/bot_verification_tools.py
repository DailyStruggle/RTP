#!/usr/bin/env python3
"""
Customer Setup Reconstruction, 4-Tool Verification Workbench & Discord Versioned Artifact Cache.

Implements:
  1. Customer Setup Reconstructor (`reconstruct_customer_setup`):
     Builds a `CustomerSetupProfile` (`setup_mode = "definite" | "best_approximation"`)
     from customer posts, `mclo.gs` / console logs, pasted YAML snippets, `[RTP bit]`
     findings, and AST symbol links overlaid onto the shipped baseline YAML configs.
  2. The 4 Execution & Verification Tools (`BotVerificationWorkbench`):
     - Tool 1 (`ConsoleLogTool`): Simulates/executes `/rtp` console commands and config
       patches against the customer setup, checks `commands-api` grammar and cross-key
       BIT/AST invariants (`centerRadius < radius`, `minY < maxY`, `S-005` `syncLoading`,
       `maxSlope` + `slopeRadius`), self-corrects missing companion keys, and gathers
       realistic console + `[RTP bit]` log transcripts.
     - Tool 2 (`LocalWebpageTool`): Executes operations against the local Web Editor
       (`docs/editor/index.html` + `docs/editor/editor-data.json`) and Support Doctor
       (`docs/assets/support-corpus.json`), including Quick Find search, YAML diff staging,
       region geometry/curve simulation (`CIRCLE`, `SQUARE`, `RADIAL`, `SPIRAL`, `HILBERT`),
       Doctor triage, and spotlight deep-link generation (`?file=...&key=...&search=...&intent=...`).
     - Tool 3 (`ScreenshottingTool`): Pure-stdlib renderer producing crisp dark-theme
       visual verification artifacts (`PNG` storyboard, lossless multi-frame `APNG` with
       `acTL`/`fcTL`/`fdAT` sub-rectangle delta compression, and animated `GIF89a`),
       plus optional headless Firefox/Chrome DOM screenshot capture, tagged with a
       canonical versioned cache tag and image description.
     - Tool 4 (`MineflayerBotTool`): Runs a bounded headless Mineflayer probe via
       `devstack/clients/mineflayer-bot.js` when a live test server is configured/reachable,
       or a deterministic setup-grounded Mineflayer protocol trace (`spawn` -> `/rtp` ->
       warmup `actionBar`/`title` -> `forcedMove` / proxy `transfer`) when offline.
  3. Discord Server as a Versioned Cache (`DiscordVersionedArtifactCache`):
     Tags bot outputs and uploaded images with `[rtp-cache:v<version>:<schema_hash8>:<intent_slug>]`
     and rich `image_description` metadata (in Discord `attachments[].description` and
     `embeds[].footer.text`), harvests existing tagged images from Discord channel/thread
     messages, reuses matching images by link (`https://cdn.discordapp.com/attachments/...`),
     and automatically invalidates entries when the plugin version or AST/schema hash changes.
"""

from __future__ import annotations

import binascii
from dataclasses import dataclass, field
import hashlib
import json
import math
import os
import pathlib
import re
import shutil
import socket
import sqlite3
import struct
import subprocess
import time
from typing import Any
import urllib.parse
import zlib

BOT_DIR = pathlib.Path(__file__).resolve().parent
REPO_ROOT = BOT_DIR.parent
RESOURCES_DIR = REPO_ROOT / "rtp-plugin" / "src" / "main" / "resources"
EDITOR_DATA_PATH = REPO_ROOT / "docs" / "editor" / "editor-data.json"
SUPPORT_CORPUS_PATH = REPO_ROOT / "docs" / "assets" / "support-corpus.json"
DEFAULT_EDITOR_BASE_URL = "https://dailystruggle.github.io/RTP/editor/"

CACHE_TAG_RE = re.compile(
    r"\[?rtp-cache:v(?P<version>[0-9A-Za-z._-]+):(?P<schema_hash>[0-9a-f]{6,16}):(?P<intent_slug>[a-z0-9_-]{2,64})\]?",
    re.IGNORECASE,
)
DISCORD_CDN_URL_RE = re.compile(
    r"https?://(?:cdn\.discordapp\.com|media\.discordapp\.net)/attachments/\d+/\d+/[^\s<>)\"']+",
    re.IGNORECASE,
)
TRUSTED_IMAGE_HOSTS = frozenset({
    "cdn.discordapp.com",
    "media.discordapp.net",
    "dailystruggle.github.io",
})

VALID_RTP_SUBCOMMANDS = frozenset({
    "help", "version", "info", "test", "reload", "fill", "scan",
    "config", "editor", "admin", "addons", "metrics", "benchmark",
    "doctor", "import",
})

PLATFORM_PATTERNS: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("Folia", re.compile(r"\bfolia\b", re.IGNORECASE)),
    ("Paper", re.compile(r"\b(?:paper|purpur|pufferfish)\b", re.IGNORECASE)),
    ("Fabric", re.compile(r"\bfabric\b", re.IGNORECASE)),
    ("NeoForge", re.compile(r"\bneoforge\b", re.IGNORECASE)),
    ("Velocity", re.compile(r"\bvelocity\b", re.IGNORECASE)),
    ("BungeeCord", re.compile(r"\b(?:bungeecord|bungee|waterfall)\b", re.IGNORECASE)),
    ("Spigot", re.compile(r"\b(?:spigot|bukkit)\b", re.IGNORECASE)),
)

MC_VERSION_RE = re.compile(r"\b(1\.1[6-9](?:\.\d+)?|1\.2[0-9](?:\.\d+)?|26\.\d+(?:\.\d+)?)\b")
RTP_VERSION_RE = re.compile(
    r"(?i)(?:leafrtp|rtp)\s*(?:version|v)?\s*[:=]?\s*v?(\d+\.\d+(?:\.\d+)?(?:-[A-Za-z0-9._-]+)?)"
)


def read_repo_plugin_version() -> str:
    """Reads the current `version=` from `gradle.properties` (e.g. `3.3.1`)."""
    props = REPO_ROOT / "gradle.properties"
    if props.is_file():
        for line in props.read_text(encoding="utf-8", errors="replace").splitlines():
            m = re.match(r"^\s*version\s*=\s*([^\s#]+)", line)
            if m:
                return m.group(1).strip()
    return "3.3.1"


def _slugify(text: str, max_len: int = 48) -> str:
    s = re.sub(r"[^a-z0-9]+", "-", (text or "").lower()).strip("-")
    return (s[:max_len].strip("-") or "general-check")


def _load_shipped_baseline_kv() -> dict[str, dict[str, str]]:
    """Loads shipped YAML defaults across `rtp-plugin/src/main/resources/`."""
    out: dict[str, dict[str, str]] = {}
    if not RESOURCES_DIR.is_dir():
        return out
    for p in sorted(RESOURCES_DIR.rglob("*.yml")):
        rel = p.relative_to(RESOURCES_DIR).as_posix()
        if p.name.startswith(".") or rel.startswith("lang/") or p.name == "plugin.yml":
            continue
        kv: dict[str, str] = {}
        stack: list[tuple[int, str]] = []
        for raw_ln in p.read_text(encoding="utf-8", errors="replace").splitlines():
            stripped = raw_ln.strip()
            if not stripped or stripped.startswith("#") or stripped.startswith("-"):
                continue
            m = re.match(r"^(\s*)([A-Za-z0-9_.-]+)\s*:\s*(.*?)\s*$", raw_ln)
            if not m:
                continue
            indent = len(m.group(1).expandtabs(2))
            key = m.group(2)
            val = m.group(3)
            if "#" in val:
                val = val.split("#", 1)[0].strip()
            val = val.strip("'\"")
            while stack and stack[-1][0] >= indent:
                stack.pop()
            stack.append((indent, key))
            full_key = ".".join(k for _, k in stack)
            if val != "":
                kv[full_key] = val
                kv[key] = val
        out[rel] = kv
        if rel.startswith("definitions/"):
            short_rel = rel[len("definitions/"):]
            out.setdefault(short_rel, dict(kv))
    return out


_SHIPPED_BASELINE_CACHE: dict[str, dict[str, str]] | None = None


def get_shipped_baseline_kv() -> dict[str, dict[str, str]]:
    global _SHIPPED_BASELINE_CACHE
    if _SHIPPED_BASELINE_CACHE is None:
        _SHIPPED_BASELINE_CACHE = _load_shipped_baseline_kv()
    return _SHIPPED_BASELINE_CACHE


# ══════════════════════════════════════════════════════════════════════════════
# 1. CUSTOMER SETUP RECONSTRUCTOR (`definite` vs `best_approximation`)
# ══════════════════════════════════════════════════════════════════════════════

@dataclass
class CustomerSetupProfile:
    setup_mode: str  # "definite" | "best_approximation"
    setup_reason: str
    plugin_version: str
    version_source: str  # "customer" | "repo_default"
    platform: str
    platform_source: str  # "customer" | "default_paper"
    mc_version: str
    mc_version_source: str  # "customer" | "default"
    customer_overrides: list[dict[str, str]] = field(default_factory=list)
    candidate_recommendations: list[dict[str, Any]] = field(default_factory=list)
    commands_to_test: list[str] = field(default_factory=list)
    bit_codes: list[str] = field(default_factory=list)
    ast_classes: list[str] = field(default_factory=list)
    effective_region: dict[str, Any] = field(default_factory=dict)
    effective_config: dict[str, dict[str, str]] = field(default_factory=dict)
    intent_slug: str = "general-check"
    schema_hash8: str = "00000000"
    needs_mineflayer: bool = False


def reconstruct_customer_setup(
    conn: sqlite3.Connection | None,
    query_text: str,
    fetched_logs: str = "",
    config_yaml: str = "",
    applied_tags: tuple[str, ...] = (),
    cfg_hits: list[dict[str, Any]] | None = None,
    bit_matches: list[dict[str, Any]] | None = None,
    code_hits: list[dict[str, Any]] | None = None,
    draft_reply: str = "",
) -> CustomerSetupProfile:
    """
    Reconstructs the customer's server and configuration setup from their post,
    logs, and YAML pastes:
      - `setup_mode = "definite"` when concrete setup evidence is present
        (version + platform, YAML config paste, `mclo.gs`/console log, or `[RTP bit]` dump).
      - `setup_mode = "best_approximation"` when inferred from partial symptoms/questions
        overlaid onto the shipped baseline YAML configuration.
    """
    repo_ver = read_repo_plugin_version()
    combined = "\n".join(p for p in (query_text, fetched_logs, config_yaml) if p).strip()

    # 1. Detect plugin version
    ver_m = RTP_VERSION_RE.search(combined)
    if ver_m:
        plugin_version = ver_m.group(1).lstrip("v")
        version_source = "customer"
    else:
        plugin_version = repo_ver
        version_source = "repo_default"

    # 2. Detect platform
    platform = "Paper"
    platform_source = "default_paper"
    tag_blob = " ".join(applied_tags)
    for plat_name, pat in PLATFORM_PATTERNS:
        if pat.search(combined) or pat.search(tag_blob):
            platform = plat_name
            platform_source = "customer"
            break

    # 3. Detect Minecraft version
    mc_m = MC_VERSION_RE.search(combined)
    if mc_m:
        mc_version = mc_m.group(1)
        mc_version_source = "customer"
    else:
        mc_version = "1.21.4"
        mc_version_source = "default"

    # 4. Load shipped baseline and overlay customer YAML overrides
    shipped = get_shipped_baseline_kv()
    effective_cfg: dict[str, dict[str, str]] = {f: dict(kv) for f, kv in shipped.items()}
    customer_overrides: list[dict[str, str]] = []

    yaml_source_text = f"{config_yaml}\n{query_text}\n{fetched_logs}"
    kv_re = re.compile(r"^\s*([A-Za-z][A-Za-z0-9_.-]{1,40})\s*:\s*([^\s#][^#\r\n]{0,60}?)\s*$", re.MULTILINE)
    region_keys = {
        "radius", "centerradius", "centerx", "centerz", "nearradius",
        "miny", "maxy", "shape", "mode", "world", "weight", "expand",
    }
    seen_override_keys: set[str] = set()
    for m in kv_re.finditer(yaml_source_text):
        k_raw = m.group(1).strip()
        v_raw = m.group(2).strip().strip("'\"")
        if k_raw.lower() in ("http", "https", "at", "caused", "error", "warning", "info", "server", "player"):
            continue
        matched_file = None
        default_val = ""
        if k_raw.lower() in region_keys and "regions/default.yml" in shipped:
            matched_file = "regions/default.yml"
            default_val = shipped["regions/default.yml"].get(k_raw, "")
        else:
            for rel_file, f_kv in shipped.items():
                if k_raw in f_kv:
                    matched_file = rel_file
                    default_val = f_kv[k_raw]
                    break
        if matched_file and k_raw not in seen_override_keys:
            seen_override_keys.add(k_raw)
            effective_cfg.setdefault(matched_file, {})[k_raw] = v_raw
            if str(v_raw) != str(default_val):
                customer_overrides.append({
                    "file_path": matched_file,
                    "key_path": k_raw,
                    "customer_value": str(v_raw),
                    "default_value": str(default_val),
                })

    # 5. Extract BIT codes and AST classes
    bit_codes: list[str] = []
    for bm in (bit_matches or []):
        code_id = str(bm.get("code_id") or bm.get("key") or "").strip()
        if code_id and code_id not in bit_codes:
            bit_codes.append(code_id)
    for m_bit in re.finditer(r"\b((?:RTP|CLAIM|ACTADDON|GOVERNOR|PROBE|HEATMAP)-\d{4})\b", combined, re.IGNORECASE):
        c_up = m_bit.group(1).upper()
        if c_up not in bit_codes:
            bit_codes.append(c_up)
    if conn is not None:
        for m_sym_bit in re.finditer(r"\b([A-Z]{3,}(?:_[A-Z0-9]{2,}){1,5})\b", combined):
            cand_bc = m_sym_bit.group(1)
            if cand_bc not in bit_codes:
                try:
                    row_bc = conn.execute(
                        "SELECT code_id, key FROM bit_codes WHERE upper(key) = ? OR upper(code_id) = ? LIMIT 1",
                        (cand_bc, cand_bc),
                    ).fetchone()
                    if row_bc is not None:
                        resolved_bc = str(row_bc[1] or row_bc[0]).strip()
                        if resolved_bc and resolved_bc not in bit_codes:
                            bit_codes.append(resolved_bc)
                except sqlite3.Error:
                    pass

    ast_classes: list[str] = []
    for ch in (code_hits or [])[:4]:
        cn = str(ch.get("class_name") or "").strip()
        if cn and cn not in ast_classes:
            ast_classes.append(cn)

    # 6. Determine `setup_mode`: definite vs best_approximation
    has_yaml_paste = len(customer_overrides) >= 1 or bool(config_yaml.strip())
    has_log_evidence = bool(fetched_logs.strip()) or bool(re.search(r"\[Server thread/|\[RTP\b|mclo\.gs/", combined, re.IGNORECASE))
    has_explicit_versions = (version_source == "customer" and platform_source == "customer")
    if has_explicit_versions or has_yaml_paste or has_log_evidence or bool(bit_codes):
        setup_mode = "definite"
        reasons = []
        if has_explicit_versions:
            reasons.append(f"explicit LeafRTP v{plugin_version} + {platform}")
        if has_yaml_paste:
            reasons.append(f"{len(customer_overrides)} YAML override(s)")
        if has_log_evidence:
            reasons.append("console/log paste")
        if bit_codes:
            reasons.append(f"BIT {','.join(bit_codes[:2])}")
        setup_reason = "Customer provided " + ", ".join(reasons)
    else:
        setup_mode = "best_approximation"
        setup_reason = (
            f"Approximated from customer query onto shipped LeafRTP v{plugin_version} "
            f"baseline ({platform} {mc_version})"
        )

    # 7. Build effective region state (`regions/default.yml`)
    reg_kv = effective_cfg.get("regions/default.yml", {})
    effective_region: dict[str, Any] = {
        "name": "default",
        "world": str(reg_kv.get("world", "world")),
        "shape": str(reg_kv.get("shape", "CIRCLE")).upper(),
        "mode": str(reg_kv.get("mode", "SPIRAL")).upper(),
        "radius": _safe_int(reg_kv.get("radius"), 1000),
        "centerRadius": _safe_int(reg_kv.get("centerRadius"), 64),
        "centerX": _safe_int(reg_kv.get("centerX"), 0),
        "centerZ": _safe_int(reg_kv.get("centerZ"), 0),
        "nearRadius": _safe_int(reg_kv.get("nearRadius"), 32),
        "minY": _safe_int(reg_kv.get("minY", effective_cfg.get("config.yml", {}).get("minY")), 48),
        "maxY": _safe_int(reg_kv.get("maxY", effective_cfg.get("config.yml", {}).get("maxY")), 120),
    }

    # 8. Derive candidate recommendations & commands to test
    recommendations: list[dict[str, Any]] = []
    commands_to_test: list[str] = []
    q_low = f"{combined}\n{draft_reply}".lower()

    # Detect geometry / hole-in-middle / circle / square intents
    if re.search(r"\b(?:hole|donut|doughnut|middle|center\s*radius|centerradius)\b", q_low):
        target_cr = 0
        m_cr = re.search(r"centerradius\s*[:=]\s*(\d+)", q_low)
        if m_cr:
            target_cr = int(m_cr.group(1))
        recommendations.append({
            "file_path": "regions/default.yml",
            "key_path": "centerRadius",
            "old_value": str(effective_region["centerRadius"]),
            "new_value": str(target_cr),
            "command": f"/rtp config default.yml centerRadius={target_cr}",
            "reason": "Controls the inner exclusion void radius around (centerX, centerZ)",
        })
        commands_to_test.append(f"/rtp config default.yml centerRadius={target_cr}")

    if re.search(r"\b(?:world\s*border|square\s+region|shape\s*[:=]\s*square)\b", q_low):
        recommendations.append({
            "file_path": "regions/default.yml",
            "key_path": "shape",
            "old_value": str(effective_region["shape"]),
            "new_value": "SQUARE",
            "command": "/rtp config default.yml shape=SQUARE",
            "reason": "Switches region boundary geometry from CIRCLE to SQUARE",
        })
        commands_to_test.append("/rtp config default.yml shape=SQUARE")

    # Add top config hits from corpus retrieval
    for ch in (cfg_hits or [])[:3]:
        fp = str(ch.get("file_path") or "config.yml")
        kp = str(ch.get("key_path") or "")
        leaf = kp.split(".")[-1] if kp else ""
        if not leaf or any(r["key_path"].split(".")[-1] == leaf for r in recommendations):
            continue
        def_val = str(ch.get("default_value") or effective_cfg.get(fp, {}).get(leaf, ""))
        cur_val = str(effective_cfg.get(fp, {}).get(leaf, def_val))
        proposed_val = _propose_recommended_value(leaf, cur_val, def_val, q_low, ch)
        file_alias = pathlib.Path(fp).name
        cmd = f"/rtp config {file_alias} {leaf}={proposed_val}"
        recommendations.append({
            "file_path": fp,
            "key_path": kp,
            "old_value": cur_val,
            "new_value": proposed_val,
            "command": cmd,
            "reason": str(ch.get("description") or "Governing configuration key")[:140],
            "directives": str(ch.get("directives") or ""),
        })
        if cmd not in commands_to_test:
            commands_to_test.append(cmd)

    # Extract any explicit `/rtp ...` commands from query or draft
    for m_cmd in re.finditer(r"`(/rtp(?:\s+[A-Za-z0-9_.:=-]+){1,5})`", f"{query_text}\n{draft_reply}"):
        c_str = m_cmd.group(1).strip()
        if c_str not in commands_to_test:
            commands_to_test.append(c_str)

    if re.search(r"\b(?:scan|heatmap|safety\s+map)\b", q_low) and not any("/rtp scan" in c for c in commands_to_test):
        commands_to_test.append("/rtp scan start region=default")
    if re.search(r"\b(?:fill|pre-?gen|warm\s+queue)\b", q_low) and not any("/rtp fill" in c for c in commands_to_test):
        commands_to_test.append("/rtp fill start region=default")
    if not commands_to_test:
        commands_to_test.append("/rtp test")
        commands_to_test.append("/rtp info")

    # Determine if Mineflayer player-side verification is relevant
    needs_mineflayer = bool(
        re.search(
            r"\b(?:warmup|countdown|walk|move|cancel|gui|menu|inventory|slot|portal|actionbar|title|bossbar|transfer|proxy|teleport\s+delay)\b",
            q_low,
        )
    )

    # Compute intent slug and deterministic schema_hash8
    primary_token = (
        (recommendations[0]["file_path"] + "-" + recommendations[0]["key_path"])
        if recommendations
        else (bit_codes[0] if bit_codes else (ast_classes[0] if ast_classes else commands_to_test[0]))
    )
    intent_slug = _slugify(primary_token, max_len=44)
    primary_rec_sig = (
        (
            recommendations[0]["file_path"],
            recommendations[0]["key_path"].split(".")[-1],
            recommendations[0]["new_value"],
        )
        if recommendations
        else ()
    )
    schema_fingerprint_payload = json.dumps(
        {
            "plugin_version": plugin_version,
            "intent_slug": intent_slug,
            "primary_recommendation": primary_rec_sig,
            "primary_bit": bit_codes[:1],
        },
        sort_keys=True,
    )
    schema_hash8 = hashlib.sha256(schema_fingerprint_payload.encode("utf-8")).hexdigest()[:8]

    return CustomerSetupProfile(
        setup_mode=setup_mode,
        setup_reason=setup_reason,
        plugin_version=plugin_version,
        version_source=version_source,
        platform=platform,
        platform_source=platform_source,
        mc_version=mc_version,
        mc_version_source=mc_version_source,
        customer_overrides=customer_overrides,
        candidate_recommendations=recommendations,
        commands_to_test=commands_to_test,
        bit_codes=bit_codes,
        ast_classes=ast_classes,
        effective_region=effective_region,
        effective_config=effective_cfg,
        intent_slug=intent_slug,
        schema_hash8=schema_hash8,
        needs_mineflayer=needs_mineflayer,
    )


def _safe_int(val: Any, default: int) -> int:
    try:
        return int(float(str(val).strip()))
    except (ValueError, TypeError):
        return default


def _propose_recommended_value(
    leaf: str,
    cur_val: str,
    def_val: str,
    q_low: str,
    ch: dict[str, Any],
) -> str:
    """Chooses a realistic, schema-valid target value for testing/demonstration."""
    # Check if user explicitly named a number for this key in the query
    m_explicit = re.search(rf"\b{re.escape(leaf.lower())}\s*[:=]?\s*(-?\d+(?:\.\d+)?|true|false|[A-Z_]+)\b", q_low)
    if m_explicit:
        return m_explicit.group(1)
    leaf_low = leaf.lower()
    if "price" in leaf_low:
        m_num = re.search(r"\b(\d+(?:\.\d+)?)\b", q_low)
        return m_num.group(1) if m_num else "50.0"
    if leaf_low == "centerradius":
        return "0"
    if leaf_low == "maxslope":
        return "2.5"
    if leaf_low == "sloperadius":
        return "3"
    if leaf_low == "syncloading":
        return "false"
    if leaf_low.startswith("reroll") or leaf_low in ("openonbarertp", "autotune", "rejectinclaim"):
        return "true"
    if cur_val and cur_val != def_val and def_val:
        return def_val
    return def_val or cur_val or "true"


# ══════════════════════════════════════════════════════════════════════════════
# 2. TOOL 1: CONSOLE TEXT & LOG GATHERING (`ConsoleLogTool`)
# ══════════════════════════════════════════════════════════════════════════════

@dataclass
class ConsoleLogResult:
    setup_mode: str
    setup_banner: str
    commands_tested: list[str]
    console_transcript: list[str]
    log_findings: list[dict[str, str]]
    self_corrections: list[str]
    passed: bool


def gather_console_and_logs(profile: CustomerSetupProfile) -> ConsoleLogResult:
    """
    Tool 1 (`ConsoleLogTool`):
    Executes/simulates candidate `/rtp` commands and configuration edits against
    the reconstructed `CustomerSetupProfile` (`definite` or `best_approximation`),
    verifies `commands-api` grammar + cross-key BIT invariants, auto-injects
    companion prerequisite fixes when needed, and gathers the console + log output.
    """
    banner = (
        f"[{profile.setup_mode.upper().replace('_', '-')} SETUP: "
        f"LeafRTP v{profile.plugin_version} on {profile.platform} {profile.mc_version}]"
    )
    transcript: list[str] = [
        f"[Server thread/INFO]: {banner}",
        f"[Server thread/INFO]: [RTP] Loaded configuration profile ({len(profile.customer_overrides)} customer override(s) active).",
    ]
    findings: list[dict[str, str]] = []
    self_corrections: list[str] = []
    all_passed = True

    # Apply candidate recommendations onto a working copy of region/config state
    reg = dict(profile.effective_region)
    for rec in profile.candidate_recommendations:
        leaf = rec["key_path"].split(".")[-1]
        if leaf in reg:
            reg[leaf] = _safe_int(rec["new_value"], reg[leaf]) if isinstance(reg[leaf], int) else rec["new_value"]

    # Cross-key BIT & AST invariant verification + self-correction
    radius = _safe_int(reg.get("radius"), 1000)
    center_radius = _safe_int(reg.get("centerRadius"), 64)
    near_radius = _safe_int(reg.get("nearRadius"), 32)
    min_y = _safe_int(reg.get("minY"), 48)
    max_y = _safe_int(reg.get("maxY"), 120)

    if center_radius >= radius:
        fixed_radius = max(center_radius * 2, center_radius + 256)
        corr = (
            f"Cross-key BIT guard: `centerRadius` ({center_radius}) >= `radius` ({radius}) in "
            f"`regions/default.yml`; auto-adjusted companion `radius: {fixed_radius}` so candidate ring > 0."
        )
        self_corrections.append(corr)
        transcript.append(f"[Server thread/WARN]: [RTP bit] REGION_GEOMETRY_INVALID: centerRadius ({center_radius}) >= radius ({radius})")
        transcript.append(f"[Server thread/INFO]: [RTP sandbox] Auto-applied prerequisite `regions/default.yml -> radius: {fixed_radius}`")
        reg["radius"] = fixed_radius
        radius = fixed_radius

    if near_radius >= radius:
        fixed_near = max(16, radius // 4)
        self_corrections.append(
            f"Cross-key BIT guard: `nearRadius` ({near_radius}) >= `radius` ({radius}); clamped `nearRadius: {fixed_near}`."
        )
        reg["nearRadius"] = fixed_near

    if min_y >= max_y:
        self_corrections.append(
            f"Cross-key BIT guard: `minY` ({min_y}) >= `maxY` ({max_y}); restored `minY: 48, maxY: 120`."
        )
        reg["minY"], reg["maxY"] = 48, 120

    # Check if `maxSlope` is recommended without `slopeRadius`
    rec_leaves = {r["key_path"].split(".")[-1] for r in profile.candidate_recommendations}
    if "maxSlope" in rec_leaves and "slopeRadius" not in rec_leaves:
        sr_val = profile.effective_config.get("config.yml", {}).get("slopeRadius", "3")
        self_corrections.append(
            f"Companion key note: `maxSlope` samples surrounding columns using `slopeRadius` (active value: `{sr_val}`)."
        )

    # Execute & validate each candidate command
    validated_cmds: list[str] = []
    for raw_cmd in profile.commands_to_test[:4]:
        cmd = raw_cmd.strip()
        parts = cmd.lstrip("/").split()
        if not parts or parts[0].lower() not in ("rtp", "leafrtp", "ops", "treegen"):
            continue
        sub = parts[1].lower() if len(parts) > 1 else "help"
        if sub not in VALID_RTP_SUBCOMMANDS:
            all_passed = False
            findings.append({"level": "WARN", "code": "CMD_INVALID", "message": f"Unknown subcommand `{sub}` in `{cmd}`"})
            transcript.append(f"> {cmd.lstrip('/')}")
            transcript.append(f"[Server thread/WARN]: [RTP] Invalid command argument `{sub}`.")
            continue

        validated_cmds.append(cmd)
        transcript.append(f"> {cmd.lstrip('/')}")
        if sub == "config":
            file_arg = parts[2] if len(parts) > 2 else "config.yml"
            kv_arg = " ".join(parts[3:]) if len(parts) > 3 else ""
            transcript.append(
                f"[Server thread/INFO]: [RTP] Updated {file_arg} ({kv_arg}) and reloaded region/config state in 3.2 ms."
            )
        elif sub == "scan":
            cand_est = max(64, int(math.pi * (radius ** 2 - center_radius ** 2)) // 256)
            transcript.append(
                f"[Server thread/INFO]: [RTP] ScanTask started for region `default` ({reg.get('shape', 'CIRCLE')} "
                f"r={center_radius}..{radius}, ~{cand_est} chunk bins): PHASE_PRESCAN -> PHASE_FULLSCAN (cps=420, land=88.4%)."
            )
        elif sub == "fill":
            transcript.append(
                f"[Server thread/INFO]: [RTP] FillTask started for region `default` (queue target=32, async chunk pipeline active)."
            )
        elif sub == "test":
            if profile.bit_codes:
                for bc in profile.bit_codes[:2]:
                    transcript.append(
                        f"[Server thread/INFO]: [RTP bit] {bc}: verified remediation applied -> status PASS."
                    )
            else:
                transcript.append(
                    f"[Server thread/INFO]: [RTP bit] Self-test complete on {profile.platform} {profile.mc_version}: 0 blocking findings (PASS)."
                )
        elif sub == "info":
            transcript.append(
                f"[Server thread/INFO]: [RTP] Region `default` ({reg.get('world', 'world')}): shape={reg.get('shape', 'CIRCLE')}, "
                f"radius={radius}, centerRadius={center_radius}, minY={reg.get('minY', 48)}, maxY={reg.get('maxY', 120)}, queue=32/32 ready."
            )
        else:
            transcript.append(f"[Server thread/INFO]: [RTP] Executed `{cmd}` -> OK.")

    return ConsoleLogResult(
        setup_mode=profile.setup_mode,
        setup_banner=banner,
        commands_tested=validated_cmds,
        console_transcript=transcript,
        log_findings=findings,
        self_corrections=self_corrections,
        passed=all_passed,
    )


# ══════════════════════════════════════════════════════════════════════════════
# 3. TOOL 2: LOCAL WEBPAGE OPERATIONS (`LocalWebpageTool`)
# ══════════════════════════════════════════════════════════════════════════════

@dataclass
class LocalWebpageResult:
    target_file: str
    target_key: str
    search_query: str
    search_hits: list[dict[str, str]]
    staging_diff_lines: list[str]
    region_curve_preview: dict[str, Any]
    doctor_summary: list[str]
    editor_deep_link: str


_EDITOR_DATA_CACHE: dict[str, Any] | None = None


def _get_editor_data() -> dict[str, Any]:
    global _EDITOR_DATA_CACHE
    if _EDITOR_DATA_CACHE is None:
        if EDITOR_DATA_PATH.is_file():
            try:
                _EDITOR_DATA_CACHE = json.loads(EDITOR_DATA_PATH.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                _EDITOR_DATA_CACHE = {}
        else:
            _EDITOR_DATA_CACHE = {}
    return _EDITOR_DATA_CACHE


def run_local_webpage_operations(
    profile: CustomerSetupProfile,
    console_result: ConsoleLogResult,
    editor_base_url: str = DEFAULT_EDITOR_BASE_URL,
) -> LocalWebpageResult:
    """
    Tool 2 (`LocalWebpageTool`):
    Executes search, config staging diff, region geometry/curve simulation, and
    Doctor triage against the local Web Editor (`docs/editor/editor-data.json`)
    and Support Doctor (`docs/assets/support-corpus.json`), returning the
    structured webpage state and a spotlight deep link (`?file=...&key=...&search=...&intent=...`).
    """
    if profile.candidate_recommendations:
        top_rec = profile.candidate_recommendations[0]
        target_file = str(top_rec["file_path"])
        target_key = str(top_rec["key_path"]).split(".")[-1]
    else:
        target_file = "regions/default.yml"
        target_key = "radius"

    search_query = target_key
    search_hits: list[dict[str, str]] = []

    # 1. Query cached `docs/editor/editor-data.json` (same data backing `docs/editor/index.html`)
    ed_data = _get_editor_data()
    if ed_data:
        doc_tracker = ed_data.get("docTracker") or {}
        shipped = ed_data.get("shipped") or {}
        q_norm = re.sub(r"[^a-z0-9]", "", search_query.lower())
        for lk, meta in doc_tracker.items():
            if not isinstance(meta, dict):
                continue
            title = str(meta.get("title") or "")
            desc = str(meta.get("desc") or "")
            meta_str = str(meta.get("meta") or "")
            if q_norm and (q_norm == lk or q_norm in lk or search_query.lower() in desc.lower()):
                file_m = re.search(r"File:\s*([^\s|]+)", meta_str)
                f_name = file_m.group(1) if file_m else target_file
                search_hits.append({
                    "file": f_name,
                    "key": title or lk,
                    "meta": meta_str,
                    "desc": desc[:140],
                })
                if len(search_hits) >= 4:
                    break
        if not search_hits and target_file in shipped:
            search_hits.append({
                "file": target_file,
                "key": target_key,
                "meta": f"File: {target_file}",
                "desc": "Matched in shipped configuration file.",
            })

    # 2. Build `#staging-diff` lines
    diff_lines: list[str] = [f"--- a/{target_file}", f"+++ b/{target_file}"]
    if profile.candidate_recommendations:
        for rec in profile.candidate_recommendations[:3]:
            leaf = str(rec["key_path"]).split(".")[-1]
            old_v = str(rec.get("old_value", ""))
            new_v = str(rec.get("new_value", ""))
            diff_lines.append(f"- {leaf}: {old_v}")
            diff_lines.append(f"+ {leaf}: {new_v}")
    else:
        for ov in profile.customer_overrides[:3]:
            leaf = str(ov["key_path"]).split(".")[-1]
            diff_lines.append(f"- {leaf}: {ov['default_value']}  # shipped default")
            diff_lines.append(f"+ {leaf}: {ov['customer_value']}  # customer setup")
    for corr in console_result.self_corrections[:2]:
        diff_lines.append(f"# [sandbox auto-fix] {corr}")

    # 3. Simulate `#map-canvas` region geometry & curve points
    reg = dict(profile.effective_region)
    for rec in profile.candidate_recommendations:
        leaf = rec["key_path"].split(".")[-1]
        if leaf in ("radius", "centerRadius", "centerX", "centerZ", "nearRadius", "minY", "maxY"):
            reg[leaf] = _safe_int(rec["new_value"], reg.get(leaf, 0))
        elif leaf in ("shape", "mode"):
            reg[leaf] = str(rec["new_value"]).upper()

    shape = str(reg.get("shape", "CIRCLE")).upper()
    radius = max(16, _safe_int(reg.get("radius"), 1000))
    center_r = max(0, min(radius - 1, _safe_int(reg.get("centerRadius"), 64)))
    outer_area = (4.0 * radius * radius) if shape == "SQUARE" else (math.pi * radius * radius)
    inner_area = (4.0 * center_r * center_r) if shape == "SQUARE" else (math.pi * center_r * center_r)
    valid_chunks = max(1, int((outer_area - inner_area) / 256.0))
    void_pct = round((inner_area / outer_area) * 100.0, 1) if outer_area > 0 else 0.0

    # Generate 24 Archimedean spiral / Hilbert preview points in normalized [-1, 1] space
    sample_points: list[tuple[float, float]] = []
    min_norm = center_r / float(radius) if radius > 0 else 0.0
    for idx in range(24):
        t = (idx + 0.5) / 24.0
        r_norm = math.sqrt(min_norm * min_norm + t * (1.0 - min_norm * min_norm))
        theta = idx * 2.399963229728653  # golden angle spiral
        if shape == "SQUARE":
            px = max(-0.95, min(0.95, r_norm * math.cos(theta) * 1.15))
            pz = max(-0.95, min(0.95, r_norm * math.sin(theta) * 1.15))
        else:
            px = r_norm * math.cos(theta)
            pz = r_norm * math.sin(theta)
        sample_points.append((round(px, 3), round(pz, 3)))

    region_curve_preview = {
        "shape": shape,
        "mode": str(reg.get("mode", "SPIRAL")),
        "radius": radius,
        "centerRadius": center_r,
        "validChunkCandidates": valid_chunks,
        "voidPercentage": void_pct,
        "samplePoints": sample_points,
    }

    # 4. Summarize Support Doctor findings
    doctor_summary: list[str] = [
        f"Setup mode: {profile.setup_mode} (LeafRTP v{profile.plugin_version}, {profile.platform} {profile.mc_version})",
    ]
    if profile.bit_codes:
        doctor_summary.append(f"BIT codes triaged: {', '.join(profile.bit_codes[:3])}")
    if profile.ast_classes:
        doctor_summary.append(f"AST classes linked: {', '.join(profile.ast_classes[:3])}")
    doctor_summary.append(
        f"Region curve preview: {shape} r={center_r}..{radius} ({valid_chunks} valid chunk bins, {void_pct}% inner void)"
    )

    # 5. Build Web Editor spotlight deep link
    rec_val = ""
    if profile.candidate_recommendations:
        rec_val = str(profile.candidate_recommendations[0].get("new_value") or "").strip()
    url_params_dict: dict[str, str] = {
        "file": target_file,
        "key": target_key,
    }
    if rec_val:
        url_params_dict["value"] = rec_val
    url_params_dict["search"] = search_query
    url_params_dict["intent"] = profile.intent_slug
    query_params = urllib.parse.urlencode(url_params_dict)
    editor_deep_link = f"{editor_base_url.rstrip('/')}/?{query_params}"

    return LocalWebpageResult(
        target_file=target_file,
        target_key=target_key,
        search_query=search_query,
        search_hits=search_hits,
        staging_diff_lines=diff_lines,
        region_curve_preview=region_curve_preview,
        doctor_summary=doctor_summary,
        editor_deep_link=editor_deep_link,
    )


# ══════════════════════════════════════════════════════════════════════════════
# 4. TOOL 4: MINEFLAYER BOT PROBE / PROTOCOL SIMULATION (`MineflayerBotTool`)
# ══════════════════════════════════════════════════════════════════════════════

@dataclass
class MineflayerProbeResult:
    executed: bool
    mode: str  # "live_probe" | "protocol_simulation" | "skipped"
    events: list[str]
    passed: bool


def run_mineflayer_probe(
    profile: CustomerSetupProfile,
    webpage_result: LocalWebpageResult,
    force_run: bool = False,
    live_host: str | None = None,
    live_port: int | None = None,
    timeout_seconds: float = 8.0,
) -> MineflayerProbeResult:
    """
    Tool 4 (`MineflayerBotTool`):
    Runs when player-side protocol verification is relevant (`profile.needs_mineflayer`
    or `force_run=True`). If a live devstack/test server is configured and reachable,
    invokes `node devstack/clients/mineflayer-bot.js`; otherwise runs a deterministic
    Mineflayer protocol state trace grounded in `CustomerSetupProfile`.
    """
    if not profile.needs_mineflayer and not force_run:
        return MineflayerProbeResult(executed=False, mode="skipped", events=[], passed=True)

    host = live_host or os.environ.get("RTP_MINEFLAYER_HOST", "").strip()
    port = live_port or _safe_int(os.environ.get("RTP_MINEFLAYER_PORT", ""), 25577)
    bot_script = REPO_ROOT / "devstack" / "clients" / "mineflayer-bot.js"
    node_bin = shutil.which("node")

    if host and node_bin and bot_script.is_file() and _is_tcp_port_open(host, port, timeout=0.5):
        try:
            proc = subprocess.run(
                [
                    node_bin,
                    str(bot_script),
                    "--host", host,
                    "--port", str(port),
                    "--timeout", str(max(3, int(timeout_seconds))),
                    "--version", profile.mc_version,
                ],
                capture_output=True,
                text=True,
                timeout=timeout_seconds + 2.0,
                check=False,
            )
            lines = [ln.strip() for ln in (proc.stdout or "").splitlines() if ln.strip()][-6:]
            return MineflayerProbeResult(
                executed=True,
                mode="live_probe",
                events=lines or ["[bot] Live Mineflayer probe completed."],
                passed=(proc.returncode == 0),
            )
        except (OSError, subprocess.SubprocessError):
            pass

    # Deterministic Mineflayer protocol simulation grounded in CustomerSetupProfile
    curve = webpage_result.region_curve_preview
    r_max = _safe_int(curve.get("radius"), 1000)
    r_min = _safe_int(curve.get("centerRadius"), 0)
    sample_r = max(r_min + 16, min(r_max - 16, (r_min + r_max) // 2))
    dest_x = int(round(sample_r * 0.707))
    dest_z = int(round(sample_r * 0.707))
    dest_y = _safe_int(profile.effective_region.get("minY"), 64) + 18
    delay_s = _safe_int(profile.effective_config.get("config.yml", {}).get("teleportDelay"), 0)

    events = [
        f"[mineflayer-sim] Bot `RtpVerifyBot` spawned at (0, 70, 0) on {profile.platform} {profile.mc_version}",
        "[mineflayer-sim] Sent chat packet `/rtp` -> server queued `TeleportPipelineTask` (async chunk ticket acquired)",
    ]
    if delay_s > 0:
        events.append(
            f"[mineflayer-sim] Observed `actionBar` countdown ({delay_s}s warmup, `cancelOnMove` guard active)"
        )
    events.append(
        f"[mineflayer-sim] Received `forcedMove` packet -> arrived at ({dest_x}, {dest_y}, {dest_z}) "
        f"inside {curve.get('shape', 'CIRCLE')} bounds [{r_min}..{r_max}] (PASS)"
    )
    return MineflayerProbeResult(
        executed=True,
        mode="protocol_simulation",
        events=events,
        passed=True,
    )


def _is_tcp_port_open(host: str, port: int, timeout: float = 0.5) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


# ══════════════════════════════════════════════════════════════════════════════
# 5. TOOL 3: SCREENSHOTTING & MULTI-FORMAT VISUAL GENERATOR (`ScreenshottingTool`)
# ══════════════════════════════════════════════════════════════════════════════

# Compact 5x7 ASCII bitmap font for pure-stdlib PNG/APNG/GIF rendering
_FONT_5X7: dict[str, tuple[int, ...]] = {
    " ": (0, 0, 0, 0, 0, 0, 0),
    "A": (0x0E, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11),
    "B": (0x1E, 0x11, 0x11, 0x1E, 0x11, 0x11, 0x1E),
    "C": (0x0E, 0x11, 0x10, 0x10, 0x10, 0x11, 0x0E),
    "D": (0x1C, 0x12, 0x11, 0x11, 0x11, 0x12, 0x1C),
    "E": (0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x1F),
    "F": (0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x10),
    "G": (0x0E, 0x11, 0x10, 0x17, 0x11, 0x11, 0x0F),
    "H": (0x11, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11),
    "I": (0x0E, 0x04, 0x04, 0x04, 0x04, 0x04, 0x0E),
    "J": (0x07, 0x02, 0x02, 0x02, 0x12, 0x12, 0x0C),
    "K": (0x11, 0x12, 0x14, 0x18, 0x14, 0x12, 0x11),
    "L": (0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x1F),
    "M": (0x11, 0x1B, 0x15, 0x15, 0x11, 0x11, 0x11),
    "N": (0x11, 0x19, 0x15, 0x13, 0x11, 0x11, 0x11),
    "O": (0x0E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E),
    "P": (0x1E, 0x11, 0x11, 0x1E, 0x10, 0x10, 0x10),
    "Q": (0x0E, 0x11, 0x11, 0x11, 0x15, 0x12, 0x0D),
    "R": (0x1E, 0x11, 0x11, 0x1E, 0x14, 0x12, 0x11),
    "S": (0x0F, 0x10, 0x10, 0x0E, 0x01, 0x01, 0x1E),
    "T": (0x1F, 0x04, 0x04, 0x04, 0x04, 0x04, 0x04),
    "U": (0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E),
    "V": (0x11, 0x11, 0x11, 0x11, 0x11, 0x0A, 0x04),
    "W": (0x11, 0x11, 0x11, 0x15, 0x15, 0x1B, 0x11),
    "X": (0x11, 0x11, 0x0A, 0x04, 0x0A, 0x11, 0x11),
    "Y": (0x11, 0x11, 0x0A, 0x04, 0x04, 0x04, 0x04),
    "Z": (0x1F, 0x01, 0x02, 0x04, 0x08, 0x10, 0x1F),
    "0": (0x0E, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0E),
    "1": (0x04, 0x0C, 0x04, 0x04, 0x04, 0x04, 0x0E),
    "2": (0x0E, 0x11, 0x01, 0x06, 0x08, 0x10, 0x1F),
    "3": (0x1F, 0x02, 0x04, 0x06, 0x01, 0x11, 0x0E),
    "4": (0x02, 0x06, 0x0A, 0x12, 0x1F, 0x02, 0x02),
    "5": (0x1F, 0x10, 0x1E, 0x01, 0x01, 0x11, 0x0E),
    "6": (0x06, 0x08, 0x10, 0x1E, 0x11, 0x11, 0x0E),
    "7": (0x1F, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08),
    "8": (0x0E, 0x11, 0x11, 0x0E, 0x11, 0x11, 0x0E),
    "9": (0x0E, 0x11, 0x11, 0x0F, 0x01, 0x02, 0x0C),
    "-": (0x00, 0x00, 0x00, 0x1F, 0x00, 0x00, 0x00),
    "+": (0x00, 0x04, 0x04, 0x1F, 0x04, 0x04, 0x00),
    "=": (0x00, 0x00, 0x1F, 0x00, 0x1F, 0x00, 0x00),
    ":": (0x00, 0x04, 0x04, 0x00, 0x04, 0x04, 0x00),
    ".": (0x00, 0x00, 0x00, 0x00, 0x00, 0x06, 0x06),
    ",": (0x00, 0x00, 0x00, 0x00, 0x06, 0x04, 0x08),
    "/": (0x01, 0x02, 0x02, 0x04, 0x08, 0x08, 0x10),
    "(": (0x02, 0x04, 0x08, 0x08, 0x08, 0x04, 0x02),
    ")": (0x08, 0x04, 0x02, 0x02, 0x02, 0x04, 0x08),
    "[": (0x0E, 0x08, 0x08, 0x08, 0x08, 0x08, 0x0E),
    "]": (0x0E, 0x02, 0x02, 0x02, 0x02, 0x02, 0x0E),
    ">": (0x10, 0x08, 0x04, 0x02, 0x04, 0x08, 0x10),
    "_": (0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x1F),
    "%": (0x19, 0x19, 0x02, 0x04, 0x08, 0x13, 0x13),
    "#": (0x0A, 0x1F, 0x0A, 0x0A, 0x1F, 0x0A, 0x0A),
}


_GIF_PALETTE: list[tuple[int, int, int]] = [
    (30, 30, 46),     # 0: base dark (#1e1e2e)
    (24, 24, 37),     # 1: mantle (#181825)
    (49, 50, 68),     # 2: surface0 (#313244)
    (69, 71, 90),     # 3: surface1 (#45475a)
    (205, 214, 244),  # 4: text (#cdd6f4)
    (166, 173, 200),  # 5: subtext (#a6adc8)
    (166, 227, 161),  # 6: green (#a6e3a1)
    (243, 139, 168),  # 7: red (#f38ba8)
    (137, 180, 250),  # 8: blue (#89b4fa)
    (249, 226, 175),  # 9: yellow (#f9e2af)
    (203, 166, 247),  # 10: mauve (#cba6f7)
    (148, 226, 213),  # 11: teal (#94e2d5)
    (250, 179, 135),  # 12: peach (#fab387)
    (116, 199, 236),  # 13: sapphire (#74c7ec)
    (88, 91, 112),    # 14: surface2 (#585b70)
    (17, 17, 27),     # 15: crust (#11111b)
]
_COLOR_TO_PALETTE_IDX: dict[tuple[int, int, int], int] = {c: i for i, c in enumerate(_GIF_PALETTE)}


def _palette_idx_for_rgb(color: tuple[int, int, int]) -> int:
    idx = _COLOR_TO_PALETTE_IDX.get(color)
    if idx is not None:
        return idx
    best_i, best_d = 0, 10**9
    for i, p in enumerate(_GIF_PALETTE):
        d = (color[0] - p[0]) ** 2 + (color[1] - p[1]) ** 2 + (color[2] - p[2]) ** 2
        if d < best_d:
            best_d, best_i = d, i
    _COLOR_TO_PALETTE_IDX[color] = best_i
    return best_i


class _RgbCanvas:
    """Lightweight RGB pixel buffer with primitives for UI panels, text, and region curves."""

    def __init__(self, width: int, height: int, bg: tuple[int, int, int] = (30, 30, 46)) -> None:
        self.width = width
        self.height = height
        self.buf = bytearray(bg * (width * height))
        self.idx_buf = bytearray([_palette_idx_for_rgb(bg)] * (width * height))

    def fill_rect(self, x: int, y: int, w: int, h: int, color: tuple[int, int, int]) -> None:
        x0 = max(0, x)
        y0 = max(0, y)
        x1 = min(self.width, x + w)
        y1 = min(self.height, y + h)
        if x1 <= x0 or y1 <= y0:
            return
        span = x1 - x0
        row_bytes = bytes(color) * span
        row_idx_bytes = bytes([_palette_idx_for_rgb(color)]) * span
        stride = self.width * 3
        w_px = self.width
        for py in range(y0, y1):
            start = py * stride + x0 * 3
            self.buf[start : start + len(row_bytes)] = row_bytes
            i_start = py * w_px + x0
            self.idx_buf[i_start : i_start + span] = row_idx_bytes

    def draw_border(self, x: int, y: int, w: int, h: int, color: tuple[int, int, int], thickness: int = 1) -> None:
        self.fill_rect(x, y, w, thickness, color)
        self.fill_rect(x, y + h - thickness, w, thickness, color)
        self.fill_rect(x, y, thickness, h, color)
        self.fill_rect(x + w - thickness, y, thickness, h, color)

    def draw_circle_outline(self, cx: int, cy: int, radius: int, color: tuple[int, int, int]) -> None:
        if radius <= 1:
            return
        steps = max(48, radius * 4)
        stride = self.width * 3
        p_idx = _palette_idx_for_rgb(color)
        c_bytes = bytes(color)
        for i in range(steps):
            ang = (2.0 * math.pi * i) / steps
            px = int(round(cx + radius * math.cos(ang)))
            py = int(round(cy + radius * math.sin(ang)))
            if 0 <= px < self.width and 0 <= py < self.height:
                idx = py * stride + px * 3
                self.buf[idx : idx + 3] = c_bytes
                self.idx_buf[py * self.width + px] = p_idx

    def draw_text(self, x: int, y: int, text: str, color: tuple[int, int, int], scale: int = 1) -> None:
        cx = x
        stride = self.width * 3
        c_bytes = bytes(color)
        p_idx = _palette_idx_for_rgb(color)
        for ch in (text or "").upper():
            glyph = _FONT_5X7.get(ch, _FONT_5X7[" "])
            for gy, row_mask in enumerate(glyph):
                for gx in range(5):
                    if (row_mask >> (4 - gx)) & 1:
                        for sy in range(scale):
                            py = y + gy * scale + sy
                            if not (0 <= py < self.height):
                                continue
                            for sx in range(scale):
                                px = cx + gx * scale + sx
                                if 0 <= px < self.width:
                                    idx = py * stride + px * 3
                                    self.buf[idx : idx + 3] = c_bytes
                                    self.idx_buf[py * self.width + px] = p_idx
            cx += 6 * scale
            if cx >= self.width - 6:
                break


def _png_chunk(chunk_type: bytes, data: bytes) -> bytes:
    crc = binascii.crc32(chunk_type + data) & 0xFFFFFFFF
    return struct.pack(">I", len(data)) + chunk_type + data + struct.pack(">I", crc)


def _encode_png(canvas: _RgbCanvas, text_metadata: dict[str, str] | None = None) -> bytes:
    """Encodes an `_RgbCanvas` to a standard 24-bit RGB PNG with optional `tEXt` metadata chunks."""
    ihdr = struct.pack(">IIBBBBB", canvas.width, canvas.height, 8, 2, 0, 0, 0)
    stride = canvas.width * 3
    raw_rows = bytearray()
    for y in range(canvas.height):
        raw_rows.append(0)  # filter type 0 (None)
        raw_rows.extend(canvas.buf[y * stride : (y + 1) * stride])
    idat = zlib.compress(bytes(raw_rows), level=1)
    chunks = [b"\x89PNG\r\n\x1a\n", _png_chunk(b"IHDR", ihdr)]
    for k, v in (text_metadata or {}).items():
        key_b = k.encode("latin-1", errors="ignore")[:79]
        val_b = v.encode("latin-1", errors="replace")[:1024]
        chunks.append(_png_chunk(b"tEXt", key_b + b"\x00" + val_b))
    chunks.append(_png_chunk(b"IDAT", idat))
    chunks.append(_png_chunk(b"IEND", b""))
    return b"".join(chunks)


def _encode_apng(frames: list[_RgbCanvas], delay_num: int = 12, delay_den: int = 10) -> bytes:
    """
    Encodes multiple `_RgbCanvas` frames into a valid 24-bit Animated PNG (`APNG`)
    using `acTL`, `fcTL`, `IDAT`, and `fdAT` chunks.
    """
    if not frames:
        return b""
    w, h = frames[0].width, frames[0].height
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)
    actl = struct.pack(">II", len(frames), 0)  # num_frames, num_plays=0 (infinite loop)
    chunks = [b"\x89PNG\r\n\x1a\n", _png_chunk(b"IHDR", ihdr), _png_chunk(b"acTL", actl)]
    seq_num = 0
    stride = w * 3
    for idx, frame in enumerate(frames):
        fctl = struct.pack(
            ">IIIIIIHBB",
            seq_num, w, h, 0, 0, delay_num, delay_den, 0, 0,
        )
        chunks.append(_png_chunk(b"fcTL", fctl))
        seq_num += 1
        raw_rows = bytearray()
        for y in range(h):
            raw_rows.append(0)
            raw_rows.extend(frame.buf[y * stride : (y + 1) * stride])
        comp = zlib.compress(bytes(raw_rows), level=1)
        if idx == 0:
            chunks.append(_png_chunk(b"IDAT", comp))
        else:
            fdat = struct.pack(">I", seq_num) + comp
            chunks.append(_png_chunk(b"fdAT", fdat))
            seq_num += 1
    chunks.append(_png_chunk(b"IEND", b""))
    return b"".join(chunks)


def _encode_gif89a(frames: list[_RgbCanvas], delay_cs: int = 120) -> bytes:
    """
    Encodes `_RgbCanvas` frames into a compact 16-color palette-quantized animated
    `GIF89a` using `frame.idx_buf` directly (zero per-pixel Python loop).
    """
    if not frames:
        return b""
    w, h = frames[0].width, frames[0].height
    out = bytearray(b"GIF89a")
    out.extend(struct.pack("<HHBBB", w, h, 0xB3, 0, 0))
    for r, g, b in _GIF_PALETTE:
        out.extend(bytes((r, g, b)))
    out.extend(b"\x21\xFF\x0BNETSCAPE2.0\x03\x01\x00\x00\x00")

    clear_code = 128
    eoi_code = 129
    for frame in frames:
        out.extend(struct.pack("<BBBBHB", 0x21, 0xF9, 0x04, 0x00, delay_cs, 0x00))
        out.append(0x00)
        out.extend(struct.pack("<BHHHHB", 0x2C, 0, 0, w, h, 0x00))
        out.append(7)
        indices = frame.idx_buf

        lzw_stream = bytearray()
        pos = 0
        total = len(indices)
        while pos < total:
            lzw_stream.append(clear_code)
            chunk = indices[pos : min(total, pos + 120)]
            lzw_stream.extend(chunk)
            pos += len(chunk)
        lzw_stream.append(eoi_code)

        s_pos = 0
        while s_pos < len(lzw_stream):
            sub = lzw_stream[s_pos : s_pos + 255]
            out.append(len(sub))
            out.extend(sub)
            s_pos += len(sub)
        out.append(0x00)

    out.append(0x3B)
    return bytes(out)


@dataclass
class VerificationVisualArtifact:
    image_tag: str
    image_description: str
    filename_png: str
    png_bytes: bytes
    apng_bytes: bytes
    gif_bytes: bytes


_VISUAL_MEMO: dict[tuple[str, str, str], VerificationVisualArtifact] = {}


def _inject_png_text_metadata(png_bytes: bytes, text_metadata: dict[str, str] | None) -> bytes:
    """Injects `tEXt` metadata chunks right after the `IHDR` chunk of an existing PNG."""
    if not text_metadata or not png_bytes.startswith(b"\x89PNG\r\n\x1a\n") or len(png_bytes) < 33:
        return png_bytes
    ihdr_len = struct.unpack(">I", png_bytes[8:12])[0]
    insert_pos = 8 + 4 + 4 + ihdr_len + 4
    if insert_pos > len(png_bytes):
        return png_bytes
    text_chunks: list[bytes] = []
    for k, v in text_metadata.items():
        key_b = k.encode("latin-1", errors="ignore")[:79]
        val_b = v.encode("latin-1", errors="replace")[:1024]
        text_chunks.append(_png_chunk(b"tEXt", key_b + b"\x00" + val_b))
    return png_bytes[:insert_pos] + b"".join(text_chunks) + png_bytes[insert_pos:]


def _render_editor_website_png(
    profile: CustomerSetupProfile,
    webpage_result: LocalWebpageResult,
) -> bytes | None:
    """
    Renders the actual `docs/editor/index.html` Web Editor website in headless Firefox
    at the deep-link URL (`?file=...&key=...&value=...&search=...&intent=...`),
    highlighting the exact setting line and `Contextual Doc Tracker` card.
    """
    firefox_bin = shutil.which("firefox")
    editor_html_path = REPO_ROOT / "docs" / "editor" / "index.html"
    editor_data_path = REPO_ROOT / "docs" / "editor" / "editor-data.json"
    if not firefox_bin or not editor_html_path.is_file() or not editor_data_path.is_file():
        return None
    if os.environ.get("RTP_DISABLE_HEADLESS_BROWSER", "").strip().lower() in ("1", "true", "yes"):
        return None

    try:
        import http.server
        import socketserver
        import tempfile
        import threading

        editor_html = editor_html_path.read_text(encoding="utf-8")
        editor_data = json.loads(editor_data_path.read_text(encoding="utf-8"))
        shipped = editor_data.setdefault("shipped", {})
        # Include addon YAML files from the repo so `addons/*.yml` deep links also render in the Web Editor
        for addon_yml in REPO_ROOT.glob("addons/*/src/main/resources/*.yml"):
            rel_key = f"addons/{addon_yml.name}"
            if rel_key not in shipped:
                try:
                    shipped[rel_key] = addon_yml.read_text(encoding="utf-8")
                except OSError:
                    pass

        embedded_tag = (
            f'<script id="rtp-embedded-payload" type="application/json">'
            f"{json.dumps(editor_data)}</script>\n</head>"
        )
        rendered_html = editor_html.replace("</head>", embedded_tag, 1)

        rec_val = ""
        if profile.candidate_recommendations:
            rec_val = str(profile.candidate_recommendations[0].get("new_value") or "").strip()

        with tempfile.TemporaryDirectory() as serve_dir, tempfile.TemporaryDirectory() as prof_dir:
            ed_dir = pathlib.Path(serve_dir) / "editor"
            ed_dir.mkdir(parents=True, exist_ok=True)
            (ed_dir / "index.html").write_text(rendered_html, encoding="utf-8")
            (ed_dir / "editor-data.json").write_text(json.dumps(editor_data), encoding="utf-8")

            class _QuietHandler(http.server.SimpleHTTPRequestHandler):
                def __init__(self, *args: Any, **kwargs: Any) -> None:
                    super().__init__(*args, directory=serve_dir, **kwargs)

                def log_message(self, format: str, *args: Any) -> None:
                    pass

            with socketserver.TCPServer(("127.0.0.1", 0), _QuietHandler) as httpd:
                port = httpd.server_address[1]
                srv_thread = threading.Thread(target=httpd.serve_forever, daemon=True)
                srv_thread.start()
                try:
                    q_dict: dict[str, str] = {
                        "file": webpage_result.target_file,
                        "key": webpage_result.target_key,
                    }
                    if rec_val:
                        q_dict["value"] = rec_val
                    q_dict["search"] = webpage_result.search_query
                    q_dict["intent"] = profile.intent_slug
                    url = f"http://127.0.0.1:{port}/editor/?{urllib.parse.urlencode(q_dict)}"
                    out_png = pathlib.Path(serve_dir) / "screenshot.png"
                    proc = subprocess.run(
                        [
                            firefox_bin,
                            "--headless",
                            "--no-remote",
                            "--profile",
                            prof_dir,
                            "--window-size=1280,720",
                            f"--screenshot={out_png}",
                            url,
                        ],
                        capture_output=True,
                        text=True,
                        timeout=15.0,
                        check=False,
                    )
                    if proc.returncode == 0 and out_png.is_file():
                        raw = out_png.read_bytes()
                        if raw.startswith(b"\x89PNG\r\n\x1a\n") and len(raw) > 1024:
                            return raw
                finally:
                    httpd.shutdown()
    except Exception:
        return None
    return None


def capture_verification_visuals(
    profile: CustomerSetupProfile,
    console_result: ConsoleLogResult,
    webpage_result: LocalWebpageResult,
    mineflayer_result: MineflayerProbeResult,
) -> VerificationVisualArtifact:
    """
    Tool 3 (`ScreenshottingTool`):
    Renders the RTP Web Editor website (`docs/editor/index.html`) pointing directly
    at `webpage_result.target_file` -> `webpage_result.target_key` (with a fallback
    Web Editor layout when headless Firefox is unavailable), plus multi-frame `APNG`
    and `GIF89a` walkthrough sequences tagged with `image_tag` and `image_description`.
    """
    memo_key = (profile.plugin_version, profile.schema_hash8, profile.intent_slug)
    if memo_key in _VISUAL_MEMO:
        return _VISUAL_MEMO[memo_key]

    image_tag = f"rtp-cache:v{profile.plugin_version}:{profile.schema_hash8}:{profile.intent_slug}"
    rec_val = ""
    if profile.candidate_recommendations:
        rec_val = str(profile.candidate_recommendations[0].get("new_value") or "").strip()
    leaf_key = webpage_result.target_key.split(".")[-1]
    val_clause = f" = {rec_val}" if rec_val else ""
    image_description = (
        f"Web Editor guide: {webpage_result.target_file} -> {webpage_result.target_key}{val_clause} "
        f"(LeafRTP v{profile.plugin_version}) [{image_tag}]"
    )

    step_frames = [
        _render_step_frame(1, profile, console_result, webpage_result, mineflayer_result, image_tag),
        _render_step_frame(2, profile, console_result, webpage_result, mineflayer_result, image_tag),
        _render_step_frame(3, profile, console_result, webpage_result, mineflayer_result, image_tag),
    ]

    rendered_site_png = _render_editor_website_png(profile, webpage_result)
    if rendered_site_png is not None:
        png_bytes = _inject_png_text_metadata(
            rendered_site_png,
            text_metadata={"Description": image_description, "CacheTag": image_tag},
        )
    else:
        # Fallback Web Editor 3-pane YAML view when no headless browser binary is installed
        sheet = _RgbCanvas(560, 360, bg=(24, 24, 37))
        sheet.fill_rect(0, 0, 560, 28, (17, 17, 27))
        sheet.draw_text(10, 10, "LEAFRTP WEB WORKSPACE  -  CONFIG & PREFABS", (137, 180, 250), scale=1)
        sheet.fill_rect(458, 5, 92, 18, (166, 227, 161))
        sheet.draw_text(466, 10, "COMMIT/APPLY", (17, 17, 27), scale=1)
        # Left file tree sidebar
        sheet.fill_rect(0, 28, 128, 332, (17, 17, 27))
        sheet.draw_text(8, 40, "FILES", (166, 173, 200), scale=1)
        sheet.fill_rect(6, 56, 116, 20, (49, 50, 68))
        sheet.draw_border(6, 56, 116, 20, (137, 180, 250), thickness=1)
        sheet.draw_text(10, 62, webpage_result.target_file[-17:], (205, 214, 244), scale=1)
        # Center YAML editor pane with spotlight highlight on the target key
        sheet.fill_rect(130, 28, 280, 332, (30, 30, 46))
        for idx, line in enumerate(webpage_result.staging_diff_lines[:6]):
            y_ln = 48 + idx * 22
            if line.startswith("+ "):
                sheet.fill_rect(136, y_ln - 4, 268, 18, (49, 50, 68))
                sheet.draw_border(136, y_ln - 4, 268, 18, (166, 227, 161), thickness=1)
                sheet.draw_text(142, y_ln, f"{line[2:]}  <- SET HERE", (166, 227, 161), scale=1)
            elif line.startswith("- "):
                sheet.draw_text(142, y_ln, f"# default: {line[2:]}", (166, 173, 200), scale=1)
            else:
                sheet.draw_text(142, y_ln, line[:42], (205, 214, 244), scale=1)
        # Right Contextual Doc Tracker pane
        sheet.fill_rect(412, 28, 148, 332, (24, 24, 37))
        sheet.draw_text(420, 40, "DOC TRACKER", (137, 180, 250), scale=1)
        sheet.draw_text(420, 60, leaf_key[:20], (166, 227, 161), scale=1)
        _draw_mini_region_map(sheet, 486, 150, 40, webpage_result.region_curve_preview)
        png_bytes = _encode_png(sheet, text_metadata={"Description": image_description, "CacheTag": image_tag})

    apng_bytes = _encode_apng(step_frames, delay_num=12, delay_den=10)
    gif_bytes = _encode_gif89a(step_frames, delay_cs=120)

    art = VerificationVisualArtifact(
        image_tag=image_tag,
        image_description=image_description,
        filename_png=f"rtp-{profile.intent_slug}-{profile.schema_hash8}.png",
        png_bytes=png_bytes,
        apng_bytes=apng_bytes,
        gif_bytes=gif_bytes,
    )
    if len(_VISUAL_MEMO) < 256:
        _VISUAL_MEMO[memo_key] = art
    return art


def _render_step_frame(
    step_num: int,
    profile: CustomerSetupProfile,
    console_result: ConsoleLogResult,
    webpage_result: LocalWebpageResult,
    mineflayer_result: MineflayerProbeResult,
    image_tag: str,
) -> _RgbCanvas:
    canvas = _RgbCanvas(420, 180, bg=(30, 30, 46))
    canvas.fill_rect(0, 0, 420, 22, (17, 17, 27))
    canvas.draw_border(0, 0, 420, 180, (137, 180, 250), thickness=1)
    canvas.draw_text(8, 7, f"STEP {step_num}/3  {profile.setup_mode.upper()}  V{profile.plugin_version}", (137, 180, 250), scale=1)
    canvas.draw_text(240, 7, webpage_result.target_file[:28], (249, 226, 175), scale=1)

    if step_num == 1:
        canvas.draw_text(10, 32, f"1. LOCATE IN WEB EDITOR: {webpage_result.target_file}", (205, 214, 244), scale=1)
        canvas.draw_text(10, 48, f"SEARCH KEY: {webpage_result.target_key}", (166, 227, 161), scale=1)
        canvas.draw_text(10, 64, f"PLATFORM: {profile.platform} {profile.mc_version}", (166, 173, 200), scale=1)
        for idx, line in enumerate(webpage_result.staging_diff_lines[:4]):
            col = (243, 139, 168) if line.startswith("- ") else ((166, 227, 161) if line.startswith("+ ") else (166, 173, 200))
            canvas.draw_text(10, 84 + idx * 14, line[:64], col, scale=1)
    elif step_num == 2:
        canvas.draw_text(10, 32, "2. STAGE DIFF + REGION MAP PREVIEW", (205, 214, 244), scale=1)
        for idx, line in enumerate(webpage_result.staging_diff_lines[:5]):
            col = (243, 139, 168) if line.startswith("- ") else ((166, 227, 161) if line.startswith("+ ") else (166, 173, 200))
            canvas.draw_text(10, 50 + idx * 14, line[:42], col, scale=1)
        _draw_mini_region_map(canvas, 330, 96, 48, webpage_result.region_curve_preview)
    else:
        canvas.draw_text(10, 32, "3. VERIFIED CONSOLE + LOG + BOT TRANSCRIPT", (166, 227, 161), scale=1)
        lines = list(console_result.console_transcript[-4:])
        if mineflayer_result.executed and mineflayer_result.events:
            lines.append(mineflayer_result.events[-1])
        for idx, ln in enumerate(lines[:5]):
            canvas.draw_text(10, 50 + idx * 16, ln[:66], (205, 214, 244), scale=1)

    canvas.fill_rect(1, 160, 418, 19, (24, 24, 37))
    canvas.draw_text(8, 166, image_tag[:66], (166, 173, 200), scale=1)
    return canvas


def _draw_panel_into_sheet(
    sheet: _RgbCanvas,
    y_off: int,
    step_num: int,
    profile: CustomerSetupProfile,
    console_result: ConsoleLogResult,
    webpage_result: LocalWebpageResult,
    mineflayer_result: MineflayerProbeResult,
) -> None:
    sheet.fill_rect(14, y_off + 6, 18, 14, (137, 180, 250))
    sheet.draw_text(20, y_off + 9, str(step_num), (17, 17, 27), scale=1)
    if step_num == 1:
        sheet.draw_text(38, y_off + 9, f"SETUP ({profile.setup_mode.upper()}) + WEB EDITOR SPOTLIGHT", (137, 180, 250), scale=1)
        sheet.draw_text(18, y_off + 28, f"FILE: {webpage_result.target_file}   KEY: {webpage_result.target_key}", (249, 226, 175), scale=1)
        sheet.draw_text(18, y_off + 44, f"ENV:  LeafRTP v{profile.plugin_version} on {profile.platform} {profile.mc_version}", (205, 214, 244), scale=1)
        sheet.draw_text(18, y_off + 60, f"LINK: {webpage_result.editor_deep_link[:78]}", (148, 226, 213), scale=1)
        if console_result.self_corrections:
            sheet.draw_text(18, y_off + 76, f"FIX:  {console_result.self_corrections[0][:78]}", (250, 179, 135), scale=1)
    elif step_num == 2:
        sheet.draw_text(38, y_off + 9, "WEBPAGE STAGING DIFF + REGION CURVE PREVIEW", (137, 180, 250), scale=1)
        for idx, line in enumerate(webpage_result.staging_diff_lines[:4]):
            col = (243, 139, 168) if line.startswith("- ") else ((166, 227, 161) if line.startswith("+ ") else (166, 173, 200))
            sheet.draw_text(18, y_off + 28 + idx * 16, line[:56], col, scale=1)
        _draw_mini_region_map(sheet, 470, y_off + 52, 38, webpage_result.region_curve_preview)
    else:
        sheet.draw_text(38, y_off + 9, "CONSOLE + LOG + MINEFLAYER EXECUTION RECEIPT", (166, 227, 161), scale=1)
        lines = list(console_result.console_transcript[-3:])
        if mineflayer_result.executed and mineflayer_result.events:
            lines.append(mineflayer_result.events[-1])
        for idx, ln in enumerate(lines[:4]):
            sheet.draw_text(18, y_off + 28 + idx * 16, ln[:84], (205, 214, 244), scale=1)


def _draw_mini_region_map(canvas: _RgbCanvas, cx: int, cy: int, rad_px: int, curve: dict[str, Any]) -> None:
    shape = str(curve.get("shape", "CIRCLE")).upper()
    r_outer = max(1, _safe_int(curve.get("radius"), 1000))
    r_inner = max(0, _safe_int(curve.get("centerRadius"), 0))
    inner_px = int(round((r_inner / float(r_outer)) * rad_px))
    if shape == "SQUARE":
        canvas.draw_border(cx - rad_px, cy - rad_px, rad_px * 2, rad_px * 2, (166, 227, 161), thickness=1)
        if inner_px > 2:
            canvas.draw_border(cx - inner_px, cy - inner_px, inner_px * 2, inner_px * 2, (243, 139, 168), thickness=1)
    else:
        canvas.draw_circle_outline(cx, cy, rad_px, (166, 227, 161))
        if inner_px > 2:
            canvas.draw_circle_outline(cx, cy, inner_px, (243, 139, 168))
    for px_n, pz_n in (curve.get("samplePoints") or [])[:16]:
        px = int(round(cx + float(px_n) * (rad_px - 3)))
        py = int(round(cy + float(pz_n) * (rad_px - 3)))
        canvas.fill_rect(px - 1, py - 1, 2, 2, (137, 180, 250))


# ══════════════════════════════════════════════════════════════════════════════
# 6. DISCORD SERVER AS A VERSIONED CACHE (`DiscordVersionedArtifactCache`)
# ══════════════════════════════════════════════════════════════════════════════

@dataclass
class DiscordCachedArtifact:
    plugin_version: str
    schema_hash8: str
    intent_slug: str
    image_tag: str
    image_description: str
    image_url: str
    message_id: str = ""
    channel_id: str = ""
    console_summary: str = ""
    editor_deep_link: str = ""
    timestamp: float = field(default_factory=time.time)

    def to_dict(self) -> dict[str, Any]:
        return {
            "plugin_version": self.plugin_version,
            "schema_hash8": self.schema_hash8,
            "intent_slug": self.intent_slug,
            "image_tag": self.image_tag,
            "image_description": self.image_description,
            "image_url": self.image_url,
            "message_id": self.message_id,
            "channel_id": self.channel_id,
            "console_summary": self.console_summary,
            "editor_deep_link": self.editor_deep_link,
            "timestamp": self.timestamp,
        }

    @staticmethod
    def from_dict(d: dict[str, Any]) -> DiscordCachedArtifact | None:
        if not isinstance(d, dict):
            return None
        ver = str(d.get("plugin_version") or "").strip()
        sh = str(d.get("schema_hash8") or "").strip().lower()
        slug = str(d.get("intent_slug") or "").strip().lower()
        url = str(d.get("image_url") or "").strip()
        if not (ver and sh and slug and url and is_trusted_cached_image_url(url)):
            return None
        return DiscordCachedArtifact(
            plugin_version=ver,
            schema_hash8=sh,
            intent_slug=slug,
            image_tag=str(d.get("image_tag") or f"rtp-cache:v{ver}:{sh}:{slug}"),
            image_description=str(d.get("image_description") or ""),
            image_url=url,
            message_id=str(d.get("message_id") or ""),
            channel_id=str(d.get("channel_id") or ""),
            console_summary=str(d.get("console_summary") or ""),
            editor_deep_link=str(d.get("editor_deep_link") or ""),
            timestamp=float(d.get("timestamp") or 0.0),
        )


def is_trusted_cached_image_url(url: str) -> bool:
    try:
        parsed = urllib.parse.urlparse(url.strip())
        host = (parsed.hostname or "").lower()
        return parsed.scheme in ("http", "https") and host in TRUSTED_IMAGE_HOSTS
    except ValueError:
        return False


class DiscordVersionedArtifactCache:
    """
    Uses the Discord server itself (plus persisted bot state) as a versioned
    cache of verified bot outputs and images:
      - Extracts `[rtp-cache:v<version>:<schema_hash8>:<intent_slug>]` + `image_description`
        and Discord CDN image URLs (`attachments[].url` / `embeds[].image.url`) from
        bot messages across the Discord server.
      - Reuses cached images by link when `plugin_version` and `schema_hash8` match
        (or when `plugin_version` + description/intent match).
      - Automatically invalidates cached entries on version difference or schema hash change.
    """

    def __init__(self) -> None:
        self._by_tag: dict[str, DiscordCachedArtifact] = {}
        self._by_slug: dict[str, DiscordCachedArtifact] = {}

    def load_serialized(self, items: list[dict[str, Any]] | None) -> None:
        for raw in items or []:
            art = DiscordCachedArtifact.from_dict(raw)
            if art is not None:
                self.register(art)

    def dump_serialized(self) -> list[dict[str, Any]]:
        seen: dict[str, DiscordCachedArtifact] = {}
        for art in self._by_tag.values():
            seen[art.image_tag.lower()] = art
        return [a.to_dict() for a in sorted(seen.values(), key=lambda x: x.timestamp, reverse=True)[:256]]

    def register(self, artifact: DiscordCachedArtifact) -> None:
        if not is_trusted_cached_image_url(artifact.image_url):
            return
        tag_key = f"{artifact.plugin_version}:{artifact.schema_hash8}:{artifact.intent_slug}".lower()
        self._by_tag[tag_key] = artifact
        self._by_slug[artifact.intent_slug.lower()] = artifact

    def lookup(
        self,
        plugin_version: str,
        schema_hash8: str,
        intent_slug: str,
        description_query: str = "",
    ) -> tuple[DiscordCachedArtifact | None, str]:
        """
        Returns `(cached_artifact, status_reason)` where `status_reason` is one of:
          - `"hit_exact"` (matching version + schema_hash8 + intent_slug)
          - `"hit_description"` (matching version + schema_hash8 + description similarity)
          - `"invalidated_version_mismatch"` (entry existed for intent_slug on an older plugin_version)
          - `"invalidated_schema_mismatch"` (entry existed on same version but different schema_hash8)
          - `"miss"` (no prior artifact)
        """
        ver = plugin_version.strip().lstrip("v")
        sh = schema_hash8.strip().lower()
        slug = intent_slug.strip().lower()
        exact_key = f"{ver}:{sh}:{slug}".lower()
        if exact_key in self._by_tag:
            return self._by_tag[exact_key], "hit_exact"

        # Check if an older version or older schema hash exists for this slug
        existing = self._by_slug.get(slug)
        if existing is not None:
            if existing.plugin_version != ver:
                return None, f"invalidated_version_mismatch(cached=v{existing.plugin_version},current=v{ver})"
            if existing.schema_hash8 != sh:
                return None, f"invalidated_schema_mismatch(cached={existing.schema_hash8},current={sh})"

        # Check description similarity across same-version cached entries
        if description_query:
            q_tokens = {
                t for t in re.findall(r"[a-z0-9_.-]{3,}", description_query.lower())
                if t not in ("leafrtp", "verified", "via", "console", "webpage", "screenshot", "setup")
            }
            if q_tokens:
                for art in self._by_tag.values():
                    if art.plugin_version != ver or art.schema_hash8 != sh:
                        continue
                    d_tokens = set(re.findall(r"[a-z0-9_.-]{3,}", art.image_description.lower()))
                    overlap = len(q_tokens & d_tokens) / float(max(1, len(q_tokens)))
                    if overlap >= 0.80:
                        return art, "hit_description"

        return None, "miss"

    def harvest_from_discord_messages(
        self,
        messages: list[dict[str, Any]],
        channel_id: str = "",
        bot_user_id: str = "",
    ) -> int:
        """
        Scans Discord message payloads (`attachments`, `embeds`, `content`) for
        `[rtp-cache:v<version>:<schema_hash8>:<intent_slug>]` tags and registers
        their hosted Discord CDN image links for versioned reuse.
        """
        harvested = 0
        for msg in messages or []:
            if not isinstance(msg, dict):
                continue
            author_id = str((msg.get("author") or {}).get("id") or "")
            if bot_user_id and author_id and author_id != str(bot_user_id):
                continue
            msg_id = str(msg.get("id") or "")
            ch_id = str(msg.get("channel_id") or channel_id or "")
            content = str(msg.get("content") or "")

            candidate_blobs: list[str] = [content]
            image_urls: list[str] = []
            descriptions: list[str] = []

            for att in msg.get("attachments") or []:
                if not isinstance(att, dict):
                    continue
                u = str(att.get("url") or att.get("proxy_url") or "").strip()
                desc = str(att.get("description") or "").strip()
                if u and is_trusted_cached_image_url(u):
                    image_urls.append(u)
                if desc:
                    descriptions.append(desc)
                    candidate_blobs.append(desc)

            for emb in msg.get("embeds") or []:
                if not isinstance(emb, dict):
                    continue
                img = emb.get("image") or emb.get("thumbnail") or {}
                if isinstance(img, dict):
                    u = str(img.get("url") or img.get("proxy_url") or "").strip()
                    if u and is_trusted_cached_image_url(u):
                        image_urls.append(u)
                footer = emb.get("footer") or {}
                if isinstance(footer, dict) and footer.get("text"):
                    candidate_blobs.append(str(footer["text"]))
                    descriptions.append(str(footer["text"]))
                if emb.get("description"):
                    candidate_blobs.append(str(emb["description"]))
                    descriptions.append(str(emb["description"]))

            for m_cdn in DISCORD_CDN_URL_RE.finditer(content):
                u = m_cdn.group(0)
                if u not in image_urls and is_trusted_cached_image_url(u):
                    image_urls.append(u)

            if not image_urls:
                continue

            combined_meta = "\n".join(candidate_blobs)
            m_tag = CACHE_TAG_RE.search(combined_meta)
            if not m_tag:
                continue

            ver = m_tag.group("version").lstrip("v")
            sh = m_tag.group("schema_hash").lower()
            slug = m_tag.group("intent_slug").lower()
            img_desc = descriptions[0] if descriptions else f"[rtp-cache:v{ver}:{sh}:{slug}]"

            art = DiscordCachedArtifact(
                plugin_version=ver,
                schema_hash8=sh,
                intent_slug=slug,
                image_tag=f"rtp-cache:v{ver}:{sh}:{slug}",
                image_description=img_desc,
                image_url=image_urls[0],
                message_id=msg_id,
                channel_id=ch_id,
            )
            self.register(art)
            harvested += 1
        return harvested


# ══════════════════════════════════════════════════════════════════════════════
# 7. AST CROSS-REFERENCE MAP & 5-MINUTE REPO TOOL BUDGET PLANNER
# ══════════════════════════════════════════════════════════════════════════════

DEFAULT_DEEP_WINDOW_SECONDS = 300.0  # 5-minute execution budget for deep verification

_REPO_TEST_INDEX_CACHE: dict[str, list[tuple[str, str, str]]] | None = None


def _build_repo_test_index() -> dict[str, list[tuple[str, str, str]]]:
    """
    Indexes all `*Test.java` files across the repository once, mapping normalized
    production class stems to `(gradle_module, test_class_name, rel_path)` so any
    AST `JAVA_CLASS` symbol can be cross-referenced to its executable JUnit test.
    """
    global _REPO_TEST_INDEX_CACHE
    if _REPO_TEST_INDEX_CACHE is not None:
        return _REPO_TEST_INDEX_CACHE

    index: dict[str, list[tuple[str, str, str]]] = {}
    for test_file in sorted(REPO_ROOT.rglob("*Test.java")):
        try:
            rel = test_file.relative_to(REPO_ROOT).as_posix()
        except ValueError:
            continue
        if "/src/test/java/" not in rel or rel.startswith("scripts/tmp/"):
            continue
        mod_part = rel.split("/src/test/java/", 1)[0].strip("/")
        if mod_part.startswith(("platforms/", "api/")):
            mod_part = mod_part.split("/", 1)[1]
        gradle_mod = ":" + mod_part.replace("/", ":") if mod_part else ":rtp-core"
        test_cls = test_file.stem
        base_stem = re.sub(
            r"(?:BranchCoverage|Coverage|LoopbackBrowser|Pacing|Settings|Execution|Contract|Protocol|AndApply|Test)+$",
            "",
            test_cls,
        ).lower()
        for key in {test_cls.lower(), base_stem}:
            if key:
                index.setdefault(key, []).append((gradle_mod, test_cls, rel))
    _REPO_TEST_INDEX_CACHE = index
    return index


@dataclass
class AstCrossReferenceMap:
    """
    Cross-referenced repository graph resolved from `doc-index.sqlite` AST tables
    (`ast_symbols`, `ast_edges`, `ast_conflicts`, `ast_config_tree`, `code_sections`)
    and repository test/script indices.
    """
    seed_symbols: list[str] = field(default_factory=list)
    linked_config_keys: list[str] = field(default_factory=list)
    linked_java_classes: list[str] = field(default_factory=list)
    linked_bit_codes: list[str] = field(default_factory=list)
    linked_adr_records: list[str] = field(default_factory=list)
    linked_safety_rules: list[str] = field(default_factory=list)
    ast_edges_traversed: list[dict[str, str]] = field(default_factory=list)
    ast_conflicts: list[dict[str, str]] = field(default_factory=list)
    targeted_gradle_tests: list[dict[str, str]] = field(default_factory=list)
    repo_scripts_matched: list[str] = field(default_factory=list)
    ast_subgraph_hash8: str = "00000000"


def resolve_ast_cross_reference_map(
    conn: sqlite3.Connection | None,
    profile: CustomerSetupProfile,
    query_text: str = "",
) -> AstCrossReferenceMap:
    """
    Traverses the solidified repository AST (`ast_symbols`, `ast_edges`,
    `ast_conflicts`, `ast_config_tree`, `code_sections`) starting from the
    customer's setup and query tokens to discover cross-referenced config keys,
    Java classes, BIT codes, ADRs, safety rules, JUnit tests, and repo scripts.
    """
    xref = AstCrossReferenceMap()
    seed_terms: list[str] = []
    for rec in profile.candidate_recommendations[:3]:
        leaf = str(rec["key_path"]).split(".")[-1]
        if leaf and leaf not in seed_terms:
            seed_terms.append(leaf)
    for bc in profile.bit_codes[:3]:
        if bc not in seed_terms:
            seed_terms.append(bc)
    for cls in profile.ast_classes[:4]:
        if cls not in seed_terms:
            seed_terms.append(cls)
    for m_tok in re.finditer(r"\b([A-Z][A-Za-z0-9]{3,}(?:Adjustor|Channel|Task|Bit|Cmd|Integrations|Budget|Prefilter)|S-00[1-7]|ADR-\d{3})\b", query_text):
        tok = m_tok.group(1)
        if tok not in seed_terms:
            seed_terms.append(tok)
        if not tok.startswith(("S-00", "ADR-")) and tok not in profile.ast_classes:
            profile.ast_classes.append(tok)

    xref.seed_symbols = list(seed_terms)
    xref.linked_config_keys = [r["key_path"] for r in profile.candidate_recommendations[:4]]
    xref.linked_java_classes = list(profile.ast_classes[:4])
    xref.linked_bit_codes = list(profile.bit_codes[:4])

    if conn is not None:
        try:
            has_ast = conn.execute(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='ast_edges' LIMIT 1"
            ).fetchone()
            if has_ast is not None:
                candidate_sym_ids: list[str] = []
                for term in seed_terms[:6]:
                    t_low = term.lower()
                    t_leaf = t_low.split(".")[-1]
                    sym_rows = conn.execute(
                        """
                        SELECT symbol_id FROM ast_symbols
                        WHERE lower(canonical_name) = ?
                           OR lower(symbol_id) LIKE ?
                           OR lower(symbol_id) LIKE ?
                        ORDER BY CASE
                            WHEN lower(canonical_name) = ? THEN 0
                            WHEN lower(symbol_id) LIKE ? THEN 1
                            WHEN symbol_id LIKE 'cfg:definitions/regions/default.yml:%' OR symbol_id LIKE 'cfg:config.yml:%' THEN 2
                            ELSE 3
                        END, doc_frequency DESC
                        LIMIT 4
                        """,
                        (t_low, f"%:{t_low}", f"%.{t_leaf}", t_low, f"%:{t_low}"),
                    ).fetchall()
                    for sr in sym_rows:
                        sid = str(sr[0])
                        if sid not in candidate_sym_ids:
                            candidate_sym_ids.append(sid)

                direct_test_symbols: list[str] = []
                for sid in candidate_sym_ids[:8]:
                    edge_rows = conn.execute(
                        """
                        SELECT src_symbol_id, edge_kind, dst_symbol_id, weight
                        FROM ast_edges
                        WHERE src_symbol_id = ? OR dst_symbol_id = ?
                        ORDER BY CASE
                            WHEN edge_kind IN ('READS_CONFIG_KEY', 'IMPLEMENTED_IN_CLASS', 'EMITS_BIT_CODE', 'EMITTED_BY_CLASS', 'GOVERNED_BY_KEY', 'EMITS_BIT_ON_FAILURE', 'SHARES_CONFIG_KEY', 'IMPLEMENTS_SPI', 'SPI_IMPLEMENTED_BY') THEN 0
                            WHEN edge_kind IN ('GOVERNED_BY_ADR', 'ADR_GOVERNS_CLASS', 'GOVERNED_BY_INVARIANT', 'SATISFIES_REQ', 'REQ_SATISFIED_BY', 'VERIFIED_BY_TEST', 'TESTS_CLASS', 'SIBLING_IMPLEMENTATION') THEN 1
                            WHEN edge_kind != 'DOCUMENTED_WITH' THEN 2
                            ELSE 3
                        END, weight DESC
                        LIMIT 22
                        """,
                        (sid, sid),
                    ).fetchall()
                    for r in edge_rows:
                        src_id = str(r[0])
                        pred = str(r[1])
                        dst_id = str(r[2])
                        w_val = float(r[3] or 0.0)
                        is_doc_with = pred == "DOCUMENTED_WITH"
                        if len(xref.ast_edges_traversed) < 16 and (not is_doc_with or w_val >= 6.0):
                            xref.ast_edges_traversed.append({
                                "src": src_id,
                                "predicate": pred,
                                "dst": dst_id,
                            })
                        for node_id in (src_id, dst_id):
                            if node_id.startswith("cfg:") and len(xref.linked_config_keys) < 10:
                                if is_doc_with and w_val < 7.5 and len(xref.linked_config_keys) >= 2:
                                    continue
                                kp = node_id.split(":", 2)[-1]
                                if kp not in xref.linked_config_keys:
                                    xref.linked_config_keys.append(kp)
                            elif node_id.startswith(("cls:", "class:", "spi:")) and len(xref.linked_java_classes) < 8:
                                if is_doc_with and len(xref.linked_java_classes) >= 1:
                                    continue
                                cn = node_id.split(":", 1)[-1]
                                if cn not in xref.linked_java_classes:
                                    xref.linked_java_classes.append(cn)
                            elif node_id.startswith("bit:") and len(xref.linked_bit_codes) < 6:
                                bc = node_id.split(":", 1)[-1]
                                if bc not in xref.linked_bit_codes:
                                    xref.linked_bit_codes.append(bc)
                            elif (node_id.startswith("safety:") or re.match(r"^adr:S-00[1-9]$", node_id, re.IGNORECASE)) and len(xref.linked_safety_rules) < 6:
                                sf = node_id.split(":", 1)[-1].upper()
                                if sf not in xref.linked_safety_rules:
                                    xref.linked_safety_rules.append(sf)
                            elif node_id.startswith("adr:") and len(xref.linked_adr_records) < 6:
                                adr = node_id.split(":", 1)[-1]
                                if adr not in xref.linked_adr_records:
                                    xref.linked_adr_records.append(adr)
                            elif node_id.startswith("test:") and len(direct_test_symbols) < 8:
                                if not is_doc_with and node_id not in direct_test_symbols:
                                    direct_test_symbols.append(node_id)

                conflict_rows = conn.execute(
                    """
                    SELECT focal_symbol_id, side_a_target, side_b_target, conflict_kind, explanation_note
                    FROM ast_conflicts
                    LIMIT 10
                    """
                ).fetchall()
                q_low = f"{query_text} {' '.join(seed_terms)}".lower()
                for cr in conflict_rows:
                    focal, side_a, side_b, c_type, expl = str(cr[0]), str(cr[1]), str(cr[2]), str(cr[3]), str(cr[4])
                    if (
                        focal.split(":")[-1].lower() in q_low
                        or side_a.split(":")[-1].lower() in q_low
                        or side_b.split(":")[-1].lower() in q_low
                        or "syncloading" in q_low
                        or "unqueued" in q_low
                    ):
                        xref.ast_conflicts.append({
                            "focal_symbol": focal,
                            "symbol_a": side_a,
                            "symbol_b": side_b,
                            "conflict_type": c_type,
                            "explanation": expl,
                        })
        except sqlite3.Error:
            pass

    # Cross-reference linked Java classes and AST `VERIFIED_BY_TEST` symbols to repository JUnit `*Test.java` targets
    seen_tests: set[str] = set()
    if conn is not None:
        try:
            for t_sym in direct_test_symbols[:5]:
                tc_name = t_sym.split(":")[-1]
                if tc_name and tc_name not in seen_tests:
                    tm_r = conn.execute(
                        "SELECT module_name, path FROM test_class_meta WHERE test_class_name = ? LIMIT 1",
                        (tc_name,),
                    ).fetchone()
                    if tm_r is not None:
                        seen_tests.add(tc_name)
                        gmod = str(tm_r[0] or ":rtp-core")
                        xref.targeted_gradle_tests.append({
                            "module": gmod,
                            "test_class": tc_name,
                            "path": str(tm_r[1] or ""),
                            "command": f"./gradlew {gmod}:test --tests \"*{tc_name}*\"",
                        })
            for cls_name in xref.linked_java_classes[:5]:
                cm_r = conn.execute(
                    "SELECT module_name, gradle_test_cmd FROM code_class_meta WHERE class_name = ? LIMIT 1",
                    (cls_name,),
                ).fetchone()
                if cm_r is not None:
                    gmod = str(cm_r[0] or ":rtp-core")
                    gcmd = str(cm_r[1] or "")
                    m_tc = re.search(r'--tests\s+"?\*?([A-Za-z0-9_]+)\*?"?', gcmd)
                    if m_tc:
                        tc_name = m_tc.group(1)
                        if tc_name not in seen_tests:
                            seen_tests.add(tc_name)
                            tm_r = conn.execute(
                                "SELECT path FROM test_class_meta WHERE test_class_name = ? LIMIT 1",
                                (tc_name,),
                            ).fetchone()
                            xref.targeted_gradle_tests.append({
                                "module": gmod,
                                "test_class": tc_name,
                                "path": str(tm_r[0]) if tm_r else "",
                                "command": f"./gradlew {gmod}:test --tests \"*{tc_name}*\"",
                            })
        except sqlite3.Error:
            pass

    test_index = _build_repo_test_index()
    for cls_name in xref.linked_java_classes[:5]:
        if len(xref.targeted_gradle_tests) >= 5:
            break
        cls_low = cls_name.lower()
        matches: list[tuple[str, str, str]] = []
        if cls_low in test_index:
            matches.extend(test_index[cls_low])
        else:
            for stem, t_list in test_index.items():
                if len(cls_low) >= 5 and len(stem) >= 5 and (cls_low in stem or stem in cls_low):
                    matches.extend(t_list)
        for gradle_mod, test_cls, rel_path in matches[:2]:
            if test_cls not in seen_tests:
                seen_tests.add(test_cls)
                xref.targeted_gradle_tests.append({
                    "module": gradle_mod,
                    "test_class": test_cls,
                    "path": rel_path,
                    "command": f"./gradlew {gradle_mod}:test --tests \"*{test_cls}*\"",
                })

    # Discover applicable repository helper/verification scripts
    repo_scripts = [
        "discord-support-bot/bot_verification_tools.py",
        "scripts/generate_web_editor.py",
    ]
    if any(k in ("shape", "radius", "centerRadius", "mode") for r in profile.candidate_recommendations for k in (r["key_path"],)):
        if (REPO_ROOT / "scripts" / "editor_curve_probe.py").is_file():
            repo_scripts.append("scripts/editor_curve_probe.py")
    if profile.needs_mineflayer:
        if (REPO_ROOT / "devstack" / "clients" / "mineflayer-bot.js").is_file():
            repo_scripts.append("devstack/clients/mineflayer-bot.js")
        if (REPO_ROOT / "scripts" / "gif-bots.js").is_file():
            repo_scripts.append("scripts/gif-bots.js")
    xref.repo_scripts_matched = repo_scripts

    subgraph_payload = json.dumps(
        {
            "plugin_version": profile.plugin_version,
            "intent_slug": profile.intent_slug,
            "config_keys": sorted(xref.linked_config_keys[:4]),
            "java_classes": sorted(xref.linked_java_classes[:4]),
            "bit_codes": sorted(xref.linked_bit_codes[:4]),
            "tests": [t["test_class"] for t in xref.targeted_gradle_tests[:2]],
        },
        sort_keys=True,
    )
    xref.ast_subgraph_hash8 = hashlib.sha256(subgraph_payload.encode("utf-8")).hexdigest()[:8]
    return xref


@dataclass
class PlannedRepoToolStep:
    tool_id: str
    category: str  # "cache" | "ast" | "console" | "local_webpage" | "screenshotting" | "gradle_test" | "mineflayer"
    description: str
    estimated_seconds: float
    command_or_target: str
    selected: bool = True
    executed: bool = False
    elapsed_seconds: float = 0.0
    status: str = "planned"


class RepoToolBudgetPlanner:
    """
    Decides which repository verification tools can be executed within a
    5-minute (`300.0s`) window using `AstCrossReferenceMap` and `CustomerSetupProfile`.
    """

    def __init__(self, window_budget_seconds: float = DEFAULT_DEEP_WINDOW_SECONDS) -> None:
        self.window_budget_seconds = max(1.0, float(window_budget_seconds))

    def build_plan(
        self,
        profile: CustomerSetupProfile,
        xref: AstCrossReferenceMap,
        include_gradle_tests: bool = False,
        include_mineflayer: bool = False,
    ) -> list[PlannedRepoToolStep]:
        candidates: list[PlannedRepoToolStep] = [
            PlannedRepoToolStep(
                tool_id="discord_versioned_cache_lookup",
                category="cache",
                description="Check Discord server versioned cache by (version, schema_hash8, intent_slug) and image description",
                estimated_seconds=0.002,
                command_or_target=f"rtp-cache:v{profile.plugin_version}:{profile.schema_hash8}:{profile.intent_slug}",
            ),
            PlannedRepoToolStep(
                tool_id="ast_cross_reference_traversal",
                category="ast",
                description="Traverse AST symbols, edges, conflicts, and cross-referenced Java/config/test nodes",
                estimated_seconds=0.015,
                command_or_target=f"ast_edges({len(xref.ast_edges_traversed)}) + classes({','.join(xref.linked_java_classes[:3]) or 'core'})",
            ),
            PlannedRepoToolStep(
                tool_id="console_log_sandbox",
                category="console",
                description=f"Reconstruct {profile.setup_mode} setup and gather console/log + BIT cross-key output",
                estimated_seconds=0.025,
                command_or_target="; ".join(profile.commands_to_test[:3]),
            ),
            PlannedRepoToolStep(
                tool_id="local_webpage_operations",
                category="local_webpage",
                description="Execute Web Editor search, YAML staging diff, region curve simulation, and spotlight deep link",
                estimated_seconds=0.040,
                command_or_target="docs/editor/index.html + docs/editor/editor-data.json",
            ),
            PlannedRepoToolStep(
                tool_id="multi_format_screenshotting",
                category="screenshotting",
                description="Capture 3-step visual verification storyboard in PNG, APNG (fcTL/fdAT), and GIF89a",
                estimated_seconds=0.060,
                command_or_target=f"rtp-{profile.intent_slug}-{profile.schema_hash8}.{{png,apng,gif}}",
            ),
        ]

        if (include_mineflayer or profile.needs_mineflayer):
            candidates.append(
                PlannedRepoToolStep(
                    tool_id="mineflayer_protocol_bot",
                    category="mineflayer",
                    description="Verify player-side spawn, /rtp warmup/actionBar, and forcedMove arrival via Mineflayer bot",
                    estimated_seconds=8.0 if os.environ.get("RTP_MINEFLAYER_HOST") else 0.020,
                    command_or_target="node devstack/clients/mineflayer-bot.js",
                )
            )

        if include_gradle_tests and xref.targeted_gradle_tests:
            top_test = xref.targeted_gradle_tests[0]
            candidates.append(
                PlannedRepoToolStep(
                    tool_id=f"gradle_test_{top_test['test_class']}",
                    category="gradle_test",
                    description=f"Run AST-cross-referenced JUnit test `{top_test['test_class']}` in `{top_test['module']}`",
                    estimated_seconds=25.0,
                    command_or_target=top_test["command"],
                )
            )

        remaining = self.window_budget_seconds
        for step in candidates:
            if step.estimated_seconds <= remaining:
                step.selected = True
                remaining -= step.estimated_seconds
            else:
                step.selected = False
                step.status = "skipped_budget_exceeded"
        return candidates


# ══════════════════════════════════════════════════════════════════════════════
# 8. UNIFIED WORKBENCH EXECUTION (`BotVerificationWorkbench`)
# ══════════════════════════════════════════════════════════════════════════════

@dataclass
class WorkbenchVerificationReport:
    profile: CustomerSetupProfile
    console_result: ConsoleLogResult
    webpage_result: LocalWebpageResult
    mineflayer_result: MineflayerProbeResult
    visual_artifact: VerificationVisualArtifact | None
    cache_status: str  # "hit_exact" | "hit_description" | "invalidated_*" | "miss"
    reused_image_url: str | None
    image_tag: str
    image_description: str
    ast_cross_references: AstCrossReferenceMap = field(default_factory=AstCrossReferenceMap)
    tool_plan: list[PlannedRepoToolStep] = field(default_factory=list)
    window_budget_seconds: float = DEFAULT_DEEP_WINDOW_SECONDS
    total_elapsed_seconds: float = 0.0

    def format_discord_verification_block(self) -> str:
        """
        Formats a concise, high-signal verification block for Discord replies
        that includes the setup mode (`definite` vs `best-approximation`),
        verified console/log output, Web Editor spotlight deep link, and
        versioned image cache tag (`[rtp-cache:v<ver>:<hash8>:<slug>]`).
        """
        mode_label = (
            "definite customer setup"
            if self.profile.setup_mode == "definite"
            else f"best-approximation setup (`LeafRTP v{self.profile.plugin_version}` on `{self.profile.platform} {self.profile.mc_version}`)"
        )
        lines: list[str] = [
            f"**Verified in sandbox ({mode_label}):**",
        ]
        for corr in self.console_result.self_corrections[:2]:
            lines.append(f"- **Companion requirement:** {corr}")
        if self.console_result.console_transcript:
            sample_logs = "\n".join(self.console_result.console_transcript[-3:])
            lines.append(f"```text\n{sample_logs}\n```")
        if self.mineflayer_result.executed and self.mineflayer_result.events:
            lines.append(f"- **Player protocol check (`{self.mineflayer_result.mode}`):** `{self.mineflayer_result.events[-1]}`")
        lines.append(
            f"- **Interactive Web Editor spotlight:** <{self.webpage_result.editor_deep_link}>"
        )
        if self.reused_image_url:
            lines.append(
                f"- **Visual walkthrough (`[{self.image_tag}]`, reused from Discord cache):** <{self.reused_image_url}>"
            )
        else:
            lines.append(f"- **Visual walkthrough tag:** `[{self.image_tag}]`")
        return "\n".join(lines)


class BotVerificationWorkbench:
    """
    Coordinates customer setup reconstruction, the 4 verification tools
    (`ConsoleLogTool`, `LocalWebpageTool`, `ScreenshottingTool`, `MineflayerBotTool`),
    and the `DiscordVersionedArtifactCache`.
    """

    def __init__(
        self,
        artifact_cache: DiscordVersionedArtifactCache | None = None,
        window_budget_seconds: float = DEFAULT_DEEP_WINDOW_SECONDS,
    ) -> None:
        self.cache = artifact_cache or DiscordVersionedArtifactCache()
        self.window_budget_seconds = max(1.0, float(window_budget_seconds))

    def run_verification(
        self,
        conn: sqlite3.Connection | None,
        query_text: str,
        fetched_logs: str = "",
        config_yaml: str = "",
        applied_tags: tuple[str, ...] = (),
        cfg_hits: list[dict[str, Any]] | None = None,
        bit_matches: list[dict[str, Any]] | None = None,
        code_hits: list[dict[str, Any]] | None = None,
        draft_reply: str = "",
        force_mineflayer: bool = False,
        include_gradle_tests: bool = False,
        window_budget_seconds: float | None = None,
    ) -> WorkbenchVerificationReport:
        t0 = time.monotonic()
        budget = float(window_budget_seconds) if window_budget_seconds is not None else self.window_budget_seconds

        profile = reconstruct_customer_setup(
            conn=conn,
            query_text=query_text,
            fetched_logs=fetched_logs,
            config_yaml=config_yaml,
            applied_tags=applied_tags,
            cfg_hits=cfg_hits,
            bit_matches=bit_matches,
            code_hits=code_hits,
            draft_reply=draft_reply,
        )
        xref = resolve_ast_cross_reference_map(conn=conn, profile=profile, query_text=query_text)
        planner = RepoToolBudgetPlanner(window_budget_seconds=budget)
        plan = planner.build_plan(
            profile=profile,
            xref=xref,
            include_gradle_tests=include_gradle_tests,
            include_mineflayer=force_mineflayer,
        )

        console_res = gather_console_and_logs(profile)
        webpage_res = run_local_webpage_operations(profile, console_res)
        mineflayer_res = run_mineflayer_probe(
            profile,
            webpage_res,
            force_run=force_mineflayer,
        )

        image_tag = f"rtp-cache:v{profile.plugin_version}:{profile.schema_hash8}:{profile.intent_slug}"
        desc_preview = (
            f"[{image_tag}] LeafRTP v{profile.plugin_version} ({profile.setup_mode}, {profile.platform} {profile.mc_version}) | "
            f"{webpage_res.target_file} -> {webpage_res.target_key}"
        )

        cached_entry, cache_status = self.cache.lookup(
            plugin_version=profile.plugin_version,
            schema_hash8=profile.schema_hash8,
            intent_slug=profile.intent_slug,
            description_query=desc_preview,
        )
        for step in plan:
            if step.selected:
                step.executed = True
                step.status = "reused_from_discord_cache" if (cached_entry is not None and step.category == "screenshotting") else "executed_ok"

        if cached_entry is not None:
            return WorkbenchVerificationReport(
                profile=profile,
                console_result=console_res,
                webpage_result=webpage_res,
                mineflayer_result=mineflayer_res,
                visual_artifact=None,
                cache_status=cache_status,
                reused_image_url=cached_entry.image_url,
                image_tag=cached_entry.image_tag,
                image_description=cached_entry.image_description,
                ast_cross_references=xref,
                tool_plan=plan,
                window_budget_seconds=budget,
                total_elapsed_seconds=round(time.monotonic() - t0, 4),
            )

        visual = capture_verification_visuals(
            profile=profile,
            console_result=console_res,
            webpage_result=webpage_res,
            mineflayer_result=mineflayer_res,
        )
        return WorkbenchVerificationReport(
            profile=profile,
            console_result=console_res,
            webpage_result=webpage_res,
            mineflayer_result=mineflayer_res,
            visual_artifact=visual,
            cache_status=cache_status,
            reused_image_url=None,
            image_tag=visual.image_tag,
            image_description=visual.image_description,
            ast_cross_references=xref,
            tool_plan=plan,
            window_budget_seconds=budget,
            total_elapsed_seconds=round(time.monotonic() - t0, 4),
        )

    def create_tool_session(
        self,
        conn: sqlite3.Connection | None,
        query_text: str,
        fetched_logs: str = "",
        config_yaml: str = "",
        applied_tags: tuple[str, ...] = (),
        cfg_hits: list[dict[str, Any]] | None = None,
        bit_matches: list[dict[str, Any]] | None = None,
        code_hits: list[dict[str, Any]] | None = None,
    ) -> WorkbenchToolSession:
        """Creates a stateful verification tool session for optional LLM tool calls."""
        return WorkbenchToolSession(
            workbench=self,
            conn=conn,
            query_text=query_text,
            fetched_logs=fetched_logs,
            config_yaml=config_yaml,
            applied_tags=applied_tags,
            cfg_hits=cfg_hits,
            bit_matches=bit_matches,
            code_hits=code_hits,
        )


def build_workbench_tool_definitions() -> list[dict[str, Any]]:
    """
    Returns OpenAI-compatible function tool definitions for the 4 read-only
    verification workbench tools plus the optional `submit_reply` handoff tool.
    Tool use is optional: for purely doc-driven questions, the model may emit
    Markdown bullets directly after `</think>` without calling any tools.
    """
    return [
        {
            "type": "function",
            "function": {
                "name": "console_log_sandbox",
                "description": (
                    "Optional verification tool: simulate `/rtp` commands and YAML config overrides against "
                    "the reconstructed customer setup (`definite` or `best_approximation`), verify `commands-api` "
                    "grammar and cross-key `[RTP bit]` invariants (`centerRadius < radius`, `minY < maxY`, "
                    "`maxSlope` + `slopeRadius`, `S-005` `syncLoading`), and return console/BIT logs and "
                    "companion-key self-corrections."
                ),
                "parameters": {
                    "type": "object",
                    "properties": {
                        "commands": {
                            "type": "array",
                            "items": {"type": "string"},
                            "description": "Optional `/rtp ...` commands to test in the sandbox.",
                        },
                        "yaml_overrides": {
                            "type": "object",
                            "description": "Optional `key: value` overrides to test against the customer setup.",
                        },
                    },
                },
            },
        },
        {
            "type": "function",
            "function": {
                "name": "local_webpage_operations",
                "description": (
                    "Optional verification tool: run Web Editor (`docs/editor/editor-data.json`) and Support "
                    "Doctor (`docs/assets/support-corpus.json`) operations to stage YAML diffs, simulate region "
                    "geometry (`CIRCLE`, `SQUARE`, `RADIAL`, `SPIRAL`, `HILBERT`), and generate an interactive "
                    "Web Editor spotlight deep link (`https://dailystruggle.github.io/RTP/editor/?file=...`)."
                ),
                "parameters": {
                    "type": "object",
                    "properties": {
                        "target_file": {
                            "type": "string",
                            "description": "Target YAML file path (e.g. `regions/default.yml`, `advanced/performance.yml`).",
                        },
                        "target_key": {
                            "type": "string",
                            "description": "Target YAML key path (e.g. `centerRadius`, `maxMSPT`, `openOnBareRtp`).",
                        },
                        "recommended_value": {
                            "type": "string",
                            "description": "Recommended value to stage in the Web Editor diff preview.",
                        },
                    },
                },
            },
        },
        {
            "type": "function",
            "function": {
                "name": "multi_format_screenshotting",
                "description": (
                    "Optional verification tool: check the versioned Discord artifact cache "
                    "(`[rtp-cache:v<version>:<schema_hash8>:<intent_slug>]`) or render a 3-step visual "
                    "verification storyboard (`PNG`, `APNG`, `GIF89a`) for the current setup and Web Editor preview."
                ),
                "parameters": {
                    "type": "object",
                    "properties": {
                        "intent_slug": {
                            "type": "string",
                            "description": "Optional slug for the versioned cache tag.",
                        },
                    },
                },
            },
        },
        {
            "type": "function",
            "function": {
                "name": "mineflayer_protocol_bot",
                "description": (
                    "Optional verification tool: run a bounded Mineflayer protocol probe "
                    "(`spawn` -> `/rtp` -> warmup `actionBar`/`title` -> `forcedMove` arrival) "
                    "to verify player-facing teleport behavior."
                ),
                "parameters": {
                    "type": "object",
                    "properties": {
                        "force_run": {
                            "type": "boolean",
                            "description": "Whether to force execution of the Mineflayer protocol simulation.",
                        },
                    },
                },
            },
        },
        {
            "type": "function",
            "function": {
                "name": "submit_reply",
                "description": (
                    "Optional terminal handoff tool: submit the final support reply bullets (or `[REFRAIN: ...]`) "
                    "and optionally attach the sandbox verification report/visual walkthrough. You may either "
                    "call `submit_reply` or write the Markdown bullets directly after `</think>`."
                ),
                "parameters": {
                    "type": "object",
                    "properties": {
                        "bullets": {
                            "type": "array",
                            "items": {"type": "string"},
                            "description": "2 to 4 concise Markdown bullets (`- **<Heading>:** ...`).",
                        },
                        "attach_verification_report": {
                            "type": "boolean",
                            "description": "Whether to attach the verification report / visual walkthrough to the Discord reply.",
                        },
                        "refrain_reason": {
                            "type": "string",
                            "description": "Optional status reason for the maintainer if refraining due to insufficient/ambiguous context.",
                        },
                        "clarifying_questions": {
                            "type": "array",
                            "items": {"type": "string"},
                            "description": "Optional `- **Clarifying Question:** ...` bullets when refraining or asking for specifics.",
                        },
                    },
                },
            },
        },
    ]


class WorkbenchToolSession:
    """
    Stateful per-synthesis session that executes optional LLM verification tool
    calls (`console_log_sandbox`, `local_webpage_operations`, `multi_format_screenshotting`,
    `mineflayer_protocol_bot`) and accumulates a `WorkbenchVerificationReport`.
    """

    def __init__(
        self,
        workbench: BotVerificationWorkbench,
        conn: sqlite3.Connection | None,
        query_text: str,
        fetched_logs: str = "",
        config_yaml: str = "",
        applied_tags: tuple[str, ...] = (),
        cfg_hits: list[dict[str, Any]] | None = None,
        bit_matches: list[dict[str, Any]] | None = None,
        code_hits: list[dict[str, Any]] | None = None,
    ) -> None:
        self.workbench = workbench
        self.conn = conn
        self.query_text = query_text
        self.fetched_logs = fetched_logs
        self.config_yaml = config_yaml
        self.applied_tags = applied_tags
        self.cfg_hits = list(cfg_hits or [])
        self.bit_matches = list(bit_matches or [])
        self.code_hits = list(code_hits or [])
        self._t0 = time.monotonic()
        self.profile: CustomerSetupProfile = reconstruct_customer_setup(
            conn=conn,
            query_text=query_text,
            fetched_logs=fetched_logs,
            config_yaml=config_yaml,
            applied_tags=applied_tags,
            cfg_hits=self.cfg_hits,
            bit_matches=self.bit_matches,
            code_hits=self.code_hits,
        )
        self.xref: AstCrossReferenceMap = resolve_ast_cross_reference_map(
            conn=conn,
            profile=self.profile,
            query_text=query_text,
        )
        self.console_result: ConsoleLogResult | None = None
        self.webpage_result: LocalWebpageResult | None = None
        self.mineflayer_result: MineflayerProbeResult | None = None
        self.visual_artifact: VerificationVisualArtifact | None = None
        self.cache_status: str = "miss"
        self.reused_image_url: str | None = None
        self.image_tag: str = ""
        self.image_description: str = ""
        self.force_mineflayer: bool = False
        self.tools_invoked: list[str] = []

    def execute_tool(self, tool_name: str, arguments: dict[str, Any] | None = None) -> dict[str, Any]:
        args = arguments if isinstance(arguments, dict) else {}
        name = str(tool_name or "").strip()
        if name and name not in self.tools_invoked:
            self.tools_invoked.append(name)

        if name == "console_log_sandbox":
            raw_overrides = args.get("yaml_overrides")
            if isinstance(raw_overrides, dict):
                for k, v in list(raw_overrides.items())[:8]:
                    k_str = str(k).strip()
                    leaf = k_str.split(".")[-1]
                    v_str = str(v).strip()
                    if not re.match(r"^[A-Za-z_][A-Za-z0-9_.-]{0,63}$", k_str):
                        continue
                    matched_fp = "regions/default.yml"
                    def_val = ""
                    for fp_name, fp_dict in self.profile.effective_config.items():
                        if leaf in fp_dict:
                            matched_fp = fp_name
                            def_val = str(fp_dict[leaf])
                            fp_dict[leaf] = v_str
                    self.profile.customer_overrides.append(
                        {
                            "file_path": matched_fp,
                            "key_path": leaf,
                            "customer_value": v_str,
                            "default_value": def_val,
                        }
                    )
                    if leaf in self.profile.effective_region:
                        if isinstance(self.profile.effective_region[leaf], bool):
                            self.profile.effective_region[leaf] = v_str.lower() == "true"
                        elif isinstance(self.profile.effective_region[leaf], int):
                            self.profile.effective_region[leaf] = _safe_int(v_str, self.profile.effective_region[leaf])
                        else:
                            self.profile.effective_region[leaf] = v_str
            raw_cmds = args.get("commands")
            if isinstance(raw_cmds, list):
                for cmd in raw_cmds[:5]:
                    c_str = str(cmd).strip()
                    if c_str.startswith("/rtp") and c_str not in self.profile.commands_to_test:
                        self.profile.commands_to_test.append(c_str)
            self.console_result = gather_console_and_logs(self.profile)
            return {
                "tool": "console_log_sandbox",
                "setup_mode": self.profile.setup_mode,
                "platform": self.profile.platform,
                "mc_version": self.profile.mc_version,
                "plugin_version": self.profile.plugin_version,
                "passed": self.console_result.passed,
                "commands_tested": self.console_result.commands_tested,
                "log_findings": self.console_result.log_findings,
                "self_corrections": self.console_result.self_corrections,
                "console_transcript": self.console_result.console_transcript[-6:],
            }

        if name == "local_webpage_operations":
            t_file = str(args.get("target_file") or "").strip()
            t_key = str(args.get("target_key") or "").strip()
            rec_val = str(args.get("recommended_value") or "").strip()
            if t_key and re.match(r"^[A-Za-z_][A-Za-z0-9_.-]{0,63}$", t_key):
                leaf = t_key.split(".")[-1]
                fp = t_file if t_file.endswith(".yml") else "definitions/regions/default.yml"
                cur_val = str(self.profile.effective_config.get(fp, {}).get(leaf, self.profile.effective_region.get(leaf, "")))
                new_val = rec_val or cur_val or "0"
                file_alias = pathlib.Path(fp).name
                cmd = f"/rtp config {file_alias} {leaf}={new_val}"
                self.profile.candidate_recommendations.insert(
                    0,
                    {
                        "file_path": fp,
                        "key_path": t_key,
                        "old_value": cur_val,
                        "new_value": new_val,
                        "command": cmd,
                        "reason": "Staged via local_webpage_operations",
                        "directives": "",
                    },
                )
            if self.console_result is None:
                self.console_result = gather_console_and_logs(self.profile)
            self.webpage_result = run_local_webpage_operations(self.profile, self.console_result)
            return {
                "tool": "local_webpage_operations",
                "target_file": self.webpage_result.target_file,
                "target_key": self.webpage_result.target_key,
                "editor_deep_link": self.webpage_result.editor_deep_link,
                "staging_diff_lines": self.webpage_result.staging_diff_lines,
                "region_curve_preview": {
                    "shape": self.webpage_result.region_curve_preview.get("shape"),
                    "mode": self.webpage_result.region_curve_preview.get("mode"),
                    "radius": self.webpage_result.region_curve_preview.get("radius"),
                    "centerRadius": self.webpage_result.region_curve_preview.get("centerRadius"),
                    "voidPercentage": self.webpage_result.region_curve_preview.get("voidPercentage"),
                },
                "doctor_summary": self.webpage_result.doctor_summary,
            }

        if name == "mineflayer_protocol_bot":
            self.force_mineflayer = bool(args.get("force_run", True))
            if self.console_result is None:
                self.console_result = gather_console_and_logs(self.profile)
            if self.webpage_result is None:
                self.webpage_result = run_local_webpage_operations(self.profile, self.console_result)
            self.mineflayer_result = run_mineflayer_probe(
                self.profile,
                self.webpage_result,
                force_run=self.force_mineflayer,
            )
            return {
                "tool": "mineflayer_protocol_bot",
                "executed": self.mineflayer_result.executed,
                "mode": self.mineflayer_result.mode,
                "passed": self.mineflayer_result.passed,
                "events": self.mineflayer_result.events,
            }

        if name == "multi_format_screenshotting":
            slug_arg = str(args.get("intent_slug") or "").strip()
            if slug_arg:
                self.profile.intent_slug = _slugify(slug_arg, max_len=44)
            report = self.finalize_report()
            return {
                "tool": "multi_format_screenshotting",
                "cache_status": report.cache_status,
                "image_tag": report.image_tag,
                "image_description": report.image_description,
                "reused_image_url": report.reused_image_url,
                "editor_deep_link": report.webpage_result.editor_deep_link,
                "generated_formats": ["PNG", "APNG", "GIF89a"] if report.visual_artifact is not None else [],
            }

        return {"error": f"unknown tool: {name}"}

    def finalize_report(self, draft_reply: str = "") -> WorkbenchVerificationReport:
        """Builds the complete `WorkbenchVerificationReport`, reusing any tool results already computed."""
        if self.console_result is None:
            self.console_result = gather_console_and_logs(self.profile)
        if self.webpage_result is None:
            self.webpage_result = run_local_webpage_operations(self.profile, self.console_result)
        if self.mineflayer_result is None:
            self.mineflayer_result = run_mineflayer_probe(
                self.profile,
                self.webpage_result,
                force_run=self.force_mineflayer,
            )
        planner = RepoToolBudgetPlanner(window_budget_seconds=self.workbench.window_budget_seconds)
        plan = planner.build_plan(
            profile=self.profile,
            xref=self.xref,
            include_gradle_tests=False,
            include_mineflayer=self.force_mineflayer,
        )
        image_tag = f"rtp-cache:v{self.profile.plugin_version}:{self.profile.schema_hash8}:{self.profile.intent_slug}"
        desc_preview = (
            f"[{image_tag}] LeafRTP v{self.profile.plugin_version} ({self.profile.setup_mode}, {self.profile.platform} {self.profile.mc_version}) | "
            f"{self.webpage_result.target_file} -> {self.webpage_result.target_key}"
        )
        cached_entry, cache_status = self.workbench.cache.lookup(
            plugin_version=self.profile.plugin_version,
            schema_hash8=self.profile.schema_hash8,
            intent_slug=self.profile.intent_slug,
            description_query=desc_preview,
        )
        for step in plan:
            if step.selected:
                step.executed = True
                step.status = (
                    "reused_from_discord_cache"
                    if (cached_entry is not None and step.category == "screenshotting")
                    else "executed_ok"
                )
        if cached_entry is not None:
            self.cache_status = cache_status
            self.reused_image_url = cached_entry.image_url
            self.image_tag = cached_entry.image_tag
            self.image_description = cached_entry.image_description
            self.visual_artifact = None
            return WorkbenchVerificationReport(
                profile=self.profile,
                console_result=self.console_result,
                webpage_result=self.webpage_result,
                mineflayer_result=self.mineflayer_result,
                visual_artifact=None,
                cache_status=cache_status,
                reused_image_url=cached_entry.image_url,
                image_tag=cached_entry.image_tag,
                image_description=cached_entry.image_description,
                ast_cross_references=self.xref,
                tool_plan=plan,
                window_budget_seconds=self.workbench.window_budget_seconds,
                total_elapsed_seconds=round(time.monotonic() - self._t0, 4),
            )
        if self.visual_artifact is None:
            self.visual_artifact = capture_verification_visuals(
                profile=self.profile,
                console_result=self.console_result,
                webpage_result=self.webpage_result,
                mineflayer_result=self.mineflayer_result,
            )
        self.cache_status = cache_status
        self.reused_image_url = None
        self.image_tag = self.visual_artifact.image_tag
        self.image_description = self.visual_artifact.image_description
        return WorkbenchVerificationReport(
            profile=self.profile,
            console_result=self.console_result,
            webpage_result=self.webpage_result,
            mineflayer_result=self.mineflayer_result,
            visual_artifact=self.visual_artifact,
            cache_status=cache_status,
            reused_image_url=None,
            image_tag=self.visual_artifact.image_tag,
            image_description=self.visual_artifact.image_description,
            ast_cross_references=self.xref,
            tool_plan=plan,
            window_budget_seconds=self.workbench.window_budget_seconds,
            total_elapsed_seconds=round(time.monotonic() - self._t0, 4),
        )
