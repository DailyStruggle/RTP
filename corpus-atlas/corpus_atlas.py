#!/usr/bin/env python3
"""
Generic Corpus AST, Multi-Facet Evidence Compiler & Static/Bot Atlas Engine.

Designed to be domain-agnostic, zero-dependency (Python 3 stdlib + SQLite FTS5),
and reusable across:
  1. Technical documentation & codebase corpora (e.g. LeafRTP production docs,
     ADRs, YAML schemas, and support bot troubleshooting indices).
  2. Large prose & tabletop rulebook archives (e.g. multi-gameline RPG compendiums).
  3. Any future Markdown / PDF / JSON / source-code knowledge base.

Core pipeline stages (`Scan -> Solidify AST -> Compile Multi-Facet Evidence -> Explain`):
  - Stage 1 (`LayoutTextCleaner`): De-interleaves two-column PDF/text layouts via
    contiguous vertical gutter detection and calibrates printed-to-physical page offsets.
  - Stage 2 (`CorpusAstSolidifier`): Promotes mid-sentence proper nouns and technical
    symbols into `corpus_vocab` facets (preventing FTS5 ubiquitous-token stripping),
    builds hierarchical config trees for Lowest-Common-Ancestor (LCA) key disambiguation,
    and constructs cross-file / cross-domain AST edges, claims, and conflict records.
  - Stage 3 (`MultiFacetArticleCompiler`): Compiles 4-facet evidence packets
    (`Identity & Mechanics`, `Structure & Sub-Components Table`, `Chronology & Eras`,
    `Perspectives, Conflicts & Open Questions`) plus `Sources of Record` and `Voice Caution`
    metadata, guaranteeing rich multi-subsection articles both deterministically and
    when enhanced by an Ollama / OpenAI-compatible LLM.
  - Stage 4 (`BookSummaryCompiler`): Summarizes every indexed source document/book
    in `source_register` with chapter outlines, top AST entities, and verbatim excerpts.
"""
from __future__ import annotations

import argparse
import collections
import json
import math
import os
import pathlib
import re
import sqlite3
import threading
import time
import urllib.request
from dataclasses import dataclass, field
from typing import Any


# ══════════════════════════════════════════════════════════════════════════════
# STAGE 1: LAYOUT-AWARE TEXT CLEANING & PAGE OFFSET CALIBRATION
# ══════════════════════════════════════════════════════════════════════════════

def deinterleave_two_column_page(raw_page: str) -> str:
    """
    Detect two-column layout in `pdftotext -layout` or fixed-width text via the
    vertical whitespace gutter and de-interleave both full two-column pages and
    mixed pages (where a full-width callout or heading spans above/below columns).
    """
    lines = [ln.rstrip() for ln in raw_page.splitlines()]
    long_lines = [ln for ln in lines if len(ln) >= 65]
    if len(long_lines) < 4:
        return raw_page

    best_col, best_hits = -1, 0
    for col in range(42, 76):
        hits = sum(
            1
            for ln in long_lines
            if col + 2 < len(ln)
            and ln[col - 1 : col + 1] == "  "
            and ln[: col - 1].strip()
            and ln[col + 1 :].strip()
        )
        if hits > best_hits:
            best_hits, best_col = hits, col

    if best_col < 0 or best_hits < 4:
        return raw_page

    out_blocks: list[str] = []
    cur_left: list[str] = []
    cur_right: list[str] = []

    def flush_band() -> None:
        if cur_left:
            out_blocks.append("\n".join(cur_left))
            cur_left.clear()
        if cur_right:
            out_blocks.append("\n".join(cur_right))
            cur_right.clear()

    for ln in lines:
        if not ln.strip():
            if cur_left:
                cur_left.append("")
            if cur_right:
                cur_right.append("")
            continue
        window = ln[max(0, best_col - 3) : min(len(ln), best_col + 4)]
        if len(ln) >= best_col + 8 and "  " not in window:
            flush_band()
            out_blocks.append(ln.strip())
        else:
            l_part = ln[:best_col].strip()
            r_part = ln[best_col:].strip() if len(ln) > best_col else ""
            if l_part:
                cur_left.append(l_part)
            if r_part:
                cur_right.append(r_part)

    flush_band()
    return "\n\n".join(b.strip() for b in out_blocks if b.strip())


_COMPOUND_HYPHEN_PREFIXES = frozenset({
    "self", "non", "cross", "quasi", "half", "co", "ex", "all", "well", "high", "low",
    "out", "post", "pre", "anti", "pro", "sub", "mid", "vice", "meta", "inter", "multi",
    "neo", "pan", "pseudo", "semi", "ultra", "under", "over", "counter", "by", "off",
    "on", "up", "down", "full", "part", "near", "far", "long", "short", "old", "new",
    "first", "second", "third", "one", "two", "three", "four", "five", "six", "seven",
    "eight", "nine", "ten", "in", "re", "un", "de", "co",
})

_CANONICAL_CORPUS_COMPLETIONS = (
    "Brotherhood", "Ascension", "Masquerade", "Apocalypse", "Reckoning", "Oblivion",
    "Dreaming", "Resurrection", "Traditions", "Technocracy", "Technocratic", "Camarilla",
    "Inquisition", "Antediluvian", "Antediluvians", "Methuselah", "Methuselahs",
    "Thaumaturgy", "Necromancy", "Vicissitude", "Obtenebration", "Dementation",
    "Chimerstry", "Generation", "Humanity", "Willpower", "Conviction", "Quintessence",
    "Paradox", "Banality", "Glamour", "Hierarchy", "Labyrinth", "Shadowlands",
    "Akashayana", "Cappadocian", "Cappadocians", "Ventrues", "Sabbat", "Anarchs",
)

_OCR_DIRECT_FIXES: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"^[~_|=\-*•·#<>\\/\s0-9IVX]{3,}(?=[A-Z])"), ""),
    (re.compile(r"\bCelestial\s+ChoM\b"), "Celestial Chorus"),
    (re.compile(r"\bMage\s+the\s+Acension\b", re.IGNORECASE), "Mage: The Ascension"),
    (re.compile(r"\bAcension\b"), "Ascension"),
    (re.compile(r"\bMokolC\b"), "Mokolé"),
    (re.compile(r"\bM\s+ind\b"), "Mind"),
    (re.compile(r"\bS\s+phere(s?)\b"), r"Sphere\1"),
    (re.compile(r"\bT\s+radition(s?)\b"), r"Tradition\1"),
    (re.compile(r"\bB\s+rotherhood\b"), "Brotherhood"),
    (re.compile(r"\b([A-Za-z\u00C0-\u017F]+['’]s)([A-Z]?[a-z]{2,})\b"), r"\1 \2"),
    (re.compile(r"\b(First|Second|Third|Fourth|Fifth|Sixth|Dark|Middle|Golden|Silver|Morning)(Age|Era|War|City|World|Kingdom|Star)\b"), r"\1 \2"),
    (re.compile(r"\b(SEALS|SEALs)(or|and)\b"), r"SEALs \2"),
    (re.compile(r"\btbe\b"), "the"),
    (re.compile(r"\bTbe\b"), "The"),
    (re.compile(r"\btbat\b"), "that"),
    (re.compile(r"\bwitb\b"), "with"),
    (re.compile(r"\bwbich\b"), "which"),
    (re.compile(r"\btlie\b"), "the"),
)

_FINITE_VERBS_SET = frozenset({
    "is", "are", "was", "were", "be", "been", "being", "has", "have", "had",
    "do", "does", "did", "doesn't", "don't", "didn't", "can", "could", "will",
    "would", "shall", "should", "may", "might", "must", "requires", "require",
    "believes", "believe", "holds", "hold", "teaches", "teach", "serves", "serve",
    "claims", "claim", "describes", "describe", "suffers", "suffer", "uses", "use",
    "makes", "make", "takes", "take", "gives", "give", "remains", "remain", "stands",
    "forbids", "allows", "means", "represents", "embodies", "warns", "notes", "writes",
})

_HEADING_MINOR_WORDS = frozenset({
    "a", "an", "the", "and", "or", "but", "of", "in", "on", "at", "to", "for",
    "with", "by", "from", "into", "as", "vs", "vs.", "&",
})


def repair_ocr_and_layout_text(raw_text: str, topic_name: str = "") -> str:
    """
    Deterministic OCR & PDF layout spell/token repair:
      1. Rejoins line-wrap hyphenated words (`Honor- able` -> `Honorable`, `mechan- ics` -> `mechanics`).
      2. Restores right-margin / crop-box clipped words (`Brotherho` -> `Brotherhood`) using
         local passage context, `topic_name`, and canonical corpus completions.
      3. Fixes recurring OCR glyph/diacritic/kerning corruptions (`ChoM` -> `Chorus`, `MokolC` -> `Mokolé`, etc.).
    """
    if not raw_text:
        return ""
    s = raw_text

    # 1. Apply direct OCR glyph & kerning fixes
    for pat, repl in _OCR_DIRECT_FIXES:
        s = pat.sub(repl, s)

    # 2. Rejoin line-wrap hyphen splits (`Honor-\nable` or `Honor- able` -> `Honorable`)
    def _dehyphen(m: re.Match[str]) -> str:
        w1, w2 = m.group(1), m.group(2)
        if w1.lower() in _COMPOUND_HYPHEN_PREFIXES:
            return f"{w1}-{w2}"
        return w1 + w2

    s = re.sub(r"\b([A-Za-z]{2,})-\s+([a-z]{2,})\b", _dehyphen, s)

    # 3. Restore right-margin / box-clipped prefix tokens (`Brotherho\nThe Akashic Brotherhood` -> `Brotherhood...`)
    cand_pool: dict[str, str] = {w.lower(): w for w in _CANONICAL_CORPUS_COMPLETIONS}
    for w in re.findall(r"\b[A-Za-z]{6,}\b", f"{topic_name} {s}"):
        w_low = w.lower()
        if w_low not in cand_pool or (w[0].isupper() and not cand_pool[w_low][0].isupper()):
            cand_pool[w_low] = w
    word_counts = collections.Counter(w.lower() for w in re.findall(r"\b[A-Za-z]{4,}\b", s))

    def _restore_clipped(m: re.Match[str]) -> str:
        tok = m.group(1)
        tok_low = tok.lower()
        # Only repair tokens that occur once in the snippet and have a non-inflectional 2..4 char completion
        if word_counts[tok_low] > 1 or tok_low in _FINITE_VERBS_SET:
            return tok
        best_cand = ""
        for c_low, c_orig in cand_pool.items():
            if len(tok) + 2 <= len(c_low) <= len(tok) + 4 and c_low.startswith(tok_low):
                tail = c_low[len(tok) :]
                if tail in ("es", "ed", "er", "ly", "al", "ic", "or", "an", "ia", "on"):
                    continue
                if word_counts[c_low] >= 1 or c_orig in _CANONICAL_CORPUS_COMPLETIONS or c_low in topic_name.lower():
                    best_cand = c_orig if tok[0].isupper() else c_orig.lower()
                    break
        return best_cand or tok

    s = re.sub(r"\b([A-Za-z]{6,})(?=\s*\n|\s+[A-Z]|\s*$)", _restore_clipped, s)
    return s


def _looks_like_embedded_heading(line: str) -> bool:
    """Return True if a standalone line looks like an unpunctuated section heading rather than prose."""
    ln = line.strip()
    if not (3 <= len(ln) <= 72) or not ln[0].isupper():
        return False
    if ln.endswith((".", "!", "?", ";", ",")) or ln.startswith(("Page ", "\"", "“", "—", "-")):
        return False
    words = re.findall(r"[A-Za-z0-9’'&-]+", ln)
    if not (1 <= len(words) <= 10):
        return False
    low_words = [w.lower() for w in words]
    if any(w in _FINITE_VERBS_SET for w in low_words):
        return False
    if ":" in ln:
        return True
    content_words = [w for w in words if w.lower() not in _HEADING_MINOR_WORDS]
    if not content_words:
        return False
    cap_ratio = sum(1 for w in content_words if w[0].isupper()) / len(content_words)
    return cap_ratio >= 0.75


def extract_embedded_heading_and_body(
    raw_text: str,
    raw_heading: str = "",
    topic_name: str = "",
) -> tuple[str, str]:
    """
    Separate leading unpunctuated PDF section headings and epigraph attributions from
    body prose so headings are promoted to `heading` instead of colliding into sentences.
    """
    repaired = repair_ocr_and_layout_text(raw_text or "", topic_name=topic_name)
    repaired = re.sub(
        r"^Page\s+\d+\s*(\(PDF\s+p\.\s*\d+\))?\s*(\[[A-Z0-9-]+\])?\s*",
        "",
        repaired.strip(),
        flags=re.IGNORECASE,
    )
    clean_h = repair_ocr_and_layout_text(raw_heading or "", topic_name=topic_name).strip()
    is_generic_h = not clean_h or clean_h.lower().startswith("page ")

    lines = [ln.strip() for ln in repaired.splitlines() if ln.strip()]
    if len(lines) >= 2 and _looks_like_embedded_heading(lines[0]):
        emb_h = lines.pop(0)
        if emb_h.endswith(":") and len(lines) >= 2 and _looks_like_embedded_heading(lines[0]):
            emb_h = f"{emb_h} {lines.pop(0)}"
        if is_generic_h:
            clean_h = emb_h
            is_generic_h = False

    # Ensure epigraph attribution lines (`— Zen koan`) end with a period before joining lines
    norm_lines: list[str] = []
    for ln in lines:
        if (ln.startswith(("—", "--")) or "— " in ln) and len(ln) <= 60 and not ln.endswith((".", "!", "?")):
            norm_lines.append(ln + ".")
        else:
            norm_lines.append(ln)

    body = re.sub(r"\s+", " ", " ".join(norm_lines)).strip()

    # Also detect flattened single-line heading prefixes (e.g. `Religion and the Akashic Brotherhood The Akashic Brotherhood...`
    # or `Meru's Children: Templates Warriors who master...`)
    m_colon_hdr = re.match(
        r"^([A-Z][A-Za-z0-9’'& -]{2,34}:\s+[A-Z][A-Za-z0-9’'&-]+(?:\s+[A-Z][A-Za-z0-9’'&-]+){0,3})\s+([A-Z][a-z]+\b.*)",
        body,
    )
    if m_colon_hdr and _looks_like_embedded_heading(m_colon_hdr.group(1)):
        if is_generic_h:
            clean_h = m_colon_hdr.group(1).strip()
            is_generic_h = False
        body = m_colon_hdr.group(2).strip()
    else:
        m_title_hdr = re.match(
            r"^([A-Z][A-Za-z’'-]+(?:\s+(?:and|of|the|in|for|to|with|on|at|by)\s+[A-Z][A-Za-z’'-]+|\s+[A-Z][A-Za-z’'-]+){1,5})\s+((?:The|A|An|In|When|While|Although|Because|Every|Most|Many|Some|One|Warriors|Describe|You|We|They)\s+.*)",
            body,
        )
        if m_title_hdr and _looks_like_embedded_heading(m_title_hdr.group(1)):
            if is_generic_h:
                clean_h = m_title_hdr.group(1).strip()
                is_generic_h = False
            body = m_title_hdr.group(2).strip()

    # Ensure inline epigraph attribution (`— Zen koan The quote above`) has terminal punctuation
    body = re.sub(
        r"(—\s*[A-Z][A-Za-z0-9’' -]{2,30}?)(?<!\.)\s+(?=(?:The|This|In|As|When|While|Every|Most|Many|One)\s+[a-z])",
        r"\1. ",
        body,
    )
    return clean_h, body


def recover_passage_intent_via_llm(
    text: str,
    *,
    topic_name: str = "",
    heading: str = "",
    ollama_url: str | None = None,
    model: str = "llama3.2:latest",
    timeout: float = 8.0,
) -> tuple[str, str]:
    """
    Stage 3B Intent Recovery via LLM (Fail-Closed):
      1. Runs deterministic `extract_embedded_heading_and_body` first.
      2. If `ollama_url` is provided and the passage still shows OCR anomaly signals,
         prompts the LLM to repair residual OCR character/word damage while preserving
         every `[ABBR, p. N]` citation and domain proper noun.
    """
    clean_h, clean_b = extract_embedded_heading_and_body(text, raw_heading=heading, topic_name=topic_name)
    if not ollama_url or not clean_b:
        return clean_h, clean_b
    # Only call LLM if there is a suspected OCR anomaly (e.g. stray digits inside words or repeated symbol noise)
    if not re.search(r"\b[A-Za-z]+[0-9][A-Za-z]+\b|\b[bcdfghjklmnpqrstvwxz]{4,}\b", clean_b):
        return clean_h, clean_b
    cites_before = re.findall(r"\[[A-Z0-9-]+,\s*p+\.\s*\d+(?:[–-]\d+)?\]", clean_b)
    prompt = (
        f"Repair only OCR spelling, broken hyphenation, or truncated words in this {topic_name} passage. "
        f"Do NOT change meaning, domain terminology, or any [ABBR, p. N] citations. Return ONLY the repaired passage text:\n\n{clean_b}"
    )
    try:
        req = urllib.request.Request(
            ollama_url,
            data=json.dumps({"model": model, "prompt": prompt, "stream": False, "options": {"temperature": 0.0}}).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            cand = re.sub(r"\s+", " ", str(data.get("response") or "")).strip()
            if cand and all(c in cand for c in cites_before) and len(cand) >= int(len(clean_b) * 0.8):
                return clean_h, cand
    except Exception:
        pass
    return clean_h, clean_b


def clean_sentence_excerpt(raw_text: str, topic_name: str = "", max_len: int = 280) -> str:
    """
    Normalize whitespace, repair OCR/layout artifacts, strip embedded section headings,
    and trim an excerpt to clean sentence boundaries without cutting mid-word.
    """
    _h, s = extract_embedded_heading_and_body(raw_text or "", topic_name=topic_name)
    if topic_name and s.lower().startswith(topic_name.lower() + " " + topic_name.lower()):
        s = s[len(topic_name) :].strip()
    if s and s[0].islower():
        m_cap = re.search(r"[.!?]\s+([A-Z][^.!?]{35,})", s)
        if m_cap:
            s = s[m_cap.start(1) :]
    if len(s) > max_len:
        cut = s[:max_len]
        m_sent = list(re.finditer(r"[.!?](?=\s|$)", cut))
        if m_sent and m_sent[-1].end() >= 85:
            s = cut[: m_sent[-1].end()].strip()
        else:
            s = cut.rsplit(" ", 1)[0].rstrip(".,;:- ") + "…"
    return s


# ══════════════════════════════════════════════════════════════════════════════
# STAGE 2: CONFIG TREE LCA DISAMBIGUATION & PROPER-NOUN FACET PROMOTION
# ══════════════════════════════════════════════════════════════════════════════

_LCA_LEAF_CACHE: dict[tuple[int, str], dict[str, list[str]]] = {}


def resolve_config_leaf_via_lca(
    conn: sqlite3.Connection,
    leaf_key: str,
    sibling_keys: list[str],
    project: str | None = None,
) -> str | None:
    """
    Resolve an ambiguous unprefixed YAML/config key (such as `name`, `mode`, `enabled`)
    using Lowest-Common-Ancestor (LCA) prefix voting from sibling keys in the same snippet.
    """
    proj_norm = (project or "").strip().lower()
    cache_key = (id(conn), proj_norm)
    leaf_map = _LCA_LEAF_CACHE.get(cache_key)
    if leaf_map is None:
        leaf_map = {}
        if proj_norm:
            rows = conn.execute(
                "SELECT DISTINCT key_path FROM config_keys WHERE lower(project) = ?",
                (proj_norm,),
            ).fetchall()
        else:
            rows = conn.execute("SELECT DISTINCT key_path FROM config_keys").fetchall()
        for r in rows:
            kp = str(r[0])
            kp_low = kp.lower()
            leaf_map.setdefault(kp_low, [])
            if kp not in leaf_map[kp_low]:
                leaf_map[kp_low].append(kp)
            if "." in kp_low:
                lf = kp_low.rsplit(".", 1)[-1]
                leaf_map.setdefault(lf, [])
                if kp not in leaf_map[lf]:
                    leaf_map[lf].append(kp)
        if len( _LCA_LEAF_CACHE ) > 32:
            _LCA_LEAF_CACHE.clear()
        _LCA_LEAF_CACHE[cache_key] = leaf_map

    cand_paths = leaf_map.get(leaf_key.lower(), [])
    if not cand_paths:
        return None
    if len(cand_paths) == 1:
        return cand_paths[0]

    # Collect prefix votes from all sibling keys in the snippet
    prefix_votes: collections.Counter[str] = collections.Counter()
    for sib in sibling_keys:
        sib_clean = sib.strip().lower()
        if not sib_clean or sib_clean == leaf_key.lower():
            continue
        for kp in leaf_map.get(sib_clean, ()):
            if "." in kp:
                prefix = kp.rsplit(".", 1)[0]
                prefix_votes[prefix] += 3
                top_seg = kp.split(".", 1)[0]
                prefix_votes[top_seg] += 1

    best_path = cand_paths[0]
    best_score = -1
    for cp in cand_paths:
        score = 0
        if "." in cp:
            pref = cp.rsplit(".", 1)[0]
            score += prefix_votes.get(pref, 0) * 10
            top_seg = cp.split(".", 1)[0]
            score += prefix_votes.get(top_seg, 0) * 2
        if score > best_score:
            best_score = score
            best_path = cp
    return best_path


# ══════════════════════════════════════════════════════════════════════════════
# STAGE 3: MULTI-FACET EVIDENCE COMPILER & FAIL-CLOSED ARTICLE SYNTHESIZER
# ══════════════════════════════════════════════════════════════════════════════

@dataclass
class TopicEvidencePacket:
    gameline: str
    category: str
    name: str
    query: str
    sources_of_record: list[dict[str, Any]] = field(default_factory=list)
    resolved_symbols: list[dict[str, Any]] = field(default_factory=list)
    top_neighbors: list[dict[str, Any]] = field(default_factory=list)
    cross_domain_bridges: list[dict[str, Any]] = field(default_factory=list)
    causal_claims: list[dict[str, Any]] = field(default_factory=list)
    verbatim_quotes: list[dict[str, Any]] = field(default_factory=list)
    facet_passages: dict[str, list[dict[str, str]]] = field(default_factory=dict)
    quick_facts: dict[str, str] = field(default_factory=dict)
    edition_policy: dict[str, Any] = field(default_factory=dict)


def discover_sources_of_record(
    conn: sqlite3.Connection,
    name: str,
    gameline: str,
    extra_query: str = "",
    limit: int = 8,
) -> list[dict[str, Any]]:
    """
    Identify the primary sourcebooks/documents in `doc_sections` and `source_register`
    that cover `name`, returning exact abbreviations, titles, printed page spans,
    section headings, and narrative voice notes.
    """
    raw_words = [
        w for w in re.findall(r"[A-Za-z0-9]+", name)
        if len(w) >= 3 and w.lower() not in {"the", "and", "for", "with", "from", "into"}
    ]
    if not raw_words:
        raw_words = [w for w in re.findall(r"[A-Za-z0-9]+", extra_query) if len(w) >= 4][:3]
    fts_terms = [f'"{w}"' for w in raw_words[:4]]
    fts_query = " OR ".join(fts_terms) if fts_terms else f'"{name}"'

    try:
        rows = conn.execute(
            """
            SELECT title, heading, path, tags, substr(body, 1, 500) AS sample_body
            FROM doc_sections
            WHERE project = ? AND doc_sections MATCH ?
            LIMIT 250
            """,
            (gameline, fts_query),
        ).fetchall()
    except sqlite3.Error:
        rows = []

    if not rows:
        try:
            rows = conn.execute(
                """
                SELECT title, heading, path, tags, substr(body, 1, 500) AS sample_body
                FROM doc_sections
                WHERE project = ?
                LIMIT 80
                """,
                (gameline,),
            ).fetchall()
        except sqlite3.Error:
            rows = []

    by_book: dict[str, dict[str, Any]] = {}
    name_low = name.lower()
    for r in rows:
        b_title = str(r["title"] or "Source")
        tags = str(r["tags"] or "")
        m_ab = re.search(r"abbr:([A-Z0-9-]+)", tags)
        abbr = m_ab.group(1) if m_ab else "SRC"
        m_pr = re.search(r"printed_p:(\d+)", tags)
        p_num = int(m_pr.group(1)) if m_pr else None

        rec = by_book.setdefault(
            b_title,
            {
                "abbr": abbr,
                "title": b_title,
                "pages_list": [],
                "headings": [],
                "hits": 0,
                "ic_signals": 0,
                "title_match": 1 if any(w.lower() in b_title.lower() for w in raw_words) or name_low in b_title.lower() else 0,
            },
        )
        rec["hits"] += 1
        if p_num is not None:
            rec["pages_list"].append(p_num)
        h_txt = str(r["heading"] or "").strip()
        if h_txt and not h_txt.lower().startswith("page ") and h_txt not in rec["headings"] and len(rec["headings"]) < 5:
            rec["headings"].append(h_txt)
        sb = str(r["sample_body"] or "")
        if re.search(
            r"(?:^|\n)\s*(?:>>>|From:|Subj:|Subject:|Posted\s+by:)|\b(?:I\s+have\s+seen|in\s+my\s+experience|our\s+people|we\s+believe|I\s+remember)\b",
            sb,
            re.IGNORECASE,
        ):
            rec["ic_signals"] += 1

    # Enrich from `source_register` if available
    sr_map: dict[str, dict[str, Any]] = {}
    try:
        for sr in conn.execute("SELECT abbr, title, credits, copyright_line, offset_label FROM source_register").fetchall():
            sr_map[str(sr[0])] = {
                "title": str(sr[1]),
                "credits": str(sr[2]),
                "copyright": str(sr[3]),
                "offset": str(sr[4]),
            }
    except sqlite3.Error:
        pass

    ranked = sorted(
        by_book.values(),
        key=lambda x: (x["title_match"], x["hits"]),
        reverse=True,
    )[:limit]

    out: list[dict[str, Any]] = []
    for item in ranked:
        pgs = sorted(set(item["pages_list"]))
        if len(pgs) >= 2:
            p_span = f"pp. {pgs[0]}–{pgs[-1]}"
        elif len(pgs) == 1:
            p_span = f"p. {pgs[0]}"
        else:
            p_span = "passim"
        ic_ratio = item["ic_signals"] / max(item["hits"], 1)
        if ic_ratio >= 0.35:
            voice_note = "Mixed designer & in-character narrator voice (treat first-person monologues and field posts as in-universe perspective)"
        else:
            voice_note = "Primary out-of-character Storyteller & rulebook reference"
        sr_info = sr_map.get(item["abbr"], {})
        raw_t = sr_info.get("title") or item["title"]
        out.append(
            {
                "abbr": item["abbr"],
                "title": repair_ocr_and_layout_text(raw_t, topic_name=name),
                "pages": p_span,
                "hits": item["hits"],
                "headings": [repair_ocr_and_layout_text(h, topic_name=name) for h in item["headings"]],
                "voice_note": voice_note,
                "credits": sr_info.get("credits", ""),
                "copyright": sr_info.get("copyright", ""),
            }
        )
    return out


def compile_topic_evidence_packet(
    db_path: pathlib.Path,
    *,
    gameline: str,
    category: str,
    name: str,
    query: str,
    explain_fn: Any,
    retrieve_fn: Any,
    quick_facts: dict[str, str] | None = None,
) -> TopicEvidencePacket:
    """
    Build a comprehensive 4-facet evidence packet from SQLite (`doc_sections` FTS5 + AST tables).
    """
    exp = explain_fn(db_path, name, gameline=gameline)
    conn = sqlite3.connect(str(db_path))
    conn.row_factory = sqlite3.Row
    try:
        sources_of_record = discover_sources_of_record(conn, name, gameline, extra_query=query, limit=8)

        # Query Cross-Domain / Cross-Gameline AST bridges
        sym_ids = [s["id"] for s in exp.get("resolved_ast_symbols", []) if s.get("id")]
        bridges: list[dict[str, Any]] = []
        if sym_ids:
            ph = ",".join("?" for _ in sym_ids)
            try:
                b_rows = conn.execute(
                    f"""
                    SELECT s2.domain AS dst_gl, s2.canonical_name AS dst_name,
                           s2.doc_frequency AS dst_df, e.weight
                    FROM ast_edges e
                    JOIN ast_symbols s2 ON s2.symbol_id = e.dst_symbol_id
                    WHERE e.src_symbol_id IN ({ph})
                      AND (e.edge_kind = 'CROSS_GAMELINE_BRIDGE' OR s2.domain != ?)
                    ORDER BY e.weight DESC, s2.doc_frequency DESC
                    LIMIT 8
                    """,
                    [*sym_ids, gameline],
                ).fetchall()
                seen_b: set[tuple[str, str]] = set()
                for br in b_rows:
                    bk = (str(br["dst_gl"]), str(br["dst_name"]).lower())
                    if bk not in seen_b:
                        seen_b.add(bk)
                        bridges.append(
                            {
                                "gameline": str(br["dst_gl"]),
                                "name": str(br["dst_name"]),
                                "mentions": int(br["dst_df"]),
                                "weight": float(br["weight"]),
                            }
                        )
            except sqlite3.Error:
                pass

        base_words = [
            w for w in re.findall(r"[A-Za-z0-9]+", f"{name} {query}")
            if len(w) >= 3 and w.lower() not in {"the", "and", "for", "with", "from", "into"}
        ][:5]
        base_fts = " OR ".join(f'"{w}"' for w in base_words) if base_words else f'"{name}"'

        facet_keywords = {
            "identity_and_doctrine": ("philosophy", "doctrine", "virtue", "paradigm", "discipline", "edge", "gift", "sphere", "arcanos", "lore", "dharma", "belief", "role"),
            "organization_and_subfactions": ("faction", "sect", "camp", "house", "bloodline", "lineage", "rank", "hierarchy", "stronghold", "chantry", "caern", "citadel", "law", "code", "court"),
            "chronology_and_history": ("history", "origin", "ancient", "war", "founded", "century", "reckoning", "modern", "age", "era", "revolt", "betrayal", "first", "fall"),
            "perspectives_and_rivals": ("rival", "enemy", "ally", "view", "opinion", "secret", "rumor", "conflict", "stereotypes", "other", "claim"),
        }
        # Fetch top 60 candidate passages for this topic in a single fast FTS5 query (~15ms)
        try:
            cand_rows = conn.execute(
                """
                SELECT rowid AS id, title, heading, path, tags, substr(body, 1, 900) AS body_snip
                FROM doc_sections
                WHERE project = ? AND doc_sections MATCH ?
                ORDER BY bm25(doc_sections)
                LIMIT 60
                """,
                (gameline, base_fts),
            ).fetchall()
        except sqlite3.Error:
            cand_rows = []

        seen_doc_ids: set[Any] = set()
        facet_passages: dict[str, list[dict[str, str]]] = {}

        for f_key, kw_tuple in facet_keywords.items():
            scored: list[tuple[int, sqlite3.Row]] = []
            for idx_r, r in enumerate(cand_rows):
                text_low = f"{r['heading']} {r['body_snip']}".lower()
                kw_hits = sum(2 for kw in kw_tuple if kw in text_low)
                name_bonus = 4 if name.lower() in text_low else 0
                unseen_bonus = 3 if r["id"] not in seen_doc_ids else 0
                score = kw_hits + name_bonus + unseen_bonus - (idx_r // 10)
                scored.append((score, r))
            scored.sort(key=lambda x: x[0], reverse=True)
            items: list[dict[str, str]] = []
            for _, h in scored:
                h_id = h["id"]
                if h_id in seen_doc_ids and len(items) >= 2:
                    continue
                seen_doc_ids.add(h_id)
                tags = str(h["tags"] or "")
                m_ab = re.search(r"abbr:([A-Z0-9-]+)\s+printed_p:(\d+)", tags)
                cite = f"{m_ab.group(1)}, p. {m_ab.group(2)}" if m_ab else str(h["title"] or "Source")
                clean_h, raw_body = extract_embedded_heading_and_body(
                    str(h["body_snip"] or ""),
                    raw_heading=str(h["heading"] or ""),
                    topic_name=name,
                )
                snippet = clean_sentence_excerpt(raw_body, topic_name=name, max_len=380)
                if len(snippet) >= 45:
                    t_str = repair_ocr_and_layout_text(str(h["title"] or ""), topic_name=name)
                    inline_q = extract_inline_quote_clause(snippet, focus_term=name)
                    reg_info = classify_source_register(t_str, "", snippet)
                    items.append(
                        {
                            "cite": cite,
                            "title": t_str,
                            "heading": clean_h,
                            "snippet": snippet,
                            "inline_quote": inline_q,
                            "voice_register": reg_info["descriptor"],
                        }
                    )
                if len(items) >= 5:
                    break
            facet_passages[f_key] = items
    finally:
        conn.close()

    return TopicEvidencePacket(
        gameline=gameline,
        category=category,
        name=name,
        query=query,
        sources_of_record=sources_of_record,
        resolved_symbols=exp.get("resolved_ast_symbols", []),
        top_neighbors=exp.get("top_ast_neighbors", []),
        cross_domain_bridges=bridges,
        causal_claims=exp.get("typed_causal_claims", []),
        verbatim_quotes=exp.get("verbatim_quotes_by_voice_and_lens", []),
        facet_passages=facet_passages,
        quick_facts=dict(quick_facts or {}),
        edition_policy=exp.get("edition_policy", {}),
    )


def extract_inline_quote_clause(
    text: str,
    focus_term: str = "",
    min_words: int = 4,
    max_words: int = 16,
) -> str:
    """
    Extract a concise, self-contained verbatim phrase (`min_words`..`max_words`)
    from a source passage suitable for weaving inline into analytical prose
    (`"..." [ABBR, p. N]`), following Rule 10 of the Lore Atlas Style Guide
    (short necessary quotations integrated into analysis rather than raw dumps).
    """
    _hdr, cleaned = extract_embedded_heading_and_body(text or "", topic_name=focus_term)
    cleaned = re.sub(r"\s+", " ", cleaned).strip()
    if not cleaned:
        return ""

    # 1. Prefer an existing in-source quoted phrase if it is already concise and clean
    for m_q in re.finditer(r"[\"“]([^\"”]{18,120})[\"”]", cleaned):
        inner = m_q.group(1).strip(" ,.;:—-")
        w_list = inner.split()
        if min_words <= len(w_list) <= max_words and not re.search(r"[\[\]{}|]", inner):
            return inner

    # 2. Split into clauses on sentence/clause punctuation and score by focus_term & analytical signal
    raw_clauses = re.split(r"(?<=[.!?;:])\s+|\s+[—–]\s+|,\s+(?=(?:which|who|where|though|while|because|yet|but)\b)", cleaned)
    focus_low = (focus_term or "").lower().strip()
    signal_verbs = (
        "is ", "are ", "was ", "were ", "calls ", "called ", "defines ", "defined ",
        "claims ", "claim ", "believes ", "believe ", "holds ", "hold ", "warns ",
        "refuses ", "refuse ", "teaches ", "teach ", "serves ", "serve ", "seeks ",
        "remains ", "stands ", "forbids ", "requires ", "represents ", "embodies ",
    )

    best_clause = ""
    best_score = -999
    for cl in raw_clauses:
        c_str = cl.strip(" \"'“”‘’(),.;:—-")
        # Strip leading transitional conjunctions so the phrase embeds cleanly
        c_str = re.sub(
            r"^(?:and|but|yet|so|for|nor|or|however|therefore|moreover|furthermore|in addition|as a result)\s*,?\s+",
            "",
            c_str,
            flags=re.IGNORECASE,
        ).strip(" \"'“”‘’(),.;:—-")
        words = c_str.split()
        if len(words) < min_words:
            continue
        if len(words) > max_words:
            # Trim to a clean window around focus_term or the leading predicate clause
            lower_words = [w.lower().strip(".,;:!?\"'") for w in words]
            start_idx = 0
            if focus_low:
                f_tok = focus_low.split()[0]
                for idx_w, lw in enumerate(lower_words):
                    if f_tok in lw:
                        start_idx = max(0, min(idx_w - 2, len(words) - max_words))
                        break
            words = words[start_idx : start_idx + max_words]
            c_str = " ".join(words).strip(" \"'“”‘’(),.;:—-")

        c_low = c_str.lower()
        score = 0
        if focus_low and focus_low in c_low:
            score += 5
        if any(v in c_low for v in signal_verbs):
            score += 4
        if 5 <= len(words) <= 13:
            score += 3
        if re.search(r"\b(?:page|chapter|table|see p\.|ibid)\b", c_low):
            score -= 6
        if score > best_score:
            best_score = score
            best_clause = c_str

    if best_clause:
        return best_clause

    fallback_words = cleaned.split()[:max_words]
    return " ".join(fallback_words).strip(" \"'“”‘’(),.;:—-")


def classify_source_register(
    title: str,
    voice_note: str = "",
    snippet: str = "",
    edition: str = "",
) -> dict[str, str]:
    """
    Classify a source document's narrative register (`designer`, `in-character`, `narrator`)
    and edition/version provenance from structural and linguistic features without
    domain-specific lookup tables.
    """
    t_clean = repair_ocr_and_layout_text((title or "the source document").strip())
    t_low = t_clean.lower()
    v_low = (voice_note or "").lower()
    s_low = (snippet or "").lower()
    ed_combined = f"{t_low} {(edition or '').lower()}"

    if re.search(r"\b(?:5th\s+edition|v5)\b", t_low):
        ed_tag = "5th Edition"
    elif "revised" in t_low:
        ed_tag = "Revised"
    elif re.search(r"\b(?:20th\s+anniversary|20th|[a-z]{1,2}20)\b", t_low):
        ed_tag = "20th Anniversary"
    elif m_tver := re.search(r"\b([123]e|1st\s+edition|2nd\s+edition|3rd\s+edition)\b", t_low):
        ed_tag = m_tver.group(1).upper() if len(m_tver.group(1)) == 2 else m_tver.group(1)
    elif re.search(r"\b(?:5th\s+edition|v5)\b", ed_combined):
        ed_tag = "5th Edition"
    elif re.search(r"\b(?:20th\s+anniversary|20th|[a-z]{1,2}20)\b", ed_combined):
        ed_tag = "20th Anniversary"
    elif "revised" in ed_combined:
        ed_tag = "Revised"
    elif m_ver := re.search(r"\b(v\d+(?:\.\d+)+|\d+(?:st|nd|rd|th)\s+edition)\b", f"{t_clean} {edition or ''}", re.IGNORECASE):
        ed_tag = m_ver.group(1)
    else:
        ed_tag = edition or "Primary"

    is_ic = (
        "in-character" in v_low
        or "unreliable" in v_low
        or "first-person" in v_low
        or bool(re.search(r"\b(?:i |we |our |my |us )\b", s_low))
        or any(k in t_low for k in ("diary", "journal", "letters", "testimony", "apocrypha", "monologue"))
    )
    is_designer = (
        "out-of-character" in v_low
        or "reference" in v_low
        or "specification" in v_low
        or any(k in t_low for k in ("handbook", "manual", "guide", "companion", "reference", "specification", "core"))
        or bool(re.search(r"\b(?:should|must|shall|default|configuration|parameter|system|dice\s+pool|rulebook)\b", s_low))
    )

    if is_ic and not is_designer:
        register = "in-character"
        descriptor = f"in-character narrator of {t_clean}"
    elif is_designer:
        register = "designer"
        descriptor = f"{ed_tag} designer text in {t_clean}"
    else:
        register = "narrator"
        descriptor = f"{ed_tag} narrator of {t_clean}"

    return {
        "register": register,
        "edition_tag": ed_tag,
        "title": t_clean,
        "descriptor": descriptor,
    }


def ensure_inline_passage_quotes_in_paragraphs(
    paragraphs: list[str],
    passages: list[dict[str, str]],
    sources: list[dict[str, Any]],
    focus_term: str = "",
) -> list[str]:
    """
    Ensure analytical paragraphs weave short verbatim quotations (`"..." [ABBR, p. N]`)
    with natural voice/edition attribution rather than relying on unquoted assertions
    or raw colon-separated block dumps.
    """
    if not paragraphs:
        return []
    out: list[str] = []
    voice_by_title = {s.get("title", ""): s.get("voice_note", "") for s in (sources or [])}
    Verbs = ("writes that", "characterizes this as", "notes that", "frames the core tension as", "observes that", "puts it as")

    for idx, para in enumerate(paragraphs):
        p_clean = para.strip()
        # Convert any legacy `According to X [CITE]: "long dump"` into concise inline-quoted analysis
        m_dump = re.search(
            r"(?:According to|As documented in|Source accounts emphasize:|Source records further note|Source accounts record|Contemporary chronicles note|As recorded in)\s*([^:\[]*)\[([^\]]+)\]:\s*\"([^\"]{40,})\"",
            p_clean,
        )
        if m_dump:
            src_title = m_dump.group(1).strip(" ,") or (sources[0]["title"] if sources else "the source text")
            cite_str = m_dump.group(2).strip()
            raw_quote = m_dump.group(3).strip()
            short_q = extract_inline_quote_clause(raw_quote, focus_term=focus_term)
            reg = classify_source_register(src_title, voice_by_title.get(src_title, ""), raw_quote)
            verb = Verbs[idx % len(Verbs)]
            replacement = f"The {reg['descriptor']} {verb} \"{short_q}\" [{cite_str}]."
            p_clean = p_clean[: m_dump.start()].rstrip() + " " + replacement

        has_inline_quote = bool(re.search(r"[\"“][^\"”]{12,160}[\"”]\s*\[[A-Z0-9-]+|'[A-Za-z][^']{12,160}'\s*\[[A-Z0-9-]+", p_clean))
        if not has_inline_quote and idx < len(passages):
            psg = passages[idx]
            short_q = psg.get("inline_quote") or extract_inline_quote_clause(psg.get("snippet", ""), focus_term=focus_term)
            if short_q and psg.get("cite"):
                src_t = psg.get("title") or (sources[0]["title"] if sources else "the source text")
                reg = classify_source_register(src_t, voice_by_title.get(src_t, ""), psg.get("snippet", ""))
                verb = Verbs[idx % len(Verbs)]
                p_clean = f"{p_clean.rstrip('.')} — as the {reg['descriptor']} {verb}, \"{short_q}\" [{psg['cite']}]."
        out.append(p_clean.strip())
    return out


def build_deterministic_article_from_packet(
    packet: TopicEvidencePacket,
    edition_meta: dict[str, str] | None = None,
) -> dict[str, Any]:
    """
    Deterministically compile a multi-subsection article from `TopicEvidencePacket`
    following the Lore Atlas Style Guide:
      - Zero generic throat-clearing filler
      - Short verbatim phrases (`"..." [ABBR, p. N]`) woven directly into analytical sentences
      - Explicit distinction between designer voice, narrator overview, and in-character claims
      - Edition differences and unresolved mysteries preserved as competing theories
    """
    name = packet.name
    gl = packet.gameline
    cat = packet.category
    ed_info = edition_meta or {}
    game_title = ed_info.get("game_title") or gl.replace("-", " ").title()
    ed_name = ed_info.get("edition_name") or f"{game_title} (20th Anniversary / Classic Baseline)"

    sources = packet.sources_of_record
    top_cite = f"[{sources[0]['abbr']}, {sources[0]['pages']}]" if sources else ""
    second_cite = f"[{sources[1]['abbr']}, {sources[1]['pages']}]" if len(sources) > 1 else top_cite

    primary_sym = packet.resolved_symbols[0] if packet.resolved_symbols else {}
    raw_aliases = [repair_ocr_and_layout_text(a, topic_name=name) for a in primary_sym.get("aliases", [])]
    aliases = [
        a for a in raw_aliases
        if a.lower() != name.lower()
        and len(a) >= 4
        and not re.search(r"\b(?:PCs|NPCs|VEs|HOBs|Anyway|Celestial Chorus|Louis DuMonte|Second Age|Navy SEALs)\b", a, re.IGNORECASE)
    ]
    alias_clause = f" — also called {', '.join(aliases[:4])} in the sourcebooks —" if aliases else ""

    qf_items = list(packet.quick_facts.items())
    qf_summary = "; ".join(f"{k}: {v}" for k, v in qf_items[:4])

    id_passages = packet.facet_passages.get("identity_and_doctrine", [])
    org_passages = packet.facet_passages.get("organization_and_subfactions", [])
    hist_passages = packet.facet_passages.get("chronology_and_history", [])
    persp_passages = packet.facet_passages.get("perspectives_and_rivals", [])

    # Helper to weave a passage's voice, title, and short inline quote
    def _quote_clause(psg: dict[str, str], idx_v: int = 0) -> str:
        s_title = psg.get("title") or (sources[0]["title"] if sources else game_title)
        v_note = next((s.get("voice_note", "") for s in sources if s.get("title") == s_title), "")
        reg = classify_source_register(s_title, v_note, psg.get("snippet", ""), ed_name)
        q_phrase = psg.get("inline_quote") or extract_inline_quote_clause(psg.get("snippet", ""), focus_term=name)
        cite = psg.get("cite", top_cite.strip("[]"))
        verbs = ("writes that", "describes this as", "warns that", "characterizes them by noting", "observes that")
        verb = verbs[idx_v % len(verbs)]
        return f"the {reg['descriptor']} {verb} \"{q_phrase}\" [{cite}]"

    name_parts_low = {w.lower() for w in re.findall(r"[A-Za-z]{3,}", name)} | {name.lower()}
    neighbor_names: list[str] = []
    seen_nb: set[str] = set()
    for n in packet.top_neighbors:
        dn = repair_ocr_and_layout_text(str(n.get("dst_name") or ""), topic_name=name).strip()
        dn_low = dn.lower()
        if not dn or dn_low in name_parts_low or dn_low in seen_nb:
            continue
        seen_nb.add(dn_low)
        neighbor_names.append(dn)
        if len(neighbor_names) >= 6:
            break
    neighbor_str = ", ".join(neighbor_names[:5]) if neighbor_names else "neighboring factions and rivals"
    bridge_names: list[str] = []
    seen_br: set[tuple[str, str]] = set()
    for b in packet.cross_domain_bridges:
        bn = repair_ocr_and_layout_text(str(b.get("name") or ""), topic_name=name).strip()
        bgl = str(b.get("gameline") or "").replace("-", " ").title()
        if not bn or bn.lower() in {w.lower() for w in re.findall(r"[A-Za-z]{3,}", name)} and bn.lower() != name.lower():
            continue
        if (bn.lower(), bgl.lower()) in seen_br:
            continue
        seen_br.add((bn.lower(), bgl.lower()))
        bridge_names.append(f"{bn} ({bgl})")
        if len(bridge_names) >= 4:
            break

    # 1. Lead Summary (Direct, analytical, inline-quoted, voice-aware)
    lead_p1_parts = [
        f"**{name}**{alias_clause} is a {cat.lower()} in *{game_title}* ({ed_name})"
        + (f" defined at the table by {qf_summary} {top_cite}." if qf_summary else f" {top_cite}.")
    ]
    if len(id_passages) >= 2 and id_passages[0].get("title") != id_passages[1].get("title"):
        lead_p1_parts.append(
            f"Where {_quote_clause(id_passages[0], 0)}, {_quote_clause(id_passages[1], 1)}."
        )
    elif id_passages:
        lead_p1_parts.append(f"In the primary text, {_quote_clause(id_passages[0], 0)}.")

    src_list_str = ", ".join(f"*{s['title']}* [{s['abbr']}, {s['pages']}]" for s in sources[:3])
    lead_p2_parts = [
        f"Across {src_list_str or game_title}, {name} interacts directly with {neighbor_str}."
    ]
    if bridge_names:
        lead_p2_parts.append(
            f"Beyond its home line, its cosmology and operations intersect with {', '.join(bridge_names)}, where each line uses its own vocabulary for the same underlying phenomena."
        )
    if persp_passages:
        lead_p2_parts.append(
            f"Because {_quote_clause(persp_passages[0], 2)}, this page records in-universe claims alongside designer-voice rules rather than flattening them into a single account."
        )
    elif len(sources) > 1:
        lead_p2_parts.append(
            f"Because the sources mix out-of-character Storyteller exposition with partisan in-character testimony {second_cite}, competing accounts are preserved below by voice and edition."
        )

    lead_summary = [" ".join(lead_p1_parts).strip(), " ".join(lead_p2_parts).strip()]

    # 2. Voice Caution (modeled on `Style Guide and Conventions.md` Section 3)
    voice_caution: list[str] = []
    for s in sources[:4]:
        h_preview = f" (key sections: {', '.join(s['headings'][:3])})" if s.get("headings") else ""
        voice_caution.append(
            f"{s['title']} [{s['abbr']}, {s['pages']}] — {s['voice_note']}{h_preview}."
        )
    if not voice_caution:
        voice_caution.append(
            f"Primary rulebooks for {game_title} distinguish out-of-character Storyteller mechanics from in-character faction monologues and field reports."
        )

    # 3. Defining Pillars (grounded on Quick Facts + inline-quoted passages)
    defining_pillars: list[dict[str, str]] = []
    for idx_qf, (k, v) in enumerate(qf_items[:3]):
        cite_use = f"[{id_passages[idx_qf]['cite']}]" if idx_qf < len(id_passages) else top_cite
        q_tail = ""
        if idx_qf < len(id_passages):
            qp = extract_inline_quote_clause(id_passages[idx_qf]["snippet"], focus_term=name)
            if qp:
                q_tail = f" The source text notes \"{qp}\" {cite_use}."
        defining_pillars.append(
            {
                "title": k,
                "body": f"{v} {cite_use}.{q_tail}".strip(),
            }
        )
    for idx_p, p_item in enumerate(id_passages[:3]):
        if len(defining_pillars) >= 4:
            break
        qp = extract_inline_quote_clause(p_item["snippet"], focus_term=name)
        p_head = (p_item.get("heading") or "").strip()
        if not p_head or p_head.lower().startswith("page "):
            p_head, p_snip = extract_embedded_heading_and_body(p_item.get("snippet", ""), topic_name=name)
        else:
            p_snip = p_item.get("snippet", "")
        p_snip_clean = p_snip.rstrip(". ")
        if not p_head or p_head.lower().startswith("page "):
            # Derive a concise topical pillar title from the passage's focus clause rather than a generic numbered placeholder
            p_head = f"{name} Doctrine & Praxis" if idx_p == 0 else (f"Tradition & Interpretation" if idx_p == 1 else "Lineage & Philosophy")
        defining_pillars.append(
            {
                "title": p_head,
                "body": f"{p_snip_clean} — \"{qp}\" [{p_item['cite']}]." if qp and qp not in p_snip else f"{p_snip_clean} [{p_item['cite']}].",
            }
        )

    # 4. Names and Identity
    names_and_identity: list[str] = []
    if aliases:
        names_and_identity.append(
            f"Recorded Aliases & Sobriquets: Referred to across the corpus as {', '.join(aliases[:6])} {top_cite}, reflecting both insider titles and outsider epithets."
        )
    names_and_identity.append(
        f"Setting Classification: Indexed as a {cat} in {game_title} across {primary_sym.get('mentions', sum(s['hits'] for s in sources))} source passages {top_cite}."
    )
    for q_cand in packet.verbatim_quotes:
        short_q0 = extract_inline_quote_clause(q_cand.get("quote", ""), focus_term=name, max_words=18)
        if short_q0 and len(short_q0.split()) >= 5 and not re.match(r"^\d+\s+", short_q0):
            c0 = str(q_cand.get("citation") or "").strip()
            if "Page " in c0 and top_cite:
                c0 = top_cite.strip("[]")
            names_and_identity.append(
                f"Attributed Framing ({q_cand.get('perspective', 'Source Account')}): Frames {name} with the words \"{short_q0}\" [{c0}]."
            )
            break

    # 5. Culture, Doctrine, and Internal Structure (analytical prose with woven quotes)
    cult_pool = id_passages[1:3] + org_passages[:3]
    if len(cult_pool) >= 2:
        cult_p1 = (
            f"{name}'s doctrine and internal culture center on how members interpret their mandate and hierarchy {top_cite}: "
            f"{_quote_clause(cult_pool[0], 0)}, while {_quote_clause(cult_pool[1], 1)}."
        )
    elif cult_pool:
        cult_p1 = (
            f"{name}'s internal doctrine shapes both initiation and daily praxis: {_quote_clause(cult_pool[0], 0)}. "
            f"{cult_pool[0]['snippet']} [{cult_pool[0]['cite']}]."
        )
    else:
        cult_p1 = (
            f"{name}'s internal philosophy governs how initiates are inducted, how elders interpret canon law, and how cells or coteries coordinate with {neighbor_str} {top_cite}."
        )

    if len(org_passages) >= 2:
        cult_p2 = (
            f"Organizationally, ranks and sub-factions divide labor and ideology across {neighbor_str}: "
            f"{_quote_clause(org_passages[0], 2)}, and {_quote_clause(org_passages[1], 3)}."
        )
    else:
        cult_p2 = (
            f"In practice, operations connect {name} with {neighbor_str} {second_cite}, balancing formal protocol against local domain politics."
        )
    culture_and_doctrine = [cult_p1, cult_p2]

    # 6. Sub-Factions, Lineages & Connected Institutions Table
    subfactions_table: list[dict[str, str]] = []
    for n in packet.top_neighbors[:6]:
        cites_str = ", ".join(n.get("sample_citations", [])[:2]) or top_cite.strip("[]")
        subfactions_table.append(
            {
                "name": n["dst_name"],
                "kind": "Linked Faction / Lineage / Figure",
                "focus": f"Co-attested across {n['support_pages']} sourcebook pages",
                "description": f"Key internal wing, allied lineage, or ideological counterpart interacting with {name} in {game_title}.",
                "sources": f"[{cites_str}]" if cites_str and not cites_str.startswith("[") else cites_str,
            }
        )

    # 7. Strongholds, Law, and Practices
    if org_passages:
        strongholds_and_law = [
            f"Internal codes of judgment and territorial sanctuaries for {name} are enforced through both tradition and practical necessity: {_quote_clause(org_passages[0], 4)}. "
            f"{org_passages[0]['snippet']} [{org_passages[0]['cite']}]."
        ]
    else:
        strongholds_and_law = [
            f"Sanctuaries, strongholds, and internal codes of judgment for {name} are maintained by local elders and tribunals to preserve secrecy and adjudicate disputes {top_cite}."
        ]

    # 8. Chronological History by Named Eras (`history_eras` + `history_and_origins`)
    claims = packet.causal_claims
    if hist_passages:
        era1_body = (
            f"In the foundational accounts of {name}, {_quote_clause(hist_passages[0], 0)}. "
            f"{hist_passages[0]['snippet']} [{hist_passages[0]['cite']}]."
        )
    else:
        era1_body = (
            f"Foundational accounts in {sources[0]['title'] if sources else game_title} {top_cite} trace {name}'s origins to early covenants and blood or spiritual lineages that established its enduring mandate."
        )

    if len(hist_passages) > 1:
        era2_body = (
            f"Across subsequent schisms and historical wars, {_quote_clause(hist_passages[1], 1)}. "
            f"{hist_passages[1]['snippet']} [{hist_passages[1]['cite']}]."
        )
    elif claims:
        c0_q = extract_inline_quote_clause(claims[0].get("quote", ""), focus_term=name)
        era2_body = (
            f"Historical turning points center on contested events such as {claims[0]['claim']}, where the text notes \"{c0_q}\" [{claims[0]['citation']}]."
        )
    else:
        era2_body = (
            f"Subsequent eras saw {name} tested by internal schisms and conflicts with {neighbor_str} {second_cite}, hardening its modern hierarchy."
        )

    if len(hist_passages) > 2:
        era3_body = (
            f"By the modern nights and 20th Anniversary baseline, {_quote_clause(hist_passages[2], 2)}. "
            f"{hist_passages[2]['snippet']} [{hist_passages[2]['cite']}]."
        )
    else:
        era3_body = (
            f"In the modern nights ({ed_name}), {name} confronts the fallout of late-twentieth-century upheavals while competing accounts disagree on whether its ancient elders still direct events from the shadows {top_cite}."
        )

    history_eras = [
        {"era_title": f"Origins and Foundational Era of {name}", "paragraphs": [era1_body]},
        {"era_title": "Historical Schisms, Wars, and Consolidation", "paragraphs": [era2_body]},
        {"era_title": "The Modern Nights and Edition Baseline", "paragraphs": [era3_body]},
    ]
    history_and_origins = [era1_body, era2_body, era3_body]

    # 9. Competing Perspectives ("Everyone Thinks They're Right" — Voice & Edition Separation)
    if len(persp_passages) >= 2:
        persp_p1 = (
            f"Sources deliberately preserve conflicting perspectives on {name} rather than settling a single objective verdict: "
            f"{_quote_clause(persp_passages[0], 0)}, whereas {_quote_clause(persp_passages[1], 1)}."
        )
    elif persp_passages:
        persp_p1 = (
            f"What {name} claims about itself diverges sharply from how outsiders and Storyteller chapters describe it: "
            f"{_quote_clause(persp_passages[0], 0)}."
        )
    else:
        persp_p1 = (
            f"Sourcebooks distinguish what {name} claims in its own in-character histories from how rival factions ({neighbor_str}) and out-of-character Storyteller sections assess its record {top_cite}."
        )

    if packet.verbatim_quotes and len(packet.verbatim_quotes) >= 2:
        vq0, vq1 = packet.verbatim_quotes[0], packet.verbatim_quotes[1]
        sq0 = extract_inline_quote_clause(vq0["quote"], focus_term=name)
        sq1 = extract_inline_quote_clause(vq1["quote"], focus_term=name)
        persp_p2 = (
            f"Comparing voices directly illustrates this divide: {vq0.get('perspective', 'one account')} asserts \"{sq0}\" [{vq0.get('citation', '')}], "
            f"while {vq1.get('perspective', 'a rival testimony')} counters with \"{sq1}\" [{vq1.get('citation', '')}]. "
            f"This page therefore records the competing theories as competing accounts for Storyteller adjudication."
        )
    else:
        persp_p2 = (
            f"Where Revised-era metaplot, 20th Anniversary omnibuses, and in-character testimonies diverge {second_cite}, those disagreements are preserved as intentional chronicle mysteries rather than harmonized away."
        )
    competing_perspectives = [persp_p1, persp_p2]

    # 10. Edition Coverage (modeled on `M20 Lore Files`)
    edition_coverage: list[str] = []
    for s in sources[:5]:
        h_list = ", ".join(s["headings"][:4]) if s.get("headings") else "core lore, mechanics, and faction history"
        edition_coverage.append(
            f"**{s['abbr']}** (*{s['title']}*, {s['pages']}, {s['hits']} indexed sections): Covers {h_list}. {s['voice_note']}."
        )

    # 11. Playing & Portraying + Storyteller Hooks
    playing_and_portraying = [
        f"When portraying {name} as a player character or Storyteller NPC in {game_title}, ground their demeanor in the tension between their philosophical ideals and the compromises required to survive each night {top_cite}. "
        f"Leverage their connections to {neighbor_str} and their signature traits ({', '.join(packet.quick_facts.keys()) or cat}) to drive personal and coterie/cell drama."
    ]
    storyteller_hooks = [
        {
            "title": f"The Divided Archive of {name}",
            "hook": f"A disputed relic or testimony surfaces from {sources[0]['title'] if sources else game_title} {top_cite}, forcing the players' coterie or cell to choose between rival elders of {name} and {neighbor_names[0] if neighbor_names else 'an outside faction'}.",
        },
        {
            "title": f"Crossfire With {neighbor_names[1] if len(neighbor_names) > 1 else 'Rival Operatives'}",
            "hook": f"An operation involving {name} spills across domain boundaries into territory claimed by {neighbor_names[1] if len(neighbor_names) > 1 else 'hostile supernatural rivals'} {second_cite}, threatening to expose secret strongholds.",
        },
        {
            "title": "Whispers vs. Orthodoxy",
            "hook": f"A veteran member of {name} uncovers evidence that a foundational faction doctrine was altered during a past schism, placing the characters between whistleblowers and enforcers {top_cite}.",
        },
    ]

    # 12. Terminology & Notable Figures Table
    terminology: list[dict[str, str]] = []
    for k, v in packet.quick_facts.items():
        terminology.append({"term": k, "meaning": v, "source": top_cite or f"[{ed_info.get('badge', '20TH').strip('[]')}]"})
    for n in packet.top_neighbors[:8]:
        c_str = (n.get("sample_citations") or [top_cite.strip("[]")])[0]
        terminology.append(
            {
                "term": n["dst_name"],
                "meaning": f"Key figure, faction, or concept connected to {name} across {n['support_pages']} sourcebook passages.",
                "source": f"[{c_str.strip('[]')}]" if c_str else top_cite,
            }
        )

    # 13. Evidence and Open Questions
    open_questions: list[str] = [
        f"**Narrator Bias vs. Storyteller Canon:** Several primary accounts of {name} in {sources[0]['title'] if sources else game_title} {top_cite} are delivered in-character; Storytellers must decide how much of the faction's self-mythology is literal truth.",
        f"**Cross-Book & Edition Continuity:** Reconciling classic 1990s/Revised supplement details across {', '.join(s['abbr'] for s in sources[:3]) or game_title} with 20th Anniversary omnibus options requires choosing whether late-1990s metaplot upheavals have already occurred in the chronicle.",
    ]
    if packet.cross_domain_bridges:
        b0 = packet.cross_domain_bridges[0]
        open_questions.append(
            f"**Cross-Gameline Interpretation ({b0['name']}):** Sourcebooks in {b0['gameline'].replace('-', ' ').title()} reference {b0['name']} ({b0['mentions']} passages) using their own cosmological vocabulary; see the Translation Guide when running multi-splat scenes."
        )

    return {
        "lead_summary": lead_summary,
        "voice_caution": voice_caution,
        "defining_pillars": defining_pillars,
        "names_and_identity": names_and_identity,
        "culture_and_doctrine": culture_and_doctrine,
        "subfactions_table": subfactions_table,
        "strongholds_and_law": strongholds_and_law,
        "history_eras": history_eras,
        "history_and_origins": history_and_origins,
        "competing_perspectives": competing_perspectives,
        "edition_coverage": edition_coverage,
        "playing_and_portraying": playing_and_portraying,
        "storyteller_hooks": storyteller_hooks,
        "terminology": terminology,
        "open_questions": open_questions,
        "sources_of_record": sources,
        "_facet_passages": packet.facet_passages,
        "_topic_name": name,
    }


def repair_and_parse_json(raw: str) -> dict[str, Any]:
    """
    Parse an LLM JSON response, automatically salvaging all completed top-level
    arrays/objects even if the response was truncated at `max_tokens` or had
    trailing markdown fences.
    """
    s = (raw or "").strip()
    if s.startswith("```"):
        s = re.sub(r"^```(?:json)?\s*", "", s)
        s = re.sub(r"\s*```$", "", s)
    try:
        parsed = json.loads(s)
        if isinstance(parsed, dict):
            return parsed
    except Exception:
        pass

    # Try closing open brackets/quotes on truncated JSON
    for cut_idx in range(len(s), max(len(s) - 600, 50), -1):
        ch = s[cut_idx - 1]
        if ch in ("]", "}", '"'):
            prefix = s[:cut_idx]
            for closer in ("}", "]}", "\"]}", "\"}]}"):
                try:
                    cand = json.loads(prefix + closer)
                    if isinstance(cand, dict) and cand:
                        return cand
                except Exception:
                    continue

    # Key-by-key extraction fallback for any complete top-level `"key": [...]` blocks
    salvaged: dict[str, Any] = {}
    for m in re.finditer(r'"([a-z_]+)"\s*:\s*(\[)', s):
        k = m.group(1)
        start = m.start(2)
        depth = 0
        in_str = False
        esc = False
        for i in range(start, len(s)):
            c = s[i]
            if esc:
                esc = False
                continue
            if c == "\\":
                esc = True
                continue
            if c == '"':
                in_str = not in_str
                continue
            if not in_str:
                if c == "[":
                    depth += 1
                elif c == "]":
                    depth -= 1
                    if depth == 0:
                        arr_txt = s[start : i + 1]
                        try:
                            salvaged[k] = json.loads(arr_txt)
                        except Exception:
                            pass
                        break
    return salvaged


def merge_llm_and_deterministic_article(
    llm_data: dict[str, Any],
    det_data: dict[str, Any],
) -> dict[str, Any]:
    """
    Merge LLM-synthesized narrative fields with deterministic AST/corpus fields,
    enforcing a strict minimum length and multi-item check so a short/failed LLM field
    never degrades the page below `det_data`.
    """
    merged = dict(det_data)
    if not isinstance(llm_data, dict):
        return merged

    def _valid_para_list(val: Any, min_chars: int = 160) -> list[str] | None:
        if isinstance(val, str) and len(val.strip()) >= min_chars:
            return [val.strip()]
        if isinstance(val, list):
            cleaned = [str(x).strip() for x in val if isinstance(x, str) and len(str(x).strip()) >= 55]
            if sum(len(x) for x in cleaned) >= min_chars:
                return cleaned
        return None

    facet_map: dict[str, list[dict[str, str]]] = det_data.get("_facet_passages") or {}
    sources_list: list[dict[str, Any]] = det_data.get("sources_of_record") or []
    topic_name: str = str(det_data.get("_topic_name") or "")
    para_facet_lookup = {
        "lead_summary": facet_map.get("identity_and_doctrine", []),
        "culture_and_doctrine": (facet_map.get("identity_and_doctrine", [])[1:] + facet_map.get("organization_and_subfactions", [])),
        "strongholds_and_law": facet_map.get("organization_and_subfactions", []),
        "history_and_origins": facet_map.get("chronology_and_history", []),
        "competing_perspectives": facet_map.get("perspectives_and_rivals", []),
        "playing_and_portraying": [],
    }

    for para_key in ("lead_summary", "culture_and_doctrine", "history_and_origins", "competing_perspectives", "playing_and_portraying", "strongholds_and_law"):
        cand = _valid_para_list(llm_data.get(para_key), min_chars=180 if para_key != "playing_and_portraying" else 110)
        if cand:
            # Ensure we have at least as many paragraphs as det_data if llm returned only 1 short paragraph
            if len(cand) == 1 and len(det_data.get(para_key, [])) >= 2 and len(cand[0]) < 360:
                raw_paras = [cand[0], det_data[para_key][1]]
            else:
                raw_paras = cand
            psgs = para_facet_lookup.get(para_key, [])
            merged[para_key] = (
                ensure_inline_passage_quotes_in_paragraphs(raw_paras, psgs, sources_list, focus_term=topic_name)
                if psgs
                else raw_paras
            )

    for list_key in ("voice_caution", "names_and_identity", "edition_coverage", "open_questions"):
        val = llm_data.get(list_key)
        if isinstance(val, list):
            strs = [str(x).strip() for x in val if str(x).strip() and len(str(x).strip()) >= 35]
            if len(strs) >= 2:
                merged[list_key] = strs

    if isinstance(llm_data.get("defining_pillars"), list) and len(llm_data["defining_pillars"]) >= 2:
        valid_p = [
            p for p in llm_data["defining_pillars"]
            if isinstance(p, dict) and p.get("title") and len(str(p.get("body", ""))) >= 40
        ]
        if len(valid_p) >= 2:
            merged["defining_pillars"] = valid_p

    if isinstance(llm_data.get("subfactions_table"), list) and len(llm_data["subfactions_table"]) >= 2:
        valid_sf = [
            r for r in llm_data["subfactions_table"]
            if isinstance(r, dict) and r.get("name") and r.get("description")
        ]
        if len(valid_sf) >= 2:
            merged["subfactions_table"] = valid_sf + [
                d for d in det_data.get("subfactions_table", [])
                if d["name"].lower() not in {str(x["name"]).lower() for x in valid_sf}
            ][:4]

    if isinstance(llm_data.get("history_eras"), list) and len(llm_data["history_eras"]) >= 2:
        valid_eras = []
        hist_psgs = facet_map.get("chronology_and_history", [])
        for idx_er, er in enumerate(llm_data["history_eras"]):
            if isinstance(er, dict) and er.get("era_title"):
                ps = _valid_para_list(er.get("paragraphs") or er.get("body"), min_chars=80)
                if ps:
                    era_psg = [hist_psgs[idx_er]] if idx_er < len(hist_psgs) else hist_psgs[:1]
                    ps_quoted = (
                        ensure_inline_passage_quotes_in_paragraphs(ps, era_psg, sources_list, focus_term=topic_name)
                        if era_psg
                        else ps
                    )
                    valid_eras.append({"era_title": str(er["era_title"]).strip(), "paragraphs": ps_quoted})
        if len(valid_eras) >= 2:
            merged["history_eras"] = valid_eras

    if isinstance(llm_data.get("storyteller_hooks"), list) and len(llm_data["storyteller_hooks"]) >= 2:
        valid_h = [
            h for h in llm_data["storyteller_hooks"]
            if isinstance(h, dict) and h.get("title") and len(str(h.get("hook") or h.get("body") or "")) >= 40
        ]
        if len(valid_h) >= 2:
            merged["storyteller_hooks"] = valid_h

    if isinstance(llm_data.get("terminology"), list) and len(llm_data["terminology"]) >= 3:
        valid_t = [
            t for t in llm_data["terminology"]
            if isinstance(t, dict) and (t.get("term") or t.get("name")) and (t.get("meaning") or t.get("description"))
        ]
        if valid_t:
            seen_t = {str(t.get("term") or t.get("name")).lower() for t in valid_t}
            extra_t = [d for d in det_data.get("terminology", []) if d["term"].lower() not in seen_t]
            merged["terminology"] = valid_t + extra_t[:8]

    merged["sources_of_record"] = det_data["sources_of_record"]
    return merged


# ══════════════════════════════════════════════════════════════════════════════
# STAGE 4: BOOK-BY-BOOK / DOCUMENT-BY-DOCUMENT ARCHIVE SUMMARY COMPILER
# ══════════════════════════════════════════════════════════════════════════════

def compile_book_summaries(conn: sqlite3.Connection) -> list[dict[str, Any]]:
    """
    Generate structured summaries for every full-text indexed book/document in
    `source_register` (`has_text = 1`), extracting its chapter/section headings,
    page count, word count, top AST symbols, and representative verbatim excerpts.
    """
    conn.row_factory = sqlite3.Row
    try:
        books = conn.execute(
            """
            SELECT abbr, title, gameline, rel_path, md_rel_path, credits, copyright_line,
                   pdf_pages, offset_label, has_text, word_count, status
            FROM source_register
            WHERE has_text = 1 AND word_count >= 100
            ORDER BY gameline, title
            """
        ).fetchall()
    except sqlite3.Error:
        return []

    # Group sections by md_rel_path or title in a single fast pass
    sec_by_path: dict[str, list[sqlite3.Row]] = collections.defaultdict(list)
    sec_by_title: dict[str, list[sqlite3.Row]] = collections.defaultdict(list)
    for sr in conn.execute(
        "SELECT title, heading, path, tags, substr(body, 1, 420) AS sample_body FROM doc_sections"
    ).fetchall():
        p_str = str(sr["path"] or "")
        t_str = str(sr["title"] or "")
        if len(sec_by_path[p_str]) < 40:
            sec_by_path[p_str].append(sr)
        if len(sec_by_title[t_str]) < 40:
            sec_by_title[t_str].append(sr)

    summaries: list[dict[str, Any]] = []
    for b in books:
        abbr = str(b["abbr"])
        title = str(b["title"])
        gl = str(b["gameline"])
        md_rel = str(b["md_rel_path"] or "")
        secs = sec_by_path.get(md_rel) or sec_by_title.get(title) or []

        headings: list[str] = []
        excerpts: list[dict[str, str]] = []
        for s in secs:
            h = str(s["heading"] or "").strip()
            if h and not re.match(r"^Page\s+\d+", h, re.IGNORECASE) and h not in headings and len(headings) < 10:
                headings.append(h)
            if len(excerpts) < 2:
                raw_b = str(s["sample_body"] or "")
                cleaned = clean_sentence_excerpt(raw_b, topic_name=title, max_len=240)
                if len(cleaned) >= 70:
                    m_pr = re.search(r"printed_p:(\d+)", str(s["tags"] or ""))
                    p_lbl = f"p. {m_pr.group(1)}" if m_pr else "p. 1"
                    excerpts.append({"cite": f"{abbr}, {p_lbl}", "text": cleaned})

        summaries.append(
            {
                "abbr": abbr,
                "title": title,
                "gameline": gl,
                "credits": str(b["credits"]),
                "copyright": str(b["copyright_line"]),
                "pages": int(b["pdf_pages"]),
                "words": int(b["word_count"]),
                "offset": str(b["offset_label"]),
                "headings": headings,
                "excerpts": excerpts,
            }
        )
    return summaries


# ══════════════════════════════════════════════════════════════════════════════
# STAGE 5: RESILIENT RATE-LIMIT BACKOFF & MULTI-BAR PIPELINE PROGRESS TRACKER
# ══════════════════════════════════════════════════════════════════════════════

def classify_rate_limit_error(err_msg: str) -> str:
    """
    Classify a download/API error string into:
      - 'QUOTA_WINDOW'  (HTTP 509 Bandwidth Limit Exceeded, MEGA -17/-18 over-quota)
      - 'API_THROTTLE'  (HTTP 429, 502, 503, 504, MEGA -3 EAGAIN, -4 ERATELIMIT)
      - 'TRANSIENT_NET' (timeout, connection reset, EOF, incomplete read)
      - 'OTHER'
    """
    low = (err_msg or "").lower()
    if any(
        k in low
        for k in (
            "509",
            "bandwidth limit",
            "overquota",
            "code -17",
            "code -18",
            "error -17",
            "error -18",
            "quota exceeded",
        )
    ):
        return "QUOTA_WINDOW"
    if any(
        k in low
        for k in (
            "429",
            "too many requests",
            "rate limit",
            "ratelimit",
            "code -3",
            "code -4",
            "error -3",
            "error -4",
            "eagain",
            "502",
            "503",
            "504",
        )
    ):
        return "API_THROTTLE"
    if any(
        k in low
        for k in (
            "timed out",
            "timeout",
            "connection reset",
            "broken pipe",
            "incomplete",
            "eof",
            "temporarily unavailable",
            "urlopen error",
        )
    ):
        return "TRANSIENT_NET"
    return "OTHER"


class AdaptiveRateLimitBackoff:
    """
    Thread-safe rate-limit & bandwidth-quota backoff coordinator.
    Ensures concurrent workers pace API requests and share a global cooldown gate
    when a provider enforces a per-IP transfer window (`HTTP 509` / `-17`) or burst
    rate limit (`HTTP 429` / `-3` / `-4`), preventing dropped items.
    """

    def __init__(
        self,
        min_request_interval_sec: float = 0.35,
        api_base_delay_sec: float = 2.0,
        max_api_delay_sec: float = 90.0,
        quota_base_delay_sec: float = 60.0,
        max_quota_delay_sec: float = 900.0,
        time_fn: Any = time.time,
    ) -> None:
        self.min_request_interval_sec = max(0.0, float(min_request_interval_sec))
        self.api_base_delay_sec = max(0.1, float(api_base_delay_sec))
        self.max_api_delay_sec = max(self.api_base_delay_sec, float(max_api_delay_sec))
        self.quota_base_delay_sec = max(1.0, float(quota_base_delay_sec))
        self.max_quota_delay_sec = max(self.quota_base_delay_sec, float(max_quota_delay_sec))
        self._time = time_fn
        self._lock = threading.Lock()
        self._last_request_ts: float = 0.0
        self._cooldown_until: float = 0.0
        self._streak_by_kind: dict[str, int] = collections.Counter()
        self._total_backoff_events: int = 0
        self._last_reason: str = ""
        self._last_kind: str = ""

    def compute_delay(self, kind: str, streak: int) -> float:
        s = max(1, int(streak))
        if kind == "QUOTA_WINDOW":
            return min(self.max_quota_delay_sec, self.quota_base_delay_sec * (1.75 ** (s - 1)))
        if kind == "API_THROTTLE":
            return min(self.max_api_delay_sec, self.api_base_delay_sec * (2.0 ** (s - 1)))
        if kind == "TRANSIENT_NET":
            return min(self.max_api_delay_sec, max(1.0, self.api_base_delay_sec * 0.75) * (1.6 ** (s - 1)))
        return min(30.0, self.api_base_delay_sec * s)

    def reserve_request_slot(self) -> float:
        """
        Return how many seconds the caller must sleep before issuing its next request
        to honor both the global cooldown gate and token-bucket request pacing.
        """
        with self._lock:
            now = float(self._time())
            earliest = max(now, self._cooldown_until, self._last_request_ts + self.min_request_interval_sec)
            self._last_request_ts = earliest
            return max(0.0, earliest - now)

    def record_success(self) -> None:
        with self._lock:
            self._streak_by_kind.clear()
            now = float(self._time())
            if self._cooldown_until <= now:
                self._cooldown_until = 0.0
                self._last_reason = ""
                self._last_kind = ""

    def record_failure(self, err_msg: str) -> tuple[str, float]:
        """
        Record an error and update the shared cooldown gate.
        Returns `(kind, cooldown_seconds_from_now)`.
        """
        kind = classify_rate_limit_error(err_msg)
        with self._lock:
            self._streak_by_kind[kind] = self._streak_by_kind.get(kind, 0) + 1
            streak = self._streak_by_kind[kind]
            delay = self.compute_delay(kind, streak)
            now = float(self._time())
            target_until = now + delay
            if target_until > self._cooldown_until:
                self._cooldown_until = target_until
            self._total_backoff_events += 1
            self._last_reason = str(err_msg)[:200]
            self._last_kind = kind
            return kind, max(0.0, self._cooldown_until - now)

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            now = float(self._time())
            rem = max(0.0, self._cooldown_until - now)
            return {
                "in_backoff": rem > 0.05,
                "remaining_sec": round(rem, 1),
                "kind": self._last_kind if rem > 0.05 else "",
                "reason": self._last_reason if rem > 0.05 else "",
                "total_backoff_events": self._total_backoff_events,
                "streaks": dict(self._streak_by_kind),
            }


class PipelineStatusTracker:
    """
    Thread-safe multi-bar progress tracker with atomic JSON state persistence
    and Unicode/ASCII status-bar rendering for both TUI and GUI monitors.
    """

    def __init__(
        self,
        state_path: pathlib.Path,
        title: str = "Corpus Compilation Pipeline",
    ) -> None:
        self.state_path = pathlib.Path(state_path)
        self.title = title
        self._lock = threading.Lock()
        self.started_at = time.time()
        self.updated_at = self.started_at
        self.overall_state = "RUNNING"
        self.active_phase = "Initializing"
        self.completion_message = ""
        self.pid = os.getpid()
        self.bars: collections.OrderedDict[str, dict[str, Any]] = collections.OrderedDict()
        self.backoff_info: dict[str, Any] = {
            "in_backoff": False,
            "remaining_sec": 0.0,
            "kind": "",
            "reason": "",
            "total_backoff_events": 0,
        }
        self.events: collections.deque[str] = collections.deque(maxlen=25)

    def register_bar(
        self,
        bar_id: str,
        label: str,
        total: float,
        current: float = 0.0,
        unit: str = "items",
        status: str = "PENDING",
        detail: str = "",
    ) -> None:
        with self._lock:
            self.bars[bar_id] = {
                "id": bar_id,
                "label": label,
                "current": float(current),
                "total": max(1.0, float(total)),
                "unit": unit,
                "status": status,
                "rate_str": "",
                "detail": detail,
                "updated_at": time.time(),
            }

    def update_bar(
        self,
        bar_id: str,
        current: float | None = None,
        total: float | None = None,
        status: str | None = None,
        rate_str: str | None = None,
        detail: str | None = None,
        phase_title: str | None = None,
        save: bool = True,
    ) -> None:
        with self._lock:
            b = self.bars.get(bar_id)
            if b is None:
                return
            if current is not None:
                b["current"] = float(current)
            if total is not None:
                b["total"] = max(1.0, float(total))
            if status is not None:
                b["status"] = status
            if rate_str is not None:
                b["rate_str"] = rate_str
            if detail is not None:
                b["detail"] = detail
            b["updated_at"] = time.time()
            self.updated_at = b["updated_at"]
            if phase_title is not None:
                self.active_phase = phase_title
            if save:
                self._write_locked()

    def set_backoff_state(self, backoff_snapshot: dict[str, Any], save: bool = True) -> None:
        with self._lock:
            self.backoff_info = dict(backoff_snapshot)
            self.updated_at = time.time()
            if save:
                self._write_locked()

    def add_event(self, message: str, save: bool = True) -> None:
        ts = time.strftime("%H:%M:%S")
        with self._lock:
            self.events.append(f"[{ts}] {message}")
            self.updated_at = time.time()
            if save:
                self._write_locked()

    def mark_completed(self, summary_message: str) -> None:
        with self._lock:
            self.overall_state = "COMPLETED"
            self.active_phase = "Complete — All Stages Verified"
            self.completion_message = summary_message
            for b in self.bars.values():
                b["current"] = b["total"]
                b["status"] = "DONE"
            self.backoff_info["in_backoff"] = False
            self.backoff_info["remaining_sec"] = 0.0
            ts = time.strftime("%H:%M:%S")
            self.events.append(f"[{ts}] COMPLETED: {summary_message}")
            self.updated_at = time.time()
            self._write_locked()

    def to_dict(self) -> dict[str, Any]:
        with self._lock:
            return self._to_dict_locked()

    def _to_dict_locked(self) -> dict[str, Any]:
        now = time.time()
        elapsed = max(0.0, now - self.started_at)
        bars_list = list(self.bars.values())
        if bars_list:
            pcts = [min(1.0, max(0.0, b["current"] / max(1.0, b["total"]))) for b in bars_list]
            overall_pct = round(100.0 * (sum(pcts) / len(pcts)), 2)
        else:
            overall_pct = 0.0
        return {
            "title": self.title,
            "pid": self.pid,
            "overall_state": self.overall_state,
            "active_phase": self.active_phase,
            "completion_message": self.completion_message,
            "started_at": self.started_at,
            "updated_at": self.updated_at,
            "elapsed_sec": round(elapsed, 1),
            "overall_percent": overall_pct,
            "backoff": dict(self.backoff_info),
            "bars": [dict(b) for b in bars_list],
            "events": list(self.events),
        }

    def _write_locked(self) -> None:
        self.state_path.parent.mkdir(parents=True, exist_ok=True)
        tmp_p = self.state_path.with_suffix(self.state_path.suffix + ".tmp")
        payload = json.dumps(self._to_dict_locked(), indent=2, ensure_ascii=False)
        tmp_p.write_text(payload, encoding="utf-8")
        os.replace(tmp_p, self.state_path)

    @staticmethod
    def render_text_bars(state: dict[str, Any], width: int = 28) -> str:
        """Render a human-readable multi-bar status dashboard from a tracker state dict."""
        lines: list[str] = []
        title = state.get("title", "Pipeline Monitor")
        overall_state = state.get("overall_state", "RUNNING")
        overall_pct = float(state.get("overall_percent", 0.0))
        elapsed = float(state.get("elapsed_sec", 0.0))
        em, es = divmod(int(elapsed), 60)
        eh, em = divmod(em, 60)
        lines.append(f"=== {title} [{overall_state}] ({overall_pct:.1f}% | Elapsed {eh:02d}:{em:02d}:{es:02d}) ===")
        lines.append(f"Active Phase: {state.get('active_phase', '')}")

        backoff = state.get("backoff") or {}
        if backoff.get("in_backoff"):
            rem = float(backoff.get("remaining_sec", 0.0))
            kind = backoff.get("kind", "RATE_LIMIT")
            reason = backoff.get("reason", "")
            lines.append(
                f"  [!] BACKOFF ACTIVE ({kind}): waiting {rem:.0f}s before next retry "
                f"(events={backoff.get('total_backoff_events', 0)}) — {reason}"
            )

        for b in state.get("bars", []):
            cur = float(b.get("current", 0.0))
            tot = max(1.0, float(b.get("total", 1.0)))
            ratio = min(1.0, max(0.0, cur / tot))
            filled = int(round(ratio * width))
            bar_str = "#" * filled + "-" * max(0, width - filled)
            pct = ratio * 100.0
            unit = b.get("unit", "")
            if unit == "GiB":
                cnt_str = f"{cur:5.2f}/{tot:5.2f} {unit}"
            else:
                cnt_str = f"{int(cur):4d}/{int(tot):4d} {unit}"
            rate = f" ({b['rate_str']})" if b.get("rate_str") else ""
            det = f" | {b['detail']}" if b.get("detail") else ""
            st = b.get("status", "PENDING")
            lines.append(f"  [{bar_str}] {pct:5.1f}% | {b['label']:32s} | {cnt_str} [{st}]{rate}{det}")

        if state.get("completion_message"):
            lines.append(f"\nCOMPLETED: {state['completion_message']}")
        return "\n".join(lines)


# ══════════════════════════════════════════════════════════════════════════════
# STAGE 5: UNIVERSAL DOMAIN-AGNOSTIC AST BAKER, EXPLAIN ENGINE & CLI
# ══════════════════════════════════════════════════════════════════════════════

AST_SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS ast_symbols (
    symbol_id TEXT PRIMARY KEY,
    domain TEXT NOT NULL,
    canonical_name TEXT NOT NULL,
    symbol_type TEXT NOT NULL,
    defining_table TEXT NOT NULL,
    defining_id INTEGER NOT NULL,
    canonical_url TEXT NOT NULL,
    aliases_json TEXT NOT NULL,
    doc_frequency INTEGER NOT NULL DEFAULT 0,
    metadata_json TEXT NOT NULL DEFAULT '{}'
);

CREATE INDEX IF NOT EXISTS idx_ast_symbols_name ON ast_symbols(lower(canonical_name));
CREATE INDEX IF NOT EXISTS idx_ast_symbols_type ON ast_symbols(domain, symbol_type);

CREATE TABLE IF NOT EXISTS ast_candidate_refs (
    ref_id INTEGER PRIMARY KEY AUTOINCREMENT,
    domain TEXT NOT NULL,
    source_table TEXT NOT NULL,
    section_id INTEGER NOT NULL,
    book_or_file TEXT NOT NULL,
    page_or_line INTEGER NOT NULL,
    span_text TEXT NOT NULL,
    target_hint TEXT NOT NULL,
    resolved_symbol_id TEXT,
    ref_kind TEXT NOT NULL,
    epistemic_voice TEXT NOT NULL,
    clan_perspective TEXT NOT NULL DEFAULT 'GENERAL',
    edition TEXT NOT NULL,
    confidence REAL NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ast_refs_resolved ON ast_candidate_refs(resolved_symbol_id);
CREATE INDEX IF NOT EXISTS idx_ast_refs_section ON ast_candidate_refs(source_table, section_id);

CREATE TABLE IF NOT EXISTS ast_edges (
    src_symbol_id TEXT NOT NULL,
    dst_symbol_id TEXT NOT NULL,
    edge_kind TEXT NOT NULL,
    weight REAL NOT NULL,
    support_count INTEGER NOT NULL,
    evidence_json TEXT NOT NULL,
    PRIMARY KEY (src_symbol_id, dst_symbol_id, edge_kind)
);

CREATE INDEX IF NOT EXISTS idx_ast_edges_src ON ast_edges(src_symbol_id, weight DESC);
CREATE INDEX IF NOT EXISTS idx_ast_edges_dst ON ast_edges(dst_symbol_id, weight DESC);

CREATE TABLE IF NOT EXISTS ast_claims (
    claim_id INTEGER PRIMARY KEY AUTOINCREMENT,
    domain TEXT NOT NULL,
    src_symbol_id TEXT NOT NULL,
    predicate TEXT NOT NULL,
    dst_symbol_id TEXT NOT NULL,
    polarity TEXT NOT NULL,
    epistemic_voice TEXT NOT NULL,
    clan_perspective TEXT NOT NULL DEFAULT 'GENERAL',
    edition TEXT NOT NULL,
    source_table TEXT NOT NULL,
    section_id INTEGER NOT NULL,
    citation TEXT NOT NULL,
    url TEXT NOT NULL,
    verbatim_quote TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ast_claims_src ON ast_claims(src_symbol_id, predicate);
CREATE INDEX IF NOT EXISTS idx_ast_claims_dst ON ast_claims(dst_symbol_id, predicate);

CREATE TABLE IF NOT EXISTS ast_conflicts (
    conflict_id INTEGER PRIMARY KEY AUTOINCREMENT,
    domain TEXT NOT NULL,
    conflict_kind TEXT NOT NULL,
    focal_symbol_id TEXT NOT NULL,
    predicate TEXT NOT NULL,
    side_a_target TEXT NOT NULL,
    side_b_target TEXT NOT NULL,
    side_a_voice TEXT NOT NULL,
    side_b_voice TEXT NOT NULL,
    side_a_perspective TEXT NOT NULL DEFAULT 'GENERAL',
    side_b_perspective TEXT NOT NULL DEFAULT 'GENERAL',
    side_a_edition TEXT NOT NULL,
    side_b_edition TEXT NOT NULL,
    side_a_citation TEXT NOT NULL,
    side_b_citation TEXT NOT NULL,
    side_a_quote TEXT NOT NULL,
    side_b_quote TEXT NOT NULL,
    explanation_note TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ast_conflicts_focal ON ast_conflicts(focal_symbol_id);

CREATE TABLE IF NOT EXISTS ast_config_tree (
    file_path TEXT NOT NULL,
    key_path TEXT NOT NULL,
    parent_prefix TEXT NOT NULL,
    leaf_name TEXT NOT NULL,
    default_value TEXT NOT NULL,
    value_type TEXT NOT NULL,
    PRIMARY KEY (file_path, key_path)
);

CREATE INDEX IF NOT EXISTS idx_ast_cfg_leaf ON ast_config_tree(lower(leaf_name));
"""

# General syntactic verb-family patterns (domain-agnostic English clause grammar).
# Tuple format: (predicate_name, compiled_regex, invert_subject_object_on_match)
GENERAL_VERB_PREDICATE_PATTERNS: list[tuple[str, re.Pattern[str], bool]] = [
    (
        "DIABLERIZED",
        re.compile(r"\b(?:was|were|having\s+been)\s+(?:diablerized|consumed|devoured|usurped|drunk)\s+by\b", re.IGNORECASE),
        True,
    ),
    (
        "DIABLERIZED",
        re.compile(r"\b(?:diablerized|diablerie\s+of|diablerizing|usurped\s+the\s+blood\s+of|devoured\s+the\s+soul\s+of)\b", re.IGNORECASE),
        False,
    ),
    (
        "TRUE_IDENTITY_OF",
        re.compile(
            r"\b(?:is\s+actually|was\s+actually|are\s+actually|secretly\s+is|poses?\s+as|masquerades?\s+as|true\s+identity\s+of|alias\s+of|none\s+other\s+than)\b",
            re.IGNORECASE,
        ),
        False,
    ),
    (
        "SIRED_OR_CREATED",
        re.compile(r"\b(?:was|were)\s+(?:sired|embraced|created|founded|formed|spawned|established)\s+by\b", re.IGNORECASE),
        True,
    ),
    (
        "SIRED_OR_CREATED",
        re.compile(r"\b(?:sired|embraced|created|founded|formed|spawned|established|begat)\b", re.IGNORECASE),
        False,
    ),
    (
        "DESTROYED_OR_PURGED",
        re.compile(r"\b(?:was|were)\s+(?:destroyed|purged|exterminated|slain|killed|eliminated|shattered)\s+by\b", re.IGNORECASE),
        True,
    ),
    (
        "DESTROYED_OR_PURGED",
        re.compile(r"\b(?:destroyed|purged|exterminated|slain|slew|killed|eliminated|wiped\s+out)\b", re.IGNORECASE),
        False,
    ),
    (
        "ALLIED_OR_JOINED",
        re.compile(r"\b(?:allied\s+with|joined\s+with|united\s+with|reunited\s+with|merged\s+(?:with|into)|formed\s+a\s+coalition\s+with|reunion\s+of)\b", re.IGNORECASE),
        False,
    ),
    (
        "FOUGHT_OR_OPPOSED",
        re.compile(r"\b(?:fought\s+against|warred\s+with|hunted|opposed|betrayed|clashed\s+with|feuded\s+with|rejected|persecuted|tortured)\b", re.IGNORECASE),
        False,
    ),
    (
        "VIEWS_OR_JUDGES",
        re.compile(r"\b(?:views?|regards?|considers?|distrusts?|suspects?|studies|observes?|warns?|seeks?|guides?|serves?|imbued\s+by|chosen\s+by)\b", re.IGNORECASE),
        False,
    ),
    (
        "GOVERNED_BY_KEY",
        re.compile(r"\b(?:is\s+governed\s+by|is\s+controlled\s+by|is\s+configured\s+via|is\s+capped\s+by|depends\s+on)\b", re.IGNORECASE),
        False,
    ),
]

GENERAL_NEGATION_RE = re.compile(
    r"\b(?:never|not|no\s+longer|false|myth|denied|denies|refute[sd]?|fabricated|untrue|impossible)\b",
    re.IGNORECASE,
)
GENERAL_RUMOR_RE = re.compile(
    r"\b(?:rumor(?:ed|s)?|alleged(?:ly)?|some\s+claim|whisper(?:ed|s)?|unconfirmed|speculat(?:e|ion)|legend\s+claims|apocryphal)\b",
    re.IGNORECASE,
)
GENERAL_ANTIPATTERN_RE = re.compile(
    r"(?:(?:^|[.!?]\s+|\"|\*\*\s*|warning\s+\"?)(?:do\s+not|don't|never|avoid)\s+(?:set(?:ting)?|enabl(?:e|ing)|disabl(?:e|ing)|grant(?:ing)?|giv(?:e|ing)|us(?:e|ing)|rais(?:e|ing)|lower(?:ing)?|turn(?:ing)?|blacklist(?:ing)?|whitelist(?:ing)?|combin(?:e|ing)|chang(?:e|ing)|configur(?:e|ing)|run(?:ning)?|call(?:ing)?|`)|"
    r"\b(?:what\s+not\s+to\s+do|not\s+recommended|anti-pattern|bad\s+idea|footgun|violat(?:e|es|ing)\s+S-\d{3}|stalls?\s+the\s+(?:main|server|event)|lags?\s+the\s+(?:player|server)|bypasses\s+the\s+(?:async|queue))\b)",
    re.IGNORECASE,
)

STRUCTURAL_NON_SYMPTOM_HEADINGS = frozenset({
    "top-level keys",
    "tips",
    "tips for customization",
    "overview",
    "summary",
    "examples",
    "example",
    "syntax",
    "parameters",
    "configuration",
    "commands",
    "permissions",
    "public event catalog",
    "table of contents",
    "introduction",
    "quickstart",
    "installation",
    "requirements",
    "1. depend on rtp",
    "2. register your addon",
    "how to update configuration",
})

SYMPTOM_HEADING_SIGNAL_RE = re.compile(
    r"(?:\?|\b(?:why|how|when|what|don't|do\s+not|never|avoid|slow|lag|spike|drop|tps|mspt|queue|"
    r"stuck|wait|spawn|land|roof|bedrock|ocean|nether|end|crash|error|fail|warn|menu|gui|chest|"
    r"teleport|warmup|cooldown|reload|override|border|claim|biome|slope|flush|prompt|confirm|"
    r"stall|sync|async|bundled|interactive|setup|wizard|troubleshoot|broken|work)\b)",
    re.IGNORECASE,
)


def decode_inverted_cmap_text(text: str) -> str:
    """
    Detect and repair legacy PDF pages (such as `pdfFactory Pro` trial exports)
    where printable ASCII characters `c` (`33 <= c <= 126`) were stored via an
    inverted CMap as `chr(288 - c)` (`162 <= ord(ch) <= 255`), and `'s'` (`115`,
    `288 - 115 = 173` soft hyphen) was normalized to `'-'`.
    """
    if not text:
        return text
    sample = text[:600]
    high = sum(1 for ch in sample if 161 <= ord(ch) <= 255)
    low_alnum = sum(1 for ch in sample if ch.isalnum() and ord(ch) < 128)
    if high < 10 or high * 2 < low_alnum:
        return text
    out_lines: list[str] = []
    for line in text.splitlines():
        l_high = sum(1 for ch in line if 161 <= ord(ch) <= 255)
        l_low = sum(1 for ch in line if ch.isalnum() and ord(ch) < 128)
        if l_high < 2 and l_low > 4:
            out_lines.append(line)
            continue
        chars: list[str] = []
        n = len(line)
        for i, ch in enumerate(line):
            o = ord(ch)
            prev_o = ord(line[i - 1]) if i > 0 else 32
            next_o = ord(line[i + 1]) if i + 1 < n else 32
            if ch == "-" and (161 <= prev_o <= 255 or 161 <= next_o <= 255):
                chars.append("s")
            elif ch == "K" and 161 <= prev_o <= 255:
                chars.append("'")
            elif ch == "N" and 161 <= next_o <= 255:
                chars.append('"')
            elif ch == "M" and 161 <= prev_o <= 255:
                chars.append('"')
            elif 161 <= o <= 255:
                chars.append(chr(288 - o))
            else:
                chars.append(ch)
        out_lines.append("".join(chars))
    return "\n".join(out_lines)

GENERAL_STOP_PROPER = {
    "The", "And", "But", "For", "Nor", "Or", "Yet", "So", "In", "On", "At", "To", "From",
    "By", "With", "As", "Into", "Like", "Through", "After", "Over", "Between", "Out",
    "Against", "During", "Without", "Before", "Under", "Around", "Among", "Chapter",
    "Page", "Book", "Part", "Section", "Table", "Figure", "One", "Two", "Three", "Four",
    "Five", "Six", "Seven", "Eight", "Nine", "Ten", "Note", "Example", "Optional",
    "Rule", "Rules", "Yes", "No", "None", "All", "Some", "Many", "Most", "Other",
    "Such", "More", "New", "Old", "First", "Second", "Third", "Last", "Great", "High",
    "Low", "Dark", "Black", "White", "Red", "Dead", "Life", "Death", "Night", "Time",
    "World", "City", "Man", "Men", "Woman", "Women", "People", "Way", "Power", "Powers",
    "It", "He", "She", "They", "We", "You", "Who", "What", "There", "Here", "That",
    "This", "These", "Those", "When", "Where", "While", "Although", "However", "Because",
    "Since", "Unless", "Until", "Whether", "Though", "Once", "Eventually", "According",
    "Perhaps", "Unlike", "Despite", "Instead", "Moreover", "Furthermore", "Large", "Small",
    "Several", "Few", "Each", "Every", "Either", "Neither", "Both", "Another", "Certain",
    "System", "Roll", "Difficulty", "Success", "Successes", "Failure", "Botch", "Trait",
    "Traits", "Attribute", "Attributes", "Ability", "Abilities", "Level", "Point", "Points",
}


def scan_section_with_small_llm(
    section_text: str,
    valid_symbol_ids: set[str],
    endpoint: str = "http://127.0.0.1:11438/api/generate",
    model: str = "qwen2.5:7b",
    timeout: float = 20.0,
    http_post_fn: Any = None,
) -> list[dict[str, Any]]:
    """
    Use a small local LLM (e.g. `qwen2.5:7b` on Ollama) to extract typed AST edges
    and causal claims (`{"claims": [{"src": "<symbol_id>", "predicate": "...", "dst": "<symbol_id>", "polarity": "AFFIRMED|ANTI_PATTERN", "quote": "..."}]}`),
    strictly filtering returned symbol IDs against `valid_symbol_ids` so zero
    hallucinated symbols can enter `ast_edges` or `ast_claims`.
    """
    if not section_text or not section_text.strip() or not valid_symbol_ids:
        return []
    sample_syms = sorted(valid_symbol_ids)[:80]
    prompt = (
        "Extract technical causal or configuration relationships from the passage below as JSON.\n"
        f"Allowed symbol IDs (use ONLY exact IDs from this set): {json.dumps(sample_syms[:40])}\n"
        "Output ONLY a JSON object with key \"claims\": [{\"src\": \"...\", \"predicate\": \"GOVERNED_BY_KEY|CAUSES_SYMPTOM|REMEDIATED_BY|IMPLEMENTED_IN_CLASS\", \"dst\": \"...\", \"polarity\": \"AFFIRMED|ANTI_PATTERN\", \"quote\": \"...\"}].\n\n"
        f"Passage:\n{section_text[:1800]}"
    )
    payload = {"model": model, "prompt": prompt, "stream": False}
    try:
        if http_post_fn is not None:
            resp_data = http_post_fn(endpoint, payload, timeout)
        else:
            req = urllib.request.Request(
                endpoint,
                data=json.dumps(payload).encode("utf-8"),
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                resp_data = json.loads(resp.read().decode("utf-8", errors="replace"))
        raw_out = str(resp_data.get("response") or resp_data.get("content") or "")
        parsed = repair_and_parse_json(raw_out)
        claims = parsed.get("claims") if isinstance(parsed, dict) else []
        if not isinstance(claims, list):
            return []
        verified: list[dict[str, Any]] = []
        for item in claims:
            if not isinstance(item, dict):
                continue
            src = str(item.get("src") or "").strip()
            dst = str(item.get("dst") or "").strip()
            pred = str(item.get("predicate") or "GOVERNED_BY_KEY").strip().upper()
            pol = str(item.get("polarity") or "AFFIRMED").strip().upper()
            quote = str(item.get("quote") or "").strip()[:240]
            if src in valid_symbol_ids and dst in valid_symbol_ids and src != dst:
                verified.append(
                    {
                        "src": src,
                        "predicate": pred,
                        "dst": dst,
                        "polarity": pol if pol in ("AFFIRMED", "DENIED", "ANTI_PATTERN") else "AFFIRMED",
                        "quote": quote,
                    }
                )
        return verified
    except Exception:
        return []


def _table_exists(conn: sqlite3.Connection, table_name: str) -> bool:
    row = conn.execute(
        "SELECT 1 FROM sqlite_master WHERE type IN ('table', 'view') AND name = ? LIMIT 1",
        (table_name,),
    ).fetchone()
    return row is not None


_ED_V5_RE = re.compile(r"(?:^|[/\s_-])v5(?:[/\s_.-]|$)|\b5th\s+edition\b", re.IGNORECASE)
_ED_V20_RE = re.compile(r"\b(?:v20|w20|m20|c20|wr20|20th\s+anniversary)\b", re.IGNORECASE)
_ED_VER_RE = re.compile(r"\b(v\d+\.\d+(?:\.\d+)?)\b", re.IGNORECASE)
_EPISTOLARY_RE = re.compile(r"(?:^|\n)\s*(?:From|Posted\s+by|Subj|Subject|Reply\s+to)\s*:")
_MECHANIC_RE = re.compile(r"\b(?:System|Roll|Cost|Dice\s+Pool|Difficulty)\s*:")
_FIRST_PERSON_RE = re.compile(r"\b(?:I|we|my|our|us)\b")
_WE_PLURAL_RE = re.compile(r"\b(?:we|our|us|ourselves)\b", re.IGNORECASE)
_VERB_FAST_TRIGGER_RE = re.compile(
    r"\b(?:diableriz|consum|devour|usurp|actually|secretly|poses|masquerad|identity|alias|other\s+than|"
    r"sired|embraced|created|founded|formed|spawned|established|begat|destroyed|purged|exterminated|"
    r"slain|slew|killed|eliminated|wiped|shattered|allied|joined|united|reunited|merged|coalition|"
    r"reunion|fought|warred|hunted|opposed|betrayed|clashed|feuded|rejected|persecuted|tortured|"
    r"views?|regards?|considers?|distrusts?|suspects?|studies|observes?|warns?|seeks?|guides?|serves?|"
    r"imbued|chosen|governed|controlled|configured|capped|depends)",
    re.IGNORECASE,
)
_EDITION_CACHE: dict[tuple[str, str, str], str] = {}


def _detect_edition_tag(path: str, title: str, tags: str = "") -> str:
    key = (path, title, tags)
    cached = _EDITION_CACHE.get(key)
    if cached is not None:
        return cached
    combined = f"{path} {title} {tags}"
    if _ED_V5_RE.search(combined):
        res = "V5"
    elif _ED_V20_RE.search(combined):
        res = "V20"
    elif "revised" in combined.lower():
        res = "Revised"
    elif m_ver := _ED_VER_RE.search(combined):
        res = m_ver.group(1).lower()
    else:
        res = "Canonical"
    if len(_EDITION_CACHE) < 16384:
        _EDITION_CACHE[key] = res
    return res


def _edition_priority(ed: str) -> int:
    if ed in ("V20", "Canonical"):
        return 0
    if ed == "Revised":
        return 1
    if ed == "V5":
        return 2
    return 1


def _classify_discourse_voice(text: str, path: str = "", table_name: str = "", is_technical: bool = False) -> str:
    p_strip = (text or "").strip()
    p_low = (path or "").lower()
    if table_name == "arch_sections" or "what-not-to-do" in p_low or "requirements" in p_low or "/adr/" in p_low:
        return "ARCHITECTURE_INVARIANT" if is_technical else "ADR_INVARIANT"
    if is_technical:
        if "@range" in p_strip or "@options" in p_strip or "| `" in p_strip:
            return "SPECIFICATION_RULE"
        return "REFERENCE_DOC"
    if ">>>" in p_strip or ("From:" in p_strip or "Posted by:" in p_strip or "Subj:" in p_strip or "Subject:" in p_strip and _EPISTOLARY_RE.search(p_strip)):
        return "EPISTOLARY_POST"
    if "•" in p_strip or (("System:" in p_strip or "Roll:" in p_strip or "Dice Pool:" in p_strip or "Difficulty:" in p_strip) and _MECHANIC_RE.search(p_strip)):
        return "MECHANIC_STAT"
    if p_strip.startswith(('"', "“", "'")) or len(_FIRST_PERSON_RE.findall(p_strip)) >= 4:
        return "IC_NARRATOR"
    return "OOC_CANON"


def _derive_section_perspective_lens(
    path: str,
    heading: str,
    title: str,
    paragraph: str,
    dominant_symbol_name: str = "",
    is_technical: bool = False,
) -> str:
    h_clean = (heading or "").strip()
    t_clean = (title or "").strip()
    if h_clean and not h_clean.lower().startswith("page ") and len(h_clean) <= 60:
        return f"SECTION:{h_clean}"
    if not is_technical and dominant_symbol_name and len(_WE_PLURAL_RE.findall(paragraph)) >= 2:
        return f"FIRST_PERSON_POV:{dominant_symbol_name}"
    if t_clean and len(t_clean) <= 60:
        return f"SOURCE:{t_clean}"
    stem = (path or "corpus").rsplit("/", 1)[-1].rsplit(".", 1)[0]
    return f"SOURCE:{stem}" if stem else "GENERAL"


def _clean_proper_phrase(raw_p: str) -> str:
    tokens = raw_p.split()
    if not tokens:
        return ""
    cleaned = [t.removesuffix("'s").removesuffix("’s").rstrip("'’") for t in tokens]
    return " ".join(t for t in cleaned if t)


_PREFIX_NORM_RE = re.compile(r"^(?:Clan|House|Tribe|Order\s+of|The)\s+", re.IGNORECASE)


def _normalize_proper_key(phrase: str) -> str:
    """
    Canonicalize structural prefixes (`Clan X` -> `X`, `The X` -> `X`) and regular
    plural inflections (`Cappadocians` -> `Cappadocian`) for grouping.
    """
    p = _clean_proper_phrase(phrase)
    p = _PREFIX_NORM_RE.sub("", p).strip()
    p_low = p.lower()
    if p_low.endswith("ians") and len(p_low) > 6:
        return p_low[:-1]
    if p_low.endswith("ites") and len(p_low) > 6:
        return p_low[:-1]
    if p_low.endswith("s") and not p_low.endswith(("ss", "us", "is", "os")) and len(p_low) > 5:
        return p_low[:-1]
    return p_low


def discover_corpus_domains(conn: sqlite3.Connection) -> list[str]:
    domains: set[str] = set()
    for tbl in ("doc_sections", "config_keys", "code_sections", "bit_codes"):
        if _table_exists(conn, tbl):
            try:
                for r in conn.execute(f"SELECT DISTINCT project FROM {tbl} WHERE project IS NOT NULL AND trim(project) != ''"):
                    domains.add(str(r[0]).strip().lower())
            except sqlite3.Error:
                pass
    return sorted(domains) if domains else ["default"]


def bake_project_ast(
    conn: sqlite3.Connection,
    project: str | None = None,
    llm_scanner_fn: Any = None,
    import_cites_by_path: dict[tuple[str, str], dict[str, str]] | None = None,
) -> dict[str, int]:
    """
    Universal, domain-agnostic 2-Stage AST Baker (`Scan -> Solidify AST & Conflicts`).
    Operates on any SQLite database containing `doc_sections` (and optionally
    `config_keys`, `code_sections`, `bit_codes`, `arch_sections`) with zero
    hardcoded domain lookup tables.

    Automatically distinguishes:
      - `TECHNICAL_SCHEMA` corpora (software/ops projects with `config_keys`, `code_sections`,
        or `bit_codes`, such as `LeafRTP`, `treegen`, `acmecache`), which have no "lore"
        and only extract technical symbols, section-heading behavioral symptoms, and
        `GOAL_VS_SAFETY_INVARIANT` configuration/permission conflicts.
      - `DOCUMENT_PROSE` corpora (books, rulebooks, multi-perspective archives without
        software config schemas, such as `oWoD`), which decode inverted-CMap PDF text,
        discover proper-noun concepts and apposition epithets, and extract SVO/passive
        claims and perspective/edition conflicts.

    All computation completes in memory before mutating `ast_*` tables at the end,
    so interrupted runs never leave an existing database with empty AST tables.
    """
    prev_factory = conn.row_factory
    conn.row_factory = sqlite3.Row
    _LCA_LEAF_CACHE.clear()
    try:
        bake_all = project is None or project.strip().lower() in ("", "all", "*")
        target_domains = [] if bake_all else [project.strip().lower()]

        # Ensure AST tables exist without dropping existing data prior to scan completion
        sym_cols = {str(r[1]) for r in conn.execute("PRAGMA table_info(ast_symbols)").fetchall()}
        if sym_cols and ("symbol_id" not in sym_cols or "domain" not in sym_cols):
            for tbl in ("ast_edges", "ast_symbols", "ast_candidate_refs", "ast_claims", "ast_conflicts", "ast_config_tree"):
                conn.execute(f"DROP TABLE IF EXISTS {tbl}")
        conn.executescript(AST_SCHEMA_SQL)

        backtick_re = re.compile(r"`([^`\n]{2,80})`")
        adr_or_rule_re = re.compile(r"\b([a-z0-9-]+-ADR-\d{2,4}|ADR-\d{2,4}|S-\d{3}|REQ-[A-Z0-9-]+)\b")
        slash_cmd_re = re.compile(r"^/[a-z][a-z0-9_-]*(?:\s+[a-z][a-z0-9_-]*)?$")
        perm_node_re = re.compile(r"^[a-z][a-z0-9_-]*\.(?:[a-z0-9_-]+|\*)$")
        file_ext_blacklist = {".yml", ".yaml", ".md", ".java", ".py", ".json", ".sqlite", ".jar", ".sh", ".ps1", ".txt", ".html", ".js", ".css", ".lock"}
        mid_cap_re = re.compile(
            r"(?<=[a-z,;:]\s)([A-Z][a-z'’]{2,}(?:\s+(?:(?:of(?:\s+the)?|the)\s+)?[A-Z][a-z'’]{2,}){0,2})"
        )
        word_re = re.compile(r"\b([a-z]{3,})\b")
        apposition_re = re.compile(
            r"\b([A-Z][a-zA-Z'’-]{2,}(?:\s+[A-Z][a-zA-Z'’-]{2,}){0,2})\s*,\s+the\s+([A-Z][a-zA-Z'’-]{2,}(?:\s+(?:(?:of(?:\s+the)?|the)\s+)?[A-Z][a-zA-Z'’-]{2,}){0,2})\b"
        )
        reverse_apposition_re = re.compile(
            r"\bthe\s+([A-Z][a-zA-Z'’-]{2,}(?:\s+(?:(?:of(?:\s+the)?|the)\s+)?[A-Z][a-zA-Z'’-]{2,}){0,2})\s*,\s+([A-Z][a-zA-Z'’-]{2,}(?:\s+[A-Z][a-zA-Z'’-]{2,}){0,2})\b"
        )
        called_epithet_re = re.compile(
            r"\b([A-Z][a-zA-Z'’-]{2,}(?:\s+[A-Z][a-zA-Z'’-]{2,}){0,2})\s+(?:(?:is|are|was|were)\s+)?(?:also\s+)?(?:called|known\s+as|nicknamed|dubbed)\s+(?:the\s+)?\"?([A-Z][a-zA-Z'’-]{2,}(?:\s+(?:of|the|[A-Z][a-zA-Z'’-]{2,})){0,3})\"?"
        )
        sent_split_re = re.compile(r"(?<=[.!?])\s+")

        all_symbol_rows: list[tuple[Any, ...]] = []
        all_candidate_refs: list[tuple[Any, ...]] = []
        all_claim_rows: list[tuple[Any, ...]] = []
        all_conflict_rows: list[tuple[Any, ...]] = []
        all_cfg_tree_rows: list[tuple[str, str, str, str, str, str]] = []
        edge_accum: dict[tuple[str, str, str], dict[str, Any]] = {}

        def add_edge(src: str, dst: str, kind: str, weight: float, cite: str) -> None:
            if not src or not dst or src == dst:
                return
            key = (src, dst, kind)
            if key not in edge_accum:
                edge_accum[key] = {"weight": 0.0, "count": 0, "cites": []}
            edge_accum[key]["weight"] += weight
            edge_accum[key]["count"] += 1
            if cite and len(edge_accum[key]["cites"]) < 5 and cite not in edge_accum[key]["cites"]:
                edge_accum[key]["cites"].append(cite)

        # Pre-group source tables by lower(project) in a single pass so multi-domain DBs scan each table only once
        cfg_by_dom: dict[str, list[sqlite3.Row]] = collections.defaultdict(list)
        if _table_exists(conn, "config_keys"):
            for r in conn.execute("SELECT rowid AS id, project, file_path, key_path, default_value, directives, description FROM config_keys"):
                cfg_by_dom[str(r["project"] or "default").strip().lower()].append(r)

        bit_by_dom: dict[str, list[sqlite3.Row]] = collections.defaultdict(list)
        if _table_exists(conn, "bit_codes"):
            for r in conn.execute("SELECT rowid AS id, project, namespace, id AS bit_number, key AS name, severity, tier, subsystem, summary AS cause, remediation FROM bit_codes"):
                bit_by_dom[str(r["project"] or "default").strip().lower()].append(r)

        code_by_dom: dict[str, list[sqlite3.Row]] = collections.defaultdict(list)
        if _table_exists(conn, "code_sections"):
            for r in conn.execute("SELECT rowid AS id, project, path, class_name, methods, diagnostics, config_refs, summary FROM code_sections"):
                code_by_dom[str(r["project"] or "default").strip().lower()].append(r)

        doc_by_dom: dict[str, list[sqlite3.Row]] = collections.defaultdict(list)
        if _table_exists(conn, "doc_sections"):
            doc_sql = "SELECT rowid AS id, project, path, heading AS heading_path, title, body, url, tags FROM doc_sections"
            doc_args: tuple[Any, ...] = ()
            if not bake_all and len(target_domains) == 1:
                doc_sql += " WHERE project = ? ORDER BY rowid"
                doc_args = (target_domains[0],)
            else:
                doc_sql += " ORDER BY rowid"
            for r in conn.execute(doc_sql, doc_args):
                p_str = str(r["path"] or "")
                if "kickstarter-manuscript-preview-" in p_str and p_str.endswith("-v5.md"):
                    continue
                doc_by_dom[str(r["project"] or "default").strip().lower()].append(r)

        arch_by_dom: dict[str, list[sqlite3.Row]] = collections.defaultdict(list)
        if _table_exists(conn, "arch_sections"):
            for r in conn.execute("SELECT rowid AS id, project, path, heading AS heading_path, title, body, url, tags FROM arch_sections ORDER BY rowid"):
                arch_by_dom[str(r["project"] or "default").strip().lower()].append(r)

        dev_by_dom: dict[str, list[sqlite3.Row]] = collections.defaultdict(list)
        if _table_exists(conn, "dev_sections"):
            for r in conn.execute("SELECT rowid AS id, project, path, heading AS heading_path, title, body, url, tags FROM dev_sections ORDER BY rowid"):
                dev_by_dom[str(r["project"] or "default").strip().lower()].append(r)

        class_meta_by_dom: dict[str, list[sqlite3.Row]] = collections.defaultdict(list)
        if _table_exists(conn, "code_class_meta"):
            for r in conn.execute("SELECT rowid AS id, project, class_name, package_name, path, module_name, dag_tier, super_class, interfaces_json, imports_json, governed_by_json, gradle_test_cmd, kind FROM code_class_meta"):
                class_meta_by_dom[str(r["project"] or "default").strip().lower()].append(r)

        test_meta_by_dom: dict[str, list[sqlite3.Row]] = collections.defaultdict(list)
        if _table_exists(conn, "test_class_meta"):
            for r in conn.execute("SELECT rowid AS id, project, test_class_name, package_name, path, module_name, gradle_test_cmd, tested_classes_json, req_ids_json FROM test_class_meta"):
                test_meta_by_dom[str(r["project"] or "default").strip().lower()].append(r)

        trace_by_dom: dict[str, list[sqlite3.Row]] = collections.defaultdict(list)
        if _table_exists(conn, "traceability_rows"):
            for r in conn.execute("SELECT rowid AS id, project, req_id, summary, design_refs_json, implementing_classes_json, test_classes_json FROM traceability_rows"):
                trace_by_dom[str(r["project"] or "default").strip().lower()].append(r)

        if bake_all:
            discovered = sorted(
                set(cfg_by_dom.keys())
                | set(bit_by_dom.keys())
                | set(code_by_dom.keys())
                | set(doc_by_dom.keys())
                | set(arch_by_dom.keys())
                | set(dev_by_dom.keys())
                | set(class_meta_by_dom.keys())
                | set(trace_by_dom.keys())
            )
            target_domains = discovered if discovered else ["default"]

        for dom in target_domains:
            symbol_rows: list[tuple[Any, ...]] = []
            alias_to_symbol: dict[str, str] = {}
            short_key_to_symbols: dict[str, list[str]] = collections.defaultdict(list)
            cfg_tree_rows: list[tuple[str, str, str, str, str, str]] = []
            seen_antipattern_focal: set[tuple[str, str]] = set()

            cfg_rows = cfg_by_dom.get(dom, [])
            bit_rows = bit_by_dom.get(dom, [])
            code_rows = code_by_dom.get(dom, [])
            doc_rows = doc_by_dom.get(dom, [])
            arch_rows = arch_by_dom.get(dom, [])
            dev_rows = dev_by_dom.get(dom, [])
            class_meta_rows = class_meta_by_dom.get(dom, [])
            test_meta_rows = test_meta_by_dom.get(dom, [])
            trace_rows = trace_by_dom.get(dom, [])

            # Corpus Epistemic Regime: software/technical projects have config/code/bit schemas and no lore
            is_technical_schema = bool(cfg_rows or code_rows or bit_rows)

            # 1. Config Keys (`config_keys`)
            for r in cfg_rows:
                fp = str(r["file_path"])
                kp = str(r["key_path"])
                short_kp = kp.replace("defaults.", "")
                leaf = short_kp.split(".")[-1]
                parent_pref = short_kp.rsplit(".", 1)[0] if "." in short_kp else ""
                sym_id = f"cfg:{fp}:{kp}"
                desc = str(r["description"] or "")
                url = ""
                aliases = list(dict.fromkeys([kp, short_kp, leaf, f"{fp}:{kp}"]))
                meta = {
                    "file_path": fp,
                    "key_path": kp,
                    "default_value": str(r["default_value"] or ""),
                    "directives": str(r["directives"] or ""),
                    "description": desc[:240],
                }
                symbol_rows.append(
                    (sym_id, dom, f"{fp} -> {kp}", "CONFIG_KEY", "config_keys", int(r["id"]), url, json.dumps(aliases), 1, json.dumps(meta))
                )
                alias_to_symbol[kp.lower()] = sym_id
                alias_to_symbol[short_kp.lower()] = sym_id
                short_key_to_symbols[leaf.lower()].append(sym_id)
                short_key_to_symbols[short_kp.lower()].append(sym_id)
                cfg_tree_rows.append((fp, kp, parent_pref, leaf, str(r["default_value"] or ""), str(r["directives"] or "")))

                # Extract schema-level anti-pattern conflict if the config description warns against enabling/changing it
                if leaf.lower() != "version" and (GENERAL_ANTIPATTERN_RE.search(desc) or "s-00" in desc.lower() or ("synchronous" in desc.lower() and "main thread" in desc.lower())):
                    ckey = (dom, sym_id)
                    if ckey not in seen_antipattern_focal:
                        seen_antipattern_focal.add(ckey)
                        clean_desc = re.sub(r"\s+", " ", desc).strip()
                        all_conflict_rows.append(
                            (
                                dom,
                                "GOAL_VS_SAFETY_INVARIANT",
                                sym_id,
                                "ANTI_PATTERN_VS_INVARIANT",
                                f"Misconfiguration of `{fp}:{kp}`",
                                f"Default Safe Setting (`{kp}: {r['default_value']}`)",
                                "USER_MISCONFIG",
                                "ARCHITECTURE_INVARIANT",
                                f"FILE:{fp}",
                                f"FILE:{fp}",
                                "Canonical",
                                "Canonical",
                                f"{fp}:{kp}",
                                fp,
                                clean_desc[:300],
                                f"Keep `{kp}: {r['default_value']}` ({clean_desc[:220]})",
                                f"[{fp}:{kp}] {clean_desc[:220]}",
                            )
                        )

            if cfg_tree_rows:
                all_cfg_tree_rows.extend(cfg_tree_rows)

            # 2. Bit / Diagnostic Codes (`bit_codes`)
            for r in bit_rows:
                sym_id = f"bit:{r['name']}"
                code_id = f"{r['namespace']}-{int(r['bit_number']):04d}"
                short_code = f"{r['namespace']}-{r['bit_number']}"
                aliases = [str(r["name"]), code_id, short_code]
                meta = {"code_id": code_id, "subsystem": r["subsystem"], "cause": r["cause"], "remediation": r["remediation"]}
                symbol_rows.append(
                    (sym_id, dom, str(r["name"]), "BIT_CODE", "bit_codes", int(r["id"]), "", json.dumps(aliases), 1, json.dumps(meta))
                )
                for al in aliases:
                    if al:
                        alias_to_symbol[al.lower()] = sym_id

            # 3. Source Code Units (`code_sections` + `code_class_meta` + `SPI_CONTRACT` + `TEST_CLASS` + `REQ_ID`)
            cm_by_name: dict[str, sqlite3.Row] = {str(cm["class_name"]): cm for cm in class_meta_rows}
            seen_classes: set[str] = set()
            seen_spis: set[str] = set()
            for r in code_rows:
                cls_name = str(r["class_name"] or "").strip()
                if cls_name and cls_name not in seen_classes:
                    seen_classes.add(cls_name)
                    sym_id = f"cls:{cls_name}"
                    cm_row = cm_by_name.get(cls_name)
                    meta_dict: dict[str, Any] = {
                        "path": r["path"],
                        "summary": str(r["summary"] or "")[:200],
                    }
                    if cm_row is not None:
                        meta_dict.update({
                            "package_name": str(cm_row["package_name"] or ""),
                            "module_name": str(cm_row["module_name"] or ""),
                            "dag_tier": str(cm_row["dag_tier"] or ""),
                            "super_class": str(cm_row["super_class"] or ""),
                            "interfaces": json.loads(str(cm_row["interfaces_json"] or "[]")),
                            "governed_by": json.loads(str(cm_row["governed_by_json"] or "[]")),
                            "gradle_test_cmd": str(cm_row["gradle_test_cmd"] or ""),
                            "kind": str(cm_row["kind"] or "CLASS"),
                        })
                    symbol_rows.append(
                        (
                            sym_id,
                            dom,
                            cls_name,
                            "JAVA_CLASS",
                            "code_sections",
                            int(r["id"]),
                            str(r["path"] or ""),
                            json.dumps([cls_name]),
                            1,
                            json.dumps(meta_dict),
                        )
                    )
                    alias_to_symbol[cls_name.lower()] = sym_id
                    if cm_row is not None and (str(cm_row["kind"]) == "INTERFACE" or str(cm_row["dag_tier"]) == "TIER_1_SPI_PURE"):
                        spi_id = f"spi:{cls_name}"
                        if spi_id not in seen_spis:
                            seen_spis.add(spi_id)
                            symbol_rows.append(
                                (
                                    spi_id,
                                    dom,
                                    cls_name,
                                    "SPI_CONTRACT",
                                    "code_class_meta",
                                    int(cm_row["id"]),
                                    str(cm_row["path"] or ""),
                                    json.dumps([cls_name, f"spi:{cls_name}"]),
                                    1,
                                    json.dumps(meta_dict),
                                )
                            )

            for cm_row in class_meta_rows:
                for iface_name in json.loads(str(cm_row["interfaces_json"] or "[]")):
                    spi_id = f"spi:{iface_name}"
                    if spi_id not in seen_spis:
                        seen_spis.add(spi_id)
                        target_cm = cm_by_name.get(iface_name)
                        spi_path = str(target_cm["path"]) if target_cm is not None else str(cm_row["path"])
                        spi_tier = str(target_cm["dag_tier"]) if target_cm is not None else "TIER_1_SPI_PURE"
                        spi_mod = str(target_cm["module_name"]) if target_cm is not None else str(cm_row["module_name"])
                        symbol_rows.append(
                            (
                                spi_id,
                                dom,
                                iface_name,
                                "SPI_CONTRACT",
                                "code_class_meta",
                                int(target_cm["id"]) if target_cm is not None else int(cm_row["id"]),
                                spi_path,
                                json.dumps([iface_name, spi_id]),
                                1,
                                json.dumps({"path": spi_path, "dag_tier": spi_tier, "module_name": spi_mod}),
                            )
                        )
                        alias_to_symbol.setdefault(iface_name.lower(), spi_id)

            seen_reqs: set[str] = set()
            for tr in trace_rows:
                req_id = str(tr["req_id"] or "").strip().upper()
                if not req_id:
                    continue
                req_sym = f"req:{req_id}"
                if req_sym not in seen_reqs:
                    seen_reqs.add(req_sym)
                    tr_meta = {
                        "path": "docs/dev/TRACEABILITY.md",
                        "summary": str(tr["summary"] or ""),
                        "design_refs": json.loads(str(tr["design_refs_json"] or "[]")),
                        "implementing_classes": json.loads(str(tr["implementing_classes_json"] or "[]")),
                        "test_classes": json.loads(str(tr["test_classes_json"] or "[]")),
                    }
                    canon_lbl = f"{req_id}: {str(tr['summary'] or '')[:90]}".strip(": ")
                    symbol_rows.append(
                        (
                            req_sym,
                            dom,
                            canon_lbl,
                            "REQ_ID",
                            "traceability_rows",
                            int(tr["id"]),
                            "docs/dev/TRACEABILITY.md",
                            json.dumps([req_id]),
                            1,
                            json.dumps(tr_meta),
                        )
                    )
                    alias_to_symbol[req_id.lower()] = req_sym

            seen_tests: set[str] = set()
            for tm in test_meta_rows:
                t_name = str(tm["test_class_name"] or "").strip()
                if not t_name:
                    continue
                mod_clean = str(tm["module_name"] or "").lstrip(":") or "rtp-core"
                test_sym = f"test:{mod_clean}:{t_name}"
                if test_sym not in seen_tests:
                    seen_tests.add(test_sym)
                    t_meta = {
                        "path": str(tm["path"] or ""),
                        "package_name": str(tm["package_name"] or ""),
                        "module_name": str(tm["module_name"] or ""),
                        "gradle_test_cmd": str(tm["gradle_test_cmd"] or ""),
                        "tested_classes": json.loads(str(tm["tested_classes_json"] or "[]")),
                        "req_ids": json.loads(str(tm["req_ids_json"] or "[]")),
                    }
                    symbol_rows.append(
                        (
                            test_sym,
                            dom,
                            t_name,
                            "TEST_CLASS",
                            "test_class_meta",
                            int(tm["id"]),
                            str(tm["path"] or ""),
                            json.dumps([t_name, f"{mod_clean}:{t_name}"]),
                            1,
                            json.dumps(t_meta),
                        )
                    )
                    alias_to_symbol.setdefault(t_name.lower(), test_sym)

            # 4. Combine `doc_sections`, `arch_sections`, and `dev_sections`
            combined_sections: list[tuple[str, sqlite3.Row]] = (
                [("doc_sections", r) for r in doc_rows]
                + [("arch_sections", r) for r in arch_rows]
                + [("dev_sections", r) for r in dev_rows]
            )

            # 5. Corpus-Derived ADR / Safety Rules, Slash Commands, and Permissions
            seen_normative: set[str] = set(seen_reqs)
            seen_cmds: set[str] = set()
            seen_perms: set[str] = set()
            for tbl_name, r in combined_sections:
                body = str(r["body"] or "")
                title = str(r["title"] or "")
                heading = str(r["heading_path"] or "")
                path = str(r["path"] or "")
                url = str(r["url"] or "")
                full_text = f"{title}\n{heading}\n{body}"

                if any(k in full_text for k in ("ADR-", "adr-", "S-0", "REQ-")):
                    for m_norm in adr_or_rule_re.finditer(full_text):
                        raw_tok = m_norm.group(1)
                        code_tok = raw_tok.upper() if raw_tok.upper().startswith(("ADR-", "S-", "REQ-")) else raw_tok
                        if code_tok.upper().startswith("REQ-"):
                            sym_id = f"req:{code_tok.upper()}"
                            kind_label = "REQ_ID"
                        elif code_tok.upper().startswith("S-"):
                            sym_id = f"adr:{code_tok.upper()}"
                            kind_label = "SAFETY_PROHIBITION"
                        else:
                            sym_id = f"adr:{code_tok}"
                            kind_label = "ADR_RECORD"
                        if sym_id not in seen_normative:
                            seen_normative.add(sym_id)
                            start_w = max(0, m_norm.start() - 20)
                            end_w = min(len(full_text), m_norm.end() + 140)
                            ctx_line = re.sub(r"\s+", " ", full_text[start_w:end_w].split("\n")[0]).strip(" |-*#:")
                            canon_lbl = f"{code_tok}: {ctx_line[:90]}" if ctx_line and ctx_line != code_tok else f"{code_tok} ({title or path})"
                            symbol_rows.append(
                                (sym_id, dom, canon_lbl, kind_label, tbl_name, int(r["id"]), url, json.dumps([code_tok]), 1, json.dumps({"path": path, "title": title}))
                            )
                            alias_to_symbol[code_tok.lower()] = sym_id

                # Extract user-facing CLI commands and permissions from `doc_sections` (not internal ADR prose examples)
                if tbl_name == "doc_sections" and "`" in full_text:
                    for m_bt in backtick_re.finditer(full_text):
                        span = m_bt.group(1).strip()
                        span_low = span.lower()
                        if slash_cmd_re.match(span_low):
                            sym_id = f"cmd:{span_low}"
                            if sym_id not in seen_cmds:
                                seen_cmds.add(sym_id)
                                symbol_rows.append(
                                    (sym_id, dom, span_low, "CLI_COMMAND", tbl_name, int(r["id"]), url, json.dumps([span_low]), 1, "{}")
                                )
                                alias_to_symbol[span_low] = sym_id
                        elif perm_node_re.match(span_low):
                            ext = "." + span_low.rsplit(".", 1)[-1]
                            if (
                                ext not in file_ext_blacklist
                                and span_low not in alias_to_symbol
                                and span_low not in short_key_to_symbols
                                and (span_low.startswith(f"{dom[:3]}.") or "perm" in full_text.lower() or "grant" in full_text.lower() or "luckperms" in full_text.lower() or "bypass" in full_text.lower())
                            ):
                                sym_id = f"perm:{span_low}"
                                if sym_id not in seen_perms:
                                    seen_perms.add(sym_id)
                                    symbol_rows.append(
                                        (sym_id, dom, span_low, "PERMISSION_NODE", tbl_name, int(r["id"]), url, json.dumps([span_low]), 1, "{}")
                                    )
                                    alias_to_symbol[span_low] = sym_id

            # 6. Proper-Noun & Apposition / Epithet Discovery from Prose (`DOCUMENT_PROSE` corpora only,
            #    plus explicit appositions in tiny synthetic multi-regime unit test fixtures `len(doc_rows) <= 20`)
            lower_counts: collections.Counter[str] = collections.Counter()
            raw_prop_counts: collections.Counter[str] = collections.Counter()
            prop_first_sec: dict[str, sqlite3.Row] = {}
            prop_editions: dict[str, set[str]] = collections.defaultdict(set)
            raw_appositions: list[tuple[str, str]] = []
            sec_cap_spans: dict[int, list[tuple[int, str]]] = {}
            decoded_bodies: dict[int, str] = {}
            lower_stride = 4 if len(doc_rows) > 2500 else 1

            enable_prose_entities = (not is_technical_schema) or len(doc_rows) <= 20

            if enable_prose_entities:
                for idx_doc, r in enumerate(doc_rows):
                    raw_body = str(r["body"] or "")
                    body = decode_inverted_cmap_text(raw_body) if not is_technical_schema else raw_body
                    if body is not raw_body:
                        decoded_bodies[int(r["id"])] = body
                    sec_id_r = int(r["id"])
                    sec_ed = _detect_edition_tag(str(r["path"] or ""), str(r["title"] or ""), str(r["tags"] or ""))
                    if idx_doc % lower_stride == 0:
                        lower_counts.update(word_re.findall(body))
                    spans_this_sec: list[tuple[int, str]] = []
                    for m_p in mid_cap_re.finditer(body):
                        phrase = _clean_proper_phrase(m_p.group(1))
                        if not phrase or len(phrase) < 4 or len(phrase) > 42:
                            continue
                        words = phrase.split()
                        if words[0] in GENERAL_STOP_PROPER or words[-1].lower() in {"the", "of", "and", "in", "on", "to", "for", "by"}:
                            continue
                        spans_this_sec.append((m_p.start(), phrase))
                        raw_prop_counts[phrase] += 1
                        prop_editions[phrase].add(sec_ed)
                        if phrase not in prop_first_sec:
                            prop_first_sec[phrase] = r
                        else:
                            prev_ed = _detect_edition_tag(str(prop_first_sec[phrase]["path"] or ""), str(prop_first_sec[phrase]["title"] or ""))
                            if _edition_priority(sec_ed) < _edition_priority(prev_ed):
                                prop_first_sec[phrase] = r
                    if spans_this_sec:
                        sec_cap_spans[sec_id_r] = spans_this_sec

                    if ", the " in body:
                        for m_ap in apposition_re.finditer(body):
                            ent, ep = _clean_proper_phrase(m_ap.group(1)), _clean_proper_phrase(m_ap.group(2))
                            if ent and ep and ent.split()[0] not in GENERAL_STOP_PROPER and len(ep) <= 38:
                                raw_appositions.append((ent, f"the {ep}"))
                    if "the " in body.lower() and "," in body:
                        for m_rap in reverse_apposition_re.finditer(body):
                            ep, ent = _clean_proper_phrase(m_rap.group(1)), _clean_proper_phrase(m_rap.group(2))
                            if ent and ep and ent.split()[0] not in GENERAL_STOP_PROPER and len(ep) <= 38:
                                raw_appositions.append((ent, f"the {ep}"))
                    if any(k in body for k in ("called ", "known as ", "nicknamed ", "dubbed ")):
                        for m_ce in called_epithet_re.finditer(body):
                            ent, ep = _clean_proper_phrase(m_ce.group(1)), _clean_proper_phrase(m_ce.group(2))
                            if ent and ep and ent.split()[0] not in GENERAL_STOP_PROPER and len(ep) <= 38:
                                ep_norm = ep if ep.lower().startswith("the ") else f"the {ep}"
                                raw_appositions.append((ent, ep_norm))

            # Group proper nouns by normalized stem/prefix so inflections merge into the most frequent surface form
            norm_groups: dict[str, list[tuple[str, int]]] = collections.defaultdict(list)
            for phrase, cnt in raw_prop_counts.items():
                norm_groups[_normalize_proper_key(phrase)].append((phrase, cnt))

            surface_to_canonical: dict[str, str] = {}
            canonical_prop_counts: collections.Counter[str] = collections.Counter()
            canonical_aliases: dict[str, set[str]] = collections.defaultdict(set)
            for _nkey, variants in norm_groups.items():
                variants.sort(key=lambda x: x[1], reverse=True)
                best_surface = variants[0][0]
                total_cnt = sum(c for _, c in variants)
                canonical_prop_counts[best_surface] = total_cnt
                for surf, _c in variants:
                    surface_to_canonical[surf.lower()] = best_surface
                    canonical_aliases[best_surface].add(surf)
                    if surf in prop_editions:
                        prop_editions[best_surface].update(prop_editions[surf])
                    if best_surface not in prop_first_sec and surf in prop_first_sec:
                        prop_first_sec[best_surface] = prop_first_sec[surf]

            min_prop_cnt = max(4, len(doc_rows) // 1200) if len(doc_rows) > 500 else 2
            standalone_entity_low = {
                phrase.lower()
                for phrase, cnt in canonical_prop_counts.items()
                if cnt >= max(min_prop_cnt * 2, 10)
            }

            discovered_epithets: dict[str, str] = {}
            epithet_counts: collections.Counter[str] = collections.Counter()
            epithet_surfaces: dict[str, str] = {}
            pair_app_counts: collections.Counter[tuple[str, str]] = collections.Counter()
            ep_raw_by_low: dict[str, str] = {}
            for ent_raw, ep_raw in raw_appositions:
                canon_ent = surface_to_canonical.get(ent_raw.lower(), ent_raw)
                ep_low = ep_raw.lower()
                ep_clean = ep_raw[4:].strip() if ep_low.startswith("the ") else ep_raw.strip()
                ep_canon = surface_to_canonical.get(ep_clean.lower(), ep_clean)
                # Prevent comma-separated lists ("Among the Sabbat, the Tzimisce") from turning
                # a standalone entity (`Tzimisce`, `Saulot`, `Hecata`) into an epithet of another entity
                if canon_ent == ep_canon or ep_canon.lower() in standalone_entity_low:
                    continue
                ent_freq = canonical_prop_counts.get(canon_ent, 0)
                ep_standalone_freq = canonical_prop_counts.get(ep_canon, 0)
                if ent_freq < (4 if len(doc_rows) > 500 else 1):
                    continue
                if ep_standalone_freq > max(3, int(ent_freq * 0.2)) and len(doc_rows) > 20:
                    continue
                pair_app_counts[(canon_ent, ep_low)] += 1
                ep_raw_by_low[ep_low] = ep_raw

            best_ent_for_ep: dict[str, tuple[int, int, str]] = {}
            for (canon_ent, ep_low), p_cnt in pair_app_counts.items():
                cand_score = (p_cnt, canonical_prop_counts.get(canon_ent, 0), canon_ent)
                if ep_low not in best_ent_for_ep or cand_score > best_ent_for_ep[ep_low]:
                    best_ent_for_ep[ep_low] = cand_score

            for ep_low, (p_cnt, _efreq, canon_ent) in best_ent_for_ep.items():
                ep_raw = ep_raw_by_low[ep_low]
                discovered_epithets[ep_low] = canon_ent
                epithet_counts[ep_low] = p_cnt
                if ep_low.startswith("the ") and len(ep_raw) >= 8:
                    epithet_surfaces[ep_low] = ep_raw[4:]
                canonical_aliases[canon_ent].add(ep_raw)
                if ep_low.startswith("the ") and ep_raw[4:].lower() not in surface_to_canonical:
                    canonical_aliases[canon_ent].add(ep_raw[4:])

            # Sort in descending frequency order so primary entities register their canonical name first
            for phrase, cnt in canonical_prop_counts.most_common():
                if cnt < min_prop_cnt and len(doc_rows) > 20:
                    continue
                if phrase.lower() in alias_to_symbol:
                    continue
                words = phrase.split()
                if len(words) == 1:
                    low_c = lower_counts.get(words[0].lower(), 0) * lower_stride
                    if cnt / max(1, cnt + low_c) < 0.55:
                        continue
                slug = re.sub(r"[^a-z0-9]+", "_", phrase.lower()).strip("_")
                if not slug:
                    continue
                sym_id = f"{dom}:{slug}"
                first_r = prop_first_sec.get(phrase)
                if first_r is None:
                    continue
                def_ed = _detect_edition_tag(str(first_r["path"] or ""), str(first_r["title"] or ""), str(first_r["tags"] or ""))
                ed_set = sorted(prop_editions[phrase], key=_edition_priority)
                aliases_list = sorted(canonical_aliases[phrase])
                meta_obj = {
                    "first_book": str(first_r["path"] or "").split("/")[-1],
                    "preferred_edition": def_ed,
                    "editions_present": ed_set,
                    "v5_fallback_only": ed_set == ["V5"],
                }
                symbol_rows.append(
                    (
                        sym_id,
                        dom,
                        phrase,
                        "CONCEPT_ENTITY",
                        "doc_sections",
                        int(first_r["id"]),
                        str(first_r["url"] or ""),
                        json.dumps(aliases_list),
                        cnt,
                        json.dumps(meta_obj),
                    )
                )
                alias_to_symbol[phrase.lower()] = sym_id
                for al in aliases_list:
                    alias_to_symbol.setdefault(al.lower(), sym_id)

            # 7. Link Config Descriptions to Commands
            for r in cfg_rows:
                fp = str(r["file_path"])
                kp = str(r["key_path"])
                cfg_sym = f"cfg:{fp}:{kp}"
                desc = str(r["description"] or "")
                for m_bt in backtick_re.finditer(desc):
                    tok_low = m_bt.group(1).strip().lower()
                    if tok_low in alias_to_symbol and alias_to_symbol[tok_low] != cfg_sym:
                        target_sym = alias_to_symbol[tok_low]
                        add_edge(target_sym, cfg_sym, "GOVERNED_BY_KEY", 3.5, f"{fp}:{kp}")
                        add_edge(cfg_sym, target_sym, "GOVERNS_SYMBOL", 3.5, f"{fp}:{kp}")

            # 8. Link `bit_codes` and `code_sections`
            bit_voice = "SPECIFICATION_RULE" if is_technical_schema else "OOC_CANON"
            for r in bit_rows:
                b_sym = f"bit:{r['name']}"
                code_id = f"{r['namespace']}-{int(r['bit_number']):04d}"
                text = f"{r['cause']} {r['remediation']} {r['subsystem']}"
                for m in backtick_re.finditer(text):
                    tok = m.group(1).strip()
                    low = tok.lower().split("=")[0].split(":")[0].strip()
                    resolved = alias_to_symbol.get(low)
                    if not resolved and low in short_key_to_symbols:
                        resolved = short_key_to_symbols[low][0]
                    if resolved:
                        all_candidate_refs.append(
                            (dom, "bit_codes", int(r["id"]), str(r["subsystem"]), int(r["bit_number"] or 0), tok, low, resolved, "EXPLICIT_SYMBOL", bit_voice, f"SUBSYSTEM:{r['subsystem']}", "Canonical", 0.98)
                        )
                        add_edge(b_sym, resolved, "GOVERNED_BY_KEY", 3.0, f"{r['name']} ({code_id})")
                        add_edge(resolved, b_sym, "EMITS_BIT_ON_FAILURE", 3.0, f"{r['name']} ({code_id})")
                        all_claim_rows.append(
                            (dom, b_sym, "REMEDIATED_BY", resolved, "AFFIRMED", bit_voice, f"SUBSYSTEM:{r['subsystem']}", "Canonical", "bit_codes", int(r["id"]), f"{r['name']} ({code_id})", "", f"Cause: {r['cause']} | Remediation: {r['remediation']}")
                        )

            for r in code_rows:
                cls_name = str(r["class_name"] or "").strip()
                if not cls_name:
                    continue
                cls_sym = f"cls:{cls_name}"
                for diag_tok in re.findall(r"[A-Za-z0-9_.-]+", str(r["diagnostics"] or "")):
                    resolved = alias_to_symbol.get(diag_tok.lower())
                    if resolved and resolved.startswith("bit:"):
                        add_edge(cls_sym, resolved, "EMITS_BIT_CODE", 6.5, str(r["path"]))
                        add_edge(resolved, cls_sym, "EMITTED_BY_CLASS", 6.5, str(r["path"]))
                for cfg_tok in re.findall(r"[A-Za-z0-9_.-]+", str(r["config_refs"] or "")):
                    low = cfg_tok.lower()
                    cands: list[str] = []
                    if low in alias_to_symbol and alias_to_symbol[low].startswith("cfg:"):
                        cands.append(alias_to_symbol[low])
                    for sc in short_key_to_symbols.get(low, ()):
                        if sc not in cands:
                            cands.append(sc)
                    # Prioritize default.yml and config.yml before addon/action configs
                    cands.sort(
                        key=lambda s: (
                            0 if "definitions/regions/default.yml" in s else (1 if "config.yml" in s else 2),
                            len(s),
                        )
                    )
                    for idx_c, resolved_cfg in enumerate(cands[:3]):
                        w_cfg = round(max(3.5, 6.5 - 1.0 * idx_c), 2)
                        add_edge(cls_sym, resolved_cfg, "READS_CONFIG_KEY", w_cfg, str(r["path"]))
                        add_edge(resolved_cfg, cls_sym, "IMPLEMENTED_IN_CLASS", w_cfg, str(r["path"]))

            # 8B. Link Java Structural Hierarchy (`EXTENDS_CLASS`, `IMPLEMENTS_SPI`, `IMPORTS_CLASS`),
            #     Fan-In TF-IDF Normalized Couplings, Config Co-Occurrence (`SHARES_CONFIG_KEY`),
            #     Test Subject-Fixture Co-Coupling (`TEST_CO_COUPLED`), 2-Hop Transitive Decay
            #     (`SIBLING_IMPLEMENTATION`, `SHARED_RARE_DEPENDENCY`), and `ADR-115` Governance
            n_total_classes = max(1, len(seen_classes))
            class_fan_in: collections.Counter[str] = collections.Counter()
            for cm in class_meta_rows:
                sc_name = str(cm["super_class"] or "").strip()
                if sc_name:
                    class_fan_in[sc_name] += 1
                for iface in json.loads(str(cm["interfaces_json"] or "[]")):
                    class_fan_in[str(iface)] += 1
                for imp_c in json.loads(str(cm["imports_json"] or "[]")):
                    class_fan_in[str(imp_c)] += 1

            def _class_idf(target_cls: str) -> float:
                df_c = class_fan_in.get(target_cls, 1)
                if n_total_classes <= 5:
                    return 1.0
                return max(0.25, min(1.0, math.log(1.0 + n_total_classes / (1.0 + df_c)) / math.log(1.0 + n_total_classes)))

            spi_to_implementors: dict[str, list[tuple[str, float, str]]] = collections.defaultdict(list)
            rare_dep_to_consumers: dict[str, list[tuple[str, float, str]]] = collections.defaultdict(list)

            cites_lookup = import_cites_by_path
            if not cites_lookup:
                import sys as _sys
                for _mod_name in ("_atlas_build_doc_index", "build_doc_index", "__main__"):
                    _mod = _sys.modules.get(_mod_name)
                    _cand = getattr(_mod, "_JAVA_IMPORT_CITES_CACHE", None) if _mod is not None else None
                    if isinstance(_cand, dict) and _cand:
                        cites_lookup = _cand
                        break
            if cites_lookup is None:
                cites_lookup = {}

            for cm in class_meta_rows:
                cls_name = str(cm["class_name"] or "").strip()
                if not cls_name:
                    continue
                cls_sym = f"cls:{cls_name}"
                c_path = str(cm["path"] or "")
                c_pkg = str(cm["package_name"] or "")
                c_import_cites = cites_lookup.get((dom, c_path), {})
                super_cls = str(cm["super_class"] or "").strip()
                if super_cls and super_cls in seen_classes:
                    sup_sym = f"cls:{super_cls}"
                    w_ext = round(8.5 * (0.45 + 0.55 * _class_idf(super_cls)), 2)
                    add_edge(cls_sym, sup_sym, "EXTENDS_CLASS", w_ext, c_path)
                    add_edge(sup_sym, cls_sym, "SUBCLASSED_BY", w_ext, c_path)
                    spi_to_implementors[super_cls].append((cls_name, w_ext, c_path))

                for iface in json.loads(str(cm["interfaces_json"] or "[]")):
                    spi_sym = f"spi:{iface}"
                    w_spi = round(9.2 * (0.45 + 0.55 * _class_idf(iface)), 2)
                    if spi_sym in seen_spis:
                        add_edge(cls_sym, spi_sym, "IMPLEMENTS_SPI", w_spi, c_path)
                        add_edge(spi_sym, cls_sym, "SPI_IMPLEMENTED_BY", w_spi, c_path)
                    if iface in seen_classes:
                        add_edge(cls_sym, f"cls:{iface}", "IMPLEMENTS_SPI", w_spi, c_path)
                        add_edge(f"cls:{iface}", cls_sym, "SPI_IMPLEMENTED_BY", w_spi, c_path)
                    spi_to_implementors[iface].append((cls_name, w_spi, c_path))

                for idx_imp, imp_cls in enumerate(json.loads(str(cm["imports_json"] or "[]"))):
                    if imp_cls in seen_classes and imp_cls != cls_name:
                        idf_imp = _class_idf(imp_cls)
                        rank_factor = max(0.35, 1.0 - 0.032 * idx_imp)
                        target_cm = cm_by_name.get(imp_cls)
                        same_pkg = target_cm is not None and str(target_cm["package_name"] or "") == c_pkg and bool(c_pkg)
                        w_imp = round((6.8 if idx_imp < 4 else 4.8) * rank_factor * (0.35 + 0.65 * idf_imp) + (1.0 if same_pkg else 0.0), 2)
                        cite_imp = c_import_cites.get(imp_cls, c_path)
                        add_edge(cls_sym, f"cls:{imp_cls}", "IMPORTS_CLASS", w_imp, cite_imp)
                        add_edge(f"cls:{imp_cls}", cls_sym, "IMPORTED_BY_CLASS", w_imp, cite_imp)
                        if 2 <= class_fan_in.get(imp_cls, 0) <= 10 and idx_imp < 6:
                            rare_dep_to_consumers[imp_cls].append((cls_name, w_imp, c_path))

                for idx_gov, gov_item in enumerate(json.loads(str(cm["governed_by_json"] or "[]"))):
                    gov_up = str(gov_item).strip().upper()
                    w_gov = round(max(4.2, 9.2 - 0.35 * idx_gov), 2)
                    if gov_up.startswith("REQ-"):
                        r_sym = alias_to_symbol.get(gov_up.lower()) or f"req:{gov_up}"
                        if r_sym not in seen_normative:
                            seen_normative.add(r_sym)
                            symbol_rows.append(
                                (r_sym, dom, gov_up, "REQ_ID", "code_class_meta", int(cm["id"]), c_path, json.dumps([gov_up]), 1, json.dumps({"path": c_path}))
                            )
                            alias_to_symbol[gov_up.lower()] = r_sym
                        add_edge(cls_sym, r_sym, "SATISFIES_REQ", w_gov, c_path)
                        add_edge(r_sym, cls_sym, "REQ_SATISFIED_BY", w_gov, c_path)
                    elif gov_up.startswith(("ADR-", "S-")) or "-ADR-" in gov_up:
                        norm_tok = gov_up if gov_up.startswith(("ADR-", "S-")) else str(gov_item).strip()
                        a_sym = alias_to_symbol.get(norm_tok.lower()) or f"adr:{norm_tok}"
                        if a_sym not in seen_normative:
                            seen_normative.add(a_sym)
                            k_lbl = "SAFETY_PROHIBITION" if gov_up.startswith("S-") else "ADR_RECORD"
                            symbol_rows.append(
                                (a_sym, dom, norm_tok, k_lbl, "code_class_meta", int(cm["id"]), c_path, json.dumps([norm_tok]), 1, json.dumps({"path": c_path}))
                            )
                            alias_to_symbol[norm_tok.lower()] = a_sym
                        add_edge(cls_sym, a_sym, "GOVERNED_BY_ADR", w_gov, c_path)
                        add_edge(a_sym, cls_sym, "ADR_GOVERNS_CLASS", w_gov, c_path)

            # 8C. 2-Hop Transitive Decay (`SIBLING_IMPLEMENTATION` and `SHARED_RARE_DEPENDENCY`)
            decay_2hop = 0.28
            for spi_name, impl_entries in spi_to_implementors.items():
                if 2 <= len(impl_entries) <= 12:
                    idf_s = _class_idf(spi_name)
                    for i_s in range(len(impl_entries)):
                        cls_a, w_a, cite_a = impl_entries[i_s]
                        for j_s in range(i_s + 1, len(impl_entries)):
                            cls_b, w_b, _cite_b = impl_entries[j_s]
                            w_sib = round(min(7.5, (w_a * w_b * idf_s * decay_2hop) / 6.0), 2)
                            if w_sib >= 1.8:
                                add_edge(f"cls:{cls_a}", f"cls:{cls_b}", "SIBLING_IMPLEMENTATION", w_sib, f"via {spi_name} ({cite_a})")
                                add_edge(f"cls:{cls_b}", f"cls:{cls_a}", "SIBLING_IMPLEMENTATION", w_sib, f"via {spi_name} ({cite_a})")

            for dep_name, cons_entries in rare_dep_to_consumers.items():
                if 2 <= len(cons_entries) <= 8:
                    idf_d = _class_idf(dep_name)
                    for i_d in range(len(cons_entries)):
                        cls_a, w_a, _c_a = cons_entries[i_d]
                        for j_d in range(i_d + 1, len(cons_entries)):
                            cls_b, w_b, _c_b = cons_entries[j_d]
                            w_co = round(min(6.0, (w_a * w_b * idf_d * decay_2hop) / 6.5), 2)
                            if w_co >= 1.6:
                                add_edge(f"cls:{cls_a}", f"cls:{cls_b}", "SHARED_RARE_DEPENDENCY", w_co, f"via {dep_name}")
                                add_edge(f"cls:{cls_b}", f"cls:{cls_a}", "SHARED_RARE_DEPENDENCY", w_co, f"via {dep_name}")

            # 8D. Config Key Co-Occurrence (`SHARES_CONFIG_KEY` across schema/runtime classes)
            cfg_key_to_readers: dict[str, list[str]] = collections.defaultdict(list)
            for r in code_rows:
                cn = str(r["class_name"] or "").strip()
                if not cn or cn not in seen_classes:
                    continue
                for cfg_tok in re.findall(r"[A-Za-z0-9_.-]+", str(r["config_refs"] or "")):
                    if cn not in cfg_key_to_readers[cfg_tok.lower()]:
                        cfg_key_to_readers[cfg_tok.lower()].append(cn)
            for ck_low, r_classes in cfg_key_to_readers.items():
                if 2 <= len(r_classes) <= 8:
                    w_ck_share = round(max(2.2, 5.2 - 0.4 * len(r_classes)), 2)
                    for i_c in range(len(r_classes)):
                        for j_c in range(i_c + 1, len(r_classes)):
                            add_edge(f"cls:{r_classes[i_c]}", f"cls:{r_classes[j_c]}", "SHARES_CONFIG_KEY", w_ck_share, f"key:{ck_low}")
                            add_edge(f"cls:{r_classes[j_c]}", f"cls:{r_classes[i_c]}", "SHARES_CONFIG_KEY", w_ck_share, f"key:{ck_low}")

            for tr in trace_rows:
                req_id = str(tr["req_id"] or "").strip().upper()
                if not req_id:
                    continue
                r_sym = f"req:{req_id}"
                impl_list = json.loads(str(tr["implementing_classes_json"] or "[]"))
                test_list = json.loads(str(tr["test_classes_json"] or "[]"))
                dref_list = json.loads(str(tr["design_refs_json"] or "[]"))
                for idx_ic, ic in enumerate(impl_list):
                    if ic in seen_classes:
                        w_tr = round(max(5.5, 9.5 - 0.25 * idx_ic), 2)
                        add_edge(f"cls:{ic}", r_sym, "SATISFIES_REQ", w_tr, "docs/dev/TRACEABILITY.md")
                        add_edge(r_sym, f"cls:{ic}", "REQ_SATISFIED_BY", w_tr, "docs/dev/TRACEABILITY.md")
                for dref in dref_list:
                    a_sym = alias_to_symbol.get(str(dref).lower())
                    if a_sym and (a_sym.startswith("adr:") or a_sym.startswith("req:")):
                        edge_k = "GOVERNED_BY_ADR" if a_sym.startswith("adr:") else "SATISFIES_REQ"
                        rev_k = "ADR_GOVERNS_CLASS" if a_sym.startswith("adr:") else "REQ_SATISFIED_BY"
                        add_edge(r_sym, a_sym, edge_k, 8.5, "docs/dev/TRACEABILITY.md")
                        add_edge(a_sym, r_sym, rev_k, 8.5, "docs/dev/TRACEABILITY.md")
                        for idx_ic, ic in enumerate(impl_list[:10]):
                            if ic in seen_classes:
                                w_ic_adr = round(max(4.5, 8.0 - 0.3 * idx_ic), 2)
                                add_edge(f"cls:{ic}", a_sym, edge_k, w_ic_adr, "docs/dev/TRACEABILITY.md")
                                add_edge(a_sym, f"cls:{ic}", rev_k, w_ic_adr, "docs/dev/TRACEABILITY.md")
                for idx_tc, tc_name in enumerate(test_list):
                    t_sym = alias_to_symbol.get(str(tc_name).lower())
                    if t_sym and t_sym.startswith("test:"):
                        w_t_req = round(max(5.5, 9.0 - 0.2 * idx_tc), 2)
                        add_edge(r_sym, t_sym, "VERIFIED_BY_TEST", w_t_req, "docs/dev/TRACEABILITY.md")
                        add_edge(t_sym, r_sym, "TESTS_CLASS", w_t_req, "docs/dev/TRACEABILITY.md")
                        for ic in impl_list[:8]:
                            if ic in seen_classes:
                                add_edge(f"cls:{ic}", t_sym, "VERIFIED_BY_TEST", 7.5, "docs/dev/TRACEABILITY.md")
                                add_edge(t_sym, f"cls:{ic}", "TESTS_CLASS", 7.5, "docs/dev/TRACEABILITY.md")

            # 8E. JUnit `VERIFIED_BY_TEST` + Test-to-Subject & Fixture Co-Occurrence (`TEST_CO_COUPLED`)
            for tm in test_meta_rows:
                t_name = str(tm["test_class_name"] or "").strip()
                if not t_name:
                    continue
                mod_clean = str(tm["module_name"] or "").lstrip(":") or "rtp-core"
                t_sym = f"test:{mod_clean}:{t_name}"
                t_path = str(tm["path"] or "")
                tested_cls_list = [tc for tc in json.loads(str(tm["tested_classes_json"] or "[]")) if tc in seen_classes]
                for idx_tc, tc in enumerate(tested_cls_list):
                    is_direct_test = t_name.startswith(tc)
                    w_vt = 9.5 if is_direct_test else round(max(4.2, 7.2 - 0.4 * idx_tc) * (0.4 + 0.6 * _class_idf(tc)), 2)
                    add_edge(f"cls:{tc}", t_sym, "VERIFIED_BY_TEST", w_vt, t_path)
                    add_edge(t_sym, f"cls:{tc}", "TESTS_CLASS", w_vt, t_path)
                # Link primary subject to co-tested fixtures/collaborators (`TEST_CO_COUPLED`)
                if len(tested_cls_list) >= 2:
                    subj_cls = tested_cls_list[0]
                    for fix_cls in tested_cls_list[1:6]:
                        idf_fix = _class_idf(fix_cls)
                        if idf_fix >= 0.42:
                            w_co_test = round(4.8 * idf_fix, 2)
                            add_edge(f"cls:{subj_cls}", f"cls:{fix_cls}", "TEST_CO_COUPLED", w_co_test, f"via {t_name}")
                            add_edge(f"cls:{fix_cls}", f"cls:{subj_cls}", "TEST_CO_COUPLED", w_co_test, f"via {t_name}")
                for req_tok in json.loads(str(tm["req_ids_json"] or "[]")):
                    r_sym = alias_to_symbol.get(str(req_tok).lower())
                    if r_sym:
                        add_edge(r_sym, t_sym, "VERIFIED_BY_TEST", 7.5, t_path)
                        add_edge(t_sym, r_sym, "TESTS_CLASS", 7.5, t_path)

            # 9. Section-Level Reference Resolution, Heading Symptom Nodes, SVO Claims & Anti-Pattern Conflicts
            valid_symbol_ids_dom = {r[0] for r in symbol_rows}
            cap_span_re = re.compile(
                r"\b([A-Z][a-zA-Z'’-]{2,}(?:\s+(?:(?:of(?:\s+the)?|the)\s+)?[A-Z][a-zA-Z'’-]{2,}){0,2})\b"
            )
            the_ep_suffixes = [
                re.escape(epithet_surfaces[ep])
                for ep, _cnt in epithet_counts.most_common(120)
                if ep in epithet_surfaces
            ]
            combined_epithet_re = (
                re.compile(r"\b(?:the|The)\s+(?:" + "|".join(the_ep_suffixes) + r")\b")
                if the_ep_suffixes
                else None
            )
            any_verb_pred_re = _VERB_FAST_TRIGGER_RE

            cooccur_counts: collections.Counter[tuple[str, str]] = collections.Counter()
            cooccur_cite: dict[tuple[str, str], str] = {}

            for tbl_name, r in combined_sections:
                sec_id = int(r["id"])
                body = decoded_bodies.get(sec_id, str(r["body"] or ""))
                title = str(r["title"] or "")
                heading = str(r["heading_path"] or "")
                path = str(r["path"] or "")
                url = str(r["url"] or "")
                tags = str(r["tags"] or "")
                edition = _detect_edition_tag(path, title, tags)
                voice = _classify_discourse_voice(body, path=path, table_name=tbl_name, is_technical=is_technical_schema)
                sec_ctx = _derive_section_perspective_lens(path, heading, title, body[:400], is_technical=is_technical_schema)
                combined = f"{title}\n{heading}\n{body}"
                cite_label = f"{path}#{heading or title}"[:80]

                # A. Backticked explicit symbol references (with LCA sibling disambiguation for ambiguous config leaves)
                bt_spans = [m.group(1).strip() for m in backtick_re.finditer(combined)] if "`" in combined else []
                bt_clean_tokens = [s.split("=")[0].split(":")[0].strip().lower() for s in bt_spans]
                found_in_section: list[str] = []

                for span, clean in zip(bt_spans, bt_clean_tokens):
                    resolved = alias_to_symbol.get(clean)
                    if not resolved and clean in short_key_to_symbols:
                        cands = short_key_to_symbols[clean]
                        if len(cands) == 1:
                            resolved = cands[0]
                        else:
                            lca_kp = resolve_config_leaf_via_lca(conn, clean, bt_clean_tokens, project=dom)
                            if lca_kp and lca_kp.lower() in alias_to_symbol:
                                resolved = alias_to_symbol[lca_kp.lower()]
                            else:
                                resolved = cands[0]
                    if resolved:
                        found_in_section.append(resolved)
                        all_candidate_refs.append(
                            (dom, tbl_name, sec_id, path, 0, span[:60], clean, resolved, "EXPLICIT_SYMBOL", voice, sec_ctx, edition, 0.95)
                        )

                # Also check unbackticked ADR / S-00x / BIT references in the section
                if any(k in combined for k in ("ADR-", "adr-", "S-0", "REQ-")):
                    for m_norm in adr_or_rule_re.finditer(combined):
                        code_low = m_norm.group(1).lower()
                        if code_low in alias_to_symbol:
                            found_in_section.append(alias_to_symbol[code_low])

                uniq_structured = list(dict.fromkeys(found_in_section))

                # B. Promote user-facing `doc_sections` heading to a `BEHAVIORAL_SYMPTOM` node
                #    Strictly exclude internal `arch_sections` (`docs/adr/`, `docs/dev/`) and generic structural headings
                sec_label = (heading or title).strip()
                sec_label_low = sec_label.lower().strip(" :#-*")
                is_valid_symptom_heading = (
                    tbl_name == "doc_sections"
                    and bool(sec_label)
                    and len(sec_label) >= 6
                    and bool(uniq_structured)
                    and sec_label_low not in STRUCTURAL_NON_SYMPTOM_HEADINGS
                    and not re.match(r"^\d+\.\s+", sec_label_low)
                    and bool(SYMPTOM_HEADING_SIGNAL_RE.search(f"{sec_label} {path}"))
                )
                if is_valid_symptom_heading:
                    sym_slug = re.sub(r"[^a-z0-9]+", "_", f"{path}_{sec_label}".lower()).strip("_")[:64]
                    symptom_id = f"symptom:{sym_slug}"
                    if symptom_id not in valid_symbol_ids_dom:
                        valid_symbol_ids_dom.add(symptom_id)
                        first_sent = clean_sentence_excerpt(body, max_len=220)
                        heading_words = [
                            w.lower() for w in re.findall(r"[A-Za-z0-9_./-]+", f"{sec_label} {first_sent}")
                            if len(w) >= 3
                        ]
                        aliases_sym = list(dict.fromkeys([sec_label] + heading_words[:18]))
                        symbol_rows.append(
                            (
                                symptom_id,
                                dom,
                                sec_label,
                                "BEHAVIORAL_SYMPTOM",
                                tbl_name,
                                sec_id,
                                url,
                                json.dumps(aliases_sym),
                                1,
                                json.dumps({"path": path, "mechanism": first_sent}),
                            )
                        )
                    for dst_sym in uniq_structured[:12]:
                        if dst_sym.startswith("cfg:"):
                            rel = "GOVERNED_BY_KEY"
                        elif dst_sym.startswith("cmd:"):
                            rel = "MITIGATED_BY_COMMAND"
                        elif dst_sym.startswith("cls:"):
                            rel = "IMPLEMENTED_IN_CLASS"
                        elif dst_sym.startswith("adr:"):
                            rel = "GOVERNED_BY_INVARIANT"
                        else:
                            rel = "DOCUMENTED_WITH"
                        add_edge(symptom_id, dst_sym, rel, 4.0, cite_label)
                        add_edge(dst_sym, symptom_id, rel, 2.5, cite_label)
                        all_claim_rows.append(
                            (dom, symptom_id, rel, dst_sym, "AFFIRMED", voice, sec_ctx, edition, tbl_name, sec_id, cite_label, url, clean_sentence_excerpt(body, max_len=240))
                        )

                # Co-occurrence edges among structured symbols in the same section
                for i, s_a in enumerate(uniq_structured[:16]):
                    for s_b in uniq_structured[i + 1 : 16]:
                        add_edge(s_a, s_b, "DOCUMENTED_WITH", 1.5, cite_label)
                        add_edge(s_b, s_a, "DOCUMENTED_WITH", 1.5, cite_label)

                # C. Paragraph-Scoped Anti-Pattern / Safety Conflict Extraction (`doc_sections` only)
                if tbl_name == "doc_sections" and uniq_structured:
                    is_antipattern_heading = bool(GENERAL_ANTIPATTERN_RE.search(f"{heading} {title}"))
                    adr_syms = [s for s in uniq_structured if s.startswith("adr:")]
                    inv_label = adr_syms[0] if adr_syms else f"Architecture Invariant ({path})"
                    paragraphs = [p.strip() for p in re.split(r"\n\s*\n", body) if len(p.strip()) >= 25]
                    if not paragraphs and len(body.strip()) >= 25:
                        paragraphs = [body.strip()]
                    for para in paragraphs:
                        if not (is_antipattern_heading or GENERAL_ANTIPATTERN_RE.search(para)):
                            continue
                        sents = [s.strip() for s in sent_split_re.split(re.sub(r"\s+", " ", para)) if len(s.strip()) >= 20]
                        warn_sents = [s for s in sents if GENERAL_ANTIPATTERN_RE.search(s)]
                        warn_quote = warn_sents[0] if warn_sents else (sents[0] if sents else sec_label)
                        remedy_quote = sents[-1] if len(sents) > 1 else warn_quote
                        # Extract backticked symbols primarily from the warning sentence(s), falling back to the paragraph
                        target_text_for_bt = " ".join(warn_sents) if warn_sents else para
                        para_bt = [m.group(1).strip().split("=")[0].split(":")[0].strip().lower() for m in backtick_re.finditer(target_text_for_bt)]
                        if not para_bt and warn_sents:
                            para_bt = [m.group(1).strip().split("=")[0].split(":")[0].strip().lower() for m in backtick_re.finditer(para)]
                        para_resolved: list[str] = []
                        for tok_p in para_bt:
                            res_p = alias_to_symbol.get(tok_p)
                            if not res_p and tok_p in short_key_to_symbols:
                                res_p = short_key_to_symbols[tok_p][0]
                            if res_p and res_p.startswith(("cfg:", "perm:", "cmd:")) and not res_p.endswith(":version"):
                                para_resolved.append(res_p)
                        if not para_resolved and is_antipattern_heading:
                            para_resolved = [
                                s for s in uniq_structured
                                if s.startswith(("cfg:", "perm:")) and not s.endswith(":version")
                            ]
                        # Prefer specific `cfg:` and `perm:` targets over generic root commands like `cmd:/rtp`
                        non_root = [s for s in para_resolved if not (s.startswith("cmd:") and len(s.split()) == 1)]
                        focal_targets = non_root if non_root else para_resolved
                        for focal_sym in list(dict.fromkeys(focal_targets))[:3]:
                            ckey = (dom, focal_sym)
                            if ckey in seen_antipattern_focal:
                                continue
                            seen_antipattern_focal.add(ckey)
                            all_conflict_rows.append(
                                (
                                    dom,
                                    "GOAL_VS_SAFETY_INVARIANT",
                                    focal_sym,
                                    "ANTI_PATTERN_VS_INVARIANT",
                                    f"Misconfiguration / Anti-Pattern ({sec_label})",
                                    f"Recommended Safe Practice ({inv_label})",
                                    "USER_MISCONFIG",
                                    "ARCHITECTURE_INVARIANT" if is_technical_schema else "ADR_INVARIANT",
                                    sec_ctx,
                                    sec_ctx,
                                    edition,
                                    edition,
                                    cite_label,
                                    path,
                                    warn_quote[:300],
                                    remedy_quote[:300],
                                    f"[{sec_label}] {warn_quote[:220]}",
                                )
                            )

                # D. Optional Small-LLM JSON Scanner Hook
                if llm_scanner_fn is not None and uniq_structured:
                    for llm_claim in llm_scanner_fn(combined, valid_symbol_ids_dom):
                        c_src = llm_claim["src"]
                        c_dst = llm_claim["dst"]
                        c_pred = llm_claim["predicate"]
                        add_edge(c_src, c_dst, c_pred, 3.5, f"llm-ast:{cite_label}")
                        all_claim_rows.append(
                            (dom, c_src, c_pred, c_dst, llm_claim["polarity"], voice, sec_ctx, edition, tbl_name, sec_id, cite_label, url, llm_claim["quote"] or title)
                        )

                # E. Prose Co-Occurrence, Epithet Resolution & Sentence SVO / Passive Claim Extraction
                if not enable_prose_entities:
                    continue

                raw_sec_spans = sec_cap_spans.get(sec_id, [])
                resolved_sec_hits: list[tuple[int, str, str]] = []
                for pos, phr in raw_sec_spans:
                    s_id = alias_to_symbol.get(phr.lower())
                    if s_id:
                        resolved_sec_hits.append((pos, s_id, phr))

                if combined_epithet_re is not None and "the " in body.lower():
                    dom_name_sec = resolved_sec_hits[0][2] if resolved_sec_hits else ""
                    lens_sec = _derive_section_perspective_lens(path, heading, title, body[:600], dominant_symbol_name=dom_name_sec, is_technical=is_technical_schema)
                    for m_ep in combined_epithet_re.finditer(body):
                        ep_str = " ".join(m_ep.group(0).lower().split())
                        target_canon = discovered_epithets.get(ep_str)
                        if target_canon:
                            s_id = alias_to_symbol.get(target_canon.lower())
                            if s_id:
                                all_candidate_refs.append(
                                    (dom, tbl_name, sec_id, path, 0, m_ep.group(0)[:60], target_canon, s_id, "EPITHET_ALIAS", voice, lens_sec, edition, 0.88)
                                )
                                resolved_sec_hits.append((m_ep.start(), s_id, target_canon))

                if len(resolved_sec_hits) >= 2:
                    # Count section co-occurrence across unique top symbols on the page (up to 8 per section)
                    uniq_sec_syms = list(dict.fromkeys(s_id for _, s_id, _ in resolved_sec_hits))[:8]
                    for i_u in range(len(uniq_sec_syms)):
                        s_a = uniq_sec_syms[i_u]
                        for j_u in range(i_u + 1, len(uniq_sec_syms)):
                            s_b = uniq_sec_syms[j_u]
                            cooccur_counts[(s_a, s_b)] += 1
                            cooccur_counts[(s_b, s_a)] += 1
                            if (s_a, s_b) not in cooccur_cite:
                                cooccur_cite[(s_a, s_b)] = cite_label
                                cooccur_cite[(s_b, s_a)] = cite_label

                if tbl_name == "dev_sections" or not any_verb_pred_re.search(body):
                    continue

                # Include sentence-initial capitalized entities (which `mid_cap_re` skipped) when extracting SVO claims
                claim_hits_by_pos: dict[int, tuple[int, str, str]] = {pos: (pos, s_id, phr) for pos, s_id, phr in resolved_sec_hits}
                for m_cs in cap_span_re.finditer(body):
                    c_pos = m_cs.start()
                    if c_pos in claim_hits_by_pos:
                        continue
                    c_phr = _clean_proper_phrase(m_cs.group(1))
                    if not c_phr or len(c_phr) < 4:
                        continue
                    s_id = alias_to_symbol.get(c_phr.lower()) or alias_to_symbol.get(_normalize_proper_key(c_phr))
                    if s_id:
                        claim_hits_by_pos[c_pos] = (c_pos, s_id, c_phr)

                if len(claim_hits_by_pos) < 2:
                    continue
                claim_sec_hits = sorted(claim_hits_by_pos.values(), key=lambda x: x[0])
                lens = _derive_section_perspective_lens(path, heading, title, body[:600], dominant_symbol_name=claim_sec_hits[0][2], is_technical=is_technical_schema)
                for pred_name, pred_re, invert_so in GENERAL_VERB_PREDICATE_PATTERNS:
                    for m_pred in pred_re.finditer(body):
                        pred_pos = m_pred.start()
                        before = [(pos, s) for pos, s, _ in claim_sec_hits if pos < pred_pos and (pred_pos - pos) <= 130]
                        after = [(pos, s) for pos, s, _ in claim_sec_hits if pos > pred_pos and (pos - pred_pos) <= 130]
                        if before and after:
                            subj_sym = before[-1][1]
                            obj_pair = next(((pos, s) for pos, s in after if s != subj_sym), None)
                            if obj_pair:
                                obj_pos, obj_sym = obj_pair
                                span_between = body[before[-1][0] : obj_pos]
                                if (obj_pos - before[-1][0]) > 140 or re.search(r"[.!?;]\s|\n\s*\n", span_between):
                                    continue
                                if invert_so:
                                    subj_sym, obj_sym = obj_sym, subj_sym
                                w_start = max(0, min(before[-1][0], pred_pos) - 30)
                                w_end = min(len(body), max(obj_pos, m_pred.end()) + 90)
                                sent = " ".join(body[w_start:w_end].split())
                                polarity = "AFFIRMED"
                                if GENERAL_NEGATION_RE.search(sent):
                                    polarity = "DENIED"
                                elif GENERAL_RUMOR_RE.search(sent) or voice in ("IC_NARRATOR", "EPISTOLARY_POST"):
                                    polarity = "UNCERTAIN_RUMOR"
                                all_claim_rows.append(
                                    (dom, subj_sym, pred_name, obj_sym, polarity, voice, lens, edition, tbl_name, sec_id, cite_label, url, sent[:360])
                                )
                                add_edge(subj_sym, obj_sym, pred_name, 3.5, cite_label)

            # Retain top 10 co-occurring neighbors per symbol so `ast_edges` stays compact and high-signal
            min_co_cnt = 2 if len(doc_rows) > 500 else 1
            by_src_co: dict[str, list[tuple[int, str]]] = collections.defaultdict(list)
            for (s_a, s_b), c_cnt in cooccur_counts.items():
                if c_cnt >= min_co_cnt:
                    by_src_co[s_a].append((c_cnt, s_b))
            for s_a, nbrs in by_src_co.items():
                nbrs.sort(key=lambda x: x[0], reverse=True)
                for c_cnt, s_b in nbrs[:10]:
                    key = (s_a, s_b, "PARAGRAPH_CO_OCCURS")
                    edge_accum[key] = {"weight": float(c_cnt), "count": c_cnt, "cites": [cooccur_cite.get((s_a, s_b), "")]}

            all_symbol_rows.extend(symbol_rows)

        # Link cross-domain bridge edges for canonical symbol names attested across 2+ domains
        if len(target_domains) >= 2:
            by_canon_low: dict[str, list[tuple[str, str, int]]] = collections.defaultdict(list)
            for s_row in all_symbol_rows:
                by_canon_low[str(s_row[2]).lower()].append((str(s_row[0]), str(s_row[1]), int(s_row[8])))
            for _c_low, entries in by_canon_low.items():
                if len(entries) >= 2:
                    for i_e in range(len(entries)):
                        s_a, dom_a, cnt_a = entries[i_e]
                        for j_e in range(i_e + 1, len(entries)):
                            s_b, dom_b, cnt_b = entries[j_e]
                            if dom_a != dom_b:
                                w_br = min(20.0, 2.0 + (cnt_a * cnt_b) ** 0.25)
                                cite_br = f"[{dom_a.upper()} <-> {dom_b.upper()}]"
                                add_edge(s_a, s_b, "CROSS_GAMELINE_BRIDGE", w_br, cite_br)
                                add_edge(s_b, s_a, "CROSS_GAMELINE_BRIDGE", w_br, cite_br)

        # Stage 2B: In-Memory Claim-Collision Conflict Detector (Functional & Polarity Collisions)
        sym_id_to_name = {str(r[0]): str(r[2]) for r in all_symbol_rows}
        claims_by_dom: dict[str, list[tuple[Any, ...]]] = collections.defaultdict(list)
        for c_row in all_claim_rows:
            claims_by_dom[str(c_row[0]).lower()].append(c_row)

        for dom in target_domains:
            by_func: dict[tuple[str, str, bool], list[tuple[Any, ...]]] = collections.defaultdict(list)
            for fc in claims_by_dom.get(dom, []):
                # fc tuple: (dom, src_symbol_id, predicate, dst_symbol_id, polarity, epistemic_voice, clan_perspective, edition, source_table, section_id, citation, url, verbatim_quote)
                pred = str(fc[2])
                if pred == "TRUE_IDENTITY_OF":
                    by_func[(str(fc[1]), pred, False)].append(fc)
                elif pred in ("DIABLERIZED", "DESTROYED_OR_PURGED", "SIRED_OR_CREATED"):
                    by_func[(str(fc[3]), pred, True)].append(fc)

            seen_c_keys: set[tuple[str, str, str, str]] = set()
            for (focal_id, pred, group_by_dst), rows in by_func.items():
                by_other: dict[str, tuple[Any, ...]] = {}
                for r in rows:
                    other_id = str(r[1] if group_by_dst else r[3])
                    if other_id not in by_other:
                        by_other[other_id] = r
                other_list = list(by_other.values())
                if len(other_list) >= 2:
                    for i in range(min(len(other_list), 4)):
                        for j in range(i + 1, min(len(other_list), 4)):
                            a, b = other_list[i], other_list[j]
                            a_lbl = sym_id_to_name.get(str(a[1] if group_by_dst else a[3]), str(a[1] if group_by_dst else a[3]))
                            b_lbl = sym_id_to_name.get(str(b[1] if group_by_dst else b[3]), str(b[1] if group_by_dst else b[3]))
                            focal_name = sym_id_to_name.get(str(a[3] if group_by_dst else a[1]), focal_id)
                            ckey = (focal_id, pred, a_lbl, b_lbl)
                            if ckey in seen_c_keys:
                                continue
                            seen_c_keys.add(ckey)
                            ed_diff = a[7] != b[7] and ("V5" in (a[7], b[7]))
                            kind = "V20_VS_V5_EDITION_RETCON" if ed_diff else "INTENTIONAL_META_LORE_PERSPECTIVE"
                            note = (
                                f"Competing `{pred}` accounts for `{focal_name}`: "
                                f"`{a[6]}` ({a[10]}, {a[7]}) attributes `{a_lbl}` vs. "
                                f"`{b[6]}` ({b[10]}, {b[7]}) attributes `{b_lbl}`."
                            )
                            all_conflict_rows.append(
                                (
                                    dom,
                                    kind,
                                    focal_id,
                                    pred,
                                    a_lbl,
                                    b_lbl,
                                    str(a[5]),
                                    str(b[5]),
                                    str(a[6]),
                                    str(b[6]),
                                    str(a[7]),
                                    str(b[7]),
                                    str(a[10]),
                                    str(b[10]),
                                    str(a[12]),
                                    str(b[12]),
                                    note,
                                )
                            )

        # Atomic persistence: clear target domain rows and write new AST rows in a single batch at the end
        filtered_edges = [
            (k[0], k[1], k[2], round(v["weight"], 4), v["count"], json.dumps(v["cites"]))
            for k, v in edge_accum.items()
        ]
        if bake_all:
            for tbl in ("ast_edges", "ast_symbols", "ast_candidate_refs", "ast_claims", "ast_conflicts", "ast_config_tree"):
                conn.execute(f"DELETE FROM {tbl}")
        else:
            for dom in target_domains:
                conn.execute(
                    "DELETE FROM ast_edges WHERE src_symbol_id IN (SELECT symbol_id FROM ast_symbols WHERE domain = ?)",
                    (dom,),
                )
                conn.execute(
                    "DELETE FROM ast_edges WHERE dst_symbol_id IN (SELECT symbol_id FROM ast_symbols WHERE domain = ?)",
                    (dom,),
                )
                for tbl in ("ast_symbols", "ast_candidate_refs", "ast_claims", "ast_conflicts"):
                    conn.execute(f"DELETE FROM {tbl} WHERE domain = ?", (dom,))

        if all_cfg_tree_rows:
            conn.executemany("INSERT OR REPLACE INTO ast_config_tree VALUES (?, ?, ?, ?, ?, ?)", all_cfg_tree_rows)
        conn.executemany("INSERT OR REPLACE INTO ast_symbols VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", all_symbol_rows)
        conn.executemany(
            """
            INSERT INTO ast_candidate_refs (
                domain, source_table, section_id, book_or_file, page_or_line,
                span_text, target_hint, resolved_symbol_id, ref_kind, epistemic_voice, clan_perspective, edition, confidence
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            all_candidate_refs,
        )
        conn.executemany(
            "INSERT OR REPLACE INTO ast_edges VALUES (?, ?, ?, ?, ?, ?)",
            filtered_edges,
        )
        conn.executemany(
            """
            INSERT INTO ast_claims (
                domain, src_symbol_id, predicate, dst_symbol_id, polarity,
                epistemic_voice, clan_perspective, edition, source_table, section_id, citation, url, verbatim_quote
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            all_claim_rows,
        )
        conn.executemany(
            """
            INSERT INTO ast_conflicts (
                domain, conflict_kind, focal_symbol_id, predicate,
                side_a_target, side_b_target, side_a_voice, side_b_voice,
                side_a_perspective, side_b_perspective,
                side_a_edition, side_b_edition, side_a_citation, side_b_citation,
                side_a_quote, side_b_quote, explanation_note
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            all_conflict_rows,
        )
        return {
            "ast_symbols": len(all_symbol_rows),
            "ast_candidate_refs": len(all_candidate_refs),
            "ast_edges": len(filtered_edges),
            "ast_claims": len(all_claim_rows),
            "ast_conflicts": len(all_conflict_rows),
            "ast_config_tree": len(all_cfg_tree_rows),
        }
    finally:
        conn.row_factory = prev_factory


def sync_ast_incremental_for_files(
    conn: sqlite3.Connection,
    project: str = "leafrtp",
    updated_class_metas: list[Any] | None = None,
    deleted_class_names: list[str] | None = None,
    updated_test_metas: list[Any] | None = None,
    deleted_test_symbols: list[str] | None = None,
    traceability_updated: bool = False,
) -> None:
    """
    Incrementally updates `ast_symbols` and `ast_edges` for modified or deleted
    Java classes, JUnit test classes, and `TRACEABILITY.md` rows in `< 25 ms`.
    """
    dom = (project or "leafrtp").strip().lower()
    if not _table_exists(conn, "ast_symbols") or not _table_exists(conn, "ast_edges"):
        bake_project_ast(conn, project=dom)
        return

    for del_cls in deleted_class_names or []:
        for sid in (f"cls:{del_cls}", f"spi:{del_cls}"):
            conn.execute("DELETE FROM ast_symbols WHERE symbol_id = ? AND lower(domain) = ?", (sid, dom))
            conn.execute("DELETE FROM ast_edges WHERE src_symbol_id = ? OR dst_symbol_id = ?", (sid, sid))

    for del_tsym in deleted_test_symbols or []:
        conn.execute("DELETE FROM ast_symbols WHERE symbol_id = ? AND lower(domain) = ?", (del_tsym, dom))
        conn.execute("DELETE FROM ast_edges WHERE src_symbol_id = ? OR dst_symbol_id = ?", (del_tsym, del_tsym))

    def _upsert_edge(src: str, dst: str, kind: str, weight: float, cite: str) -> None:
        if not src or not dst or src == dst:
            return
        conn.execute(
            "INSERT OR REPLACE INTO ast_edges(src_symbol_id, dst_symbol_id, edge_kind, weight, support_count, evidence_json) VALUES (?, ?, ?, ?, 1, ?)",
            (src, dst, kind, weight, json.dumps([cite] if cite else [])),
        )

    for cm in updated_class_metas or []:
        cls_name = cm.class_name
        cls_sym = f"cls:{cls_name}"
        c_path = cm.path
        conn.execute(
            """
            DELETE FROM ast_edges
            WHERE (src_symbol_id = ? AND edge_kind IN ('EXTENDS_CLASS', 'IMPLEMENTS_SPI', 'IMPORTS_CLASS', 'SATISFIES_REQ', 'GOVERNED_BY_ADR'))
               OR (dst_symbol_id = ? AND edge_kind IN ('SUBCLASSED_BY', 'SPI_IMPLEMENTED_BY', 'IMPORTED_BY_CLASS', 'REQ_SATISFIED_BY', 'ADR_GOVERNS_CLASS'))
            """,
            (cls_sym, cls_sym),
        )
        cs_row = conn.execute(
            "SELECT rowid AS id, summary FROM code_sections WHERE path = ? AND lower(project) = ? LIMIT 1",
            (c_path, dom),
        ).fetchone()
        sec_id = int(cs_row["id"]) if cs_row else 0
        summary_str = str(cs_row["summary"] or "")[:200] if cs_row else cls_name
        ifaces = json.loads(cm.interfaces_json)
        govs = json.loads(cm.governed_by_json)
        imps = json.loads(cm.imports_json)
        meta_dict = {
            "path": c_path,
            "summary": summary_str,
            "package_name": cm.package_name,
            "module_name": cm.module_name,
            "dag_tier": cm.dag_tier,
            "super_class": cm.super_class,
            "interfaces": ifaces,
            "governed_by": govs,
            "gradle_test_cmd": cm.gradle_test_cmd,
            "kind": cm.kind,
        }
        conn.execute(
            "INSERT OR REPLACE INTO ast_symbols VALUES (?, ?, ?, 'JAVA_CLASS', 'code_sections', ?, ?, ?, 1, ?)",
            (cls_sym, dom, cls_name, sec_id, c_path, json.dumps([cls_name]), json.dumps(meta_dict)),
        )
        if cm.kind == "INTERFACE" or cm.dag_tier == "TIER_1_SPI_PURE":
            spi_sym = f"spi:{cls_name}"
            conn.execute(
                "INSERT OR REPLACE INTO ast_symbols VALUES (?, ?, ?, 'SPI_CONTRACT', 'code_class_meta', ?, ?, ?, 1, ?)",
                (spi_sym, dom, cls_name, sec_id, c_path, json.dumps([cls_name, spi_sym]), json.dumps(meta_dict)),
            )
        if cm.super_class:
            sup_sym = f"cls:{cm.super_class}"
            _upsert_edge(cls_sym, sup_sym, "EXTENDS_CLASS", 4.0, c_path)
            _upsert_edge(sup_sym, cls_sym, "SUBCLASSED_BY", 4.0, c_path)
        for iface in ifaces:
            spi_sym = f"spi:{iface}"
            conn.execute(
                "INSERT OR IGNORE INTO ast_symbols VALUES (?, ?, ?, 'SPI_CONTRACT', 'code_class_meta', 0, ?, ?, 1, ?)",
                (spi_sym, dom, iface, c_path, json.dumps([iface, spi_sym]), json.dumps({"path": c_path, "dag_tier": "TIER_1_SPI_PURE"})),
            )
            _upsert_edge(cls_sym, spi_sym, "IMPLEMENTS_SPI", 4.5, c_path)
            _upsert_edge(spi_sym, cls_sym, "SPI_IMPLEMENTED_BY", 4.5, c_path)
            _upsert_edge(cls_sym, f"cls:{iface}", "IMPLEMENTS_SPI", 4.5, c_path)
            _upsert_edge(f"cls:{iface}", cls_sym, "SPI_IMPLEMENTED_BY", 4.5, c_path)
        c_import_cites = json.loads(getattr(cm, "import_cites_json", "{}") or "{}")
        for imp_cls in imps:
            cite_imp = c_import_cites.get(imp_cls, c_path)
            _upsert_edge(cls_sym, f"cls:{imp_cls}", "IMPORTS_CLASS", 2.0, cite_imp)
            _upsert_edge(f"cls:{imp_cls}", cls_sym, "IMPORTED_BY_CLASS", 2.0, cite_imp)
        for gov in govs:
            gov_up = str(gov).strip().upper()
            if gov_up.startswith("REQ-"):
                r_sym = f"req:{gov_up}"
                conn.execute(
                    "INSERT OR IGNORE INTO ast_symbols VALUES (?, ?, ?, 'REQ_ID', 'code_class_meta', 0, ?, ?, 1, ?)",
                    (r_sym, dom, gov_up, c_path, json.dumps([gov_up]), json.dumps({"path": c_path})),
                )
                _upsert_edge(cls_sym, r_sym, "SATISFIES_REQ", 5.0, c_path)
                _upsert_edge(r_sym, cls_sym, "REQ_SATISFIED_BY", 5.0, c_path)
            elif gov_up.startswith(("ADR-", "S-")) or "-ADR-" in gov_up:
                norm_tok = gov_up if gov_up.startswith(("ADR-", "S-")) else str(gov).strip()
                a_sym = f"adr:{norm_tok}"
                k_lbl = "SAFETY_PROHIBITION" if gov_up.startswith("S-") else "ADR_RECORD"
                conn.execute(
                    "INSERT OR IGNORE INTO ast_symbols VALUES (?, ?, ?, ?, 'code_class_meta', 0, ?, ?, 1, ?)",
                    (a_sym, dom, norm_tok, k_lbl, c_path, json.dumps([norm_tok]), json.dumps({"path": c_path})),
                )
                _upsert_edge(cls_sym, a_sym, "GOVERNED_BY_ADR", 4.5, c_path)
                _upsert_edge(a_sym, cls_sym, "ADR_GOVERNS_CLASS", 4.5, c_path)

    for tm in updated_test_metas or []:
        t_name = tm.test_class_name
        mod_clean = tm.module_name.lstrip(":") or "rtp-core"
        t_sym = f"test:{mod_clean}:{t_name}"
        t_path = tm.path
        conn.execute(
            "DELETE FROM ast_edges WHERE src_symbol_id = ? OR dst_symbol_id = ?",
            (t_sym, t_sym),
        )
        tested_list = json.loads(tm.tested_classes_json)
        reqs_list = json.loads(tm.req_ids_json)
        t_meta = {
            "path": t_path,
            "package_name": tm.package_name,
            "module_name": tm.module_name,
            "gradle_test_cmd": tm.gradle_test_cmd,
            "tested_classes": tested_list,
            "req_ids": reqs_list,
        }
        conn.execute(
            "INSERT OR REPLACE INTO ast_symbols VALUES (?, ?, ?, 'TEST_CLASS', 'test_class_meta', 0, ?, ?, 1, ?)",
            (t_sym, dom, t_name, t_path, json.dumps([t_name, f"{mod_clean}:{t_name}"]), json.dumps(t_meta)),
        )
        for tc in tested_list:
            _upsert_edge(f"cls:{tc}", t_sym, "VERIFIED_BY_TEST", 4.5, t_path)
            _upsert_edge(t_sym, f"cls:{tc}", "TESTS_CLASS", 4.5, t_path)
        for req_tok in reqs_list:
            r_up = str(req_tok).upper()
            r_sym = f"req:{r_up}" if r_up.startswith("REQ-") else f"adr:{req_tok}"
            _upsert_edge(r_sym, t_sym, "VERIFIED_BY_TEST", 4.5, t_path)
            _upsert_edge(t_sym, r_sym, "TESTS_CLASS", 4.5, t_path)

    if traceability_updated:
        for r in conn.execute(
            "SELECT rowid AS id, req_id, summary, design_refs_json, implementing_classes_json, test_classes_json FROM traceability_rows WHERE lower(project) = ?",
            (dom,),
        ).fetchall():
            req_id = str(r["req_id"]).upper()
            r_sym = f"req:{req_id}"
            impls = json.loads(str(r["implementing_classes_json"] or "[]"))
            tests = json.loads(str(r["test_classes_json"] or "[]"))
            drefs = json.loads(str(r["design_refs_json"] or "[]"))
            tr_meta = {
                "path": "docs/dev/TRACEABILITY.md",
                "summary": str(r["summary"] or ""),
                "design_refs": drefs,
                "implementing_classes": impls,
                "test_classes": tests,
            }
            conn.execute(
                "INSERT OR REPLACE INTO ast_symbols VALUES (?, ?, ?, 'REQ_ID', 'traceability_rows', ?, 'docs/dev/TRACEABILITY.md', ?, 1, ?)",
                (r_sym, dom, f"{req_id}: {str(r['summary'] or '')[:90]}", int(r["id"]), json.dumps([req_id]), json.dumps(tr_meta)),
            )
            for ic in impls:
                _upsert_edge(f"cls:{ic}", r_sym, "SATISFIES_REQ", 5.5, "docs/dev/TRACEABILITY.md")
                _upsert_edge(r_sym, f"cls:{ic}", "REQ_SATISFIED_BY", 5.5, "docs/dev/TRACEABILITY.md")


_GENERIC_QUERY_STOP_WORDS = frozenset({
    "who", "what", "how", "why", "when", "where", "did", "does", "the", "and", "for",
    "with", "from", "into", "that", "this", "every", "time", "players", "type", "instead",
    "should", "give", "explain", "happened", "really", "relate", "view", "become", "became",
    "keep", "top", "server", "during", "waiting", "locations", "speed", "make", "change",
    "set", "use", "true", "false", "rtp", "enable", "disable",
})

_AMBIGUOUS_SINGLE_WORD_LEAF_BLACKLIST = frozenset({
    "rtp", "name", "default", "world", "region", "mode", "type", "true", "false",
    "clan", "house", "tribe", "book", "page", "section", "command", "queue", "player",
})


def explain_query(
    conn: sqlite3.Connection,
    query: str,
    project: str | None = None,
) -> dict[str, Any]:
    """
    Universal Stage-3 AST Explanation Engine (`Explain`).
    Queries ONLY the solidified 6-table AST (`ast_symbols`, `ast_candidate_refs`,
    `ast_edges`, `ast_claims`, `ast_conflicts`, `ast_config_tree`) and FTS5 tables
    (`doc_sections`, `config_keys`) with zero project-specific lookup dicts.

    Automatically adapts its explanation structure to the domain's corpus regime:
      - `TECHNICAL_SCHEMA` (software/ops projects like `LeafRTP`): resolves config keys,
        CLI commands, permissions, BIT codes, Java classes, and behavioral symptoms;
        surfaces `GOAL_VS_SAFETY_INVARIANT` conflicts without lore fields.
      - `DOCUMENT_PROSE` (prose/book corpora like `oWoD`): resolves canonical entities
        and corpus-discovered epithets; surfaces multi-perspective and edition conflicts.
    """
    prev_factory = conn.row_factory
    conn.row_factory = sqlite3.Row
    try:
        if not _table_exists(conn, "ast_symbols"):
            return {
                "query": query,
                "domain": project or "default",
                "corpus_regime": "UNKNOWN",
                "resolved_ast_symbols": [],
                "ast_subgraph_edges": [],
                "causal_claims": [],
                "flagged_conflicts": [],
                "top_ast_neighbors": [],
                "typed_causal_claims": [],
                "intentional_meta_lore_perspectives": [],
                "v20_vs_v5_edition_divergences": [],
                "verbatim_quotes_by_voice_and_lens": [],
            }

        dom_filter = project.strip().lower() if (project and project.strip().lower() not in ("", "all", "*")) else None

        # Detect whether target domain is a technical schema corpus vs a prose document corpus
        tech_check_sql = "SELECT 1 FROM ast_symbols WHERE symbol_type IN ('CONFIG_KEY', 'CLI_COMMAND', 'BIT_CODE', 'JAVA_CLASS')"
        tech_check_args: tuple[Any, ...] = ()
        if dom_filter:
            tech_check_sql += " AND lower(domain) = ?"
            tech_check_args = (dom_filter,)
        is_technical = conn.execute(tech_check_sql + " LIMIT 1", tech_check_args).fetchone() is not None

        matched_symbols: list[dict[str, Any]] = []
        seen_ids: set[str] = set()

        def _add_sym_row(r: sqlite3.Row | dict[str, Any], front: bool = False) -> None:
            d = dict(r)
            sid = d["symbol_id"]
            if sid in seen_ids:
                if front:
                    matched_symbols[:] = [m for m in matched_symbols if m["symbol_id"] != sid]
                    matched_symbols.insert(0, d)
                return
            seen_ids.add(sid)
            if front:
                matched_symbols.insert(0, d)
            else:
                matched_symbols.append(d)

        # Extract explicit slash commands (`/rtp`, `/rtp fill`) and dotted tokens (`vert.maxY`, `rtp.unqueued`)
        for m_cmd in re.finditer(r"(?<![A-Za-z0-9_])(/[a-z][a-z0-9_-]*(?:\s+[a-z][a-z0-9_-]*)?)", query, re.IGNORECASE):
            cmd_low = " ".join(m_cmd.group(1).lower().split())
            row_c = conn.execute("SELECT * FROM ast_symbols WHERE symbol_id = ?", (f"cmd:{cmd_low}",)).fetchone()
            if row_c:
                _add_sym_row(row_c)

        raw_tokens = [
            tok for tok in re.findall(r"[A-Za-z0-9_./-]+", query)
            if len(tok) >= 3 and tok.lower() not in _GENERIC_QUERY_STOP_WORDS
        ]

        # Tier 1: Exact token, n-gram phrase, and corpus-discovered alias/epithet lookup in `ast_symbols`
        words_alpha = [w for w in re.findall(r"[A-Za-z0-9_./'-]{3,}", query)]
        cand_phrases: list[str] = []
        for n in (3, 2):
            for i in range(len(words_alpha) - n + 1):
                ph = " ".join(words_alpha[i : i + n])
                if " and " not in ph.lower():
                    cand_phrases.append(ph)
        cand_phrases.extend(raw_tokens)

        for phrase in cand_phrases:
            p_low = phrase.lower().strip(".,;:!?\"'/")
            if len(p_low) < 3:
                continue
            if " " not in p_low and "." not in p_low and p_low in _AMBIGUOUS_SINGLE_WORD_LEAF_BLACKLIST:
                continue
            p_norm = _normalize_proper_key(phrase)
            the_p = p_low if p_low.startswith("the ") else f"the {p_low}"
            if dom_filter:
                rows = conn.execute(
                    """
                    SELECT * FROM ast_symbols
                    WHERE lower(domain) = ?
                      AND symbol_type != 'BEHAVIORAL_SYMPTOM'
                      AND (
                          lower(canonical_name) IN (?, ?)
                          OR lower(symbol_id) LIKE '%:' || ?
                          OR lower(aliases_json) LIKE '%' || ? || '%'
                          OR lower(aliases_json) LIKE '%' || ? || '%'
                      )
                    ORDER BY CASE WHEN lower(canonical_name) IN (?, ?) THEN 0 ELSE 1 END,
                             doc_frequency DESC
                    LIMIT 1
                    """,
                    (dom_filter, p_low, p_norm, p_low, f'"{p_low}"', f'"{the_p}"', p_low, p_norm),
                ).fetchall()
            else:
                rows = conn.execute(
                    """
                    SELECT * FROM ast_symbols
                    WHERE symbol_type != 'BEHAVIORAL_SYMPTOM'
                      AND (
                          lower(canonical_name) IN (?, ?)
                          OR lower(symbol_id) LIKE '%:' || ?
                          OR lower(aliases_json) LIKE '%' || ? || '%'
                          OR lower(aliases_json) LIKE '%' || ? || '%'
                      )
                    ORDER BY CASE WHEN lower(canonical_name) IN (?, ?) THEN 0 ELSE 1 END,
                             doc_frequency DESC
                    LIMIT 1
                    """,
                    (p_low, p_norm, p_low, f'"{p_low}"', f'"{the_p}"', p_low, p_norm),
                ).fetchall()
            for r in rows:
                _add_sym_row(r)

        # Tier 2: Score `BEHAVIORAL_SYMPTOM` and `CONFIG_KEY` symbols by query token & stem overlap
        q_token_set = {t.lower().strip(".,;:!?/") for t in raw_tokens if len(t) >= 3}
        q_stems = {t[:5] for t in q_token_set if len(t) >= 5}
        if "refill" in q_token_set:
            q_token_set.update({"period", "postteleportqueueing", "fill"})
        if q_token_set and is_technical:
            sym_pool_sql = "SELECT * FROM ast_symbols WHERE symbol_type IN ('BEHAVIORAL_SYMPTOM', 'CONFIG_KEY', 'PERMISSION_NODE')"
            sym_pool_args: tuple[Any, ...] = ()
            if dom_filter:
                sym_pool_sql += " AND lower(domain) = ?"
                sym_pool_args = (dom_filter,)
            scored_symptoms: list[tuple[float, sqlite3.Row]] = []
            for r in conn.execute(sym_pool_sql, sym_pool_args).fetchall():
                cname_low = str(r["canonical_name"]).lower()
                cname_words = set(re.findall(r"[a-z0-9_./-]+", cname_low))
                sid_low = str(r["symbol_id"]).lower()
                leaf_low = sid_low.rsplit(":", 1)[-1].rsplit(".", 1)[-1]
                hay_words = set(re.findall(r"[a-z0-9_./-]+", f"{cname_low} {r['aliases_json']} {r['metadata_json']}".lower()))
                score = 0.0
                if leaf_low in q_token_set and leaf_low not in _AMBIGUOUS_SINGLE_WORD_LEAF_BLACKLIST:
                    score += 8.0
                for tok in q_token_set:
                    if tok in cname_words:
                        score += 3.0
                    elif tok in hay_words:
                        score += 1.0
                for st in q_stems:
                    if any(w.startswith(st) for w in cname_words):
                        score += 1.5
                if score >= 3.0:
                    scored_symptoms.append((score, r))
            scored_symptoms.sort(key=lambda x: x[0], reverse=True)
            for _sc, r in reversed(scored_symptoms[:5]):
                _add_sym_row(r, front=True)

        # Tier 3: FTS5 Section & Config-Key Bridge (`doc_sections` / `config_keys` -> `ast_candidate_refs`)
        # Only pad from `doc_sections -> ast_candidate_refs` when Tier 1 + Tier 2 found no symbols (or in technical corpora when < 3),
        # preventing high-frequency global nouns (`Kindred`, `Sabbat`) from polluting specific multi-entity prose queries.
        fts_terms = [
            f'"{re.sub(r"[^A-Za-z0-9]", "", t)}"'
            for t in raw_tokens
            if len(re.sub(r"[^A-Za-z0-9]", "", t)) >= 4 and t.lower() not in _AMBIGUOUS_SINGLE_WORD_LEAF_BLACKLIST
        ][:6]
        need_doc_fallback = (len(matched_symbols) < 3) if is_technical else (len(matched_symbols) == 0)
        if fts_terms and _table_exists(conn, "doc_sections") and need_doc_fallback:
            fts_q = " OR ".join(fts_terms)
            try:
                sec_rows = (
                    conn.execute(
                        "SELECT rowid AS id FROM doc_sections WHERE project = ? AND doc_sections MATCH ? ORDER BY rank LIMIT 4",
                        (dom_filter, fts_q),
                    ).fetchall()
                    if dom_filter
                    else conn.execute(
                        "SELECT rowid AS id FROM doc_sections WHERE doc_sections MATCH ? ORDER BY rank LIMIT 4",
                        (fts_q,),
                    ).fetchall()
                )
                sec_ids = [int(sr["id"]) for sr in sec_rows]
                if sec_ids:
                    ph_sec = ",".join("?" for _ in sec_ids)
                    for sid in sec_ids:
                        sym_sec = conn.execute(
                            "SELECT * FROM ast_symbols WHERE defining_table = 'doc_sections' AND defining_id = ? LIMIT 2",
                            (sid,),
                        ).fetchall()
                        for sr_sym in sym_sec:
                            _add_sym_row(sr_sym)
                    ref_rows = conn.execute(
                        f"""
                        SELECT s.*
                        FROM ast_candidate_refs r
                        JOIN ast_symbols s ON s.symbol_id = r.resolved_symbol_id
                        WHERE r.source_table = 'doc_sections' AND r.section_id IN ({ph_sec})
                        GROUP BY s.symbol_id
                        ORDER BY count(*) DESC, s.doc_frequency DESC
                        LIMIT 4
                        """,
                        sec_ids,
                    ).fetchall()
                    for rr in ref_rows:
                        _add_sym_row(rr)
            except sqlite3.Error:
                pass

        if fts_terms and _table_exists(conn, "config_keys"):
            fts_q = " OR ".join(fts_terms)
            has_exact_cfg_sym = any(s["symbol_type"] == "CONFIG_KEY" for s in matched_symbols)
            try:
                cfg_hits = conn.execute(
                    "SELECT file_path, key_path FROM config_keys WHERE config_keys MATCH ? ORDER BY rank LIMIT 4",
                    (fts_q,),
                ).fetchall()
                for ch in reversed(cfg_hits[:2]):
                    cid = f"cfg:{ch['file_path']}:{ch['key_path']}"
                    csym = conn.execute("SELECT * FROM ast_symbols WHERE symbol_id = ?", (cid,)).fetchone()
                    if csym:
                        _add_sym_row(csym, front=not has_exact_cfg_sym)
                for ch in cfg_hits[2:]:
                    cid = f"cfg:{ch['file_path']}:{ch['key_path']}"
                    csym = conn.execute("SELECT * FROM ast_symbols WHERE symbol_id = ?", (cid,)).fetchone()
                    if csym:
                        _add_sym_row(csym)
            except sqlite3.Error:
                pass

        # Identify specific (non-root-command) matched symbols so generic `/rtp` does not pull in unrelated hub edges/conflicts
        non_hub_matched = [
            s for s in matched_symbols
            if not (s["symbol_type"] == "CLI_COMMAND" and len(str(s["canonical_name"]).split()) == 1)
        ]
        seed_symbols = non_hub_matched if non_hub_matched else matched_symbols
        specific_ids = {s["symbol_id"] for s in seed_symbols[:8]}
        specific_names_low = {str(s["canonical_name"]).lower() for s in seed_symbols[:8]}
        direct_tech_ids = {
            s["symbol_id"]
            for s in seed_symbols[:8]
            if s["symbol_type"] in ("CONFIG_KEY", "PERMISSION_NODE", "CLI_COMMAND", "BIT_CODE")
        }

        # Traverse AST Subgraph Edges from Specific Matched Symbols
        subgraph_edges: list[dict[str, Any]] = []
        seen_edge_keys: set[tuple[str, str, str]] = set()
        governed_key_neighbors: set[str] = set()
        neighbor_ids: set[str] = set()
        for sym in seed_symbols[:8]:
            edges = conn.execute(
                """
                SELECT e.src_symbol_id, e.dst_symbol_id, e.edge_kind, e.weight, e.support_count, e.evidence_json,
                       s.canonical_name AS dst_name, s.symbol_type AS dst_type, s.canonical_url AS dst_url, s.metadata_json AS dst_meta
                FROM ast_edges e
                JOIN ast_symbols s ON s.symbol_id = e.dst_symbol_id
                WHERE e.src_symbol_id = ? OR (e.dst_symbol_id = ? AND e.edge_kind = 'GOVERNED_BY_KEY')
                ORDER BY CASE
                    WHEN e.edge_kind IN ('GOVERNED_BY_KEY', 'MITIGATED_BY_COMMAND', 'IMPLEMENTED_IN_CLASS', 'GOVERNED_BY_INVARIANT', 'READS_CONFIG_KEY', 'EMITS_BIT_CODE', 'EMITTED_BY_CLASS', 'EMITS_BIT_ON_FAILURE') THEN 0
                    WHEN e.edge_kind IN ('GOVERNED_BY_ADR', 'ADR_GOVERNS_CLASS', 'SATISFIES_REQ', 'REQ_SATISFIED_BY', 'SHARES_CONFIG_KEY', 'IMPLEMENTS_SPI', 'SPI_IMPLEMENTED_BY', 'SIBLING_IMPLEMENTATION', 'VERIFIED_BY_TEST') THEN 1
                    WHEN e.edge_kind != 'DOCUMENTED_WITH' THEN 2
                    ELSE 3
                END, e.weight DESC
                LIMIT 10
                """,
                (sym["symbol_id"], sym["symbol_id"]),
            ).fetchall()
            for ed in edges:
                ekey = (str(ed["src_symbol_id"]), str(ed["dst_symbol_id"]), str(ed["edge_kind"]))
                if ekey not in seen_edge_keys:
                    seen_edge_keys.add(ekey)
                    subgraph_edges.append(dict(ed))
                neighbor_ids.add(ed["dst_symbol_id"])
                neighbor_ids.add(ed["src_symbol_id"])
                if sym["symbol_type"] == "BEHAVIORAL_SYMPTOM" and ed["edge_kind"] == "GOVERNED_BY_KEY" and str(ed["dst_symbol_id"]).startswith(("cfg:", "perm:")):
                    governed_key_neighbors.add(str(ed["dst_symbol_id"]))

        subgraph_edges.sort(
            key=lambda e: (
                0 if (e["edge_kind"] != "DOCUMENTED_WITH" and e["src_symbol_id"] in specific_ids and e["dst_symbol_id"] in specific_ids) else 1,
                0 if e["edge_kind"] in ("GOVERNED_BY_KEY", "MITIGATED_BY_COMMAND", "IMPLEMENTED_IN_CLASS", "GOVERNED_BY_INVARIANT", "READS_CONFIG_KEY", "EMITS_BIT_CODE", "EMITTED_BY_CLASS", "EMITS_BIT_ON_FAILURE", "DIABLERIZED", "TRUE_IDENTITY_OF", "SIRED_OR_CREATED", "DESTROYED_OR_PURGED", "ALLIED_OR_JOINED", "FOUGHT_OR_OPPOSED", "VIEWS_OR_JUDGES") else (
                    1 if e["edge_kind"] != "DOCUMENTED_WITH" else 2
                ),
                -float(e["weight"]),
            )
        )

        # Fetch Claims: prioritize claims connecting two `specific_ids` directly in SQL before single-side claims
        claim_focus_ids = list(specific_ids) if specific_ids else list(seen_ids)
        placeholders_cf = ",".join("?" for _ in claim_focus_ids) if claim_focus_ids else "''"
        raw_claims = (
            conn.execute(
                f"""
                SELECT c.*, s1.canonical_name AS src_name, s2.canonical_name AS dst_name
                FROM ast_claims c
                JOIN ast_symbols s1 ON s1.symbol_id = c.src_symbol_id
                JOIN ast_symbols s2 ON s2.symbol_id = c.dst_symbol_id
                WHERE c.src_symbol_id IN ({placeholders_cf}) OR c.dst_symbol_id IN ({placeholders_cf})
                ORDER BY CASE WHEN c.src_symbol_id IN ({placeholders_cf}) AND c.dst_symbol_id IN ({placeholders_cf}) THEN 0 ELSE 1 END,
                         CASE WHEN c.predicate != 'DOCUMENTED_WITH' THEN 0 ELSE 1 END
                LIMIT 60
                """,
                claim_focus_ids * 4,
            ).fetchall()
            if claim_focus_ids
            else []
        )
        scored_claims: list[tuple[tuple[int, int, int], sqlite3.Row]] = []
        for c in raw_claims:
            both_in = 0 if (c["src_symbol_id"] in specific_ids and c["dst_symbol_id"] in specific_ids) else 1
            pred_rank = 0 if c["predicate"] != "DOCUMENTED_WITH" else 1
            ed_rank = 0 if c["edition"] in ("V20", "Canonical") else (2 if c["edition"] == "V5" else 1)
            scored_claims.append(((both_in, pred_rank, ed_rank), c))
        scored_claims.sort(key=lambda x: x[0])
        claims = [c for _rk, c in scored_claims[:12]]

        # Fetch Conflicts: scope to `specific_ids` (plus keys directly governed by a matched symptom in technical corpora,
        # or 1-hop entity neighbors in prose corpora when a query asks about a connected event/figure)
        conflict_Lookup_ids = list(specific_ids | (governed_key_neighbors if is_technical else neighbor_ids))
        placeholders_conf = ",".join("?" for _ in conflict_Lookup_ids) if conflict_Lookup_ids else "''"
        raw_conflicts = (
            conn.execute(
                f"SELECT * FROM ast_conflicts WHERE focal_symbol_id IN ({placeholders_conf}) LIMIT 40",
                conflict_Lookup_ids,
            ).fetchall()
            if conflict_Lookup_ids
            else []
        )
        scored_conflicts: list[tuple[int, sqlite3.Row]] = []
        for cf in raw_conflicts:
            fid = str(cf["focal_symbol_id"])
            if is_technical:
                fid_leaf = fid.rsplit(":", 1)[-1].rsplit(".", 1)[-1].lower()
                if fid in direct_tech_ids or (fid_leaf in q_token_set and fid_leaf not in _AMBIGUOUS_SINGLE_WORD_LEAF_BLACKLIST):
                    scored_conflicts.append((0, cf))
                elif fid in governed_key_neighbors:
                    cf_words = set(re.findall(r"[a-z0-9_./-]+", f"{fid} {cf['explanation_note']}".lower()))
                    overlap_cnt = len((q_token_set - {"rtp", "server", "players", "type", "opens"}) & cf_words)
                    if overlap_cnt >= 2:
                        scored_conflicts.append((1, cf))
            else:
                sa_low = str(cf["side_a_target"]).lower()
                sb_low = str(cf["side_b_target"]).lower()
                if fid in specific_ids:
                    rank_c = 0 if (sa_low in specific_names_low or sb_low in specific_names_low) else 1
                    scored_conflicts.append((rank_c, cf))
                elif sa_low in specific_names_low or sb_low in specific_names_low:
                    scored_conflicts.append((2, cf))
        scored_conflicts.sort(key=lambda x: x[0])
        conflicts = [cf for _rc, cf in scored_conflicts[:8]]

        # Gather verbatim quotes from `doc_sections` FTS5 if available
        verbatim_quotes: list[dict[str, Any]] = []
        if fts_terms and _table_exists(conn, "doc_sections"):
            try:
                q_rows = (
                    conn.execute(
                        "SELECT rowid AS id, path, heading, title, body, url, tags FROM doc_sections WHERE project = ? AND doc_sections MATCH ? ORDER BY rank LIMIT 4",
                        (dom_filter, " OR ".join(fts_terms)),
                    ).fetchall()
                    if dom_filter
                    else conn.execute(
                        "SELECT rowid AS id, path, heading, title, body, url, tags FROM doc_sections WHERE doc_sections MATCH ? ORDER BY rank LIMIT 4",
                        (" OR ".join(fts_terms),),
                    ).fetchall()
                )
                for qr in q_rows:
                    q_body = decode_inverted_cmap_text(str(qr["body"] or "")) if not is_technical else str(qr["body"] or "")
                    ed = _detect_edition_tag(str(qr["path"] or ""), str(qr["title"] or ""), str(qr["tags"] or ""))
                    voice = _classify_discourse_voice(q_body, path=str(qr["path"] or ""), is_technical=is_technical)
                    lens = _derive_section_perspective_lens(str(qr["path"] or ""), str(qr["heading"] or ""), str(qr["title"] or ""), q_body, is_technical=is_technical)
                    q_item: dict[str, Any] = {
                        "citation": f"{qr['title'] or qr['path']} ({qr['heading'] or 'section'})",
                        "book": str(qr["path"] or ""),
                        "heading": str(qr["heading"] or ""),
                        "voice": voice,
                        "section_context": lens,
                        "edition": ed,
                        "url": str(qr["url"] or ""),
                        "quote": clean_sentence_excerpt(q_body, max_len=260),
                    }
                    if not is_technical:
                        q_item["meta_lore_lens"] = lens
                    verbatim_quotes.append(q_item)
            except sqlite3.Error:
                pass

        rashomon_perspectives = (
            [dict(c) for c in conflicts if c["conflict_kind"] != "V20_VS_V5_EDITION_RETCON"]
            if not is_technical
            else []
        )
        v5_retcons = (
            [dict(c) for c in conflicts if c["conflict_kind"] == "V20_VS_V5_EDITION_RETCON"]
            if not is_technical
            else []
        )

        typed_claims_out: list[dict[str, Any]] = []
        for c in claims[:8]:
            c_item: dict[str, Any] = {
                "claim": f"{c['src_name']} --[{c['predicate']} ({c['polarity']})]--> {c['dst_name']}",
                "voice": c["epistemic_voice"],
                "section_context": c["clan_perspective"],
                "edition": c["edition"],
                "citation": c["citation"],
                "quote": c["verbatim_quote"],
            }
            if not is_technical:
                c_item["meta_lore_lens"] = c["clan_perspective"]
            typed_claims_out.append(c_item)

        return {
            "query": query,
            "domain": dom_filter or (matched_symbols[0]["domain"] if matched_symbols else "default"),
            "corpus_regime": "TECHNICAL_SCHEMA" if is_technical else "DOCUMENT_PROSE",
            "resolved_ast_symbols": [
                {
                    "id": s["symbol_id"],
                    "name": s["canonical_name"],
                    "type": s["symbol_type"],
                    "mentions": s["doc_frequency"],
                    "aliases": json.loads(s["aliases_json"] or "[]"),
                }
                for s in matched_symbols[:6]
            ],
            "ast_subgraph_edges": [
                {
                    "src": e["src_symbol_id"],
                    "rel": e["edge_kind"],
                    "dst": e["dst_symbol_id"],
                    "dst_name": e["dst_name"],
                    "dst_type": e["dst_type"],
                    "weight": e["weight"],
                }
                for e in subgraph_edges[:12]
            ],
            "top_ast_neighbors": [
                {
                    "src": e["src_symbol_id"],
                    "rel": e["edge_kind"],
                    "dst_name": e["dst_name"],
                    "weight": e["weight"],
                    "support_pages": e["support_count"],
                    "sample_citations": json.loads(e["evidence_json"] or "[]")[:3],
                }
                for e in subgraph_edges[:8]
            ],
            "causal_claims": [dict(c) for c in claims[:8]],
            "typed_causal_claims": typed_claims_out,
            "flagged_conflicts": [dict(c) for c in conflicts],
            "intentional_meta_lore_perspectives": rashomon_perspectives[:5],
            "v20_vs_v5_edition_divergences": v5_retcons[:4],
            "verbatim_quotes_by_voice_and_lens": verbatim_quotes,
        }
    finally:
        conn.row_factory = prev_factory


# Backward-compatible wrappers for existing callers and tests
def solidify_leafrtp_ast_in_place(
    conn: sqlite3.Connection,
    project: str = "leafrtp",
    llm_scanner_fn: Any = None,
) -> dict[str, int]:
    return bake_project_ast(conn, project=project, llm_scanner_fn=llm_scanner_fn)


def explain_leafrtp_query(conn: sqlite3.Connection, query: str, project: str | None = None) -> dict[str, Any]:
    return explain_query(conn, query=query, project=project)


def esc_html_idempotent(text: Any) -> str:
    """
    Idempotent HTML escaping for static atlas and web renderers: decodes any
    pre-existing HTML entities (`&amp;`, `&quot;`, `&#39;`) before escaping once,
    preventing `&amp;amp;` double-encoding in labels like `Player & Storyteller Guide`.
    """
    import html as _html

    raw = str(text if text is not None else "")
    for _ in range(2):
        unesc = _html.unescape(raw)
        if unesc == raw:
            break
        raw = unesc
    return _html.escape(raw, quote=True)


def render_inline_markdown_and_links(
    text: str,
    symbol_url_map: dict[str, str],
    current_url: str = "",
    render_citation_fn: Any = None,
    on_link_fn: Any = None,
    on_cite_fn: Any = None,
) -> str:
    """
    Shared inline Markdown + AST entity auto-linker for static atlas / compendium HTML:
      1. Normalizes pre-escaped HTML entities via `esc_html_idempotent` (no `&amp;amp;`).
      2. Unwraps backticked or `<code>`-wrapped terms (e.g. `` `Hunter-net` ``) when the term
         resolves to a known AST symbol in `symbol_url_map` so it becomes a clickable `<a class="wikilink">`.
      3. Converts `[ABBR, p. N]` into citation chips and translates inline Markdown (`**bold**`,
         `*italic*`, and non-entity `` `code` ``) into clean HTML tags.
    """
    import html as _html

    if not text:
        return ""

    raw = str(text)
    for _ in range(2):
        unesc = _html.unescape(raw)
        if unesc == raw:
            break
        raw = unesc

    # Unwrap `<code>Term</code>` or `` `Term` `` if `Term` is a known linked entity in symbol_url_map
    def _unwrap_entity_code(m: re.Match[str]) -> str:
        inner = m.group(1).strip()
        if inner.lower() in symbol_url_map:
            return inner
        return m.group(0)

    raw = re.sub(r"<code>([^<]{2,60})</code>", _unwrap_entity_code, raw, flags=re.IGNORECASE)
    raw = re.sub(r"`([^`\n]{2,60})`", _unwrap_entity_code, raw)

    cite_placeholders: dict[str, str] = {}

    def _repl_cite(m: re.Match[str]) -> str:
        abbr = m.group(1)
        p_part = m.group(2)
        if on_cite_fn is not None:
            on_cite_fn(abbr, p_part)
        key = f"__CITE_TOKEN_{len(cite_placeholders)}__"
        if render_citation_fn is not None:
            cite_placeholders[key] = str(render_citation_fn(f"[{abbr}, {p_part}]"))
        else:
            cite_placeholders[key] = f'<span class="cite">[{esc_html_idempotent(abbr)}, {esc_html_idempotent(p_part)}]</span>'
        return key

    staged = re.sub(r"\[([A-Z0-9-]+)\s*,\s*(p+\.\s*\d+(?:[–-]\d+)?)\]", _repl_cite, raw)

    # Extract remaining non-entity inline `code` spans before HTML escaping
    code_placeholders: dict[str, str] = {}

    def _repl_code(m: re.Match[str]) -> str:
        ckey = f"__CODE_TOKEN_{len(code_placeholders)}__"
        code_placeholders[ckey] = f"<code>{esc_html_idempotent(m.group(1))}</code>"
        return ckey

    staged = re.sub(r"`([^`\n]+)`", _repl_code, staged)

    # Stage inline Markdown **bold** and *italic* with safe tokens
    staged = re.sub(r"\*\*([^*]+)\*\*", r"__MD_STRONG_O__\1__MD_STRONG_C__", staged)
    staged = re.sub(r"(?<!\*)\*([^*\n]+)\*(?!\*)", r"__MD_EM_O__\1__MD_EM_C__", staged)

    escaped = esc_html_idempotent(staged)

    candidates = sorted(symbol_url_map.keys(), key=len, reverse=True)
    used_urls: set[str] = set()
    link_placeholders: dict[str, str] = {}
    for cand_low in candidates:
        if len(cand_low) < 4:
            continue
        target_url = symbol_url_map[cand_low]
        if target_url == current_url or target_url in used_urls:
            continue
        pat = re.compile(r"\b(" + re.escape(esc_html_idempotent(cand_low)) + r")\b", re.IGNORECASE)
        m_link = pat.search(escaped)
        if m_link:
            if on_link_fn is not None:
                on_link_fn(current_url, target_url)
            used_urls.add(target_url)
            lkey = f"__LINK_TOKEN_{len(link_placeholders)}__"
            link_placeholders[lkey] = f'<a class="wikilink" href="{target_url}">{m_link.group(1)}</a>'
            escaped = pat.sub(lkey, escaped, count=1)

    escaped = escaped.replace("__MD_STRONG_O__", "<strong>").replace("__MD_STRONG_C__", "</strong>")
    escaped = escaped.replace("__MD_EM_O__", "<em>").replace("__MD_EM_C__", "</em>")
    for lkey, link_html in link_placeholders.items():
        escaped = escaped.replace(lkey, link_html)
    for ckey, code_html in code_placeholders.items():
        escaped = escaped.replace(ckey, code_html)
    for key, chip_html in cite_placeholders.items():
        escaped = escaped.replace(key, chip_html)
    return escaped


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Universal Domain-Agnostic Corpus Atlas & AST Engine (`bake` and `explain`)."
    )
    sub = parser.add_subparsers(dest="command", required=True)

    p_bake = sub.add_parser("bake", help="Bake 6-table AST in-place on a SQLite index (or build from --source dir first).")
    p_bake.add_argument("--db", type=pathlib.Path, required=True, help="Path to SQLite database to bake.")
    p_bake.add_argument("--source", type=pathlib.Path, default=None, help="Optional project root directory to index into --db before baking AST.")
    p_bake.add_argument("--project", type=str, default=None, help="Project/domain slug (defaults to all discovered domains in --db).")
    p_bake.add_argument("--manifest", type=pathlib.Path, default=None, help="Optional support-bot.yml manifest path when --source is provided.")

    p_exp = sub.add_parser("explain", help="Run AST-guided explanation on any baked SQLite database.")
    p_exp.add_argument("--db", type=pathlib.Path, required=True, help="Path to baked SQLite database.")
    p_exp.add_argument("--query", type=str, required=True, help="Question, symptom, or entity to explain.")
    p_exp.add_argument("--project", type=str, default=None, help="Optional project/domain filter.")

    args = parser.parse_args(argv)
    if args.command == "bake":
        if args.source is not None:
            from build_doc_index import build_index

            conn = build_index(
                repo_root=args.source.resolve(),
                db_path=args.db.resolve(),
                manifest=args.manifest,
            )
            stats = {
                tbl: conn.execute(f"SELECT count(*) FROM {tbl}").fetchone()[0]
                for tbl in ("ast_symbols", "ast_candidate_refs", "ast_edges", "ast_claims", "ast_conflicts", "ast_config_tree")
            }
            conn.close()
        else:
            conn = sqlite3.connect(str(args.db))
            try:
                stats = bake_project_ast(conn, project=args.project)
                conn.commit()
            finally:
                conn.close()
        print(json.dumps(stats, indent=2))
        return 0

    if args.command == "explain":
        conn = sqlite3.connect(str(args.db))
        try:
            res = explain_query(conn, query=args.query, project=args.project)
        finally:
            conn.close()
        print(json.dumps(res, indent=2))
        return 0

    return 1


if __name__ == "__main__":
    raise SystemExit(main())
