#!/usr/bin/env python3
"""Whole-server discovery, passive command-graph filtering, and agentless observation (ADR-125, stdlib only).

Merges per-tier Brigadier `declare_commands` graphs into a canonical command tree,
folds namespaced aliases (`namespace:cmd` <-> `cmd`) only when their resolved
subtrees are structurally equal, cuts redirect cycles, records visible tiers and
the minimum reproducing tier (`default` before `op`), applies path and
argument-kind denylists (`DEFAULT_DENYLIST` + `--deny`), serialises the merged
command inventory to `commands.json`, and provides read-only `JcmdObserver`
collection, offline `JarIndex` archive scanning, and passive statistical
summaries of operator-supplied histograms and tick gaps.
"""
from __future__ import annotations

from dataclasses import dataclass, field
import fnmatch
import glob
import io
import json
import math
import os
import re
from statistics import NormalDist
import subprocess
import sys
import time
from typing import Any, Callable, Iterable
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bench_leakcheck as lc  # noqa: E402
import bench_fuzz as bf  # noqa: E402

TIER_ORDER: tuple[str, ...] = ("default", "op")

DEFAULT_DENYLIST: tuple[str, ...] = (
    "stop",
    "restart",
    "op",
    "deop",
    "ban*",
    "pardon*",
    "whitelist",
    "save-off",
    "kill <entity>",
    "execute",
    "reload confirm",
    "kick",
    "difficulty",
    "gamerule",
    "tick freeze",
    "tick unfreeze",
    "tick rate",
    "tick sprint",
    "tick step",
)


def tier_rank(tier: str) -> int:
    try:
        return TIER_ORDER.index(tier)
    except ValueError:
        return len(TIER_ORDER)


def sort_tiers(tiers: Iterable[str]) -> list[str]:
    unique = {str(t).strip() for t in tiers if str(t).strip()}
    return sorted(unique, key=lambda t: (tier_rank(t), t))


def min_visible_tier(tiers: Iterable[str]) -> str:
    ordered = sort_tiers(tiers)
    if not ordered:
        return "op"
    return ordered[0]


def split_namespace(name: str) -> tuple[str | None, str]:
    """Return `(namespace, bare_name)` for a root literal, or `(None, name)`."""
    if ":" in name:
        ns, _, bare = name.partition(":")
        if ns and bare and ":" not in bare:
            return ns, bare
    return None, name


def normalize_parser_kind(parser: str | None) -> str:
    """Short parser kind used by `<argkind>` tokens (e.g. `minecraft:entity` -> `entity`)."""
    if not parser:
        return "arg"
    p = str(parser).strip().lower()
    if ":" in p:
        _, _, short = p.partition(":")
        if short:
            return short
    return p or "arg"


def normalize_properties(props: Any) -> dict[str, Any]:
    if not isinstance(props, dict):
        return {}
    out: dict[str, Any] = {}
    for k in sorted(props.keys()):
        v = props[k]
        if isinstance(v, dict):
            out[str(k)] = normalize_properties(v)
        elif isinstance(v, list):
            out[str(k)] = list(v)
        else:
            out[str(k)] = v
    return out


@dataclass(frozen=True)
class DenylistPattern:
    raw: str
    root_glob: str
    subpath: tuple[str, ...]
    arg_kind: str | None

    @classmethod
    def parse(cls, entry: str) -> DenylistPattern:
        text = (entry or "").strip()
        if not text:
            raise ValueError("denylist entry must not be empty")
        tokens = [t for t in text.split() if t]
        root_tok = tokens[0].lower()
        _, root_bare = split_namespace(root_tok)
        root_glob = root_bare or root_tok
        sub: list[str] = []
        arg_kind: str | None = None
        for idx, tok in enumerate(tokens[1:], start=1):
            low = tok.lower()
            if low.startswith("<") and low.endswith(">") and len(low) > 2:
                if idx != len(tokens) - 1:
                    raise ValueError(f"denylist <argkind> must be the final token in {entry!r}")
                arg_kind = normalize_parser_kind(low[1:-1])
            elif "<" in low or ">" in low:
                raise ValueError(f"malformed argument token {tok!r} in denylist entry {entry!r}")
            else:
                sub.append(low)
        return cls(raw=text, root_glob=root_glob, subpath=tuple(sub), arg_kind=arg_kind)


class Denylist:
    """Enforces ADR-125 section 2.5 command path and `<argkind>` denylist rules."""

    def __init__(self, extra_entries: Iterable[str] | None = None):
        entries: list[str] = list(DEFAULT_DENYLIST)
        if extra_entries:
            for e in extra_entries:
                if e is None:
                    continue
                for part in str(e).split(","):
                    part = part.strip()
                    if part and part not in entries:
                        entries.append(part)
        self.entries: tuple[str, ...] = tuple(entries)
        self.patterns: tuple[DenylistPattern, ...] = tuple(DenylistPattern.parse(e) for e in self.entries)

    def match_node(
        self,
        root_names: Iterable[str],
        token_steps: SequenceStepList,
        next_arg_parsers: Iterable[str] = (),
    ) -> str | None:
        """Return the matching denylist rule string if this node path is denied, else None.

        `root_names` includes the canonical root name and all aliases (both bare and
        namespaced forms are checked against `root_glob` after stripping any namespace).
        `token_steps` are the path steps after the root: each step is `(kind, token, parser)`
        where `kind` is `'literal'` or `'argument'`.
        """
        _ = next_arg_parsers
        candidate_roots: set[str] = set()
        for rn in root_names:
            low = str(rn).strip().lower()
            if not low:
                continue
            candidate_roots.add(low)
            _, bare = split_namespace(low)
            if bare:
                candidate_roots.add(bare)

        for pat in self.patterns:
            if not any(
                fnmatch.fnmatchcase(r, pat.root_glob) or fnmatch.fnmatchcase(split_namespace(r)[1], pat.root_glob)
                for r in candidate_roots
            ):
                continue

            if not pat.subpath and pat.arg_kind is None:
                return pat.raw

            if len(token_steps) < len(pat.subpath):
                continue

            sub_ok = True
            for step, expected_lit in zip(token_steps[: len(pat.subpath)], pat.subpath):
                step_kind, step_tok, _ = step
                if step_kind != "literal" or step_tok.lower() != expected_lit:
                    sub_ok = False
                    break
            if not sub_ok:
                continue

            rem = token_steps[len(pat.subpath) :]
            if pat.arg_kind is None:
                return pat.raw

            # Pattern ends with `<argkind>`: denies any path below `root [subpath]`
            # whose next step is an argument of that parser kind. Bare `root [subpath]`
            # (with no remaining steps) stays allowed when executable.
            if rem:
                first_kind, _, first_parser = rem[0]
                if first_kind == "argument" and self._arg_matches(first_parser, pat.arg_kind):
                    return pat.raw
        return None

    def is_denied_node(
        self,
        root_names: Iterable[str],
        token_steps: SequenceStepList,
        next_arg_parsers: Iterable[str] = (),
    ) -> bool:
        return self.match_node(root_names, token_steps, next_arg_parsers) is not None

    def match_command_line(self, cmd_line: str, tree: CommandTree | None = None) -> str | None:
        """Check whether a raw command string (e.g. `/minecraft:stop`, `kill @a`, or `tp @s`) is denied.

        When `tree` is provided, resolves both namespace-folded aliases (`alias_map`) and
        redirect chains (`redirect_target` closure on root or prefix nodes) before matching.
        """
        text = (cmd_line or "").strip()
        if text.startswith("/"):
            text = text[1:].strip()
        if not text:
            return None
        parts = [p for p in text.split() if p]
        if not parts:
            return None

        # Expand command token sequences across tree aliases and redirect_target closure.
        expanded_sequences: list[tuple[str, ...]] = [tuple(parts)]
        if tree is not None:
            lower_nodes = {nid.lower(): node for nid, node in tree.nodes.items()}
            lower_aliases = {k.lower(): v for k, v in tree.alias_map.items()}
            seen_seqs: set[tuple[str, ...]] = {tuple(p.lower() for p in parts)}
            queue: list[tuple[str, ...]] = [tuple(parts)]
            while queue and len(seen_seqs) < 64:
                curr = queue.pop(0)
                if not curr:
                    continue
                r0 = curr[0].lower()
                _, r0_bare = split_namespace(r0)
                canon_r0 = lower_aliases.get(r0) or lower_aliases.get(r0_bare) or r0
                root_node = lower_nodes.get(canon_r0.lower()) or lower_nodes.get(r0) or lower_nodes.get(r0_bare)
                active_nodes: list[CommandNode] = [root_node] if root_node is not None else []
                for prefix_len in range(1, len(curr) + 1):
                    prefix_nid = " ".join((canon_r0.lower(),) + tuple(t.lower() for t in curr[1:prefix_len]))
                    candidates: list[CommandNode] = list(active_nodes)
                    direct_node = lower_nodes.get(prefix_nid)
                    if direct_node is not None and direct_node not in candidates:
                        candidates.append(direct_node)
                    for pnode in candidates:
                        if not pnode.redirect_target:
                            continue
                        redir_str = pnode.redirect_target.strip()
                        if not redir_str or redir_str.startswith(("<root>", "@")):
                            continue
                        redir_tokens = tuple(t for t in redir_str.split() if t)
                        if not redir_tokens:
                            continue
                        new_seq = redir_tokens + curr[prefix_len:]
                        new_key = tuple(t.lower() for t in new_seq)
                        if new_key not in seen_seqs:
                            seen_seqs.add(new_key)
                            expanded_sequences.append(new_seq)
                            queue.append(new_seq)
                    if prefix_len < len(curr) and active_nodes:
                        next_tok = curr[prefix_len].lower()
                        next_active: list[CommandNode] = []
                        for pnode in active_nodes:
                            for cid in pnode.children:
                                ch = tree.nodes.get(cid)
                                if ch is None:
                                    continue
                                if (ch.kind == "literal" and ch.name.lower() == next_tok) or ch.kind == "argument":
                                    if ch not in next_active:
                                        next_active.append(ch)
                        active_nodes = next_active

        for seq in expanded_sequences:
            root_tok = seq[0].lower()
            _, bare_root = split_namespace(root_tok)
            root_candidates = {root_tok, bare_root}
            if tree is not None:
                alias_target = (
                    tree.alias_map.get(root_tok)
                    or tree.alias_map.get(bare_root)
                    or next((v for k, v in tree.alias_map.items() if k.lower() in (root_tok, bare_root)), None)
                )
                if alias_target:
                    root_candidates.add(alias_target.lower())
                    _, at_bare = split_namespace(alias_target.lower())
                    root_candidates.add(at_bare)
                    root_node = tree.nodes.get(alias_target)
                    if root_node:
                        for a in root_node.aliases:
                            al = a.lower()
                            root_candidates.add(al)
                            _, ab = split_namespace(al)
                            root_candidates.add(ab)

            rest = seq[1:]
            for pat in self.patterns:
                if not any(
                    fnmatch.fnmatchcase(r, pat.root_glob) or fnmatch.fnmatchcase(split_namespace(r)[1], pat.root_glob)
                    for r in root_candidates
                ):
                    continue
                if not pat.subpath and pat.arg_kind is None:
                    return pat.raw
                if len(rest) < len(pat.subpath):
                    continue
                if any(rest[i].lower() != pat.subpath[i] for i in range(len(pat.subpath))):
                    continue
                after_sub = rest[len(pat.subpath) :]
                if pat.arg_kind is None:
                    return pat.raw
                if after_sub:
                    return pat.raw
        return None

    def is_denied_command_line(self, cmd_line: str, tree: CommandTree | None = None) -> bool:
        return self.match_command_line(cmd_line, tree) is not None

    @staticmethod
    def _arg_matches(parser: str | None, pattern_arg_kind: str) -> bool:
        norm = normalize_parser_kind(parser)
        if norm == pattern_arg_kind:
            return True
        if pattern_arg_kind == "entity" and norm in ("entity", "game_profile", "score_holder"):
            return True
        return False


SequenceStep = tuple[str, str, str | None]
SequenceStepList = list[SequenceStep] | tuple[SequenceStep, ...]


@dataclass
class CommandNode:
    node_id: str
    name: str
    kind: str
    aliases: list[str] = field(default_factory=list)
    parser: str | None = None
    properties: dict[str, Any] = field(default_factory=dict)
    suggestion_type: str | None = None
    executable: bool = False
    tiers: list[str] = field(default_factory=list)
    min_tier: str = "op"
    children: list[str] = field(default_factory=list)
    redirect_target: str | None = None
    cycle_cut: bool = False
    denied: bool = False
    denied_by: str | None = None
    completions: list[str] = field(default_factory=list)
    _steps: tuple[SequenceStep, ...] = field(default=(), repr=False)
    _root_id: str = field(default="", repr=False)

    def to_dict(self) -> dict[str, Any]:
        return {
            "node_id": self.node_id,
            "name": self.name,
            "kind": self.kind,
            "aliases": list(self.aliases),
            "parser": self.parser,
            "properties": dict(self.properties),
            "suggestion_type": self.suggestion_type,
            "executable": self.executable,
            "tiers": list(self.tiers),
            "min_tier": self.min_tier,
            "children": list(self.children),
            "redirect_target": self.redirect_target,
            "cycle_cut": self.cycle_cut,
            "denied": self.denied,
            "denied_by": self.denied_by,
            "completions": list(self.completions),
            "root_id": self._root_id or self.node_id.partition(" ")[0],
            "steps": [
                {"kind": s_kind, "name": s_name, "parser": s_parser}
                for s_kind, s_name, s_parser in self._steps
            ],
        }

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> CommandNode:
        node_id = str(d["node_id"])
        raw_steps = d.get("steps") or []
        steps: list[SequenceStep] = []
        for s in raw_steps:
            if isinstance(s, dict):
                steps.append((str(s.get("kind") or "literal"), str(s.get("name") or ""), s.get("parser")))
            elif isinstance(s, (list, tuple)) and len(s) >= 2:
                steps.append((str(s[0]), str(s[1]), s[2] if len(s) > 2 else None))
        root_id = str(d.get("root_id") or node_id.partition(" ")[0])
        return cls(
            node_id=node_id,
            name=str(d.get("name") or ""),
            kind=str(d.get("kind") or "literal"),
            aliases=list(d.get("aliases") or []),
            parser=d.get("parser"),
            properties=dict(d.get("properties") or {}),
            suggestion_type=d.get("suggestion_type"),
            executable=bool(d.get("executable", False)),
            tiers=sort_tiers(d.get("tiers") or []),
            min_tier=str(d.get("min_tier") or min_visible_tier(d.get("tiers") or [])),
            children=list(d.get("children") or []),
            redirect_target=d.get("redirect_target"),
            cycle_cut=bool(d.get("cycle_cut", False)),
            denied=bool(d.get("denied", False)),
            denied_by=d.get("denied_by"),
            completions=list(d.get("completions") or []),
            _steps=tuple(steps),
            _root_id=root_id,
        )


@dataclass
class _ResolvedSubtree:
    kind: str
    name: str
    parser: str | None
    properties: dict[str, Any]
    suggestion_type: str | None
    executable: bool
    redirect_target_path: str | None
    cycle_cut: bool
    has_own_children: bool = False
    children: dict[str, _ResolvedSubtree] = field(default_factory=dict)
    _sig_cache: tuple[Any, ...] | None = field(default=None, repr=False, compare=False)

    def token(self) -> str:
        if self.kind == "argument":
            return f"<{self.name}>"
        return self.name

    def step(self) -> SequenceStep:
        return (self.kind, self.name, self.parser if self.kind == "argument" else None)

    def structural_signature(self) -> tuple[Any, ...]:
        """Canonical structural signature of this node's body (ignoring root literal name)."""
        if self._sig_cache is not None:
            return self._sig_cache
        child_sigs = tuple(
            (ck, child._full_signature())
            for ck, child in sorted(self.children.items(), key=lambda kv: kv[0])
        )
        sig = (
            self.kind,
            self.parser,
            json.dumps(self.properties, sort_keys=True, separators=(",", ":")),
            self.suggestion_type,
            self.executable,
            self.cycle_cut,
            child_sigs,
        )
        self._sig_cache = sig
        return sig

    def _full_signature(self) -> tuple[Any, ...]:
        return (self.token(), self.redirect_target_path) + self.structural_signature()


@dataclass
class CommandTree:
    tiers: list[str]
    nodes: dict[str, CommandNode]
    root_children: list[str]
    alias_map: dict[str, str]
    denylist_entries: list[str]
    truncated: bool = False

    def _ensure_steps(self) -> None:
        """Populate or reconstruct `_root_id` and `_steps` from the parent-child graph."""
        all_children: set[str] = set()
        for n in self.nodes.values():
            all_children.update(n.children)
        roots = [r for r in self.root_children if r in self.nodes]
        for nid in sorted(self.nodes.keys()):
            if nid not in all_children and nid not in roots:
                roots.append(nid)
        visited: set[str] = set()

        def walk(nid: str, root_id: str, steps: tuple[SequenceStep, ...]) -> None:
            if nid in visited or nid not in self.nodes:
                return
            visited.add(nid)
            node = self.nodes[nid]
            if not node._root_id:
                node._root_id = root_id
            if nid == root_id:
                node._steps = ()
            elif not node._steps:
                node._steps = steps
            curr_steps = node._steps
            for cid in node.children:
                ch = self.nodes.get(cid)
                if ch is None:
                    continue
                ch_step: SequenceStep = (
                    ch.kind,
                    ch.name,
                    ch.parser if ch.kind == "argument" else None,
                )
                walk(cid, node._root_id, curr_steps + (ch_step,))

        for r in roots:
            walk(r, r, ())

    def _resolve_redirect_target(
        self, target_path: str
    ) -> tuple[tuple[str, ...], tuple[SequenceStep, ...], str | None]:
        text = (target_path or "").strip()
        if not text or text.startswith(("<root>", "@")):
            return (), (), None
        t_parts = [p for p in text.split() if p]
        if not t_parts:
            return (), (), None
        t_root = t_parts[0]
        _, t_bare = split_namespace(t_root.lower())
        canon_root = (
            self.alias_map.get(t_root)
            or self.alias_map.get(t_root.lower())
            or self.alias_map.get(t_bare)
            or t_root
        )
        canon_target_id = " ".join([canon_root] + t_parts[1:])
        target_node = self.nodes.get(canon_target_id) or self.nodes.get(text)
        if target_node is not None:
            t_root_node = self.nodes.get(target_node._root_id)
            roots = (
                list(t_root_node.aliases)
                if (t_root_node and t_root_node.aliases)
                else [target_node._root_id or canon_root]
            )
            if t_root not in roots:
                roots.append(t_root)
            return tuple(roots), target_node._steps, target_node.node_id
        roots = [canon_root]
        if t_root not in roots:
            roots.append(t_root)
        steps: list[SequenceStep] = []
        for tok in t_parts[1:]:
            if tok.startswith("<") and tok.endswith(">") and len(tok) > 2:
                steps.append(("argument", tok[1:-1], None))
            else:
                steps.append(("literal", tok, None))
        return tuple(roots), tuple(steps), None

    def _descendant_nid(self, start_nid: str, suffix_steps: tuple[SequenceStep, ...]) -> str | None:
        cur = start_nid
        for skind, sname, sparser in suffix_steps:
            node = self.nodes.get(cur)
            if node is None:
                return None
            matched_child: str | None = None
            for cid in node.children:
                ch = self.nodes.get(cid)
                if ch is None:
                    continue
                if ch.kind == skind and ch.name == sname and (skind != "argument" or ch.parser == sparser):
                    matched_child = cid
                    break
            if matched_child is None:
                return None
            cur = matched_child
        return cur

    def apply_denylist(self, denylist: Denylist | Iterable[str] | None = None) -> CommandTree:
        """Evaluate `denylist` across all nodes, propagating denials along redirect closures."""
        deny = denylist if isinstance(denylist, Denylist) else Denylist(denylist)
        self.denylist_entries = list(deny.entries)
        self._ensure_steps()

        parent_of: dict[str, str] = {}
        for pid, pnode in self.nodes.items():
            for cid in pnode.children:
                if cid not in parent_of:
                    parent_of[cid] = pid

        def ancestor_chain(nid: str) -> list[CommandNode]:
            chain: list[CommandNode] = []
            cur: str | None = nid
            seen_anc: set[str] = set()
            while cur and cur in self.nodes and cur not in seen_anc:
                seen_anc.add(cur)
                chain.append(self.nodes[cur])
                cur = parent_of.get(cur)
            chain.reverse()
            return chain

        for nid in sorted(self.nodes.keys()):
            node = self.nodes[nid]
            root_node = self.nodes.get(node._root_id)
            init_roots = (
                tuple(root_node.aliases)
                if (root_node and root_node.aliases)
                else (node._root_id or node.name,)
            )
            queue: list[tuple[tuple[str, ...], tuple[SequenceStep, ...], str | None]] = [
                (init_roots, node._steps, nid)
            ]
            seen_states: set[tuple[tuple[str, ...], tuple[SequenceStep, ...]]] = {
                (init_roots, node._steps)
            }
            matched_rule: str | None = None
            while queue:
                curr_roots, curr_steps, curr_nid = queue.pop(0)
                rule = deny.match_node(curr_roots, curr_steps)
                if rule is not None:
                    matched_rule = rule
                    break
                if curr_nid and curr_nid in self.nodes:
                    for anc in ancestor_chain(curr_nid):
                        if not anc.redirect_target:
                            continue
                        suffix = curr_steps[len(anc._steps) :]
                        t_roots, t_steps, t_nid = self._resolve_redirect_target(anc.redirect_target)
                        if not t_roots:
                            continue
                        next_steps = t_steps + suffix
                        next_nid = self._descendant_nid(t_nid, suffix) if t_nid else None
                        state_key = (t_roots, next_steps)
                        if state_key not in seen_states:
                            seen_states.add(state_key)
                            queue.append((t_roots, next_steps, next_nid))
            node.denied = matched_rule is not None
            node.denied_by = matched_rule
        return self

    def allowed_executable_nodes(self, active_tiers: Iterable[str] | None = None) -> list[CommandNode]:
        tier_filter = set(active_tiers) if active_tiers is not None else None
        out: list[CommandNode] = []
        for nid in sorted(self.nodes.keys()):
            node = self.nodes[nid]
            if node.denied or not node.executable:
                continue
            if tier_filter is not None and not (set(node.tiers) & tier_filter):
                continue
            out.append(node)
        return out

    def to_dict(self) -> dict[str, Any]:
        return {
            "tiers": list(self.tiers),
            "root_children": list(self.root_children),
            "alias_map": dict(sorted(self.alias_map.items())),
            "denylist": list(self.denylist_entries),
            "truncated": self.truncated,
            "nodes": [self.nodes[k].to_dict() for k in sorted(self.nodes.keys())],
        }

    def write_json(self, path: str) -> dict[str, Any]:
        payload = self.to_dict()
        parent = os.path.dirname(os.path.abspath(path))
        if parent:
            os.makedirs(parent, exist_ok=True)
        with open(path, "w", encoding="utf-8") as fh:
            json.dump(payload, fh, indent=2, sort_keys=False)
            fh.write("\n")
        return payload

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> CommandTree:
        nodes_list = data.get("nodes") or []
        nodes = {str(n["node_id"]): CommandNode.from_dict(n) for n in nodes_list}
        tree = cls(
            tiers=sort_tiers(data.get("tiers") or []),
            nodes=nodes,
            root_children=list(data.get("root_children") or []),
            alias_map=dict(data.get("alias_map") or {}),
            denylist_entries=list(data.get("denylist") or DEFAULT_DENYLIST),
            truncated=bool(data.get("truncated", False)),
        )
        tree._ensure_steps()
        return tree

    @classmethod
    def read_json(cls, path: str) -> CommandTree:
        with open(path, "r", encoding="utf-8") as fh:
            return cls.from_dict(json.load(fh))


def _resolve_raw_tier_graph(packet: dict[str, Any], max_depth: int = 16) -> dict[str, _ResolvedSubtree]:
    """Resolve a single tier's `declare_commands` packet into top-level command subtrees.

    Follows `redirect` edges with visited-index cycle protection so self-redirects
    and mutual redirect loops (`execute -> run -> execute`) terminate cleanly.
    """
    raw_nodes = packet.get("nodes") or []
    if not isinstance(raw_nodes, list) or not raw_nodes:
        return {}
    root_idx = int(packet.get("rootIndex", 0) or 0)
    if root_idx < 0 or root_idx >= len(raw_nodes):
        return {}

    by_idx: dict[int, dict[str, Any]] = {}
    for pos, raw in enumerate(raw_nodes):
        if isinstance(raw, dict):
            idx = int(raw.get("index", pos))
            by_idx[idx] = raw

    root_raw = by_idx.get(root_idx)
    if not root_raw:
        return {}

    # Map node index -> shortest canonical path from root for redirect target identification (O(V + E)).
    node_paths: dict[int, str] = {}
    queue_paths: list[tuple[int, str, int]] = []
    for c_idx in root_raw.get("children") or []:
        if isinstance(c_idx, int) and c_idx in by_idx:
            c_name = str(by_idx[c_idx].get("name") or "").strip()
            if c_name and c_idx not in node_paths:
                node_paths[c_idx] = c_name
                queue_paths.append((c_idx, c_name, 1))
    while queue_paths:
        curr_idx, curr_path, curr_depth = queue_paths.pop(0)
        if curr_depth >= max_depth:
            continue
        for ch_idx in by_idx[curr_idx].get("children") or []:
            if isinstance(ch_idx, int) and ch_idx in by_idx and ch_idx != root_idx and ch_idx not in node_paths:
                ch_raw = by_idx[ch_idx]
                ch_name = str(ch_raw.get("name") or "").strip()
                if not ch_name:
                    continue
                ch_tok = f"<{ch_name}>" if ch_raw.get("kind") == "argument" else ch_name
                ch_path = f"{curr_path} {ch_tok}"
                node_paths[ch_idx] = ch_path
                queue_paths.append((ch_idx, ch_path, curr_depth + 1))

    def resolve_effective(
        idx: int, seen_redirects: frozenset[int]
    ) -> tuple[dict[str, Any], list[int], bool, str | None, bool, bool]:
        """Follow redirect chain for children/executable inheritance, detecting cycles."""
        raw = by_idx[idx]
        own_children = [c for c in (raw.get("children") or []) if isinstance(c, int) and c in by_idx]
        own_exec = bool(raw.get("executable", False))
        has_own = len(own_children) > 0
        redir = raw.get("redirect")
        if redir is None or not isinstance(redir, int) or redir not in by_idx:
            return raw, own_children, own_exec, None, False, has_own

        redir_target_name = node_paths.get(redir) or str(by_idx[redir].get("name") or f"@{redir}")
        if redir == root_idx or redir in seen_redirects:
            return raw, own_children, own_exec, redir_target_name or "<root>", True, has_own

        _, target_children, target_exec, _, sub_cycle, _ = resolve_effective(
            redir, seen_redirects | {idx, redir}
        )
        merged_children: list[int] = list(own_children)
        for tc in target_children:
            if tc not in merged_children:
                merged_children.append(tc)
        # If a node redirects without its own children, it inherits the target's executable flag
        # only when the redirect did not cut a cycle (e.g. `betterrtp:rtp -> rtp`).
        eff_exec = own_exec or (target_exec and not own_children and not sub_cycle)
        return raw, merged_children, eff_exec, redir_target_name, sub_cycle, has_own

    # Memoize acyclic resolved subtrees by `(idx, height)` to bound DAG-shaped command graphs.
    acyclic_memo: dict[int, tuple[_ResolvedSubtree, int]] = {}

    def build_node(idx: int, path_indices: frozenset[int], depth: int) -> tuple[_ResolvedSubtree | None, bool, int]:
        if idx not in by_idx or depth > max_depth:
            return None, True, 0
        cached = acyclic_memo.get(idx)
        if cached is not None and depth + cached[1] <= max_depth:
            return cached[0], False, cached[1]

        raw, eff_children, eff_exec, redir_name, redir_cycle, has_own = resolve_effective(idx, path_indices)
        kind = str(raw.get("kind") or "literal")
        name = str(raw.get("name") or "").strip()
        if not name and kind != "root":
            return None, False, 0
        parser = str(raw.get("parser")).strip() if raw.get("parser") else None
        props = normalize_properties(raw.get("properties"))
        sug = raw.get("suggestionType") or raw.get("suggestions")
        suggestion_type = str(sug).strip() if isinstance(sug, str) and sug.strip() else None

        cycle_cut = bool(redir_cycle)
        depended_on_path = bool(redir_cycle)
        max_child_height = 0
        children_map: dict[str, _ResolvedSubtree] = {}
        for c_idx in eff_children:
            if c_idx in path_indices or c_idx == root_idx:
                cycle_cut = True
                depended_on_path = True
                continue
            child_sub, child_dep, child_h = build_node(c_idx, path_indices | {c_idx}, depth + 1)
            if child_dep:
                depended_on_path = True
            if child_sub is None:
                continue
            if child_h + 1 > max_child_height:
                max_child_height = child_h + 1
            tok = _child_key_for_merge(children_map, child_sub)
            if tok not in children_map:
                children_map[tok] = child_sub
            else:
                children_map[tok] = _merge_two_subtrees(children_map[tok], child_sub)

        res = _ResolvedSubtree(
            kind=kind,
            name=name,
            parser=parser if kind == "argument" else None,
            properties=props if kind == "argument" else {},
            suggestion_type=suggestion_type if kind == "argument" else None,
            executable=eff_exec,
            redirect_target_path=redir_name,
            cycle_cut=cycle_cut,
            has_own_children=has_own,
            children=children_map,
        )
        if not depended_on_path:
            acyclic_memo[idx] = (res, max_child_height)
        return res, depended_on_path, max_child_height

    roots: dict[str, _ResolvedSubtree] = {}
    for c_idx in root_raw.get("children") or []:
        if not isinstance(c_idx, int) or c_idx not in by_idx:
            continue
        sub, _, _ = build_node(c_idx, frozenset({root_idx, c_idx}), 1)
        if sub is not None and sub.name:
            roots[sub.name] = sub
    return roots


def _merge_two_subtrees(
    a: _ResolvedSubtree,
    b: _ResolvedSubtree,
    memo: dict[tuple[int, int], _ResolvedSubtree] | None = None,
) -> _ResolvedSubtree:
    if a is b:
        return a
    if memo is None:
        memo = {}
    pair_key = (id(a), id(b))
    if pair_key in memo:
        return memo[pair_key]
    merged_children: dict[str, _ResolvedSubtree] = dict(a.children)
    for b_child in b.children.values():
        tok = _child_key_for_merge(merged_children, b_child)
        if tok in merged_children:
            merged_children[tok] = _merge_two_subtrees(merged_children[tok], b_child, memo)
        else:
            merged_children[tok] = b_child
    out = _ResolvedSubtree(
        kind=a.kind,
        name=a.name,
        parser=a.parser or b.parser,
        properties=a.properties if a.properties else b.properties,
        suggestion_type=a.suggestion_type or b.suggestion_type,
        executable=a.executable or b.executable,
        redirect_target_path=a.redirect_target_path or b.redirect_target_path,
        cycle_cut=a.cycle_cut or b.cycle_cut,
        has_own_children=a.has_own_children or b.has_own_children,
        children=merged_children,
    )
    memo[pair_key] = out
    return out


def _child_key_for_merge(existing: dict[str, _ResolvedSubtree], child: _ResolvedSubtree) -> str:
    """Return a child token key that avoids merging incompatible argument parsers."""
    base_tok = child.token()
    cur = existing.get(base_tok)
    if cur is None:
        return base_tok
    if (
        cur.kind == child.kind
        and cur.parser == child.parser
        and cur.properties == child.properties
    ):
        return base_tok
    p_short = normalize_parser_kind(child.parser) if child.kind == "argument" else child.kind
    alt_tok = f"<{child.name}:{p_short}>" if child.kind == "argument" else f"{child.name}:{p_short}"
    idx = 2
    cand = alt_tok
    while cand in existing and (
        existing[cand].kind != child.kind
        or existing[cand].parser != child.parser
        or existing[cand].properties != child.properties
    ):
        cand = f"<{child.name}:{p_short}:{idx}>" if child.kind == "argument" else f"{child.name}:{p_short}:{idx}"
        idx += 1
    return cand


def merge_command_trees(
    tier_packets: dict[str, dict[str, Any]],
    denylist: Denylist | Iterable[str] | None = None,
    out_path: str | None = None,
    max_nodes: int = 16384,
) -> CommandTree:
    """Merge per-tier `declare_commands` graphs into a single `CommandTree`.

    - Folds `namespace:cmd` and `cmd` into a single canonical root `cmd` (with
      `aliases: ["cmd", "namespace:cmd"]`) within a tier when their resolved
      subtrees are structurally equal (and any redirect is a pure alias with no
      own children), and across tiers only when every tier is compatible (including
      tiers where only one of the pair appears).
    - Resolves redirects with cycle protection (`cycle_cut=True`).
    - Tracks `tiers` and `min_tier` (`default` before `op`) on every node.
    - Marks nodes matching `Denylist` (`denied=True`, `denied_by=<pattern>`),
      propagating denials across redirect target closures.
    """
    deny = denylist if isinstance(denylist, Denylist) else Denylist(denylist)
    active_tiers = sort_tiers(tier_packets.keys())

    per_tier_roots: dict[str, dict[str, _ResolvedSubtree]] = {}
    for tier in active_tiers:
        pkt = tier_packets.get(tier) or {}
        per_tier_roots[tier] = _resolve_raw_tier_graph(pkt)

    # Decide which `namespace:bare` roots fold into `bare`.
    # Rule: `ns:bare` folds into `bare` iff:
    # 1. `bare` and `ns:bare` appear together in at least one tier.
    # 2. In every tier where BOTH `ns:bare` and `bare` exist:
    #    - their resolved structural signatures are equal, AND
    #    - if one redirects to the other, the redirecting root has no own children.
    # 3. In any tier where only `ns:bare` exists, its structural signature must match
    #    a `bare` signature seen in another tier (and have no own children if redirecting to `bare`).
    # 4. In any tier where only `bare` exists, its structural signature must match
    #    an `ns:bare` signature seen in another tier (and have no own children if redirecting to `ns:bare`).
    all_root_names: set[str] = set()
    for rmap in per_tier_roots.values():
        all_root_names.update(rmap.keys())

    fold_to_bare: dict[str, str] = {}
    for rname in sorted(all_root_names):
        ns, bare = split_namespace(rname)
        if not ns or bare not in all_root_names:
            continue
        bare_tiers = {t: per_tier_roots[t][bare] for t in active_tiers if bare in per_tier_roots[t]}
        ns_tiers = {t: per_tier_roots[t][rname] for t in active_tiers if rname in per_tier_roots[t]}
        shared_tiers = [t for t in active_tiers if t in bare_tiers and t in ns_tiers]
        if not shared_tiers:
            continue

        conflict = False
        for t in shared_tiers:
            ns_sub = ns_tiers[t]
            bare_sub = bare_tiers[t]
            redir_compatible = (
                ns_sub.redirect_target_path == bare_sub.redirect_target_path
                or (
                    ns_sub.redirect_target_path == bare
                    and bare_sub.redirect_target_path is None
                    and not ns_sub.has_own_children
                )
                or (
                    bare_sub.redirect_target_path == rname
                    and ns_sub.redirect_target_path is None
                    and not bare_sub.has_own_children
                )
            )
            if not redir_compatible:
                conflict = True
                break
            if ns_sub.structural_signature() != bare_sub.structural_signature():
                conflict = True
                break
        if conflict:
            continue

        bare_sigs = {sub.structural_signature() for sub in bare_tiers.values()}
        ns_sigs = {sub.structural_signature() for sub in ns_tiers.values()}

        for t, ns_sub in ns_tiers.items():
            if t in bare_tiers:
                continue
            if ns_sub.redirect_target_path == bare and ns_sub.has_own_children:
                conflict = True
                break
            if ns_sub.structural_signature() not in bare_sigs:
                conflict = True
                break
        if conflict:
            continue

        for t, bare_sub in bare_tiers.items():
            if t in ns_tiers:
                continue
            if bare_sub.redirect_target_path == rname and bare_sub.has_own_children:
                conflict = True
                break
            if bare_sub.structural_signature() not in ns_sigs:
                conflict = True
                break
        if conflict:
            continue

        fold_to_bare[rname] = bare

    # Build canonical root groups per tier.
    canonical_roots: dict[str, dict[str, _ResolvedSubtree]] = {}
    root_aliases: dict[str, list[str]] = {}
    root_tiers: dict[str, set[str]] = {}

    for tier in active_tiers:
        rmap = per_tier_roots[tier]
        for rname, sub in sorted(rmap.items()):
            canon = fold_to_bare.get(rname, rname)
            aliases = root_aliases.setdefault(canon, [])
            if canon not in aliases:
                aliases.append(canon)
            if rname not in aliases:
                aliases.append(rname)
            root_tiers.setdefault(canon, set()).add(tier)
            tier_map = canonical_roots.setdefault(canon, {})
            if tier in tier_map:
                tier_map[tier] = _merge_two_subtrees(tier_map[tier], sub)
            else:
                tier_map[tier] = sub

    # Flatten canonical roots and all descendants into CommandNode objects.
    nodes: dict[str, CommandNode] = {}
    root_children: list[str] = []
    alias_map: dict[str, str] = {}
    truncated = False

    for canon in sorted(canonical_roots.keys()):
        aliases = root_aliases[canon]
        # Order aliases with canonical name first, then sorted remaining aliases.
        ordered_aliases = [canon] + sorted(a for a in aliases if a != canon)
        for a in ordered_aliases:
            alias_map[a] = canon

        tier_subtrees = canonical_roots[canon]
        merged_root = None
        for t in active_tiers:
            if t in tier_subtrees:
                merged_root = (
                    tier_subtrees[t]
                    if merged_root is None
                    else _merge_two_subtrees(merged_root, tier_subtrees[t])
                )
        if merged_root is None:
            continue

        def visit(
            node_id: str,
            merged_sub: _ResolvedSubtree,
            tier_views: dict[str, _ResolvedSubtree],
            steps: tuple[SequenceStep, ...],
            is_root: bool,
        ) -> None:
            nonlocal truncated
            if len(nodes) >= max_nodes:
                truncated = True
                return
            visible_tiers = sort_tiers(tier_views.keys())
            m_tier = min_visible_tier(visible_tiers)
            node_aliases = list(ordered_aliases) if is_root else [
                f"{a} {' '.join( nid_part for nid_part in [node_id.partition(' ')[2]] if nid_part )}".strip()
                for a in ordered_aliases
            ]

            child_ids: list[str] = []
            for tok in sorted(merged_sub.children.keys()):
                child_id = f"{node_id} {tok}"
                child_ids.append(child_id)

            cnode = CommandNode(
                node_id=node_id,
                name=canon if is_root else merged_sub.name,
                kind=merged_sub.kind,
                aliases=node_aliases,
                parser=merged_sub.parser,
                properties=dict(merged_sub.properties),
                suggestion_type=merged_sub.suggestion_type,
                executable=merged_sub.executable,
                tiers=visible_tiers,
                min_tier=m_tier,
                children=child_ids,
                redirect_target=merged_sub.redirect_target_path,
                cycle_cut=merged_sub.cycle_cut,
                denied=False,
                denied_by=None,
                completions=[],
                _steps=steps,
                _root_id=canon,
            )
            nodes[node_id] = cnode

            for tok in sorted(merged_sub.children.keys()):
                if len(nodes) >= max_nodes:
                    truncated = True
                    break
                child_sub = merged_sub.children[tok]
                child_id = f"{node_id} {tok}"
                child_tier_views: dict[str, _ResolvedSubtree] = {}
                for t, tv in tier_views.items():
                    for tv_child in tv.children.values():
                        if (
                            tv_child.name == child_sub.name
                            and tv_child.kind == child_sub.kind
                            and tv_child.parser == child_sub.parser
                            and tv_child.properties == child_sub.properties
                        ):
                            child_tier_views[t] = tv_child
                            break
                visit(
                    child_id,
                    child_sub,
                    child_tier_views,
                    steps + (child_sub.step(),),
                    False,
                )
            cnode.children = [cid for cid in child_ids if cid in nodes]

        visit(canon, merged_root, tier_subtrees, (), True)
        if canon in nodes:
            root_children.append(canon)

    tree = CommandTree(
        tiers=active_tiers,
        nodes=nodes,
        root_children=root_children,
        alias_map=alias_map,
        denylist_entries=list(deny.entries),
        truncated=truncated,
    )
    tree.apply_denylist(deny)
    if out_path:
        tree.write_json(out_path)
    return tree


# --- Platform profiles (ADR-125 section 2.6) ---------------------------------

COMMON_JDK_AND_SERVER_PREFIXES: tuple[str, ...] = (
    "java.",
    "javax.",
    "jdk.",
    "sun.",
    "com.sun.",
    "kotlin.",
    "scala.",
    "io.netty.",
    "com.mojang.",
    "org.slf4j.",
    "org.apache.",
    "com.google.",
    "it.unimi.dsi.",
    "net.minecraft.",
)


@dataclass(frozen=True)
class PlatformProfile:
    name: str
    jar_subdir: str
    prereq_prefixes: tuple[str, ...]
    server_frame_prefixes: tuple[str, ...]
    loaded_check_cmd: str

    def jar_dir(self, server_dir: str) -> str:
        return os.path.join(server_dir, self.jar_subdir)

    def is_prerequisite_jar(self, jar_name: str, extra_prereqs: Iterable[str] = ()) -> bool:
        base = os.path.basename(jar_name).lower()
        if base.endswith(".off"):
            base = base[:-4]
        if base.endswith(".jar"):
            base = base[:-4]
        prefixes = tuple(p.lower() for p in self.prereq_prefixes) + tuple(
            str(p).strip().lower() for p in extra_prereqs if str(p).strip()
        )
        return any(base == p or base.startswith(p) for p in prefixes)

    def is_server_or_jdk_class(self, class_name: str) -> bool:
        norm = normalize_jvm_class_name(class_name)
        if not norm or "." not in norm:
            return True
        return norm.startswith(self.server_frame_prefixes)


PLATFORM_PROFILES: dict[str, PlatformProfile] = {
    "paper": PlatformProfile(
        name="paper",
        jar_subdir="plugins",
        prereq_prefixes=("viaversion", "viabackwards", "viarewind"),
        server_frame_prefixes=COMMON_JDK_AND_SERVER_PREFIXES
        + (
            "org.bukkit.",
            "org.spigotmc.",
            "io.papermc.",
            "com.destroystokyo.",
            "net.md_5.",
            "co.aikar.",
            "ca.spottedleaf.",
        ),
        loaded_check_cmd="plugins",
    ),
    "fabric": PlatformProfile(
        name="fabric",
        jar_subdir="mods",
        prereq_prefixes=("fabric-api", "fabricloader", "fabric-loader"),
        server_frame_prefixes=COMMON_JDK_AND_SERVER_PREFIXES
        + (
            "net.fabricmc.",
            "org.spongepowered.asm.",
        ),
        loaded_check_cmd="fabric mods",
    ),
    "neoforge": PlatformProfile(
        name="neoforge",
        jar_subdir="mods",
        prereq_prefixes=("neoforge",),
        server_frame_prefixes=COMMON_JDK_AND_SERVER_PREFIXES
        + (
            "net.neoforged.",
            "cpw.mods.",
            "org.spongepowered.asm.",
        ),
        loaded_check_cmd="forge mods",
    ),
}


def get_platform_profile(name: str = "paper") -> PlatformProfile:
    key = (name or "paper").strip().lower()
    if key not in PLATFORM_PROFILES:
        raise ValueError(f"unknown platform profile {name!r}; expected one of {sorted(PLATFORM_PROFILES)}")
    return PLATFORM_PROFILES[key]


# --- Class name normalization & JcmdObserver (ADR-125 section 2.6) -----------

HISTO_LINE_RE = lc.HISTO_LINE
GC_FILLER_RE = lc.GC_FILLER
ALLOWED_JCMD_OPS: frozenset[str] = frozenset({"GC.class_histogram", "Thread.print"})
_JVM_ARRAY_DESC_RE = re.compile(r"^\[+L([^;]+);$")
_JVM_PRIMITIVE_ARRAY_RE = re.compile(r"^\[+[ZBCSIJFD]$")


def normalize_jvm_class_name(raw_name: str) -> str:
    """Normalize a JVM histogram or stack-frame class token into a dotted binary class name."""
    s = (raw_name or "").strip()
    if not s:
        return ""
    # Strip module/classloader prefix like `java.base/java.lang.Thread` or `Plugin.jar//pkg.Cls`
    if "//" in s:
        s = s.split("//", 1)[1]
    elif "/" in s and not s.startswith("["):
        left, right = s.split("/", 1)
        if "." in right:
            s = right
    # Strip trailing `(java.base@21.0.2)` or similar module annotation in histograms
    s = s.split()[0]
    m_arr = _JVM_ARRAY_DESC_RE.match(s)
    if m_arr:
        s = m_arr.group(1)
    elif _JVM_PRIMITIVE_ARRAY_RE.match(s):
        return s
    while s.endswith("[]"):
        s = s[:-2]
    s = s.replace("/", ".")
    return s


@dataclass(frozen=True)
class ExclusionWindow:
    start_ms: int
    end_ms: int
    kind: str = "observer"

    def overlaps(self, interval_start_ms: float, interval_end_ms: float) -> bool:
        lo = min(interval_start_ms, interval_end_ms)
        hi = max(interval_start_ms, interval_end_ms)
        return lo <= self.end_ms and hi >= self.start_ms

    def to_dict(self) -> dict[str, Any]:
        return {"start_ms": self.start_ms, "end_ms": self.end_ms, "kind": self.kind}


class JcmdError(RuntimeError):
    """Raised when `jcmd` is missing, unauthorized, unmatched, or returns unreadable output."""


def _default_clock_ms() -> int:
    return int(time.time() * 1000)


def _default_pid_cwd(pid: int) -> str | None:
    proc_cwd = f"/proc/{pid}/cwd"
    try:
        if os.path.exists(proc_cwd):
            return os.path.realpath(proc_cwd)
    except OSError:
        return None
    return None


def _cmd_contains_dir_path(cmd_desc: str, *dir_paths: str) -> bool:
    """Return True only when a directory path (with a separator, never a bare basename) appears as a path prefix in `cmd_desc`."""
    norm_cmd = (cmd_desc or "").replace("\\", "/").lower()
    if not norm_cmd:
        return False
    for dp in dir_paths:
        if not dp:
            continue
        clean = dp.replace("\\", "/").rstrip("/").lower()
        if not clean or "/" not in clean:
            continue
        start = 0
        while True:
            idx = norm_cmd.find(clean, start)
            if idx < 0:
                break
            before_ok = idx == 0 or norm_cmd[idx - 1].isspace() or norm_cmd[idx - 1] in ("=", '"', "'", ":")
            end_idx = idx + len(clean)
            after_ok = end_idx == len(norm_cmd) or norm_cmd[end_idx] == "/" or norm_cmd[end_idx].isspace() or norm_cmd[end_idx] in ('"', "'")
            if before_ok and after_ok:
                return True
            start = idx + 1
    return False


class JcmdObserver:
    """Read-only HotSpot JVM observer using `jcmd` (`GC.class_histogram` and `Thread.print`).

    Resolves the target JVM from `--pid-file`, an explicit `--container` (`docker exec <c> jcmd ...`),
    an explicit `pid`, or `jcmd -l` matched against `jar_pattern` / `server_dir` (checking
    `/proc/<pid>/cwd` and full normalized directory paths, never matching basename-only).
    Fails fast with an actionable hint on missing `jcmd`, wrong user/permissions, unmatched PID,
    or empty histogram output. Records `[start_ms, end_ms]` exclusion windows for every
    histogram and thread dump so stall analysis excludes observer-induced pauses.
    """

    def __init__(
        self,
        pid: int | None = None,
        pid_file: str | None = None,
        container: str | None = None,
        container_user: str | None = None,
        server_dir: str | None = None,
        jar_pattern: str | None = None,
        jcmd_bin: str = "jcmd",
        docker_bin: str = "docker",
        timeout_s: float = 60.0,
        post_grace_ms: int = 250,
        check_local_pid: bool = True,
        runner: Callable[[list[str], float], subprocess.CompletedProcess[str]] | None = None,
        clock_ms: Callable[[], int] | None = None,
        pid_cwd: Callable[[int], str | None] | None = None,
    ):
        self._explicit_pid = pid
        self.pid_file = pid_file
        self.container = container.strip() if isinstance(container, str) and container.strip() else None
        self.container_user = container_user.strip() if isinstance(container_user, str) and container_user.strip() else None
        self.server_dir = server_dir
        self.jar_pattern = jar_pattern
        self.jcmd_bin = jcmd_bin
        self.docker_bin = docker_bin
        self.timeout_s = float(timeout_s)
        self.post_grace_ms = max(0, int(post_grace_ms))
        self.check_local_pid = bool(check_local_pid)
        self._runner = runner or self._default_runner
        self._clock_ms = clock_ms or _default_clock_ms
        self._pid_cwd = pid_cwd or _default_pid_cwd
        self._resolved_pid: int | None = None
        self.exclusion_windows: list[ExclusionWindow] = []

        if self.container is not None and self.container.startswith("-"):
            raise JcmdError(f"invalid container name {self.container!r}; pass a container name or ID to --container")
        if self.container_user is not None and self.container_user.startswith("-"):
            raise JcmdError(f"invalid container user {self.container_user!r}; pass a user name or UID")

    @staticmethod
    def _default_runner(cmd: list[str], timeout_s: float) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout_s,
            check=False,
        )

    def _docker_exec_prefix(self) -> list[str]:
        assert self.container is not None
        prefix = [self.docker_bin, "exec"]
        if self.container_user is not None:
            prefix.extend(["-u", self.container_user])
        prefix.append(self.container)
        return prefix

    def record_window(self, start_ms: int, end_ms: int, kind: str = "external") -> ExclusionWindow:
        w = ExclusionWindow(start_ms=int(min(start_ms, end_ms)), end_ms=int(max(start_ms, end_ms)), kind=str(kind))
        self.exclusion_windows.append(w)
        return w

    def overlaps_exclusion(self, start_ms: float, end_ms: float) -> bool:
        return any(w.overlaps(start_ms, end_ms) for w in self.exclusion_windows)

    def _verify_local_pid(self, pid: int, source_desc: str) -> int:
        if pid <= 0:
            raise JcmdError(f"invalid PID {pid} from {source_desc}; expected a positive integer")
        if self.container is not None or not self.check_local_pid:
            return pid
        try:
            os.kill(pid, 0)
        except ProcessLookupError as exc:
            raise JcmdError(
                f"JVM process PID {pid} ({source_desc}) is not running. "
                "Hint: verify the server is running and update --pid-file or pass --container."
            ) from exc
        except PermissionError as exc:
            raise JcmdError(
                f"permission denied signalling JVM PID {pid} ({source_desc}). "
                "Hint: jcmd must run as the same OS user that owns the server JVM, or via --container."
            ) from exc
        except OSError as exc:
            raise JcmdError(f"cannot access JVM PID {pid} ({source_desc}): {exc}") from exc

        if self.server_dir:
            cand_cwd = self._pid_cwd(pid)
            if cand_cwd is not None:
                real_dir = os.path.normpath(os.path.realpath(self.server_dir)).lower()
                raw_dir = os.path.normpath(self.server_dir)
                real_cwd = os.path.normpath(os.path.realpath(cand_cwd)).lower()
                if real_cwd != real_dir:
                    cmdline_txt = ""
                    try:
                        with open(f"/proc/{pid}/cmdline", "rb") as fh:
                            cmdline_txt = fh.read().replace(b"\x00", b" ").decode("utf-8", errors="replace")
                    except OSError:
                        cmdline_txt = ""
                    if not _cmd_contains_dir_path(cmdline_txt, raw_dir, real_dir):
                        raise JcmdError(
                            f"PID {pid} ({source_desc}) has working directory {cand_cwd!r}, which does not match "
                            f"server_dir={self.server_dir!r}. Hint: --pid-file may be stale."
                        )
        return pid

    @staticmethod
    def _parse_jcmd_list(stdout: str) -> list[tuple[int, str]]:
        candidates: list[tuple[int, str]] = []
        for line in (stdout or "").splitlines():
            s = line.strip()
            if not s:
                continue
            parts = s.split(None, 1)
            if not parts[0].isdigit():
                continue
            cand_pid = int(parts[0])
            cmd_desc = parts[1].strip() if len(parts) > 1 else ""
            if "sun.tools.jcmd.JCmd" in cmd_desc or cmd_desc.startswith("jdk.jcmd"):
                continue
            candidates.append((cand_pid, cmd_desc))
        return candidates

    def _match_jcmd_candidates(
        self,
        candidates: list[tuple[int, str]],
        check_cwd: bool = True,
    ) -> list[tuple[int, str]]:
        if not self.jar_pattern and not self.server_dir:
            return list(candidates)

        jar_pat_low = self.jar_pattern.lower() if self.jar_pattern else None
        raw_dir = os.path.normpath(self.server_dir) if self.server_dir else ""
        real_dir = os.path.normpath(os.path.realpath(self.server_dir)) if self.server_dir else ""

        matched: list[tuple[int, str]] = []
        for cand_pid, cmd_desc in candidates:
            low_desc = cmd_desc.lower()
            if jar_pat_low and jar_pat_low not in low_desc:
                continue
            if self.server_dir:
                cand_cwd = self._pid_cwd(cand_pid) if check_cwd else None
                cwd_ok = (
                    cand_cwd is not None
                    and os.path.normpath(os.path.realpath(cand_cwd)).lower() == real_dir.lower()
                )
                path_ok = _cmd_contains_dir_path(cmd_desc, raw_dir, real_dir)
                if cand_cwd is not None:
                    if not (cwd_ok or path_ok):
                        continue
                else:
                    if not path_ok:
                        continue
            matched.append((cand_pid, cmd_desc))
        return matched

    def resolve_pid(self, refresh: bool = False) -> int:
        if self._resolved_pid is not None and not refresh:
            return self._resolved_pid

        if self._explicit_pid is not None:
            self._resolved_pid = self._verify_local_pid(int(self._explicit_pid), "explicit pid")
            return self._resolved_pid

        if self.pid_file:
            if not os.path.exists(self.pid_file):
                raise JcmdError(
                    f"PID file {self.pid_file!r} does not exist. "
                    "Hint: check --pid-file path or omit --pid-file to match via `jcmd -l`."
                )
            try:
                with open(self.pid_file, "r", encoding="utf-8") as fh:
                    raw = fh.read().strip()
            except OSError as exc:
                raise JcmdError(f"failed to read --pid-file {self.pid_file!r}: {exc}") from exc
            if not raw or not raw.lstrip("-").isdigit():
                raise JcmdError(
                    f"--pid-file {self.pid_file!r} does not contain a valid integer PID (got {raw!r})."
                )
            self._resolved_pid = self._verify_local_pid(int(raw), f"--pid-file {self.pid_file}")
            return self._resolved_pid

        if self.container is not None:
            list_cmd = self._docker_exec_prefix() + [self.jcmd_bin, "-l"]
            try:
                proc = self._runner(list_cmd, self.timeout_s)
            except FileNotFoundError as exc:
                raise JcmdError(
                    f"executable `{list_cmd[0]}` not found while running {' '.join(list_cmd)}. "
                    "Hint: ensure `docker` is installed and on PATH."
                ) from exc
            except subprocess.TimeoutExpired as exc:
                raise JcmdError(f"`{' '.join(list_cmd)}` timed out after {self.timeout_s:g}s.") from exc

            combined = f"{proc.stdout or ''}\n{proc.stderr or ''}".strip()
            if proc.returncode != 0:
                raise JcmdError(
                    f"`{' '.join(list_cmd)}` failed (exit {proc.returncode}): {combined}. "
                    "Hint: verify the container is running, contains a JDK (`jcmd`), and matches the server UID."
                )
            candidates = self._parse_jcmd_list(proc.stdout or "")
            if not candidates:
                self._resolved_pid = 1
                return self._resolved_pid
            matched = self._match_jcmd_candidates(candidates, check_cwd=False)
            if len(matched) == 1:
                self._resolved_pid = matched[0][0]
                return self._resolved_pid
            if not matched and len(candidates) == 1 and not self.jar_pattern:
                self._resolved_pid = candidates[0][0]
                return self._resolved_pid
            cand_summary = ", ".join(f"{p} ({d or '<no main class>'})" for p, d in candidates)
            if not matched:
                raise JcmdError(
                    f"`{' '.join(list_cmd)}` found no JVM matching jar_pattern={self.jar_pattern!r} / "
                    f"server_dir={self.server_dir!r}; container JVMs: [{cand_summary}]."
                )
            match_summary = ", ".join(f"{p} ({d})" for p, d in matched)
            raise JcmdError(
                f"`{' '.join(list_cmd)}` matched multiple container JVMs: [{match_summary}]. "
                "Hint: pass explicit `pid` or `jar_pattern` to disambiguate."
            )

        # Discover via `jcmd -l` on the host.
        try:
            proc = self._runner([self.jcmd_bin, "-l"], self.timeout_s)
        except FileNotFoundError as exc:
            raise JcmdError(
                f"`{self.jcmd_bin}` not found on PATH. "
                "Hint: install a Full JDK (which provides jcmd) or pass --container <name> if the server runs in Docker."
            ) from exc
        except subprocess.TimeoutExpired as exc:
            raise JcmdError(f"`{self.jcmd_bin} -l` timed out after {self.timeout_s:g}s.") from exc

        combined = f"{proc.stdout or ''}\n{proc.stderr or ''}".strip()
        if proc.returncode != 0:
            raise JcmdError(
                f"`{self.jcmd_bin} -l` failed (exit {proc.returncode}): {combined}. "
                "Hint: run as the server JVM user, or pass --pid-file / --container."
            )

        candidates = self._parse_jcmd_list(proc.stdout or "")
        if not candidates:
            raise JcmdError(
                f"`{self.jcmd_bin} -l` listed no target JVMs. "
                "Hint: jcmd only sees JVMs owned by the current OS user; run as the server user, "
                "pass --pid-file <path>, or pass --container <name>."
            )

        matched = self._match_jcmd_candidates(candidates, check_cwd=True)

        if len(matched) == 1:
            self._resolved_pid = self._verify_local_pid(matched[0][0], "jcmd -l")
            return self._resolved_pid

        cand_summary = ", ".join(f"{p} ({d or '<no main class>'})" for p, d in candidates)
        if not matched:
            raise JcmdError(
                f"`{self.jcmd_bin} -l` found no JVM matching server_dir={self.server_dir!r} / "
                f"jar_pattern={self.jar_pattern!r}; visible JVMs: [{cand_summary}]. "
                "Hint: pass --pid-file <path> or --container <name>."
            )
        match_summary = ", ".join(f"{p} ({d})" for p, d in matched)
        raise JcmdError(
            f"`{self.jcmd_bin} -l` matched multiple JVMs: [{match_summary}]. "
            "Hint: disambiguate with --pid-file <path> or --container <name>."
        )

    def _build_command(self, op: str) -> list[str]:
        if op not in ALLOWED_JCMD_OPS:
            raise JcmdError(
                f"disallowed jcmd operation {op!r}; JcmdObserver only permits read-only {sorted(ALLOWED_JCMD_OPS)}"
            )
        target_pid = self.resolve_pid()
        if self.container is not None:
            return self._docker_exec_prefix() + [self.jcmd_bin, str(target_pid), op]
        return [self.jcmd_bin, str(target_pid), op]

    def _run_jcmd_op(self, op: str) -> tuple[str, ExclusionWindow]:
        cmd = self._build_command(op)
        t0 = self._clock_ms()
        proc: subprocess.CompletedProcess[str] | None = None
        try:
            proc = self._runner(cmd, self.timeout_s)
        except FileNotFoundError as exc:
            exe = cmd[0]
            raise JcmdError(
                f"executable `{exe}` not found while running {' '.join(cmd)}. "
                "Hint: ensure a Full JDK (`jcmd`) or `docker` is installed and on PATH."
            ) from exc
        except subprocess.TimeoutExpired as exc:
            raise JcmdError(f"`{' '.join(cmd)}` timed out after {self.timeout_s:g}s.") from exc
        finally:
            t1 = self._clock_ms()
            win = self.record_window(t0, max(t0, t1) + self.post_grace_ms, kind=op)

        out = proc.stdout or ""
        err = proc.stderr or ""
        combined = f"{out}\n{err}".strip()
        low = combined.lower()
        if (
            proc.returncode != 0
            or "operation not permitted" in low
            or "permission denied" in low
            or "unable to open socket file" in low
            or "no such process" in low
            or "attachnotsupportedexception" in low
        ):
            raise JcmdError(
                f"`{' '.join(cmd)}` failed (exit {proc.returncode}): {combined or '<empty output>'}. "
                "Hint: run as the OS user that owns the server JVM, verify the PID is alive, "
                "or pass --pid-file / --container."
            )
        return out, win

    @staticmethod
    def parse_histogram(text: str) -> dict[str, tuple[int, int]]:
        """Parse HotSpot `GC.class_histogram` text into `{raw_class_name: (instances, bytes)}`."""
        histo: dict[str, tuple[int, int]] = {}
        for line in (text or "").splitlines():
            m = HISTO_LINE_RE.match(line)
            if not m:
                continue
            raw_cls = m.group(3).strip()
            if not raw_cls or GC_FILLER_RE.search(raw_cls):
                continue
            inst = int(m.group(1))
            nbytes = int(m.group(2))
            prev_i, prev_b = histo.get(raw_cls, (0, 0))
            histo[raw_cls] = (prev_i + inst, prev_b + nbytes)
        if not histo:
            raise JcmdError(
                "jcmd GC.class_histogram returned no readable class rows. "
                "Hint: an unreadable or empty histogram is never treated as 0; verify JVM permissions and jcmd output."
            )
        return histo

    def class_histogram(self) -> dict[str, tuple[int, int]]:
        """Collect a post-GC live-object histogram (`{class_name: (instances, bytes)}`) and record its pause window."""
        out, _ = self._run_jcmd_op("GC.class_histogram")
        return self.parse_histogram(out)

    def class_counts(self) -> dict[str, int]:
        """Collect post-GC live instance counts (`{class_name: instances}`)."""
        histo = self.class_histogram()
        return {cls: pair[0] for cls, pair in histo.items()}

    def thread_print(self) -> str:
        """Collect a `Thread.print` stack dump and record its observation window."""
        out, _ = self._run_jcmd_op("Thread.print")
        if not out or ('"' not in out and "Thread" not in out and "\tat " not in out and "   at " not in out):
            raise JcmdError(
                "jcmd Thread.print returned no recognizable thread stacks. "
                "Hint: verify the target PID is a HotSpot JVM and jcmd has attach permissions."
            )
        return out


# --- Offline JarIndex (ADR-125 section 2.4 & 2.6) ----------------------------

_BUILTIN_PLATFORM_DEPS: frozenset[str] = frozenset(
    {
        "minecraft",
        "java",
        "fabricloader",
        "fabric-loader",
        "neoforge",
        "forge",
        "paper",
        "spigot",
        "bukkit",
    }
)


def jar_entry_class(entry: str) -> str | None:
    """Convert a zip entry path to a binary class name, or None for non-classes / module metadata."""
    if not entry or not entry.endswith(".class"):
        return None
    clean = entry.lstrip("/")
    if clean.startswith("META-INF/versions/"):
        parts = clean.split("/", 3)
        if len(parts) < 4:
            return None
        clean = parts[3]
    elif clean.startswith("META-INF/"):
        return None
    if clean.endswith(("module-info.class", "package-info.class")):
        return None
    return clean[: -len(".class")].replace("/", ".")


def _strip_yaml_comment(line: str) -> str:
    in_sq = False
    in_dq = False
    for i, ch in enumerate(line):
        if ch == "'" and not in_dq:
            in_sq = not in_sq
        elif ch == '"' and not in_sq:
            in_dq = not in_dq
        elif ch == "#" and not in_sq and not in_dq:
            if i == 0 or line[i - 1].isspace():
                return line[:i]
    return line


def _unquote_scalar(val: str) -> str:
    s = val.strip()
    if len(s) >= 2 and ((s[0] == "'" and s[-1] == "'") or (s[0] == '"' and s[-1] == '"')):
        return s[1:-1].strip()
    return s


def _parse_inline_yaml_list(val: str) -> list[str]:
    s = val.strip()
    if not (s.startswith("[") and s.endswith("]")):
        return []
    inner = s[1:-1].strip()
    if not inner:
        return []
    out: list[str] = []
    for item in inner.split(","):
        cleaned = _unquote_scalar(item)
        if cleaned:
            out.append(cleaned)
    return out


def parse_plugin_yml_metadata(text: str) -> tuple[str | None, list[str], list[str], list[str]]:
    """Parse `(name, hard_depends, soft_depends, provides)` from Bukkit `plugin.yml`."""
    name: str | None = None
    depend: list[str] = []
    softdepend: list[str] = []
    provides: list[str] = []
    active_list: list[str] | None = None
    active_indent = 0

    for raw_line in (text or "").splitlines():
        line = _strip_yaml_comment(raw_line).rstrip()
        if not line.strip():
            continue
        indent = len(line) - len(line.lstrip(" "))
        stripped = line.strip()

        if active_list is not None:
            if indent >= active_indent and stripped.startswith("-"):
                item = _unquote_scalar(stripped[1:])
                if item and item not in active_list:
                    active_list.append(item)
                continue
            if indent <= active_indent and not stripped.startswith("-"):
                active_list = None

        if indent == 0 and ":" in stripped:
            key, _, rest = stripped.partition(":")
            key = key.strip().lower()
            rest = rest.strip()
            if key == "name" and rest:
                name = _unquote_scalar(rest)
            elif key in ("depend", "softdepend", "provides"):
                target = depend if key == "depend" else (softdepend if key == "softdepend" else provides)
                if rest.startswith("["):
                    for item in _parse_inline_yaml_list(rest):
                        if item not in target:
                            target.append(item)
                elif rest:
                    scalar = _unquote_scalar(rest)
                    if scalar and scalar not in target:
                        target.append(scalar)
                else:
                    active_list = target
                    active_indent = indent
    return name, depend, softdepend, provides


def parse_paper_plugin_yml_metadata(text: str) -> tuple[str | None, list[str], list[str], list[str]]:
    """Parse `(name, hard_depends, soft_depends, provides)` from Paper `paper-plugin.yml`."""
    name: str | None = None
    hard_deps: list[str] = []
    soft_deps: list[str] = []
    provides: list[str] = []

    in_dependencies = False
    dep_indent = -1
    section_indent = -1
    in_dep_section = False
    current_dep_name: str | None = None
    current_dep_indent = -1
    current_dep_required: bool = True  # Paper defaults to required=true unless specified otherwise
    in_provides = False
    provides_indent = -1

    def flush_dep() -> None:
        nonlocal current_dep_name, current_dep_required
        if current_dep_name:
            target = hard_deps if current_dep_required else soft_deps
            if current_dep_name not in target:
                target.append(current_dep_name)
        current_dep_name = None
        current_dep_required = True

    for raw_line in (text or "").splitlines():
        line = _strip_yaml_comment(raw_line).rstrip()
        if not line.strip():
            continue
        indent = len(line) - len(line.lstrip(" "))
        stripped = line.strip()

        if in_provides:
            if indent >= provides_indent and stripped.startswith("-"):
                item = _unquote_scalar(stripped[1:])
                if item and item not in provides:
                    provides.append(item)
                continue
            if indent <= provides_indent and not stripped.startswith("-"):
                in_provides = False

        if indent == 0 and ":" in stripped:
            flush_dep()
            in_dependencies = False
            in_dep_section = False
            key, _, rest = stripped.partition(":")
            key_low = key.strip().lower()
            rest_s = rest.strip()
            if key_low == "name" and rest_s:
                name = _unquote_scalar(rest_s)
            elif key_low == "provides":
                if rest_s.startswith("["):
                    for item in _parse_inline_yaml_list(rest_s):
                        if item not in provides:
                            provides.append(item)
                else:
                    in_provides = True
                    provides_indent = indent
            elif key_low == "dependencies":
                in_dependencies = True
                dep_indent = indent
            continue

        if in_dependencies:
            if indent <= dep_indent:
                flush_dep()
                in_dependencies = False
                in_dep_section = False
                continue

            # Inside `dependencies:`: look for `server:` or `bootstrap:`
            if not in_dep_section or indent <= section_indent:
                flush_dep()
                if stripped.endswith(":"):
                    sec_key = stripped[:-1].strip().lower()
                    if sec_key in ("server", "bootstrap"):
                        in_dep_section = True
                        section_indent = indent
                    else:
                        in_dep_section = False
                continue

            if in_dep_section:
                if current_dep_name is not None and indent <= current_dep_indent:
                    flush_dep()

                if current_dep_name is None:
                    if ":" in stripped:
                        dkey, _, drest = stripped.partition(":")
                        dep_id = _unquote_scalar(dkey)
                        drest_s = drest.strip()
                        current_dep_name = dep_id
                        current_dep_indent = indent
                        current_dep_required = True
                        if drest_s.startswith("{") and drest_s.endswith("}"):
                            for pair in drest_s[1:-1].split(","):
                                if ":" in pair:
                                    pk, _, pv = pair.partition(":")
                                    if pk.strip().lower() == "required":
                                        current_dep_required = pv.strip().lower() != "false"
                            flush_dep()
                else:
                    if ":" in stripped:
                        pk, _, pv = stripped.partition(":")
                        if pk.strip().lower() == "required":
                            current_dep_required = pv.strip().lower() != "false"

    flush_dep()
    return name, hard_deps, soft_deps, provides


def parse_fabric_mod_json_metadata(text: str) -> tuple[str | None, list[str], list[str], list[str]]:
    """Parse `(mod_id, hard_depends, soft_depends, provides)` from `fabric.mod.json`."""
    try:
        data = json.loads(text)
    except (ValueError, TypeError):
        return None, [], [], []
    if not isinstance(data, dict):
        return None, [], [], []
    mod_id = str(data.get("id") or "").strip() or None
    provides: list[str] = []
    raw_prov = data.get("provides")
    if isinstance(raw_prov, list):
        for p in raw_prov:
            if isinstance(p, str) and p.strip():
                provides.append(p.strip())
    name = str(data.get("name") or "").strip()
    if name and name != mod_id and name not in provides:
        provides.append(name)

    hard_deps: list[str] = []
    raw_deps = data.get("depends")
    if isinstance(raw_deps, dict):
        for k in raw_deps.keys():
            dep = str(k).strip()
            if dep and dep.lower() not in _BUILTIN_PLATFORM_DEPS and dep not in hard_deps:
                hard_deps.append(dep)
    elif isinstance(raw_deps, list):
        for k in raw_deps:
            dep = str(k).strip()
            if dep and dep.lower() not in _BUILTIN_PLATFORM_DEPS and dep not in hard_deps:
                hard_deps.append(dep)

    soft_deps: list[str] = []
    raw_rec = data.get("recommends")
    if isinstance(raw_rec, dict):
        for k in raw_rec.keys():
            dep = str(k).strip()
            if dep and dep.lower() not in _BUILTIN_PLATFORM_DEPS and dep not in soft_deps:
                soft_deps.append(dep)
    return mod_id, hard_deps, soft_deps, provides


_TOML_SECTION_RE = re.compile(r"^\s*(\[\[?[^\]\r\n]+\]\]?)\s*$")
_TOML_KV_RE = re.compile(r"^\s*([A-Za-z0-9_.-]+)\s*=\s*(.+?)\s*$")


def parse_neoforge_toml_metadata(text: str) -> tuple[str | None, list[str], list[str], list[str]]:
    """Parse `(mod_id, hard_depends, soft_depends, provides)` from `META-INF/neoforge.mods.toml` or `mods.toml`."""
    mod_ids: list[str] = []
    hard_deps: list[str] = []
    soft_deps: list[str] = []

    section = ""
    cur_dep_id: str | None = None
    cur_dep_required: bool = True  # NeoForge `type="required"` is default when omitted

    def flush_dep() -> None:
        nonlocal cur_dep_id, cur_dep_required
        if cur_dep_id and cur_dep_id.lower() not in _BUILTIN_PLATFORM_DEPS:
            if cur_dep_id not in mod_ids:
                target = hard_deps if cur_dep_required else soft_deps
                if cur_dep_id not in target:
                    target.append(cur_dep_id)
        cur_dep_id = None
        cur_dep_required = True

    for raw_line in (text or "").splitlines():
        line = _strip_yaml_comment(raw_line).strip()
        if not line:
            continue
        m_sec = _TOML_SECTION_RE.match(line)
        if m_sec:
            flush_dep()
            section = m_sec.group(1).strip().lower()
            continue
        m_kv = _TOML_KV_RE.match(line)
        if not m_kv:
            continue
        k = m_kv.group(1).strip()
        v = _unquote_scalar(m_kv.group(2))
        if section == "[[mods]]":
            if k == "modId" and v and v not in mod_ids:
                mod_ids.append(v)
        elif section.startswith("[[dependencies"):
            if k == "modId":
                cur_dep_id = v
            elif k == "type":
                cur_dep_required = v.lower() == "required"
            elif k == "mandatory":
                cur_dep_required = v.lower() == "true"

    flush_dep()
    primary_id = mod_ids[0] if mod_ids else None
    provides = mod_ids[1:] if len(mod_ids) > 1 else []
    return primary_id, hard_deps, soft_deps, provides


@dataclass
class JarMetadata:
    jar_name: str
    jar_path: str
    plugin_id: str
    provides: list[str] = field(default_factory=list)
    hard_depends: list[str] = field(default_factory=list)
    soft_depends: list[str] = field(default_factory=list)
    class_count: int = 0


class JarIndex:
    """Offline scanner mapping `.class` entries and hard dependencies across `plugins/` or `mods/`.

    Uses stdlib `zipfile` only. Maps every class in `*.jar` archives to its owning jar(s),
    labels classes absent from all scanned jars as `'server'` (ADR-125 section 2.4), and
    closes jar subsets under declared hard dependencies (`depend`, `paper-plugin.yml`,
    `fabric.mod.json`, `META-INF/neoforge.mods.toml`).
    """

    _MAX_NESTED_JAR_DEPTH = 3

    def __init__(
        self,
        jar_dir: str,
        include_disabled: bool = False,
    ):
        self.jar_dir = jar_dir
        self.include_disabled = include_disabled
        self.jars: dict[str, JarMetadata] = {}
        self.class_to_jars: dict[str, list[str]] = {}
        self.id_to_jar: dict[str, str] = {}
        self._scan()

    def _scan_zip_archive(
        self,
        zf: zipfile.ZipFile,
        outer_jar_name: str,
        cls_seen: set[str],
        hard_deps: list[str],
        soft_deps: list[str],
        provides: list[str],
        depth: int = 0,
    ) -> str | None:
        entries = zf.namelist()
        plugin_id, h_d, s_d, prov, nested_decl = self._read_descriptor(zf, entries, outer_jar_name)
        for d in h_d:
            if d not in hard_deps:
                hard_deps.append(d)
        for d in s_d:
            if d not in soft_deps:
                soft_deps.append(d)
        for p in prov:
            if p not in provides:
                provides.append(p)
        if depth > 0 and plugin_id and plugin_id not in provides:
            provides.append(plugin_id)

        nested_candidates: list[str] = []
        for entry in entries:
            cls_name = jar_entry_class(entry)
            if cls_name is not None:
                if cls_name not in cls_seen:
                    cls_seen.add(cls_name)
                    owners = self.class_to_jars.setdefault(cls_name, [])
                    if outer_jar_name not in owners:
                        owners.append(outer_jar_name)
                continue
            clean_entry = entry.lstrip("/")
            if clean_entry.lower().endswith(".jar") and (
                clean_entry.startswith(("META-INF/jars/", "META-INF/jarjar/"))
                or clean_entry in nested_decl
            ):
                nested_candidates.append(entry)

        if depth < self._MAX_NESTED_JAR_DEPTH:
            for nested_entry in nested_candidates:
                try:
                    nested_bytes = zf.read(nested_entry)
                    with zipfile.ZipFile(io.BytesIO(nested_bytes), "r") as nested_zf:
                        nested_stem = os.path.basename(nested_entry)
                        if nested_stem.lower().endswith(".jar"):
                            nested_stem = nested_stem[:-4]
                        self._scan_zip_archive(
                            nested_zf,
                            outer_jar_name=outer_jar_name,
                            cls_seen=cls_seen,
                            hard_deps=hard_deps,
                            soft_deps=soft_deps,
                            provides=provides,
                            depth=depth + 1,
                        )
                        if nested_stem and nested_stem not in provides:
                            provides.append(nested_stem)
                except (OSError, zipfile.BadZipFile, KeyError):
                    continue
        return plugin_id

    def _scan(self) -> None:
        if not self.jar_dir or not os.path.isdir(self.jar_dir):
            return
        patterns = [os.path.join(self.jar_dir, "*.jar")]
        if self.include_disabled:
            patterns.append(os.path.join(self.jar_dir, "*.jar.off"))
        paths: list[str] = []
        for pat in patterns:
            paths.extend(glob.glob(pat))
        for jar_path in sorted(set(paths)):
            jar_name = os.path.basename(jar_path)
            if jar_name.endswith(".off"):
                jar_name = jar_name[:-4]
            cls_seen: set[str] = set()
            hard_deps: list[str] = []
            soft_deps: list[str] = []
            provides: list[str] = []
            try:
                with zipfile.ZipFile(jar_path, "r") as zf:
                    plugin_id = self._scan_zip_archive(
                        zf,
                        outer_jar_name=jar_name,
                        cls_seen=cls_seen,
                        hard_deps=hard_deps,
                        soft_deps=soft_deps,
                        provides=provides,
                        depth=0,
                    )
            except (OSError, zipfile.BadZipFile):
                continue

            fallback_id = jar_name[:-4] if jar_name.lower().endswith(".jar") else jar_name
            resolved_id = plugin_id or fallback_id
            provided_low = {resolved_id.lower()} | {p.lower() for p in provides}
            hard_deps = [d for d in hard_deps if d.lower() not in provided_low]
            soft_deps = [d for d in soft_deps if d.lower() not in provided_low]

            meta = JarMetadata(
                jar_name=jar_name,
                jar_path=jar_path,
                plugin_id=resolved_id,
                provides=provides,
                hard_depends=hard_deps,
                soft_depends=soft_deps,
                class_count=len(cls_seen),
            )
            self.jars[jar_name] = meta
            self.id_to_jar[resolved_id.lower()] = jar_name
            stem = jar_name[:-4] if jar_name.lower().endswith(".jar") else jar_name
            self.id_to_jar.setdefault(stem.lower(), jar_name)
            for prov in provides:
                self.id_to_jar.setdefault(prov.lower(), jar_name)

        for owners in self.class_to_jars.values():
            owners.sort()

    @staticmethod
    def _read_descriptor(
        zf: zipfile.ZipFile, entries: list[str], jar_name: str
    ) -> tuple[str | None, list[str], list[str], list[str], set[str]]:
        entry_set = set(entries)
        plugin_id: str | None = None
        hard_deps: list[str] = []
        soft_deps: list[str] = []
        provides: list[str] = []
        nested_jars: set[str] = set()

        if "paper-plugin.yml" in entry_set:
            text = zf.read("paper-plugin.yml").decode("utf-8", errors="replace")
            p_id, h_d, s_d, prov = parse_paper_plugin_yml_metadata(text)
            plugin_id = p_id or plugin_id
            hard_deps.extend(d for d in h_d if d not in hard_deps)
            soft_deps.extend(d for d in s_d if d not in soft_deps)
            provides.extend(p for p in prov if p not in provides)

        if "plugin.yml" in entry_set:
            text = zf.read("plugin.yml").decode("utf-8", errors="replace")
            p_id, h_d, s_d, prov = parse_plugin_yml_metadata(text)
            plugin_id = plugin_id or p_id
            hard_deps.extend(d for d in h_d if d not in hard_deps)
            soft_deps.extend(d for d in s_d if d not in soft_deps)
            provides.extend(p for p in prov if p not in provides)

        if "fabric.mod.json" in entry_set:
            text = zf.read("fabric.mod.json").decode("utf-8", errors="replace")
            p_id, h_d, s_d, prov = parse_fabric_mod_json_metadata(text)
            plugin_id = plugin_id or p_id
            hard_deps.extend(d for d in h_d if d not in hard_deps)
            soft_deps.extend(d for d in s_d if d not in soft_deps)
            provides.extend(p for p in prov if p not in provides)
            try:
                f_data = json.loads(text)
                if isinstance(f_data, dict) and isinstance(f_data.get("jars"), list):
                    for j_entry in f_data["jars"]:
                        if isinstance(j_entry, dict) and isinstance(j_entry.get("file"), str):
                            nested_jars.add(j_entry["file"].lstrip("/"))
            except (ValueError, TypeError):
                pass

        for toml_path in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml"):
            if toml_path in entry_set:
                text = zf.read(toml_path).decode("utf-8", errors="replace")
                p_id, h_d, s_d, prov = parse_neoforge_toml_metadata(text)
                plugin_id = plugin_id or p_id
                hard_deps.extend(d for d in h_d if d not in hard_deps)
                soft_deps.extend(d for d in s_d if d not in soft_deps)
                provides.extend(p for p in prov if p not in provides)

        _ = jar_name
        return plugin_id, hard_deps, soft_deps, provides, nested_jars

    def jars_for_class(self, class_name: str) -> list[str]:
        """Return the sorted list of jars containing `class_name` (or its outer host class)."""
        norm = normalize_jvm_class_name(class_name)
        if not norm:
            return []
        candidates = [norm]
        host = norm.split("$$", 1)[0]
        if host and host not in candidates:
            candidates.append(host)
        while "$" in host:
            host = host.rsplit("$", 1)[0]
            if host and host not in candidates:
                candidates.append(host)
        for cand in candidates:
            if cand in self.class_to_jars:
                return list(self.class_to_jars[cand])
        return []

    def owner_of_class(self, class_name: str, profile: PlatformProfile | None = None) -> str:
        """Map `class_name` to its single owning jar filename, `'ambiguous'`, or `'server'`."""
        _ = profile
        owners = self.jars_for_class(class_name)
        if not owners:
            return "server"
        if len(owners) == 1:
            return owners[0]
        return "ambiguous"

    def close_deps(self, jar_subset: Iterable[str]) -> list[str]:
        """Return the sorted transitive closure of `jar_subset` under declared hard dependencies."""
        closure: set[str] = set()
        queue: list[str] = []
        for j in jar_subset:
            j_str = str(j).strip()
            if not j_str:
                continue
            resolved = self.jars.get(j_str)
            canon = resolved.jar_name if resolved else self.id_to_jar.get(j_str.lower(), j_str)
            if canon not in closure:
                closure.add(canon)
                queue.append(canon)

        while queue:
            curr_jar = queue.pop(0)
            meta = self.jars.get(curr_jar)
            if meta is None:
                continue
            for dep_id in meta.hard_depends:
                dep_jar = self.id_to_jar.get(dep_id.strip().lower())
                if dep_jar and dep_jar not in closure:
                    closure.add(dep_jar)
                    queue.append(dep_jar)
        return sorted(closure)


# --- Passive statistical summaries: histograms & tick gaps (ADR-125 2.4) -----

CLS_PREFIX = bf.CLS
NOISE_FLOOR = bf.NOISE_FLOOR
bonferroni_z = bf.bonferroni_z


def _clean_raw_histogram_class(raw_name: str) -> str:
    """Keep raw histogram class token (preserving array descriptors like `[Lpkg.X;`), stripping module suffixes."""
    s = (raw_name or "").strip()
    if not s:
        return ""
    return s.split()[0]


def extract_server_episode_features(record: dict[str, Any]) -> dict[str, float]:
    """Extract passive feature counts (`node:<id>`, `arm:<id>:<follow_up>`, `departures`) from a record."""
    feats: dict[str, float] = {}
    raw_feats = record.get("features")
    if isinstance(raw_feats, dict):
        for k, v in raw_feats.items():
            if isinstance(v, (int, float)) and (
                k == "departures" or k.startswith(("node:", "arm:"))
            ):
                feats[str(k)] = float(v)

    node_id = record.get("node") or record.get("canonical_node")
    follow_up = record.get("follow_up")
    if isinstance(node_id, str) and node_id.strip():
        nk = f"node:{node_id.strip()}"
        feats[nk] = feats.get(nk, 0.0) + 1.0
        if isinstance(follow_up, str) and follow_up.strip():
            ak = f"arm:{node_id.strip()}:{follow_up.strip()}"
            feats[ak] = feats.get(ak, 0.0) + 1.0

    arms = record.get("arms")
    if isinstance(arms, list):
        for arm in arms:
            if isinstance(arm, dict):
                an = str(arm.get("node") or "").strip()
                af = str(arm.get("follow_up") or "").strip()
                count = float(arm.get("count", 1.0) or 1.0)
                if an:
                    feats[f"node:{an}"] = feats.get(f"node:{an}", 0.0) + count
                    if af:
                        feats[f"arm:{an}:{af}"] = feats.get(f"arm:{an}:{af}", 0.0) + count

    deps = record.get("departures")
    if isinstance(deps, (int, float)) and "departures" not in feats:
        feats["departures"] = float(deps)
    return feats


def _extract_record_class_deltas(record: dict[str, Any]) -> dict[str, float]:
    out: dict[str, float] = {}
    deltas = record.get("deltas")
    if isinstance(deltas, dict):
        for k, v in deltas.items():
            if not isinstance(v, (int, float)):
                continue
            if k.startswith(CLS_PREFIX):
                cls_name = _clean_raw_histogram_class(k[len(CLS_PREFIX) :])
                if cls_name and not GC_FILLER_RE.search(cls_name):
                    out[cls_name] = float(v)
    cls_deltas = record.get("class_deltas")
    if isinstance(cls_deltas, dict):
        for k, v in cls_deltas.items():
            if isinstance(v, (int, float)):
                cls_name = _clean_raw_histogram_class(str(k))
                if cls_name and not GC_FILLER_RE.search(cls_name):
                    out[cls_name] = float(v)
    return out


def _usable_server_record(record: dict[str, Any]) -> bool:
    if not isinstance(record, dict):
        return False
    if record.get("excluded") or record.get("no_histogram"):
        return False
    return bool(_extract_record_class_deltas(record)) or isinstance(record.get("deltas"), dict) or isinstance(
        record.get("class_deltas"), dict
    )


def _fit_family_ridge(
    row_feats: list[dict[str, float]],
    row_deltas: list[dict[str, float]],
    feat_list: list[str],
    classes: set[str],
    lam: float = 1.0,
) -> dict[tuple[str, str], tuple[float, float, int]]:
    """Fit a feature family (`arm:*`, `node:*`, or `departures`) jointly with an unpenalised intercept via `bf.ridge_fit`."""
    n_total = len(row_feats)
    slopes: dict[tuple[str, str], tuple[float, float, int]] = {}
    if not feat_list or n_total < 2:
        return slopes

    active_counts = {f: sum(1 for rf in row_feats if rf.get(f, 0.0) > 0) for f in feat_list}
    valid_feats = [f for f in feat_list if active_counts[f] >= 2]
    if not valid_feats:
        return slopes

    sxx_map: dict[str, float] = {}
    for f in valid_feats:
        xs = [rf.get(f, 0.0) for rf in row_feats]
        mx = sum(xs) / n_total
        sxx_map[f] = sum((x - mx) ** 2 for x in xs)

    varied_feats = [f for f in valid_feats if sxx_map[f] > 1e-12]
    if not varied_feats:
        return slopes

    se_floors = {f: max(1.0, NOISE_FLOOR) / math.sqrt(max(1e-6, sxx_map[f])) for f in varied_feats}
    x1 = [[1.0] + [rf.get(f, 0.0) for f in varied_feats] for rf in row_feats]

    for cls in classes:
        ys = [d.get(cls, 0.0) for d in row_deltas]
        # A real leak accumulates positive net instances over the run; mean-reverting series (sum <= 0) are not leaks.
        if sum(ys) <= 0.0:
            continue
        try:
            b1, se1 = bf.ridge_fit(x1, ys, lam=lam)
        except ValueError:
            continue

        cur_b = {f: b1[i + 1] for i, f in enumerate(varied_feats)}
        cur_se = {f: max(se1[i + 1], se_floors[f]) for i, f in enumerate(varied_feats)}

        # When only a proper subset of features in the family has clearly positive growth above noise,
        # refit jointly over that subset so non-leaking arms anchor the unpenalised intercept without
        # dummy-variable shrinkage (`sum_j x_j == 1`) or noise-pooling bias.
        pos_feats = [f for f in varied_feats if cur_b[f] > 2.0 * cur_se[f]]
        while 0 < len(pos_feats) < len(varied_feats):
            x2 = [[1.0] + [rf.get(f, 0.0) for f in pos_feats] for rf in row_feats]
            try:
                b2, se2 = bf.ridge_fit(x2, ys, lam=1e-6)
            except ValueError:
                break
            next_pos: list[str] = []
            for i, f in enumerate(pos_feats):
                f_se = max(se2[i + 1], se_floors[f])
                cur_b[f] = b2[i + 1]
                cur_se[f] = f_se
                if b2[i + 1] > 2.0 * f_se:
                    next_pos.append(f)
            if len(next_pos) == len(pos_feats):
                break
            pos_feats = next_pos

        for f in varied_feats:
            slopes[(cls, f)] = (cur_b[f], cur_se[f], active_counts[f])

    return slopes


def _fit_feature_slopes(
    records: list[dict[str, Any]],
) -> tuple[dict[tuple[str, str], tuple[float, float, int]], dict[str, tuple[float, float, int]], set[str], set[str]]:
    """Compute per `(class, feature)` slope `(b, se, n_active)` and per-class episode `(mean, sd, n)`."""
    usable = [r for r in records if _usable_server_record(r)]
    n_total = len(usable)
    if n_total < 2:
        return {}, {}, set(), set()

    row_feats: list[dict[str, float]] = [extract_server_episode_features(r) for r in usable]
    row_deltas: list[dict[str, float]] = [_extract_record_class_deltas(r) for r in usable]

    classes: set[str] = set()
    features: set[str] = set()
    for d in row_deltas:
        classes.update(d.keys())
    for f in row_feats:
        features.update(k for k, v in f.items() if v > 0)

    class_baseline: dict[str, tuple[float, float, int]] = {}
    for cls in classes:
        ys = [d.get(cls, 0.0) for d in row_deltas]
        mean = sum(ys) / n_total
        var = sum((y - mean) ** 2 for y in ys) / max(1, n_total - 1)
        sd = max(NOISE_FLOOR, math.sqrt(max(0.0, var)))
        class_baseline[cls] = (mean, sd, n_total)

    arm_feats = sorted(f for f in features if f.startswith("arm:"))
    node_feats = sorted(f for f in features if f.startswith("node:"))
    dep_feats = ["departures"] if "departures" in features else []

    slopes: dict[tuple[str, str], tuple[float, float, int]] = {}
    for fam in (arm_feats, node_feats, dep_feats):
        slopes.update(_fit_family_ridge(row_feats, row_deltas, fam, classes, lam=1.0))

    return slopes, class_baseline, classes, features


def screen_server_leaks(
    records: list[dict[str, Any]],
    baseline_records: list[dict[str, Any]] | None = None,
    jar_index: JarIndex | None = None,
    profile: PlatformProfile | None = None,
    alpha: float = 0.05,
    min_n: int = 4,
    min_feature_episodes: int = 2,
    max_results: int = 64,
) -> dict[str, Any]:
    """Passively screen post-GC class growth against `node`, `arm` (`node x follow_up`), and `departures`.

    Applies Bonferroni correction over `n_classes * n_features` comparisons and subtracts
    the vanilla baseline slope (or baseline per-episode drift when the feature was absent
    in the vanilla baseline). Results are reported as passive statistical correlations
    (`attribution_status: 'unconfirmed_passive_correlation'`) with `first_suspect` from `JarIndex`.
    """
    usable = [r for r in records if _usable_server_record(r)]
    if len(usable) < min_n:
        return {
            "usable_episodes": len(usable),
            "classes_tested": 0,
            "features_tested": 0,
            "total_tests": 0,
            "bonferroni_z": 0.0,
            "correlations": [],
        }

    slopes, cls_stats, classes, features = _fit_feature_slopes(usable)
    base_slopes, base_cls_stats, _, _ = _fit_feature_slopes(baseline_records or [])

    n_classes = len(classes)
    n_features = len(features)
    total_tests = n_classes * n_features
    if total_tests == 0:
        return {
            "usable_episodes": len(usable),
            "classes_tested": n_classes,
            "features_tested": n_features,
            "total_tests": 0,
            "bonferroni_z": 0.0,
            "correlations": [],
        }

    z_crit = bonferroni_z(total_tests, alpha=alpha)
    correlations: list[dict[str, Any]] = []

    for (cls, feat), (b, se, active_n) in slopes.items():
        if active_n < min_feature_episodes or b <= 0:
            continue
        if (cls, feat) in base_slopes:
            cb_raw, cse_raw, _ = base_slopes[(cls, feat)]
            cb = max(0.0, cb_raw)
            cse = cse_raw
            if cls in base_cls_stats and base_cls_stats[cls][0] > cb:
                c_mean, c_sd, c_n = base_cls_stats[cls]
                cb = max(cb, c_mean)
                cse = max(cse, c_sd / math.sqrt(max(1, c_n)))
            in_baseline = True
        elif cls in base_cls_stats:
            c_mean, c_sd, c_n = base_cls_stats[cls]
            cb = max(0.0, c_mean)
            cse = c_sd / math.sqrt(max(1, c_n))
            in_baseline = True
        else:
            cb, cse = 0.0, 0.0
            in_baseline = False

        excess = b - cb
        if excess <= 0:
            continue
        combined_se = math.sqrt(se * se + cse * cse)
        z = excess / combined_se if combined_se > 0 else 0.0
        if z <= z_crit:
            continue

        suspect = jar_index.owner_of_class(cls, profile=profile) if jar_index is not None else "server"
        candidate_jars = jar_index.jars_for_class(cls) if jar_index is not None else []
        correlations.append(
            {
                "cls": cls,
                "feature": feat,
                "slope": round(b, 4),
                "baseline_slope": round(cb, 4),
                "excess_slope": round(excess, 4),
                "se": round(combined_se, 4),
                "z": round(z, 4),
                "bonferroni_z": round(z_crit, 4),
                "feature_episodes": active_n,
                "usable_episodes": len(usable),
                "in_baseline": in_baseline,
                "first_suspect": suspect,
                "candidate_jars": candidate_jars,
                "attribution_status": "unconfirmed_passive_correlation",
            }
        )

    # Sort by highest z-score first; prefer specific `arm:` features over aggregate `node:`/`departures` on ties
    def _sort_key(item: dict[str, Any]) -> tuple[float, int, str, str]:
        f_str = str(item["feature"])
        specificity = 0 if f_str.startswith("arm:") else (1 if f_str.startswith("node:") else 2)
        return (-float(item["z"]), specificity, str(item["cls"]), f_str)

    correlations.sort(key=_sort_key)
    return {
        "usable_episodes": len(usable),
        "classes_tested": n_classes,
        "features_tested": n_features,
        "total_tests": total_tests,
        "bonferroni_z": round(z_crit, 4),
        "correlations": correlations[: max(0, max_results)],
    }


def summarize_histogram_snapshots(
    snapshots: list[dict[str, Any]],
    jar_index: JarIndex | None = None,
    profile: PlatformProfile | None = None,
    top_n: int = 20,
) -> dict[str, Any]:
    """Passively summarize operator-supplied `(epoch_ms, histogram)` snapshots."""
    valid = [
        s
        for s in (snapshots or [])
        if isinstance(s, dict) and isinstance(s.get("histogram"), dict) and s.get("histogram")
    ]
    if len(valid) < 2:
        return {"snapshot_count": len(valid), "span_ms": 0, "top_growing_classes": []}

    ordered = sorted(valid, key=lambda s: int(s.get("epoch_ms", 0) or 0))
    first_h: dict[str, tuple[int, int]] = ordered[0]["histogram"]
    last_h: dict[str, tuple[int, int]] = ordered[-1]["histogram"]
    span_ms = max(0, int(ordered[-1].get("epoch_ms", 0) or 0) - int(ordered[0].get("epoch_ms", 0) or 0))
    span_h = span_ms / 3.6e6 if span_ms > 0 else 0.0

    growth_rows: list[dict[str, Any]] = []
    all_classes = set(first_h.keys()) | set(last_h.keys())
    for raw_cls in all_classes:
        cls_name = _clean_raw_histogram_class(raw_cls)
        if not cls_name or GC_FILLER_RE.search(cls_name):
            continue
        i0, b0 = first_h.get(raw_cls, (0, 0))
        i1, b1 = last_h.get(raw_cls, (0, 0))
        di = int(i1) - int(i0)
        db = int(b1) - int(b0)
        if di <= 0 and db <= 0:
            continue
        suspect = jar_index.owner_of_class(cls_name, profile=profile) if jar_index is not None else "server"
        growth_rows.append(
            {
                "cls": cls_name,
                "delta_instances": di,
                "delta_bytes": db,
                "instances_per_hour": round(di / span_h, 2) if span_h > 0 else None,
                "bytes_per_hour": round(db / span_h, 2) if span_h > 0 else None,
                "first_suspect": suspect,
                "attribution_status": "unconfirmed_passive_correlation",
            }
        )

    growth_rows.sort(key=lambda r: (-int(r["delta_bytes"]), -int(r["delta_instances"]), str(r["cls"])))
    return {
        "snapshot_count": len(ordered),
        "span_ms": span_ms,
        "top_growing_classes": growth_rows[: max(0, top_n)],
    }


# --- Tick-gap filtering, Thread.print parsing, and StallOracle ---------------

_THREAD_HEADER_RE = re.compile(r'^\s*"([^"]+)"')
_STACK_FRAME_RE = re.compile(r"^\s*at\s+(?:[^\s/(]+/+)*([^\s/(]+)\(")
_NON_MAIN_THREAD_TOKENS: tuple[str, ...] = (
    "netty",
    "epoll",
    "nio",
    "io ",
    "io-",
    "worker",
    "async",
    "watchdog",
    "rcon",
    "query",
    "timer",
    "scheduler",
    "pool",
    "cleaner",
    "destroyjavavm",
)


def _coerce_exclusion_windows(
    exclusion_windows: Iterable[ExclusionWindow | dict[str, Any] | tuple[int, int]] | None,
) -> list[ExclusionWindow]:
    windows: list[ExclusionWindow] = []
    for w in exclusion_windows or ():
        if isinstance(w, ExclusionWindow):
            windows.append(w)
        elif isinstance(w, dict):
            s = int(w.get("start_ms", 0) or 0)
            e = int(w.get("end_ms", s) or s)
            windows.append(ExclusionWindow(start_ms=min(s, e), end_ms=max(s, e), kind=str(w.get("kind") or "observer")))
        elif isinstance(w, (tuple, list)) and len(w) >= 2:
            s, e = int(w[0]), int(w[1])
            windows.append(
                ExclusionWindow(
                    start_ms=min(s, e),
                    end_ms=max(s, e),
                    kind=str(w[2]) if len(w) > 2 else "observer",
                )
            )
    return windows


def filter_tick_gaps(
    tick_gaps: Iterable[dict[str, Any] | float | int],
    exclusion_windows: Iterable[ExclusionWindow | dict[str, Any] | tuple[int, int]] | None = None,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Split `tick_gaps` into `(kept_gaps, excluded_gaps)` after removing intervals overlapping `exclusion_windows`.

    Each tick_gap entry may be a numeric gap in ms or a dict `{ms, at|end_ms|ts_ms|t_ms, start_ms, keepalive_ms, ...}`.
    `bench-swarm.js` emits `at` as the epoch-ms arrival timestamp (`Date.now()`), matching `_default_clock_ms`.
    When `at` (or `end_ms`/`ts_ms`/`t_ms`) is present without `start_ms`, the gap interval is `[end_ms - ms, end_ms]`.
    When `exclusion_windows` are present and a gap has no timestamp bounds, it fails closed into `excluded_gaps`.
    """
    windows = _coerce_exclusion_windows(exclusion_windows)
    kept: list[dict[str, Any]] = []
    excluded: list[dict[str, Any]] = []

    for item in tick_gaps or ():
        if isinstance(item, (int, float)):
            entry: dict[str, Any] = {"ms": float(item)}
        elif isinstance(item, dict):
            if "ms" not in item:
                continue
            entry = dict(item)
            entry["ms"] = float(item["ms"])
        else:
            continue

        gap_ms = entry["ms"]
        if gap_ms < 0:
            continue

        end_ms = entry.get("at")
        if end_ms is None:
            end_ms = entry.get("end_ms")
        if end_ms is None:
            end_ms = entry.get("ts_ms")
        if end_ms is None:
            end_ms = entry.get("t_ms")
        start_ms = entry.get("start_ms")

        if end_ms is not None and start_ms is None:
            end_f: float | None = float(end_ms)
            start_f: float | None = end_f - gap_ms
        elif start_ms is not None and end_ms is None:
            start_f = float(start_ms)
            end_f = start_f + gap_ms
        elif start_ms is not None and end_ms is not None:
            start_f = float(start_ms)
            end_f = float(end_ms)
        else:
            start_f = None
            end_f = None

        if windows:
            if start_f is None or end_f is None:
                entry["excluded_reason"] = "unverifiable_timestamp"
                excluded.append(entry)
                continue
            if any(w.overlaps(start_f, end_f) for w in windows):
                entry["excluded_reason"] = "observer_window"
                excluded.append(entry)
                continue
        kept.append(entry)

    return kept, excluded


def _percentile(sorted_vals: list[float], q: float) -> float:
    if not sorted_vals:
        return 0.0
    if len(sorted_vals) == 1:
        return sorted_vals[0]
    pos = max(0.0, min(1.0, q)) * (len(sorted_vals) - 1)
    lo = int(math.floor(pos))
    hi = int(math.ceil(pos))
    if lo == hi:
        return sorted_vals[lo]
    frac = pos - lo
    return sorted_vals[lo] * (1.0 - frac) + sorted_vals[hi] * frac


def summarize_tick_gaps(
    tick_gaps: Iterable[dict[str, Any] | float | int],
    exclusion_windows: Iterable[ExclusionWindow | dict[str, Any] | tuple[int, int]] | None = None,
) -> dict[str, Any]:
    """Compute passive summary statistics over `tick_gaps` after excluding `exclusion_windows`."""
    kept, excluded = filter_tick_gaps(tick_gaps, exclusion_windows)
    unverifiable_count = sum(1 for g in excluded if g.get("excluded_reason") == "unverifiable_timestamp")
    vals = sorted(float(g["ms"]) for g in kept)
    ka_vals = [
        float(g["keepalive_ms"])
        for g in kept
        if isinstance(g.get("keepalive_ms"), (int, float)) and float(g["keepalive_ms"]) >= 0
    ]
    n = len(vals)
    if n == 0:
        return {
            "count": 0,
            "excluded_count": len(excluded),
            "unverifiable_count": unverifiable_count,
            "mean_ms": 0.0,
            "sd_ms": 0.0,
            "p50_ms": 0.0,
            "p95_ms": 0.0,
            "p99_ms": 0.0,
            "max_ms": 0.0,
            "max_keepalive_ms": max(ka_vals) if ka_vals else 0.0,
        }
    mean = sum(vals) / n
    var = sum((v - mean) ** 2 for v in vals) / max(1, n - 1) if n > 1 else 0.0
    sd = math.sqrt(max(0.0, var))
    return {
        "count": n,
        "excluded_count": len(excluded),
        "unverifiable_count": unverifiable_count,
        "mean_ms": round(mean, 3),
        "sd_ms": round(sd, 3),
        "p50_ms": round(_percentile(vals, 0.50), 3),
        "p95_ms": round(_percentile(vals, 0.95), 3),
        "p99_ms": round(_percentile(vals, 0.99), 3),
        "max_ms": round(vals[-1], 3),
        "max_keepalive_ms": round(max(ka_vals), 3) if ka_vals else 0.0,
    }


def parse_main_thread_frame(
    thread_dump_text: str,
    profile: PlatformProfile | None = None,
    jar_index: JarIndex | None = None,
) -> dict[str, Any]:
    """Extract the main server thread's stack and top non-JDK/non-server frame from `jcmd Thread.print`."""
    prof = profile or PLATFORM_PROFILES["paper"]
    threads: list[tuple[str, list[str]]] = []
    cur_name: str | None = None
    cur_frames: list[str] = []

    for line in (thread_dump_text or "").splitlines():
        m_hdr = _THREAD_HEADER_RE.match(line)
        if m_hdr:
            if cur_name is not None:
                threads.append((cur_name, cur_frames))
            cur_name = m_hdr.group(1).strip()
            cur_frames = []
            continue
        if cur_name is not None:
            m_fr = _STACK_FRAME_RE.match(line)
            if m_fr:
                cur_frames.append(m_fr.group(1).strip())
    if cur_name is not None:
        threads.append((cur_name, cur_frames))

    chosen_name: str | None = None
    chosen_frames: list[str] = []
    # Priority 1: exact "Server thread"
    for t_name, t_frames in threads:
        if t_name.lower() == "server thread" and t_frames:
            chosen_name = t_name
            chosen_frames = t_frames
            break
    # Priority 2: exact "main" with stack frames (not DestroyJavaVM)
    if chosen_name is None:
        for t_name, t_frames in threads:
            if t_name.lower() == "main" and t_frames:
                chosen_name = t_name
                chosen_frames = t_frames
                break
    # Priority 3: thread name containing "server thread"
    if chosen_name is None:
        for t_name, t_frames in threads:
            low_t = t_name.lower()
            if "server thread" in low_t and t_frames and not any(tok in low_t for tok in _NON_MAIN_THREAD_TOKENS):
                chosen_name = t_name
                chosen_frames = t_frames
                break
    # Priority 4: non-IO/non-worker server thread
    if chosen_name is None:
        for t_name, t_frames in threads:
            low_t = t_name.lower()
            if "server" in low_t and t_frames and not any(tok in low_t for tok in _NON_MAIN_THREAD_TOKENS):
                chosen_name = t_name
                chosen_frames = t_frames
                break

    if not chosen_frames:
        return {
            "thread_name": chosen_name,
            "top_frame": None,
            "culprit_frame": None,
            "culprit_class": None,
            "first_suspect": "server",
            "frames": [],
        }

    culprit_frame: str | None = None
    culprit_class: str | None = None
    for frame in chosen_frames:
        cls_name = frame.rsplit(".", 1)[0] if "." in frame else frame
        cls_norm = normalize_jvm_class_name(cls_name)
        if not prof.is_server_or_jdk_class(cls_norm):
            culprit_frame = frame
            culprit_class = cls_norm
            break

    if culprit_class and jar_index is not None:
        suspect = jar_index.owner_of_class(culprit_class, profile=prof)
    else:
        suspect = "server"

    return {
        "thread_name": chosen_name,
        "top_frame": chosen_frames[0],
        "culprit_frame": culprit_frame,
        "culprit_class": culprit_class,
        "first_suspect": suspect,
        "frames": chosen_frames,
    }


class StallOracle:
    """Scores acute `update_time` spikes and sustained episode-wide tick inflation (ADR-125 section 2.4).

    - Excludes any `tick_gap` overlapping a recorded observer GC histogram, thread dump, or restart window.
    - Acute spike: single non-excluded gap `> baseline_p99 + stall_ms` (default 2000 ms).
    - Sustained tick inflation: episode-wide `mean` or `p95` excess above the baseline distribution
      during `repeat` or `concurrent` follow-ups that degrades TPS without crossing the single-gap threshold.
    - Maps the recurring main-thread top non-JDK/non-server frame from `Thread.print` dumps to a
      first-suspect jar via `JarIndex` (defaulting to `'server'`).
    """

    SUSTAINED_FOLLOW_UPS: frozenset[str] = frozenset({"repeat", "concurrent"})

    def __init__(
        self,
        baseline_gaps: Iterable[dict[str, Any] | float | int] = (),
        stall_ms: float = 2000.0,
        sustained_mean_excess_ms: float = 35.0,
        sustained_p95_excess_ms: float = 60.0,
        min_sustained_samples: int = 5,
        profile: PlatformProfile | None = None,
        jar_index: JarIndex | None = None,
        observer: JcmdObserver | None = None,
        baseline_exclusion_windows: Iterable[ExclusionWindow | dict[str, Any] | tuple[int, int]] | None = None,
    ):
        self.profile = profile or PLATFORM_PROFILES["paper"]
        self.jar_index = jar_index
        self.observer = observer
        self.stall_ms = float(stall_ms)
        self.sustained_mean_excess_ms = float(sustained_mean_excess_ms)
        self.sustained_p95_excess_ms = float(sustained_p95_excess_ms)
        self.min_sustained_samples = max(2, int(min_sustained_samples))
        self.baseline_summary = summarize_tick_gaps(baseline_gaps, baseline_exclusion_windows)
        if self.baseline_summary["count"] == 0:
            # Nominal 20-TPS `update_time` baseline (1000 ms per 20 ticks) when no baseline samples are supplied
            self.baseline_p99_ms = 1050.0
            self.baseline_p95_ms = 1030.0
            self.baseline_mean_ms = 1000.0
            self.baseline_sd_ms = 15.0
        else:
            self.baseline_p99_ms = float(self.baseline_summary["p99_ms"])
            self.baseline_p95_ms = float(self.baseline_summary["p95_ms"])
            self.baseline_mean_ms = float(self.baseline_summary["mean_ms"])
            self.baseline_sd_ms = max(5.0, float(self.baseline_summary["sd_ms"]))

    def _collect_exclusion_windows(
        self,
        extra_windows: Iterable[ExclusionWindow | dict[str, Any] | tuple[int, int]] | None = None,
    ) -> list[ExclusionWindow]:
        windows: list[ExclusionWindow] = []
        if self.observer is not None:
            windows.extend(self.observer.exclusion_windows)
        if extra_windows is not None:
            windows.extend(_coerce_exclusion_windows(extra_windows))
        return windows

    def _attribute_thread_dumps(self, thread_dumps: Iterable[str]) -> dict[str, Any]:
        frame_counts: dict[str, int] = {}
        frame_info: dict[str, dict[str, Any]] = {}
        fallback_top: str | None = None

        for dump_text in thread_dumps or ():
            if not dump_text:
                continue
            parsed = parse_main_thread_frame(dump_text, profile=self.profile, jar_index=self.jar_index)
            if fallback_top is None and parsed.get("top_frame"):
                fallback_top = parsed["top_frame"]
            c_frame = parsed.get("culprit_frame")
            if c_frame:
                frame_counts[c_frame] = frame_counts.get(c_frame, 0) + 1
                frame_info[c_frame] = parsed

        if not frame_counts:
            return {
                "culprit_frame": None,
                "culprit_class": None,
                "top_frame": fallback_top,
                "first_suspect": "server",
                "dump_hits": 0,
            }

        best_frame = sorted(frame_counts.keys(), key=lambda f: (-frame_counts[f], f))[0]
        info = frame_info[best_frame]
        return {
            "culprit_frame": best_frame,
            "culprit_class": info.get("culprit_class"),
            "top_frame": info.get("top_frame"),
            "first_suspect": info.get("first_suspect") or "server",
            "dump_hits": frame_counts[best_frame],
        }

    def evaluate_episode(
        self,
        tick_gaps: Iterable[dict[str, Any] | float | int],
        follow_up: str | None = None,
        thread_dumps: Iterable[str] = (),
        exclusion_windows: Iterable[ExclusionWindow | dict[str, Any] | tuple[int, int]] | None = None,
        capture_dump_on_stall: bool = False,
    ) -> dict[str, Any]:
        """Evaluate an episode's `tick_gaps` (outside exclusion windows) for acute or sustained stalls."""
        all_windows = self._collect_exclusion_windows(exclusion_windows)
        summary = summarize_tick_gaps(tick_gaps, all_windows)

        acute_threshold_ms = self.baseline_p99_ms + self.stall_ms
        max_ms = float(summary["max_ms"])
        acute_excess_ms = max_ms - self.baseline_p99_ms
        is_acute = summary["count"] > 0 and max_ms > acute_threshold_ms

        follow_up_norm = (follow_up or "").strip().lower()
        sustained_eligible = follow_up_norm in self.SUSTAINED_FOLLOW_UPS
        mean_excess_ms = float(summary["mean_ms"]) - self.baseline_mean_ms
        p95_excess_ms = float(summary["p95_ms"]) - self.baseline_p95_ms
        se_mean = self.baseline_sd_ms / math.sqrt(max(1, summary["count"]))
        sustained_z = mean_excess_ms / se_mean if se_mean > 0 and summary["count"] > 0 else 0.0
        is_sustained = (
            sustained_eligible
            and summary["count"] >= self.min_sustained_samples
            and (
                mean_excess_ms >= self.sustained_mean_excess_ms
                or p95_excess_ms >= self.sustained_p95_excess_ms
            )
            and sustained_z >= 3.0
        )

        dumps = list(thread_dumps or [])
        if (is_acute or is_sustained) and not dumps and capture_dump_on_stall and self.observer is not None:
            dumps.append(self.observer.thread_print())

        attr = self._attribute_thread_dumps(dumps)
        stalled = is_acute or is_sustained
        kind: str | None = None
        if is_acute and is_sustained:
            kind = "acute_and_sustained"
        elif is_acute:
            kind = "acute"
        elif is_sustained:
            kind = "sustained"

        signature_target = attr["culprit_frame"] or "server"
        signature = f"stall:{kind}:{signature_target}" if stalled else None

        return {
            "stalled": stalled,
            "kind": kind,
            "acute": is_acute,
            "sustained": is_sustained,
            "acute_threshold_ms": round(acute_threshold_ms, 3),
            "acute_excess_ms": round(acute_excess_ms, 3) if summary["count"] > 0 else 0.0,
            "mean_excess_ms": round(mean_excess_ms, 3) if summary["count"] > 0 else 0.0,
            "p95_excess_ms": round(p95_excess_ms, 3) if summary["count"] > 0 else 0.0,
            "sustained_z": round(sustained_z, 3),
            "summary": summary,
            "baseline_p99_ms": round(self.baseline_p99_ms, 3),
            "baseline_mean_ms": round(self.baseline_mean_ms, 3),
            "baseline_p95_ms": round(self.baseline_p95_ms, 3),
            "culprit_frame": attr["culprit_frame"],
            "culprit_class": attr["culprit_class"],
            "first_suspect": attr["first_suspect"],
            "signature": signature,
            "attribution_status": "unconfirmed",
        }
