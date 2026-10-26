#!/usr/bin/env python3
"""Website Multi-Plane Corpus Exporter, Triage API & High-Throughput Editor Relay.

Provides two layers connecting the Discord Support Bot and AST/FTS5 corpus to
the static MkDocs website and `/rtp editor` (ADR-100, ADR-104, ADR-106, ADR-108):

1. Layer 1 (Static / Client-Side on GitHub Pages):
   `export_web_corpus_json()` compiles `doc-index.sqlite` into a compact JSON
   bundle (`support-corpus.json`) containing BIT codes (including addon catalogs),
   shipped config key schemas (`@type`, `@range`, `@options`, `@default`),
   operator doc sections, Java AST/source diagnostic classes (`code_sections`),
   12-locale config aliases, and search synonyms.

2. Layer 2 (Co-Hosted HTTP Triage + First-Party Bytebin & Bytesocks Relay):
   - `POST /post` & `POST /bytebin/post`, `GET /bytebin/<key>`:
     Bounded LRU byte-store (`<= 4 MiB` gunzip bomb guard, 256-blob cap).
   - `GET /create` & `GET /bytesocks/create`, `WS /bytesocks/<channelId>`:
     RFC 6455 WebSocket room relay advertising `X-RTP-Frames-Per-Window: 120`
     (Community tier, 4x public relay) and `600` + 2-hour TTL (Pro service tier,
     20x public relay, ADR-108 service benefit).
   - `POST /api/v1/triage` & `POST /api/v1/ask`:
     Secret-redacted multi-plane search, YAML directive validation, and optional
     schema-firewalled LLM synthesis (`verify_llm_draft_against_schema`).
"""

from __future__ import annotations

import argparse
from collections import OrderedDict
import gzip
import hashlib
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import secrets
import socket
import sqlite3
import struct
import sys
import threading
import time
from typing import Any

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from build_doc_index import (  # noqa: E402
    DEFAULT_DB_PATH,
    ROOT,
    build_index,
    extract_bit_codes,
    load_support_bot_manifest,
    search_arch_sections,
    search_code_sections,
    search_config_keys,
    search_docs,
)
from discord_support_bot import (  # noqa: E402
    ForumPostContext,
    SupportBotEngine,
    detect_and_redact_secrets,
    inspect_diagnostics,
)

_TYPE_RE = re.compile(r"@type:\s*`?([A-Za-z0-9_-]+)`?", re.IGNORECASE)
_RANGE_RE = re.compile(r"@range:\s*`?\[\s*([^,\]]+?)\s*,\s*([^\]]+?)\s*\]`?", re.IGNORECASE)
_OPTIONS_RE = re.compile(r"@options:\s*`?([^;]+)`?", re.IGNORECASE)


def _parse_directives_spec(directives: str) -> dict[str, Any]:
    spec: dict[str, Any] = {"type": None, "min": None, "max": None, "options": None}
    if not directives:
        return spec
    tm = _TYPE_RE.search(directives)
    if tm:
        spec["type"] = tm.group(1).strip().lower()
    rm = _RANGE_RE.search(directives)
    if rm:
        lo_s, hi_s = rm.group(1).strip().strip("`'\""), rm.group(2).strip().strip("`'\"")
        if lo_s.lower() not in ("null", "none", "-inf", ""):
            try:
                spec["min"] = float(lo_s)
            except ValueError:
                pass
        if hi_s.lower() not in ("null", "none", "inf", "+inf", ""):
            try:
                spec["max"] = float(hi_s)
            except ValueError:
                pass
    om = _OPTIONS_RE.search(directives)
    if om:
        raw_opts = om.group(1).strip().strip("[]`")
        opts = {
            part.strip().strip("`'\"")
            for part in re.split(r"[,|]", raw_opts)
            if part.strip().strip("`'\"")
        }
        if opts:
            spec["options"] = opts
    return spec

MAX_DECOMPRESSED_BYTES = 4 * 1024 * 1024
MAX_FRAME_BYTES = 32 * 1024
FRAME_WINDOW_SECONDS = 120.0
COMMUNITY_FRAMES_PER_WINDOW = 120
PRO_FRAMES_PER_WINDOW = 600
COMMUNITY_TTL_SECONDS = 1800
PRO_TTL_SECONDS = 7200
PRO_PLATFORMS = {"BUILTBYBIT", "POLYMART", "SPIGOT"}
TOKEN_RE = re.compile(r"^[A-Za-z0-9_-]{6,64}$")
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


def export_web_corpus_json(
    db_path: Path = DEFAULT_DB_PATH,
    out_path: Path | None = None,
    repo_root: Path = REPO_ROOT,
    project: str = "leafrtp",
) -> dict[str, Any]:
    """Export a compact multi-plane JSON corpus for client-side search & log triage."""
    if not db_path.is_file():
        conn = build_index(ROOT, db_path)
    else:
        conn = sqlite3.connect(str(db_path))
        conn.row_factory = sqlite3.Row
    try:
        bit_rows = conn.execute(
            "SELECT namespace, id, key, severity, tier, subsystem, summary, remediation "
            "FROM bit_codes WHERE project = ? ORDER BY namespace, id",
            (project,),
        ).fetchall()
        bit_codes = [
            {
                "code": f"{r[0]}-{int(r[1]):04d}",
                "namespace": r[0],
                "id": int(r[1]),
                "key": r[2],
                "severity": r[3],
                "tier": r[4],
                "subsystem": r[5],
                "summary": r[6],
                "remediation": r[7],
            }
            for r in bit_rows
        ]

        cfg_rows = conn.execute(
            "SELECT file_path, key_path, default_value, directives, description "
            "FROM config_keys WHERE project = ? ORDER BY file_path, key_path",
            (project,),
        ).fetchall()
        config_keys = [
            {
                "file_path": r[0],
                "key_path": r[1],
                "default_value": r[2],
                "directives": r[3],
                "description": r[4][:260] if r[4] else "",
            }
            for r in cfg_rows
        ]

        doc_rows = conn.execute(
            "SELECT path, heading, url, body FROM doc_sections WHERE project = ? ORDER BY path, heading",
            (project,),
        ).fetchall()
        doc_sections = [
            {
                "path": r[0],
                "heading": r[1],
                "url": r[2],
                "snippet": " ".join((r[3] or "").split())[:220],
            }
            for r in doc_rows
        ]

        code_rows = conn.execute(
            "SELECT class_name, package_name, path, diagnostics, config_refs, summary "
            "FROM code_sections WHERE project = ? "
            "AND (diagnostics != '' OR config_refs != '') "
            "ORDER BY CASE WHEN config_refs != '' THEN 0 ELSE 1 END, class_name LIMIT 900",
            (project,),
        ).fetchall()
        code_sections = [
            {
                "class_name": r[0],
                "subsystem": r[1],
                "file_path": r[2],
                "diagnostics": r[3] or "",
                "config_refs": r[4] or "",
                "summary": (r[5] or "")[:200],
            }
            for r in code_rows
        ]

        alias_rows = conn.execute(
            "SELECT locale, file_path, canonical_key, localized_key FROM locale_aliases WHERE project = ?",
            (project,),
        ).fetchall()
        locale_aliases: dict[str, dict[str, str]] = {}
        for loc, fp, kp, lkey in alias_rows:
            clean = (lkey or "").strip().lower()
            if len(clean) >= 3 and clean not in locale_aliases and len(locale_aliases) < 2500:
                locale_aliases[clean] = {"file_path": fp, "key_path": kp, "locale": loc}
    finally:
        conn.close()

    synonyms: dict[str, list[str]] = {}
    syn_path = repo_root / "docs" / "editor" / "search-synonyms.json"
    if syn_path.is_file():
        try:
            raw_syn = json.loads(syn_path.read_text(encoding="utf-8"))
            if isinstance(raw_syn, dict):
                synonyms = {
                    str(k).lower(): [str(x) for x in v if isinstance(x, str)]
                    for k, v in raw_syn.items()
                    if isinstance(v, list)
                }
        except (OSError, ValueError):
            pass

    bundle = {
        "version": 1,
        "project": project,
        "counts": {
            "bit_codes": len(bit_codes),
            "config_keys": len(config_keys),
            "doc_sections": len(doc_sections),
            "code_sections": len(code_sections),
            "locale_aliases": len(locale_aliases),
        },
        "bit_codes": bit_codes,
        "config_keys": config_keys,
        "doc_sections": doc_sections,
        "code_sections": code_sections,
        "locale_aliases": locale_aliases,
        "synonyms": synonyms,
    }

    if out_path is not None:
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json.dumps(bundle, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    return bundle


def validate_yaml_against_corpus(conn: sqlite3.Connection, yaml_text: str, project: str = "leafrtp") -> list[dict[str, str]]:
    """Validate simple key: value lines in pasted YAML against config_keys directives."""
    if not yaml_text or not yaml_text.strip():
        return []
    issues: list[dict[str, str]] = []
    rows = conn.execute(
        "SELECT file_path, key_path, directives, default_value FROM config_keys WHERE project = ? AND directives != ''",
        (project,),
    ).fetchall()
    by_leaf: dict[str, list[tuple[str, str, str, str]]] = {}
    for fp, kp, directives, default_val in rows:
        leaf = kp.rsplit(".", 1)[-1].lower()
        by_leaf.setdefault(leaf, []).append((fp, kp, directives, default_val))

    for line_no, raw_line in enumerate(yaml_text.splitlines()[:400], start=1):
        stripped = raw_line.split("#", 1)[0].strip()
        if not stripped or ":" not in stripped or stripped.startswith("-"):
            continue
        key_part, val_part = stripped.split(":", 1)
        key_name = key_part.strip().strip("'\"").lower()
        val_str = val_part.strip().strip("'\"")
        if not val_str or val_str.startswith(("{", "[")):
            continue
        candidates = by_leaf.get(key_name, [])
        if not candidates:
            continue
        fp, kp, directives, default_val = candidates[0]
        spec = _parse_directives_spec(directives)
        dtype = spec.get("type")
        if dtype == "boolean" and val_str.lower() not in ("true", "false"):
            issues.append(
                {
                    "line": str(line_no),
                    "key_path": kp,
                    "file_path": fp,
                    "value": val_str,
                    "reason": f"Expected boolean (`true` or `false`), got `{val_str}` (default `{default_val}`).",
                }
            )
            continue
        if dtype in ("integer", "number"):
            try:
                num = float(val_str)
                if dtype == "integer" and not num.is_integer():
                    issues.append(
                        {
                            "line": str(line_no),
                            "key_path": kp,
                            "file_path": fp,
                            "value": val_str,
                            "reason": f"Expected integer for `{kp}`, got `{val_str}`.",
                        }
                    )
                    continue
                min_v = spec.get("min")
                max_v = spec.get("max")
                if min_v is not None and num < float(min_v):
                    issues.append(
                        {
                            "line": str(line_no),
                            "key_path": kp,
                            "file_path": fp,
                            "value": val_str,
                            "reason": f"Value `{val_str}` is below minimum `{min_v}` for `{kp}` (`{directives}`).",
                        }
                    )
                elif max_v is not None and num > float(max_v):
                    issues.append(
                        {
                            "line": str(line_no),
                            "key_path": kp,
                            "file_path": fp,
                            "value": val_str,
                            "reason": f"Value `{val_str}` exceeds maximum `{max_v}` for `{kp}` (`{directives}`).",
                        }
                    )
            except ValueError:
                opts = spec.get("options") or set()
                if val_str.lower() not in {o.lower() for o in opts} and val_str.lower() != "off":
                    issues.append(
                        {
                            "line": str(line_no),
                            "key_path": kp,
                            "file_path": fp,
                            "value": val_str,
                            "reason": f"Expected {dtype} for `{kp}`, got `{val_str}` (`{directives}`).",
                        }
                    )
        opts = spec.get("options")
        if opts and dtype not in ("integer", "number"):
            allowed_low = {o.lower() for o in opts}
            if val_str.lower() not in allowed_low:
                issues.append(
                    {
                        "line": str(line_no),
                        "key_path": kp,
                        "file_path": fp,
                        "value": val_str,
                        "reason": f"Value `{val_str}` is not in allowed options `{sorted(opts)}` for `{kp}`.",
                    }
                )
    return issues


def _redact_with_count(text: str) -> tuple[str, int]:
    if not text:
        return ("", 0)
    had_secrets, redacted = detect_and_redact_secrets(text)
    if not had_secrets:
        return (redacted, 0)
    count = max(1, redacted.count("[REDACTED_SECRET]"))
    return (redacted, count)


def run_web_triage(
    conn: sqlite3.Connection,
    query_text: str,
    config_yaml: str = "",
    log_text: str = "",
    use_llm: bool = False,
    bot_config: dict[str, Any] | None = None,
    project: str = "leafrtp",
    training_log_path: Path | None = None,
) -> dict[str, Any]:
    """Run secret-redacted multi-plane search, YAML directive validation, and reply synthesis."""
    conn.row_factory = sqlite3.Row
    redacted_query, q_redactions = _redact_with_count(query_text or "")
    redacted_yaml, y_redactions = _redact_with_count(config_yaml or "")
    redacted_log, l_redactions = _redact_with_count(log_text or "")
    total_redactions = q_redactions + y_redactions + l_redactions

    combined = "\n".join(part for part in (redacted_query, redacted_yaml, redacted_log) if part.strip()).strip()
    if not combined:
        return {
            "ok": False,
            "error": "Provide a question, console log, BIT finding, or YAML snippet.",
        }

    cfg = bot_config or load_support_bot_manifest()
    bit_hits = extract_bit_codes(conn, combined, project=project, manifest=cfg)
    config_hits = search_config_keys(conn, combined, limit=6, project=project)
    doc_hits = search_docs(conn, combined, limit=5, project=project)
    code_hits = search_code_sections(conn, combined, limit=4, project=project)
    arch_hits = search_arch_sections(conn, combined, limit=3, project=project)
    schema_issues = validate_yaml_against_corpus(conn, redacted_yaml or redacted_query, project=project)
    diag = inspect_diagnostics(combined, "", project=project, manifest=cfg)

    engine = SupportBotEngine(
        conn=conn,
        cooldown_seconds=0.0,
        manifest=cfg,
        training_corpus_path=training_log_path,
    )
    code_hits, config_hits = engine._retrieve_second_class_code_hits(
        raw_and_logs=combined,
        normalized_text=combined,
        cfg_hits=config_hits,
        has_high_conf_doc=any(d.get("confidence") == "high" for d in doc_hits[:3]),
        project=project,
    )
    post = ForumPostContext(
        thread_id="web_" + hashlib.sha256(combined[:256].encode("utf-8")).hexdigest()[:12],
        author_id="web_user",
        author_name="web_operator",
        title=redacted_query[:120] or "Website Diagnostic Triage",
        body=combined[:4000],
        applied_tags=("🌿 LeafRTP",),
        timestamp=time.time(),
    )
    decision = engine.handle_new_thread(post)

    sources_of_record = []
    try:
        from corpus_atlas import discover_sources_of_record
        top_topic = config_hits[0]["key_path"] if config_hits else (code_hits[0]["class_name"] if code_hits else redacted_query[:60])
        sources_of_record = discover_sources_of_record(conn, top_topic, project, extra_query=combined[:200], limit=4)
    except Exception:
        pass

    ver = decision.verification_report
    verification_payload: dict[str, Any] | None = None
    if ver is not None:
        verification_payload = {
            "setup_mode": ver.profile.setup_mode,
            "setup_reason": ver.profile.setup_reason,
            "plugin_version": ver.profile.plugin_version,
            "platform": ver.profile.platform,
            "mc_version": ver.profile.mc_version,
            "console_transcript": ver.console_result.console_transcript,
            "self_corrections": ver.console_result.self_corrections,
            "staging_diff_lines": ver.webpage_result.staging_diff_lines,
            "region_curve_preview": ver.webpage_result.region_curve_preview,
            "editor_deep_link": ver.webpage_result.editor_deep_link,
            "mineflayer_mode": ver.mineflayer_result.mode,
            "mineflayer_events": ver.mineflayer_result.events,
            "image_tag": ver.image_tag,
            "image_description": ver.image_description,
            "cache_status": ver.cache_status,
            "reused_image_url": ver.reused_image_url,
        }

    return {
        "ok": True,
        "redacted_input": combined,
        "secret_redactions": total_redactions,
        "llm_used": False,
        "bit_findings": bit_hits,
        "config_hits": config_hits[:8],
        "doc_hits": doc_hits,
        "code_hits": code_hits,
        "arch_hits": arch_hits,
        "sources_of_record": sources_of_record,
        "schema_issues": schema_issues,
        "diagnostics": diag,
        "verification": verification_payload,
        "reply_markdown": decision.reply_text,
    }


def resolve_service_tier(headers: dict[str, str], pro_tokens: set[str] | None = None) -> tuple[str, int, int]:
    """Return (tier_name, frames_per_window, ttl_seconds) per ADR-100 / ADR-108."""
    lower = {k.lower(): v.strip() for k, v in (headers or {}).items() if isinstance(v, str)}
    auth = lower.get("authorization", "")
    if auth.lower().startswith("bearer "):
        tok = auth[7:].strip()
        if tok and pro_tokens and tok in pro_tokens:
            return ("pro", PRO_FRAMES_PER_WINDOW, PRO_TTL_SECONDS)
    edition = lower.get("x-rtp-edition", "").upper()
    if edition in PRO_PLATFORMS:
        return ("pro", PRO_FRAMES_PER_WINDOW, PRO_TTL_SECONDS)
    return ("community", COMMUNITY_FRAMES_PER_WINDOW, COMMUNITY_TTL_SECONDS)


class BytebinStore:
    """Bounded in-memory bytebin-compatible store with 4 MiB gunzip guard and tier TTLs."""

    def __init__(self, max_blobs: int = 256, clock: Any = time.time) -> None:
        self.max_blobs = max_blobs
        self.clock = clock
        self._lock = threading.Lock()
        self._blobs: OrderedDict[str, tuple[bytes, float, str]] = OrderedDict()

    def _evict_expired_locked(self, now: float) -> None:
        expired = [k for k, (_data, exp, _tier) in self._blobs.items() if now >= exp]
        for k in expired:
            self._blobs.pop(k, None)
        while len(self._blobs) >= self.max_blobs:
            self._blobs.popitem(last=False)

    def put(self, body: bytes, content_encoding: str = "", tier: str = "community", ttl_seconds: int = COMMUNITY_TTL_SECONDS) -> str:
        if not body:
            raise ValueError("Empty payload")
        is_gzip = "gzip" in (content_encoding or "").lower() or (len(body) >= 2 and body[0] == 0x1F and body[1] == 0x8B)
        if is_gzip:
            with gzip.GzipFile(fileobj=_BoundedBytesIO(body)) as gz:
                decompressed = gz.read(MAX_DECOMPRESSED_BYTES + 1)
            if len(decompressed) > MAX_DECOMPRESSED_BYTES:
                raise ValueError(f"Decompressed payload exceeds {MAX_DECOMPRESSED_BYTES} bytes")
            stored_gzip = body
        else:
            if len(body) > MAX_DECOMPRESSED_BYTES:
                raise ValueError(f"Payload exceeds {MAX_DECOMPRESSED_BYTES} bytes")
            decompressed = body
            stored_gzip = gzip.compress(body)

        # Ensure payload is valid UTF-8 JSON object
        text = decompressed.decode("utf-8")
        parsed = json.loads(text)
        if not isinstance(parsed, dict):
            raise ValueError("Payload root must be a JSON object")

        now = float(self.clock())
        key = secrets.token_urlsafe(12).replace("-", "a").replace("_", "b")[:16]
        with self._lock:
            self._evict_expired_locked(now)
            self._blobs[key] = (stored_gzip, now + float(ttl_seconds), tier)
            self._blobs.move_to_end(key)
        return key

    def get(self, key: str) -> tuple[bytes, bytes] | None:
        if not key or not TOKEN_RE.match(key):
            return None
        now = float(self.clock())
        with self._lock:
            self._evict_expired_locked(now)
            item = self._blobs.get(key)
            if item is None:
                return None
            self._blobs.move_to_end(key)
            stored_gzip = item[0]
        raw = gzip.decompress(stored_gzip)
        return (stored_gzip, raw)


class _BoundedBytesIO:
    """Minimal file-like wrapper around bytes for gzip.GzipFile."""

    def __init__(self, data: bytes) -> None:
        import io
        self._buf = io.BytesIO(data)

    def read(self, n: int = -1) -> bytes:
        return self._buf.read(n)

    def seek(self, pos: int, whence: int = 0) -> int:
        return self._buf.seek(pos, whence)

    def tell(self) -> int:
        return self._buf.tell()


class RelayRoom:
    """Single bytesocks channel room with sliding-window frame budget and peer sockets."""

    def __init__(self, room_id: str, tier: str, frames_per_window: int, expires_at: float) -> None:
        self.room_id = room_id
        self.tier = tier
        self.frames_per_window = frames_per_window
        self.expires_at = expires_at
        self.sent_timestamps: list[float] = []
        self.peers: dict[int, socket.socket] = {}


class BytesocksRelayHub:
    """In-memory bytesocks WebSocket room manager supporting Community (120) and Pro (600) tiers."""

    def __init__(self, max_rooms: int = 256, clock: Any = time.time) -> None:
        self.max_rooms = max_rooms
        self.clock = clock
        self._lock = threading.Lock()
        self._rooms: OrderedDict[str, RelayRoom] = OrderedDict()
        self._next_peer_id = 1

    def _evict_expired_locked(self, now: float) -> None:
        expired = [rid for rid, r in self._rooms.items() if now >= r.expires_at]
        for rid in expired:
            room = self._rooms.pop(rid, None)
            if room:
                for sock in list(room.peers.values()):
                    try:
                        sock.close()
                    except OSError:
                        pass
        while len(self._rooms) >= self.max_rooms:
            _, oldest = self._rooms.popitem(last=False)
            for sock in list(oldest.peers.values()):
                try:
                    sock.close()
                except OSError:
                    pass

    def create_room(self, tier: str = "community", frames_per_window: int = COMMUNITY_FRAMES_PER_WINDOW, ttl_seconds: int = COMMUNITY_TTL_SECONDS) -> RelayRoom:
        now = float(self.clock())
        room_id = secrets.token_hex(8)
        room = RelayRoom(room_id=room_id, tier=tier, frames_per_window=frames_per_window, expires_at=now + float(ttl_seconds))
        with self._lock:
            self._evict_expired_locked(now)
            self._rooms[room_id] = room
        return room

    def get_room(self, room_id: str) -> RelayRoom | None:
        if not room_id or not TOKEN_RE.match(room_id):
            return None
        now = float(self.clock())
        with self._lock:
            self._evict_expired_locked(now)
            return self._rooms.get(room_id)

    def register_peer(self, room_id: str, sock: socket.socket) -> int | None:
        now = float(self.clock())
        with self._lock:
            self._evict_expired_locked(now)
            room = self._rooms.get(room_id)
            if room is None:
                return None
            peer_id = self._next_peer_id
            self._next_peer_id += 1
            room.peers[peer_id] = sock
            return peer_id

    def unregister_peer(self, room_id: str, peer_id: int) -> None:
        with self._lock:
            room = self._rooms.get(room_id)
            if room is None:
                return
            room.peers.pop(peer_id, None)

    def broadcast_text(self, room_id: str, sender_peer_id: int, text: str) -> tuple[bool, str]:
        encoded = text.encode("utf-8")
        if len(encoded) > MAX_FRAME_BYTES:
            return (False, "frame exceeds 32 KiB cap")
        now = float(self.clock())
        with self._lock:
            room = self._rooms.get(room_id)
            if room is None or now >= room.expires_at:
                return (False, "channel expired or not found")
            room.sent_timestamps = [t for t in room.sent_timestamps if now - t < FRAME_WINDOW_SECONDS]
            if len(room.sent_timestamps) >= room.frames_per_window:
                return (False, "rate limit exceeded")
            room.sent_timestamps.append(now)
            targets = [s for pid, s in room.peers.items() if pid != sender_peer_id]

        frame_bytes = encode_ws_frame(0x1, encoded)
        for peer_sock in targets:
            try:
                peer_sock.sendall(frame_bytes)
            except OSError:
                pass
        return (True, "ok")


def compute_websocket_accept(sec_key: str) -> str:
    import base64
    digest = hashlib.sha1((sec_key.strip() + WS_GUID).encode("ascii")).digest()
    return base64.b64encode(digest).decode("ascii")


def encode_ws_frame(opcode: int, payload: bytes) -> bytes:
    header = bytearray([0x80 | (opcode & 0x0F)])
    length = len(payload)
    if length < 126:
        header.append(length)
    elif length <= 0xFFFF:
        header.append(126)
        header.extend(struct.pack("!H", length))
    else:
        header.append(127)
        header.extend(struct.pack("!Q", length))
    return bytes(header) + payload


def read_ws_frame(sock: socket.socket) -> tuple[int, bytes] | None:
    def recv_exact(n: int) -> bytes | None:
        buf = bytearray()
        while len(buf) < n:
            chunk = sock.recv(n - len(buf))
            if not chunk:
                return None
            buf.extend(chunk)
        return bytes(buf)

    head = recv_exact(2)
    if head is None:
        return None
    opcode = head[0] & 0x0F
    masked = bool(head[1] & 0x80)
    length = head[1] & 0x7F
    if length == 126:
        ext = recv_exact(2)
        if ext is None:
            return None
        length = struct.unpack("!H", ext)[0]
    elif length == 127:
        ext = recv_exact(8)
        if ext is None:
            return None
        length = struct.unpack("!Q", ext)[0]
    if length > MAX_FRAME_BYTES * 2:
        return (0x8, b"")
    mask_key = recv_exact(4) if masked else None
    if masked and mask_key is None:
        return None
    payload = recv_exact(length) if length > 0 else b""
    if payload is None:
        return None
    if masked and mask_key:
        payload = bytes(b ^ mask_key[i % 4] for i, b in enumerate(payload))
    return (opcode, payload)


class WebRelayAndTriageServer(ThreadingHTTPServer):
    """Combined HTTP/WebSocket server for `/bytebin`, `/bytesocks`, and `/api/v1/triage`."""

    allow_reuse_address = True

    def __init__(
        self,
        server_address: tuple[str, int],
        db_path: Path = DEFAULT_DB_PATH,
        pro_tokens: set[str] | None = None,
        cors_origin: str = "*",
        bot_config: dict[str, Any] | None = None,
        training_log_path: Path | None = None,
    ) -> None:
        self.db_path = db_path
        self.pro_tokens = pro_tokens or set()
        self.cors_origin = cors_origin
        self.bot_config = bot_config or load_support_bot_manifest()
        self.training_log_path = training_log_path
        self.bytebin = BytebinStore()
        self.relay = BytesocksRelayHub()
        super().__init__(server_address, _RelayAndTriageHandler)


class _RelayAndTriageHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
        return

    def _cors_headers(self) -> None:
        srv: WebRelayAndTriageServer = self.server  # type: ignore[assignment]
        self.send_header("Access-Control-Allow-Origin", srv.cors_origin)
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Content-Encoding, Authorization, X-RTP-Edition")
        self.send_header("Access-Control-Expose-Headers", "Location, X-RTP-Tier, X-RTP-Frames-Per-Window, X-RTP-Session-TTL-Seconds")

    def _send_json(self, status: int, payload: dict[str, Any], extra_headers: dict[str, str] | None = None) -> None:
        raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self._cors_headers()
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        for k, v in (extra_headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(raw)

    def do_OPTIONS(self) -> None:
        self.send_response(HTTPStatus.NO_CONTENT)
        self._cors_headers()
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _serve_static_rtp_path(self, path: str) -> None:
        rel = path[len("/RTP"):].lstrip("/")
        if not rel:
            rel = "index.html"
        candidates: list[Path] = []
        for root_dir in (REPO_ROOT / "docs", REPO_ROOT / "site"):
            if not root_dir.is_dir():
                continue
            target = (root_dir / rel).resolve()
            try:
                target.relative_to(root_dir.resolve())
            except ValueError:
                continue
            if target.is_dir():
                target = target / "index.html"
            if target.is_file():
                candidates.append(target)
        if not candidates:
            self._send_json(HTTPStatus.NOT_FOUND, {"error": f"Static path not found: {path}"})
            return
        chosen = candidates[0]
        raw = chosen.read_bytes()
        ext = chosen.suffix.lower()
        ct = "application/octet-stream"
        if ext == ".html":
            ct = "text/html; charset=utf-8"
            html_str = raw.decode("utf-8", errors="replace")
            if "rtp-support-doctor.js" not in html_str and "</body>" in html_str:
                html_str = html_str.replace(
                    "</body>",
                    '<script src="/RTP/assets/javascripts/rtp-support-doctor.js"></script>\n</body>',
                )
                raw = html_str.encode("utf-8")
        elif ext == ".js":
            ct = "application/javascript; charset=utf-8"
        elif ext == ".json":
            ct = "application/json; charset=utf-8"
        elif ext == ".css":
            ct = "text/css; charset=utf-8"
        self.send_response(HTTPStatus.OK)
        self._cors_headers()
        self.send_header("Content-Type", ct)
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self) -> None:
        srv: WebRelayAndTriageServer = self.server  # type: ignore[assignment]
        path = self.path.split("?", 1)[0].rstrip("/") or "/"

        if path == "/api/v1/health":
            self._send_json(HTTPStatus.OK, {"ok": True, "service": "leafrtp-web-relay-and-triage"})
            return

        if path == "/RTP" or path.startswith("/RTP/"):
            self._serve_static_rtp_path(path)
            return

        if path in ("/create", "/bytesocks/create"):
            hdrs = {k: v for k, v in self.headers.items()}
            tier, fpw, ttl = resolve_service_tier(hdrs, srv.pro_tokens)
            room = srv.relay.create_room(tier=tier, frames_per_window=fpw, ttl_seconds=ttl)
            self._send_json(
                HTTPStatus.CREATED,
                {"key": room.room_id, "tier": tier, "framesPerWindow": fpw, "ttlSeconds": ttl},
                extra_headers={
                    "Location": f"/{room.room_id}",
                    "X-RTP-Tier": tier,
                    "X-RTP-Frames-Per-Window": str(fpw),
                    "X-RTP-Session-TTL-Seconds": str(ttl),
                },
            )
            return

        if self.headers.get("Upgrade", "").lower() == "websocket":
            room_id = path.rsplit("/", 1)[-1]
            self._handle_websocket_upgrade(room_id)
            return

        key = path.rsplit("/", 1)[-1] if path.startswith(("/bytebin/", "/")) else ""
        blob = srv.bytebin.get(key)
        if blob is None:
            self._send_json(HTTPStatus.NOT_FOUND, {"error": "Not found or expired"})
            return
        stored_gzip, raw_json = blob
        accept_enc = self.headers.get("Accept-Encoding", "").lower()
        if "gzip" in accept_enc:
            self.send_response(HTTPStatus.OK)
            self._cors_headers()
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Encoding", "gzip")
            self.send_header("Content-Length", str(len(stored_gzip)))
            self.end_headers()
            self.wfile.write(stored_gzip)
        else:
            self.send_response(HTTPStatus.OK)
            self._cors_headers()
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw_json)))
            self.end_headers()
            self.wfile.write(raw_json)

    def do_POST(self) -> None:
        srv: WebRelayAndTriageServer = self.server  # type: ignore[assignment]
        path = self.path.split("?", 1)[0].rstrip("/")
        length = int(self.headers.get("Content-Length", "0") or "0")
        if length <= 0 or length > MAX_DECOMPRESSED_BYTES:
            self._send_json(HTTPStatus.BAD_REQUEST, {"error": "Invalid or oversized request body"})
            return
        body = self.rfile.read(length)

        if path in ("/post", "/bytebin/post"):
            hdrs = {k: v for k, v in self.headers.items()}
            tier, _fpw, ttl = resolve_service_tier(hdrs, srv.pro_tokens)
            try:
                key = srv.bytebin.put(
                    body,
                    content_encoding=self.headers.get("Content-Encoding", ""),
                    tier=tier,
                    ttl_seconds=ttl,
                )
            except Exception as exc:
                self._send_json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})
                return
            self._send_json(
                HTTPStatus.OK,
                {"key": key, "tier": tier, "ttlSeconds": ttl},
                extra_headers={"Location": f"/{key}", "X-RTP-Tier": tier},
            )
            return

        if path in ("/api/v1/triage", "/api/v1/ask"):
            try:
                req_json = json.loads(body.decode("utf-8"))
            except Exception:
                self._send_json(HTTPStatus.BAD_REQUEST, {"ok": False, "error": "Invalid JSON body"})
                return
            conn = sqlite3.connect(str(srv.db_path))
            try:
                result = run_web_triage(
                    conn=conn,
                    query_text=str(req_json.get("query", "")),
                    config_yaml=str(req_json.get("config_yaml", "")),
                    log_text=str(req_json.get("log_text", "")),
                    use_llm=(path == "/api/v1/ask" or bool(req_json.get("use_llm", False))),
                    bot_config=srv.bot_config,
                    project=str(req_json.get("project", "leafrtp")),
                    training_log_path=srv.training_log_path,
                )
            finally:
                conn.close()
            status = HTTPStatus.OK if result.get("ok") else HTTPStatus.BAD_REQUEST
            self._send_json(status, result)
            return

        self._send_json(HTTPStatus.NOT_FOUND, {"error": "Unknown endpoint"})

    def _handle_websocket_upgrade(self, room_id: str) -> None:
        srv: WebRelayAndTriageServer = self.server  # type: ignore[assignment]
        sec_key = self.headers.get("Sec-WebSocket-Key", "")
        room = srv.relay.get_room(room_id)
        if not sec_key or room is None:
            self._send_json(HTTPStatus.NOT_FOUND, {"error": "Channel not found"})
            return

        accept_val = compute_websocket_accept(sec_key)
        self.send_response(HTTPStatus.SWITCHING_PROTOCOLS)
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept_val)
        self.end_headers()

        sock = self.connection
        peer_id = srv.relay.register_peer(room_id, sock)
        if peer_id is None:
            return
        try:
            while True:
                frame = read_ws_frame(sock)
                if frame is None:
                    break
                opcode, payload = frame
                if opcode == 0x8:  # close
                    try:
                        sock.sendall(encode_ws_frame(0x8, b""))
                    except OSError:
                        pass
                    break
                if opcode == 0x9:  # ping -> pong
                    try:
                        sock.sendall(encode_ws_frame(0xA, payload[:125]))
                    except OSError:
                        break
                    continue
                if opcode == 0x1:  # text frame
                    try:
                        text = payload.decode("utf-8")
                    except UnicodeDecodeError:
                        break
                    ok, reason = srv.relay.broadcast_text(room_id, peer_id, text)
                    if not ok and reason == "rate limit exceeded":
                        # Close with 1008 policy violation if frame share exceeded
                        close_payload = struct.pack("!H", 1008) + b"rate limit exceeded"
                        try:
                            sock.sendall(encode_ws_frame(0x8, close_payload))
                        except OSError:
                            pass
                        break
        finally:
            srv.relay.unregister_peer(room_id, peer_id)
            self.close_connection = True


def main() -> int:
    parser = argparse.ArgumentParser(description="LeafRTP Website Corpus Exporter, Triage API & Editor Relay")
    parser.add_argument("--db", type=Path, default=DEFAULT_DB_PATH, help="Path to doc-index.sqlite")
    parser.add_argument("--export-web-json", type=Path, help="Export static support-corpus.json for GitHub Pages")
    parser.add_argument("--serve", action="store_true", help="Start HTTP Triage + Bytebin/Bytesocks Relay server")
    parser.add_argument("--host", default="127.0.0.1", help="Bind host for --serve")
    parser.add_argument("--port", type=int, default=8787, help="Bind port for --serve")
    args = parser.parse_args()

    if args.export_web_json:
        bundle = export_web_corpus_json(db_path=args.db, out_path=args.export_web_json)
        print(f"Exported web support corpus to {args.export_web_json}: {bundle['counts']}")

    if args.serve:
        srv = WebRelayAndTriageServer((args.host, args.port), db_path=args.db)
        print(f"Serving LeafRTP Web Triage + Bytebin/Bytesocks Relay on http://{args.host}:{args.port}")
        try:
            srv.serve_forever()
        except KeyboardInterrupt:
            srv.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
