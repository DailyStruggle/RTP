#!/usr/bin/env python3
"""Priority-queued local LLM dispatch and staged multi-opinion verification broker.

Manages `scripts/out/llm-queue.sqlite` in WAL mode across three priority tiers:
  - P0 (0): Live interactive / support-bot preemption leases (`gpu_leases`).
  - P1 (1): Session-scoped diff invariant critique and branch verification.
  - P2 (2): Background batch locale bundle translation, doc translation, and
            Rule D-010 (`ADR-115`) `@governedBy` Javadoc backfills.

Every task chunk passes through a 4-stage verification funnel (`REQ-LLM-Q-002`,
`REQ-LLM-Q-003`):
  1. Opinion 1 (Forward Generator)
  2. Deterministic Gate (< 5 ms sentinel / mojibake / AST / citation check)
  3. Opinion 2 (Asymmetric Blind Critic / Back-Translation)
  4. Bounded 1-Pass Local Repair on failure -> Auto-Apply or Quarantine digest
"""

from __future__ import annotations

import argparse
import contextlib
from dataclasses import dataclass, field
import importlib.util
import ipaddress
import json
import os
import pathlib
import re
import shutil
import sqlite3
import subprocess
import sys
import time
from typing import Any, Callable, Iterator, Protocol
import urllib.error
import urllib.parse
import urllib.request

SCRIPTS_DIR = pathlib.Path(__file__).resolve().parent
REPO_ROOT = SCRIPTS_DIR.parent
DEFAULT_OUT_DIR = SCRIPTS_DIR / "out"
DEFAULT_QUEUE_DB_PATH = DEFAULT_OUT_DIR / "llm-queue.sqlite"
DEFAULT_LLM_ENDPOINT = "http://127.0.0.1:11434/v1/chat/completions"
DEFAULT_FORWARD_MODEL = "deepseek-r1:32b"
DEFAULT_CRITIC_MODEL = "deepseek-r1:32b"
DEFAULT_LEASE_TTL_MS = 180_000
DEFAULT_P0_LEASE_TTL_MS = 60_000

PRIORITY_P0 = 0
PRIORITY_P1 = 1
PRIORITY_P2 = 2

TASK_DEFAULT_PRIORITY: dict[str, int] = {
    "critique-diff": PRIORITY_P1,
    "translate-bundle": PRIORITY_P2,
    "translate-doc": PRIORITY_P2,
    "backfill-javadoc": PRIORITY_P2,
}

_SECRET_PATTERNS: tuple[re.Pattern[str], ...] = (
    re.compile(r"(?i)(password|passwd|secret|api[_-]?key|token|authorization)\s*[:=]\s*['\"]?[^\s'\"]{6,}['\"]?"),
    re.compile(r"[\w-]{24}\.[\w-]{6}\.[\w-]{27,}"),
    re.compile(r"gh[pousr]_[A-Za-z0-9_]{20,}"),
)


def now_epoch_ms() -> int:
    return int(time.time() * 1000)


def redact_secrets(text: str) -> str:
    """Redacts credentials, tokens, and secret assignments before local LLM dispatch."""
    if not text:
        return ""
    out = text
    for pat in _SECRET_PATTERNS:
        out = pat.sub("[REDACTED_SECRET]", out)
    return out


def is_safe_llm_endpoint(url: str) -> bool:
    """Verifies that an LLM endpoint URL points strictly to loopback or private LAN."""
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


def strip_think_tags(text: str) -> str:
    """Strips DeepSeek `<think>...</think>` blocks and dangling `<think>` prefixes."""
    if not text:
        return ""
    cleaned = re.sub(r"(?s)<think>.*?</think>\s*", "", text).strip()
    if "<think>" in cleaned:
        cleaned = cleaned.split("</think>")[-1].strip() if "</think>" in cleaned else ""
    return cleaned


def extract_json_payload(text: str) -> Any:
    """Extracts a JSON object or array from an LLM response after stripping `<think>`."""
    cleaned = strip_think_tags(text)
    if not cleaned:
        raise ValueError("Empty LLM response after stripping reasoning tags")
    fence_match = re.search(r"(?s)```(?:json)?\s*\n(.*?)\n```", cleaned)
    candidate = fence_match.group(1).strip() if fence_match else cleaned
    try:
        return json.loads(candidate)
    except ValueError:
        pass
    for open_ch, close_ch in (("{", "}"), ("[", "]")):
        start = candidate.find(open_ch)
        end = candidate.rfind(close_ch)
        if start != -1 and end > start:
            try:
                return json.loads(candidate[start : end + 1])
            except ValueError:
                continue
    raise ValueError(f"Could not parse JSON payload from response: {cleaned[:160]}")


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
        raw = resp.read(262_144).decode("utf-8", errors="replace")
        return json.loads(raw)  # type: ignore[no-any-return]


class LocalLlmError(RuntimeError):
    """Raised when the local LLM endpoint is unreachable, unsafe, or returns invalid data."""


class LocalLlmClient:
    """Loopback-safe OpenAI-compatible client for Forward (Opinion 1) and Critic (Opinion 2) passes."""

    def __init__(
        self,
        endpoint: str = DEFAULT_LLM_ENDPOINT,
        forward_model: str = DEFAULT_FORWARD_MODEL,
        critic_model: str = DEFAULT_CRITIC_MODEL,
        timeout: float = 90.0,
        http_post: Callable[[str, dict[str, Any], float], dict[str, Any]] | None = None,
    ) -> None:
        self.endpoint = endpoint.strip()
        self.forward_model = forward_model.strip() or DEFAULT_FORWARD_MODEL
        self.critic_model = critic_model.strip() or DEFAULT_CRITIC_MODEL
        self.timeout = max(0.5, float(timeout))
        self.http_post = http_post or _default_llm_http_post

    def _chat(
        self,
        messages: list[dict[str, str]],
        model: str,
        temperature: float,
        max_tokens: int = 4096,
        phase: str = "forward",
    ) -> str:
        if not is_safe_llm_endpoint(self.endpoint):
            raise LocalLlmError(f"Refusing unsafe non-loopback/non-LAN LLM endpoint: {self.endpoint}")
        safe_messages = [
            {
                "role": str(m.get("role", "user")),
                "content": redact_secrets(str(m.get("content", ""))),
            }
            for m in messages
        ]
        payload: dict[str, Any] = {
            "model": model,
            "messages": safe_messages,
            "temperature": temperature,
            "max_tokens": max_tokens,
            "metadata": {"phase": phase},
        }
        try:
            resp = self.http_post(self.endpoint, payload, self.timeout)
        except (OSError, urllib.error.URLError, ValueError, KeyError, TypeError) as exc:
            raise LocalLlmError(f"Local LLM endpoint offline or failed ({exc})") from exc

        choices = resp.get("choices") if isinstance(resp, dict) else None
        if not isinstance(choices, list) or not choices:
            raise LocalLlmError("Malformed LLM response: missing choices list")
        msg = choices[0].get("message") if isinstance(choices[0], dict) else None
        if not isinstance(msg, dict):
            raise LocalLlmError("Malformed LLM response: missing message object")
        raw_content = str(msg.get("content") or "")
        return strip_think_tags(raw_content)

    def chat_forward(self, messages: list[dict[str, str]], max_tokens: int = 4096) -> str:
        return self._chat(
            messages=messages,
            model=self.forward_model,
            temperature=0.2,
            max_tokens=max_tokens,
            phase="forward",
        )

    def chat_critic(self, messages: list[dict[str, str]], max_tokens: int = 2048) -> str:
        return self._chat(
            messages=messages,
            model=self.critic_model,
            temperature=0.0,
            max_tokens=max_tokens,
            phase="critic",
        )

    def chat_repair(self, messages: list[dict[str, str]], max_tokens: int = 4096) -> str:
        return self._chat(
            messages=messages,
            model=self.forward_model,
            temperature=0.1,
            max_tokens=max_tokens,
            phase="repair",
        )


QUEUE_SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS jobs (
    job_id INTEGER PRIMARY KEY AUTOINCREMENT,
    priority INTEGER NOT NULL CHECK (priority IN (0, 1, 2)),
    task_type TEXT NOT NULL,
    target_key TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    model_forward TEXT NOT NULL DEFAULT 'deepseek-r1:32b',
    model_critic TEXT NOT NULL DEFAULT 'deepseek-r1:32b',
    status TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'NEEDS_REVIEW', 'FAILED', 'CANCELED')),
    total_items INTEGER NOT NULL DEFAULT 1,
    completed_items INTEGER NOT NULL DEFAULT 0,
    retried_items INTEGER NOT NULL DEFAULT 0,
    quarantined_items INTEGER NOT NULL DEFAULT 0,
    result_summary_md TEXT NOT NULL DEFAULT '',
    review_path TEXT NOT NULL DEFAULT '',
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    lease_expires_at_ms INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_jobs_sched
    ON jobs(status, priority ASC, created_at_ms ASC);

CREATE TABLE IF NOT EXISTS job_attempts (
    attempt_id INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id INTEGER NOT NULL,
    chunk_index INTEGER NOT NULL,
    item_id TEXT NOT NULL,
    stage TEXT NOT NULL,
    passed INTEGER NOT NULL,
    reason TEXT NOT NULL DEFAULT '',
    created_at_ms INTEGER NOT NULL,
    FOREIGN KEY(job_id) REFERENCES jobs(job_id)
);

CREATE INDEX IF NOT EXISTS idx_job_attempts_job
    ON job_attempts(job_id, chunk_index);

CREATE TABLE IF NOT EXISTS gpu_leases (
    holder_id TEXT PRIMARY KEY,
    priority INTEGER NOT NULL,
    acquired_at_ms INTEGER NOT NULL,
    expires_at_ms INTEGER NOT NULL
);
"""


class LlmQueueBroker:
    """SQLite WAL priority queue broker supporting P0>P1>P2 scheduling and chunk preemption."""

    def __init__(self, db_path: pathlib.Path | str = DEFAULT_QUEUE_DB_PATH) -> None:
        self.db_path = pathlib.Path(db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._init_db()

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(str(self.db_path), timeout=5.0)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL;")
        conn.execute("PRAGMA busy_timeout=5000;")
        return conn

    def _init_db(self) -> None:
        with contextlib.closing(self._connect()) as conn:
            with conn:
                conn.executescript(QUEUE_SCHEMA_SQL)

    def enqueue(
        self,
        task_type: str,
        target_key: str,
        payload: dict[str, Any] | None = None,
        priority: int | None = None,
        model_forward: str = DEFAULT_FORWARD_MODEL,
        model_critic: str = DEFAULT_CRITIC_MODEL,
        total_items: int = 1,
        now_ms: int | None = None,
    ) -> int:
        ts = now_ms if now_ms is not None else now_epoch_ms()
        prio = priority if priority is not None else TASK_DEFAULT_PRIORITY.get(task_type, PRIORITY_P2)
        if prio not in (PRIORITY_P0, PRIORITY_P1, PRIORITY_P2):
            raise ValueError(f"Invalid priority {prio}; expected 0 (P0), 1 (P1), or 2 (P2)")
        payload_dict = dict(payload or {})
        payload_json = json.dumps(payload_dict, ensure_ascii=False, sort_keys=True)
        with contextlib.closing(self._connect()) as conn:
            with conn:
                cur = conn.execute(
                    """
                    INSERT INTO jobs (
                        priority, task_type, target_key, payload_json,
                        model_forward, model_critic, status, total_items,
                        created_at_ms, updated_at_ms, lease_expires_at_ms
                    ) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, 0)
                    """,
                    (
                        prio,
                        task_type,
                        target_key,
                        payload_json,
                        model_forward,
                        model_critic,
                        max(1, int(total_items)),
                        ts,
                        ts,
                    ),
                )
                return int(cur.lastrowid or 0)

    def acquire_p0_lease(
        self,
        holder_id: str,
        ttl_ms: int = DEFAULT_P0_LEASE_TTL_MS,
        now_ms: int | None = None,
    ) -> None:
        ts = now_ms if now_ms is not None else now_epoch_ms()
        expires = ts + max(1_000, int(ttl_ms))
        with contextlib.closing(self._connect()) as conn:
            with conn:
                conn.execute("DELETE FROM gpu_leases WHERE expires_at_ms <= ?", (ts,))
                conn.execute(
                    """
                    INSERT INTO gpu_leases (holder_id, priority, acquired_at_ms, expires_at_ms)
                    VALUES (?, 0, ?, ?)
                    ON CONFLICT(holder_id) DO UPDATE SET
                        priority = 0,
                        acquired_at_ms = excluded.acquired_at_ms,
                        expires_at_ms = excluded.expires_at_ms
                    """,
                    (holder_id, ts, expires),
                )

    def release_p0_lease(self, holder_id: str) -> None:
        with contextlib.closing(self._connect()) as conn:
            with conn:
                conn.execute("DELETE FROM gpu_leases WHERE holder_id = ?", (holder_id,))

    def has_active_p0_lease(self, now_ms: int | None = None) -> bool:
        ts = now_ms if now_ms is not None else now_epoch_ms()
        with contextlib.closing(self._connect()) as conn:
            with conn:
                conn.execute("DELETE FROM gpu_leases WHERE expires_at_ms <= ?", (ts,))
                row = conn.execute(
                    "SELECT 1 FROM gpu_leases WHERE priority = 0 AND expires_at_ms > ? LIMIT 1",
                    (ts,),
                ).fetchone()
                return row is not None

    def reclaim_expired_leases(self, now_ms: int | None = None) -> int:
        """Resets crashed RUNNING jobs whose chunk lease has expired back to PENDING."""
        ts = now_ms if now_ms is not None else now_epoch_ms()
        with contextlib.closing(self._connect()) as conn:
            with conn:
                conn.execute("DELETE FROM gpu_leases WHERE expires_at_ms <= ?", (ts,))
                cur = conn.execute(
                    """
                    UPDATE jobs
                    SET status = 'PENDING',
                        lease_expires_at_ms = 0,
                        updated_at_ms = ?
                    WHERE status = 'RUNNING'
                      AND lease_expires_at_ms > 0
                      AND lease_expires_at_ms <= ?
                    """,
                    (ts, ts),
                )
                return int(cur.rowcount or 0)

    def yield_if_higher_priority_pending(
        self,
        current_priority: int,
        now_ms: int | None = None,
    ) -> bool:
        """Returns True if an active P0 lease or a strictly higher-priority PENDING job exists."""
        ts = now_ms if now_ms is not None else now_epoch_ms()
        with contextlib.closing(self._connect()) as conn:
            with conn:
                conn.execute("DELETE FROM gpu_leases WHERE expires_at_ms <= ?", (ts,))
                if current_priority > PRIORITY_P0:
                    lease_row = conn.execute(
                        "SELECT 1 FROM gpu_leases WHERE priority < ? AND expires_at_ms > ? LIMIT 1",
                        (current_priority, ts),
                    ).fetchone()
                    if lease_row is not None:
                        return True
                job_row = conn.execute(
                    """
                    SELECT 1 FROM jobs
                    WHERE status = 'PENDING' AND priority < ?
                    LIMIT 1
                    """,
                    (current_priority,),
                ).fetchone()
                return job_row is not None

    def claim_next_job(
        self,
        lease_ttl_ms: int = DEFAULT_LEASE_TTL_MS,
        now_ms: int | None = None,
        job_id: int | None = None,
    ) -> dict[str, Any] | None:
        """Claims the highest-priority `(priority ASC, created_at_ms ASC)` PENDING job."""
        ts = now_ms if now_ms is not None else now_epoch_ms()
        self.reclaim_expired_leases(now_ms=ts)
        with contextlib.closing(self._connect()) as conn:
            with conn:
                active_p0 = conn.execute(
                    "SELECT 1 FROM gpu_leases WHERE priority = 0 AND expires_at_ms > ? LIMIT 1",
                    (ts,),
                ).fetchone()
                if job_id is not None:
                    row = conn.execute(
                        "SELECT * FROM jobs WHERE job_id = ? AND status = 'PENDING' LIMIT 1",
                        (job_id,),
                    ).fetchone()
                    if row is not None and active_p0 is not None and int(row["priority"]) > PRIORITY_P0:
                        return None
                elif active_p0 is not None:
                    row = conn.execute(
                        """
                        SELECT * FROM jobs
                        WHERE status = 'PENDING' AND priority = 0
                        ORDER BY priority ASC, created_at_ms ASC, job_id ASC
                        LIMIT 1
                        """
                    ).fetchone()
                else:
                    row = conn.execute(
                        """
                        SELECT * FROM jobs
                        WHERE status = 'PENDING'
                        ORDER BY priority ASC, created_at_ms ASC, job_id ASC
                        LIMIT 1
                        """
                    ).fetchone()

                if row is None:
                    return None
                claimed_id = int(row["job_id"])
                lease_exp = ts + max(5_000, int(lease_ttl_ms))
                conn.execute(
                    """
                    UPDATE jobs
                    SET status = 'RUNNING',
                        updated_at_ms = ?,
                        lease_expires_at_ms = ?
                    WHERE job_id = ?
                    """,
                    (ts, lease_exp, claimed_id),
                )
                updated = conn.execute("SELECT * FROM jobs WHERE job_id = ?", (claimed_id,)).fetchone()
                return dict(updated) if updated is not None else None

    def record_attempt(
        self,
        job_id: int,
        chunk_index: int,
        item_id: str,
        stage: str,
        passed: bool,
        reason: str = "",
        now_ms: int | None = None,
    ) -> None:
        ts = now_ms if now_ms is not None else now_epoch_ms()
        with contextlib.closing(self._connect()) as conn:
            with conn:
                conn.execute(
                    """
                    INSERT INTO job_attempts (
                        job_id, chunk_index, item_id, stage, passed, reason, created_at_ms
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    (job_id, chunk_index, item_id, stage, 1 if passed else 0, reason, ts),
                )

    def checkpoint_job_chunk(
        self,
        job_id: int,
        payload: dict[str, Any],
        total_items: int,
        completed_items: int,
        retried_items: int,
        quarantined_items: int,
        review_path: str,
        yield_to_pending: bool = False,
        lease_ttl_ms: int = DEFAULT_LEASE_TTL_MS,
        now_ms: int | None = None,
    ) -> None:
        ts = now_ms if now_ms is not None else now_epoch_ms()
        new_status = "PENDING" if yield_to_pending else "RUNNING"
        new_lease = 0 if yield_to_pending else ts + max(5_000, int(lease_ttl_ms))
        payload_json = json.dumps(payload, ensure_ascii=False, sort_keys=True)
        with contextlib.closing(self._connect()) as conn:
            with conn:
                conn.execute(
                    """
                    UPDATE jobs
                    SET payload_json = ?,
                        total_items = ?,
                        completed_items = ?,
                        retried_items = ?,
                        quarantined_items = ?,
                        review_path = ?,
                        status = ?,
                        lease_expires_at_ms = ?,
                        updated_at_ms = ?
                    WHERE job_id = ?
                    """,
                    (
                        payload_json,
                        max(1, int(total_items)),
                        int(completed_items),
                        int(retried_items),
                        int(quarantined_items),
                        review_path,
                        new_status,
                        new_lease,
                        ts,
                        job_id,
                    ),
                )

    def complete_job(
        self,
        job_id: int,
        status: str,
        total_items: int,
        completed_items: int,
        retried_items: int,
        quarantined_items: int,
        result_summary_md: str,
        review_path: str = "",
        payload: dict[str, Any] | None = None,
        now_ms: int | None = None,
    ) -> None:
        ts = now_ms if now_ms is not None else now_epoch_ms()
        with contextlib.closing(self._connect()) as conn:
            with conn:
                if payload is not None:
                    conn.execute(
                        """
                        UPDATE jobs
                        SET status = ?,
                            total_items = ?,
                            completed_items = ?,
                            retried_items = ?,
                            quarantined_items = ?,
                            result_summary_md = ?,
                            review_path = ?,
                            payload_json = ?,
                            lease_expires_at_ms = 0,
                            updated_at_ms = ?
                        WHERE job_id = ?
                        """,
                        (
                            status,
                            max(1, int(total_items)),
                            int(completed_items),
                            int(retried_items),
                            int(quarantined_items),
                            result_summary_md,
                            review_path,
                            json.dumps(payload, ensure_ascii=False, sort_keys=True),
                            ts,
                            job_id,
                        ),
                    )
                else:
                    conn.execute(
                        """
                        UPDATE jobs
                        SET status = ?,
                            total_items = ?,
                            completed_items = ?,
                            retried_items = ?,
                            quarantined_items = ?,
                            result_summary_md = ?,
                            review_path = ?,
                            lease_expires_at_ms = 0,
                            updated_at_ms = ?
                        WHERE job_id = ?
                        """,
                        (
                            status,
                            max(1, int(total_items)),
                            int(completed_items),
                            int(retried_items),
                            int(quarantined_items),
                            result_summary_md,
                            review_path,
                            ts,
                            job_id,
                        ),
                    )

    def cancel_job(self, job_id: int, now_ms: int | None = None) -> bool:
        ts = now_ms if now_ms is not None else now_epoch_ms()
        with contextlib.closing(self._connect()) as conn:
            with conn:
                cur = conn.execute(
                    """
                    UPDATE jobs
                    SET status = 'CANCELED',
                        lease_expires_at_ms = 0,
                        updated_at_ms = ?
                    WHERE job_id = ? AND status IN ('PENDING', 'RUNNING')
                    """,
                    (ts, job_id),
                )
                return bool(cur.rowcount and cur.rowcount > 0)

    def get_job(self, job_id: int) -> dict[str, Any] | None:
        with contextlib.closing(self._connect()) as conn:
            row = conn.execute("SELECT * FROM jobs WHERE job_id = ?", (job_id,)).fetchone()
            return dict(row) if row is not None else None

    def list_jobs(self, limit: int = 20) -> list[dict[str, Any]]:
        with contextlib.closing(self._connect()) as conn:
            rows = conn.execute(
                """
                SELECT * FROM jobs
                ORDER BY
                    CASE status WHEN 'RUNNING' THEN 0 WHEN 'PENDING' THEN 1 ELSE 2 END ASC,
                    priority ASC,
                    created_at_ms ASC,
                    job_id ASC
                LIMIT ?
                """,
                (max(1, int(limit)),),
            ).fetchall()
            return [dict(r) for r in rows]

    def get_status_digest(self, job_id: int | None = None, max_lines: int = 40) -> dict[str, Any]:
        """Builds a compact (< 40 lines) token-compressed status digest (`REQ-LLM-Q-004`)."""
        self.reclaim_expired_leases()
        with contextlib.closing(self._connect()) as conn:
            active_leases = [
                dict(r)
                for r in conn.execute(
                    "SELECT holder_id, priority, expires_at_ms FROM gpu_leases ORDER BY priority ASC"
                ).fetchall()
            ]
            counts_row = conn.execute(
                """
                SELECT
                     COUNT(*) AS total_jobs,
                     COALESCE(SUM(CASE WHEN status = 'PENDING' THEN 1 ELSE 0 END), 0) AS pending_jobs,
                     COALESCE(SUM(CASE WHEN status = 'RUNNING' THEN 1 ELSE 0 END), 0) AS running_jobs,
                     COALESCE(SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END), 0) AS completed_jobs,
                     COALESCE(SUM(CASE WHEN status = 'NEEDS_REVIEW' THEN 1 ELSE 0 END), 0) AS needs_review_jobs,
                     COALESCE(SUM(completed_items), 0) AS auto_applied,
                     COALESCE(SUM(retried_items), 0) AS retried_ok,
                     COALESCE(SUM(quarantined_items), 0) AS quarantined
                FROM jobs
                """
            ).fetchone()

        jobs = [self.get_job(job_id)] if job_id is not None else self.list_jobs(limit=10)
        jobs_clean = [j for j in jobs if j is not None]

        lines: list[str] = ["### Local LLM Priority Queue Digest"]
        if counts_row is not None:
            lines.append(
                f"- **Queue**: `{counts_row['pending_jobs']}` pending, "
                f"`{counts_row['running_jobs']}` running, "
                f"`{counts_row['completed_jobs']}` completed, "
                f"`{counts_row['needs_review_jobs']}` needs_review"
            )
            lines.append(
                f"- **Totals**: `{counts_row['auto_applied']}` auto_applied, "
                f"`{counts_row['retried_ok']}` retried_ok, "
                f"`{counts_row['quarantined']}` quarantined"
            )
        if active_leases:
            holders = ", ".join(f"`{l['holder_id']}` (P{l['priority']})" for l in active_leases)
            lines.append(f"- **Active Leases**: {holders}")

        for j in jobs_clean:
            lines.append(
                f"- **Job `#{j['job_id']}`** (`P{j['priority']}` `{j['task_type']}` -> `{j['target_key']}`): "
                f"**{j['status']}** (`{j['completed_items']}/{j['total_items']}` applied, "
                f"`{j['retried_items']}` retried, `{j['quarantined_items']}` quarantined)"
            )
            if j.get("review_path"):
                lines.append(f"  - Review digest: `{j['review_path']}`")
            if j.get("result_summary_md"):
                for sl in str(j["result_summary_md"]).splitlines()[:8]:
                    if sl.strip():
                        lines.append(f"  {sl}")

        trimmed_md = "\n".join(lines[: max(5, int(max_lines))])
        return {
            "total_jobs": int(counts_row["total_jobs"]) if counts_row else 0,
            "pending_jobs": int(counts_row["pending_jobs"]) if counts_row else 0,
            "running_jobs": int(counts_row["running_jobs"]) if counts_row else 0,
            "completed_jobs": int(counts_row["completed_jobs"]) if counts_row else 0,
            "needs_review_jobs": int(counts_row["needs_review_jobs"]) if counts_row else 0,
            "auto_applied": int(counts_row["auto_applied"]) if counts_row else 0,
            "retried_ok": int(counts_row["retried_ok"]) if counts_row else 0,
            "quarantined": int(counts_row["quarantined"]) if counts_row else 0,
            "active_leases": active_leases,
            "jobs": jobs_clean,
            "markdown": trimmed_md,
        }


@contextlib.contextmanager
def p0_gpu_lease(
    db_path: pathlib.Path | str = DEFAULT_QUEUE_DB_PATH,
    holder_id: str = "discord-support-bot",
    ttl_ms: int = DEFAULT_P0_LEASE_TTL_MS,
    only_if_db_exists: bool = True,
) -> Iterator[bool]:
    """Context manager acquiring a P0 GPU preemption lease when `llm-queue.sqlite` exists."""
    resolved = pathlib.Path(db_path)
    if only_if_db_exists and not resolved.exists():
        yield False
        return
    broker: LlmQueueBroker | None = None
    try:
        broker = LlmQueueBroker(resolved)
        broker.acquire_p0_lease(holder_id=holder_id, ttl_ms=ttl_ms)
    except sqlite3.Error:
        broker = None
    try:
        yield broker is not None
    finally:
        if broker is not None:
            with contextlib.suppress(sqlite3.Error):
                broker.release_p0_lease(holder_id=holder_id)


@dataclass
class QuarantinedItem:
    item_id: str
    stage: str
    reason: str
    item: dict[str, Any]
    draft: dict[str, Any]


@dataclass
class ChunkFunnelResult:
    total_items: int = 0
    applied_count: int = 0
    retried_ok_count: int = 0
    quarantined: list[dict[str, Any]] = field(default_factory=list)
    verified_pairs: list[tuple[dict[str, Any], dict[str, Any]]] = field(default_factory=list)
    summary_notes: list[str] = field(default_factory=list)


class OffloadTaskHandler(Protocol):
    """Protocol implemented by each offloaded local GPU task family (`REQ-LLM-Q-002`)."""

    task_type: str
    default_priority: int

    def split_chunks(self, payload: dict[str, Any]) -> list[list[dict[str, Any]]]:
        ...

    def build_forward_messages(self, chunk: list[dict[str, Any]]) -> list[dict[str, str]]:
        ...

    def parse_forward_response(
        self,
        chunk: list[dict[str, Any]],
        raw_response: str,
    ) -> list[dict[str, Any]]:
        ...

    def verify_deterministic(
        self,
        item: dict[str, Any],
        draft: dict[str, Any],
    ) -> tuple[bool, str]:
        ...

    def build_critic_messages(
        self,
        item: dict[str, Any],
        draft: dict[str, Any],
    ) -> list[dict[str, str]]:
        ...

    def parse_critic_verdict(self, raw_response: str) -> tuple[bool, str]:
        ...

    def build_repair_messages(
        self,
        item: dict[str, Any],
        draft: dict[str, Any],
        reason: str,
    ) -> list[dict[str, str]]:
        ...

    def parse_repair_response(
        self,
        item: dict[str, Any],
        raw_response: str,
    ) -> dict[str, Any]:
        ...

    def apply_verified(
        self,
        verified_items: list[tuple[dict[str, Any], dict[str, Any]]],
    ) -> int:
        ...

    def review_file_path(self, payload: dict[str, Any]) -> pathlib.Path:
        ...

    def format_quarantine_entry(
        self,
        item: dict[str, Any],
        draft: dict[str, Any],
        stage: str,
        reason: str,
    ) -> str:
        ...


def parse_standard_critic_verdict(raw_response: str) -> tuple[bool, str]:
    """Parses `PASS` vs `DRIFT: <reason>` or JSON `{"verdict": "PASS"|"DRIFT", "reason": "..."}`."""
    cleaned = strip_think_tags(raw_response).strip()
    if not cleaned:
        return False, "empty critic response"
    if cleaned.startswith("{"):
        with contextlib.suppress(ValueError):
            parsed = extract_json_payload(cleaned)
            if isinstance(parsed, dict):
                verdict = str(parsed.get("verdict") or parsed.get("status") or "").strip().upper()
                reason = str(parsed.get("reason") or parsed.get("details") or "").strip()
                if verdict in ("PASS", "OK", "VALID", "TRUE"):
                    return True, "ok"
                return False, reason or f"critic verdict: {verdict}"
    first_line = cleaned.splitlines()[0].strip()
    if re.match(r"(?i)^PASS\b", first_line):
        return True, "ok"
    m_drift = re.match(r"(?i)^(?:DRIFT|FAIL|REJECT)\s*:?\s*(.*)$", first_line)
    if m_drift:
        detail = m_drift.group(1).strip() or cleaned
        return False, detail
    if "DRIFT" in cleaned.upper() or "FAIL" in cleaned.upper():
        return False, first_line[:200]
    if "PASS" in first_line.upper():
        return True, "ok"
    return False, first_line[:200]


class StagedFunnelRunner:
    """Executes `Forward -> Deterministic Gate -> Critic -> 1-Pass Repair -> Apply/Quarantine`."""

    def __init__(
        self,
        client: LocalLlmClient,
        broker: LlmQueueBroker | None = None,
        max_retries: int = 1,
        out_dir: pathlib.Path = DEFAULT_OUT_DIR,
    ) -> None:
        self.client = client
        self.broker = broker
        self.max_retries = max(0, int(max_retries))
        self.out_dir = pathlib.Path(out_dir)
        self.out_dir.mkdir(parents=True, exist_ok=True)

    def _record(
        self,
        job_id: int | None,
        chunk_index: int,
        item_id: str,
        stage: str,
        passed: bool,
        reason: str = "",
    ) -> None:
        if self.broker is not None and job_id is not None:
            self.broker.record_attempt(
                job_id=job_id,
                chunk_index=chunk_index,
                item_id=item_id,
                stage=stage,
                passed=passed,
                reason=reason,
            )

    def execute_chunk(
        self,
        chunk: list[dict[str, Any]],
        handler: OffloadTaskHandler,
        job_id: int | None = None,
        chunk_index: int = 0,
    ) -> ChunkFunnelResult:
        res = ChunkFunnelResult(total_items=len(chunk))
        if not chunk:
            return res

        forward_msgs = handler.build_forward_messages(chunk)
        raw_forward = self.client.chat_forward(forward_msgs)
        drafts = handler.parse_forward_response(chunk, raw_forward)
        while len(drafts) < len(chunk):
            drafts.append({})

        verified_pairs: list[tuple[dict[str, Any], dict[str, Any]]] = []
        for idx, item in enumerate(chunk):
            item_id = str(item.get("item_id") or item.get("base_key") or item.get("class_name") or f"item_{idx}")
            draft = drafts[idx] if idx < len(drafts) and isinstance(drafts[idx], dict) else {}

            det_ok, det_reason = handler.verify_deterministic(item, draft)
            self._record(job_id, chunk_index, item_id, "deterministic_1", det_ok, det_reason)

            need_repair = False
            failure_stage = ""
            failure_reason = ""

            if not det_ok:
                need_repair = True
                failure_stage = "deterministic"
                failure_reason = det_reason
            else:
                critic_msgs = handler.build_critic_messages(item, draft)
                raw_critic = self.client.chat_critic(critic_msgs)
                crit_ok, crit_reason = handler.parse_critic_verdict(raw_critic)
                self._record(job_id, chunk_index, item_id, "critic_1", crit_ok, crit_reason)
                if not crit_ok:
                    need_repair = True
                    failure_stage = "critic"
                    failure_reason = crit_reason

            if not need_repair:
                verified_pairs.append((item, draft))
                continue

            repaired_ok = False
            if self.max_retries >= 1:
                repair_msgs = handler.build_repair_messages(item, draft, f"[{failure_stage}] {failure_reason}")
                raw_repair = self.client.chat_repair(repair_msgs)
                repaired_draft = handler.parse_repair_response(item, raw_repair)
                draft = repaired_draft

                det2_ok, det2_reason = handler.verify_deterministic(item, repaired_draft)
                self._record(job_id, chunk_index, item_id, "deterministic_repair", det2_ok, det2_reason)
                if det2_ok:
                    critic2_msgs = handler.build_critic_messages(item, repaired_draft)
                    raw_critic2 = self.client.chat_critic(critic2_msgs)
                    crit2_ok, crit2_reason = handler.parse_critic_verdict(raw_critic2)
                    self._record(job_id, chunk_index, item_id, "critic_repair", crit2_ok, crit2_reason)
                    if crit2_ok:
                        repaired_ok = True
                    else:
                        failure_stage = "critic_after_repair"
                        failure_reason = crit2_reason
                else:
                    failure_stage = "deterministic_after_repair"
                    failure_reason = det2_reason

            if repaired_ok:
                res.retried_ok_count += 1
                verified_pairs.append((item, draft))
            else:
                res.quarantined.append(
                    {
                        "item_id": item_id,
                        "stage": failure_stage,
                        "reason": failure_reason,
                        "item": item,
                        "draft": draft,
                    }
                )

        if verified_pairs:
            applied = handler.apply_verified(verified_pairs)
            res.applied_count = int(applied)
            res.verified_pairs = verified_pairs
        return res

    def append_quarantine_digest(
        self,
        review_path: pathlib.Path,
        task_type: str,
        target_key: str,
        quarantined: list[dict[str, Any]],
        handler: OffloadTaskHandler,
    ) -> None:
        if not quarantined:
            return
        review_path.parent.mkdir(parents=True, exist_ok=True)
        existing = ""
        if review_path.exists():
            existing = review_path.read_text(encoding="utf-8")
        lines: list[str] = []
        if not existing.strip():
            lines.append(f"### Quarantine Review Digest (`{task_type}`)")
            lines.append("")
        for q in quarantined:
            entry = handler.format_quarantine_entry(
                item=q["item"],
                draft=q["draft"],
                stage=str(q["stage"]),
                reason=str(q["reason"]),
            )
            lines.append(entry.rstrip())
        body = (existing.rstrip("\n") + "\n" if existing.strip() else "") + "\n".join(lines) + "\n"
        review_path.write_bytes(body.replace("\r\n", "\n").encode("utf-8"))

    def run_job(
        self,
        job: dict[str, Any],
        handler: OffloadTaskHandler,
        max_chunks: int | None = None,
        now_fn: Callable[[], int] = now_epoch_ms,
    ) -> dict[str, Any]:
        """Executes a claimed job chunk-by-chunk, yielding between chunks on P0/P1 preemption."""
        job_id = int(job["job_id"])
        priority = int(job["priority"])
        task_type = str(job["task_type"])
        target_key = str(job["target_key"])
        payload: dict[str, Any] = json.loads(str(job.get("payload_json") or "{}"))

        chunks = handler.split_chunks(payload)
        total_items = sum(len(c) for c in chunks)
        start_chunk_idx = int(payload.get("_next_chunk_index", 0))
        completed_items = int(job.get("completed_items", 0))
        retried_items = int(job.get("retried_items", 0))
        quarantined_items = int(job.get("quarantined_items", 0))
        review_file = handler.review_file_path(payload)
        review_rel = str(review_file)
        with contextlib.suppress(ValueError):
            review_rel = str(review_file.resolve().relative_to(REPO_ROOT.resolve())).replace("\\", "/")

        if not chunks or start_chunk_idx >= len(chunks):
            status = "NEEDS_REVIEW" if quarantined_items > 0 else "COMPLETED"
            summary = (
                f"- Completed `{task_type}` (`{target_key}`): "
                f"`{completed_items}` applied, `{retried_items}` repaired, `{quarantined_items}` quarantined."
            )
            if self.broker is not None:
                self.broker.complete_job(
                    job_id=job_id,
                    status=status,
                    total_items=max(1, total_items),
                    completed_items=completed_items,
                    retried_items=retried_items,
                    quarantined_items=quarantined_items,
                    result_summary_md=summary,
                    review_path=review_rel if quarantined_items > 0 else "",
                    payload=payload,
                    now_ms=now_fn(),
                )
            return {
                "job_id": job_id,
                "status": status,
                "total_items": total_items,
                "completed_items": completed_items,
                "retried_items": retried_items,
                "quarantined_items": quarantined_items,
                "summary": summary,
            }

        chunks_processed = 0
        summary_notes: list[str] = list(payload.get("_summary_notes") or [])
        for chunk_idx in range(start_chunk_idx, len(chunks)):
            chunk = chunks[chunk_idx]
            try:
                c_res = self.execute_chunk(
                    chunk=chunk,
                    handler=handler,
                    job_id=job_id,
                    chunk_index=chunk_idx,
                )
            except LocalLlmError as exc:
                # Fail closed: preserve uncommitted chunk index in PENDING state without corrupting artifacts
                if self.broker is not None:
                    self.broker.checkpoint_job_chunk(
                        job_id=job_id,
                        payload=payload,
                        total_items=max(1, total_items),
                        completed_items=completed_items,
                        retried_items=retried_items,
                        quarantined_items=quarantined_items,
                        review_path=review_rel if quarantined_items > 0 else "",
                        yield_to_pending=True,
                        now_ms=now_fn(),
                    )
                return {
                    "job_id": job_id,
                    "status": "OFFLINE",
                    "error": str(exc),
                    "completed_items": completed_items,
                    "retried_items": retried_items,
                    "quarantined_items": quarantined_items,
                }

            completed_items += c_res.applied_count
            retried_items += c_res.retried_ok_count
            quarantined_items += len(c_res.quarantined)
            if c_res.summary_notes:
                summary_notes.extend(c_res.summary_notes)
            if c_res.quarantined:
                self.append_quarantine_digest(
                    review_path=review_file,
                    task_type=task_type,
                    target_key=target_key,
                    quarantined=c_res.quarantined,
                    handler=handler,
                )

            chunks_processed += 1
            next_idx = chunk_idx + 1
            payload["_next_chunk_index"] = next_idx
            if summary_notes:
                payload["_summary_notes"] = summary_notes[:25]

            has_more_chunks = next_idx < len(chunks)
            should_preempt = False
            if has_more_chunks and self.broker is not None:
                if self.broker.yield_if_higher_priority_pending(priority, now_ms=now_fn()):
                    should_preempt = True
            if has_more_chunks and max_chunks is not None and chunks_processed >= max_chunks:
                should_preempt = True

            if has_more_chunks and should_preempt:
                if self.broker is not None:
                    self.broker.checkpoint_job_chunk(
                        job_id=job_id,
                        payload=payload,
                        total_items=max(1, total_items),
                        completed_items=completed_items,
                        retried_items=retried_items,
                        quarantined_items=quarantined_items,
                        review_path=review_rel if quarantined_items > 0 else "",
                        yield_to_pending=True,
                        now_ms=now_fn(),
                    )
                return {
                    "job_id": job_id,
                    "status": "PREEMPTED",
                    "next_chunk_index": next_idx,
                    "total_chunks": len(chunks),
                    "completed_items": completed_items,
                    "retried_items": retried_items,
                    "quarantined_items": quarantined_items,
                }

            if has_more_chunks and self.broker is not None:
                self.broker.checkpoint_job_chunk(
                    job_id=job_id,
                    payload=payload,
                    total_items=max(1, total_items),
                    completed_items=completed_items,
                    retried_items=retried_items,
                    quarantined_items=quarantined_items,
                    review_path=review_rel if quarantined_items > 0 else "",
                    yield_to_pending=False,
                    now_ms=now_fn(),
                )

        final_status = "NEEDS_REVIEW" if quarantined_items > 0 else "COMPLETED"
        summary_lines = [
            f"- Completed `{task_type}` (`{target_key}`): "
            f"`{completed_items}/{total_items}` applied, `{retried_items}` repaired, `{quarantined_items}` quarantined."
        ]
        for note in summary_notes[:12]:
            summary_lines.append(f"- {note}")
        summary_md = "\n".join(summary_lines)

        if self.broker is not None:
            self.broker.complete_job(
                job_id=job_id,
                status=final_status,
                total_items=max(1, total_items),
                completed_items=completed_items,
                retried_items=retried_items,
                quarantined_items=quarantined_items,
                result_summary_md=summary_md,
                review_path=review_rel if quarantined_items > 0 else "",
                payload=payload,
                now_ms=now_fn(),
            )
        return {
            "job_id": job_id,
            "status": final_status,
            "total_items": total_items,
            "completed_items": completed_items,
            "retried_items": retried_items,
            "quarantined_items": quarantined_items,
            "review_path": review_rel if quarantined_items > 0 else "",
            "summary": summary_md,
        }


def _load_script_module(script_name: str, module_alias: str) -> Any:
    path = SCRIPTS_DIR / script_name
    spec = importlib.util.spec_from_file_location(module_alias, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Unable to load script module {path}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


_EXPORT_BUNDLE_MOD: Any = None
_IMPORT_BUNDLE_MOD: Any = None


def _export_bundle_mod() -> Any:
    global _EXPORT_BUNDLE_MOD
    if _EXPORT_BUNDLE_MOD is None:
        _EXPORT_BUNDLE_MOD = _load_script_module("export-translation-bundle.py", "_export_translation_bundle")
    return _EXPORT_BUNDLE_MOD


def _import_bundle_mod() -> Any:
    global _IMPORT_BUNDLE_MOD
    if _IMPORT_BUNDLE_MOD is None:
        _IMPORT_BUNDLE_MOD = _load_script_module("import-translation-bundle.py", "_import_translation_bundle")
    return _IMPORT_BUNDLE_MOD


def mask_bundle_text(text: str, legend: list[str]) -> str:
    return str(_export_bundle_mod().mask_text(text, legend))


def unescape_tsv_comment(s: str | None) -> str:
    return str(_export_bundle_mod().unescape_tsv_comment(s))


def extract_sentinels(s: str) -> list[str]:
    return list(_import_bundle_mod().extract_sentinels(s))


def parse_legend(line: str) -> dict[str, str]:
    return dict(_import_bundle_mod().parse_legend(line))


def unmask_text(s: str, legend: dict[str, str]) -> str:
    return str(_import_bundle_mod().unmask(s, legend))


def has_mojibake(s: str) -> bool:
    return bool(_import_bundle_mod().has_mojibake(s))


def escape_tsv_comment(s: str | None) -> str:
    return str(_import_bundle_mod().escape_tsv_comment(s))


def format_legend_line(legend: list[str]) -> str:
    if not legend:
        return "(none)"
    parts: list[str] = []
    for idx, val in enumerate(legend):
        flat = val.replace("\r\n", "\\n").replace("\n", "\\n").replace("\t", "\\t")
        parts.append(f"P{idx}={flat}")
    return "; ".join(parts)


class TranslateBundleHandler:
    """Drains `bleed-<lang>.tsv` rows in chunks of 5-10 with `<<P#>>` sentinel & blind critic gates."""

    task_type = "translate-bundle"
    default_priority = PRIORITY_P2

    def __init__(self, out_dir: pathlib.Path = DEFAULT_OUT_DIR) -> None:
        self.out_dir = pathlib.Path(out_dir)

    def _load_bleed_items(self, target: str, max_rows: int = 0) -> list[dict[str, Any]]:
        if target in ("all", "*"):
            bleed_files = sorted(self.out_dir.glob("bleed-*.tsv"))
        else:
            bleed_path = self.out_dir / f"bleed-{target}.tsv"
            bleed_files = [bleed_path] if bleed_path.exists() else []

        items: list[dict[str, Any]] = []
        for bf in bleed_files:
            lang = re.sub(r"^bleed-", "", bf.stem)
            raw = bf.read_bytes()
            if raw.startswith(b"\xef\xbb\xbf"):
                raw = raw[3:]
            lines = re.split(r"\r?\n", raw.decode("utf-8"))
            data_lines = [ln for ln in lines[1:] if ln != ""]
            if max_rows > 0:
                data_lines = data_lines[:max_rows]
            for row_idx, ln in enumerate(data_lines):
                cols = ln.split("\t", 7)
                if len(cols) != 8:
                    continue
                relpath, parent_path, key_col, index_col, value_en, comment_en_raw, blank_before, base_key = cols
                legend: list[str] = []
                value_masked = mask_bundle_text(value_en, legend)
                comment_raw = unescape_tsv_comment(comment_en_raw)
                comment_masked = mask_bundle_text(comment_raw, legend)
                legend_str = format_legend_line(legend)
                item_id = f"{lang}:{relpath}::{parent_path}.{base_key}[{index_col}]"
                items.append(
                    {
                        "item_id": item_id,
                        "locale": lang,
                        "relpath": relpath,
                        "parent_path": parent_path,
                        "base_key": base_key,
                        "index": index_col,
                        "blank_before": blank_before,
                        "sentinels": legend_str,
                        "value_english": value_masked,
                        "comment_english": comment_masked,
                        "key_locale": key_col,
                        "row_ordinal": row_idx,
                    }
                )
        return items

    def split_chunks(self, payload: dict[str, Any]) -> list[list[dict[str, Any]]]:
        chunk_size = max(1, min(10, int(payload.get("chunk_size", 8))))
        raw_items = payload.get("items")
        if isinstance(raw_items, list) and raw_items:
            items = [dict(x) for x in raw_items]
        else:
            target = str(payload.get("target") or payload.get("locale") or "all").strip()
            max_rows = int(payload.get("max_rows", 0))
            items = self._load_bleed_items(target=target, max_rows=max_rows)
        return [items[i : i + chunk_size] for i in range(0, len(items), chunk_size)]

    def build_forward_messages(self, chunk: list[dict[str, Any]]) -> list[dict[str, str]]:
        system_prompt = (
            "You are the LeafRTP localization engine (`TRANSLATION_GUIDE.md`).\n"
            "Rules:\n"
            "1. Translate `value_english` -> `value_locale` and `comment_english` -> `comment_locale` into the item's `locale`.\n"
            "2. Preserve every `<<P0>>`, `<<P1>>`, ... sentinel token verbatim with exact multiset parity.\n"
            "3. Keep `key_locale` identical to `base_key` (Rule 8 identity-key preservation) unless `key_locale` is already localized.\n"
            "4. Never emit mojibake; preserve Minecraft/RTP technical accuracy (`chunk`, `region`, `ticket`, `worldborder`, `cooldown`, `ticks`, `ms`, `blocks`).\n"
            "5. Return a JSON array of objects with keys `item_id`, `key_locale`, `value_locale`, `comment_locale`."
        )
        prompt_items = [
            {
                "item_id": it["item_id"],
                "locale": it["locale"],
                "base_key": it["base_key"],
                "key_locale": it.get("key_locale", it["base_key"]),
                "sentinels": it.get("sentinels", "(none)"),
                "value_english": it.get("value_english", ""),
                "comment_english": it.get("comment_english", ""),
            }
            for it in chunk
        ]
        return [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": json.dumps(prompt_items, ensure_ascii=False)},
        ]

    def parse_forward_response(
        self,
        chunk: list[dict[str, Any]],
        raw_response: str,
    ) -> list[dict[str, Any]]:
        cleaned = strip_think_tags(raw_response)
        if "```bundle" in cleaned:
            blocks = _import_bundle_mod().parse_blocks(cleaned, chunk[0].get("locale", ""))
            return [
                {
                    "item_id": chunk[i]["item_id"] if i < len(chunk) else "",
                    "key_locale": b.get("key_locale", ""),
                    "value_locale": b.get("value_locale", ""),
                    "comment_locale": b.get("comment_locale", ""),
                }
                for i, b in enumerate(blocks)
            ]
        parsed = extract_json_payload(cleaned)
        if isinstance(parsed, dict) and isinstance(parsed.get("items"), list):
            parsed = parsed["items"]
        if isinstance(parsed, list):
            by_id = {
                str(d.get("item_id")): dict(d)
                for d in parsed
                if isinstance(d, dict) and d.get("item_id")
            }
            out: list[dict[str, Any]] = []
            for idx, it in enumerate(chunk):
                iid = str(it.get("item_id") or "")
                if iid in by_id:
                    out.append(by_id[iid])
                elif idx < len(parsed) and isinstance(parsed[idx], dict):
                    out.append(dict(parsed[idx]))
                else:
                    out.append({})
            return out
        if isinstance(parsed, dict):
            return [dict(parsed)]
        return [{} for _ in chunk]

    def verify_deterministic(
        self,
        item: dict[str, Any],
        draft: dict[str, Any],
    ) -> tuple[bool, str]:
        val_en = str(item.get("value_english") or "")
        cmt_en = str(item.get("comment_english") or "")
        val_lo = str(draft.get("value_locale") if draft.get("value_locale") is not None else "")
        cmt_lo = str(draft.get("comment_locale") if draft.get("comment_locale") is not None else "")
        key_lo = str(draft.get("key_locale") or item.get("key_locale") or item.get("base_key") or "")

        if not val_lo and val_en:
            return False, "missing value_locale"
        if not cmt_lo and cmt_en:
            return False, "missing comment_locale"

        sent_val_en = sorted(extract_sentinels(val_en))
        sent_val_lo = sorted(extract_sentinels(val_lo))
        if sent_val_en != sent_val_lo:
            return False, f"value sentinel mismatch: expected {sent_val_en}, got {sent_val_lo}"

        sent_cmt_en = sorted(extract_sentinels(cmt_en))
        sent_cmt_lo = sorted(extract_sentinels(cmt_lo))
        if sent_cmt_en != sent_cmt_lo:
            return False, f"comment sentinel mismatch: expected {sent_cmt_en}, got {sent_cmt_lo}"

        if has_mojibake(val_lo) or has_mojibake(cmt_lo) or has_mojibake(key_lo):
            return False, "mojibake detected in locale output"

        if (val_en or cmt_en) and val_lo == val_en and cmt_lo == cmt_en:
            return False, "untranslated no-op output identical to English baseline"

        return True, "ok"

    def build_critic_messages(
        self,
        item: dict[str, Any],
        draft: dict[str, Any],
    ) -> list[dict[str, str]]:
        system_prompt = (
            "You are an independent Opinion 2 semantic back-translation & glossary critic for LeafRTP.\n"
            "Perform a blind back-translation check of `value_locale` and `comment_locale` and compare against "
            "`value_english` and `comment_english`:\n"
            "1. Domain glossary check: `chunk` must mean Minecraft world chunk (not slice/bread), `region` must mean "
            "RTP teleport region, `ticket` must mean chunk-load ticket, `cooldown` must mean timer delay.\n"
            "2. Units & directionality: verify numeric units (`ticks`, `ms`, `blocks`, `chunks`) and boolean/negation "
            "polarity (`true`/`false`, `enable`/`disable`, `minimum`/`maximum`) are not inverted.\n"
            "3. Rule 8 identity keys: `key_locale` must not corrupt YAML syntax.\n"
            "Respond with `PASS` on the first line if accurate, or `DRIFT: <concise reason>` if any meaning shifted."
        )
        user_payload = {
            "locale": item.get("locale"),
            "base_key": item.get("base_key"),
            "sentinels": item.get("sentinels"),
            "value_english": item.get("value_english"),
            "comment_english": item.get("comment_english"),
            "key_locale": draft.get("key_locale") or item.get("key_locale"),
            "value_locale": draft.get("value_locale"),
            "comment_locale": draft.get("comment_locale"),
        }
        return [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": json.dumps(user_payload, ensure_ascii=False)},
        ]

    def parse_critic_verdict(self, raw_response: str) -> tuple[bool, str]:
        return parse_standard_critic_verdict(raw_response)

    def build_repair_messages(
        self,
        item: dict[str, Any],
        draft: dict[str, Any],
        reason: str,
    ) -> list[dict[str, str]]:
        system_prompt = (
            "Repair the rejected LeafRTP locale translation item so it fixes the reported deterministic/critic issue.\n"
            "Preserve all `<<P#>>` sentinels verbatim and return a single JSON object with keys "
            "`item_id`, `key_locale`, `value_locale`, `comment_locale`."
        )
        user_payload = {
            "item": item,
            "rejected_draft": draft,
            "rejection_reason": reason,
        }
        return [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": json.dumps(user_payload, ensure_ascii=False)},
        ]

    def parse_repair_response(
        self,
        item: dict[str, Any],
        raw_response: str,
    ) -> dict[str, Any]:
        parsed = extract_json_payload(raw_response)
        if isinstance(parsed, list) and parsed and isinstance(parsed[0], dict):
            return dict(parsed[0])
        if isinstance(parsed, dict):
            return dict(parsed)
        return {}

    def apply_verified(
        self,
        verified_items: list[tuple[dict[str, Any], dict[str, Any]]],
    ) -> int:
        tsv_store: dict[str, dict[str, Any]] = {}

        def _load_store(lang: str) -> dict[str, Any] | None:
            if lang in tsv_store:
                return tsv_store[lang]
            path = self.out_dir / f"locale-{lang}.tsv"
            if not path.exists():
                return None
            raw = path.read_bytes()
            if raw.startswith(b"\xef\xbb\xbf"):
                raw = raw[3:]
            lines = re.split(r"\r?\n", raw.decode("utf-8"))
            header = lines[0] if lines else "relpath\tparent_path\tkey\tindex\tvalue\tcomment\tblank_before\tbase_key"
            data = list(lines[1:])
            if data and data[-1] == "":
                data.pop()
            idx_map: dict[str, int] = {}
            for i, row in enumerate(data):
                c = row.split("\t", 7)
                if len(c) == 8:
                    idx_map[f"{c[0]}|{c[1]}|{c[7]}|{c[3]}"] = i
            entry = {"path": path, "header": header, "data": data, "index": idx_map, "dirty": False}
            tsv_store[lang] = entry
            return entry

        applied = 0
        for item, draft in verified_items:
            lang = str(item.get("locale") or "")
            if not lang:
                continue
            store = _load_store(lang)
            if store is None:
                continue
            match_key = f"{item.get('relpath', '')}|{item.get('parent_path', '')}|{item.get('base_key', '')}|{item.get('index', '')}"
            if match_key not in store["index"]:
                continue
            legend = parse_legend(str(item.get("sentinels") or "(none)"))
            val_unmasked = unmask_text(str(draft.get("value_locale") or ""), legend)
            cmt_unmasked = unmask_text(str(draft.get("comment_locale") or ""), legend)
            row_idx = store["index"][match_key]
            cols = store["data"][row_idx].split("\t", 7)
            key_lo = str(draft.get("key_locale") or item.get("key_locale") or "").strip()
            if key_lo:
                cols[2] = key_lo
            cols[4] = val_unmasked
            cols[5] = escape_tsv_comment(cmt_unmasked)
            store["data"][row_idx] = "\t".join(cols)
            store["dirty"] = True
            applied += 1

        for lang in sorted(tsv_store):
            store = tsv_store[lang]
            if not store["dirty"]:
                continue
            path = store["path"]
            bak = pathlib.Path(str(path) + ".bak")
            shutil.copyfile(path, bak)
            text = "\n".join([store["header"]] + store["data"]) + "\n"
            path.write_bytes(text.replace("\r\n", "\n").encode("utf-8"))
        return applied

    def review_file_path(self, payload: dict[str, Any]) -> pathlib.Path:
        target = str(payload.get("target") or payload.get("locale") or "all").strip()
        if target and target not in ("all", "*"):
            return self.out_dir / f"review-locale-{target}.md"
        return self.out_dir / "review-translate-bundle.md"

    def format_quarantine_entry(
        self,
        item: dict[str, Any],
        draft: dict[str, Any],
        stage: str,
        reason: str,
    ) -> str:
        return (
            f"- **`{item.get('item_id')}`** (`{stage}`): {reason}\n"
            f"  - `en`: `{item.get('value_english', '')}` | `draft`: `{draft.get('value_locale', '')}`"
        )


_DOC_MASK_PATTERNS: tuple[re.Pattern[str], ...] = (
    re.compile(r"(?s)```.*?```"),
    re.compile(r"`[^`\r\n]+`"),
    re.compile(r"\]\(([^)\s]+)\)"),
    re.compile(r"<https?://[^>\s]+>"),
)


def mask_markdown_section(text: str) -> tuple[str, list[str]]:
    """Masks fenced code blocks, inline code spans, and link targets into `<<P#>>` sentinels."""
    legend: list[str] = []
    if not text:
        return "", legend

    def _alloc(val: str) -> str:
        tok = f"<<P{len(legend)}>>"
        legend.append(val)
        return tok

    masked = re.sub(r"(?s)```.*?```", lambda m: _alloc(m.group(0)), text)
    masked = re.sub(r"`[^`\r\n]+`", lambda m: _alloc(m.group(0)), masked)
    masked = re.sub(r"\]\(([^)\s]+)\)", lambda m: f"]({_alloc(m.group(1))})", masked)
    masked = re.sub(r"<https?://[^>\s]+>", lambda m: _alloc(m.group(0)), masked)
    return masked, legend


def extract_heading_levels(markdown_text: str) -> list[int]:
    """Returns the sequence of `#` heading levels outside fenced code blocks."""
    levels: list[int] = []
    in_fence = False
    for line in markdown_text.splitlines():
        s = line.strip()
        if s.startswith("```"):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        m = re.match(r"^(#{1,6})\s+\S", s)
        if m:
            levels.append(len(m.group(1)))
    return levels


def split_markdown_sections(markdown_text: str) -> list[str]:
    """Splits a Markdown document into sections at `##` / `###` heading boundaries."""
    lines = markdown_text.replace("\r\n", "\n").splitlines()
    sections: list[list[str]] = []
    current: list[str] = []
    in_fence = False
    for ln in lines:
        if ln.strip().startswith("```"):
            in_fence = not in_fence
        if not in_fence and re.match(r"^#{2,3}\s+\S", ln) and current:
            sections.append(current)
            current = [ln]
        else:
            current.append(ln)
    if current:
        sections.append(current)
    return ["\n".join(sec).strip("\n") for sec in sections if "\n".join(sec).strip()]


class TranslateDocHandler:
    """Translates Markdown documentation sections with `<<P#>>` code/link sentinel masking (`REQ-LLM-Q-002`)."""

    task_type = "translate-doc"
    default_priority = PRIORITY_P2

    def __init__(
        self,
        out_dir: pathlib.Path = DEFAULT_OUT_DIR,
        repo_root: pathlib.Path = REPO_ROOT,
    ) -> None:
        self.out_dir = pathlib.Path(out_dir)
        self.repo_root = pathlib.Path(repo_root)

    def split_chunks(self, payload: dict[str, Any]) -> list[list[dict[str, Any]]]:
        chunk_size = max(1, min(10, int(payload.get("chunk_size", 6))))
        raw_items = payload.get("items")
        if isinstance(raw_items, list) and raw_items:
            items = [dict(x) for x in raw_items]
            return [items[i : i + chunk_size] for i in range(0, len(items), chunk_size)]

        doc_target = str(payload.get("doc_path") or payload.get("target") or "").strip()
        locale = str(payload.get("locale") or "de").strip()
        if ":" in doc_target and not pathlib.Path(doc_target).exists():
            doc_part, loc_part = doc_target.rsplit(":", 1)
            if len(loc_part) <= 5:
                doc_target = doc_part
                locale = loc_part

        doc_path = pathlib.Path(doc_target)
        if not doc_path.is_absolute():
            doc_path = self.repo_root / doc_target
        if not doc_path.exists():
            return []

        raw_md = doc_path.read_text(encoding="utf-8")
        sections = split_markdown_sections(raw_md)
        out_path = str(
            payload.get("output_path")
            or (self.out_dir / f"docs-{locale}" / doc_path.name)
        )
        items = []
        for idx, sec in enumerate(sections):
            masked_sec, legend = mask_markdown_section(sec)
            items.append(
                {
                    "item_id": f"{doc_path.name}#sec{idx}",
                    "doc_path": str(doc_path),
                    "output_path": out_path,
                    "section_index": idx,
                    "total_sections": len(sections),
                    "locale": locale,
                    "sentinels": format_legend_line(legend),
                    "heading_levels": extract_heading_levels(masked_sec),
                    "markdown_english": masked_sec,
                }
            )
        return [items[i : i + chunk_size] for i in range(0, len(items), chunk_size)]

    def build_forward_messages(self, chunk: list[dict[str, Any]]) -> list[dict[str, str]]:
        system_prompt = (
            "You are the LeafRTP technical documentation translator.\n"
            "Rules:\n"
            "1. Translate each section's `markdown_english` into the target `locale` as `markdown_locale`.\n"
            "2. Preserve every `<<P0>>`, `<<P1>>`, ... sentinel token verbatim (they mask code spans, fenced blocks, and URLs).\n"
            "3. Preserve exact Markdown heading levels (`#`, `##`, `###`).\n"
            "4. Return a JSON array of objects with keys `item_id` and `markdown_locale`."
        )
        prompt_items = [
            {
                "item_id": it["item_id"],
                "locale": it["locale"],
                "sentinels": it["sentinels"],
                "markdown_english": it["markdown_english"],
            }
            for it in chunk
        ]
        return [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": json.dumps(prompt_items, ensure_ascii=False)},
        ]

    def parse_forward_response(
        self,
        chunk: list[dict[str, Any]],
        raw_response: str,
    ) -> list[dict[str, Any]]:
        parsed = extract_json_payload(raw_response)
        if isinstance(parsed, dict) and isinstance(parsed.get("items"), list):
            parsed = parsed["items"]
        if isinstance(parsed, list):
            by_id = {
                str(d.get("item_id")): dict(d)
                for d in parsed
                if isinstance(d, dict) and d.get("item_id")
            }
            out: list[dict[str, Any]] = []
            for idx, it in enumerate(chunk):
                iid = str(it.get("item_id") or "")
                if iid in by_id:
                    out.append(by_id[iid])
                elif idx < len(parsed) and isinstance(parsed[idx], dict):
                    out.append(dict(parsed[idx]))
                else:
                    out.append({})
            return out
        if isinstance(parsed, dict):
            return [dict(parsed)]
        return [{} for _ in chunk]

    def verify_deterministic(
        self,
        item: dict[str, Any],
        draft: dict[str, Any],
    ) -> tuple[bool, str]:
        md_en = str(item.get("markdown_english") or "")
        md_lo = str(draft.get("markdown_locale") or "")
        if not md_lo.strip():
            return False, "empty markdown_locale"

        sent_en = sorted(extract_sentinels(md_en))
        sent_lo = sorted(extract_sentinels(md_lo))
        if sent_en != sent_lo:
            return False, f"markdown sentinel mismatch: expected {sent_en}, got {sent_lo}"

        expected_headings = list(item.get("heading_levels") or extract_heading_levels(md_en))
        actual_headings = extract_heading_levels(md_lo)
        if expected_headings != actual_headings:
            return False, f"heading level mismatch: expected {expected_headings}, got {actual_headings}"

        if has_mojibake(md_lo):
            return False, "mojibake detected in markdown_locale"

        return True, "ok"

    def build_critic_messages(
        self,
        item: dict[str, Any],
        draft: dict[str, Any],
    ) -> list[dict[str, str]]:
        system_prompt = (
            "You are an independent Opinion 2 technical documentation critic for LeafRTP.\n"
            "Verify that `markdown_locale` faithfully translates `markdown_english` into `locale` without inverting "
            "boolean advice, corrupting RTP domain concepts (`chunk`, `region`, `ticket`, `worldborder`), or dropping "
            "technical constraints.\n"
            "Respond with `PASS` on the first line if accurate, or `DRIFT: <reason>` if meaning shifted."
        )
        return [
            {"role": "system", "content": system_prompt},
            {
                "role": "user",
                "content": json.dumps(
                    {
                        "locale": item.get("locale"),
                        "markdown_english": item.get("markdown_english"),
                        "markdown_locale": draft.get("markdown_locale"),
                    },
                    ensure_ascii=False,
                ),
            },
        ]

    def parse_critic_verdict(self, raw_response: str) -> tuple[bool, str]:
        return parse_standard_critic_verdict(raw_response)

    def build_repair_messages(
        self,
        item: dict[str, Any],
        draft: dict[str, Any],
        reason: str,
    ) -> list[dict[str, str]]:
        system_prompt = (
            "Repair the translated Markdown section to resolve the reported error while preserving all `<<P#>>` "
            "sentinels and heading levels (`#`, `##`, `###`). Return a JSON object with `item_id` and `markdown_locale`."
        )
        return [
            {"role": "system", "content": system_prompt},
            {
                "role": "user",
                "content": json.dumps(
                    {"item": item, "rejected_draft": draft, "rejection_reason": reason},
                    ensure_ascii=False,
                ),
            },
        ]

    def parse_repair_response(
        self,
        item: dict[str, Any],
        raw_response: str,
    ) -> dict[str, Any]:
        parsed = extract_json_payload(raw_response)
        if isinstance(parsed, list) and parsed and isinstance(parsed[0], dict):
            return dict(parsed[0])
        return dict(parsed) if isinstance(parsed, dict) else {}

    def apply_verified(
        self,
        verified_items: list[tuple[dict[str, Any], dict[str, Any]]],
    ) -> int:
        by_out: dict[str, list[tuple[int, str]]] = {}
        applied = 0
        for item, draft in verified_items:
            out_path = str(item.get("output_path") or (self.out_dir / "translated-doc.md"))
            sec_idx = int(item.get("section_index", 0))
            legend = parse_legend(str(item.get("sentinels") or "(none)"))
            unmasked = unmask_text(str(draft.get("markdown_locale") or ""), legend)
            by_out.setdefault(out_path, []).append((sec_idx, unmasked.strip("\n")))
            applied += 1

        for out_path, sec_list in by_out.items():
            p = pathlib.Path(out_path)
            p.parent.mkdir(parents=True, exist_ok=True)
            sec_list.sort(key=lambda t: t[0])
            existing = p.read_text(encoding="utf-8").strip("\n") if p.exists() else ""
            parts = ([existing] if existing else []) + [s for _, s in sec_list]
            body = "\n\n".join(parts) + "\n"
            p.write_bytes(body.replace("\r\n", "\n").encode("utf-8"))
        return applied

    def review_file_path(self, payload: dict[str, Any]) -> pathlib.Path:
        return self.out_dir / "review-docs.md"

    def format_quarantine_entry(
        self,
        item: dict[str, Any],
        draft: dict[str, Any],
        stage: str,
        reason: str,
    ) -> str:
        return f"- **`{item.get('item_id')}`** (`{stage}`): {reason}"


_REPO_AST_MOD: Any = None
_SHARED_AST_CONN: dict[str, sqlite3.Connection] = {}


def _repo_ast_mod() -> Any:
    global _REPO_AST_MOD
    if _REPO_AST_MOD is None:
        _REPO_AST_MOD = _load_script_module("repo_ast.py", "_repo_ast_mod")
    return _REPO_AST_MOD


def _get_shared_ast_conn(repo_root: pathlib.Path) -> sqlite3.Connection | None:
    key = str(repo_root.resolve())
    existing = _SHARED_AST_CONN.get(key)
    if existing is not None:
        return existing
    with contextlib.suppress(Exception):
        conn, _ = _repo_ast_mod().ensure_db_connection(repo_root=repo_root, auto_sync=False)
        _SHARED_AST_CONN[key] = conn
        return conn
    return None


_PROCESS_NOTE_RE = re.compile(
    r"(?:\b(?:Slice\s+[A-Z0-9]+|Phase\s+[0-9A-Za-z]+|Step\s+\d+|row\s+[A-Z]\d+)\b|"
    r"CHECKLIST-[A-Za-z0-9_-]+|[A-Z0-9_]+_PLAN\.md)"
)
_VALID_SAFETY_IDS = frozenset({f"S-00{i}" for i in range(1, 8)})


def has_process_note_violation(text: str) -> tuple[bool, str]:
    """Detects forbidden development process shorthand (`Slice`, `Phase`, `Step`, `CHECKLIST-*`, `*_PLAN.md`)."""
    if not text:
        return False, ""
    m = _PROCESS_NOTE_RE.search(text)
    if m:
        return True, m.group(0)
    return False, ""


def _collect_known_governance_ids(conn: sqlite3.Connection | None) -> tuple[set[str], set[str]]:
    """Returns `(known_adrs, known_reqs)` from `doc-index.sqlite` when available."""
    known_adrs: set[str] = set()
    known_reqs: set[str] = set()
    if conn is None:
        return known_adrs, known_reqs
    with contextlib.suppress(sqlite3.Error):
        for r in conn.execute("SELECT path, title FROM dev_sections WHERE path LIKE '%ADR-%'").fetchall():
            for m in re.findall(r"\b(?:[a-z-]+-)?ADR-\d{3}\b", f"{r[0]} {r[1]}", flags=re.IGNORECASE):
                known_adrs.add(m.upper() if m.upper().startswith("ADR-") else m)
        for r in conn.execute("SELECT req_id FROM traceability_rows").fetchall():
            if r[0]:
                known_reqs.add(str(r[0]).strip().upper())
        for r in conn.execute("SELECT governed_by_json FROM code_class_meta").fetchall():
            for g in json.loads(str(r[0] or "[]")):
                g_str = str(g).strip()
                if g_str.upper().startswith("REQ-"):
                    known_reqs.add(g_str.upper())
                elif "ADR-" in g_str.upper():
                    known_adrs.add(g_str.upper() if g_str.upper().startswith("ADR-") else g_str)
    return known_adrs, known_reqs


def parse_git_diff_hunks(diff_text: str) -> list[dict[str, Any]]:
    """Splits a unified `git diff` into per-file / per-class structured items."""
    if not diff_text or not diff_text.strip():
        return []
    file_chunks = re.split(r"(?m)^diff --git ", diff_text.replace("\r\n", "\n"))
    items: list[dict[str, Any]] = []
    for raw_fc in file_chunks:
        fc = raw_fc.strip()
        if not fc:
            continue
        full_block = "diff --git " + fc if not fc.startswith("diff --git ") else fc
        path_match = re.search(r"(?m)^\+\+\+\s+b/([^\r\n]+)", full_block)
        if not path_match:
            path_match = re.search(r"a/(\S+)\s+b/(\S+)", full_block)
            relpath = path_match.group(2) if path_match else "unknown"
        else:
            relpath = path_match.group(1).strip()

        cls_name = pathlib.Path(relpath).stem if relpath.endswith(".java") else pathlib.Path(relpath).name
        added_lines: list[str] = []
        for ln in full_block.splitlines():
            if ln.startswith("+") and not ln.startswith("+++"):
                added_lines.append(ln[1:])
        items.append(
            {
                "item_id": f"diff:{cls_name}",
                "class_name": cls_name,
                "path": relpath,
                "diff_hunk": full_block,
                "added_lines": added_lines,
            }
        )
    return items


class CritiqueDiffHandler:
    """P1 Cold-Context `Gov(c)` Diff Invariant Auditor (`ADR-115`, `S-001`..`S-007`)."""

    task_type = "critique-diff"
    default_priority = PRIORITY_P1

    def __init__(
        self,
        out_dir: pathlib.Path = DEFAULT_OUT_DIR,
        repo_root: pathlib.Path = REPO_ROOT,
        ast_conn: sqlite3.Connection | None = None,
    ) -> None:
        self.out_dir = pathlib.Path(out_dir)
        self.repo_root = pathlib.Path(repo_root)
        self._ast_conn = ast_conn

    def _get_ast_conn(self) -> sqlite3.Connection | None:
        if self._ast_conn is not None:
            return self._ast_conn
        self._ast_conn = _get_shared_ast_conn(self.repo_root)
        return self._ast_conn

    def _enrich_item_with_gov_c(self, item: dict[str, Any]) -> dict[str, Any]:
        enriched = dict(item)
        if enriched.get("gov_c") and enriched.get("known_adrs") is not None:
            return enriched
        conn = self._get_ast_conn()
        known_adrs, known_reqs = _collect_known_governance_ids(conn)
        gov_c: dict[str, Any] = {
            "adrs": sorted(list(known_adrs))[:8],
            "reqs": sorted(list(known_reqs))[:8],
            "safety_prohibitions": sorted(_VALID_SAFETY_IDS),
        }
        cls_name = str(enriched.get("class_name") or "")
        if conn is not None and cls_name:
            with contextlib.suppress(Exception):
                ctx = _repo_ast_mod().query_context(conn, cls_name)
                if ctx.get("found"):
                    gov_c = ctx.get("gov_c") or gov_c
                    enriched["primary_class"] = ctx.get("primary_class")
                    enriched["dependencies"] = ctx.get("dependencies")
        enriched["gov_c"] = gov_c
        enriched["valid_adrs"] = sorted(
            set(gov_c.get("adrs") or []) | {a.upper() for a in known_adrs}
        )
        enriched["valid_reqs"] = sorted(
            set(gov_c.get("reqs") or []) | {r.upper() for r in known_reqs}
        )
        return enriched

    def split_chunks(self, payload: dict[str, Any]) -> list[list[dict[str, Any]]]:
        chunk_size = max(1, min(5, int(payload.get("chunk_size", 4))))
        raw_items = payload.get("items")
        if isinstance(raw_items, list) and raw_items:
            items = [self._enrich_item_with_gov_c(dict(x)) for x in raw_items]
            return [items[i : i + chunk_size] for i in range(0, len(items), chunk_size)]

        diff_text = str(payload.get("diff_text") or "")
        if not diff_text.strip():
            target = str(payload.get("target") or "HEAD").strip()
            cmd = ["git", "diff", target] if target and target != "WORKTREE" else ["git", "diff"]
            with contextlib.suppress(OSError, subprocess.SubprocessError):
                proc = subprocess.run(
                    cmd,
                    cwd=str(self.repo_root),
                    capture_output=True,
                    text=True,
                    timeout=10,
                    check=False,
                )
                diff_text = proc.stdout or ""

        parsed_hunks = parse_git_diff_hunks(diff_text)
        items = [self._enrich_item_with_gov_c(h) for h in parsed_hunks]
        return [items[i : i + chunk_size] for i in range(0, len(items), chunk_size)]

    def build_forward_messages(self, chunk: list[dict[str, Any]]) -> list[dict[str, str]]:
        system_prompt = (
            "You are an independent cold-context adversarial code auditor for LeafRTP (`ADR-115`).\n"
            "Given each modified class's `diff_hunk` and its `gov_c` neighborhood (`ADR-*`, `REQ-*`, and safety rules "
            "`S-001`..`S-007`), identify ONLY concrete invariant violations evidenced by exact lines in the diff:\n"
            "- `S-001`: unsafe destination block check bypass\n"
            "- `S-002`: leaked or double-closed `MemoryTracker` chunk ticket\n"
            "- `S-003`: inline claim-plugin call in pipeline/commands\n"
            "- `S-004`: silently swallowed teleport failure / empty catch\n"
            "- `S-005`: synchronous chunk load (`getChunkAt`, blocking `.get()`) on main thread\n"
            "- `S-006`: silent null-guard return instead of `IllegalStateException` pre-init\n"
            "- `S-007`: hardcoded command failure message string\n"
            "Return a JSON array of objects with keys:\n"
            "  `item_id`, `class_name`, `findings` (list of objects with `rule_id` (`S-00x`/`ADR-NNN`/`REQ-*`), "
            "`cited_line` (exact substring from `diff_hunk`), `severity` (`HIGH`|`MEDIUM`), and `summary`).\n"
            "If a diff hunk has no violations, return `\"findings\": []`."
        )
        prompt_items = [
            {
                "item_id": it["item_id"],
                "class_name": it.get("class_name"),
                "path": it.get("path"),
                "gov_c": it.get("gov_c"),
                "diff_hunk": it.get("diff_hunk"),
            }
            for it in chunk
        ]
        return [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": json.dumps(prompt_items, ensure_ascii=False)},
        ]

    def parse_forward_response(
        self,
        chunk: list[dict[str, Any]],
        raw_response: str,
    ) -> list[dict[str, Any]]:
        parsed = extract_json_payload(raw_response)
        if isinstance(parsed, dict) and isinstance(parsed.get("items"), list):
            parsed = parsed["items"]
        if isinstance(parsed, list):
            by_id = {
                str(d.get("item_id")): dict(d)
                for d in parsed
                if isinstance(d, dict) and d.get("item_id")
            }
            out: list[dict[str, Any]] = []
            for idx, it in enumerate(chunk):
                iid = str(it.get("item_id") or "")
                if iid in by_id:
                    out.append(by_id[iid])
                elif idx < len(parsed) and isinstance(parsed[idx], dict):
                    out.append(dict(parsed[idx]))
                else:
                    out.append({"item_id": iid, "findings": []})
            return out
        if isinstance(parsed, dict):
            return [dict(parsed)]
        return [{"item_id": it.get("item_id", ""), "findings": []} for it in chunk]

    def verify_deterministic(
        self,
        item: dict[str, Any],
        draft: dict[str, Any],
    ) -> tuple[bool, str]:
        findings = draft.get("findings")
        if findings is None or not isinstance(findings, list):
            return False, "missing or non-list findings field"

        diff_hunk = str(item.get("diff_hunk") or "")
        valid_adrs = {str(a).upper() for a in (item.get("valid_adrs") or [])}
        valid_reqs = {str(r).upper() for r in (item.get("valid_reqs") or [])}
        for gov_adr in (item.get("gov_c") or {}).get("adrs") or []:
            valid_adrs.add(str(gov_adr).upper())
        for gov_req in (item.get("gov_c") or {}).get("reqs") or []:
            valid_reqs.add(str(gov_req).upper())

        for idx, f in enumerate(findings):
            if not isinstance(f, dict):
                return False, f"finding[{idx}] is not an object"
            rule_id = str(f.get("rule_id") or "").strip().upper()
            cited_line = str(f.get("cited_line") or "").strip()
            summary = str(f.get("summary") or "").strip()
            if not rule_id or not summary:
                return False, f"finding[{idx}] missing rule_id or summary"
            if has_mojibake(summary):
                return False, f"finding[{idx}] contains mojibake"

            if rule_id in _VALID_SAFETY_IDS:
                pass
            elif rule_id.startswith("ADR-") or "-ADR-" in rule_id:
                if valid_adrs and rule_id not in valid_adrs:
                    return False, f"hallucinated or unindexed ADR citation: {rule_id}"
            elif rule_id.startswith("REQ-"):
                if valid_reqs and rule_id not in valid_reqs:
                    return False, f"hallucinated or unindexed REQ citation: {rule_id}"
            else:
                return False, f"unknown governance rule_id format: {rule_id}"

            if not cited_line or cited_line not in diff_hunk:
                return False, f"finding[{idx}] cited_line {cited_line!r} not present in diff_hunk"

        return True, "ok"

    def build_critic_messages(
        self,
        item: dict[str, Any],
        draft: dict[str, Any],
    ) -> list[dict[str, str]]:
        system_prompt = (
            "You are the Opinion 2 False-Positive Critic for LeafRTP diff audits.\n"
            "Challenge each reported finding in `draft.findings` against the exact `diff_hunk` and `gov_c` rules:\n"
            "- Reject speculative findings that are not actually caused by the diff lines.\n"
            "- Reject findings that misread async callbacks or properly closed `MemoryTracker` resources.\n"
            "Respond with `PASS` on the first line if all reported findings are genuine, or `DRIFT: <reason>` "
            "if any finding is a false positive or inaccurate."
        )
        return [
            {"role": "system", "content": system_prompt},
            {
                "role": "user",
                "content": json.dumps(
                    {
                        "class_name": item.get("class_name"),
                        "gov_c": item.get("gov_c"),
                        "diff_hunk": item.get("diff_hunk"),
                        "draft": draft,
                    },
                    ensure_ascii=False,
                ),
            },
        ]

    def parse_critic_verdict(self, raw_response: str) -> tuple[bool, str]:
        return parse_standard_critic_verdict(raw_response)

    def build_repair_messages(
        self,
        item: dict[str, Any],
        draft: dict[str, Any],
        reason: str,
    ) -> list[dict[str, str]]:
        system_prompt = (
            "Filter and repair the diff audit findings to remove hallucinated ADR/REQ IDs, unverified diff lines, "
            "or false positives flagged by the critic. Return a JSON object with `item_id`, `class_name`, and `findings`."
        )
        return [
            {"role": "system", "content": system_prompt},
            {
                "role": "user",
                "content": json.dumps(
                    {"item": item, "rejected_draft": draft, "rejection_reason": reason},
                    ensure_ascii=False,
                ),
            },
        ]

    def parse_repair_response(
        self,
        item: dict[str, Any],
        raw_response: str,
    ) -> dict[str, Any]:
        parsed = extract_json_payload(raw_response)
        if isinstance(parsed, list) and parsed and isinstance(parsed[0], dict):
            return dict(parsed[0])
        return dict(parsed) if isinstance(parsed, dict) else {"item_id": item.get("item_id", ""), "findings": []}

    def apply_verified(
        self,
        verified_items: list[tuple[dict[str, Any], dict[str, Any]]],
    ) -> int:
        lines: list[str] = ["### Gov(c) Diff Invariant Critique Digest"]
        finding_count = 0
        for item, draft in verified_items:
            cls_name = str(item.get("class_name") or item.get("item_id") or "")
            path = str(item.get("path") or "")
            findings = draft.get("findings") or []
            if not findings:
                lines.append(f"- **`{cls_name}`** (`{path}`): `0` invariant violations.")
                continue
            for f in findings:
                finding_count += 1
                rule_id = str(f.get("rule_id") or "")
                sev = str(f.get("severity") or "HIGH")
                summary = str(f.get("summary") or "")
                cited = str(f.get("cited_line") or "")
                lines.append(
                    f"- **[{sev}] `{rule_id}` in `{cls_name}`** (`{path}`): {summary} (line: `{cited}`)"
                )

        digest_path = self.out_dir / "critique-diff-latest.md"
        digest_path.parent.mkdir(parents=True, exist_ok=True)
        body = "\n".join(lines[:30]) + "\n"
        digest_path.write_bytes(body.replace("\r\n", "\n").encode("utf-8"))
        return len(verified_items)

    def review_file_path(self, payload: dict[str, Any]) -> pathlib.Path:
        return self.out_dir / "review-critique-diff.md"

    def format_quarantine_entry(
        self,
        item: dict[str, Any],
        draft: dict[str, Any],
        stage: str,
        reason: str,
    ) -> str:
        return f"- **`{item.get('class_name', item.get('item_id'))}`** (`{stage}`): {reason}"


class BackfillJavadocHandler:
    """P2 `Rule D-010` / `ADR-115` `@governedBy` & `@trace` Telegraphic Javadoc Backfill Handler."""

    task_type = "backfill-javadoc"
    default_priority = PRIORITY_P2

    def __init__(
        self,
        out_dir: pathlib.Path = DEFAULT_OUT_DIR,
        repo_root: pathlib.Path = REPO_ROOT,
        ast_conn: sqlite3.Connection | None = None,
    ) -> None:
        self.out_dir = pathlib.Path(out_dir)
        self.repo_root = pathlib.Path(repo_root)
        self._ast_conn = ast_conn

    def _get_ast_conn(self) -> sqlite3.Connection | None:
        if self._ast_conn is not None:
            return self._ast_conn
        self._ast_conn = _get_shared_ast_conn(self.repo_root)
        return self._ast_conn

    def _enrich_class_item(self, item: dict[str, Any]) -> dict[str, Any]:
        enriched = dict(item)
        conn = self._get_ast_conn()
        known_adrs, known_reqs = _collect_known_governance_ids(conn)
        cls_name = str(enriched.get("class_name") or "")
        if conn is not None and cls_name and not enriched.get("methods"):
            with contextlib.suppress(Exception):
                ctx = _repo_ast_mod().query_context(conn, cls_name)
                if ctx.get("found"):
                    pc = ctx.get("primary_class") or {}
                    enriched.setdefault("path", pc.get("path", ""))
                    enriched.setdefault("module_name", pc.get("module_name", ""))
                    enriched.setdefault("dag_tier", pc.get("dag_tier", "TIER_1_SPI_PURE"))
                    enriched.setdefault("methods", pc.get("methods", ""))
                    enriched.setdefault("gov_c", ctx.get("gov_c") or {})
        gov_c = enriched.get("gov_c") or {}
        valid_adrs = set( enriched.get("valid_adrs") or [] ) | set(gov_c.get("adrs") or []) | {a.upper() for a in known_adrs}
        valid_reqs = set( enriched.get("valid_reqs") or [] ) | set(gov_c.get("reqs") or []) | {r.upper() for r in known_reqs}
        enriched["valid_adrs"] = sorted(valid_adrs)
        enriched["valid_reqs"] = sorted(valid_reqs)
        enriched.setdefault("item_id", f"javadoc:{cls_name}")
        return enriched

    def split_chunks(self, payload: dict[str, Any]) -> list[list[dict[str, Any]]]:
        chunk_size = max(1, min(10, int(payload.get("chunk_size", 6))))
        raw_items = payload.get("items")
        if isinstance(raw_items, list) and raw_items:
            items = [self._enrich_class_item(dict(x)) for x in raw_items]
            return [items[i : i + chunk_size] for i in range(0, len(items), chunk_size)]

        conn = self._get_ast_conn()
        if conn is None:
            return []
        limit = max(1, int(payload.get("limit", 12)))
        target_filter = str(payload.get("target") or "all").strip()
        audit = _repo_ast_mod().audit_corpus_searchability(conn, limit=limit)
        candidates = list(audit.get("spi_missing_javadoc_or_gov") or []) + list(
            audit.get("high_fan_in_missing_gov") or []
        )
        if target_filter and target_filter not in ("all", "*"):
            candidates = [
                c
                for c in candidates
                if target_filter.lower() in str(c.get("class_name", "")).lower()
                or target_filter.lower() in str(c.get("module_name", "")).lower()
            ]
        items = [self._enrich_class_item(c) for c in candidates[:limit]]
        return [items[i : i + chunk_size] for i in range(0, len(items), chunk_size)]

    def build_forward_messages(self, chunk: list[dict[str, Any]]) -> list[dict[str, str]]:
        system_prompt = (
            "You are the LeafRTP `Rule D-010` (`ADR-115`) Javadoc governance generator.\n"
            "Rules:\n"
            "1. Draft a concise, telegraphic class-level Javadoc (`/** ... */`) of `<= 8` lines for each class.\n"
            "2. Include `@governedBy <ADR-NNN>` and/or `@trace <REQ-*>` tags using ONLY verified IDs from `gov_c`.\n"
            "3. Never include development process shorthand (`Slice`, `Phase`, `Step`, `CHECKLIST-*`, `*_PLAN.md`).\n"
            "4. Return a JSON array of objects with keys `item_id`, `class_name`, and `javadoc`."
        )
        return [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": json.dumps(chunk, ensure_ascii=False)},
        ]

    def parse_forward_response(
        self,
        chunk: list[dict[str, Any]],
        raw_response: str,
    ) -> list[dict[str, Any]]:
        parsed = extract_json_payload(raw_response)
        if isinstance(parsed, dict) and isinstance(parsed.get("items"), list):
            parsed = parsed["items"]
        if isinstance(parsed, list):
            by_id = {
                str(d.get("item_id") or f"javadoc:{d.get('class_name', '')}"): dict(d)
                for d in parsed
                if isinstance(d, dict)
            }
            out: list[dict[str, Any]] = []
            for idx, it in enumerate(chunk):
                iid = str(it.get("item_id") or f"javadoc:{it.get('class_name', '')}")
                if iid in by_id:
                    out.append(by_id[iid])
                elif idx < len(parsed) and isinstance(parsed[idx], dict):
                    out.append(dict(parsed[idx]))
                else:
                    out.append({})
            return out
        if isinstance(parsed, dict):
            return [dict(parsed)]
        return [{} for _ in chunk]

    def verify_deterministic(
        self,
        item: dict[str, Any],
        draft: dict[str, Any],
    ) -> tuple[bool, str]:
        javadoc = str(draft.get("javadoc") or "").strip()
        if not javadoc:
            return False, "empty javadoc draft"
        if not javadoc.startswith("/**") or not javadoc.endswith("*/"):
            return False, "javadoc must start with /** and end with */"

        lines = [ln.rstrip() for ln in javadoc.splitlines()]
        if len(lines) > 8:
            return False, f"javadoc exceeds <= 8 lines telegraphic limit ({len(lines)} lines)"

        has_proc, bad_tok = has_process_note_violation(javadoc)
        if has_proc:
            return False, f"forbidden process-note shorthand in javadoc: {bad_tok}"

        if has_mojibake(javadoc):
            return False, "mojibake detected in javadoc"

        if "@governedBy" not in javadoc and "@trace" not in javadoc:
            return False, "missing required @governedBy or @trace governance tag"

        valid_adrs = {str(a).upper() for a in (item.get("valid_adrs") or [])}
        valid_reqs = {str(r).upper() for r in (item.get("valid_reqs") or [])}

        cited_adrs = [m.upper() for m in re.findall(r"\b(?:[a-z-]+-)?ADR-\d{3}\b", javadoc, flags=re.IGNORECASE)]
        cited_reqs = [m.upper() for m in re.findall(r"\bREQ-[A-Z0-9-]+\b", javadoc)]
        if not cited_adrs and not cited_reqs:
            return False, "@governedBy / @trace must cite at least one concrete ADR-NNN or REQ-* ID"

        for adr in cited_adrs:
            if valid_adrs and adr not in valid_adrs:
                return False, f"hallucinated or unindexed ADR citation: {adr}"
        for req in cited_reqs:
            if valid_reqs and req not in valid_reqs:
                return False, f"hallucinated or unindexed REQ citation: {req}"

        return True, "ok"

    def build_critic_messages(
        self,
        item: dict[str, Any],
        draft: dict[str, Any],
    ) -> list[dict[str, str]]:
        system_prompt = (
            "You are the Opinion 2 AST Accuracy Critic for LeafRTP `Rule D-010` Javadocs.\n"
            "Compare the drafted `javadoc` against the class's AST `methods`, `dag_tier`, and `gov_c` neighborhood.\n"
            "Verify that the summary accurately describes the class's capability without inventing non-existent "
            "methods or behavior.\n"
            "Respond with `PASS` on the first line if accurate, or `DRIFT: <reason>` if inaccurate."
        )
        return [
            {"role": "system", "content": system_prompt},
            {
                "role": "user",
                "content": json.dumps(
                    {
                        "class_name": item.get("class_name"),
                        "dag_tier": item.get("dag_tier"),
                        "methods": item.get("methods"),
                        "gov_c": item.get("gov_c"),
                        "javadoc": draft.get("javadoc"),
                    },
                    ensure_ascii=False,
                ),
            },
        ]

    def parse_critic_verdict(self, raw_response: str) -> tuple[bool, str]:
        return parse_standard_critic_verdict(raw_response)

    def build_repair_messages(
        self,
        item: dict[str, Any],
        draft: dict[str, Any],
        reason: str,
    ) -> list[dict[str, str]]:
        system_prompt = (
            "Repair the rejected `Rule D-010` Javadoc block so it satisfies the `<= 8` line telegraphic limit, "
            "contains zero process notes (`Slice`, `Phase`, `Step`, `CHECKLIST-*`, `*_PLAN.md`), and cites only "
            "valid `ADR-NNN` / `REQ-*` IDs from `valid_adrs` / `valid_reqs`. Return a JSON object with "
            "`item_id`, `class_name`, and `javadoc`."
        )
        return [
            {"role": "system", "content": system_prompt},
            {
                "role": "user",
                "content": json.dumps(
                    {"item": item, "rejected_draft": draft, "rejection_reason": reason},
                    ensure_ascii=False,
                ),
            },
        ]

    def parse_repair_response(
        self,
        item: dict[str, Any],
        raw_response: str,
    ) -> dict[str, Any]:
        parsed = extract_json_payload(raw_response)
        if isinstance(parsed, list) and parsed and isinstance(parsed[0], dict):
            return dict(parsed[0])
        return dict(parsed) if isinstance(parsed, dict) else {}

    def apply_verified(
        self,
        verified_items: list[tuple[dict[str, Any], dict[str, Any]]],
    ) -> int:
        if not verified_items:
            return 0
        out_file = self.out_dir / "javadoc-drafts.md"
        out_file.parent.mkdir(parents=True, exist_ok=True)
        existing = out_file.read_text(encoding="utf-8") if out_file.exists() else ""
        lines: list[str] = []
        if not existing.strip():
            lines.append("### Verified Rule D-010 Javadoc Backfill Drafts")
            lines.append("")
        for item, draft in verified_items:
            cls_name = str(item.get("class_name") or "")
            path = str(item.get("path") or "")
            jdoc = str(draft.get("javadoc") or "").strip()
            lines.append(f"#### `{cls_name}` (`{path}`)")
            lines.append("```java")
            lines.append(jdoc)
            lines.append("```")
            lines.append("")
        body = (existing.rstrip("\n") + "\n\n" if existing.strip() else "") + "\n".join(lines).rstrip() + "\n"
        out_file.write_bytes(body.replace("\r\n", "\n").encode("utf-8"))
        return len(verified_items)

    def review_file_path(self, payload: dict[str, Any]) -> pathlib.Path:
        return self.out_dir / "review-backfill-javadoc.md"

    def format_quarantine_entry(
        self,
        item: dict[str, Any],
        draft: dict[str, Any],
        stage: str,
        reason: str,
    ) -> str:
        return f"- **`{item.get('class_name', item.get('item_id'))}`** (`{stage}`): {reason}"


def build_default_handlers(
    out_dir: pathlib.Path = DEFAULT_OUT_DIR,
    repo_root: pathlib.Path = REPO_ROOT,
    ast_conn: sqlite3.Connection | None = None,
) -> dict[str, OffloadTaskHandler]:
    """Returns registered task handlers keyed by `task_type`."""
    handlers: dict[str, OffloadTaskHandler] = {
        "translate-bundle": TranslateBundleHandler(out_dir=out_dir),
        "translate-doc": TranslateDocHandler(out_dir=out_dir, repo_root=repo_root),
        "critique-diff": CritiqueDiffHandler(out_dir=out_dir, repo_root=repo_root, ast_conn=ast_conn),
        "backfill-javadoc": BackfillJavadocHandler(out_dir=out_dir, repo_root=repo_root, ast_conn=ast_conn),
    }
    return handlers


def run_worker_step(
    broker: LlmQueueBroker,
    runner: StagedFunnelRunner,
    handlers: dict[str, OffloadTaskHandler],
    max_chunks_per_claim: int | None = None,
    now_fn: Callable[[], int] = now_epoch_ms,
    job_id: int | None = None,
) -> dict[str, Any] | None:
    """Claims and executes the next eligible job (or yields if a P0 lease is active)."""
    claimed = broker.claim_next_job(now_ms=now_fn(), job_id=job_id)
    if claimed is None:
        return None
    task_type = str(claimed["task_type"])
    handler = handlers.get(task_type)
    if handler is None:
        broker.complete_job(
            job_id=int(claimed["job_id"]),
            status="FAILED",
            total_items=int(claimed.get("total_items", 1)),
            completed_items=0,
            retried_items=0,
            quarantined_items=0,
            result_summary_md=f"- Unknown task_type `{task_type}`",
            now_ms=now_fn(),
        )
        return {"job_id": int(claimed["job_id"]), "status": "FAILED", "error": f"Unknown task_type: {task_type}"}
    return runner.run_job(claimed, handler, max_chunks=max_chunks_per_claim, now_fn=now_fn)


def execute_offload_action(
    action: str,
    task_type: str = "",
    target: str = "",
    priority: int | None = None,
    db_path: pathlib.Path | str = DEFAULT_QUEUE_DB_PATH,
    out_dir: pathlib.Path = DEFAULT_OUT_DIR,
    repo_root: pathlib.Path = REPO_ROOT,
    ast_conn: sqlite3.Connection | None = None,
    endpoint: str = DEFAULT_LLM_ENDPOINT,
    http_post: Callable[[str, dict[str, Any], float], dict[str, Any]] | None = None,
    payload_extra: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Unified entry point for `repo_ast_offload` MCP tool and `repo_ast.py offload` CLI."""
    act = action.strip().lower()
    broker = LlmQueueBroker(db_path)
    out_path = pathlib.Path(out_dir)

    if act == "status":
        jid = int(target) if target and target.strip().isdigit() else None
        digest = broker.get_status_digest(job_id=jid, max_lines=40)
        digest["action"] = "status"
        return digest

    if act == "enqueue":
        resolved_task = (task_type or "translate-bundle").strip()
        if resolved_task not in TASK_DEFAULT_PRIORITY:
            raise ValueError(f"Unsupported task_type: {resolved_task}")
        resolved_target = (target or "all").strip()
        payload = dict(payload_extra or {})
        payload.setdefault("target", resolved_target)
        job_id = broker.enqueue(
            task_type=resolved_task,
            target_key=resolved_target,
            payload=payload,
            priority=priority,
        )
        digest = broker.get_status_digest(job_id=job_id, max_lines=40)
        digest["action"] = "enqueue"
        digest["enqueued_job_id"] = job_id
        return digest

    client = LocalLlmClient(endpoint=endpoint, http_post=http_post)
    runner = StagedFunnelRunner(client=client, broker=broker, out_dir=out_path)
    handlers = build_default_handlers(out_dir=out_path, repo_root=repo_root, ast_conn=ast_conn)

    if act == "critique_diff":
        resolved_target = (target or "HEAD").strip()
        payload = dict(payload_extra or {})
        payload.setdefault("target", resolved_target)
        eff_prio = priority if priority is not None else PRIORITY_P1
        job_id = broker.enqueue(
            task_type="critique-diff",
            target_key=resolved_target,
            payload=payload,
            priority=eff_prio,
        )
        step_res = run_worker_step(
            broker=broker,
            runner=runner,
            handlers=handlers,
            job_id=job_id,
        )
        digest = broker.get_status_digest(job_id=job_id, max_lines=30)
        digest["action"] = "critique_diff"
        digest["job_id"] = job_id
        digest["step_result"] = step_res
        if step_res and step_res.get("status") == "OFFLINE":
            digest["markdown"] = (
                "### Local LLM Priority Queue Digest\n"
                f"- Local LLM endpoint offline; diff critique job `#{job_id}` queued as `PENDING`."
            )
            return digest
        critique_md_path = out_path / "critique-diff-latest.md"
        if critique_md_path.exists():
            crit_lines = critique_md_path.read_text(encoding="utf-8").strip().splitlines()
            combined = digest["markdown"].splitlines() + [""] + crit_lines
            digest["markdown"] = "\n".join(combined[:40])
        return digest

    if act == "run_once":
        jid = int(target) if target and target.strip().isdigit() else None
        step_res = run_worker_step(
            broker=broker,
            runner=runner,
            handlers=handlers,
            job_id=jid,
        )
        digest = broker.get_status_digest(
            job_id=int(step_res["job_id"]) if step_res and "job_id" in step_res else jid,
            max_lines=40,
        )
        digest["action"] = "run_once"
        digest["step_result"] = step_res
        return digest

    raise ValueError(f"Unsupported offload action: {action}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Priority-queued local LLM dispatch and multi-opinion verifier.")
    parser.add_argument("--db-path", default=str(DEFAULT_QUEUE_DB_PATH), help="Path to llm-queue.sqlite")
    sub = parser.add_subparsers(dest="subcommand", required=True)

    p_enq = sub.add_parser("enqueue", help="Enqueue a local GPU offload job")
    p_enq.add_argument("--task-type", required=True, choices=sorted(TASK_DEFAULT_PRIORITY.keys()))
    p_enq.add_argument("--target", required=True, help="Locale code, doc path, diff target, or class filter")
    p_enq.add_argument("--priority", type=int, choices=[0, 1, 2], default=None)
    p_enq.add_argument("--payload-json", default="{}")
    p_enq.add_argument("--forward-model", default=DEFAULT_FORWARD_MODEL)
    p_enq.add_argument("--critic-model", default=DEFAULT_CRITIC_MODEL)

    p_stat = sub.add_parser("status", help="Show compact queue status digest")
    p_stat.add_argument("--job-id", type=int, default=None)
    p_stat.add_argument("--format", choices=["markdown", "json"], default="markdown")

    p_can = sub.add_parser("cancel", help="Cancel a PENDING or RUNNING job")
    p_can.add_argument("--job-id", type=int, required=True)

    p_run = sub.add_parser("run-worker", help="Run the staged funnel queue worker")
    p_run.add_argument("--once", action="store_true", help="Process at most one job claim and exit")
    p_run.add_argument("--max-chunks", type=int, default=None, help="Max chunks per claim before re-checking queue")
    p_run.add_argument("--endpoint", default=DEFAULT_LLM_ENDPOINT)
    p_run.add_argument("--forward-model", default=DEFAULT_FORWARD_MODEL)
    p_run.add_argument("--critic-model", default=DEFAULT_CRITIC_MODEL)

    args = parser.parse_args(argv)
    broker = LlmQueueBroker(args.db_path)

    if args.subcommand == "enqueue":
        payload = json.loads(args.payload_json)
        if not isinstance(payload, dict):
            raise SystemExit("--payload-json must decode to a JSON object")
        payload.setdefault("target", args.target)
        job_id = broker.enqueue(
            task_type=args.task_type,
            target_key=args.target,
            payload=payload,
            priority=args.priority,
            model_forward=args.forward_model,
            model_critic=args.critic_model,
        )
        print(f"Enqueued job #{job_id} ({args.task_type} -> {args.target})")
        return 0

    if args.subcommand == "status":
        digest = broker.get_status_digest(job_id=args.job_id)
        if args.format == "json":
            print(json.dumps(digest, indent=2, ensure_ascii=False))
        else:
            print(digest["markdown"])
        return 0

    if args.subcommand == "cancel":
        ok = broker.cancel_job(args.job_id)
        print(f"Canceled job #{args.job_id}: {ok}")
        return 0 if ok else 1

    if args.subcommand == "run-worker":
        client = LocalLlmClient(
            endpoint=args.endpoint,
            forward_model=args.forward_model,
            critic_model=args.critic_model,
        )
        runner = StagedFunnelRunner(client=client, broker=broker)
        handlers = build_default_handlers()
        while True:
            step_res = run_worker_step(
                broker=broker,
                runner=runner,
                handlers=handlers,
                max_chunks_per_claim=args.max_chunks,
            )
            if args.once or step_res is None or step_res.get("status") == "OFFLINE":
                if step_res is not None:
                    print(json.dumps(step_res, ensure_ascii=False))
                break
        return 0

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
