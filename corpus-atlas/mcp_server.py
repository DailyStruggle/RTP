#!/usr/bin/env python3
"""
Project-Neutral Model Context Protocol (`stdio` JSON-RPC 2.0) Server for `corpus-atlas`.

Exposes the repository's split-plane SQLite FTS5 + solidified AST hypergraph (`doc-index.sqlite`)
as persistent, low-latency MCP tools (`repo_ast_context`, `repo_ast_search`, `repo_ast_impact`,
`repo_ast_audit`, `repo_ast_sync`, `repo_ast_offload`) for AI coding agents and read-only `EXPLORE` subagents.

Design invariants:
  - Zero external Python dependencies (Python 3.12+ stdlib + `sqlite3` only).
  - Persistent in-memory `sqlite3.Connection` and pre-warmed FTS/IDF caches (`~1-5 ms` warm latency
    vs `~140-820 ms` cold CLI subprocess invocations).
  - Automatic `file_manifest` incremental dirty-file sync (`0` stale reads after working-tree edits).
  - Compact tool schema (`6` tools, `< 650` tokens total) to avoid prompt-declaration bloat.
  - Strictly preserves `REQ-DISC-003` public/developer split-plane isolation.
"""
from __future__ import annotations

import argparse
import io
import json
import pathlib
import sqlite3
import sys
import time
from typing import Any, BinaryIO

ATLAS_DIR = pathlib.Path(__file__).resolve().parent
REPO_ROOT = ATLAS_DIR.parent
SCRIPTS_DIR = REPO_ROOT / "scripts"
BOT_DIR = REPO_ROOT / "discord-support-bot"

for _p in (ATLAS_DIR, SCRIPTS_DIR, BOT_DIR):
    if _p.is_dir() and str(_p) not in sys.path:
        sys.path.insert(0, str(_p))

import repo_ast  # noqa: E402
from build_doc_index import DEFAULT_DB_PATH, DEFAULT_PROJECT, sync_dirty_files  # noqa: E402

MCP_SERVER_NAME = "corpus-atlas"
MCP_SERVER_VERSION = "1.0.0"
DEFAULT_PROTOCOL_VERSION = "2024-11-05"

MCP_TOOLS_SCHEMA: list[dict[str, Any]] = [
    {
        "name": "repo_ast_context",
        "description": (
            "Resolve ADR-115 Gov(c) neighborhood (path, DAG tier, superclass/SPIs, 1-hop callers, "
            "2-hop co-coupled classes, config keys, BIT codes, ADR-*/REQ-*/S-00x rules, Gradle test cmd)."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "target": {
                    "type": "string",
                    "description": "Class, SPI, file path, REQ-*, ADR-*, S-00x, or config key.",
                },
                "format": {
                    "type": "string",
                    "enum": ["markdown", "json"],
                    "default": "markdown",
                },
            },
            "required": ["target"],
        },
    },
    {
        "name": "repo_ast_search",
        "description": (
            "Hybrid FTS5 + IDF + AST capability search with Tier 1/2 SPI anti-redundancy ranking. "
            "Call before creating new helpers or interfaces."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "Capability, domain concept, or symbol query.",
                },
                "limit": {
                    "type": "integer",
                    "default": 8,
                    "minimum": 1,
                    "maximum": 25,
                },
                "format": {
                    "type": "string",
                    "enum": ["markdown", "json"],
                    "default": "markdown",
                },
            },
            "required": ["query"],
        },
    },
    {
        "name": "repo_ast_impact",
        "description": (
            "Compute 1-hop/2-hop dependents, affected Gradle modules, TRACEABILITY.md REQ-* rows, "
            "and Gradle verification test commands for a class, SPI, or file."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "target": {
                    "type": "string",
                    "description": "Class, SPI interface, or file path.",
                },
                "format": {
                    "type": "string",
                    "enum": ["markdown", "json"],
                    "default": "markdown",
                },
            },
            "required": ["target"],
        },
    },
    {
        "name": "repo_ast_audit",
        "description": (
            "Audit corpus searchability (Rule D-010 / ADR-115): Tier 1/2 SPIs and high-fan-in classes "
            "missing Javadoc summaries or @governedBy tags, plus unindexed TRACEABILITY.md refs."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "format": {
                    "type": "string",
                    "enum": ["markdown", "json"],
                    "default": "markdown",
                },
            },
        },
    },
    {
        "name": "repo_ast_sync",
        "description": "Incrementally sync modified/added/deleted working-tree files against file_manifest (<25ms).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "check_only": {
                    "type": "boolean",
                    "default": False,
                },
            },
        },
    },
    {
        "name": "repo_ast_offload",
        "description": "Enqueue/poll local GPU jobs (translate-bundle, translate-doc, backfill-javadoc) or run P1 critique_diff.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "enum": ["enqueue", "status", "critique_diff", "run_once"],
                },
                "task_type": {
                    "type": "string",
                    "enum": ["translate-bundle", "translate-doc", "critique-diff", "backfill-javadoc"],
                },
                "target": {"type": "string"},
                "priority": {"type": "integer", "enum": [0, 1, 2]},
                "format": {"type": "string", "enum": ["markdown", "json"], "default": "markdown"},
            },
            "required": ["action"],
        },
    },
]


class CorpusAtlasMcpServer:
    """
    Stateful, warm-cache MCP server backed by `doc-index.sqlite` and `repo_ast`.
    """

    def __init__(
        self,
        db_path: pathlib.Path = DEFAULT_DB_PATH,
        repo_root: pathlib.Path = REPO_ROOT,
        project: str = DEFAULT_PROJECT,
        auto_sync: bool = True,
        sync_coalesce_ms: float = 0.0,
    ) -> None:
        self.db_path = pathlib.Path(db_path)
        self.repo_root = pathlib.Path(repo_root)
        self.project = project
        self.auto_sync = auto_sync
        self.sync_coalesce_ms = max(0.0, float(sync_coalesce_ms))
        self._conn: sqlite3.Connection | None = None
        self._db_stat_key: tuple[int, int] | None = None
        self._last_sync_check_mono: float = 0.0
        self._warmed: bool = False

    def _current_db_stat_key(self) -> tuple[int, int] | None:
        try:
            st = self.db_path.stat()
        except OSError:
            return None
        if st.st_ino != 0:
            return (int(st.st_dev), int(st.st_ino))
        return (int(st.st_size), int(st.st_mtime_ns))

    def _reset_caches(self) -> None:
        repo_ast._CODE_SEC_BY_PATH_CACHE.clear()
        repo_ast._SEARCH_CORPUS_CACHE.clear()

    def _run_sync_with_recovery(self, check_only: bool = False) -> dict[str, Any]:
        assert self._conn is not None
        try:
            res = sync_dirty_files(
                self._conn,
                repo_root=self.repo_root,
                project=self.project,
                check_only=check_only,
            )
            self._db_stat_key = self._current_db_stat_key()
            return res
        except sqlite3.OperationalError as first_exc:
            # Handle `SQLITE_READONLY_DBMOVED` when `doc-index.sqlite` was rebuilt/replaced
            # on disk after `self._conn` was opened, or fall back to read-only check mode
            # when the database file/directory is read-only or locked.
            if self.db_path.is_file():
                try:
                    self._conn.close()
                except sqlite3.Error:
                    pass
                self._conn = sqlite3.connect(str(self.db_path))
                self._conn.row_factory = sqlite3.Row
                self._db_stat_key = self._current_db_stat_key()
                self._reset_caches()
                try:
                    res = sync_dirty_files(
                        self._conn,
                        repo_root=self.repo_root,
                        project=self.project,
                        check_only=check_only,
                    )
                    self._db_stat_key = self._current_db_stat_key()
                    return res
                except sqlite3.OperationalError as retry_exc:
                    first_exc = retry_exc
            if not check_only:
                try:
                    res = sync_dirty_files(
                        self._conn,
                        repo_root=self.repo_root,
                        project=self.project,
                        check_only=True,
                    )
                except sqlite3.OperationalError:
                    res = {"dirty_count": 0, "dirty_files": [], "actions": {}, "reindexed": 0, "elapsed_ms": 0.0}
                res["readonly_fallback"] = True
                res["sync_warning"] = str(first_exc)
                return res
            return {
                "dirty_count": 0,
                "dirty_files": [],
                "actions": {},
                "reindexed": 0,
                "elapsed_ms": 0.0,
                "readonly_fallback": True,
                "sync_warning": str(first_exc),
            }

    def _get_connection(self, force_sync: bool = False) -> tuple[sqlite3.Connection, dict[str, Any]]:
        sync_info: dict[str, Any] = {"dirty_count": 0, "reindexed": 0, "elapsed_ms": 0.0}
        cur_stat_key = self._current_db_stat_key()
        db_replaced = (
            self._conn is not None
            and cur_stat_key is not None
            and self._db_stat_key is not None
            and cur_stat_key != self._db_stat_key
        )
        if self._conn is None or cur_stat_key is None or db_replaced:
            if self._conn is not None:
                try:
                    self._conn.close()
                except sqlite3.Error:
                    pass
                self._conn = None
            if db_replaced:
                self._reset_caches()
                self._warmed = False
            self._conn, sync_info = repo_ast.ensure_db_connection(
                db_path=self.db_path,
                repo_root=self.repo_root,
                project=self.project,
                auto_sync=self.auto_sync,
            )
            self._db_stat_key = self._current_db_stat_key()
            self._last_sync_check_mono = time.perf_counter()
            if not self._warmed:
                repo_ast._get_code_sections_map(self._conn, self.project.strip().lower())
                repo_ast._get_prepared_search_corpus(self._conn, self.project.strip().lower())
                self._warmed = True
            return self._conn, sync_info

        now_mono = time.perf_counter()
        elapsed_since_check_ms = (now_mono - self._last_sync_check_mono) * 1000.0
        if self.auto_sync and (force_sync or elapsed_since_check_ms >= self.sync_coalesce_ms):
            sync_info = self._run_sync_with_recovery(check_only=False)
            self._last_sync_check_mono = time.perf_counter()
            if sync_info.get("dirty_count", 0) > 0 and not sync_info.get("readonly_fallback"):
                self._reset_caches()
                repo_ast._get_code_sections_map(self._conn, self.project.strip().lower())
                repo_ast._get_prepared_search_corpus(self._conn, self.project.strip().lower())
        return self._conn, sync_info

    def close(self) -> None:
        if self._conn is not None:
            try:
                self._conn.close()
            except sqlite3.Error:
                pass
            self._conn = None
        self._db_stat_key = None

    def call_tool(self, name: str, arguments: dict[str, Any] | None = None) -> dict[str, Any]:
        args = dict(arguments or {})
        fmt = str(args.get("format") or "markdown").strip().lower()
        t0 = time.perf_counter()

        if name == "repo_ast_sync":
            self._get_connection(force_sync=False)
            check_only = bool(args.get("check_only", False))
            res = self._run_sync_with_recovery(check_only=check_only)
            self._last_sync_check_mono = time.perf_counter()
            if res.get("dirty_count", 0) > 0 and not check_only and not res.get("readonly_fallback"):
                self._reset_caches()
            text = json.dumps(res, indent=2) if fmt == "json" else repo_ast.format_sync_markdown(res, check_only=check_only)
            return {
                "content": [{"type": "text", "text": text}],
                "isError": False,
            }

        conn, sync_info = self._get_connection(force_sync=False)

        if name == "repo_ast_context":
            target = str(args.get("target") or "").strip()
            if not target:
                return {
                    "content": [{"type": "text", "text": "Error: 'target' parameter is required."}],
                    "isError": True,
                }
            res = repo_ast.query_context(conn, target, project=self.project)
            res["sync"] = sync_info
            res["elapsed_ms"] = round((time.perf_counter() - t0) * 1000.0, 3)
            text = json.dumps(res, indent=2) if fmt == "json" else repo_ast.format_context_markdown(res)
            return {"content": [{"type": "text", "text": text}], "isError": False}

        if name == "repo_ast_search":
            query = str(args.get("query") or "").strip()
            if not query:
                return {
                    "content": [{"type": "text", "text": "Error: 'query' parameter is required."}],
                    "isError": True,
                }
            limit = max(1, min(25, int(args.get("limit") or 8)))
            res = repo_ast.query_search(conn, query, limit=limit, project=self.project)
            res["sync"] = sync_info
            res["elapsed_ms"] = round((time.perf_counter() - t0) * 1000.0, 3)
            text = json.dumps(res, indent=2) if fmt == "json" else repo_ast.format_search_markdown(res)
            return {"content": [{"type": "text", "text": text}], "isError": False}

        if name == "repo_ast_impact":
            target = str(args.get("target") or "").strip()
            if not target:
                return {
                    "content": [{"type": "text", "text": "Error: 'target' parameter is required."}],
                    "isError": True,
                }
            res = repo_ast.query_impact(conn, target, project=self.project)
            res["sync"] = sync_info
            res["elapsed_ms"] = round((time.perf_counter() - t0) * 1000.0, 3)
            text = json.dumps(res, indent=2) if fmt == "json" else repo_ast.format_impact_markdown(res)
            return {"content": [{"type": "text", "text": text}], "isError": False}

        if name == "repo_ast_audit":
            res = repo_ast.audit_corpus_searchability(conn, project=self.project)
            res["sync"] = sync_info
            res["elapsed_ms"] = round((time.perf_counter() - t0) * 1000.0, 3)
            text = json.dumps(res, indent=2) if fmt == "json" else repo_ast.format_audit_markdown(res)
            return {"content": [{"type": "text", "text": text}], "isError": False}

        if name == "repo_ast_offload":
            action = str(args.get("action") or "").strip()
            if not action:
                return {
                    "content": [{"type": "text", "text": "Error: 'action' parameter is required."}],
                    "isError": True,
                }
            res = repo_ast.query_offload(
                conn,
                action=action,
                task_type=str(args.get("task_type") or ""),
                target=str(args.get("target") or ""),
                priority=int(args["priority"]) if args.get("priority") is not None else None,
                repo_root=self.repo_root,
            )
            res["sync"] = sync_info
            res["elapsed_ms"] = round((time.perf_counter() - t0) * 1000.0, 3)
            text = json.dumps(res, indent=2) if fmt == "json" else repo_ast.format_offload_markdown(res)
            return {"content": [{"type": "text", "text": text}], "isError": False}

        return {
            "content": [{"type": "text", "text": f"Unknown tool: {name}"}],
            "isError": True,
        }

    def handle_jsonrpc_message(self, msg: dict[str, Any]) -> dict[str, Any] | None:
        """
        Processes a single JSON-RPC 2.0 request or notification and returns the response dict
        (or `None` for notifications).
        """
        method = str(msg.get("method") or "")
        msg_id = msg.get("id")
        params = msg.get("params") or {}

        # JSON-RPC notifications have no "id"
        if "id" not in msg:
            return None

        if method == "initialize":
            client_proto = str(params.get("protocolVersion") or DEFAULT_PROTOCOL_VERSION)
            if self.db_path.is_file():
                try:
                    self._get_connection(force_sync=False)
                except Exception:  # noqa: BLE001
                    pass
            return {
                "jsonrpc": "2.0",
                "id": msg_id,
                "result": {
                    "protocolVersion": client_proto,
                    "capabilities": {
                        "tools": {"listChanged": False},
                    },
                    "serverInfo": {
                        "name": MCP_SERVER_NAME,
                        "version": MCP_SERVER_VERSION,
                    },
                },
            }

        if method == "ping":
            return {"jsonrpc": "2.0", "id": msg_id, "result": {}}

        if method == "tools/list":
            return {
                "jsonrpc": "2.0",
                "id": msg_id,
                "result": {"tools": MCP_TOOLS_SCHEMA},
            }

        if method == "tools/call":
            tool_name = str(params.get("name") or "")
            tool_args = params.get("arguments") or {}
            try:
                result = self.call_tool(tool_name, tool_args)
                return {"jsonrpc": "2.0", "id": msg_id, "result": result}
            except Exception as exc:  # noqa: BLE001
                return {
                    "jsonrpc": "2.0",
                    "id": msg_id,
                    "result": {
                        "content": [{"type": "text", "text": f"Tool execution error: {exc}"}],
                        "isError": True,
                    },
                }

        if method in ("resources/list", "ResourceList"):
            return {"jsonrpc": "2.0", "id": msg_id, "result": {"resources": []}}

        if method in ("prompts/list", "PromptList"):
            return {"jsonrpc": "2.0", "id": msg_id, "result": {"prompts": []}}

        return {
            "jsonrpc": "2.0",
            "id": msg_id,
            "error": {
                "code": -32601,
                "message": f"Method not found: {method}",
            },
        }


def _read_next_rpc_message(stream: BinaryIO) -> tuple[dict[str, Any] | None, str]:
    """
    Reads the next JSON-RPC 2.0 message from `stream`, supporting both:
      1. Standard MCP newline-delimited JSON (`{"jsonrpc":"2.0",...}\\n`)
      2. Content-Length framed JSON-RPC (`Content-Length: N\\r\\n\\r\\n{...}`)
    Returns `(parsed_dict, framing_mode)` where `framing_mode` is `'line'` or `'header'`.
    """
    while True:
        first_line = stream.readline()
        if not first_line:
            return None, "line"
        stripped = first_line.strip()
        if not stripped:
            continue
        if stripped.lower().startswith(b"content-length:"):
            length = int(stripped.split(b":", 1)[1].strip())
            while True:
                hdr_line = stream.readline()
                if not hdr_line or hdr_line in (b"\r\n", b"\n"):
                    break
            payload = stream.read(length)
            if not payload:
                return None, "header"
            return json.loads(payload.decode("utf-8")), "header"
        return json.loads(stripped.decode("utf-8")), "line"


def _write_rpc_message(stream: BinaryIO, response: dict[str, Any], framing_mode: str = "line") -> None:
    encoded = json.dumps(response, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if framing_mode == "header":
        header = f"Content-Length: {len(encoded)}\r\n\r\n".encode("ascii")
        stream.write(header + encoded)
    else:
        stream.write(encoded + b"\n")
    stream.flush()


def serve_stdio(
    server: CorpusAtlasMcpServer,
    in_stream: BinaryIO | None = None,
    out_stream: BinaryIO | None = None,
) -> int:
    inp = in_stream if in_stream is not None else sys.stdin.buffer
    out = out_stream if out_stream is not None else sys.stdout.buffer
    try:
        while True:
            try:
                msg, framing = _read_next_rpc_message(inp)
            except json.JSONDecodeError as exc:
                err_resp = {
                    "jsonrpc": "2.0",
                    "id": None,
                    "error": {"code": -32700, "message": f"Parse error: {exc}"},
                }
                _write_rpc_message(out, err_resp, "line")
                continue
            if msg is None:
                break
            resp = server.handle_jsonrpc_message(msg)
            if resp is not None:
                _write_rpc_message(out, resp, framing)
    finally:
        server.close()
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Corpus Atlas stdio MCP Server")
    parser.add_argument("--db", type=pathlib.Path, default=DEFAULT_DB_PATH, help="Path to doc-index.sqlite")
    parser.add_argument("--repo-root", type=pathlib.Path, default=REPO_ROOT, help="Repository root path")
    parser.add_argument("--project", type=str, default=DEFAULT_PROJECT, help="Project namespace")
    parser.add_argument("--no-auto-sync", action="store_true", help="Disable pre-query dirty file check")
    parser.add_argument(
        "--sync-coalesce-ms",
        type=float,
        default=0.0,
        help="Optional coalescing window in ms between stat() scans (default 0.0 = check every call)",
    )
    args = parser.parse_args(argv)
    srv = CorpusAtlasMcpServer(
        db_path=args.db,
        repo_root=args.repo_root,
        project=args.project,
        auto_sync=not args.no_auto_sync,
        sync_coalesce_ms=args.sync_coalesce_ms,
    )
    return serve_stdio(srv)


if __name__ == "__main__":
    raise SystemExit(main())
