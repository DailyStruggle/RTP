#!/usr/bin/env python3
"""
One-time idempotent Discord server provisioner for `LeafCraft Plugin Support`.

Reorganizes the server in-place via the Discord REST API v10 (`--dry-run` by
default, `--apply` with `DISCORD_BOT_TOKEN` and `DISCORD_GUILD_ID`):
  1. Never deletes populated text channels. Moves legacy flat channels
     (`#support` -> `#old-support`, `#issues`, `#feature-requests`, `#faq`,
     `#recommended-specs`, `#role-assignment`, `#welcome`, `#info`, `#rules`,
     and duplicate per-plugin support channels) into a collapsed
     `ARCHIVE (Read-Only)` category with `VIEW_CHANNEL: true`,
     `READ_MESSAGE_HISTORY: true`, and `SEND_MESSAGES: false` for `@everyone`.
  2. Creates/updates the streamlined category hierarchy:
     - `WELCOME & INFO`: `#start-here`, `#announcements`, `#releases`, `#benchmarks`
     - `SUPPORT & COMMUNITY`: `#support` (`GUILD_FORUM` with `require_tag=True`), `#general`
     - `DEVELOPMENT`: `#development`
     - `ADMIN` (private): `#bbb-logs` (preserved), `#bot-triage-queue`
  3. Configures the `#support` Forum channel tags (`Project`, `Platform`, `Status`)
     and idempotently publishes/pins the 5 Rule D-006 quick-fix reference threads.
"""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import re
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Any

BOT_DIR = pathlib.Path(__file__).resolve().parent
ROOT = BOT_DIR.parent
DEFAULT_SPEC_PATH = BOT_DIR / "embeds.json"
DISCORD_LAYOUT_DOC = BOT_DIR / "DISCORD_LAYOUT.md"
DISCORD_API_BASE = "https://discord.com/api/v10"

# Discord channel types (API v10)
CHANNEL_TYPES = {
    "GUILD_TEXT": 0,
    "GUILD_CATEGORY": 4,
    "GUILD_ANNOUNCEMENT": 5,
    "GUILD_FORUM": 15,
}

# Discord permission bitflags
PERM_ADD_REACTIONS = 1 << 6
PERM_VIEW_CHANNEL = 1 << 10
PERM_SEND_MESSAGES = 1 << 11
PERM_READ_MESSAGE_HISTORY = 1 << 16
PERM_CREATE_PUBLIC_THREADS = 1 << 35
PERM_CREATE_PRIVATE_THREADS = 1 << 36
PERM_SEND_MESSAGES_IN_THREADS = 1 << 38

# Discord channel flags
CHANNEL_FLAG_PINNED = 1 << 1
CHANNEL_FLAG_REQUIRE_TAG = 1 << 4

SALES_HYPE_DENYLIST = (
    re.compile(r"(?i)\brevolutionary\b"),
    re.compile(r"(?i)\bblazingly\s+fast\b"),
    re.compile(r"(?i)\bgame[- ]changer\b"),
    re.compile(r"(?i)\bnext[- ]gen(eration)?\b"),
    re.compile(r"(?i)\bworld[- ]class\b"),
    re.compile(r"(?i)\bunparalleled\b"),
    re.compile(r"(?i)\bseamless(ly)?\b"),
)

MOJIBAKE_MARKERS = (
    "â€", "â”", "â–", "âš", "âœ", "Â«", "Â»", "Â·", "Â²", "Â°", "Â§", "\ufffd"  # mojibake-ok
)


@dataclass(frozen=True)
class ProvisionAction:
    op: str
    target: str
    payload: dict[str, Any] = field(default_factory=dict)
    reason: str = ""


@dataclass
class ProvisioningPlan:
    actions: list[ProvisionAction] = field(default_factory=list)

    def format_summary(self) -> str:
        if not self.actions:
            return "Guild already matches embeds.json specification (0 actions needed)."
        lines = [f"Provisioning plan ({len(self.actions)} action(s)):"]
        for idx, act in enumerate(self.actions, 1):
            lines.append(f"  {idx}. [{act.op}] {act.target} - {act.reason}")
        return "\n".join(lines)


def load_spec(spec_path: pathlib.Path = DEFAULT_SPEC_PATH) -> dict[str, Any]:
    """Loads `discord-support-bot/embeds.json`."""
    return json.loads(spec_path.read_text(encoding="utf-8"))


def build_archive_overwrites(guild_id: str) -> list[dict[str, Any]]:
    """
    Permission overwrites for `ARCHIVE (Read-Only)` and archived legacy channels:
    `@everyone` has `VIEW_CHANNEL: true`, `READ_MESSAGE_HISTORY: true`, and
    `SEND_MESSAGES: false` so historical troubleshooting stays searchable.
    """
    allow = PERM_VIEW_CHANNEL | PERM_READ_MESSAGE_HISTORY
    deny = (
        PERM_SEND_MESSAGES
        | PERM_SEND_MESSAGES_IN_THREADS
        | PERM_CREATE_PUBLIC_THREADS
        | PERM_CREATE_PRIVATE_THREADS
        | PERM_ADD_REACTIONS
    )
    return [
        {
            "id": str(guild_id),
            "type": 0,  # role (@everyone has id == guild_id)
            "allow": str(allow),
            "deny": str(deny),
        }
    ]


def build_read_only_overwrites(guild_id: str) -> list[dict[str, Any]]:
    """Permission overwrites for public read-only channels (`#start-here`, `#benchmarks`)."""
    allow = PERM_VIEW_CHANNEL | PERM_READ_MESSAGE_HISTORY
    deny = (
        PERM_SEND_MESSAGES
        | PERM_SEND_MESSAGES_IN_THREADS
        | PERM_CREATE_PUBLIC_THREADS
        | PERM_CREATE_PRIVATE_THREADS
    )
    return [
        {
            "id": str(guild_id),
            "type": 0,
            "allow": str(allow),
            "deny": str(deny),
        }
    ]


def build_private_admin_overwrites(guild_id: str) -> list[dict[str, Any]]:
    """Permission overwrites for private `ADMIN` channels (`#bbb-logs`, `#bot-triage-queue`)."""
    return [
        {
            "id": str(guild_id),
            "type": 0,
            "allow": "0",
            "deny": str(PERM_VIEW_CHANNEL),
        }
    ]


def _overwrites_match(actual: list[dict[str, Any]] | None, expected: list[dict[str, Any]]) -> bool:
    if not actual:
        return False
    by_id = {str(o.get("id")): o for o in actual}
    for exp in expected:
        act = by_id.get(str(exp["id"]))
        if not act:
            return False
        act_allow = int(act.get("allow", 0))
        act_deny = int(act.get("deny", 0))
        exp_allow = int(exp["allow"])
        exp_deny = int(exp["deny"])
        if (act_allow & exp_allow) != exp_allow or (act_deny & exp_deny) != exp_deny:
            return False
    return True


def strip_leading_emoji_label(raw_name: str) -> tuple[str | None, str]:
    """
    Splits a leading non-ASCII emoji prefix (`"🌿 LeafRTP"`) into
    `("🌿", "LeafRTP")` so Discord Forum `available_tags` do not render
    duplicate icons (`[emoji_name] + [name]`).
    """
    cleaned = raw_name.strip()
    parts = cleaned.split(" ", 1)
    if len(parts) == 2 and not parts[0].isascii():
        return parts[0], parts[1].strip()
    return None, cleaned


def build_forum_available_tags(forum_tags_spec: dict[str, list[dict[str, Any]]]) -> list[dict[str, Any]]:
    """Flattens the Project, Platform, and Status tag groups into Discord `available_tags`."""
    tags: list[dict[str, Any]] = []
    for group in ("project", "platform", "status"):
        for item in forum_tags_spec.get(group, []):
            emoji_prefix, clean_name = strip_leading_emoji_label(item["name"])
            if emoji_prefix:
                tags.append(
                    {
                        "name": clean_name,
                        "moderated": bool(item.get("moderated", False)),
                        "emoji_name": emoji_prefix,
                    }
                )
            else:
                tags.append(
                    {
                        "name": clean_name,
                        "moderated": bool(item.get("moderated", False)),
                    }
                )
    return tags


def validate_rule_d006_copy(spec: dict[str, Any], layout_doc_path: pathlib.Path = DISCORD_LAYOUT_DOC) -> list[str]:
    """
    Validates that all embeds, pinned Forum threads, and `DISCORD_LAYOUT.md`
    adhere to Rule D-006:
      - No em-dashes (\u2014) or en-dashes (\u2013)
      - No mojibake sequences
      - No sales hype denylist words
      - First-person maintainer voice ("I") present in embeds and pinned threads
    Returns a list of violation descriptions (empty if valid).
    """
    violations: list[str] = []

    def check_text(label: str, text: str) -> None:
        if "\u2014" in text or "\u2013" in text:
            violations.append(f"{label}: contains em/en dash (use ASCII '-' only per Rule D-006)")
        for marker in MOJIBAKE_MARKERS:
            if marker in text:
                violations.append(f"{label}: contains mojibake marker {marker!r}")
        for pat in SALES_HYPE_DENYLIST:
            m = pat.search(text)
            if m:
                violations.append(f"{label}: contains sales hype word {m.group(0)!r}")

    for ch_name, embeds in spec.get("channel_embeds", {}).items():
        for idx, emb in enumerate(embeds):
            label = f"channel_embeds.{ch_name}[{idx}]"
            check_text(label, emb.get("title", ""))
            check_text(label, emb.get("description", ""))

    for idx, thread in enumerate(spec.get("pinned_forum_threads", [])):
        label = f"pinned_forum_threads[{idx}] ({thread.get('title', '')})"
        check_text(label, thread.get("title", ""))
        check_text(label, thread.get("body", ""))

    start_text = " ".join(
        e.get("description", "") for e in spec.get("channel_embeds", {}).get("start-here", [])
    )
    if not re.search(r"\b(I|my)\b", start_text):
        violations.append("channel_embeds.start-here: missing first-person engineering voice ('I' / 'my')")

    if layout_doc_path.is_file():
        check_text("discord-support-bot/DISCORD_LAYOUT.md", layout_doc_path.read_text(encoding="utf-8"))

    return violations


def default_legacy_guild_snapshot(guild_id: str = "100000000000000000") -> list[dict[str, Any]]:
    """
    Returns a representative channel snapshot matching the pre-overhaul
    `LeafCraft Plugin Support` Discord layout (`info`, `Text Channels`,
    `RTP support`, `OnePlayerSleep support`, `TreeGen support`, `admin`).
    """
    return [
        {"id": "cat_info", "name": "info", "type": 4},
        {"id": "ch_rules", "name": "rules", "type": 0, "parent_id": "cat_info"},
        {"id": "ch_announcements", "name": "announcements", "type": 5, "parent_id": "cat_info"},
        {"id": "cat_text", "name": "Text Channels", "type": 4},
        {"id": "ch_welcome", "name": "welcome", "type": 0, "parent_id": "cat_text"},
        {"id": "ch_roles", "name": "role-assignment", "type": 0, "parent_id": "cat_text"},
        {"id": "ch_general", "name": "general", "type": 0, "parent_id": "cat_text"},
        {"id": "cat_rtp", "name": "RTP support", "type": 4},
        {"id": "ch_rtp_info", "name": "info", "type": 0, "parent_id": "cat_rtp"},
        {"id": "ch_rtp_faq", "name": "faq", "type": 0, "parent_id": "cat_rtp"},
        {"id": "ch_rtp_specs", "name": "recommended-specs", "type": 0, "parent_id": "cat_rtp"},
        {"id": "ch_rtp_issues", "name": "issues", "type": 0, "parent_id": "cat_rtp"},
        {"id": "ch_rtp_support", "name": "support", "type": 0, "parent_id": "cat_rtp"},
        {"id": "ch_rtp_features", "name": "feature-requests", "type": 0, "parent_id": "cat_rtp"},
        {"id": "ch_rtp_dev", "name": "development", "type": 0, "parent_id": "cat_rtp"},
        {"id": "cat_ops", "name": "OnePlayerSleep support", "type": 4},
        {"id": "ch_ops_support", "name": "ops-support", "type": 0, "parent_id": "cat_ops"},
        {"id": "cat_treegen", "name": "TreeGen support", "type": 4},
        {"id": "ch_treegen_support", "name": "treegen-support", "type": 0, "parent_id": "cat_treegen"},
        {
            "id": "cat_admin",
            "name": "admin",
            "type": 4,
            "permission_overwrites": build_private_admin_overwrites(guild_id),
        },
        {
            "id": "ch_bbb_logs",
            "name": "bbb-logs",
            "type": 0,
            "parent_id": "cat_admin",
            "permission_overwrites": build_private_admin_overwrites(guild_id),
        },
    ]


def enrich_threads_with_starter_bodies(
    client: Any,
    threads: list[dict[str, Any]],
    spec: dict[str, Any],
) -> None:
    """
    Populates `th['body']` via `GET /channels/{th_id}/messages/{th_id}` for
    existing Forum reference threads whose title matches `pinned_forum_threads`
    when Discord's `/threads/active` or `/threads/archived/public` response omits
    the starter message body.
    """
    pinned_titles = {
        str(p.get("title", "")).strip()
        for p in spec.get("pinned_forum_threads", [])
        if isinstance(p, dict) and str(p.get("title", "")).strip()
    }
    for th in threads:
        if not isinstance(th, dict):
            continue
        th_title = str(th.get("name", th.get("title", ""))).strip()
        th_id = str(th.get("id", "")).strip()
        if th_title in pinned_titles and th_id and "body" not in th and "message" not in th:
            try:
                starter_msg = client.request("GET", f"/channels/{th_id}/messages/{th_id}") or {}
                if isinstance(starter_msg, dict) and "content" in starter_msg:
                    th["body"] = str(starter_msg.get("content") or "")
            except OSError:
                pass


def compute_provisioning_plan(
    current_channels: list[dict[str, Any]],
    current_roles: list[dict[str, Any]],
    existing_threads: list[dict[str, Any]],
    spec: dict[str, Any],
    guild_id: str,
    published_embed_channels: set[str] | None = None,
) -> ProvisioningPlan:
    """
    Computes an idempotent, non-destructive `ProvisioningPlan` transforming
    `current_channels` into the target hierarchy in `spec`.
    Never deletes populated text channels.
    """
    plan = ProvisioningPlan()
    published = published_embed_channels or set()

    # 1. Ensure roles exist
    existing_role_names = {r.get("name", "").lower(): r for r in current_roles}
    for role_spec in spec.get("roles", []):
        r_name = role_spec["name"]
        if r_name.lower() not in existing_role_names:
            plan.actions.append(
                ProvisionAction(
                    op="CREATE_ROLE",
                    target=r_name,
                    payload={
                        "name": r_name,
                        "color": role_spec.get("color", 0),
                        "hoist": role_spec.get("hoist", False),
                        "mentionable": role_spec.get("mentionable", False),
                    },
                    reason="Ensure @Pro Supporter role exists for BuiltByBit priority routing.",
                )
            )

    # Index existing categories & non-category channels
    categories_by_lower: dict[str, dict[str, Any]] = {}
    channels_by_id: dict[str, dict[str, Any]] = {}
    for ch in current_channels:
        channels_by_id[str(ch["id"])] = ch
        if ch.get("type") == CHANNEL_TYPES["GUILD_CATEGORY"]:
            categories_by_lower[ch["name"].lower()] = ch

    # 2. Ensure target categories + ARCHIVE category exist
    archive_cat_name = spec["archive_category"]
    archive_overwrites = build_archive_overwrites(guild_id)
    archive_cat = categories_by_lower.get(archive_cat_name.lower())
    archive_cat_id = str(archive_cat["id"]) if archive_cat else f"planned_cat_{archive_cat_name}"

    if not archive_cat:
        plan.actions.append(
            ProvisionAction(
                op="CREATE_CATEGORY",
                target=archive_cat_name,
                payload={
                    "name": archive_cat_name,
                    "type": CHANNEL_TYPES["GUILD_CATEGORY"],
                    "permission_overwrites": archive_overwrites,
                },
                reason="Create collapsed read-only archive category for legacy text channels.",
            )
        )
    elif not _overwrites_match(archive_cat.get("permission_overwrites"), archive_overwrites):
        plan.actions.append(
            ProvisionAction(
                op="UPDATE_CATEGORY",
                target=archive_cat_name,
                payload={
                    "id": archive_cat_id,
                    "permission_overwrites": archive_overwrites,
                },
                reason="Lock SEND_MESSAGES on ARCHIVE category while preserving VIEW_CHANNEL + READ_MESSAGE_HISTORY.",
            )
        )

    target_cat_ids: dict[str, str] = {}
    for cat_spec in spec.get("categories", []):
        cat_name = cat_spec["name"]
        existing_cat = categories_by_lower.get(cat_name.lower())
        expected_overwrites = (
            build_private_admin_overwrites(guild_id) if cat_spec.get("private") else []
        )
        if not existing_cat:
            planned_id = f"planned_cat_{cat_name}"
            target_cat_ids[cat_name] = planned_id
            payload: dict[str, Any] = {
                "name": cat_name,
                "type": CHANNEL_TYPES["GUILD_CATEGORY"],
            }
            if expected_overwrites:
                payload["permission_overwrites"] = expected_overwrites
            plan.actions.append(
                ProvisionAction(
                    op="CREATE_CATEGORY",
                    target=cat_name,
                    payload=payload,
                    reason=f"Create target category {cat_name}.",
                )
            )
        else:
            cat_id = str(existing_cat["id"])
            target_cat_ids[cat_name] = cat_id
            needs_rename = existing_cat["name"] != cat_name
            needs_perms = bool(
                expected_overwrites
                and not _overwrites_match(existing_cat.get("permission_overwrites"), expected_overwrites)
            )
            if needs_rename or needs_perms:
                upd_payload: dict[str, Any] = {"id": cat_id, "name": cat_name}
                if expected_overwrites:
                    upd_payload["permission_overwrites"] = expected_overwrites
                plan.actions.append(
                    ProvisionAction(
                        op="UPDATE_CATEGORY",
                        target=cat_name,
                        payload=upd_payload,
                        reason=f"Align category {cat_name} name and permission overwrites.",
                    )
                )

    # Collect target active channel names -> (cat_name, ch_spec)
    active_targets: dict[tuple[str, int], tuple[str, dict[str, Any]]] = {}
    active_names_by_type: dict[str, int] = {}
    for cat_spec in spec.get("categories", []):
        for ch_spec in cat_spec.get("channels", []):
            ch_type_int = CHANNEL_TYPES[ch_spec["type"]]
            active_targets[(ch_spec["name"].lower(), ch_type_int)] = (cat_spec["name"], ch_spec)
            active_names_by_type[ch_spec["name"].lower()] = ch_type_int

    legacy_names = {n.lower() for n in spec.get("legacy_channels_to_archive", [])}
    legacy_cat_names = {c.lower() for c in spec.get("legacy_categories_to_collapse", [])}

    # 3. Archive legacy flat text channels non-destructively
    for ch in current_channels:
        ch_type = ch.get("type")
        if ch_type == CHANNEL_TYPES["GUILD_CATEGORY"]:
            continue
        ch_name = ch["name"]
        ch_lower = ch_name.lower()
        parent_cat = channels_by_id.get(str(ch.get("parent_id", "")))
        parent_name_lower = parent_cat["name"].lower() if parent_cat else ""

        # Check if this channel is one of the active target channels of the matching type
        if (ch_lower, ch_type) in active_targets:
            continue

        is_legacy_named = ch_lower in legacy_names or ch_lower.startswith("old-")
        is_in_legacy_plugin_cat = parent_name_lower in (
            "oneplayersleep support",
            "treegen support",
            "rtp support",
        )

        if is_legacy_named or is_in_legacy_plugin_cat:
            new_name = "old-support" if ch_lower == "support" and ch_type == CHANNEL_TYPES["GUILD_TEXT"] else ch_name
            already_in_archive = str(ch.get("parent_id")) == archive_cat_id
            perms_ok = _overwrites_match(ch.get("permission_overwrites"), archive_overwrites)
            name_ok = ch_name == new_name

            if not (already_in_archive and perms_ok and name_ok):
                plan.actions.append(
                    ProvisionAction(
                        op="ARCHIVE_CHANNEL",
                        target=ch_name,
                        payload={
                            "id": str(ch["id"]),
                            "name": new_name,
                            "parent_id": archive_cat_id,
                            "permission_overwrites": archive_overwrites,
                        },
                        reason=(
                            f"Move legacy #{ch_name} to {archive_cat_name} as #{new_name} "
                            "(VIEW_CHANNEL=true, READ_MESSAGE_HISTORY=true, SEND_MESSAGES=false)."
                        ),
                    )
                )

    # 4. Create or update active channels in the streamlined hierarchy
    for cat_spec in spec.get("categories", []):
        cat_name = cat_spec["name"]
        cat_id = target_cat_ids[cat_name]
        is_private_cat = bool(cat_spec.get("private", False))

        for ch_spec in cat_spec.get("channels", []):
            ch_name = ch_spec["name"]
            ch_type_int = CHANNEL_TYPES[ch_spec["type"]]

            if is_private_cat:
                expected_overwrites = build_private_admin_overwrites(guild_id)
            elif ch_spec.get("read_only"):
                expected_overwrites = build_read_only_overwrites(guild_id)
            else:
                expected_overwrites = []

            matching_ch = next(
                (
                    c
                    for c in current_channels
                    if c.get("name", "").lower() == ch_name.lower() and c.get("type") == ch_type_int
                ),
                None,
            )

            if ch_type_int == CHANNEL_TYPES["GUILD_FORUM"]:
                forum_tags = build_forum_available_tags(spec.get("forum_tags", {}))
                forum_flags = CHANNEL_FLAG_REQUIRE_TAG if ch_spec.get("require_tag", True) else 0
                default_emoji = {"emoji_name": ch_spec.get("default_reaction_emoji", "✅")}
                forum_payload: dict[str, Any] = {
                    "name": ch_name,
                    "type": ch_type_int,
                    "parent_id": cat_id,
                    "topic": ch_spec.get("topic", ""),
                    "flags": forum_flags,
                    "default_reaction_emoji": default_emoji,
                    "default_sort_order": ch_spec.get("default_sort_order", 0),
                    "default_forum_layout": ch_spec.get("default_forum_layout", 1),
                    "available_tags": forum_tags,
                }
                if not matching_ch:
                    plan.actions.append(
                        ProvisionAction(
                            op="CREATE_FORUM_CHANNEL",
                            target=ch_name,
                            payload=forum_payload,
                            reason="Create unified #support GUILD_FORUM with required Project/Platform/Status tags.",
                        )
                    )
                else:
                    existing_tags_by_name: dict[str, str] = {}
                    for t in matching_ch.get("available_tags", []):
                        if "name" in t and "id" in t:
                            raw_t_name = str(t.get("name"))
                            _, stripped_t_name = strip_leading_emoji_label(raw_t_name)
                            existing_tags_by_name[raw_t_name] = str(t["id"])
                            existing_tags_by_name[stripped_t_name] = str(t["id"])
                    existing_tag_names = [t.get("name") for t in matching_ch.get("available_tags", [])]
                    desired_tag_names = [t["name"] for t in forum_tags]
                    same_parent = str(matching_ch.get("parent_id")) == cat_id
                    same_flags = (int(matching_ch.get("flags", 0)) & CHANNEL_FLAG_REQUIRE_TAG) == forum_flags
                    same_tags = existing_tag_names == desired_tag_names
                    same_topic = (matching_ch.get("topic") or "") == ch_spec.get("topic", "")
                    if not (same_parent and same_flags and same_tags and same_topic):
                        merged_tags: list[dict[str, Any]] = []
                        for tag_item in forum_tags:
                            tag_copy = dict(tag_item)
                            if tag_copy["name"] in existing_tags_by_name:
                                tag_copy["id"] = existing_tags_by_name[tag_copy["name"]]
                            merged_tags.append(tag_copy)
                        forum_payload["available_tags"] = merged_tags
                        forum_payload["id"] = str(matching_ch["id"])
                        plan.actions.append(
                            ProvisionAction(
                                op="UPDATE_FORUM_CHANNEL",
                                target=ch_name,
                                payload=forum_payload,
                                reason="Synchronize #support Forum tags, flags, and post guidelines.",
                            )
                        )
                continue

            # Standard text or announcement channel
            ch_payload: dict[str, Any] = {
                "name": ch_name,
                "type": ch_type_int,
                "parent_id": cat_id,
                "topic": ch_spec.get("topic", ""),
            }
            if expected_overwrites:
                ch_payload["permission_overwrites"] = expected_overwrites

            if not matching_ch:
                plan.actions.append(
                    ProvisionAction(
                        op="CREATE_CHANNEL",
                        target=ch_name,
                        payload=ch_payload,
                        reason=f"Create #{ch_name} under {cat_name}.",
                    )
                )
            else:
                same_parent = str(matching_ch.get("parent_id")) == cat_id
                same_perms = (
                    True
                    if not expected_overwrites
                    else _overwrites_match(matching_ch.get("permission_overwrites"), expected_overwrites)
                )
                if not (same_parent and same_perms):
                    ch_payload["id"] = str(matching_ch["id"])
                    plan.actions.append(
                        ProvisionAction(
                            op="UPDATE_CHANNEL",
                            target=ch_name,
                            payload=ch_payload,
                            reason=f"Move/update #{ch_name} under {cat_name}.",
                        )
                    )

            # Publish Rule D-006 embeds for #start-here and #benchmarks if configured
            if ch_name in spec.get("channel_embeds", {}) and ch_name not in published:
                plan.actions.append(
                    ProvisionAction(
                        op="PUBLISH_EMBEDS",
                        target=ch_name,
                        payload={
                            "channel": ch_name,
                            "embeds": spec["channel_embeds"][ch_name],
                        },
                        reason=f"Publish Rule D-006 embeds to #{ch_name}.",
                    )
                )

    # 5. Ensure the 5 Forum reference threads exist in #support.
    # Note: Discord GUILD_FORUM channels enforce a hard limit of 1 pinned post
    # (CHANNEL_FLAG_PINNED) per forum channel. We pin the primary "Read First"
    # intake guide (idx == 0) at the top of #support and publish the 4 Quick Fix
    # reference guides without CHANNEL_FLAG_PINNED.
    threads_by_title = {t.get("name", t.get("title", "")).strip(): t for t in existing_threads}
    for idx, pinned_spec in enumerate(spec.get("pinned_forum_threads", [])):
        title = pinned_spec["title"].strip()
        should_pin = bool(pinned_spec.get("pin", idx == 0))
        desired_flags = CHANNEL_FLAG_PINNED if should_pin else 0
        existing = threads_by_title.get(title)
        if not existing:
            plan.actions.append(
                ProvisionAction(
                    op="CREATE_PINNED_THREAD",
                    target=title,
                    payload={
                        "name": title,
                        "applied_tags": pinned_spec.get("tags", []),
                        "message": {"content": pinned_spec["body"]},
                        "flags": desired_flags,
                    },
                    reason=f"Create and pin Forum guide: {title!r}.",
                )
            )
        else:
            has_body = ("body" in existing) or ("message" in existing)
            existing_body = existing.get("body", (existing.get("message") or {}).get("content", ""))
            is_pinned = bool(int(existing.get("flags", 0)) & CHANNEL_FLAG_PINNED)
            body_mismatch = has_body and (existing_body != pinned_spec["body"])
            pin_mismatch = should_pin and not is_pinned
            if body_mismatch or pin_mismatch:
                plan.actions.append(
                    ProvisionAction(
                        op="UPDATE_PINNED_THREAD",
                        target=title,
                        payload={
                            "id": str(existing["id"]),
                            "name": title,
                            "applied_tags": pinned_spec.get("tags", []),
                            "message": {"content": pinned_spec["body"]},
                            "flags": desired_flags,
                        },
                        reason=f"Update pinned Forum guide content/pin state: {title!r}.",
                    )
                )

    return plan


class DiscordRestClient:
    """Minimal stdlib REST client for Discord API v10 with 429 rate-limit backoff."""

    def __init__(self, bot_token: str, api_base: str = DISCORD_API_BASE) -> None:
        self.bot_token = bot_token
        self.api_base = api_base.rstrip("/")

    def request(self, method: str, path: str, payload: dict[str, Any] | None = None) -> Any:
        url = f"{self.api_base}/{path.lstrip('/')}"
        headers = {
            "Authorization": f"Bot {self.bot_token}",
            "User-Agent": "LeafRTP-DiscordSetup/1.0 (+https://github.com/DailyStruggle/RTP)",
        }
        if payload is not None:
            data: bytes | None = json.dumps(payload).encode("utf-8")
            headers["Content-Type"] = "application/json"
        elif method in ("PUT", "POST"):
            data = b""
            headers["Content-Length"] = "0"
        else:
            data = None

        for attempt in range(6):
            req = urllib.request.Request(url, data=data, method=method, headers=headers)
            try:
                with urllib.request.urlopen(req, timeout=15) as resp:
                    raw = resp.read().decode("utf-8")
                    return json.loads(raw) if raw else None
            except urllib.error.HTTPError as exc:
                raw_err = ""
                try:
                    raw_err = exc.read().decode("utf-8", errors="replace")
                except OSError:
                    pass
                if exc.code == 429 and attempt < 5:
                    retry_after = 2.0
                    try:
                        err_body = json.loads(raw_err)
                        retry_after = float(err_body.get("retry_after", 2.0))
                    except (ValueError, KeyError, TypeError):
                        pass
                    time.sleep(min(max(retry_after, 0.5), 30.0))
                    continue
                if raw_err:
                    exc.msg = f"{exc.msg} ({method} {path}: {raw_err})"
                raise

    def request_multipart(
        self,
        method: str,
        path: str,
        payload: dict[str, Any],
        files: list[tuple[str, bytes, str]],
    ) -> Any:
        """Sends a `multipart/form-data` request with `payload_json` and `files[n]` attachments."""
        boundary = f"----LeafRTPBoundary{int(time.time() * 1000000)}"
        parts: list[bytes] = []
        parts.append(
            (
                f"--{boundary}\r\n"
                'Content-Disposition: form-data; name="payload_json"\r\n'
                "Content-Type: application/json\r\n\r\n"
            ).encode("utf-8")
            + json.dumps(payload).encode("utf-8")
            + b"\r\n"
        )
        for idx, (filename, file_bytes, content_type) in enumerate(files):
            safe_name = filename.replace('"', "_")
            parts.append(
                (
                    f"--{boundary}\r\n"
                    f'Content-Disposition: form-data; name="files[{idx}]"; filename="{safe_name}"\r\n'
                    f"Content-Type: {content_type}\r\n\r\n"
                ).encode("utf-8")
                + file_bytes
                + b"\r\n"
            )
        parts.append(f"--{boundary}--\r\n".encode("utf-8"))
        body = b"".join(parts)

        url = f"{self.api_base}/{path.lstrip('/')}"
        headers = {
            "Authorization": f"Bot {self.bot_token}",
            "User-Agent": "LeafRTP-DiscordSetup/1.0 (+https://github.com/DailyStruggle/RTP)",
            "Content-Type": f"multipart/form-data; boundary={boundary}",
        }
        for attempt in range(6):
            req = urllib.request.Request(url, data=body, method=method, headers=headers)
            try:
                with urllib.request.urlopen(req, timeout=20) as resp:
                    raw = resp.read().decode("utf-8")
                    return json.loads(raw) if raw else None
            except urllib.error.HTTPError as exc:
                raw_err = ""
                try:
                    raw_err = exc.read().decode("utf-8", errors="replace")
                except OSError:
                    pass
                if exc.code == 429 and attempt < 5:
                    retry_after = 2.0
                    try:
                        err_body = json.loads(raw_err)
                        retry_after = float(err_body.get("retry_after", 2.0))
                    except (ValueError, KeyError, TypeError):
                        pass
                    time.sleep(min(max(retry_after, 0.5), 30.0))
                    continue
                if raw_err:
                    exc.msg = f"{exc.msg} ({method} {path}: {raw_err})"
                raise


def apply_provisioning_plan(
    client: DiscordRestClient,
    plan: ProvisioningPlan,
    guild_id: str,
    current_channels: list[dict[str, Any]] | None = None,
) -> list[dict[str, Any]]:
    """
    Executes a `ProvisioningPlan` against the Discord REST API v10, resolving
    planned category/channel IDs and Forum tag snowflake IDs as resources are created.
    """
    executed: list[dict[str, Any]] = []
    resolved_ids: dict[str, str] = {}
    forum_tag_ids: dict[str, str] = {}

    def _register_tag_map(tags_list: list[dict[str, Any]]) -> None:
        for t in tags_list:
            if "name" in t and "id" in t:
                t_id = str(t["id"])
                t_name = str(t["name"]).strip()
                t_emoji = str(t.get("emoji_name") or "").strip()
                _, stripped = strip_leading_emoji_label(t_name)
                forum_tag_ids[t_name] = t_id
                forum_tag_ids[stripped] = t_id
                if t_emoji:
                    forum_tag_ids[f"{t_emoji} {stripped}"] = t_id

    for ch in current_channels or []:
        ch_id = str(ch.get("id", ""))
        ch_name = str(ch.get("name", ""))
        ch_type = ch.get("type")
        if ch_type == CHANNEL_TYPES["GUILD_CATEGORY"]:
            resolved_ids[f"planned_cat_{ch_name}"] = ch_id
        else:
            resolved_ids[f"ch_{ch_name}"] = ch_id
            if ch_type == CHANNEL_TYPES["GUILD_FORUM"] and ch_name.lower() == "support":
                resolved_ids["forum_support"] = ch_id
                _register_tag_map(ch.get("available_tags", []))

    def resolve_tag_ids(raw_tags: list[str]) -> list[str]:
        resolved: list[str] = []
        for t in raw_tags:
            _, stripped = strip_leading_emoji_label(t)
            resolved.append(forum_tag_ids.get(t, forum_tag_ids.get(stripped, t)))
        return resolved

    for act in plan.actions:
        payload = dict(act.payload)
        parent_id = payload.get("parent_id")
        if isinstance(parent_id, str) and parent_id in resolved_ids:
            payload["parent_id"] = resolved_ids[parent_id]

        if act.op == "CREATE_ROLE":
            res = client.request("POST", f"/guilds/{guild_id}/roles", payload)
            executed.append({"op": act.op, "target": act.target, "result": res})
        elif act.op == "CREATE_CATEGORY":
            res = client.request("POST", f"/guilds/{guild_id}/channels", payload)
            if isinstance(res, dict) and "id" in res:
                resolved_ids[f"planned_cat_{act.target}"] = str(res["id"])
            executed.append({"op": act.op, "target": act.target, "result": res})
        elif act.op in ("UPDATE_CATEGORY", "ARCHIVE_CHANNEL", "UPDATE_CHANNEL", "UPDATE_FORUM_CHANNEL"):
            ch_id = payload.pop("id")
            res = client.request("PATCH", f"/channels/{ch_id}", payload)
            if act.op == "UPDATE_FORUM_CHANNEL" and isinstance(res, dict) and "id" in res:
                resolved_ids["forum_support"] = str(res["id"])
                _register_tag_map(res.get("available_tags", []))
            executed.append({"op": act.op, "target": act.target, "result": res})
        elif act.op in ("CREATE_CHANNEL", "CREATE_FORUM_CHANNEL"):
            res = client.request("POST", f"/guilds/{guild_id}/channels", payload)
            if isinstance(res, dict) and "id" in res:
                resolved_ids[f"ch_{act.target}"] = str(res["id"])
                if act.op == "CREATE_FORUM_CHANNEL":
                    resolved_ids["forum_support"] = str(res["id"])
                    _register_tag_map(res.get("available_tags", []))
            executed.append({"op": act.op, "target": act.target, "result": res})
        elif act.op == "PUBLISH_EMBEDS":
            ch_id = resolved_ids.get(f"ch_{act.target}", act.target)
            res = client.request("POST", f"/channels/{ch_id}/messages", {"embeds": payload["embeds"]})
            executed.append({"op": act.op, "target": act.target, "result": res})
        elif act.op == "CREATE_PINNED_THREAD":
            forum_id = resolved_ids.get("forum_support", "support")
            if "applied_tags" in payload:
                payload["applied_tags"] = resolve_tag_ids(list(payload["applied_tags"]))
            flags = payload.pop("flags", None)
            res = client.request("POST", f"/channels/{forum_id}/threads", payload)
            if flags and isinstance(res, dict) and "id" in res:
                try:
                    client.request("PATCH", f"/channels/{res['id']}", {"flags": flags})
                except urllib.error.HTTPError as exc:
                    if exc.code not in (400, 403):
                        raise
                    print(
                        f"Note: Could not pin thread {act.target!r} (Discord allows 1 pinned post per Forum): {exc}",
                        file=sys.stderr,
                    )
            executed.append({"op": act.op, "target": act.target, "result": res})
        elif act.op == "UPDATE_PINNED_THREAD":
            th_id = payload.pop("id")
            msg_payload = payload.pop("message", None)
            flags = payload.pop("flags", None)
            if "applied_tags" in payload:
                payload["applied_tags"] = resolve_tag_ids(list(payload["applied_tags"]))
            res = client.request("PATCH", f"/channels/{th_id}", payload)
            if flags:
                try:
                    client.request("PATCH", f"/channels/{th_id}", {"flags": flags})
                except urllib.error.HTTPError as exc:
                    if exc.code not in (400, 403):
                        raise
            if isinstance(msg_payload, dict) and "content" in msg_payload:
                client.request("PATCH", f"/channels/{th_id}/messages/{th_id}", {"content": msg_payload["content"]})
            executed.append({"op": act.op, "target": act.target, "result": res})

    return executed


def _read_home_secret_file(filename: str) -> str:
    """Reads a single-value credential file from `~/<filename>` without printing or logging its contents."""
    path = pathlib.Path(f"~/{filename}").expanduser()
    if not path.is_file():
        return ""
    try:
        raw = path.read_text(encoding="utf-8").strip()
    except OSError:
        return ""
    for line in raw.splitlines():
        s = line.strip()
        if not s or s.startswith("#"):
            continue
        if "=" in s and re.match(r"^[A-Za-z_][A-Za-z0-9_]*\s*=", s):
            _, s = s.split("=", 1)
            s = s.strip()
        if (s.startswith('"') and s.endswith('"')) or (s.startswith("'") and s.endswith("'")):
            s = s[1:-1].strip()
        if s:
            return s
    return ""


def resolve_discord_bot_token() -> str:
    """Resolves `DISCORD_BOT_TOKEN` from environment or `~/.DISCORD_BOT_TOKEN`."""
    env_val = os.environ.get("DISCORD_BOT_TOKEN", "").strip()
    if env_val:
        return env_val
    return _read_home_secret_file(".DISCORD_BOT_TOKEN")


def resolve_discord_guild_id(default: str = "100000000000000000") -> str:
    """Resolves `DISCORD_GUILD_ID` from environment or `~/.DISCORD_TEST_SERVER_ID` / `~/.DISCORD_GUILD_ID`."""
    for env_key in ("DISCORD_GUILD_ID", "DISCORD_TEST_SERVER_ID"):
        env_val = os.environ.get(env_key, "").strip()
        if env_val and env_val != "100000000000000000":
            return env_val
    for fname in (".DISCORD_TEST_SERVER_ID", ".DISCORD_GUILD_ID"):
        file_val = _read_home_secret_file(fname)
        if file_val and file_val != "100000000000000000":
            return file_val
    return default


def resolve_discord_maintainer_id(default: str = "999999999999999999") -> str:
    """Resolves `DISCORD_MAINTAINER_ID` from environment or `~/.DISCORD_MAINTAINER_ID`."""
    env_val = os.environ.get("DISCORD_MAINTAINER_ID", "").strip()
    if env_val and env_val != "999999999999999999":
        return env_val
    file_val = _read_home_secret_file(".DISCORD_MAINTAINER_ID")
    if file_val:
        return file_val
    return default


def reset_test_server(
    client: DiscordRestClient,
    guild_id: str,
    spec: dict[str, Any],
    state_path: pathlib.Path | None = None,
) -> dict[str, int]:
    """
    Clears user test threads in `#support` (preserving `pinned_forum_threads`
    from `embeds.json`), removes bot triage digest messages in
    `#bot-triage-queue`, and deletes local `bot-state.json` so live test runs
    can be repeated cleanly.
    """
    pinned_titles = {
        str(p.get("title", "")).strip()
        for p in spec.get("pinned_forum_threads", [])
        if isinstance(p, dict) and str(p.get("title", "")).strip()
    }
    channels = client.request("GET", f"/guilds/{guild_id}/channels") or []
    support_ch_id = ""
    triage_ch_id = ""
    for ch in channels:
        if not isinstance(ch, dict):
            continue
        ch_name = str(ch.get("name", "")).strip().lower()
        ch_type = ch.get("type")
        if ch_type == CHANNEL_TYPES["GUILD_FORUM"] and ch_name == "support":
            support_ch_id = str(ch.get("id", ""))
        elif ch_type == CHANNEL_TYPES["GUILD_TEXT"] and ch_name == "bot-triage-queue":
            triage_ch_id = str(ch.get("id", ""))

    deleted_threads = 0
    if support_ch_id:
        active_resp = client.request("GET", f"/guilds/{guild_id}/threads/active") or {}
        all_threads: list[dict[str, Any]] = (
            list(active_resp.get("threads", []))
            if isinstance(active_resp, dict)
            else []
        )
        try:
            arch_resp = client.request("GET", f"/channels/{support_ch_id}/threads/archived/public") or {}
            if isinstance(arch_resp, dict):
                all_threads.extend(arch_resp.get("threads", []))
        except OSError:
            pass

        seen_thread_ids: set[str] = set()
        for th in all_threads:
            if not isinstance(th, dict):
                continue
            th_id = str(th.get("id", "")).strip()
            if not th_id or th_id in seen_thread_ids:
                continue
            seen_thread_ids.add(th_id)
            if str(th.get("parent_id", "")) != support_ch_id:
                continue
            th_title = str(th.get("name", th.get("title", ""))).strip()
            if th_title in pinned_titles or (int(th.get("flags", 0)) & CHANNEL_FLAG_PINNED):
                continue
            try:
                client.request("DELETE", f"/channels/{th_id}")
                deleted_threads += 1
            except OSError:
                pass

    deleted_triage_msgs = 0
    if triage_ch_id:
        seen_triage_ids: set[str] = set()
        for _ in range(10):
            try:
                msgs = client.request("GET", f"/channels/{triage_ch_id}/messages?limit=50") or []
                if not msgs:
                    break
                batch_deleted = 0
                for m in msgs:
                    if not isinstance(m, dict):
                        continue
                    msg_id = str(m.get("id", "")).strip()
                    if msg_id and msg_id not in seen_triage_ids:
                        seen_triage_ids.add(msg_id)
                        try:
                            client.request("DELETE", f"/channels/{triage_ch_id}/messages/{msg_id}")
                            deleted_triage_msgs += 1
                            batch_deleted += 1
                        except OSError:
                            pass
                if batch_deleted == 0 or len(msgs) < 50:
                    break
            except OSError:
                break

    target_state = state_path or (BOT_DIR / "generated" / "bot-state.json")
    removed_state = 0
    if target_state.is_file():
        try:
            target_state.unlink()
            removed_state = 1
        except OSError:
            pass

    return {
        "deleted_threads": deleted_threads,
        "deleted_triage_messages": deleted_triage_msgs,
        "removed_state_files": removed_state,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Provision and reorganize LeafCraft Plugin Support Discord server."
    )
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument(
        "--dry-run",
        action="store_true",
        default=True,
        help="Compute and print the non-destructive provisioning plan without mutating Discord (default).",
    )
    mode.add_argument(
        "--apply",
        action="store_true",
        help="Apply the provisioning plan against the live Discord REST API.",
    )
    mode.add_argument(
        "--reset-test-server",
        action="store_true",
        help="Delete non-reference test threads in #support, clear #bot-triage-queue, and remove bot-state.json.",
    )
    parser.add_argument(
        "--live",
        action="store_true",
        help="Fetch current channels/roles/threads from the live Discord guild during --dry-run.",
    )
    parser.add_argument(
        "--spec",
        type=pathlib.Path,
        default=DEFAULT_SPEC_PATH,
        help="Path to embeds.json specification.",
    )
    parser.add_argument(
        "--snapshot",
        type=pathlib.Path,
        default=None,
        help="Optional JSON snapshot of current guild channels for offline dry-run.",
    )
    args = parser.parse_args(argv)

    spec = load_spec(args.spec)
    violations = validate_rule_d006_copy(spec)
    if violations:
        for v in violations:
            print(f"Rule D-006 violation: {v}", file=sys.stderr)
        return 1

    guild_id = resolve_discord_guild_id("100000000000000000")
    token = resolve_discord_bot_token()
    client: DiscordRestClient | None = None
    published_embed_channels: set[str] = set()

    if args.reset_test_server:
        if not token or guild_id == "100000000000000000":
            print(
                "Error: --reset-test-server requires DISCORD_BOT_TOKEN and DISCORD_GUILD_ID (or ~/.DISCORD_* files).",
                file=sys.stderr,
            )
            return 2
        client = DiscordRestClient(token)
        summary = reset_test_server(client, guild_id, spec)
        print(
            f"Test server reset complete: deleted {summary['deleted_threads']} test thread(s), "
            f"{summary['deleted_triage_messages']} triage message(s), and "
            f"{summary['removed_state_files']} local state file(s)."
        )
        return 0

    if args.snapshot:
        snap = json.loads(args.snapshot.read_text(encoding="utf-8"))
        channels = snap.get("channels", snap if isinstance(snap, list) else [])
        roles = snap.get("roles", [])
        threads = snap.get("threads", [])
        published_embed_channels = set(snap.get("published_embed_channels", []))
    elif args.apply or args.live:
        if not token or guild_id == "100000000000000000":
            print(
                "Error: --apply / --live requires DISCORD_BOT_TOKEN and DISCORD_GUILD_ID environment variables (or ~/.DISCORD_* files).",
                file=sys.stderr,
            )
            return 2
        client = DiscordRestClient(token)
        channels = client.request("GET", f"/guilds/{guild_id}/channels") or []
        roles = client.request("GET", f"/guilds/{guild_id}/roles") or []
        active_threads = client.request("GET", f"/guilds/{guild_id}/threads/active") or {}
        threads = list(active_threads.get("threads", [])) if isinstance(active_threads, dict) else []

        # Also inspect existing embed channels (#start-here, #benchmarks) and archived forum threads
        # so re-running --apply never posts duplicate embeds or duplicate reference guides.
        embed_targets = set(spec.get("channel_embeds", {}).keys())
        for ch in channels:
            ch_name = str(ch.get("name", ""))
            ch_id = str(ch.get("id", ""))
            ch_type = ch.get("type")
            if ch_name in embed_targets and ch_id:
                try:
                    msgs = client.request("GET", f"/channels/{ch_id}/messages?limit=5") or []
                    if any(isinstance(m, dict) and m.get("embeds") for m in msgs):
                        published_embed_channels.add(ch_name)
                except OSError:
                    pass
            elif ch_type == CHANNEL_TYPES["GUILD_FORUM"] and ch_name.lower() == "support" and ch_id:
                try:
                    arch = client.request("GET", f"/channels/{ch_id}/threads/archived/public") or {}
                    if isinstance(arch, dict):
                        threads.extend(arch.get("threads", []))
                except OSError:
                    pass

        enrich_threads_with_starter_bodies(client, threads, spec)
    else:
        channels = default_legacy_guild_snapshot(guild_id)
        roles = []
        threads = []

    plan = compute_provisioning_plan(
        channels,
        roles,
        threads,
        spec,
        guild_id,
        published_embed_channels=published_embed_channels,
    )
    print(plan.format_summary())
    if args.apply and client is not None:
        apply_provisioning_plan(client, plan, guild_id, channels)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
