#!/usr/bin/env python3
"""
Developer AST, Governance Neighborhood (`Gov(c)`), Impact & Anti-Redundancy Search CLI.

Exposes the split-plane SQLite FTS5 + AST knowledge graph (`corpus-atlas/generated/doc-index.sqlite`)
to AI coding agents and developers with automatic sub-150ms incremental file sync and
bounded, low-token Markdown/JSON output.

Governed by:
  - `ADR-115`: Package DAG Disentanglement and Finite Decision-Preservation Hypergraph (`Gov(c)`)
  - `ADR-000` / `ADR-018`: Autonomous ADR discovery and thin router discipline
  - `REQ-DISC-003`: Strict isolation between public `doc_sections` and developer planes
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import sqlite3
import sys
import time
from typing import Any

SCRIPT_DIR = pathlib.Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent
ATLAS_DIR = REPO_ROOT / "corpus-atlas"
BOT_DIR = REPO_ROOT / "discord-support-bot"

for _p in (BOT_DIR, ATLAS_DIR):
    if _p.is_dir() and str(_p) not in sys.path:
        sys.path.insert(0, str(_p))

from build_doc_index import (  # noqa: E402
    DEFAULT_DB_PATH,
    DEFAULT_PROJECT,
    build_index,
    porter_stem_word,
    sync_dirty_files,
)

DAG_TIER_MULTIPLIER: dict[str, float] = {
    "TIER_1_SPI_PURE": 1.35,
    "TIER_2_CORE_DOMAIN": 1.20,
    "TIER_3_PLATFORM_ADAPTER": 1.00,
    "TIER_4_LEAF_ENTRY": 0.90,
}

DAG_TIER_BADGE: dict[str, str] = {
    "TIER_1_SPI_PURE": "[REUSE SPI | Tier 1]",
    "TIER_2_CORE_DOMAIN": "[CORE REUSE | Tier 2]",
    "TIER_3_PLATFORM_ADAPTER": "[PLATFORM ADAPTER | Tier 3]",
    "TIER_4_LEAF_ENTRY": "[LEAF / ENTRY | Tier 4]",
}

_SEARCH_STOP_WORDS = frozenset({
    "the", "and", "for", "with", "from", "into", "that", "this", "how", "what",
    "where", "when", "why", "can", "use", "using", "existing", "helper", "utility",
    "class", "interface", "method", "module", "api", "spi", "rtp", "in", "on", "to", "of",
})


def _split_camel_tokens(text: str) -> list[str]:
    if not text:
        return []
    expanded = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", text)
    expanded = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1 \2", expanded)
    return [w.lower() for w in re.findall(r"[A-Za-z0-9]{2,}", expanded)]


def ensure_db_connection(
    db_path: pathlib.Path = DEFAULT_DB_PATH,
    repo_root: pathlib.Path = REPO_ROOT,
    project: str = DEFAULT_PROJECT,
    auto_sync: bool = True,
) -> tuple[sqlite3.Connection, dict[str, Any]]:
    """
    Opens `db_path` (building it if missing or lacking `code_class_meta`), and runs
    the incremental `file_manifest` sync when `auto_sync=True`.
    """
    sync_info: dict[str, Any] = {"dirty_count": 0, "reindexed": 0, "elapsed_ms": 0.0}
    need_build = not db_path.is_file()
    conn: sqlite3.Connection | None = None
    if not need_build:
        conn = sqlite3.connect(str(db_path))
        conn.row_factory = sqlite3.Row
        tables = {
            str(r[0])
            for r in conn.execute("SELECT name FROM sqlite_master WHERE type IN ('table', 'view')").fetchall()
        }
        if not {"code_class_meta", "file_manifest", "traceability_rows", "dev_sections"}.issubset(tables):
            conn.close()
            conn = None
            need_build = True

    if need_build:
        t0 = time.perf_counter()
        conn = build_index(repo_root=repo_root, db_path=db_path)
        conn.row_factory = sqlite3.Row
        sync_info = {
            "dirty_count": 0,
            "reindexed": 0,
            "built_from_scratch": True,
            "elapsed_ms": round((time.perf_counter() - t0) * 1000.0, 3),
        }
        return conn, sync_info

    assert conn is not None
    if auto_sync:
        try:
            sync_info = sync_dirty_files(conn, repo_root=repo_root, project=project, check_only=False)
        except sqlite3.OperationalError as exc:
            try:
                sync_info = sync_dirty_files(conn, repo_root=repo_root, project=project, check_only=True)
            except sqlite3.OperationalError:
                sync_info = {"dirty_count": 0, "reindexed": 0, "elapsed_ms": 0.0}
            sync_info["readonly_fallback"] = True
            sync_info["sync_warning"] = str(exc)
        if sync_info.get("dirty_count", 0) > 0 and not sync_info.get("readonly_fallback"):
            _CODE_SEC_BY_PATH_CACHE.clear()
            _SEARCH_CORPUS_CACHE.clear()
    return conn, sync_info


_CODE_SEC_BY_PATH_CACHE: dict[tuple[int, str, float], dict[str, dict[str, str]]] = {}


def _manifest_stamp(conn: sqlite3.Connection) -> float:
    try:
        row = conn.execute("SELECT coalesce(max(last_indexed_at), 0.0) FROM file_manifest").fetchone()
        return float(row[0]) if row else 0.0
    except sqlite3.OperationalError:
        return 0.0


def _get_code_sections_map(conn: sqlite3.Connection, proj: str) -> dict[str, dict[str, str]]:
    ck = (id(conn), proj, _manifest_stamp(conn))
    cached = _CODE_SEC_BY_PATH_CACHE.get(ck)
    if cached is not None:
        return cached
    out: dict[str, dict[str, str]] = {}
    for r in conn.execute(
        "SELECT path, class_name, methods, config_refs, diagnostics, summary FROM code_sections WHERE lower(project) = ?",
        (proj,),
    ).fetchall():
        out[str(r["path"])] = {
            "class_name": str(r["class_name"] or ""),
            "methods": str(r["methods"] or ""),
            "config_refs": str(r["config_refs"] or ""),
            "diagnostics": str(r["diagnostics"] or ""),
            "summary": str(r["summary"] or ""),
        }
    if len(_CODE_SEC_BY_PATH_CACHE) > 16:
        _CODE_SEC_BY_PATH_CACHE.clear()
    _CODE_SEC_BY_PATH_CACHE[ck] = out
    return out


def _resolve_target_symbols(
    conn: sqlite3.Connection,
    target: str,
    project: str = DEFAULT_PROJECT,
) -> dict[str, Any]:
    """
    Resolves `<target>` (class name, SPI name, file path, `REQ-*`, `ADR-*`, `S-00x`,
    BIT code, or config key) into primary metadata and AST symbol IDs using indexed lookups.
    """
    proj = project.strip().lower()
    raw = target.strip()
    raw_low = raw.lower()
    stem = pathlib.Path(raw).stem if ("/" in raw or raw.endswith((".java", ".md", ".yml"))) else raw
    stem_low = stem.lower()

    # 1. Indexed lookup on `code_class_meta(lower(class_name), lower(project))`
    cm_rows = conn.execute(
        """
        SELECT * FROM code_class_meta
        WHERE lower(class_name) = ? AND lower(project) = ?
        ORDER BY CASE WHEN class_name = ? THEN 0 ELSE 1 END,
                 CASE dag_tier
                     WHEN 'TIER_1_SPI_PURE' THEN 0
                     WHEN 'TIER_2_CORE_DOMAIN' THEN 1
                     WHEN 'TIER_3_PLATFORM_ADAPTER' THEN 2
                     ELSE 3
                 END
        """,
        (stem_low, proj, stem),
    ).fetchall()
    if not cm_rows and ("/" in raw or raw.endswith(".java")):
        cm_rows = conn.execute(
            "SELECT * FROM code_class_meta WHERE lower(project) = ? AND (path = ? OR path LIKE '%' || ?) LIMIT 6",
            (proj, raw, raw),
        ).fetchall()

    # 2. Check `traceability_rows` if target looks like `REQ-*`
    trace_row = None
    if raw.upper().startswith("REQ-"):
        trace_row = conn.execute(
            "SELECT * FROM traceability_rows WHERE req_id = ? AND project = ? LIMIT 1",
            (raw.upper(), proj),
        ).fetchone()
        if trace_row is None:
            trace_row = conn.execute(
                "SELECT * FROM traceability_rows WHERE lower(project) = ? AND upper(req_id) LIKE ? LIMIT 1",
                (proj, raw.upper() + "%"),
            ).fetchone()

    # 3. Primary-key / indexed lookup on `ast_symbols`
    cand_ids = (
        raw,
        f"cls:{stem}",
        f"spi:{stem}",
        f"req:{raw.upper()}",
        f"adr:{raw.upper() if raw.upper().startswith(('ADR-', 'S-')) else raw}",
        f"bit:{raw.upper()}",
    )
    sym_rows = conn.execute(
        "SELECT * FROM ast_symbols WHERE symbol_id IN (?, ?, ?, ?, ?, ?) AND domain = ? LIMIT 12",
        (*cand_ids, proj),
    ).fetchall()
    if not sym_rows:
        sym_rows = conn.execute(
            "SELECT * FROM ast_symbols WHERE lower(canonical_name) = ? AND domain = ? LIMIT 8",
            (raw_low, proj),
        ).fetchall()

    # 4. Check `config_keys` only when target is not already resolved as a class, REQ, or ADR
    cfg_rows = []
    if not cm_rows and trace_row is None and not raw.upper().startswith(("ADR-", "REQ-")):
        cfg_rows = conn.execute(
            """
            SELECT file_path, key_path, default_value, directives, description
            FROM config_keys
            WHERE lower(project) = ?
              AND (lower(key_path) = ? OR lower(key_path) LIKE '%.' || ?)
            ORDER BY CASE WHEN lower(key_path) = ? THEN 0 ELSE 1 END
            LIMIT 6
            """,
            (proj, raw_low, raw_low, raw_low),
        ).fetchall()

    # 5. Check `arch_sections` if target looks like `ADR-*`
    adr_doc_row = None
    if "adr-" in raw_low:
        adr_doc_row = conn.execute(
            """
            SELECT title, heading, path, substr(body, 1, 260) AS summary
            FROM arch_sections
            WHERE lower(project) = ? AND lower(path) LIKE '%' || ? || '%'
            LIMIT 1
            """,
            (proj, raw_low),
        ).fetchone()

    return {
        "cm_rows": [dict(r) for r in cm_rows],
        "trace_row": dict(trace_row) if trace_row is not None else None,
        "sym_rows": [dict(r) for r in sym_rows],
        "cfg_rows": [dict(r) for r in cfg_rows],
        "adr_doc_row": dict(adr_doc_row) if adr_doc_row is not None else None,
    }


def query_context(
    conn: sqlite3.Connection,
    target: str,
    project: str = DEFAULT_PROJECT,
) -> dict[str, Any]:
    """
    Computes the `ADR-115` decision-preservation neighborhood `Gov(c)` and structural
    AST context for `<target>`.
    """
    t0 = time.perf_counter()
    proj = project.strip().lower()
    resolved = _resolve_target_symbols(conn, target, project=proj)
    cm_rows = resolved["cm_rows"]
    trace_row = resolved["trace_row"]
    sym_rows = resolved["sym_rows"]
    cfg_rows = resolved["cfg_rows"]

    focal_sym_ids: list[str] = []
    for cm in cm_rows[:3]:
        cn = cm["class_name"]
        for sid in (f"cls:{cn}", f"spi:{cn}"):
            if sid not in focal_sym_ids:
                focal_sym_ids.append(sid)
    if trace_row is not None:
        rsid = f"req:{trace_row['req_id']}"
        if rsid not in focal_sym_ids:
            focal_sym_ids.insert(0, rsid)
    for sr in sym_rows[:6]:
        sid = str(sr["symbol_id"])
        if sid not in focal_sym_ids:
            focal_sym_ids.append(sid)
    for cr in cfg_rows[:4]:
        csid = f"cfg:{cr['file_path']}:{cr['key_path']}"
        if csid not in focal_sym_ids:
            focal_sym_ids.append(csid)

    edges: list[dict[str, Any]] = []
    if focal_sym_ids:
        ph = ",".join("?" for _ in focal_sym_ids)
        edge_rows = conn.execute(
            f"""
            SELECT src_symbol_id, dst_symbol_id, edge_kind, weight, evidence_json
            FROM ast_edges
            WHERE src_symbol_id IN ({ph}) OR dst_symbol_id IN ({ph})
            ORDER BY CASE
                WHEN edge_kind IN (
                    'ADR_GOVERNS_CLASS', 'GOVERNED_BY_ADR',
                    'SATISFIES_REQ', 'REQ_SATISFIED_BY',
                    'IMPLEMENTS_SPI', 'SPI_IMPLEMENTED_BY',
                    'EXTENDS_CLASS', 'SUBCLASSED_BY',
                    'VERIFIED_BY_TEST', 'TESTS_CLASS'
                ) THEN 0
                WHEN edge_kind IN (
                    'IMPORTS_CLASS', 'IMPORTED_BY_CLASS',
                    'READS_CONFIG_KEY', 'IMPLEMENTED_IN_CLASS',
                    'EMITS_BIT_CODE', 'EMITTED_BY_CLASS', 'EMITS_BIT_ON_FAILURE',
                    'SIBLING_IMPLEMENTATION', 'SHARED_RARE_DEPENDENCY',
                    'TEST_CO_COUPLED', 'SHARES_CONFIG_KEY'
                ) THEN 1
                ELSE 2
            END, weight DESC
            LIMIT 320
            """,
            tuple(focal_sym_ids) + tuple(focal_sym_ids),
        ).fetchall()
        edges = [dict(r) for r in edge_rows]

    super_classes: list[str] = []
    implemented_spis: list[str] = []
    subclasses_and_impls: list[str] = []
    sibling_and_coupled: list[str] = []
    outbound_imports: list[str] = []
    inbound_callers: list[str] = []
    governing_adrs: list[str] = []
    governing_reqs: list[str] = []
    safety_prohibitions: list[str] = []
    config_keys: list[str] = []
    bit_codes: list[str] = []
    verified_tests: list[str] = []
    gradle_test_commands: list[str] = []
    fallback_cm_cmds: list[str] = []

    def _add_unique(lst: list[str], item: str) -> None:
        if item and item not in lst:
            lst.append(item)

    for cm in cm_rows[:3]:
        if cm.get("super_class"):
            _add_unique(super_classes, str(cm["super_class"]))
        for iface in json.loads(cm.get("interfaces_json") or "[]"):
            _add_unique(implemented_spis, str(iface))
        for imp in json.loads(cm.get("imports_json") or "[]"):
            _add_unique(outbound_imports, str(imp))
        for gov in json.loads(cm.get("governed_by_json") or "[]"):
            g_str = str(gov).strip()
            g_up = g_str.upper()
            if g_up.startswith("REQ-"):
                _add_unique(governing_reqs, g_up)
            elif g_up.startswith("S-00"):
                _add_unique(safety_prohibitions, g_up)
            elif "ADR-" in g_up:
                _add_unique(governing_adrs, g_str)
        if cm.get("gradle_test_cmd"):
            _add_unique(fallback_cm_cmds, str(cm["gradle_test_cmd"]))

        cs_map = _get_code_sections_map(conn, proj)
        cs_row = cs_map.get(str(cm["path"]))
        if cs_row is not None:
            cm["methods"] = str(cs_row["methods"] or "")
            cm["summary"] = str(cs_row["summary"] or "")
            for ck in str(cs_row["config_refs"] or "").split():
                _add_unique(config_keys, ck)

    if trace_row is not None:
        _add_unique(governing_reqs, str(trace_row["req_id"]))
        for dref in json.loads(trace_row.get("design_refs_json") or "[]"):
            d_up = str(dref).upper()
            if d_up.startswith("S-00"):
                _add_unique(safety_prohibitions, d_up)
            elif "ADR-" in d_up:
                _add_unique(governing_adrs, str(dref))
        for ic in json.loads(trace_row.get("implementing_classes_json") or "[]"):
            _add_unique(subclasses_and_impls, str(ic))
        for tc in json.loads(trace_row.get("test_classes_json") or "[]"):
            _add_unique(verified_tests, str(tc))

    focal_set = set(focal_sym_ids)
    # Sort edges so explicit governance/contract edges precede generic co-occurrence edges
    edge_priority = {
        "ADR_GOVERNS_CLASS": 0,
        "GOVERNED_BY_ADR": 0,
        "SATISFIES_REQ": 0,
        "REQ_SATISFIED_BY": 0,
        "IMPLEMENTS_SPI": 1,
        "SPI_IMPLEMENTED_BY": 1,
        "EXTENDS_CLASS": 1,
        "SUBCLASSED_BY": 1,
        "VERIFIED_BY_TEST": 1,
        "TESTS_CLASS": 1,
    }
    edges.sort(key=lambda item: (edge_priority.get(str(item["edge_kind"]), 2), -float(item["weight"] or 0.0)))
    caller_site_hints: dict[str, str] = {}
    for e in edges:
        src = str(e["src_symbol_id"])
        dst = str(e["dst_symbol_id"])
        kind = str(e["edge_kind"])
        other = dst if src in focal_set else src
        other_clean = other.split(":", 1)[-1]

        if kind == "EXTENDS_CLASS" and src in focal_set:
            _add_unique(super_classes, other_clean)
        elif kind in ("SUBCLASSED_BY", "SPI_IMPLEMENTED_BY") and src in focal_set:
            _add_unique(subclasses_and_impls, other_clean)
        elif kind in ("EXTENDS_CLASS", "IMPLEMENTS_SPI") and dst in focal_set:
            _add_unique(subclasses_and_impls, src.split(":", 1)[-1])
        elif kind == "IMPLEMENTS_SPI" and src in focal_set:
            _add_unique(implemented_spis, other_clean)
        elif kind == "IMPORTS_CLASS" and src in focal_set:
            _add_unique(outbound_imports, other_clean)
        elif (kind == "IMPORTED_BY_CLASS" and src in focal_set) or (kind == "IMPORTS_CLASS" and dst in focal_set):
            caller_cls = other_clean if (kind == "IMPORTED_BY_CLASS" and src in focal_set) else src.split(":", 1)[-1]
            _add_unique(inbound_callers, caller_cls)
            if caller_cls not in caller_site_hints and e.get("evidence_json"):
                try:
                    ev_list = json.loads(str(e["evidence_json"]))
                except (ValueError, TypeError):
                    ev_list = []
                for ev in ev_list:
                    m_ev = re.search(r":(\d+(?:,\d+)?)(?:\s*\(([^)]+)\))?$", str(ev).strip())
                    if m_ev:
                        ln_p = m_ev.group(1)
                        m_p = m_ev.group(2)
                        caller_site_hints[caller_cls] = f"`{caller_cls}:{ln_p}` (`{m_p}`)" if m_p else f"`{caller_cls}:{ln_p}`"
                        break
        elif kind in ("SATISFIES_REQ", "REQ_SATISFIED_BY"):
            if other.startswith("req:"):
                _add_unique(governing_reqs, other_clean)
            elif other.startswith("cls:"):
                _add_unique(subclasses_and_impls, other_clean)
        elif kind in ("GOVERNED_BY_ADR", "ADR_GOVERNS_CLASS", "GOVERNED_BY_INVARIANT"):
            if other.startswith("adr:"):
                if other_clean.upper().startswith("S-00"):
                    _add_unique(safety_prohibitions, other_clean.upper())
                else:
                    _add_unique(governing_adrs, other_clean)
            elif other.startswith("cls:"):
                _add_unique(subclasses_and_impls, other_clean)
        elif kind in ("VERIFIED_BY_TEST", "TESTS_CLASS"):
            if other.startswith("test:"):
                t_short = other.split(":")[-1]
                _add_unique(verified_tests, t_short)
            elif other.startswith("cls:"):
                _add_unique(subclasses_and_impls, other_clean)
        elif kind in ("READS_CONFIG_KEY", "IMPLEMENTED_IN_CLASS", "GOVERNED_BY_KEY"):
            if other.startswith("cfg:"):
                _add_unique(config_keys, other.split(":", 1)[-1])
            elif other.startswith("cls:"):
                _add_unique(inbound_callers, other_clean)
        elif kind in ("EMITS_BIT_CODE", "EMITTED_BY_CLASS", "EMITS_BIT_ON_FAILURE"):
            if other.startswith("bit:"):
                _add_unique(bit_codes, other_clean)
        elif kind in ("SIBLING_IMPLEMENTATION", "SHARED_RARE_DEPENDENCY", "TEST_CO_COUPLED", "SHARES_CONFIG_KEY"):
            if other.startswith("cls:"):
                _add_unique(sibling_and_coupled, f"{other_clean} ({kind.lower().replace('_', '-')}, w={e['weight']:.1f})")

    # Enrich `governing_reqs` with implementing classes and tests from `traceability_rows`
    req_details: list[dict[str, Any]] = []
    primary_cm = cm_rows[0] if cm_rows else None
    p_cn = str(primary_cm["class_name"]) if primary_cm is not None else ""
    p_stem = re.sub(r"^(?:RTP|I(?=[A-Z]))", "", p_cn) if p_cn else ""
    if governing_reqs:
        ph_r = ",".join("?" for _ in governing_reqs[:12])
        tr_rows = conn.execute(
            f"SELECT req_id, summary, design_refs_json, implementing_classes_json, test_classes_json FROM traceability_rows WHERE lower(project) = ? AND req_id IN ({ph_r})",
            (proj, *governing_reqs[:12]),
        ).fetchall()
        for r in tr_rows:
            drefs = json.loads(str(r["design_refs_json"] or "[]"))
            impls = json.loads(str(r["implementing_classes_json"] or "[]"))
            tsts = json.loads(str(r["test_classes_json"] or "[]"))
            req_details.append({
                "req_id": str(r["req_id"]),
                "summary": str(r["summary"]),
                "design_refs": drefs,
                "implementing_classes": impls,
                "test_classes": tsts,
            })
            for d in drefs:
                if str(d).upper().startswith("S-00"):
                    _add_unique(safety_prohibitions, str(d).upper())
                elif "ADR-" in str(d).upper():
                    _add_unique(governing_adrs, str(d))
            for t in tsts:
                t_str = str(t)
                if primary_cm is None or len(impls) <= 8 or (p_cn and p_cn in t_str) or (len(p_stem) >= 4 and p_stem in t_str):
                    _add_unique(verified_tests, t_str)

    # Resolve Gradle test commands for verified_tests, prioritizing <Target>*Test and stem matches
    if primary_cm is not None:
        impl_prefixes = [c for c in subclasses_and_impls[:8] if len(c) >= 4]
        verified_tests.sort(
            key=lambda tn: (
                1 if ("ArchitectureTest" in tn or "APIGuardTest" in tn or "CandidateGateTest" in tn) else 0,
                0 if tn == f"{p_cn}Test" else (
                    1 if tn.startswith(p_cn) else (
                        2 if p_cn in tn else (
                            3 if (len(p_stem) >= 4 and p_stem in tn) or any(tn.startswith(ip) for ip in impl_prefixes) else 4
                        )
                    )
                ),
                tn,
            )
        )
    if verified_tests:
        ph_t = ",".join("?" for _ in verified_tests[:14])
        t_rows = conn.execute(
            f"SELECT test_class_name, gradle_test_cmd FROM test_class_meta WHERE lower(project) = ? AND test_class_name IN ({ph_t})",
            (proj, *verified_tests[:14]),
        ).fetchall()
        cmd_by_t = {str(tr["test_class_name"]): str(tr["gradle_test_cmd"]) for tr in t_rows}
        for tn in verified_tests[:14]:
            if tn in cmd_by_t:
                _add_unique(gradle_test_commands, cmd_by_t[tn])
    for fb_cmd in fallback_cm_cmds:
        _add_unique(gradle_test_commands, fb_cmd)

    for cr in cfg_rows[:4]:
        _add_unique(config_keys, f"{cr['file_path']}:{cr['key_path']} (default={cr['default_value']})")

    if cfg_rows and not cm_rows:
        leaf_k = target.strip().split(".")[-1]
        if len(leaf_k) >= 3:
            for csr in conn.execute(
                """
                SELECT class_name FROM code_sections
                WHERE lower(project) = ? AND lower(config_refs) LIKE '%' || ? || '%'
                ORDER BY CASE
                    WHEN class_name LIKE '%ConfigConstraints%' THEN 0
                    WHEN class_name LIKE '%Params%' OR class_name LIKE '%Keys%' THEN 1
                    WHEN class_name IN ('Circle', 'Square', 'Shape', 'MemoryShape', 'Region') THEN 2
                    ELSE 3
                END, class_name ASC
                LIMIT 12
                """,
                (proj, leaf_k.lower()),
            ).fetchall():
                _add_unique(inbound_callers, str(csr["class_name"]))
            fts_tok = re.sub(r"[^a-zA-Z0-9]", "", leaf_k)
            if len(fts_tok) >= 4:
                try:
                    for ftr in conn.execute(
                        "SELECT class_name FROM code_sections WHERE code_sections MATCH ? AND lower(project) = ? ORDER BY bm25(code_sections) ASC LIMIT 8",
                        (f'"{fts_tok}"', proj),
                    ).fetchall():
                        _add_unique(inbound_callers, str(ftr["class_name"]))
                except sqlite3.OperationalError:
                    pass
        # 2-hop vertical-slice propagation from config key -> reader/validator classes -> Gov(c) ADRs, REQs, and Gradle tests
        if inbound_callers:
            ph_ic = ",".join("?" for _ in inbound_callers[:8])
            ic_meta_by_name = {
                str(cm_r["class_name"]): cm_r
                for cm_r in conn.execute(
                    f"SELECT class_name, governed_by_json, gradle_test_cmd FROM code_class_meta WHERE lower(project) = ? AND class_name IN ({ph_ic})",
                    (proj, *inbound_callers[:8]),
                ).fetchall()
            }
            for ic_name in inbound_callers[:8]:
                cm_r = ic_meta_by_name.get(ic_name)
                if cm_r is None:
                    continue
                for g in json.loads(str(cm_r["governed_by_json"] or "[]"))[:8]:
                    g_up = str(g).upper()
                    if g_up.startswith("REQ-"):
                        _add_unique(governing_reqs, g_up)
                    elif g_up.startswith("S-00"):
                        _add_unique(safety_prohibitions, g_up)
                    elif "ADR-" in g_up:
                        _add_unique(governing_adrs, str(g))
                if cm_r["gradle_test_cmd"]:
                    _add_unique(gradle_test_commands, str(cm_r["gradle_test_cmd"]))

    adr_doc_row = resolved.get("adr_doc_row")
    if adr_doc_row is not None and "adr-" in target.lower():
        _add_unique(governing_adrs, target.upper() if target.upper().startswith("ADR-") else target)

    # Bidirectional S-00x <-> REQ-RTP-S-00x invariant closure
    for r_id in list(governing_reqs):
        m_s = re.fullmatch(r"REQ-RTP-S-(00[1-7])", r_id.upper())
        if m_s:
            _add_unique(safety_prohibitions, f"S-{m_s.group(1)}")
    for s_id in list(safety_prohibitions):
        m_s = re.fullmatch(r"S-(00[1-7])", s_id.upper())
        if m_s:
            _add_unique(governing_reqs, f"REQ-RTP-S-{m_s.group(1)}")

    primary_cm = cm_rows[0] if cm_rows else None
    elapsed_ms = round((time.perf_counter() - t0) * 1000.0, 3)

    return {
        "target": target,
        "found": bool(cm_rows or trace_row or sym_rows or cfg_rows or adr_doc_row),
        "primary_class": primary_cm,
        "adr_doc": adr_doc_row,
        "disambiguation_classes": [
            {"class_name": c["class_name"], "module_name": c["module_name"], "dag_tier": c["dag_tier"], "path": c["path"]}
            for c in cm_rows[1:5]
        ],
        "traceability_row": trace_row,
        "gov_c": {
            "adrs": governing_adrs[:16],
            "reqs": governing_reqs[:16],
            "safety_prohibitions": safety_prohibitions[:8],
        },
        "req_details": req_details[:6],
        "hierarchy": {
            "super_classes": super_classes[:6],
            "implemented_spis": implemented_spis[:10],
            "subclasses_and_implementors": subclasses_and_impls[:24],
            "sibling_and_coupled": sibling_and_coupled[:8],
        },
        "dependencies": {
            "outbound_imports": outbound_imports[:14],
            "inbound_callers": inbound_callers[:14],
            "inbound_caller_sites": [caller_site_hints.get(c, f"`{c}`") for c in inbound_callers[:14]],
        },
        "config_keys": config_keys[:10],
        "bit_codes": bit_codes[:8],
        "verified_by_tests": verified_tests[:24],
        "gradle_test_commands": gradle_test_commands[:6],
        "elapsed_ms": elapsed_ms,
    }


def format_context_markdown(ctx: dict[str, Any]) -> str:
    """Formats `query_context` into a compact `<= 40`-line Markdown card for low-token orientation."""
    if not ctx.get("found"):
        return f"### AST Context: `{ctx['target']}` (not found)\n- No matching symbol in index.\n"

    lines: list[str] = []
    pc = ctx.get("primary_class")
    tr = ctx.get("traceability_row")
    gov = ctx["gov_c"]
    hier = ctx["hierarchy"]
    deps = ctx["dependencies"]

    if pc is not None:
        badge = DAG_TIER_BADGE.get(pc["dag_tier"], pc["dag_tier"])
        lines.append(f"### AST Context: `{pc['class_name']}` ({pc['kind']}) {badge}")
        lines.append(f"- **Path / Module:** `{pc['path']}` (`{pc['module_name']}`, `{pc['package_name']}`)")
        if pc.get("methods"):
            lines.append(f"- **Methods:** `{', '.join(pc['methods'].split()[:16])}`")
    elif tr is not None:
        lines.append(f"### AST Context: `{tr['req_id']}` (Requirement Traceability)")
        lines.append(f"- **Summary:** {tr['summary'][:200]}")
    elif ctx.get("adr_doc") is not None:
        ad = ctx["adr_doc"]
        lines.append(f"### AST Context: `{ctx['target']}` — {ad['title']}")
        lines.append(f"- **Path:** `{ad['path']}`")
    else:
        lines.append(f"### AST Context: `{ctx['target']}`")

    if ctx.get("disambiguation_classes"):
        others = ", ".join(f"`{d['module_name']}:{d['class_name']}` ({d['dag_tier']})" for d in ctx["disambiguation_classes"])
        lines.append(f"- **Other Modules:** {others}")

    gov_parts: list[str] = []
    if gov["adrs"]:
        gov_parts.append("ADRs: " + ", ".join(f"`{a}`" for a in gov["adrs"][:8]))
    if gov["reqs"]:
        gov_parts.append("REQs: " + ", ".join(f"`{r}`" for r in gov["reqs"][:8]))
    if gov["safety_prohibitions"]:
        gov_parts.append("Prohibitions: " + ", ".join(f"`{s}`" for s in gov["safety_prohibitions"]))
    if gov_parts:
        lines.append("- **Gov(c) (`ADR-115`):** " + " | ".join(gov_parts))

    if hier["super_classes"] or hier["implemented_spis"]:
        h_parts: list[str] = []
        if hier["super_classes"]:
            h_parts.append("extends " + ", ".join(f"`{s}`" for s in hier["super_classes"]))
        if hier["implemented_spis"]:
            h_parts.append("implements " + ", ".join(f"`{i}`" for i in hier["implemented_spis"]))
        lines.append("- **Hierarchy / SPIs:** " + "; ".join(h_parts))

    if hier["subclasses_and_implementors"]:
        lines.append("- **Implementors / Subclasses:** " + ", ".join(f"`{c}`" for c in hier["subclasses_and_implementors"][:10]))
    if hier.get("sibling_and_coupled"):
        lines.append("- **2-Hop Siblings & Co-Coupled:** " + ", ".join(f"`{c}`" for c in hier["sibling_and_coupled"][:6]))

    if deps["outbound_imports"]:
        lines.append("- **Depends On (Imports):** " + ", ".join(f"`{d}`" for d in deps["outbound_imports"][:10]))
    if deps["inbound_callers"]:
        caller_items = deps.get("inbound_caller_sites") or [f"`{c}`" for c in deps["inbound_callers"]]
        lines.append("- **Referenced By (Callers):** " + ", ".join(caller_items[:10]))

    if ctx["config_keys"]:
        lines.append("- **Config Keys:** " + ", ".join(f"`{k}`" for k in ctx["config_keys"][:8]))
    if ctx["bit_codes"]:
        lines.append("- **BIT Codes:** " + ", ".join(f"`{b}`" for b in ctx["bit_codes"][:6]))

    if ctx["verified_by_tests"]:
        lines.append("- **Verified By Tests:** " + ", ".join(f"`{t}`" for t in ctx["verified_by_tests"][:10]))
    if ctx["gradle_test_commands"]:
        lines.append("- **Gradle Test Cmd:** `" + ctx["gradle_test_commands"][0] + "`")

    return "\n".join(lines[:36]) + "\n"


_SEARCH_CORPUS_CACHE: dict[tuple[int, str, float], tuple[dict[str, int], dict[str, int], list[tuple[Any, ...]]]] = {}


def _get_prepared_search_corpus(
    conn: sqlite3.Connection,
    proj: str,
) -> tuple[dict[str, int], dict[str, int], list[tuple[Any, ...]]]:
    cache_key = (id(conn), proj, _manifest_stamp(conn))
    cached = _SEARCH_CORPUS_CACHE.get(cache_key)
    if cached is not None:
        return cached

    impl_by_spi: dict[str, list[str]] = {}
    spi_impl_deg: dict[str, int] = {}
    inbound_import_deg: dict[str, int] = {}
    for er in conn.execute(
        """
        SELECT src_symbol_id, dst_symbol_id, edge_kind
        FROM ast_edges
        WHERE edge_kind IN ('SPI_IMPLEMENTED_BY', 'SUBCLASSED_BY', 'IMPORTED_BY_CLASS')
        """
    ).fetchall():
        src_s = str(er["src_symbol_id"])
        dst_s = str(er["dst_symbol_id"])
        ek = str(er["edge_kind"])
        src_c = src_s.split(":", 1)[-1]
        dst_c = dst_s.split(":", 1)[-1]
        if ek in ("SPI_IMPLEMENTED_BY", "SUBCLASSED_BY"):
            impl_list = impl_by_spi.setdefault(src_c, [])
            if dst_c not in impl_list:
                impl_list.append(dst_c)
            spi_impl_deg[src_c] = len(impl_list)
        else:
            inbound_import_deg[src_c] = inbound_import_deg.get(src_c, 0) + 1

    cs_by_path: dict[str, tuple[str, str]] = {}
    for cr in conn.execute(
        "SELECT path, methods, summary FROM code_sections WHERE lower(project) = ?",
        (proj,),
    ).fetchall():
        cs_by_path[str(cr["path"])] = (str(cr["methods"] or ""), str(cr["summary"] or ""))

    rows = conn.execute(
        """
        SELECT class_name, package_name, path, module_name, dag_tier,
               super_class, interfaces_json, imports_json, governed_by_json, gradle_test_cmd, kind
        FROM code_class_meta
        WHERE lower(project) = ?
        """,
        (proj,),
    ).fetchall()

    prepared_rows: list[tuple[Any, ...]] = []
    for r in rows:
        cls_name = str(r["class_name"])
        cls_low = cls_name.lower()
        cls_words = set(_split_camel_tokens(cls_name))
        cls_stems = {porter_stem_word(w) for w in cls_words}
        pkg_low = str(r["package_name"] or "").lower()
        mod_low = str(r["module_name"] or "").lower()
        methods_str, summary_str = cs_by_path.get(str(r["path"]), ("", ""))
        method_words = set(_split_camel_tokens(methods_str))
        ifaces = json.loads(str(r["interfaces_json"] or "[]"))
        imps = json.loads(str(r["imports_json"] or "[]"))[:12]
        import_words = set(_split_camel_tokens(" ".join(imps)))
        govs = json.loads(str(r["governed_by_json"] or "[]"))
        impls_str = " ".join(impl_by_spi.get(cls_name, [])[:10])
        hay_low = f"{cls_low} {pkg_low} {mod_low} {methods_str.lower()} {summary_str.lower()} {' '.join(ifaces).lower()} {' '.join(govs).lower()} {impls_str.lower()}"
        hay_words = (
            set(re.findall(r"[a-z0-9_.-]{2,}", hay_low))
            | method_words
            | set(_split_camel_tokens(summary_str))
            | set(_split_camel_tokens(impls_str))
        )
        hay_stems = {porter_stem_word(w) for w in hay_words if len(w) >= 3}
        prepared_rows.append((r, cls_name, cls_low, cls_words, cls_stems, method_words, import_words, pkg_low, mod_low, methods_str, summary_str, ifaces, govs, hay_low, hay_words, hay_stems))

    if len(_SEARCH_CORPUS_CACHE) > 16:
        _SEARCH_CORPUS_CACHE.clear()
    res_tuple = (spi_impl_deg, inbound_import_deg, prepared_rows)
    _SEARCH_CORPUS_CACHE[cache_key] = res_tuple
    return res_tuple


def query_search(
    conn: sqlite3.Connection,
    query: str,
    project: str = DEFAULT_PROJECT,
    limit: int = 10,
) -> dict[str, Any]:
    """
    Hybrid FTS5 + AST capability search with `ADR-115` Tier 1/2 SPI anti-redundancy
    ranking (`1.35x` for `TIER_1_SPI_PURE`, `1.20x` for `TIER_2_CORE_DOMAIN`).
    """
    t0 = time.perf_counter()
    proj = project.strip().lower()
    raw_tokens = [
        t.lower()
        for t in re.findall(r"[A-Za-z0-9_.-]{2,}", query)
        if t.lower() not in _SEARCH_STOP_WORDS
    ]
    camel_tokens: list[str] = []
    for rt in raw_tokens:
        for ct in _split_camel_tokens(rt):
            if ct not in _SEARCH_STOP_WORDS and ct not in camel_tokens:
                camel_tokens.append(ct)
    all_tokens = list(dict.fromkeys(raw_tokens + camel_tokens))

    spi_impl_deg, inbound_import_deg, prepared_rows = _get_prepared_search_corpus(conn, proj)
    scored_classes: list[dict[str, Any]] = []

    n_docs = max(1, len(prepared_rows))
    tok_df: dict[str, int] = {t: 0 for t in all_tokens}
    for _r, _cn, _cl, cls_words, cls_stems, _mw, _iw, _pl, _ml, _ms, _ss, _if, _gv, _hl, hay_words, hay_stems in prepared_rows:
        for tok in all_tokens:
            st = porter_stem_word(tok)
            if tok in cls_words or tok in hay_words or (len(st) >= 4 and (st in cls_stems or st in hay_stems)):
                tok_df[tok] = tok_df.get(tok, 0) + 1

    import math as _math
    tok_idf: dict[str, float] = {
        tok: max(0.55, min(2.2, _math.log(1.0 + n_docs / (1.0 + df)) / 3.2))
        for tok, df in tok_df.items()
    }
    total_query_idf = sum(tok_idf.values()) or 1.0
    max_query_idf = max(tok_idf.values()) if tok_idf else 1.0

    for r, cls_name, cls_low, cls_words, cls_stems, method_words, import_words, pkg_low, mod_low, methods_str, summary_str, ifaces, govs, hay_low, hay_words, hay_stems in prepared_rows:
        base_score = 0.0
        matched_terms: list[str] = []
        cls_word_hits = 0
        exact_hits = 0
        for tok in all_tokens:
            st = porter_stem_word(tok)
            idf_w = tok_idf.get(tok, 1.0)
            if tok == cls_low:
                base_score += 30.0 * idf_w
                cls_word_hits += 2
                exact_hits += 1
                matched_terms.append(tok)
            elif tok in cls_words:
                base_score += 14.0 * idf_w
                cls_word_hits += 1
                exact_hits += 1
                matched_terms.append(tok)
            elif len(st) >= 4 and st in cls_stems:
                base_score += 14.0 * idf_w
                cls_word_hits += 1
                matched_terms.append(tok)
            elif any(tok == iface.lower() for iface in ifaces):
                base_score += 7.5 * idf_w
                exact_hits += 1
                matched_terms.append(tok)
            elif tok in pkg_low or tok in mod_low:
                base_score += 4.5 * idf_w
                exact_hits += 1
                matched_terms.append(tok)
            elif tok in method_words and idf_w >= 1.35:
                m_hits = sum(1 for m in methods_str.split() if tok in _split_camel_tokens(m))
                base_score += (8.0 + 3.0 * min(max(0, m_hits - 1), 2)) * idf_w
                exact_hits += 1
                matched_terms.append(tok)
            elif tok in hay_words:
                base_score += 3.0 * idf_w
                exact_hits += 1
                matched_terms.append(tok)
            elif len(st) >= 4 and st in hay_stems:
                base_score += 3.0 * idf_w
                matched_terms.append(tok)
            elif tok in import_words and idf_w >= 1.25:
                base_score += 2.4 * idf_w
                matched_terms.append(tok)
            elif len(tok) >= 4 and tok in hay_low:
                base_score += 1.8 * idf_w
                matched_terms.append(tok)

        if base_score <= 0.0:
            continue

        if cls_word_hits >= 2:
            base_score += 7.5 * (cls_word_hits - 1)
            if len(cls_words) >= 2 and cls_words.issubset(set(all_tokens)):
                base_score += 10.0

        # Multi-term & IDF-weighted query coverage coordination; damp partial generic collisions
        uniq_matched_set = set(matched_terms)
        uniq_matched = len(uniq_matched_set)
        if uniq_matched >= 2:
            base_score *= 1.0 + 0.22 * min(uniq_matched, 5)
        elif len(all_tokens) >= 2 and exact_hits == 0:
            base_score *= 0.45

        if len(all_tokens) >= 3 and max_query_idf >= 1.35:
            matched_idf = sum(tok_idf.get(t, 1.0) for t in uniq_matched_set)
            idf_cov = matched_idf / total_query_idf
            base_score *= 0.45 + 0.55 * idf_cov
            max_matched_idf = max((tok_idf.get(t, 0.0) for t in uniq_matched_set), default=0.0)
            if max_matched_idf < max_query_idf - 0.25 and all_tokens[0] not in uniq_matched_set:
                base_score *= 0.35

        tier = str(r["dag_tier"] or "TIER_2_CORE_DOMAIN")
        kind = str(r["kind"] or "CLASS")
        tier_mult = DAG_TIER_MULTIPLIER.get(tier, 1.0)
        if kind == "INTERFACE" and tier in ("TIER_1_SPI_PURE", "TIER_2_CORE_DOMAIN"):
            tier_mult *= 1.22
        impl_cnt = spi_impl_deg.get(cls_name, 0)
        if impl_cnt >= 1 and tier in ("TIER_1_SPI_PURE", "TIER_2_CORE_DOMAIN"):
            tier_mult *= 1.0 + min(0.45, 0.09 * impl_cnt)
        # Damp high-fan-in passive data carrier classes (`Region`, `RTP`, `RTPLocation`) unless exact name match
        imp_fan_in = inbound_import_deg.get(cls_name, 0)
        if imp_fan_in > 25 and impl_cnt == 0 and kind != "INTERFACE" and cls_low not in all_tokens:
            tier_mult *= 0.72
        # De-boost test/mock/benchmark/internal-helper classes when searching for reusable SPIs
        if cls_low.endswith(("test", "tests", "benchmark", "cmd", "command")) or "mock" in cls_low:
            if "cmd" not in all_tokens and "command" not in all_tokens:
                tier_mult *= 0.65

        final_score = round(base_score * tier_mult, 3)
        scored_classes.append({
            "class_name": cls_name,
            "kind": kind,
            "module_name": str(r["module_name"]),
            "dag_tier": tier,
            "badge": DAG_TIER_BADGE.get(tier, tier),
            "path": str(r["path"]),
            "methods": " ".join(methods_str.split()[:10]),
            "interfaces": ifaces[:6],
            "governed_by": govs[:8],
            "summary": summary_str[:200],
            "gradle_test_cmd": str(r["gradle_test_cmd"]),
            "score": final_score,
        })

    scored_classes.sort(
        key=lambda item: (
            -item["score"],
            0 if item["dag_tier"] == "TIER_1_SPI_PURE" else (1 if item["dag_tier"] == "TIER_2_CORE_DOMAIN" else 2),
            len(item["class_name"]),
        )
    )

    # 2. Search `arch_sections` and `dev_sections` via FTS5 for matching ADRs / dev docs (deduplicated & IDF-ranked)
    doc_hits: list[dict[str, Any]] = []
    ranked_fts_tokens = sorted(
        [t for t in all_tokens if len(re.sub(r"[^a-z0-9]", "", t)) >= 3],
        key=lambda t: (-tok_idf.get(t, 1.0), t),
    )
    fts_terms = [re.sub(r"[^a-z0-9]", "", t) for t in ranked_fts_tokens][:6]
    if fts_terms:
        top_gov_adrs = {
            str(g).upper()
            for c in scored_classes[:4]
            for g in c.get("governed_by", [])
            if "ADR-" in str(g).upper()
        }
        top_mod_dirs = {
            c["path"].split("/src/", 1)[0].lower()
            for c in scored_classes[:4]
            if "/src/" in str(c.get("path") or "")
        }
        fts_expr = " OR ".join(f'"{t}"' for t in fts_terms)
        best_doc_by_path: dict[str, dict[str, Any]] = {}
        for plane_tbl in ("arch_sections", "dev_sections"):
            try:
                d_rows = conn.execute(
                    f"""
                    SELECT title, heading, path, substr(body, 1, 240) AS snippet, bm25({plane_tbl}) AS rank
                    FROM {plane_tbl}
                    WHERE {plane_tbl} MATCH ? AND lower(project) = ?
                    ORDER BY rank ASC
                    LIMIT 30
                    """,
                    (fts_expr, proj),
                ).fetchall()
                for dr in d_rows:
                    d_path = str(dr["path"])
                    d_title = str(dr["title"] or "")
                    d_heading = str(dr["heading"] or d_title)
                    d_snip = " ".join(str(dr["snippet"] or "").split())[:150]
                    hdr_blob = f"{d_path} {d_title} {d_heading}".lower()
                    body_blob = f"{hdr_blob} {d_snip}".lower()
                    body_stems = {porter_stem_word(w) for w in re.findall(r"[a-z0-9]{3,}", body_blob)}
                    d_score = max(0.0, -float(dr["rank"] or 0.0)) * 0.35
                    doc_matched_idf = 0.0
                    doc_max_idf = 0.0
                    for tok in all_tokens:
                        idf_w = tok_idf.get(tok, 1.0)
                        st = porter_stem_word(tok)
                        if tok in hdr_blob:
                            d_score += 4.2 * idf_w
                            doc_matched_idf += idf_w
                            doc_max_idf = max(doc_max_idf, idf_w)
                        elif tok in body_blob or (len(st) >= 4 and st in body_stems):
                            d_score += 2.0 * idf_w
                            doc_matched_idf += idf_w
                            doc_max_idf = max(doc_max_idf, idf_w)
                    path_up = d_path.upper()
                    if any(adr_id in path_up for adr_id in top_gov_adrs):
                        d_score += 6.0
                    path_low = d_path.lower()
                    if path_low.endswith("/readme.md") and any(path_low.startswith(md + "/") for md in top_mod_dirs):
                        d_score += 6.5
                    if len(all_tokens) >= 3:
                        d_cov = doc_matched_idf / total_query_idf
                        d_score *= 0.35 + 0.65 * (d_cov ** 1.15)
                        if max_query_idf >= 1.35 and doc_max_idf < max_query_idf - 0.25 and all_tokens[0] not in body_blob:
                            d_score *= 0.12
                    cand_doc = {
                        "plane": plane_tbl,
                        "path": d_path,
                        "heading": d_heading,
                        "snippet": d_snip,
                        "score": round(d_score, 3),
                    }
                    prev_doc = best_doc_by_path.get(d_path)
                    if prev_doc is None or cand_doc["score"] > prev_doc["score"]:
                        best_doc_by_path[d_path] = cand_doc
            except sqlite3.OperationalError:
                pass
        doc_hits = sorted(best_doc_by_path.values(), key=lambda x: (-x["score"], x["path"]))

    elapsed_ms = round((time.perf_counter() - t0) * 1000.0, 3)
    return {
        "query": query,
        "results": scored_classes[:limit],
        "doc_hits": doc_hits[:5],
        "elapsed_ms": elapsed_ms,
    }


def format_search_markdown(res: dict[str, Any]) -> str:
    lines: list[str] = [f"### AST Capability Search: `{res['query']}`"]
    hits = res.get("results", [])
    if not hits:
        lines.append("- No matching classes or SPIs found.")
    for idx, h in enumerate(hits, 1):
        gov_str = f" | Gov: {', '.join(h['governed_by'][:4])}" if h.get("governed_by") else ""
        methods_str = f" | methods: `{h['methods']}`" if h.get("methods") else ""
        lines.append(
            f"{idx}. **`{h['class_name']}`** ({h['kind']}) {h['badge']} — `{h['path']}` (`{h['module_name']}`, score={h['score']}){gov_str}{methods_str}"
        )
    if res.get("doc_hits"):
        lines.append("#### Related Architecture & Dev Docs")
        for dh in res["doc_hits"][:4]:
            lines.append(f"- `{dh['path']}#{dh['heading']}` ({dh['plane']})")
    return "\n".join(lines) + "\n"


def query_impact(
    conn: sqlite3.Connection,
    target: str,
    project: str = DEFAULT_PROJECT,
) -> dict[str, Any]:
    """
    Computes downstream dependents (1-hop and 2-hop), affected `REQ-*` traceability
    rows, governing `ADR-*` / `S-00x` invariants, and exact Gradle test commands.
    """
    t0 = time.perf_counter()
    proj = project.strip().lower()
    base_ctx = query_context(conn, target, project=proj)

    direct_dependents: list[str] = []
    for c in base_ctx["hierarchy"]["subclasses_and_implementors"] + base_ctx["dependencies"]["inbound_callers"]:
        if c not in direct_dependents:
            direct_dependents.append(c)

    second_hop_dependents: list[str] = []
    if direct_dependents:
        hop_syms = [f"cls:{c}" for c in direct_dependents[:16]] + [f"spi:{c}" for c in direct_dependents[:16]]
        ph = ",".join("?" for _ in hop_syms)
        hop_rows = conn.execute(
            f"""
            SELECT src_symbol_id, dst_symbol_id, edge_kind
            FROM ast_edges
            WHERE (src_symbol_id IN ({ph}) AND edge_kind IN ('SUBCLASSED_BY', 'SPI_IMPLEMENTED_BY', 'IMPORTED_BY_CLASS'))
               OR (dst_symbol_id IN ({ph}) AND edge_kind IN ('EXTENDS_CLASS', 'IMPLEMENTS_SPI', 'IMPORTS_CLASS'))
            LIMIT 60
            """,
            tuple(hop_syms) + tuple(hop_syms),
        ).fetchall()
        hop_set = set(hop_syms)
        for hr in hop_rows:
            s_id = str(hr["src_symbol_id"])
            d_id = str(hr["dst_symbol_id"])
            cand = d_id if s_id in hop_set else s_id
            if cand.startswith("cls:"):
                cn = cand[4:]
                if cn not in direct_dependents and cn not in second_hop_dependents:
                    second_hop_dependents.append(cn)

    all_affected_classes = list(dict.fromkeys(
        ([base_ctx["primary_class"]["class_name"]] if base_ctx.get("primary_class") else [])
        + direct_dependents[:20]
        + second_hop_dependents[:12]
    ))

    affected_modules: list[str] = []
    gradle_cmds: list[str] = list(base_ctx["gradle_test_commands"][:1])
    affected_reqs: list[str] = list(base_ctx["gov_c"]["reqs"])

    if all_affected_classes:
        ph_c = ",".join("?" for _ in all_affected_classes)
        m_rows = conn.execute(
            f"SELECT class_name, module_name, governed_by_json, gradle_test_cmd FROM code_class_meta WHERE lower(project) = ? AND class_name IN ({ph_c})",
            (proj, *all_affected_classes),
        ).fetchall()
        rows_by_cls: dict[str, list[sqlite3.Row]] = {}
        for mr in m_rows:
            rows_by_cls.setdefault(str(mr["class_name"]), []).append(mr)
        # Pass 1: Add primary (`-common` preferred) module and test command per dependent in strict BFS hop order
        for cn in all_affected_classes:
            cls_rows = sorted(
                rows_by_cls.get(cn, []),
                key=lambda r: (0 if "common" in str(r["module_name"]) or "_R" not in str(r["module_name"]) else 1, str(r["module_name"])),
            )
            for idx_r, mr in enumerate(cls_rows):
                mod = str(mr["module_name"])
                if idx_r == 0 and mod and mod not in affected_modules:
                    affected_modules.append(mod)
                gcmd = str(mr["gradle_test_cmd"])
                if (
                    gcmd
                    and gcmd not in gradle_cmds
                    and "--tests" in gcmd
                    and "ArchitectureTest" not in gcmd
                    and "APIGuardTest" not in gcmd
                    and "CandidateGateTest" not in gcmd
                ):
                    gradle_cmds.append(gcmd)
                for g in json.loads(str(mr["governed_by_json"] or "[]")):
                    if str(g).upper().startswith("REQ-") and str(g).upper() not in affected_reqs:
                        affected_reqs.append(str(g).upper())
        # Pass 2: Append any secondary version-specific carrier modules
        for cn in all_affected_classes:
            for mr in rows_by_cls.get(cn, []):
                mod = str(mr["module_name"])
                if mod and mod not in affected_modules:
                    affected_modules.append(mod)
    for extra_cmd in base_ctx["gradle_test_commands"][1:]:
        if extra_cmd not in gradle_cmds:
            gradle_cmds.append(extra_cmd)

    elapsed_ms = round((time.perf_counter() - t0) * 1000.0, 3)
    return {
        "target": target,
        "found": base_ctx["found"],
        "primary_class": base_ctx["primary_class"],
        "direct_dependents": direct_dependents[:20],
        "second_hop_dependents": second_hop_dependents[:15],
        "affected_modules": affected_modules[:14],
        "affected_reqs": affected_reqs[:12],
        "governing_adrs": base_ctx["gov_c"]["adrs"],
        "safety_prohibitions": base_ctx["gov_c"]["safety_prohibitions"],
        "gradle_test_commands": gradle_cmds[:8],
        "elapsed_ms": elapsed_ms,
    }


def format_impact_markdown(imp: dict[str, Any]) -> str:
    if not imp.get("found"):
        return f"### AST Impact Analysis: `{imp['target']}` (not found)\n"
    lines: list[str] = [f"### AST Impact Analysis: `{imp['target']}`"]
    if imp.get("primary_class"):
        pc = imp["primary_class"]
        lines.append(f"- **Origin:** `{pc['path']}` (`{pc['module_name']}`, `{pc['dag_tier']}`)")
    if imp["affected_modules"]:
        lines.append("- **Affected Modules:** " + ", ".join(f"`{m}`" for m in imp["affected_modules"]))
    if imp["direct_dependents"]:
        lines.append("- **Direct Dependents (1-Hop):** " + ", ".join(f"`{c}`" for c in imp["direct_dependents"][:12]))
    if imp["second_hop_dependents"]:
        lines.append("- **Transitive Dependents (2-Hop):** " + ", ".join(f"`{c}`" for c in imp["second_hop_dependents"][:10]))
    if imp["affected_reqs"] or imp["governing_adrs"] or imp["safety_prohibitions"]:
        g_items = [f"`{x}`" for x in (imp["safety_prohibitions"] + imp["governing_adrs"][:6] + imp["affected_reqs"][:6])]
        lines.append("- **Affected Governance (`Gov(c)`):** " + ", ".join(g_items))
    if imp["gradle_test_commands"]:
        lines.append("- **Recommended Verification Commands:**")
        for cmd in imp["gradle_test_commands"][:5]:
            lines.append(f"  - `{cmd}`")
    return "\n".join(lines[:32]) + "\n"


def audit_corpus_searchability(
    conn: sqlite3.Connection,
    project: str = DEFAULT_PROJECT,
    limit: int = 12,
) -> dict[str, Any]:
    """
    Audits the repository AST and Javadoc corpus for searchability & governance gaps:
      1. `TIER_1_SPI_PURE` / `TIER_2_CORE_DOMAIN` interfaces or SPIs missing Javadoc capability
         summaries or `@governedBy` / `TRACEABILITY.md` governance (`Gov(c)`).
      2. High-fan-in classes (`IMPORTED_BY_CLASS` + `SPI_IMPLEMENTED_BY` >= 5) with empty `Gov(c)`.
    """
    t0 = time.perf_counter()
    proj = project.strip().lower()
    inbound_counts: dict[str, int] = {}
    for er in conn.execute(
        "SELECT src_symbol_id FROM ast_edges WHERE edge_kind IN ('SPI_IMPLEMENTED_BY', 'SUBCLASSED_BY', 'IMPORTED_BY_CLASS')"
    ).fetchall():
        cn = str(er[0]).split(":", 1)[-1]
        inbound_counts[cn] = inbound_counts.get(cn, 0) + 1

    rows = conn.execute(
        """
        SELECT m.class_name, m.package_name, m.path, m.module_name, m.dag_tier,
               m.governed_by_json, m.kind, c.summary
        FROM code_class_meta m
        LEFT JOIN code_sections c ON c.path = m.path AND lower(c.project) = lower(m.project)
        WHERE lower(m.project) = ?
        """,
        (proj,),
    ).fetchall()

    spi_missing_javadoc_or_gov: list[dict[str, Any]] = []
    high_fan_in_missing_gov: list[dict[str, Any]] = []

    for r in rows:
        cn = str(r["class_name"])
        pkg = str(r["package_name"] or "")
        tier = str(r["dag_tier"] or "")
        kind = str(r["kind"] or "")
        govs = json.loads(str(r["governed_by_json"] or "[]"))
        summary = str(r["summary"] or "").strip()
        default_summary = f"{pkg}.{cn}" if pkg else cn
        has_real_javadoc = bool(summary and summary != default_summary and len(summary) > len(default_summary) + 12)
        has_in_file_gov_tag = "Gov:" in summary
        fan_in = inbound_counts.get(cn, 0)

        if tier == "TIER_1_SPI_PURE" and kind == "INTERFACE" and (not has_real_javadoc or not has_in_file_gov_tag):
            spi_missing_javadoc_or_gov.append({
                "class_name": cn,
                "module_name": str(r["module_name"]),
                "path": str(r["path"]),
                "fan_in": fan_in,
                "has_javadoc": has_real_javadoc,
                "has_in_file_gov_tag": has_in_file_gov_tag,
                "gov_count": len(govs),
            })
        elif fan_in >= 5 and not govs and tier in ("TIER_1_SPI_PURE", "TIER_2_CORE_DOMAIN"):
            high_fan_in_missing_gov.append({
                "class_name": cn,
                "module_name": str(r["module_name"]),
                "dag_tier": tier,
                "path": str(r["path"]),
                "fan_in": fan_in,
                "has_javadoc": has_real_javadoc,
            })

    spi_missing_javadoc_or_gov.sort(key=lambda x: (-x["fan_in"], x["class_name"]))
    high_fan_in_missing_gov.sort(key=lambda x: (-x["fan_in"], x["class_name"]))

    elapsed_ms = round((time.perf_counter() - t0) * 1000.0, 3)
    return {
        "tier1_spi_missing_javadoc_or_gov_count": len(spi_missing_javadoc_or_gov),
        "tier1_spi_missing_javadoc_or_gov": spi_missing_javadoc_or_gov[:limit],
        "high_fan_in_missing_gov_count": len(high_fan_in_missing_gov),
        "high_fan_in_missing_gov": high_fan_in_missing_gov[:limit],
        "elapsed_ms": elapsed_ms,
    }


def format_audit_markdown(aud: dict[str, Any]) -> str:
    lines: list[str] = [
        "### AST Corpus Searchability & Governance Audit (`ADR-115`)",
        f"- **Tier 1 SPI Interfaces Missing Javadoc or `@governedBy`:** `{aud['tier1_spi_missing_javadoc_or_gov_count']}`",
    ]
    for item in aud.get("tier1_spi_missing_javadoc_or_gov", [])[:8]:
        lines.append(
            f"  - `{item['class_name']}` (`{item['module_name']}`, `{item['path']}`) — "
            f"fan_in={item['fan_in']}, javadoc={item['has_javadoc']}, governed_by_tag={item['has_in_file_gov_tag']}, inferred_gov={item['gov_count']}"
        )
    lines.append(
        f"- **High-Fan-In Tier 1/2 Classes Missing `Gov(c)` (`@governedBy` / `TRACEABILITY.md`):** `{aud['high_fan_in_missing_gov_count']}`"
    )
    for item in aud.get("high_fan_in_missing_gov", [])[:8]:
        lines.append(
            f"  - `{item['class_name']}` (`{item['module_name']}`, `{item['path']}`) — "
            f"fan_in={item['fan_in']}, javadoc={item['has_javadoc']}"
        )
    return "\n".join(lines[:32]) + "\n"


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="repo_ast.py",
        description="Developer AST, ADR-115 Gov(c) Neighborhood, Impact & Anti-Redundancy Search CLI.",
    )
    parser.add_argument(
        "--db",
        type=pathlib.Path,
        default=DEFAULT_DB_PATH,
        help="Path to SQLite AST index (default: corpus-atlas/generated/doc-index.sqlite)",
    )
    parser.add_argument(
        "--repo-root",
        type=pathlib.Path,
        default=REPO_ROOT,
        help="Repository root directory (default: repo root)",
    )
    parser.add_argument(
        "--project",
        default=DEFAULT_PROJECT,
        help="Project domain slug (default: leafrtp)",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Emit structured JSON output",
    )
    parser.add_argument(
        "--no-auto-sync",
        action="store_true",
        help="Skip automatic pre-query incremental file_manifest check",
    )

    sub = parser.add_subparsers(dest="subcommand", required=True)

    p_ctx = sub.add_parser("context", help="Resolve ADR-115 Gov(c) neighborhood for a class, SPI, file, REQ-*, ADR-*, or config key")
    p_ctx.add_argument("target", help="Symbol, class name, file path, REQ-*, ADR-*, or config key")

    p_search = sub.add_parser("search", help="Hybrid FTS5 + AST capability search with Tier 1/2 SPI anti-redundancy ranking")
    p_search.add_argument("query", help="Natural-language capability or intent query")
    p_search.add_argument("--limit", type=int, default=8, help="Max results to return (default: 8)")

    p_imp = sub.add_parser("impact", help="Compute downstream dependents, affected REQ-* rows, and Gradle test commands")
    p_imp.add_argument("target", help="Class name, SPI name, file path, or REQ-* ID")

    p_sync = sub.add_parser("sync", help="Incrementally refresh dirty files via file_manifest (<150ms for single-file edits)")
    p_sync.add_argument("--check", action="store_true", help="Only report dirty files without modifying the index")

    p_aud = sub.add_parser("audit", help="Audit Tier 1/2 SPIs and high-fan-in classes for missing Javadoc summaries or ADR-115 Gov(c) links")
    p_aud.add_argument("--limit", type=int, default=12, help="Max items per audit category (default: 12)")

    p_mcp = sub.add_parser("mcp", help="Run persistent stdio JSON-RPC 2.0 MCP server for corpus-atlas")
    p_mcp.add_argument("--sync-coalesce-ms", type=float, default=0.0, help="Coalescing window in ms between stat() checks")

    p_off = sub.add_parser("offload", help="Dispatch or poll priority-queued local GPU tasks (P0/P1/P2)")
    p_off.add_argument("action", choices=["enqueue", "status", "critique_diff", "run_once"], help="Offload queue action")
    p_off.add_argument("--task-type", default="", help="Task type (translate-bundle, translate-doc, critique-diff, backfill-javadoc)")
    p_off.add_argument("--target", default="", help="Locale code, doc path, diff target, class filter, or job_id")
    p_off.add_argument("--priority", type=int, choices=[0, 1, 2], default=None, help="Priority override (0=P0, 1=P1, 2=P2)")
    p_off.add_argument("--queue-db", type=pathlib.Path, default=None, help="Optional override path for llm-queue.sqlite")

    return parser


def query_offload(
    conn: sqlite3.Connection,
    action: str,
    task_type: str = "",
    target: str = "",
    priority: int | None = None,
    repo_root: pathlib.Path = REPO_ROOT,
    queue_db_path: pathlib.Path | None = None,
    out_dir: pathlib.Path | None = None,
    http_post: Any = None,
    payload_extra: dict[str, Any] | None = None,
) -> dict[str, Any]:
    import local_llm_queue as llq

    resolved_db = queue_db_path or (repo_root / "scripts" / "out" / "llm-queue.sqlite")
    resolved_out = out_dir or resolved_db.parent
    return llq.execute_offload_action(
        action=action,
        task_type=task_type,
        target=target,
        priority=priority,
        db_path=resolved_db,
        out_dir=resolved_out,
        repo_root=repo_root,
        ast_conn=conn,
        http_post=http_post,
        payload_extra=payload_extra,
    )


def format_offload_markdown(res: dict[str, Any]) -> str:
    md = str(res.get("markdown") or "").strip()
    lines = md.splitlines()[:40] if md else ["### Local LLM Priority Queue Digest"]
    return "\n".join(lines) + "\n"


def format_sync_markdown(res: dict[str, Any], check_only: bool = False) -> str:
    mode_lbl = "Check" if check_only else "Sync"
    lines = [
        f"### AST Incremental {mode_lbl}: {res['dirty_count']} dirty, "
        f"{res['reindexed']} reindexed in {res['elapsed_ms']} ms"
    ]
    for df in res.get("dirty_files", [])[:15]:
        lines.append(f"- `{df}` ({res.get('actions', {}).get(df, 'modified')})")
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = build_arg_parser()
    args = parser.parse_args(argv)

    if args.subcommand == "mcp":
        from mcp_server import CorpusAtlasMcpServer, serve_stdio

        srv = CorpusAtlasMcpServer(
            db_path=args.db,
            repo_root=args.repo_root,
            project=args.project,
            auto_sync=not args.no_auto_sync,
            sync_coalesce_ms=getattr(args, "sync_coalesce_ms", 0.0),
        )
        return serve_stdio(srv)

    auto_sync = (not args.no_auto_sync) and (args.subcommand != "sync")
    conn, sync_info = ensure_db_connection(
        db_path=args.db,
        repo_root=args.repo_root,
        project=args.project,
        auto_sync=auto_sync,
    )
    try:
        if args.subcommand == "sync":
            res = sync_dirty_files(
                conn,
                repo_root=args.repo_root,
                project=args.project,
                check_only=args.check,
            )
            if args.json:
                print(json.dumps(res, indent=2))
            else:
                mode_lbl = "Check" if args.check else "Sync"
                print(
                    f"### AST Incremental {mode_lbl}: {res['dirty_count']} dirty, "
                    f"{res['reindexed']} reindexed in {res['elapsed_ms']} ms"
                )
                for df in res.get("dirty_files", [])[:15]:
                    print(f"- `{df}` ({res['actions'].get(df, 'modified')})")
            return 0

        if args.subcommand == "context":
            ctx = query_context(conn, args.target, project=args.project)
            ctx["sync"] = sync_info
            if args.json:
                print(json.dumps(ctx, indent=2))
            else:
                sys.stdout.write(format_context_markdown(ctx))
            return 0

        if args.subcommand == "search":
            s_res = query_search(conn, args.query, project=args.project, limit=args.limit)
            s_res["sync"] = sync_info
            if args.json:
                print(json.dumps(s_res, indent=2))
            else:
                sys.stdout.write(format_search_markdown(s_res))
            return 0

        if args.subcommand == "impact":
            imp = query_impact(conn, args.target, project=args.project)
            imp["sync"] = sync_info
            if args.json:
                print(json.dumps(imp, indent=2))
            else:
                sys.stdout.write(format_impact_markdown(imp))
            return 0

        if args.subcommand == "audit":
            aud = audit_corpus_searchability(conn, project=args.project, limit=args.limit)
            aud["sync"] = sync_info
            if args.json:
                print(json.dumps(aud, indent=2))
            else:
                sys.stdout.write(format_audit_markdown(aud))
            return 0

        if args.subcommand == "offload":
            off = query_offload(
                conn,
                action=args.action,
                task_type=args.task_type,
                target=args.target,
                priority=args.priority,
                repo_root=args.repo_root,
                queue_db_path=args.queue_db,
            )
            off["sync"] = sync_info
            if args.json:
                print(json.dumps(off, indent=2))
            else:
                sys.stdout.write(format_offload_markdown(off))
            return 0

        return 1
    finally:
        conn.close()


if __name__ == "__main__":
    raise SystemExit(main())
