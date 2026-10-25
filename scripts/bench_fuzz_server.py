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
import functools
import glob
import io
import ipaddress
import json
import math
import os
import random
import re
import statistics
from statistics import NormalDist
import subprocess
import sys
import threading
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
        if self.truncated:
            bf.log(f"WARNING: command tree truncated at {len(self.nodes)} nodes when writing {path}")
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
    root_visits: list[int] = [0]
    max_visits_per_root = 256

    def build_node(idx: int, path_indices: frozenset[int], depth: int) -> tuple[_ResolvedSubtree | None, bool, int]:
        if idx not in by_idx or depth > max_depth or root_visits[0] >= max_visits_per_root:
            return None, True, 0
        cached = acyclic_memo.get(idx)
        if cached is not None and depth + cached[1] <= max_depth:
            return cached[0], False, cached[1]
        root_visits[0] += 1

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
        root_visits[0] = 0
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
    truncated = any(bool((tier_packets.get(t) or {}).get("truncated")) for t in active_tiers)

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


# --- Brigadier schema value sampling & tier-stratified NodeBandit (ADR-125 2.3) ---

SCENARIO_SHAPES: tuple[str, ...] = (
    "repeat",
    "homesteader_reroll",
    "quit_lt1s",
    "concurrent",
    "explorer_wander",
    "coop_group",
    "quit_delay",
    "rejoin",
    "window_click",
)
PLAYER_TAXONOMY_SHAPES: tuple[str, ...] = (
    "homesteader_reroll",
    "explorer_wander",
    "coop_group",
)
FOLLOW_UP_SHAPES: tuple[str, ...] = SCENARIO_SHAPES

_INT32_MIN = -2147483648
_INT32_MAX = 2147483647
_BASE_LADDER_POINTS: tuple[int, ...] = (-1, 0, 1, 16, 64, 256, 1024)


def numeric_ladder(
    lo: int | float | None = None,
    hi: int | float | None = None,
    *,
    is_float: bool = False,
    cap: int | float = 4096,
) -> list[int | float]:
    """Return deduplicated sorted boundary/magnitude ladder values within `[lo, hi]`.

    Includes `lo`, `-1`, `0`, `1`, `16`, `64`, `256`, `1024`, `min(hi, cap)`, and `hi`
    clamped to `[lo, hi]` (ADR-125 section 2.3).
    """
    eff_lo: float = float(_INT32_MIN if not is_float else -float(cap)) if lo is None else float(lo)
    eff_hi: float = float(_INT32_MAX if not is_float else float(cap)) if hi is None else float(hi)
    if eff_lo > eff_hi:
        eff_lo, eff_hi = eff_hi, eff_lo

    cap_val = float(cap)
    clamped_cap = min(eff_hi, max(eff_lo, cap_val))
    raw_points: list[float] = [eff_lo, *[float(p) for p in _BASE_LADDER_POINTS], clamped_cap, eff_hi]

    if is_float:
        seen_f: set[float] = set()
        out_f: list[float] = []
        for val in sorted(raw_points):
            if eff_lo - 1e-9 <= val <= eff_hi + 1e-9:
                clamped = min(eff_hi, max(eff_lo, round(float(val), 6)))
                if clamped not in seen_f:
                    seen_f.add(clamped)
                    out_f.append(clamped)
        return out_f

    lo_i = int(math.ceil(eff_lo)) if eff_lo != int(eff_lo) else int(eff_lo)
    hi_i = int(math.floor(eff_hi)) if eff_hi != int(eff_hi) else int(eff_hi)
    if lo_i > hi_i:
        lo_i = int(round(eff_lo))
        hi_i = lo_i
    clamped_cap_i = min(hi_i, max(lo_i, int(round(cap_val))))
    int_candidates = [lo_i, *_BASE_LADDER_POINTS, clamped_cap_i, hi_i]
    seen_i: set[int] = set()
    out_i: list[int | float] = []
    for v in sorted(int_candidates):
        if lo_i <= v <= hi_i and v not in seen_i:
            seen_i.add(v)
            out_i.append(int(v))
    return out_i


def _resolve_node_chain(node: CommandNode | str, tree: CommandTree | None) -> list[CommandNode]:
    """Return the ordered list of `CommandNode`s from root to `node`."""
    if isinstance(node, str):
        if tree is None or node not in tree.nodes:
            raise KeyError(f"command node {node!r} not found in CommandTree")
        target = tree.nodes[node]
    else:
        target = node

    if tree is None:
        if not target._steps:
            return [target]
        root_name = target._root_id or target.node_id.partition(" ")[0]
        synth: list[CommandNode] = [
            CommandNode(node_id=root_name, name=root_name, kind="literal", _root_id=root_name)
        ]
        cur_id = root_name
        for idx, (skind, sname, sparser) in enumerate(target._steps):
            tok = f"<{sname}>" if skind == "argument" else sname
            cur_id = f"{cur_id} {tok}"
            if idx == len(target._steps) - 1:
                synth.append(target)
            else:
                synth.append(
                    CommandNode(
                        node_id=cur_id,
                        name=sname,
                        kind=skind,
                        parser=sparser,
                        _steps=target._steps[: idx + 1],
                        _root_id=root_name,
                    )
                )
        return synth

    tree._ensure_steps()
    parent_of: dict[str, str] = {}
    for pid, pnode in tree.nodes.items():
        for cid in pnode.children:
            if cid not in parent_of:
                parent_of[cid] = pid

    chain: list[CommandNode] = []
    cur: str | None = target.node_id
    seen: set[str] = set()
    while cur and cur in tree.nodes and cur not in seen:
        seen.add(cur)
        chain.append(tree.nodes[cur])
        cur = parent_of.get(cur)
    if not chain:
        return [target]
    chain.reverse()
    if len(chain) == 1 and target._steps:
        root_name = target._root_id or target.node_id.partition(" ")[0]
        prefix_nodes: list[CommandNode] = []
        root_node = tree.nodes.get(root_name) or CommandNode(
            node_id=root_name, name=root_name, kind="literal", _root_id=root_name
        )
        prefix_nodes.append(root_node)
        cur_id = root_name
        for idx, (skind, sname, sparser) in enumerate(target._steps):
            tok = f"<{sname}>" if skind == "argument" else sname
            cur_id = f"{cur_id} {tok}"
            if idx == len(target._steps) - 1:
                prefix_nodes.append(target)
            elif cur_id in tree.nodes:
                prefix_nodes.append(tree.nodes[cur_id])
            else:
                prefix_nodes.append(
                    CommandNode(
                        node_id=cur_id,
                        name=sname,
                        kind=skind,
                        parser=sparser,
                        _steps=target._steps[: idx + 1],
                        _root_id=root_name,
                    )
                )
        return prefix_nodes
    return chain


def _sample_argument_token(
    arg_node: CommandNode,
    rng: Any,
    *,
    completions: dict[str, list[str]] | None = None,
    bot_names: list[str] | None = None,
    cap: int | float = 4096,
) -> str:
    """Sample one concrete argument token for `arg_node` according to its Brigadier parser."""
    comp_list: list[str] = []
    if completions:
        for key in (arg_node.node_id, arg_node.name, f"<{arg_node.name}>"):
            if key in completions and completions[key]:
                comp_list = [str(c).strip() for c in completions[key] if str(c).strip()]
                if comp_list:
                    break
    if not comp_list and arg_node.completions:
        comp_list = [str(c).strip() for c in arg_node.completions if str(c).strip()]

    p_kind = normalize_parser_kind(arg_node.parser)
    props = arg_node.properties or {}

    if comp_list and p_kind not in ("integer", "long", "int", "float", "double"):
        return str(rng.choice(comp_list))
    if comp_list and rng.random() < 0.25:
        return str(rng.choice(comp_list))

    if p_kind in ("integer", "long", "int"):
        lo_raw = props.get("min", _INT32_MIN)
        hi_raw = props.get("max", _INT32_MAX)
        lo_i = int(lo_raw) if lo_raw is not None else _INT32_MIN
        hi_i = int(hi_raw) if hi_raw is not None else _INT32_MAX
        if lo_i > hi_i:
            lo_i, hi_i = hi_i, lo_i
        cap_i = max(1, int(round(float(cap))))
        ladder = numeric_ladder(lo_i, hi_i, is_float=False, cap=cap)
        if cap_i < hi_i:
            capped_ladder = [v for v in ladder if -cap_i <= int(v) <= cap_i]
            if capped_ladder:
                ladder = capped_ladder
        if ladder and rng.random() < 0.75:
            return str(int(rng.choice(ladder)))
        u_lo = max(lo_i, -cap_i)
        u_hi = min(hi_i, cap_i)
        if u_lo > u_hi:
            u_lo, u_hi = lo_i, hi_i
        return str(int(rng.randint(u_lo, u_hi)))

    if p_kind in ("float", "double"):
        cap_f = float(cap)
        lo_raw = props.get("min", -cap_f)
        hi_raw = props.get("max", cap_f)
        lo_f = float(lo_raw) if lo_raw is not None else -cap_f
        hi_f = float(hi_raw) if hi_raw is not None else cap_f
        if lo_f > hi_f:
            lo_f, hi_f = hi_f, lo_f
        ladder_f = numeric_ladder(lo_f, hi_f, is_float=True, cap=cap_f)
        if cap_f < hi_f:
            capped_f = [v for v in ladder_f if -cap_f <= float(v) <= cap_f]
            if capped_f:
                ladder_f = capped_f
        if ladder_f and rng.random() < 0.75:
            val_f = float(rng.choice(ladder_f))
        else:
            u_lo_f = max(lo_f, -cap_f)
            u_hi_f = min(hi_f, cap_f)
            if u_lo_f > u_hi_f:
                u_lo_f, u_hi_f = lo_f, hi_f
            val_f = round(float(rng.uniform(u_lo_f, u_hi_f)), 2)
        if val_f.is_integer():
            return f"{val_f:.1f}"
        return f"{val_f:g}"

    if p_kind in ("bool", "boolean"):
        return str(rng.choice(["true", "false"]))

    if p_kind in ("entity", "game_profile", "score_holder"):
        candidates = ["{self}"]
        for b in bot_names or ():
            b_str = str(b).strip()
            if b_str and b_str not in candidates:
                candidates.append(b_str)
        return str(rng.choice(candidates))

    if p_kind in ("vec2", "column_pos"):
        offsets = (-16, -1, 0, 1, 16)
        dx = rng.choice(offsets)
        dz = rng.choice(offsets)
        return f"~{dx if dx != 0 else ''} ~{dz if dz != 0 else ''}".strip()

    if p_kind in ("block_pos", "vec3", "rotation"):
        if p_kind == "rotation":
            return f"{rng.choice([-90, 0, 90, 180])} {rng.choice([-45, 0, 45])}"
        offsets = (-16, -1, 0, 1, 16)
        dx = rng.choice(offsets)
        dy = rng.choice((-1, 0, 1))
        dz = rng.choice(offsets)
        tx = f"~{dx}" if dx != 0 else "~"
        ty = f"~{dy}" if dy != 0 else "~"
        tz = f"~{dz}" if dz != 0 else "~"
        return f"{tx} {ty} {tz}"

    if p_kind in ("dimension", "resource_location"):
        return str(rng.choice(["minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"]))

    str_type = str(props.get("type") or props.get("mode") or "word").strip().lower()
    if str_type in ("phrase", "quotable_phrase", "1"):
        return str(rng.choice(['"alpha"', '"beta_test"', "default", "alpha"]))
    if str_type in ("greedy", "greedy_phrase", "2"):
        return str(rng.choice(["alpha", "alpha beta", "default"]))
    return str(rng.choice(["alpha", "beta", "default", "1"]))


def gen_args(
    node: CommandNode | str,
    tree: CommandTree | None,
    rng: Any = None,
    *,
    denylist: Denylist | Iterable[str] | None = None,
    completions: dict[str, list[str]] | None = None,
    bot_names: list[str] | None = None,
    cap: int | float = 4096,
) -> str:
    """Generate a concrete command string for `node` in `tree` using `rng`, enforcing `Denylist`."""
    rng_obj = rng if rng is not None else random.Random(1)
    chain = _resolve_node_chain(node, tree)
    target = chain[-1]

    active_deny: Denylist
    if isinstance(denylist, Denylist):
        active_deny = denylist
    elif denylist is not None:
        active_deny = Denylist(denylist)
    elif tree is not None and tree.denylist_entries:
        active_deny = Denylist(tree.denylist_entries)
    else:
        active_deny = Denylist()

    if target.denied:
        raise ValueError(f"command node {target.node_id!r} is denied by {target.denied_by!r}")

    root_node = chain[0]
    root_names = tuple(root_node.aliases) if root_node.aliases else (root_node.name or target._root_id,)
    steps: list[SequenceStep] = [
        (n.kind, n.name, n.parser if n.kind == "argument" else None)
        for n in chain[1:]
    ]
    matched_rule = active_deny.match_node(root_names, steps)
    if matched_rule is not None:
        raise ValueError(f"command node {target.node_id!r} matches denylist rule {matched_rule!r}")

    for attempt in range(8):
        _ = attempt
        tokens: list[str] = []
        for idx, step_node in enumerate(chain):
            if idx == 0 or step_node.kind != "argument":
                tokens.append(step_node.name)
            else:
                tok = _sample_argument_token(
                    step_node,
                    rng_obj,
                    completions=completions,
                    bot_names=bot_names,
                    cap=cap,
                )
                tokens.append(tok)
        cmd_line = " ".join(t for t in tokens if t)
        deny_hit = active_deny.match_command_line(cmd_line, tree)
        if deny_hit is None:
            return cmd_line
    raise ValueError(f"generated command for {target.node_id!r} is denied by {deny_hit!r}")


format_node_command = gen_args


def build_arm_program(
    node: CommandNode | str,
    shape: str,
    tree: CommandTree | None,
    rng: Any = None,
    *,
    denylist: Denylist | Iterable[str] | None = None,
    completions: dict[str, list[str]] | None = None,
    bot_names: list[str] | None = None,
    cap: int | float = 4096,
) -> list[dict[str, Any]]:
    """Build a bot episode program `[{"name": ..., "program": [...]}]` for `(node, shape)`."""
    rng_obj = rng if rng is not None else random.Random(1)
    names = [str(b) for b in (bot_names or ["fz_0", "fz_1"]) if str(b).strip()] or ["fz_0"]
    primary_bot = names[0]
    peer_bot = names[1] if len(names) > 1 else f"{primary_bot}_peer"
    cmd_line = gen_args(
        node,
        tree,
        rng_obj,
        denylist=denylist,
        completions=completions,
        bot_names=names,
        cap=cap,
    )
    target_id = node if isinstance(node, str) else node.node_id
    cmd_act = {"a": "cmd", "cmd": cmd_line, "node": target_id}
    norm_shape = (shape or "repeat").strip().lower()

    if norm_shape == "quit_lt1s":
        return [
            {
                "name": primary_bot,
                "program": [
                    dict(cmd_act),
                    {"a": "wait", "ms": int(rng_obj.choice([200, 400, 750]))},
                    {"a": "quit"},
                ],
            }
        ]
    if norm_shape == "quit_delay":
        return [
            {
                "name": primary_bot,
                "program": [
                    dict(cmd_act),
                    {"a": "wait", "ms": int(rng_obj.choice([1200, 1800, 2500]))},
                    {"a": "quit"},
                ],
            }
        ]
    if norm_shape == "rejoin":
        return [
            {
                "name": primary_bot,
                "program": [
                    dict(cmd_act),
                    {"a": "wait", "ms": 300},
                    {"a": "rejoin", "rename": False},
                    dict(cmd_act),
                ],
            }
        ]
    if norm_shape == "concurrent":
        peer_cmd = gen_args(
            node,
            tree,
            rng_obj,
            denylist=denylist,
            completions=completions,
            bot_names=[peer_bot, primary_bot],
            cap=cap,
        )
        return [
            {
                "name": primary_bot,
                "program": [dict(cmd_act), {"a": "wait", "ms": 200}, dict(cmd_act)],
            },
            {
                "name": peer_bot,
                "program": [{"a": "cmd", "cmd": peer_cmd, "node": target_id}, {"a": "wait", "ms": 200}],
            },
        ]
    if norm_shape == "window_click":
        return [
            {
                "name": primary_bot,
                "program": [
                    dict(cmd_act),
                    {"a": "wait", "ms": 250},
                    {"a": "click", "slot": int(rng_obj.choice([0, 1, 4, 8])), "button": 0, "mode": 0},
                    {"a": "wait", "ms": 150},
                    {"a": "close_window"},
                ],
            }
        ]
    if norm_shape == "homesteader_reroll":
        reroll_cmd_1 = gen_args(
            node,
            tree,
            rng_obj,
            denylist=denylist,
            completions=completions,
            bot_names=names,
            cap=cap,
        )
        reroll_cmd_2 = gen_args(
            node,
            tree,
            rng_obj,
            denylist=denylist,
            completions=completions,
            bot_names=names,
            cap=cap,
        )
        return [
            {
                "name": primary_bot,
                "program": [
                    dict(cmd_act),
                    {"a": "wait", "ms": 300},
                    {"a": "move", "dx": int(rng_obj.choice([-8, 8])), "dz": int(rng_obj.choice([-8, 8])), "ms": 400},
                    {"a": "cmd", "cmd": reroll_cmd_1, "node": target_id},
                    {"a": "wait", "ms": 250},
                    {"a": "cmd", "cmd": reroll_cmd_2, "node": target_id},
                    {"a": "wait", "ms": 350},
                    {"a": "move", "dx": 4, "dz": -4, "ms": 300},
                ],
            }
        ]
    if norm_shape == "explorer_wander":
        follow_cmd = gen_args(
            node,
            tree,
            rng_obj,
            denylist=denylist,
            completions=completions,
            bot_names=names,
            cap=cap,
        )
        return [
            {
                "name": primary_bot,
                "program": [
                    dict(cmd_act),
                    {"a": "wait", "ms": 250},
                    {"a": "move", "dx": int(rng_obj.choice([-24, 24])), "dz": int(rng_obj.choice([-16, 16])), "ms": 600},
                    {"a": "cmd", "cmd": follow_cmd, "node": target_id},
                    {"a": "move", "dx": int(rng_obj.choice([-32, 32])), "dz": int(rng_obj.choice([-24, 24])), "ms": 700},
                    {"a": "wait", "ms": 200},
                ],
            }
        ]
    if norm_shape == "coop_group":
        peer_cmd = gen_args(
            node,
            tree,
            rng_obj,
            denylist=denylist,
            completions=completions,
            bot_names=[peer_bot, primary_bot],
            cap=cap,
        )
        return [
            {
                "name": primary_bot,
                "program": [
                    dict(cmd_act),
                    {"a": "wait", "ms": 250},
                    {"a": "move", "dx": 8, "dz": 8, "ms": 400},
                    dict(cmd_act),
                ],
            },
            {
                "name": peer_bot,
                "program": [
                    {"a": "wait", "ms": 150},
                    {"a": "cmd", "cmd": peer_cmd, "node": target_id},
                    {"a": "move", "dx": -8, "dz": 8, "ms": 400},
                    {"a": "wait", "ms": 200},
                ],
            },
        ]
    return [
        {
            "name": primary_bot,
            "program": [
                dict(cmd_act),
                {"a": "wait", "ms": 150},
                dict(cmd_act),
                {"a": "wait", "ms": 150},
                dict(cmd_act),
            ],
        }
    ]


@dataclass
class BanditArmStats:
    node_id: str
    shape: str
    min_tier: str
    pulls: int = 0
    total_reward: float = 0.0
    novelty_hits: int = 0
    harm_total: float = 0.0
    usage_errors: int = 0

    @property
    def mean_reward(self) -> float:
        return self.total_reward / self.pulls if self.pulls > 0 else 0.0


class NodeBandit:
    """Tier-stratified UCB1 scheduler over `(node_id, scenario_shape)` pairs (ADR-125 section 2.3).

    Stratifies selection budget so `default`-visible (player-reachable) command nodes
    receive `default_budget_share` (default 0.75) of pulls when both `default` and `op`
    tiers are active, preventing a large `op` command tree from starving the player surface.
    """

    def __init__(
        self,
        tree: CommandTree,
        *,
        tiers: Iterable[str] = ("default",),
        default_budget_share: float = 0.75,
        c: float = 1.414,
        rng: Any = None,
        shapes: Iterable[str] = SCENARIO_SHAPES,
    ):
        self.tree = tree
        self.tiers = sort_tiers(tiers) or ["default"]
        self.default_budget_share = min(1.0, max(0.0, float(default_budget_share)))
        self.c = float(c)
        self.rng = rng if rng is not None else random.Random(1)
        self.shapes: tuple[str, ...] = tuple(str(s) for s in shapes if str(s)) or SCENARIO_SHAPES

        allowed_nodes = self.tree.allowed_executable_nodes(self.tiers)
        self.arms: dict[tuple[str, str], BanditArmStats] = {}
        self.default_arms: list[tuple[str, str]] = []
        self.op_arms: list[tuple[str, str]] = []

        for node in allowed_nodes:
            m_tier = "default" if ("default" in node.tiers or node.min_tier == "default") else "op"
            for sh in self.shapes:
                key = (node.node_id, sh)
                self.arms[key] = BanditArmStats(node_id=node.node_id, shape=sh, min_tier=m_tier)
                if m_tier == "default":
                    self.default_arms.append(key)
                else:
                    self.op_arms.append(key)

        self.total_pulls: int = 0
        self.stratum_pulls: dict[str, int] = {"default": 0, "op": 0}
        self.node_branch_hints: dict[str, int] = {}

    def _choose_stratum(self) -> tuple[str, list[tuple[str, str]]]:
        if self.default_arms and self.op_arms:
            if self.rng.random() < self.default_budget_share:
                return "default", self.default_arms
            return "op", self.op_arms
        if self.default_arms:
            return "default", self.default_arms
        if self.op_arms:
            return "op", self.op_arms
        raise ValueError("NodeBandit has no allowed executable command arms for the active tiers")

    def _ucb_score(self, stats: BanditArmStats, stratum_total: int) -> float:
        log_term = math.log(max(2.0, float(stratum_total) + 2.0))
        if stats.pulls <= 0:
            return self.c * math.sqrt(log_term)
        return stats.mean_reward + self.c * math.sqrt(log_term / float(stats.pulls))

    def select_arm(self) -> tuple[str, str]:
        """Select the next `(node_id, shape)` arm via tier-stratified UCB1 and record the pull."""
        stratum, pool = self._choose_stratum()
        stratum_total = self.stratum_pulls.get(stratum, 0)

        best_score = -float("inf")
        best_candidates: list[tuple[str, str]] = []
        for arm_key in pool:
            st = self.arms[arm_key]
            sc = self._ucb_score(st, stratum_total)
            if sc > best_score + 1e-12:
                best_score = sc
                best_candidates = [arm_key]
            elif abs(sc - best_score) <= 1e-12:
                best_candidates.append(arm_key)

        chosen = best_candidates[0] if len(best_candidates) == 1 else self.rng.choice(best_candidates)
        self.arms[chosen].pulls += 1
        self.total_pulls += 1
        self.stratum_pulls[stratum] = stratum_total + 1
        return chosen

    pick_arm = select_arm
    next_arm = select_arm

    def update(
        self,
        node_id: str,
        shape: str,
        reward: float | None = None,
        *,
        novelty: int | float = 0.0,
        class_growth: float = 0.0,
        tick_gap_excess_ms: float = 0.0,
        kicks: int = 0,
        usage_error: bool = False,
    ) -> float:
        """Update arm statistics with novelty and telemetry harm deltas."""
        key = (node_id, shape)
        if key not in self.arms:
            node = self.tree.nodes.get(node_id)
            m_tier = "default" if (node and ("default" in node.tiers or node.min_tier == "default")) else "op"
            self.arms[key] = BanditArmStats(node_id=node_id, shape=shape, min_tier=m_tier)
            if m_tier == "default":
                self.default_arms.append(key)
            else:
                self.op_arms.append(key)

        stats = self.arms[key]
        if stats.pulls == 0:
            stats.pulls = 1
            self.total_pulls += 1
            self.stratum_pulls[stats.min_tier] = self.stratum_pulls.get(stats.min_tier, 0) + 1

        harm_component = (
            max(0.0, float(class_growth)) / 50.0
            + max(0.0, float(tick_gap_excess_ms)) / 500.0
            + 0.5 * max(0, int(kicks))
        )
        computed = (0.0 if reward is None else float(reward)) + float(novelty) + harm_component
        if usage_error:
            computed -= 0.25
            stats.usage_errors += 1
            self.node_branch_hints[node_id] = self.node_branch_hints.get(node_id, 0) + 1
        if novelty > 0:
            stats.novelty_hits += int(max(1, round(float(novelty))))
        stats.harm_total += harm_component
        stats.total_reward += computed
        return computed

    def summary(self) -> dict[str, Any]:
        return {
            "tiers": list(self.tiers),
            "default_budget_share": self.default_budget_share,
            "total_pulls": self.total_pulls,
            "default_pulls": self.stratum_pulls.get("default", 0),
            "op_pulls": self.stratum_pulls.get("op", 0),
            "arm_count": len(self.arms),
            "default_arm_count": len(self.default_arms),
            "op_arm_count": len(self.op_arms),
        }


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
        prereq_prefixes=("viaversion", "viabackwards", "viarewind", "luckperms", "vault"),
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


def _default_pid_cmdline(pid: int) -> str | None:
    proc_cmd = f"/proc/{pid}/cmdline"
    try:
        with open(proc_cmd, "rb") as fh:
            raw = fh.read()
        if raw:
            return raw.replace(b"\x00", b" ").decode("utf-8", errors="replace").strip()
    except OSError:
        return None
    return None


def _cmd_contains_dir_path(cmd_desc: str, *dir_paths: str) -> bool:
    """Return True only when a directory path (with a separator, never a bare basename) appears as a path prefix in `cmd_desc`."""
    norm_cmd = os.path.normcase((cmd_desc or "").replace("\\", "/"))
    if not norm_cmd:
        return False
    for dp in dir_paths:
        if not dp:
            continue
        clean = os.path.normcase(dp.replace("\\", "/").rstrip("/"))
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
        ssh: str | None = None,
        remote_server_dir: str | None = None,
        server_dir: str | None = None,
        jar_pattern: str | None = None,
        jcmd_bin: str = "jcmd",
        docker_bin: str = "docker",
        ssh_bin: str = "ssh",
        timeout_s: float = 60.0,
        post_grace_ms: int = 250,
        check_local_pid: bool = True,
        runner: Callable[[list[str], float], subprocess.CompletedProcess[str]] | None = None,
        clock_ms: Callable[[], int] | None = None,
        pid_cwd: Callable[[int], str | None] | None = None,
        pid_cmdline: Callable[[int], str | None] | None = None,
    ):
        self._explicit_pid = pid
        self.pid_file = pid_file
        self.container = container.strip() if isinstance(container, str) and container.strip() else None
        self.container_user = container_user.strip() if isinstance(container_user, str) and container_user.strip() else None
        self.ssh = ssh.strip() if isinstance(ssh, str) and ssh.strip() else None
        self.remote_server_dir = (
            remote_server_dir.strip() if isinstance(remote_server_dir, str) and remote_server_dir.strip() else None
        )
        self.server_dir = server_dir
        self.jar_pattern = jar_pattern
        self.jcmd_bin = jcmd_bin
        self.docker_bin = docker_bin
        self.ssh_bin = ssh_bin
        self.timeout_s = float(timeout_s)
        self.post_grace_ms = max(0, int(post_grace_ms))
        self.check_local_pid = bool(check_local_pid)
        self._runner = runner or self._default_runner
        self._clock_ms = clock_ms or _default_clock_ms
        self._pid_cwd = pid_cwd or _default_pid_cwd
        self._pid_cmdline = pid_cmdline or _default_pid_cmdline
        self._resolved_pid: int | None = None
        self.exclusion_windows: list[ExclusionWindow] = []

        if self.container is not None and self.container.startswith("-"):
            raise JcmdError(f"invalid container name {self.container!r}; pass a container name or ID to --container")
        if self.container_user is not None and self.container_user.startswith("-"):
            raise JcmdError(f"invalid container user {self.container_user!r}; pass a user name or UID")
        if self.ssh is not None and self.ssh.startswith("-"):
            raise JcmdError(f"invalid ssh target {self.ssh!r}; pass [user@]host to --ssh")

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

    def _ssh_exec_prefix(self) -> list[str]:
        assert self.ssh is not None
        return [self.ssh_bin, "-o", "BatchMode=yes", self.ssh]

    def record_window(self, start_ms: int, end_ms: int, kind: str = "external") -> ExclusionWindow:
        w = ExclusionWindow(start_ms=int(min(start_ms, end_ms)), end_ms=int(max(start_ms, end_ms)), kind=str(kind))
        self.exclusion_windows.append(w)
        return w

    def overlaps_exclusion(self, start_ms: float, end_ms: float) -> bool:
        return any(w.overlaps(start_ms, end_ms) for w in self.exclusion_windows)

    def _verify_local_pid(self, pid: int, source_desc: str) -> int:
        if pid <= 0:
            raise JcmdError(f"invalid PID {pid} from {source_desc}; expected a positive integer")
        if self.container is not None or self.ssh is not None or not self.check_local_pid:
            return pid

        # Never call os.kill(pid, 0) on Windows: signal 0 == CTRL_C_EVENT and sends Ctrl+C.
        if os.name != "posix":
            raise JcmdError(
                f"local PID liveness and ownership verification for PID {pid} ({source_desc}) "
                f"is unsupported on non-POSIX host ({os.name!r}); refusing to probe or attach."
            )

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
            real_dir = os.path.normcase(os.path.normpath(os.path.realpath(self.server_dir)))
            raw_dir = os.path.normpath(self.server_dir)
            cand_cwd = self._pid_cwd(pid)
            cwd_matches = (
                cand_cwd is not None
                and os.path.normcase(os.path.normpath(os.path.realpath(cand_cwd))) == real_dir
            )
            if not cwd_matches:
                cmdline_txt = self._pid_cmdline(pid)
                if not _cmd_contains_dir_path(cmdline_txt or "", raw_dir, real_dir):
                    if cand_cwd is None:
                        raise JcmdError(
                            f"PID {pid} ({source_desc}) ownership of server_dir={self.server_dir!r} "
                            "cannot be positively verified (unknown working directory and unreadable or "
                            "non-matching command line). Refusing to run jcmd against an unverified JVM."
                        )
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
        use_server_dir: bool = True,
    ) -> list[tuple[int, str]]:
        eff_server_dir = self.server_dir if use_server_dir else None
        if not self.jar_pattern and not eff_server_dir:
            return list(candidates)

        jar_pat_low = self.jar_pattern.lower() if self.jar_pattern else None
        raw_dir = os.path.normpath(eff_server_dir) if eff_server_dir else ""
        real_dir = os.path.normpath(os.path.realpath(eff_server_dir)) if eff_server_dir else ""
        real_dir_norm = os.path.normcase(real_dir) if real_dir else ""

        matched: list[tuple[int, str]] = []
        for cand_pid, cmd_desc in candidates:
            low_desc = cmd_desc.lower()
            if jar_pat_low and jar_pat_low not in low_desc:
                continue
            if eff_server_dir:
                cand_cwd = self._pid_cwd(cand_pid) if check_cwd else None
                cwd_ok = (
                    cand_cwd is not None
                    and os.path.normcase(os.path.normpath(os.path.realpath(cand_cwd))) == real_dir_norm
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
            # Ignore host `server_dir` inside the container namespace; match by `jar_pattern` or single JVM.
            matched = self._match_jcmd_candidates(candidates, check_cwd=False, use_server_dir=False)
            if len(matched) == 1:
                self._resolved_pid = matched[0][0]
                return self._resolved_pid
            if not matched and len(candidates) == 1 and not self.jar_pattern:
                self._resolved_pid = candidates[0][0]
                return self._resolved_pid
            cand_summary = ", ".join(f"{p} ({d or '<no main class>'})" for p, d in candidates)
            if not matched:
                raise JcmdError(
                    f"`{' '.join(list_cmd)}` found no JVM matching jar_pattern={self.jar_pattern!r}; "
                    f"container JVMs: [{cand_summary}]."
                )
            match_summary = ", ".join(f"{p} ({d})" for p, d in matched)
            raise JcmdError(
                f"`{' '.join(list_cmd)}` matched multiple container JVMs: [{match_summary}]. "
                "Hint: pass explicit `pid` or `jar_pattern` to disambiguate."
            )

        if self.ssh is not None:
            list_cmd = self._ssh_exec_prefix() + [self.jcmd_bin, "-l"]
            try:
                proc = self._runner(list_cmd, self.timeout_s)
            except FileNotFoundError as exc:
                raise JcmdError(
                    f"executable `{list_cmd[0]}` not found while running {' '.join(list_cmd)}. "
                    "Hint: ensure `ssh` is installed and on PATH."
                ) from exc
            except subprocess.TimeoutExpired as exc:
                raise JcmdError(f"`{' '.join(list_cmd)}` timed out after {self.timeout_s:g}s.") from exc

            combined = f"{proc.stdout or ''}\n{proc.stderr or ''}".strip()
            if proc.returncode != 0:
                raise JcmdError(
                    f"`{' '.join(list_cmd)}` failed (exit {proc.returncode}): {combined}. "
                    "Hint: verify non-interactive SSH access (`ssh -o BatchMode=yes`) and JDK `jcmd` on the remote host."
                )
            candidates = self._parse_jcmd_list(proc.stdout or "")
            if not candidates:
                raise JcmdError(
                    f"`{' '.join(list_cmd)}` listed no target JVMs on {self.ssh!r}. "
                    "Hint: verify the server is running under the SSH user on the remote host."
                )
            saved_dir = self.server_dir
            try:
                if self.remote_server_dir:
                    self.server_dir = self.remote_server_dir
                matched = self._match_jcmd_candidates(
                    candidates,
                    check_cwd=False,
                    use_server_dir=bool(self.remote_server_dir),
                )
            finally:
                self.server_dir = saved_dir
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
                    f"remote_server_dir={self.remote_server_dir!r}; remote JVMs: [{cand_summary}]."
                )
            match_summary = ", ".join(f"{p} ({d})" for p, d in matched)
            raise JcmdError(
                f"`{' '.join(list_cmd)}` matched multiple remote JVMs on {self.ssh!r}: [{match_summary}]. "
                "Hint: pass --pid, --pid-file, or --remote-server-dir to disambiguate."
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
        if self.container is None and self.ssh is None and self.check_local_pid:
            # Re-check liveness and ownership before every jcmd call to guard against PID recycling.
            target_pid = self._verify_local_pid(target_pid, "re-check before jcmd")
        if self.container is not None:
            return self._docker_exec_prefix() + [self.jcmd_bin, str(target_pid), op]
        if self.ssh is not None:
            return self._ssh_exec_prefix() + [self.jcmd_bin, str(target_pid), op]
        return [self.jcmd_bin, str(target_pid), op]

    def _run_jcmd_op(self, op: str, *, record_window: bool = True) -> tuple[str, ExclusionWindow]:
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
            end_ms = max(t0, t1) + self.post_grace_ms
            if record_window:
                win = self.record_window(t0, end_ms, kind=op)
            else:
                win = ExclusionWindow(start_ms=t0, end_ms=end_ms, kind=op)

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

    def thread_print(self, *, record_window: bool = True) -> str:
        """Collect a `Thread.print` stack dump and optionally record its observation window."""
        out, _ = self._run_jcmd_op("Thread.print", record_window=record_window)
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


def parse_plugin_yml_commands(text: str) -> tuple[list[str], dict[str, str]]:
    """Parse `(canonical_commands, alias_to_canonical)` from Bukkit `plugin.yml` `commands:` block."""
    canonical_cmds: list[str] = []
    alias_map: dict[str, str] = {}
    in_commands = False
    commands_indent = -1
    cur_cmd: str | None = None
    cur_cmd_indent = -1
    in_aliases = False
    aliases_indent = -1

    for raw_line in (text or "").splitlines():
        line = _strip_yaml_comment(raw_line).rstrip()
        if not line.strip():
            continue
        indent = len(line) - len(line.lstrip(" "))
        stripped = line.strip()

        if in_aliases:
            if indent >= aliases_indent and stripped.startswith("-"):
                al = _unquote_scalar(stripped[1:]).lower()
                if al and cur_cmd and al != cur_cmd:
                    alias_map[al] = cur_cmd
                continue
            if indent <= aliases_indent and not stripped.startswith("-"):
                in_aliases = False

        if indent == 0 and ":" in stripped:
            key, _, _ = stripped.partition(":")
            in_commands = key.strip().lower() == "commands"
            commands_indent = indent if in_commands else -1
            cur_cmd = None
            cur_cmd_indent = -1
            in_aliases = False
            continue

        if in_commands:
            if indent <= commands_indent:
                in_commands = False
                cur_cmd = None
                continue
            if (cur_cmd is None or indent <= cur_cmd_indent) and ":" in stripped and not stripped.startswith("-"):
                ckey, _, _ = stripped.partition(":")
                cmd_name = _unquote_scalar(ckey).lower()
                if cmd_name:
                    cur_cmd = cmd_name
                    cur_cmd_indent = indent
                    if cmd_name not in canonical_cmds:
                        canonical_cmds.append(cmd_name)
                continue
            if cur_cmd is not None and indent > cur_cmd_indent and ":" in stripped:
                akey, _, arest = stripped.partition(":")
                if akey.strip().lower() == "aliases":
                    arest_s = arest.strip()
                    if arest_s.startswith("["):
                        for item in _parse_inline_yaml_list(arest_s):
                            al = item.lower()
                            if al and al != cur_cmd:
                                alias_map[al] = cur_cmd
                    elif arest_s:
                        al = _unquote_scalar(arest_s).lower()
                        if al and al != cur_cmd:
                            alias_map[al] = cur_cmd
                    else:
                        in_aliases = True
                        aliases_indent = indent

    return canonical_cmds, alias_map


@dataclass
class JarMetadata:
    jar_name: str
    jar_path: str
    plugin_id: str
    provides: list[str] = field(default_factory=list)
    hard_depends: list[str] = field(default_factory=list)
    soft_depends: list[str] = field(default_factory=list)
    class_count: int = 0
    commands: list[str] = field(default_factory=list)
    command_aliases: dict[str, str] = field(default_factory=dict)


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

    @classmethod
    def from_server_dir(
        cls,
        server_dir: str,
        profile: PlatformProfile | str = "paper",
        include_disabled: bool = False,
    ) -> JarIndex:
        prof = profile if isinstance(profile, PlatformProfile) else get_platform_profile(profile)
        return cls(prof.jar_dir(server_dir), include_disabled=include_disabled)

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
            jar_cmds: list[str] = []
            jar_aliases: dict[str, str] = {}
            try:
                with zipfile.ZipFile(jar_path, "r") as zf:
                    if "plugin.yml" in set(zf.namelist()):
                        yml_text = zf.read("plugin.yml").decode("utf-8", errors="replace")
                        jar_cmds, jar_aliases = parse_plugin_yml_commands(yml_text)
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
                commands=jar_cmds,
                command_aliases=jar_aliases,
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


#: Top-rank exceedance thresholds. Each threshold is a function of the class deltas alone, so the
#: permutation null stays exact; the ladder length is the within-class multiplicity charged to every p-value.
_TOP_RANK_LADDER: tuple[int, ...] = (4, 8, 16, 32, 64)
#: Cheap normal-score gate: skips only (class, feature, threshold) cells that cannot reach a Bonferroni p-value.
_PREFILTER_Z = 2.0


@functools.lru_cache(maxsize=1 << 16)
def _hypergeom_upper_tail(n: int, high: int, draws: int, hits: int) -> float:
    """Exact `P(X >= hits)` for `X ~ Hypergeometric(population n, high successes, draws)`."""
    if hits <= 0:
        return 1.0
    lo = max(hits, draws - (n - high), 0)
    hi = min(draws, high)
    if lo > hi:
        return 0.0
    num = sum(math.comb(high, i) * math.comb(n - high, draws - i) for i in range(lo, hi + 1))
    return min(1.0, num / math.comb(n, draws))


class _LeakDesign:
    """Episode x feature design plus per-class deltas for the passive leak screen.

    A feature is *active* in an episode when its count is positive (features active everywhere are split
    at the median count). Significance never depends on a fitted model: for each class the episodes whose
    delta falls in the top-`m` ranks (ties included) are compared with the feature's active set through an
    exact hypergeometric tail. That test is distribution-free (discrete, bimodal and heavy-tailed deltas
    included) and exact for any active count, under the null that deltas are exchangeable across episodes.
    """

    def __init__(self, records: list[dict[str, Any]], min_feature_episodes: int) -> None:
        self.n = len(records)
        self.row_feats = [extract_server_episode_features(r) for r in records]
        self.row_deltas = [_extract_record_class_deltas(r) for r in records]
        self.min_act = max(2, int(min_feature_episodes))
        raw_classes: set[str] = set()
        raw_features: set[str] = set()
        for d in self.row_deltas:
            raw_classes.update(d.keys())
        for f in self.row_feats:
            raw_features.update(k for k, v in f.items() if v > 0)
        self.cols: dict[str, list[float]] = {}
        self.act: dict[str, list[int]] = {}
        self.flags: list[list[str]] = [[] for _ in range(self.n)]
        for feat in sorted(raw_features):
            col = [rf.get(feat, 0.0) for rf in self.row_feats]
            act = [i for i, v in enumerate(col) if v > 0.0]
            if len(act) >= self.n:
                med = statistics.median(col)
                act = [i for i, v in enumerate(col) if v > med]
            if len(act) < self.min_act or self.n - len(act) < 2:
                continue
            self.cols[feat] = col
            self.act[feat] = act
            for i in act:
                self.flags[i].append(feat)
        if len(raw_classes) > 32:
            self.classes = {
                cls
                for cls in raw_classes
                if sum(d.get(cls, 0.0) for d in self.row_deltas) > 0.0
                and sum(1 for d in self.row_deltas if d.get(cls, 0.0) > 0.0) >= self.min_act
            }
            self.features = set(self.cols.keys())
        else:
            self.classes = raw_classes
            self.features = raw_features

    def scan(self, p_crit: float) -> dict[tuple[str, str], float]:
        """Return `{(class, feature): adjusted_p}` for cells at or below `p_crit`."""
        n = self.n
        out: dict[tuple[str, str], float] = {}
        if n < 4 or not self.cols:
            return out
        multiplicity = len(_TOP_RANK_LADDER)
        ladder = [m for m in _TOP_RANK_LADDER if m < n]
        for cls in self.classes:
            ys = [d.get(cls, 0.0) for d in self.row_deltas]
            # Functions of the deltas only (not of the feature assignment), so they keep the null exact.
            if sum(ys) <= 0.0 or max(ys) <= min(ys):
                continue
            order = sorted(range(n), key=ys.__getitem__, reverse=True)
            best: dict[str, float] = {}
            seen_high: set[int] = set()
            for m in ladder:
                tau = ys[order[m - 1]]
                high = m
                while high < n and ys[order[high]] >= tau:
                    high += 1
                if high > n // 2 or high in seen_high:
                    continue
                seen_high.add(high)
                counts: dict[str, int] = {}
                for i in order[:high]:
                    for f in self.flags[i]:
                        counts[f] = counts.get(f, 0) + 1
                frac = high / n
                for f, hits in counts.items():
                    n_act = len(self.act[f])
                    mu = n_act * frac
                    if hits < 2 or hits <= mu:
                        continue
                    var = n_act * frac * (1.0 - frac) * (n - n_act) / (n - 1)
                    if var > 0.0 and (hits - mu) / math.sqrt(var) < _PREFILTER_Z:
                        continue
                    p = _hypergeom_upper_tail(n, high, n_act, hits)
                    if p < best.get(f, 1.0):
                        best[f] = p
            for f, p in best.items():
                p_adj = min(1.0, p * multiplicity)
                if p_adj <= p_crit:
                    out[(cls, f)] = p_adj
        return out

    def contrast(self, cls: str, feat: str) -> tuple[float, float, int, float] | None:
        """Active-vs-inactive effect `(per_unit_slope, se, n_active, ctrl_ref)` for one pair, or None."""
        col = self.cols.get(feat)
        if col is None:
            return None
        act = self.act[feat]
        act_set = set(act)
        ys = [d.get(cls, 0.0) for d in self.row_deltas]
        a_ys = [ys[i] for i in act]
        c_ys = [y for i, y in enumerate(ys) if i not in act_set]
        n_act, n_ctl = len(a_ys), len(c_ys)
        mean_x = sum(col[i] for i in act) / n_act
        if mean_x <= 0.0 or n_ctl < 1:
            return None
        mean_a = sum(a_ys) / n_act
        mean_c = sum(c_ys) / n_ctl
        var_a = sum((y - mean_a) ** 2 for y in a_ys) / max(1, n_act - 1)
        var_c = sum((y - mean_c) ** 2 for y in c_ys) / max(1, n_ctl - 1)
        se = math.sqrt(var_a / n_act + var_c / n_ctl) / mean_x
        ctrl_ref = max(statistics.median(c_ys), mean_c)
        return (mean_a - mean_c) / mean_x, se, n_act, ctrl_ref


def _class_drift_stats(design: _LeakDesign) -> dict[str, tuple[float, float, int]]:
    """Per-class episode `(mean, sd, n)` used to net vanilla-baseline drift out of a finding."""
    stats: dict[str, tuple[float, float, int]] = {}
    n = design.n
    for cls in design.classes:
        ys = [d.get(cls, 0.0) for d in design.row_deltas]
        mean = sum(ys) / n
        var = sum((y - mean) ** 2 for y in ys) / max(1, n - 1)
        stats[cls] = (mean, max(NOISE_FLOOR, math.sqrt(max(0.0, var))), n)
    return stats


def screen_server_leaks(
    records: list[dict[str, Any]],
    baseline_records: list[dict[str, Any]] | None = None,
    jar_index: JarIndex | None = None,
    profile: PlatformProfile | None = None,
    alpha: float = 0.05,
    min_n: int = 4,
    min_feature_episodes: int = 4,
    max_results: int = 64,
) -> dict[str, Any]:
    """Passively screen post-GC class growth against `node`, `arm` (`node x follow_up`), and `departures`.

    Each (class, feature) cell gets an exact hypergeometric top-rank exceedance p-value (see
    `_LeakDesign`), multiplied by the threshold-ladder length and compared with `alpha / (2 * classes x
    features)`: Bonferroni, valid under arbitrary dependence between cells. Intended familywise
    false-positive rate under exchangeable deltas is `alpha / 2`; positives are one-sided. Cells with too
    little evidence (few active episodes, or hits that rank plausibly by chance) are simply not reported.
    A flagged cell must also exceed the vanilla baseline's per-feature slope or unabsorbed class drift
    by `max(NOISE_FLOOR, 2 x baseline_se)`. Results stay `unconfirmed_passive_correlation`, with
    `first_suspect` from `JarIndex`.
    """
    usable = [r for r in records if _usable_server_record(r)]
    eff_min_ep = max(4, int(min_feature_episodes))
    empty = {
        "usable_episodes": len(usable),
        "classes_tested": 0,
        "features_tested": 0,
        "total_tests": 0,
        "bonferroni_z": 0.0,
        "correlations": [],
    }
    if len(usable) < max(min_n, eff_min_ep):
        return empty

    design = _LeakDesign(usable, eff_min_ep)
    n_classes = len(design.classes)
    n_features = len(design.features)
    total_tests = n_classes * n_features
    if total_tests == 0:
        return {**empty, "classes_tested": n_classes, "features_tested": n_features}

    z_crit = bonferroni_z(total_tests, alpha=alpha)
    p_crit = alpha / (2.0 * total_tests)
    cells = design.scan(p_crit)

    base_usable = [r for r in (baseline_records or []) if _usable_server_record(r)]
    base_design = _LeakDesign(base_usable, 2) if len(base_usable) >= 2 else None
    base_stats = _class_drift_stats(base_design) if base_design is not None else {}

    correlations: list[dict[str, Any]] = []
    for (cls, feat), p_adj in cells.items():
        eff = design.contrast(cls, feat)
        if eff is None:
            continue
        b, se, active_n, ctrl_ref = eff
        if b <= NOISE_FLOOR:
            continue
        unabsorbed_base = 0.0
        base_drift_se = 0.0
        if cls in base_stats:
            c_mean, c_sd, c_n = base_stats[cls]
            unabsorbed_base = max(0.0, c_mean - max(0.0, ctrl_ref))
            base_drift_se = c_sd / math.sqrt(max(1, c_n))

        base_eff = base_design.contrast(cls, feat) if base_design is not None else None
        if base_eff is not None and base_eff[0] > NOISE_FLOOR:
            cb, cse = base_eff[0], base_eff[1]
            if unabsorbed_base > cb:
                cb = unabsorbed_base
                cse = max(cse, base_drift_se)
            in_baseline = True
        elif cls in base_stats:
            cb, cse = unabsorbed_base, base_drift_se
            in_baseline = True
        else:
            cb, cse = 0.0, 0.0
            in_baseline = False

        excess = b - cb
        if excess <= max(NOISE_FLOOR, 2.0 * cse):
            continue
        combined_se = math.sqrt(se * se + cse * cse)
        z = -NormalDist().inv_cdf(max(p_adj, 1e-300))

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
                "p_value": p_adj,
                "bonferroni_z": round(z_crit, 4),
                "feature_episodes": active_n,
                "usable_episodes": len(usable),
                "in_baseline": in_baseline,
                "first_suspect": suspect,
                "candidate_jars": candidate_jars,
                "attribution_status": "unconfirmed_passive_correlation",
            }
        )

    # Sort by smallest p-value first; prefer specific `arm:` features and concrete jar suspects on ties
    def _sort_key(item: dict[str, Any]) -> tuple[float, int, int, str, str]:
        f_str = str(item["feature"])
        specificity = 0 if f_str.startswith("arm:") else (1 if f_str.startswith("node:") else 2)
        has_jar = 0 if str(item.get("first_suspect") or "server") not in ("server", "ambiguous") else 1
        return (float(item["p_value"]), specificity, has_jar, str(item["cls"]), f_str)

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


# --- Callback-driven numeric bisection & dependency-closed jar ddmin (ADR-125 2.4) ---


def _select_numeric_arg_node(
    node: CommandNode | str,
    tree: CommandTree | None = None,
) -> tuple[CommandNode, int]:
    """Return `(numeric_arg_node, token_index_in_cmd)` for `node`."""
    chain = _resolve_node_chain(node, tree)
    numeric_kinds = ("integer", "long", "int", "float", "double")
    best_node = chain[-1]
    best_idx = max(1, len(chain) - 1)

    tok_pos = 0
    for idx, step_node in enumerate(chain):
        p_kind = normalize_parser_kind(step_node.parser) if step_node.kind == "argument" else "literal"
        if idx > 0 and step_node.kind == "argument" and p_kind in numeric_kinds:
            best_node = step_node
            best_idx = tok_pos
        if p_kind in ("block_pos", "vec3"):
            tok_pos += 3
        elif p_kind in ("vec2", "column_pos", "rotation"):
            tok_pos += 2
        else:
            tok_pos += 1

    if best_node.kind != "argument" and tree is not None:
        for cid in best_node.children:
            ch = tree.nodes.get(cid)
            if ch is not None and ch.kind == "argument":
                best_node = ch
                best_idx = max(1, tok_pos)
                break
    return best_node, best_idx


def _parse_numeric_token(tok: str) -> float | None:
    try:
        val = float(tok)
    except (ValueError, TypeError):
        return None
    if not math.isfinite(val):
        return None
    return val


def _clone_program_structure(program: list[dict[str, Any]]) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for item in program or ():
        if not isinstance(item, dict):
            continue
        copied = dict(item)
        if isinstance(copied.get("program"), list):
            copied["program"] = [dict(a) for a in copied["program"] if isinstance(a, dict)]
        out.append(copied)
    return out


def bisect_numeric_arg(
    program: list[dict[str, Any]],
    node: CommandNode | str,
    predicate_fn: Callable[[list[dict[str, Any]]], bool],
    *,
    denylist: Denylist | Iterable[str] | None = None,
    passing_value: int | float | None = None,
    tree: CommandTree | None = None,
) -> tuple[list[dict[str, Any]], str | None]:
    """Binary-search a numeric argument token in `program` to find the minimal failing threshold.

    Uses an injected pure `predicate_fn(candidate_program) -> bool` callback and validates
    every candidate command against `Denylist` before probing (ADR-125 section 2.4).
    """
    base_prog = _clone_program_structure(program)
    if not base_prog:
        return base_prog, None

    active_deny: Denylist
    if isinstance(denylist, Denylist):
        active_deny = denylist
    elif denylist is not None:
        active_deny = Denylist(denylist)
    elif tree is not None and tree.denylist_entries:
        active_deny = Denylist(tree.denylist_entries)
    else:
        active_deny = Denylist()

    arg_node, expected_tok_idx = _select_numeric_arg_node(node, tree)
    if arg_node.denied:
        return base_prog, None

    p_kind = normalize_parser_kind(arg_node.parser)
    is_float = p_kind in ("float", "double")
    param_name = (arg_node.name or "value").strip("<>") or "value"

    is_bot_list = isinstance(base_prog[0].get("program"), list)
    target_bot_idx = -1
    target_act_idx = -1
    target_tok_idx = -1
    leading_slash = False
    cmd_tokens: list[str] = []

    def _iter_actions() -> list[tuple[int, int, dict[str, Any]]]:
        found: list[tuple[int, int, dict[str, Any]]] = []
        if is_bot_list:
            for b_i, b_dict in enumerate(base_prog):
                for a_i, act in enumerate(b_dict.get("program") or ()):
                    if isinstance(act, dict) and act.get("a") == "cmd" and isinstance(act.get("cmd"), str):
                        found.append((b_i, a_i, act))
        else:
            for a_i, act in enumerate(base_prog):
                if isinstance(act, dict) and act.get("a") == "cmd" and isinstance(act.get("cmd"), str):
                    found.append((0, a_i, act))
        return found

    for b_i, a_i, act in _iter_actions():
        raw_cmd = str(act.get("cmd") or "").strip()
        if not raw_cmd:
            continue
        has_slash = raw_cmd.startswith("/")
        clean_cmd = raw_cmd[1:].strip() if has_slash else raw_cmd
        toks = [t for t in clean_cmd.split() if t]
        if len(toks) < 2:
            continue
        chosen_t_idx = -1
        if 0 < expected_tok_idx < len(toks) and _parse_numeric_token(toks[expected_tok_idx]) is not None:
            chosen_t_idx = expected_tok_idx
        else:
            for t_i in range(len(toks) - 1, 0, -1):
                if _parse_numeric_token(toks[t_i]) is not None:
                    chosen_t_idx = t_i
                    break
        if chosen_t_idx > 0:
            target_bot_idx = b_i
            target_act_idx = a_i
            target_tok_idx = chosen_t_idx
            leading_slash = has_slash
            cmd_tokens = toks
            break

    if target_act_idx < 0 or target_tok_idx <= 0:
        return base_prog, None

    parsed_fail = _parse_numeric_token(cmd_tokens[target_tok_idx])
    if parsed_fail is None:
        return base_prog, None

    def _format_val(v: int | float) -> str:
        if is_float:
            vf = round(float(v), 4)
            return f"{vf:.1f}" if vf.is_integer() else f"{vf:g}"
        return str(int(round(float(v))))

    def _build_candidate(v: int | float) -> tuple[list[dict[str, Any]], str]:
        cand_prog = _clone_program_structure(base_prog)
        formatted_v = _format_val(v)
        new_toks = list(cmd_tokens)
        new_toks[target_tok_idx] = formatted_v
        new_cmd = ("/" if leading_slash else "") + " ".join(new_toks)
        if is_bot_list:
            cand_prog[target_bot_idx]["program"][target_act_idx]["cmd"] = new_cmd
            for b_dict in cand_prog:
                for act in b_dict.get("program") or ():
                    if isinstance(act, dict) and act.get("a") == "cmd" and isinstance(act.get("cmd"), str):
                        rc = act["cmd"].strip()
                        hs = rc.startswith("/")
                        ct = [t for t in (rc[1:].strip() if hs else rc).split() if t]
                        if len(ct) > target_tok_idx and ct[0].lower() == cmd_tokens[0].lower() and _parse_numeric_token(ct[target_tok_idx]) is not None:
                            ct[target_tok_idx] = formatted_v
                            act["cmd"] = ("/" if hs else "") + " ".join(ct)
        else:
            cand_prog[target_act_idx]["cmd"] = new_cmd
            for act in cand_prog:
                if isinstance(act, dict) and act.get("a") == "cmd" and isinstance(act.get("cmd"), str):
                    rc = act["cmd"].strip()
                    hs = rc.startswith("/")
                    ct = [t for t in (rc[1:].strip() if hs else rc).split() if t]
                    if len(ct) > target_tok_idx and ct[0].lower() == cmd_tokens[0].lower() and _parse_numeric_token(ct[target_tok_idx]) is not None:
                        ct[target_tok_idx] = formatted_v
                        act["cmd"] = ("/" if hs else "") + " ".join(ct)
        return cand_prog, new_cmd

    orig_cmd = ("/" if leading_slash else "") + " ".join(cmd_tokens)
    if active_deny.is_denied_command_line(orig_cmd, tree):
        return base_prog, None

    props = arg_node.properties or {}
    if is_float:
        fail_f = float(parsed_fail)
        lo_f = float(props.get("min", min(0.0, fail_f)))
        hi_f = float(props.get("max", max(fail_f, lo_f)))
        if passing_value is not None:
            pass_f = float(passing_value)
        elif lo_f <= 1.0 <= fail_f:
            pass_f = max(lo_f, 1.0 if lo_f >= 1.0 else 0.0)
        else:
            pass_f = lo_f
        if abs(fail_f - pass_f) <= 1e-9:
            return base_prog, f"{param_name} >= {_format_val(fail_f)}"

        def _probe_f(v: float) -> tuple[bool, list[dict[str, Any]]]:
            c_prog, c_cmd = _build_candidate(v)
            if active_deny.is_denied_command_line(c_cmd, tree):
                return False, c_prog
            return bool(predicate_fn(c_prog)), c_prog

        pass_ok, pass_prog_f = _probe_f(pass_f)
        if pass_ok:
            return pass_prog_f, f"{param_name} >= {_format_val(pass_f)}"

        low_f, high_f = min(pass_f, fail_f), max(pass_f, fail_f)
        best_f = fail_f
        best_prog_f = base_prog
        for _ in range(16):
            mid_f = round((low_f + high_f) / 2.0, 4)
            if abs(mid_f - low_f) <= 1e-6 or abs(mid_f - high_f) <= 1e-6:
                break
            ok_f, c_prog_f = _probe_f(mid_f)
            if fail_f >= pass_f:
                if ok_f:
                    best_f = mid_f
                    best_prog_f = c_prog_f
                    high_f = mid_f
                else:
                    low_f = mid_f
            else:
                if ok_f:
                    best_f = mid_f
                    best_prog_f = c_prog_f
                    low_f = mid_f
                else:
                    high_f = mid_f
        op_str = ">=" if fail_f >= pass_f else "<="
        return best_prog_f, f"{param_name} {op_str} {_format_val(best_f)}"

    fail_i = int(round(parsed_fail))
    lo_i = int(props.get("min", min(0, fail_i)))
    hi_i = int(props.get("max", max(fail_i, lo_i)))
    if passing_value is not None:
        pass_i = int(round(float(passing_value)))
    elif lo_i <= 1 <= fail_i:
        pass_i = max(lo_i, 1 if lo_i >= 1 else 0)
    else:
        pass_i = lo_i

    if fail_i == pass_i:
        return base_prog, f"{param_name} >= {fail_i}"

    memo_i: dict[int, tuple[bool, bool, list[dict[str, Any]]]] = {}

    def _eval_int(v: int) -> tuple[bool, bool, list[dict[str, Any]]]:
        if v not in memo_i:
            c_prog, c_cmd = _build_candidate(v)
            denied_flag = active_deny.is_denied_command_line(c_cmd, tree)
            if denied_flag:
                memo_i[v] = (False, True, c_prog)
            else:
                memo_i[v] = (bool(predicate_fn(c_prog)), False, c_prog)
        return memo_i[v]

    pass_fails, pass_denied, pass_prog = _eval_int(pass_i)
    if pass_fails and not pass_denied:
        return pass_prog, f"{param_name} >= {pass_i}"

    if fail_i > pass_i:
        low_i = pass_i + 1
        high_i = fail_i
        best_i = fail_i
        best_prog_i, _ = _build_candidate(fail_i)
        while low_i <= high_i:
            mid_i = (low_i + high_i) // 2
            probe_val = mid_i
            fails, is_den, c_prog = _eval_int(probe_val)
            if is_den:
                # Find nearest non-denied integer in [low_i, high_i]
                alt_val: int | None = None
                for offset in range(1, (high_i - low_i) + 1):
                    for cand_v in (mid_i + offset, mid_i - offset):
                        if low_i <= cand_v <= high_i:
                            _, cand_den, _ = _eval_int(cand_v)
                            if not cand_den:
                                alt_val = cand_v
                                break
                    if alt_val is not None:
                        break
                if alt_val is None:
                    break
                probe_val = alt_val
                fails, _, c_prog = _eval_int(probe_val)

            if fails:
                best_i = probe_val
                best_prog_i = c_prog
                high_i = probe_val - 1
            else:
                low_i = probe_val + 1
        return best_prog_i, f"{param_name} >= {best_i}"

    # Symmetric case: fail_i < pass_i
    low_i = fail_i
    high_i = pass_i - 1
    best_i = fail_i
    best_prog_i, _ = _build_candidate(fail_i)
    while low_i <= high_i:
        mid_i = (low_i + high_i + 1) // 2
        fails, is_den, c_prog = _eval_int(mid_i)
        if is_den:
            high_i = mid_i - 1
            continue
        if fails:
            best_i = mid_i
            best_prog_i = c_prog
            low_i = mid_i + 1
        else:
            high_i = mid_i - 1
    return best_prog_i, f"{param_name} <= {best_i}"


def _normalize_jar_probe_result(
    res: Any,
    finding_k: int,
) -> tuple[int, bool, bool]:
    """Normalize a `probe_fn(active_jars)` return value to `(hits, all_loaded, reproduced)`."""
    if isinstance(res, dict):
        hits = int(res.get("hits", res.get("repro_count", 0)) or 0)
        all_loaded = bool(res.get("all_loaded", True))
    elif isinstance(res, tuple):
        if len(res) >= 3:
            hits = int(res[0] or 0)
            all_loaded = bool(res[2])
        elif len(res) == 2:
            hits = int(res[0] or 0)
            all_loaded = bool(res[1])
        elif len(res) == 1:
            hits = int(res[0] or 0)
            all_loaded = True
        else:
            hits, all_loaded = 0, False
    elif isinstance(res, bool):
        hits = int(finding_k) if res else 0
        all_loaded = True
    elif isinstance(res, (int, float)):
        hits = int(res)
        all_loaded = True
    else:
        hits, all_loaded = 0, False

    reproduced = bool(all_loaded) and (hits >= max(1, int(finding_k)))
    return hits, all_loaded, reproduced


def run_pre_ddmin_probes(
    candidate_jars: Iterable[str],
    probe_fn: Callable[[list[str]], tuple[int, bool] | int],
    *,
    finding_k: int = 3,
    finding_n: int = 3,
) -> dict[str, Any]:
    """Run the two mandatory pre-`ddmin` guardrails (ADR-125 section 2.4).

    1. Full candidate set (`list(candidate_jars)`): if `< finding_k` reproductions or a jar
       failed to load, reports `not_reproducible_after_restart` and does not attribute.
    2. Prerequisites only (`[]`): if `>= finding_k` reproductions with all prerequisites
       loaded, labels the finding `server` and skips jar `ddmin` so no plugin/mod jar is blamed.
    """
    cands = [str(j).strip() for j in candidate_jars if str(j).strip()]
    k_req = max(1, int(finding_k))
    n_req = max(k_req, int(finding_n))

    full_res = probe_fn(list(cands))
    full_hits, full_loaded, full_repro = _normalize_jar_probe_result(full_res, k_req)
    if not full_repro:
        return {
            "status": "not_reproducible_after_restart",
            "attribution_status": "not_reproducible_after_restart",
            "full_hits": full_hits,
            "full_all_loaded": full_loaded,
            "prereq_hits": 0,
            "prereq_all_loaded": True,
            "finding_k": k_req,
            "finding_n": n_req,
            "probes_run": 1,
            "blamed_jars": [],
        }

    prereq_res = probe_fn([])
    prereq_hits, prereq_loaded, prereq_repro = _normalize_jar_probe_result(prereq_res, k_req)
    if prereq_repro:
        return {
            "status": "server",
            "attribution_status": "server",
            "full_hits": full_hits,
            "full_all_loaded": full_loaded,
            "prereq_hits": prereq_hits,
            "prereq_all_loaded": prereq_loaded,
            "finding_k": k_req,
            "finding_n": n_req,
            "probes_run": 2,
            "blamed_jars": [],
        }

    return {
        "status": "proceed_to_ddmin",
        "attribution_status": "proceed_to_ddmin",
        "full_hits": full_hits,
        "full_all_loaded": full_loaded,
        "prereq_hits": prereq_hits,
        "prereq_all_loaded": prereq_loaded,
        "finding_k": k_req,
        "finding_n": n_req,
        "probes_run": 2,
        "blamed_jars": [],
    }


def ddmin_jars(
    candidate_jars: Iterable[str],
    jar_index: JarIndex | None,
    probe_fn: Callable[[list[str]], tuple[int, bool] | int],
    *,
    finding_k: int = 3,
    finding_n: int = 3,
    max_probes: int = 32,
    first_suspect: str | None = None,
) -> dict[str, Any]:
    """Isolate the minimal reproducing jar subset via dependency-closed `ddmin` (ADR-125 section 2.4).

    Runs `run_pre_ddmin_probes` first, closes every candidate subset under `jar_index.close_deps`,
    treats subsets where `all_loaded=False` as unresolved (non-reproducing), and falls back to
    `INCONCLUSIVE` / `unconfirmed` (`first_suspect`) if `max_probes` is exhausted before isolation.
    """
    cands = list(dict.fromkeys(str(j).strip() for j in candidate_jars if str(j).strip()))
    k_req = max(1, int(finding_k))
    n_req = max(k_req, int(finding_n))
    max_p = max(2, int(max_probes))

    pre = run_pre_ddmin_probes(cands, probe_fn, finding_k=k_req, finding_n=n_req)
    if pre["status"] == "not_reproducible_after_restart":
        return {
            **pre,
            "verdict": "INCONCLUSIVE",
            "first_suspect": first_suspect,
            "budget_exhausted": False,
            "dependency_closed_jars": [],
        }
    if pre["status"] == "server":
        return {
            **pre,
            "verdict": "PASS",
            "first_suspect": "server",
            "budget_exhausted": False,
            "dependency_closed_jars": [],
        }

    if not cands:
        return {
            **pre,
            "status": "unconfirmed",
            "attribution_status": "unconfirmed",
            "verdict": "INCONCLUSIVE",
            "first_suspect": first_suspect,
            "budget_exhausted": False,
            "dependency_closed_jars": [],
        }

    def _close_subset(sub: Iterable[str]) -> list[str]:
        if jar_index is not None:
            closed_set = set(jar_index.close_deps(sub))
        else:
            closed_set = {str(s).strip() for s in sub if str(s).strip()}
        ordered = [j for j in cands if j in closed_set]
        for extra in sorted(closed_set):
            if extra not in ordered:
                ordered.append(extra)
        return ordered

    if len(cands) == 1:
        closed_single = _close_subset(cands)
        return {
            **pre,
            "status": "confirmed",
            "attribution_status": "confirmed",
            "verdict": "FAIL",
            "blamed_jars": list(cands),
            "dependency_closed_jars": closed_single,
            "first_suspect": first_suspect or cands[0],
            "budget_exhausted": False,
        }

    full_closed = _close_subset(cands)
    memo: dict[tuple[str, ...], tuple[int, bool, bool]] = {
        tuple(sorted(full_closed)): (pre["full_hits"], pre["full_all_loaded"], True),
        (): (pre["prereq_hits"], pre["prereq_all_loaded"], False),
    }
    probes_used = int(pre["probes_run"])
    budget_exhausted = False

    def _test_subset(sub: list[str]) -> bool:
        nonlocal probes_used, budget_exhausted
        if not sub:
            return False
        closed = _close_subset(sub)
        key = tuple(sorted(closed))
        if key in memo:
            return memo[key][2]
        if probes_used >= max_p:
            budget_exhausted = True
            return False
        probes_used += 1
        res = probe_fn(list(closed))
        hits, all_loaded, passed = _normalize_jar_probe_result(res, k_req)
        memo[key] = (hits, all_loaded, passed)
        return passed

    minimal = bf.ddmin(list(cands), _test_subset)
    closed_minimal = _close_subset(minimal)

    if budget_exhausted:
        return {
            **pre,
            "status": "unconfirmed",
            "attribution_status": "unconfirmed",
            "verdict": "INCONCLUSIVE",
            "probes_run": probes_used,
            "blamed_jars": [],
            "candidate_subset": list(minimal),
            "dependency_closed_jars": closed_minimal,
            "first_suspect": first_suspect,
            "budget_exhausted": True,
        }

    return {
        **pre,
        "status": "confirmed",
        "attribution_status": "confirmed",
        "verdict": "FAIL",
        "probes_run": probes_used,
        "blamed_jars": list(minimal),
        "dependency_closed_jars": closed_minimal,
        "first_suspect": first_suspect or (minimal[0] if minimal else None),
        "budget_exhausted": False,
    }


def _invoke_optional_callback(fn: Callable[..., Any], primary_arg: Any, context: dict[str, Any]) -> Any:
    try:
        return fn(primary_arg, context)
    except TypeError:
        return fn(primary_arg)


def _resolve_node_for_finding(
    node: CommandNode | str | None,
    tree: CommandTree | None,
    program: list[dict[str, Any]] | None = None,
) -> tuple[CommandNode | None, CommandNode | None]:
    """Return `(mitigation_node, numeric_or_exec_node)` resolved against `tree`."""
    if isinstance(node, CommandNode):
        root_node = tree.nodes.get(node._root_id) if (tree and node._root_id) else None
        return (root_node or node), node

    if tree is None:
        if isinstance(node, str) and node.strip():
            nid = node.strip()
            synth = CommandNode(node_id=nid, name=nid.partition(" ")[0], kind="literal", aliases=[nid], min_tier="default")
            return synth, synth
        return None, None

    tree._ensure_steps()

    # Check if program contains an explicit `node` or `cmd` we can match in `tree`.
    prog_cmd_str: str | None = None
    prog_node_id: str | None = None
    for item in program or ():
        if not isinstance(item, dict):
            continue
        acts = item.get("program") if isinstance(item.get("program"), list) else [item]
        for act in acts:
            if isinstance(act, dict) and act.get("a") == "cmd":
                if isinstance(act.get("node"), str) and act["node"].strip():
                    prog_node_id = act["node"].strip()
                if isinstance(act.get("cmd"), str) and act["cmd"].strip():
                    prog_cmd_str = act["cmd"].strip()
                break
        if prog_node_id or prog_cmd_str:
            break

    raw_id = (str(node).strip() if isinstance(node, str) and str(node).strip() else None) or prog_node_id
    exec_node: CommandNode | None = None
    root_node: CommandNode | None = None

    if raw_id:
        if raw_id in tree.nodes:
            exec_node = tree.nodes[raw_id]
        else:
            r_tok = raw_id.partition(" ")[0]
            _, r_bare = split_namespace(r_tok)
            canon_r = tree.alias_map.get(r_tok) or tree.alias_map.get(r_bare) or r_tok
            rest = raw_id.partition(" ")[2].strip()
            cand_id = f"{canon_r} {rest}".strip() if rest else canon_r
            if cand_id in tree.nodes:
                exec_node = tree.nodes[cand_id]
            elif canon_r in tree.nodes:
                root_node = tree.nodes[canon_r]

    if exec_node is None and prog_cmd_str:
        clean_line = prog_cmd_str.lstrip("/").strip()
        toks = [t for t in clean_line.split() if t]
        if toks:
            r0 = toks[0]
            _, r0_bare = split_namespace(r0)
            canon_r0 = tree.alias_map.get(r0) or tree.alias_map.get(r0_bare) or r0
            if canon_r0 in tree.nodes:
                cur_n = tree.nodes[canon_r0]
                root_node = cur_n
                for tok in toks[1:]:
                    matched_ch: CommandNode | None = None
                    for cid in cur_n.children:
                        ch = tree.nodes.get(cid)
                        if ch is not None and ch.kind == "literal" and ch.name.lower() == tok.lower():
                            matched_ch = ch
                            break
                    if matched_ch is None:
                        for cid in cur_n.children:
                            ch = tree.nodes.get(cid)
                            if ch is not None and ch.kind == "argument":
                                matched_ch = ch
                                break
                    if matched_ch is None:
                        break
                    cur_n = matched_ch
                exec_node = cur_n

    if exec_node is not None and root_node is None:
        r_id = exec_node._root_id or exec_node.node_id.partition(" ")[0]
        root_node = tree.nodes.get(r_id) or exec_node

    if exec_node is not None and exec_node.kind != "argument":
        for cid in exec_node.children:
            ch = tree.nodes.get(cid)
            if ch is not None and ch.kind == "argument":
                return (root_node or exec_node), ch

    return (root_node or exec_node), (exec_node or root_node)


def build_finding_verdict(
    *,
    oracle: str,
    signature: str,
    node: CommandNode | str | None = None,
    tree: CommandTree | None = None,
    follow_up: str | None = None,
    evidence: str = "",
    first_suspect: str | None = None,
    attribution_status: str = "unconfirmed",
    blamed_jars: Iterable[str] = (),
    shrunk_program: list[dict[str, Any]] | None = None,
    bisected_bound: str | None = None,
    k: int = 0,
    n: int = 0,
    server_dir: str = "<server_dir>",
    profile: str = "paper",
    replay_file: str = "episodes.jsonl",
    ep: int | str = 0,
) -> dict[str, Any]:
    """Construct a structured finding verdict with `operator_mitigation` and `author_reproducer` (ADR-125 2.4)."""
    mitigation_node, exec_node = _resolve_node_for_finding(node, tree, shrunk_program)
    target_node = exec_node or mitigation_node

    if target_node is not None:
        canonical_node = mitigation_node.node_id if mitigation_node is not None else target_node.node_id
        aliases: list[str] = []
        for src in (mitigation_node, target_node):
            if src is None:
                continue
            for a in (src.aliases or [src.node_id]):
                if a and a not in aliases:
                    aliases.append(a)
        min_tier = target_node.min_tier or (mitigation_node.min_tier if mitigation_node else "default") or "default"
    else:
        raw_node_str = str(node).strip() if isinstance(node, str) and str(node).strip() else "unknown"
        canonical_node = raw_node_str
        aliases = [raw_node_str] if raw_node_str != "unknown" else []
        min_tier = "default"

    blamed_list = [str(j).strip() for j in (blamed_jars or ()) if str(j).strip()]
    suspect = str(first_suspect).strip() if first_suspect else (blamed_list[0] if blamed_list else "server")
    prog_copy = _clone_program_structure(shrunk_program or [])
    k_val = max(0, int(k))
    n_val = max(k_val, int(n))
    replay_cmd = (
        f"python3 scripts/bench_fuzz.py --mode server --profile {profile} "
        f"--server-dir {server_dir} --replay {replay_file} --ep {ep}"
    )

    return {
        "oracle": str(oracle),
        "signature": str(signature),
        "attribution_status": str(attribution_status),
        "blamed_jars": blamed_list,
        "first_suspect": suspect,
        "reproduced": bool(k_val > 0 and k_val >= n_val) or attribution_status in ("confirmed", "server"),
        "k": k_val,
        "n": n_val,
        "operator_mitigation": {
            "canonical_node": canonical_node,
            "aliases": aliases,
            "min_tier": min_tier,
            "bisected_bound": bisected_bound,
            "follow_up": follow_up,
        },
        "author_reproducer": {
            "oracle": str(oracle),
            "attribution_status": str(attribution_status),
            "blamed_jars": blamed_list,
            "first_suspect": suspect,
            "evidence": str(evidence),
            "shrunk_program": prog_copy,
            "reproduction": {"k": k_val, "n": n_val},
            "replay_cmd": replay_cmd,
        },
    }


# --- Declarative BIT Catalog, Tables & Survival Envelope (ADR-125) -----------

BENCH_NAMESPACE: str = "BENCH"

BENCH_BIT_CODES: dict[int, dict[str, Any]] = {
    5001: {
        "id": 5001,
        "ns": BENCH_NAMESPACE,
        "code": "BENCH_CMD_IMPACT",
        "severity": "INFO",
        "tier": "CBIT",
        "component": "bench.surface",
        "side_effect": "NONE",
        "remediation": "Inspect observed command tick gap and post-GC retention metrics across tested scenario shapes.",
    },
    5002: {
        "id": 5002,
        "ns": BENCH_NAMESPACE,
        "code": "BENCH_CMD_HITCH_SUBTHRESHOLD",
        "severity": "WARNING",
        "tier": "CBIT",
        "component": "bench.surface",
        "side_effect": "NONE",
        "remediation": "Command exceeded 1.5x baseline p99 tick gap without crossing the hard stall failure threshold.",
    },
    5003: {
        "id": 5003,
        "ns": BENCH_NAMESPACE,
        "code": "BENCH_MAIN_THREAD_FRAME",
        "severity": "INFO",
        "tier": "CBIT",
        "component": "bench.frames",
        "side_effect": "NONE",
        "remediation": "Review main-thread stack frame share captured during elevated tick-gap intervals.",
    },
    5101: {
        "id": 5101,
        "ns": BENCH_NAMESPACE,
        "code": "BENCH_STALL_ACUTE",
        "severity": "ERROR",
        "tier": "IBIT",
        "component": "bench.attribution",
        "side_effect": "WORLD_SAFE",
        "remediation": "Restrict command min_tier, clamp argument to pass_domain, or disable blamed_jars.",
    },
    5102: {
        "id": 5102,
        "ns": BENCH_NAMESPACE,
        "code": "BENCH_STALL_SUSTAINED",
        "severity": "ERROR",
        "tier": "IBIT",
        "component": "bench.attribution",
        "side_effect": "WORLD_SAFE",
        "remediation": "Throttle repeated/concurrent command execution or disable blamed_jars.",
    },
    5103: {
        "id": 5103,
        "ns": BENCH_NAMESPACE,
        "code": "BENCH_STALL_UNISOLATED",
        "severity": "WARNING",
        "tier": "IBIT",
        "component": "bench.attribution",
        "side_effect": "WORLD_SAFE",
        "remediation": "Tick-gap stall observed during exploration; rerun with server restart enabled to complete jar ddmin.",
    },
    5201: {
        "id": 5201,
        "ns": BENCH_NAMESPACE,
        "code": "BENCH_LEAK_POST_GC",
        "severity": "ERROR",
        "tier": "IBIT",
        "component": "bench.attribution",
        "side_effect": "WORLD_SAFE",
        "remediation": "Disable blamed_jars or restrict the trigger command/departure path retaining objects after full GC.",
    },
    5202: {
        "id": 5202,
        "ns": BENCH_NAMESPACE,
        "code": "BENCH_LEAK_UNISOLATED",
        "severity": "WARNING",
        "tier": "IBIT",
        "component": "bench.attribution",
        "side_effect": "WORLD_SAFE",
        "remediation": "Post-GC class retention slope observed; rerun with higher episode budget or restart enabled to isolate.",
    },
    5301: {
        "id": 5301,
        "ns": BENCH_NAMESPACE,
        "code": "BENCH_SURVIVAL_RAMP_PASS",
        "severity": "INFO",
        "tier": "IBIT",
        "component": "bench.ramp",
        "side_effect": "WORLD_SAFE",
        "remediation": "Concurrency rung satisfied tick-gap, disconnect, and post-GC retention invariants.",
    },
    5302: {
        "id": 5302,
        "ns": BENCH_NAMESPACE,
        "code": "BENCH_SURVIVAL_RAMP_DEGRADED",
        "severity": "WARNING",
        "tier": "IBIT",
        "component": "bench.ramp",
        "side_effect": "WORLD_SAFE",
        "remediation": "Concurrency rung experienced elevated p95 tick gap without acute stall or disconnect failure.",
    },
    5303: {
        "id": 5303,
        "ns": BENCH_NAMESPACE,
        "code": "BENCH_SURVIVAL_RAMP_FAIL",
        "severity": "ERROR",
        "tier": "IBIT",
        "component": "bench.ramp",
        "side_effect": "WORLD_SAFE",
        "remediation": "Concurrency rung violated survival SLO invariants; inspect bench.attribution counterfactuals.",
    },
}

BIT_TABLE_COLUMNS: tuple[str, ...] = (
    "component",
    "tier",
    "sideEffect",
    "health",
    "E",
    "W",
    "severity",
    "id",
    "code",
    "source",
    "path",
    "message",
    "expected",
    "suggestion",
)

_LINE_BREAK_RE = re.compile(r"[\r\n]+")


def _bit_single_line(value: Any) -> str:
    return _LINE_BREAK_RE.sub(" ", str(value if value is not None else ""))


def _bit_csv_cell(value: Any) -> str:
    """Format one CSV cell matching `io.github.dailystruggle.bit.BitTable.csvCell`."""
    s = _bit_single_line(value)
    quote = (
        "," in s
        or '"' in s
        or (bool(s) and (s[0].isspace() or s[-1].isspace()))
    )
    if not quote:
        return s
    return '"' + s.replace('"', '""') + '"'


def render_bit_table_csv(
    rows: Iterable[dict[str, Any]],
    columns: Iterable[str] = BIT_TABLE_COLUMNS,
) -> str:
    """Render an aligned CSV table matching `BitTable.render` (padding after `, ` delimiters)."""
    col_list = [str(c) for c in columns]
    row_list = list(rows or ())
    widths = [len(_bit_csv_cell(c)) for c in col_list]
    for r in row_list:
        for i, col in enumerate(col_list):
            cell_str = _bit_csv_cell(r.get(col, ""))
            if len(cell_str) > widths[i]:
                widths[i] = len(cell_str)

    def _fmt_row(src: dict[str, Any] | None) -> str:
        parts: list[str] = []
        for i, col in enumerate(col_list):
            cell_str = _bit_csv_cell(col if src is None else src.get(col, ""))
            pad = " " * max(0, widths[i] - len(cell_str))
            parts.append(pad + cell_str)
        return ", ".join(parts)

    out_lines = [_fmt_row(None)]
    for r in row_list:
        out_lines.append(_fmt_row(r))
    return "\n".join(out_lines) + "\n"


def _compact_program_sequence(shrunk_program: Iterable[dict[str, Any]] | None) -> list[str]:
    """Summarize a shrunk bot program into declarative action tokens (e.g. `['cmd: rtp', 'wait: 200ms', 'quit']`)."""
    seq: list[str] = []
    for item in shrunk_program or ():
        if not isinstance(item, dict):
            continue
        acts = item.get("program") if isinstance(item.get("program"), list) else [item]
        for act in acts:
            if not isinstance(act, dict):
                continue
            kind = str(act.get("a") or "")
            if kind == "cmd":
                seq.append(f"cmd: {act.get('cmd') or act.get('node') or ''}".strip())
            elif kind == "wait":
                seq.append(f"wait: {int(act.get('ms', 0) or 0)}ms")
            elif kind == "move":
                seq.append(
                    f"move: dx={act.get('dx', 0)},dz={act.get('dz', 0)},{int(act.get('ms', 0) or 0)}ms"
                )
            elif kind == "click":
                seq.append(f"click: slot={int(act.get('slot', 0) or 0)}")
            elif kind == "rejoin":
                seq.append("rejoin(rename)" if act.get("rename") else "rejoin")
            elif kind:
                seq.append(kind)
    return seq


def derive_parameter_boundary(
    bisected_bound: str | None,
    exec_node: CommandNode | None = None,
) -> dict[str, Any] | None:
    """Derive declarative `pass_domain` and `fail_domain` from `bisected_bound` and `exec_node` properties."""
    if not bisected_bound:
        return None
    m = re.match(r"^\s*([A-Za-z0-9_.-]+)\s*(>=|<=)\s*([-+]?\d+(?:\.\d+)?)\s*$", str(bisected_bound))
    if not m:
        return {
            "argument": exec_node.name if exec_node is not None else "arg",
            "bisected_bound": str(bisected_bound),
            "pass_domain": "unknown",
            "fail_domain": str(bisected_bound),
        }
    arg_name, op_str, thresh_raw = m.group(1), m.group(2), m.group(3)
    props = (exec_node.properties if exec_node is not None and isinstance(exec_node.properties, dict) else {}) or {}
    is_int = "." not in thresh_raw and (
        exec_node is None or exec_node.parser in (None, "brigadier:integer", "brigadier:long")
    )
    if is_int:
        t_val = int(round(float(thresh_raw)))
        lo_val = int(props["min"]) if "min" in props and props["min"] is not None else None
        hi_val = int(props["max"]) if "max" in props and props["max"] is not None else None
        if op_str == ">=":
            fail_dom = f"[{t_val}, {hi_val}]" if hi_val is not None and hi_val >= t_val else f">= {t_val}"
            if lo_val is not None:
                pass_dom = f"[{lo_val}, {t_val - 1}]" if t_val - 1 >= lo_val else "none"
            else:
                pass_dom = f"<= {t_val - 1}"
        else:
            fail_dom = f"[{lo_val}, {t_val}]" if lo_val is not None and lo_val <= t_val else f"<= {t_val}"
            if hi_val is not None:
                pass_dom = f"[{t_val + 1}, {hi_val}]" if hi_val >= t_val + 1 else "none"
            else:
                pass_dom = f">= {t_val + 1}"
    else:
        t_f = float(thresh_raw)
        lo_f = float(props["min"]) if "min" in props and props["min"] is not None else None
        hi_f = float(props["max"]) if "max" in props and props["max"] is not None else None
        if op_str == ">=":
            fail_dom = f"[{t_f:g}, {hi_f:g}]" if hi_f is not None and hi_f >= t_f else f">= {t_f:g}"
            pass_dom = f"[{lo_f:g}, <{t_f:g})" if lo_f is not None and t_f > lo_f else ("none" if lo_f is not None else f"< {t_f:g}")
        else:
            fail_dom = f"[{lo_f:g}, {t_f:g}]" if lo_f is not None and lo_f <= t_f else f"<= {t_f:g}"
            pass_dom = f"(>{t_f:g}, {hi_f:g}]" if hi_f is not None and hi_f > t_f else ("none" if hi_f is not None else f"> {t_f:g}")

    return {
        "argument": arg_name,
        "bisected_bound": str(bisected_bound),
        "pass_domain": pass_dom,
        "fail_domain": fail_dom,
    }


def summarize_main_thread_attribution(
    records: Iterable[dict[str, Any]],
    *,
    profile: PlatformProfile | str = "paper",
    jar_index: JarIndex | None = None,
) -> list[dict[str, Any]]:
    """Aggregate captured `jcmd Thread.print` main-thread stack frames into a frequency table."""
    prof = profile if isinstance(profile, PlatformProfile) else get_platform_profile(profile)
    counts: dict[tuple[str, str], int] = {}
    total = 0
    for r in records or ():
        if not isinstance(r, dict):
            continue
        for td in r.get("thread_dumps") or ():
            if not isinstance(td, str) or not td.strip():
                continue
            parsed = parse_main_thread_frame(td, profile=prof, jar_index=jar_index)
            frame = str(parsed.get("culprit_frame") or parsed.get("top_frame") or "").strip()
            if not frame:
                frames = parsed.get("frames") or []
                frame = str(frames[0]) if frames else "unknown"
            owner = str(parsed.get("first_suspect") or "server").strip() or "server"
            counts[(owner, frame)] = counts.get((owner, frame), 0) + 1
            total += 1

    if total == 0:
        return []

    rows: list[dict[str, Any]] = []
    for (owner, frame), cnt in sorted(counts.items(), key=lambda kv: (-kv[1], kv[0][0], kv[0][1])):
        rows.append(
            {
                "owner_jar": owner,
                "top_frame": frame,
                "samples": cnt,
                "total_samples": total,
                "share_pct": round(100.0 * cnt / total, 1),
            }
        )
    return rows


def build_command_impact_table(
    records: Iterable[dict[str, Any]],
    *,
    baseline_records: Iterable[dict[str, Any]] = (),
    tree: CommandTree | None = None,
    jar_index: JarIndex | None = None,
    profile: PlatformProfile | str = "paper",
    findings: Iterable[dict[str, Any]] = (),
    baseline_p99_ms: float = 1000.0,
    stall_ms: float = 2000.0,
) -> list[dict[str, Any]]:
    """Build a declarative impact leaderboard across all tested `(tier, command, shape)` arms."""
    prof = profile if isinstance(profile, PlatformProfile) else get_platform_profile(profile)
    base_max_delta: dict[str, float] = {}
    for br in baseline_records or ():
        if isinstance(br, dict):
            for k_cls, v_delta in (br.get("deltas") or {}).items():
                f_val = float(v_delta or 0.0)
                if f_val > base_max_delta.get(k_cls, 0.0):
                    base_max_delta[k_cls] = f_val
    cmd_to_jar: dict[str, str] = {}
    if jar_index is not None:
        for j_name, meta in jar_index.jars.items():
            p_ns = (meta.plugin_id or "").lower()
            for c_name in meta.commands:
                cl = c_name.lower()
                cmd_to_jar.setdefault(cl, j_name)
                if p_ns:
                    cmd_to_jar.setdefault(f"{p_ns}:{cl}", j_name)
            for al in meta.command_aliases:
                al_l = al.lower()
                cmd_to_jar.setdefault(al_l, j_name)
                if p_ns:
                    cmd_to_jar.setdefault(f"{p_ns}:{al_l}", j_name)

    finding_by_cmd: dict[str, list[dict[str, Any]]] = {}
    for f in findings or ():
        if not isinstance(f, dict):
            continue
        op_mit = f.get("operator_mitigation") or {}
        c_node = str(op_mit.get("canonical_node") or "").strip()
        if c_node:
            finding_by_cmd.setdefault(c_node, []).append(f)
            finding_by_cmd.setdefault(c_node.partition(" ")[0], []).append(f)
        for al in op_mit.get("aliases") or ():
            if al:
                finding_by_cmd.setdefault(str(al), []).append(f)

    groups: dict[tuple[str, str, str], list[dict[str, Any]]] = {}
    for r in records or ():
        if not isinstance(r, dict):
            continue
        node_str = str(r.get("node") or r.get("canonical_node") or "").strip()
        if not node_str:
            continue
        shape_str = str(r.get("follow_up") or "default").strip() or "default"
        node_obj = tree.nodes.get(node_str) if (tree is not None and node_str in tree.nodes) else None
        tier_str = (node_obj.min_tier if node_obj is not None and node_obj.min_tier else "default")
        groups.setdefault((tier_str, node_str, shape_str), []).append(r)

    cand_jars: set[str] = set()
    if jar_index is not None:
        cand_jars = {j for j in jar_index.jars if not prof.is_prerequisite_jar(j)}

    table: list[dict[str, Any]] = []
    for (tier_str, node_str, shape_str), g_recs in groups.items():
        all_gaps: list[dict[str, Any]] = []
        all_windows: list[Any] = []
        pos_retained_totals: list[float] = []
        owner_votes: dict[str, float] = {}
        for r in g_recs:
            r_windows = r.get("exclusion_windows") or ()
            kept_g, _ = filter_tick_gaps(r.get("tick_gaps") or (), r_windows)
            all_gaps.extend(kept_g)
            all_windows.extend(r_windows)
            ep_pos = 0.0
            for k_cls, v_delta in (r.get("deltas") or {}).items():
                val = float(v_delta or 0.0) - base_max_delta.get(k_cls, 0.0)
                if val <= 0.0:
                    continue
                c_clean = k_cls[len(CLS_PREFIX) :] if k_cls.startswith(CLS_PREFIX) else k_cls
                if "$$Lambda" in c_clean:
                    continue
                if jar_index is not None:
                    ow = jar_index.owner_of_class(c_clean, profile=prof)
                    if ow and (not cand_jars or ow in cand_jars):
                        ep_pos += val
                        owner_votes[ow] = owner_votes.get(ow, 0.0) + val
                else:
                    ep_pos += val
            pos_retained_totals.append(ep_pos)

        gap_summary = summarize_tick_gaps(all_gaps, ())
        p95_ms = round(float(gap_summary.get("p95_ms", 0.0) or 0.0), 1)
        max_ms = round(float(gap_summary.get("max_ms", 0.0) or 0.0), 1)
        mean_ret = round(sum(pos_retained_totals) / max(1, len(pos_retained_totals)), 1)

        root_tok = node_str.partition(" ")[0].lower()
        bare_tok = root_tok.split(":", 1)[1] if ":" in root_tok else root_tok
        matched_findings = finding_by_cmd.get(node_str) or finding_by_cmd.get(root_tok) or finding_by_cmd.get(bare_tok) or []

        owner_jar = cmd_to_jar.get(root_tok) or cmd_to_jar.get(bare_tok)
        if not owner_jar and matched_findings:
            f0 = matched_findings[0]
            bl = f0.get("blamed_jars") or []
            owner_jar = bl[0] if bl else f0.get("first_suspect")
        if not owner_jar and owner_votes:
            owner_jar = max(owner_votes.items(), key=lambda kv: (kv[1], kv[0]))[0]
        if not owner_jar:
            owner_jar = "server"

        status = "PASS"
        for mf in matched_findings:
            f_follow = (mf.get("operator_mitigation") or {}).get("follow_up")
            if f_follow in (None, "", shape_str) or len(groups) == 1 or mf.get("oracle") == "leak":
                bound = (mf.get("operator_mitigation") or {}).get("bisected_bound")
                oracle_tag = str(mf.get("oracle") or "fault")
                if mf.get("attribution_status") == "confirmed":
                    status = f"FAIL ({oracle_tag}{', ' + bound if bound else ''})"
                    break
                if mf.get("attribution_status") == "unconfirmed" and status == "PASS":
                    status = f"WARN ({oracle_tag})"
        if status == "PASS" and max_ms >= max(1500.0, baseline_p99_ms * 1.5) and max_ms < baseline_p99_ms + stall_ms:
            status = "WARN (hitch)"

        table.append(
            {
                "tier": tier_str,
                "command": node_str,
                "shape": shape_str,
                "episodes": len(g_recs),
                "p95_gap_ms": p95_ms,
                "max_gap_ms": max_ms,
                "retained_per_ep": mean_ret,
                "owner_jar": str(owner_jar),
                "status": status,
            }
        )

    def _impact_sort_key(row: dict[str, Any]) -> tuple[int, float, float, str, str]:
        st = str(row.get("status") or "PASS")
        rank = 0 if st.startswith("FAIL") else (1 if st.startswith("WARN") else 2)
        return (
            rank,
            -float(row.get("max_gap_ms", 0.0) or 0.0),
            -float(row.get("retained_per_ep", 0.0) or 0.0),
            str(row.get("command") or ""),
            str(row.get("shape") or ""),
        )

    table.sort(key=_impact_sort_key)
    return table


STANDARD_SURVIVAL_RUNGS: tuple[int, ...] = (10, 25, 50, 75, 100, 128)


def build_survival_ramp_rungs(
    target_players: int | None = None,
    explicit_rungs: str | Iterable[int] | None = None,
) -> list[int]:
    """Build an ascending list of concurrency rungs (`<= 128`) for the survival benchmark."""
    if explicit_rungs is not None:
        if isinstance(explicit_rungs, str):
            raw_parts = [p.strip() for p in explicit_rungs.split(",") if p.strip()]
            parsed = [int(p) for p in raw_parts]
        else:
            parsed = [int(p) for p in explicit_rungs]
        cleaned = sorted({p for p in parsed if 1 <= p <= 128})
        if not cleaned:
            raise ValueError(f"ramp rungs must contain at least one integer in [1, 128]; got {explicit_rungs!r}")
        return cleaned

    if target_players is None or int(target_players) <= 0:
        return []
    target = max(1, min(128, int(target_players)))
    rungs = [r for r in STANDARD_SURVIVAL_RUNGS if r < target]
    if not rungs and target > 2:
        rungs.append(max(2, target // 2))
    rungs.append(target)
    return sorted(set(rungs))


def build_ramp_wave_program(
    player_count: int,
    tree: CommandTree | None,
    rng: random.Random,
    *,
    denylist: Denylist | Iterable[str] | None = None,
    tiers: Iterable[str] = ("default",),
    bot_prefix: str = "fzr",
    preferred_node_ids: Iterable[str] | None = None,
) -> list[dict[str, Any]]:
    """Build a concurrent `player_count`-bot player-taxonomy wave for a survival ramp rung."""
    n_bots = max(1, min(128, int(player_count)))
    active_deny = denylist if isinstance(denylist, Denylist) else Denylist(denylist)
    allowed_nodes = (
        (tree.allowed_executable_nodes(tiers) or tree.allowed_executable_nodes(None))
        if tree is not None
        else []
    )
    default_nodes = [n for n in allowed_nodes if (n.min_tier or "default") == "default"] or list(allowed_nodes)
    pref_set = {str(nid) for nid in (preferred_node_ids or ()) if nid}
    preferred_nodes = [n for n in default_nodes if n.node_id in pref_set] if pref_set else []

    bots: list[dict[str, Any]] = []
    for idx in range(n_bots):
        b_name = f"{bot_prefix}_{idx}"[:16]
        stagger_ms = (idx % 10) * 60
        role = idx % 4
        cmd_line: str | None = None
        node_id: str | None = None
        if default_nodes:
            pool = preferred_nodes if (preferred_nodes and idx % 4 != 3) else default_nodes
            chosen_node = pool[(idx + rng.randint(0, max(0, len(pool) - 1))) % len(pool)]
            node_id = chosen_node.node_id
            ramp_cap = 16 if n_bots <= 25 else 4096
            for _ in range(5):
                try:
                    cand_cmd = gen_args(
                        chosen_node,
                        tree,
                        rng,
                        denylist=active_deny,
                        bot_names=[b_name],
                        cap=ramp_cap,
                    )
                except ValueError:
                    continue
                if not active_deny.is_denied_command_line(cand_cmd, tree):
                    cmd_line = cand_cmd
                    break

        prog: list[dict[str, Any]] = []
        if stagger_ms > 0:
            prog.append({"a": "wait", "ms": stagger_ms})

        if role == 0:
            # Explorer wander: outward movement + command
            prog.append({"a": "move", "dx": 12, "dz": 8, "ms": 400})
            if cmd_line:
                prog.append({"a": "cmd", "cmd": cmd_line, "node": node_id})
            prog.append({"a": "move", "dx": 16, "dz": -10, "ms": 500})
            prog.append({"a": "wait", "ms": 400})
        elif role == 1:
            # Homesteader reroll: evaluate landing, move, reroll
            if cmd_line:
                prog.append({"a": "cmd", "cmd": cmd_line, "node": node_id})
            prog.append({"a": "wait", "ms": 250})
            prog.append({"a": "move", "dx": 6, "dz": -6, "ms": 300})
            if cmd_line:
                prog.append({"a": "cmd", "cmd": cmd_line, "node": node_id})
            prog.append({"a": "wait", "ms": 400})
        elif role == 2:
            # Co-op settler: steady traversal and interaction
            prog.append({"a": "move", "dx": -10, "dz": 10, "ms": 400})
            if cmd_line:
                prog.append({"a": "cmd", "cmd": cmd_line, "node": node_id})
            prog.append({"a": "wait", "ms": 500})
        else:
            # Session churner: command + early quit or rejoin
            if cmd_line:
                prog.append({"a": "cmd", "cmd": cmd_line, "node": node_id})
            prog.append({"a": "wait", "ms": 200})
            prog.append({"a": "quit"})

        bots.append({"name": b_name, "program": prog})
    return bots


def evaluate_survival_envelope(
    ramp_records: Iterable[dict[str, Any]] = (),
    *,
    baseline_records: Iterable[dict[str, Any]] = (),
    stall_ms: float = 2000.0,
    target_players: int | None = None,
    jar_index: JarIndex | None = None,
    profile: PlatformProfile | str = "paper",
) -> dict[str, Any]:
    """Evaluate empirical concurrency ramp records against declarative survival SLO invariants."""
    prof = profile if isinstance(profile, PlatformProfile) else get_platform_profile(profile)
    base_gaps: list[Any] = []
    base_windows: list[Any] = []
    base_max_delta: dict[str, float] = {}
    for br in baseline_records or ():
        if isinstance(br, dict):
            base_gaps.extend(br.get("tick_gaps") or ())
            base_windows.extend(br.get("exclusion_windows") or ())
            for k_cls, v_delta in (br.get("deltas") or {}).items():
                f_val = float(v_delta or 0.0)
                if f_val > base_max_delta.get(k_cls, 0.0):
                    base_max_delta[k_cls] = f_val
    base_summary = summarize_tick_gaps(base_gaps, base_windows)
    base_p99 = float(base_summary.get("p99_ms") or 1000.0)
    if base_p99 <= 0.0:
        base_p99 = 1000.0

    slo_max_ms = round(base_p99 + float(stall_ms), 1)
    slo_p95_ms = round(max(base_p99 * 1.25, base_p99 + min(500.0, float(stall_ms) * 0.4)), 1)
    slo = {
        "tick_gap_p95_ms": slo_p95_ms,
        "tick_gap_max_ms": slo_max_ms,
        "max_disconnects": 0,
    }

    cand_jars: set[str] = set()
    if jar_index is not None:
        cand_jars = {j for j in jar_index.jars if not prof.is_prerequisite_jar(j)}

    rungs_out: list[dict[str, Any]] = []
    for rec in ramp_records or ():
        if not isinstance(rec, dict):
            continue
        players = int(rec.get("players") or len(rec.get("bots") or ()) or 1)
        g_sum = summarize_tick_gaps(rec.get("tick_gaps") or (), rec.get("exclusion_windows") or ())
        p95_ms = round(float(g_sum.get("p95_ms", 0.0) or 0.0), 1)
        max_ms = round(float(g_sum.get("max_ms", 0.0) or 0.0), 1)
        disconnects = int(rec.get("disconnects", rec.get("kicks", 0)) or 0)
        retained_objs = 0.0
        for k_cls, v_delta in (rec.get("deltas") or {}).items():
            val = float(v_delta or 0.0) - base_max_delta.get(k_cls, 0.0) * max(1, players)
            if val <= 0.0:
                continue
            c_clean = k_cls[len(CLS_PREFIX) :] if k_cls.startswith(CLS_PREFIX) else k_cls
            if "$$Lambda" in c_clean:
                continue
            if jar_index is not None and cand_jars:
                ow = jar_index.owner_of_class(c_clean, profile=prof)
                if ow in cand_jars:
                    retained_objs += val
            else:
                retained_objs += val
        retained_objs = round(retained_objs, 1)

        violated: list[str] = []
        if max_ms > slo_max_ms:
            violated.append("tick_gap_max_ms")
        if disconnects > slo["max_disconnects"]:
            violated.append("disconnects")

        if violated:
            status = "FAIL"
        elif p95_ms > slo_p95_ms:
            status = "DEGRADED"
        else:
            status = "PASS"

        rungs_out.append(
            {
                "players": players,
                "status": status,
                "violated_invariants": violated,
                "tick_gap_p95_ms": p95_ms,
                "tick_gap_max_ms": max_ms,
                "disconnects": disconnects,
                "post_gc_retained_objects": retained_objs,
            }
        )

    rungs_out.sort(key=lambda r: int(r["players"]))
    first_failing: int | None = None
    max_surviving = 0
    for r in rungs_out:
        if r["status"] == "FAIL":
            if first_failing is None:
                first_failing = int(r["players"])
        elif first_failing is None:
            max_surviving = max(max_surviving, int(r["players"]))

    eff_target = int(target_players) if target_players is not None else (rungs_out[-1]["players"] if rungs_out else 0)
    survived_target = (
        bool(first_failing is None and max_surviving >= eff_target)
        if rungs_out and eff_target > 0
        else None
    )
    return {
        "target_players": eff_target,
        "max_surviving_players": max_surviving,
        "first_failing_players": first_failing,
        "survived_target": survived_target,
        "slo": slo,
        "rungs": rungs_out,
    }


def build_counterfactuals(
    findings: Iterable[dict[str, Any]],
    *,
    tree: CommandTree | None = None,
    stall_thresh_ms: float = 3000.0,
) -> list[dict[str, Any]]:
    """Build declarative counterfactual delta entries (`CF-1`, `CF-2`, ...) from evaluated findings."""
    out: list[dict[str, Any]] = []
    for idx, f in enumerate(findings or (), start=1):
        if not isinstance(f, dict):
            continue
        oracle = str(f.get("oracle") or "fault")
        attr_status = str(f.get("attribution_status") or "unconfirmed")
        op_mit = f.get("operator_mitigation") or {}
        auth_rep = f.get("author_reproducer") or {}
        repro = auth_rep.get("reproduction") or {}
        k_val = int(repro.get("k", f.get("k", 0)) or 0)
        n_val = int(repro.get("n", f.get("n", 0)) or 0)
        blamed = list(f.get("blamed_jars") or ())
        suspect = str(f.get("first_suspect") or (blamed[0] if blamed else "server"))
        c_node = str(op_mit.get("canonical_node") or "unknown")
        min_tier = str(op_mit.get("min_tier") or "default")
        follow_up = op_mit.get("follow_up")
        bisected_bound = op_mit.get("bisected_bound")
        shrunk_prog = auth_rep.get("shrunk_program") or []

        _, exec_node = _resolve_node_for_finding(c_node, tree, shrunk_prog)
        param_bound = derive_parameter_boundary(bisected_bound, exec_node)

        if oracle == "stall":
            inv_str = f"tick_gap_max_ms <= {stall_thresh_ms:.1f}"
        elif oracle == "leak":
            inv_str = "post_gc_class_slope <= 0.0"
        else:
            inv_str = "server_log_exceptions == 0"

        if attr_status == "confirmed":
            with_present = {"reproduced": f"{k_val}/{n_val}", "status": "FAIL"}
            with_disabled = {"reproduced": f"0/{n_val}", "status": "PASS"}
        elif attr_status == "server":
            with_present = {"reproduced": f"{k_val}/{n_val}", "status": "FAIL (server)"}
            with_disabled = {"reproduced": f"{k_val}/{n_val}", "status": "FAIL (server)"}
        else:
            with_present = {"reproduced": f"{k_val}/{n_val}", "status": attr_status.upper()}
            with_disabled = {"reproduced": "not_run", "status": "UNKNOWN"}

        out.append(
            {
                "id": f"CF-{idx}",
                "oracle": oracle,
                "signature": str(f.get("signature") or ""),
                "invariant_violated": inv_str,
                "trigger": {
                    "command": c_node,
                    "aliases": list(op_mit.get("aliases") or ()),
                    "min_tier": min_tier,
                    "shape": follow_up or "default",
                    "minimal_sequence": _compact_program_sequence(shrunk_prog),
                },
                "parameter_boundary": param_bound,
                "jar_isolation": {
                    "attribution_status": attr_status,
                    "blamed_jars": blamed,
                    "first_suspect": suspect,
                    "with_jar_present": with_present,
                    "with_jar_disabled": with_disabled,
                },
            }
        )
    return out


def build_server_bit_report(verdict: dict[str, Any]) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    """Construct `(bit_report_dict, bit_table_rows)` matching `BitReport` JSON and `BitTable` CSV schemas."""
    bit_findings: list[dict[str, Any]] = []
    comp_items: dict[str, list[tuple[dict[str, Any], dict[str, Any]]]] = {
        "bench.ramp": [],
        "bench.attribution": [],
        "bench.frames": [],
        "bench.surface": [],
    }

    # 1. `bench.ramp`: progressive player concurrency survival envelope
    envelope = verdict.get("survival_envelope") or {}
    slo = envelope.get("slo") or {}
    slo_p95 = slo.get("tick_gap_p95_ms", 1250.0)
    slo_max = slo.get("tick_gap_max_ms", 3000.0)
    for rung in envelope.get("rungs") or ():
        st = str(rung.get("status") or "PASS")
        players = int(rung.get("players", 0) or 0)
        if st == "FAIL":
            spec = BENCH_BIT_CODES[5303]
        elif st == "DEGRADED":
            spec = BENCH_BIT_CODES[5302]
        else:
            spec = BENCH_BIT_CODES[5301]
        viol = ",".join(rung.get("violated_invariants") or ()) or "none"
        f_obj = {
            "severity": spec["severity"],
            "id": spec["id"],
            "ns": spec["ns"],
            "code": spec["code"],
            "source": "server",
            "path": f"players={players}",
            "message": (
                f"status={st} p95_gap_ms={rung.get('tick_gap_p95_ms', 0.0)} "
                f"max_gap_ms={rung.get('tick_gap_max_ms', 0.0)} "
                f"disconnects={rung.get('disconnects', 0)} "
                f"retained_objs=+{rung.get('post_gc_retained_objects', 0.0)}"
            ),
            "expected": f"p95_gap_ms<={slo_p95} max_gap_ms<={slo_max} disconnects==0",
            "suggestion": (
                f"max_surviving_players={envelope.get('max_surviving_players', 0)} "
                f"violated={viol}"
            ),
        }
        bit_findings.append(f_obj)
        comp_items["bench.ramp"].append((spec, f_obj))

    # 2. `bench.attribution`: confirmed/candidate stalls, leaks & counterfactuals
    cfs = verdict.get("counterfactuals") or []
    findings = verdict.get("findings") or []
    for idx, f in enumerate(findings):
        cf = cfs[idx] if idx < len(cfs) else {}
        oracle = str(f.get("oracle") or "fault")
        attr_status = str(f.get("attribution_status") or "unconfirmed")
        sig = str(f.get("signature") or "")
        op_mit = f.get("operator_mitigation") or {}
        auth_rep = f.get("author_reproducer") or {}
        if oracle == "stall":
            if attr_status == "confirmed":
                spec = BENCH_BIT_CODES[5102 if "sustained" in sig else 5101]
            else:
                spec = BENCH_BIT_CODES[5103]
        else:
            spec = BENCH_BIT_CODES[5201 if attr_status == "confirmed" else 5202]

        blamed = f.get("blamed_jars") or []
        source_str = ",".join(blamed) if blamed else str(f.get("first_suspect") or "server")
        c_node = str(op_mit.get("canonical_node") or "unknown")
        min_tier = str(op_mit.get("min_tier") or "default")
        shape = str(op_mit.get("follow_up") or "default")
        p_bound = cf.get("parameter_boundary") or {}
        jar_iso = cf.get("jar_isolation") or {}
        pass_dom = p_bound.get("pass_domain")
        fail_dom = p_bound.get("fail_domain")
        inv_str = str(cf.get("invariant_violated") or "invariant_ok")
        exp_parts = [inv_str]
        if pass_dom:
            exp_parts.append(f"pass_domain={p_bound.get('argument', 'arg')}:{pass_dom}")
        with_dis = (jar_iso.get("with_jar_disabled") or {}).get("status", "UNKNOWN")
        with_dis_rep = (jar_iso.get("with_jar_disabled") or {}).get("reproduced", "n/a")
        sug_parts = [f"with_jar_disabled={with_dis}({with_dis_rep})", f"min_tier={min_tier}"]
        if fail_dom:
            sug_parts.append(f"fail_domain={p_bound.get('argument', 'arg')}:{fail_dom}")
        seq = (cf.get("trigger") or {}).get("minimal_sequence") or []
        if seq:
            sug_parts.append(f"seq=[{' -> '.join(seq)}]")

        f_obj = {
            "severity": spec["severity"],
            "id": spec["id"],
            "ns": spec["ns"],
            "code": spec["code"],
            "source": source_str,
            "path": f"cmd:/{c_node} [tier={min_tier},shape={shape}]",
            "message": str(auth_rep.get("evidence") or sig),
            "expected": " ".join(exp_parts),
            "suggestion": " ".join(sug_parts),
        }
        bit_findings.append(f_obj)
        comp_items["bench.attribution"].append((spec, f_obj))

    # 3. `bench.frames`: main-thread stall frame accounting
    for fr in verdict.get("main_thread_attribution") or ():
        spec = BENCH_BIT_CODES[5003]
        f_obj = {
            "severity": spec["severity"],
            "id": spec["id"],
            "ns": spec["ns"],
            "code": spec["code"],
            "source": str(fr.get("owner_jar") or "server"),
            "path": str(fr.get("top_frame") or "unknown"),
            "message": f"share={fr.get('share_pct', 0.0)}% samples={fr.get('samples', 0)}/{fr.get('total_samples', 0)}",
            "expected": "samples==0 during tick_gap<=stall_ms",
            "suggestion": f"owner_jar={fr.get('owner_jar') or 'server'}",
        }
        bit_findings.append(f_obj)
        comp_items["bench.frames"].append((spec, f_obj))

    # 4. `bench.surface`: tested command & action impact table
    for row in verdict.get("command_impact_table") or ():
        st = str(row.get("status") or "PASS")
        spec = BENCH_BIT_CODES[5002 if st.startswith("WARN") else 5001]
        f_obj = {
            "severity": spec["severity"],
            "id": spec["id"],
            "ns": spec["ns"],
            "code": spec["code"],
            "source": str(row.get("owner_jar") or "server"),
            "path": f"cmd:/{row.get('command')} [tier={row.get('tier')},shape={row.get('shape')}]",
            "message": (
                f"eps={row.get('episodes', 0)} p95_gap_ms={row.get('p95_gap_ms', 0.0)} "
                f"max_gap_ms={row.get('max_gap_ms', 0.0)} retained_per_ep=+{row.get('retained_per_ep', 0.0)} "
                f"status={st}"
            ),
            "expected": f"max_gap_ms<={slo_max} retained_per_ep<=0.0",
            "suggestion": f"owner_jar={row.get('owner_jar') or 'server'} tier={row.get('tier')}",
        }
        bit_findings.append(f_obj)
        comp_items["bench.surface"].append((spec, f_obj))

    has_err = any(f["severity"] == "ERROR" for f in bit_findings)
    has_warn = any(f["severity"] == "WARNING" for f in bit_findings)
    overall_health = "FAILED" if has_err else ("DEGRADED" if has_warn else "HEALTHY")

    table_rows: list[dict[str, Any]] = []
    for comp_id in ("bench.ramp", "bench.attribution", "bench.frames", "bench.surface"):
        items = comp_items[comp_id]
        if not items:
            continue
        c_errs = sum(1 for _, f in items if f["severity"] == "ERROR")
        c_warns = sum(1 for _, f in items if f["severity"] == "WARNING")
        c_health = "FAILED" if c_errs > 0 else ("DEGRADED" if c_warns > 0 else "HEALTHY")
        for spec, f in items:
            table_rows.append(
                {
                    "component": comp_id,
                    "tier": spec["tier"],
                    "sideEffect": spec["side_effect"],
                    "health": c_health,
                    "E": str(c_errs),
                    "W": str(c_warns),
                    "severity": f["severity"],
                    "id": f"{f['ns']}:{f['id']}",
                    "code": f["code"],
                    "source": f["source"],
                    "path": f["path"],
                    "message": f["message"],
                    "expected": f["expected"],
                    "suggestion": f["suggestion"],
                }
            )

    if not table_rows:
        table_rows.append(
            {
                "component": "bench.attribution",
                "tier": "IBIT",
                "sideEffect": "WORLD_SAFE",
                "health": overall_health,
                "E": "0",
                "W": "0",
                "severity": "",
                "id": "",
                "code": "",
                "source": "",
                "path": "",
                "message": "",
                "expected": "",
                "suggestion": "",
            }
        )

    bit_report = {
        "schema": 1,
        "health": overall_health,
        "findings": bit_findings,
    }
    return bit_report, table_rows


def format_server_findings_report(verdict: dict[str, Any]) -> str:
    """Format a human-readable declarative `report.txt` (including `BitTable` aligned CSV) from `verdict`."""
    lines: list[str] = []
    lines.append("=== Whole-Server Trace Evaluation Report (ADR-125) ===")
    lines.append(f"mode: {verdict.get('mode', 'server')}")
    lines.append(f"status: {verdict.get('status', 'offline_trace_evaluated')}")
    lines.append(f"verdict: {verdict.get('verdict', 'PASS')}")
    lines.append(f"profile: {verdict.get('profile', 'paper')}")
    lines.append(f"server_dir: {verdict.get('server_dir', '<server_dir>')}")
    tiers = verdict.get("tiers") or ["default"]
    lines.append(f"tiers: {', '.join(str(t) for t in tiers)}")
    lines.append("")

    culprits = verdict.get("confirmed_culprits") or []
    blamed = verdict.get("blamed_jars") or []
    lines.append("Attribution Summary:")
    lines.append(f"  confirmed_culprits ({len(culprits)}): {', '.join(culprits) if culprits else 'none'}")
    lines.append(f"  blamed_jars ({len(blamed)}): {', '.join(blamed) if blamed else 'none'}")
    lines.append("")

    envelope = verdict.get("survival_envelope") or {}
    rungs = envelope.get("rungs") or []
    if rungs:
        lines.append("Declarative Survival Envelope:")
        lines.append(f"  target_players: {envelope.get('target_players', 0)}")
        lines.append(f"  max_surviving_players: {envelope.get('max_surviving_players', 0)}")
        lines.append(f"  first_failing_players: {envelope.get('first_failing_players')}")
        lines.append(f"  survived_target: {envelope.get('survived_target')}")
        for r in rungs:
            viol_s = ",".join(r.get("violated_invariants") or ()) or "none"
            lines.append(
                f"  - players={r.get('players')} status={r.get('status')} "
                f"p95_gap_ms={r.get('tick_gap_p95_ms')} max_gap_ms={r.get('tick_gap_max_ms')} "
                f"disconnects={r.get('disconnects')} retained_objs=+{r.get('post_gc_retained_objects')} "
                f"violated={viol_s}"
            )
        lines.append("")

    findings = verdict.get("findings") or []
    lines.append(f"Findings ({len(findings)}):")
    if not findings:
        lines.append("  none")
    for idx, f in enumerate(findings, start=1):
        op_mit = f.get("operator_mitigation") or {}
        auth_rep = f.get("author_reproducer") or {}
        repro = auth_rep.get("reproduction") or {}
        lines.append(
            f"  [{idx}] oracle={f.get('oracle')} signature={f.get('signature')} "
            f"status={f.get('attribution_status')}"
        )
        lines.append("      operator_mitigation:")
        lines.append(f"        canonical_node: {op_mit.get('canonical_node')}")
        lines.append(f"        aliases: {', '.join(op_mit.get('aliases') or [])}")
        lines.append(f"        min_tier: {op_mit.get('min_tier')}")
        lines.append(f"        bisected_bound: {op_mit.get('bisected_bound') or 'none'}")
        lines.append("      author_reproducer:")
        lines.append(
            f"        blamed_jars: {', '.join(auth_rep.get('blamed_jars') or []) or 'none'} "
            f"(first_suspect={auth_rep.get('first_suspect')})"
        )
        lines.append(f"        evidence: {auth_rep.get('evidence')}")
        lines.append(f"        reproduction: {repro.get('k', 0)} of {repro.get('n', 0)}")
        lines.append(f"        shrunk_program: {json.dumps(auth_rep.get('shrunk_program') or [])}")
        lines.append(f"        replay_cmd: {auth_rep.get('replay_cmd')}")

    cfs = verdict.get("counterfactuals") or []
    if cfs:
        lines.append("")
        lines.append(f"Declarative Counterfactual Deltas ({len(cfs)}):")
        for cf in cfs:
            trig = cf.get("trigger") or {}
            p_bound = cf.get("parameter_boundary") or {}
            j_iso = cf.get("jar_isolation") or {}
            w_pres = j_iso.get("with_jar_present") or {}
            w_dis = j_iso.get("with_jar_disabled") or {}
            lines.append(
                f"  - id={cf.get('id')} invariant={cf.get('invariant_violated')!r} "
                f"cmd={trig.get('command')!r} tier={trig.get('min_tier')} shape={trig.get('shape')}"
            )
            if p_bound:
                lines.append(
                    f"    parameter_boundary: arg={p_bound.get('argument')} "
                    f"pass_domain={p_bound.get('pass_domain')} fail_domain={p_bound.get('fail_domain')}"
                )
            lines.append(
                f"    jar_isolation: blamed={j_iso.get('blamed_jars')} "
                f"with_jar_present={w_pres.get('status')}({w_pres.get('reproduced')}) "
                f"with_jar_disabled={w_dis.get('status')}({w_dis.get('reproduced')})"
            )

    mt_frames = verdict.get("main_thread_attribution") or []
    if mt_frames:
        lines.append("")
        lines.append("Main-Thread Stall Attribution:")
        for fr in mt_frames:
            lines.append(
                f"  {fr.get('share_pct', 0.0):5.1f}% ({fr.get('samples', 0)}/{fr.get('total_samples', 0)}) "
                f"owner={fr.get('owner_jar')} frame={fr.get('top_frame')}"
            )

    impact_rows = verdict.get("command_impact_table") or []
    if impact_rows:
        lines.append("")
        lines.append("Tested Command & Action Impact Table:")
        for r in impact_rows:
            lines.append(
                f"  tier={r.get('tier')} cmd={r.get('command')!r} shape={r.get('shape')} "
                f"eps={r.get('episodes')} p95_gap_ms={r.get('p95_gap_ms')} "
                f"max_gap_ms={r.get('max_gap_ms')} retained_per_ep=+{r.get('retained_per_ep')} "
                f"owner={r.get('owner_jar')} status={r.get('status')}"
            )

    bit_csv = verdict.get("bit_table_csv")
    if not bit_csv:
        _, t_rows = build_server_bit_report(verdict)
        bit_csv = render_bit_table_csv(t_rows)
    lines.append("")
    lines.append("BIT Table (Aligned CSV):")
    lines.append(bit_csv.rstrip("\n"))
    return "\n".join(lines) + "\n"


def evaluate_server_trace(
    records: list[dict[str, Any]],
    baseline_records: list[dict[str, Any]] | tuple[dict[str, Any], ...] = (),
    tree: CommandTree | None = None,
    jar_index: JarIndex | None = None,
    profile: PlatformProfile | str = "paper",
    *,
    denylist: Denylist | Iterable[str] | None = None,
    stall_ms: float = 2000.0,
    finding_k: int = 3,
    finding_n: int = 3,
    max_ddmin_probes: int = 32,
    probe_fn: Callable[..., tuple[int, bool] | int] | None = None,
    jar_probe_fn: Callable[..., tuple[int, bool] | int] | None = None,
    bisect_predicate_fn: Callable[..., bool] | None = None,
    predicate_fn: Callable[..., bool] | None = None,
    shrink_probe_fn: Callable[..., tuple[int, int, bool]] | None = None,
    server_dir: str = "<server_dir>",
    ramp_records: Iterable[dict[str, Any]] = (),
    target_players: int | None = None,
) -> dict[str, Any]:
    """Offline evaluator screening recorded episode telemetry against `baseline_records` (ADR-125 2.4).

    Combines `screen_server_leaks`, `StallOracle`, optional `bf.shrink_bots`, `bisect_numeric_arg`,
    `run_pre_ddmin_probes`, and `ddmin_jars` via injected callbacks, and formats structured
    `operator_mitigation` and `author_reproducer` blocks without mutating `ServerRun`.
    """
    prof = profile if isinstance(profile, PlatformProfile) else get_platform_profile(profile)
    active_jar_probe = jar_probe_fn or probe_fn
    active_bisect_pred = bisect_predicate_fn or predicate_fn
    base_list = list(baseline_records or ())
    rec_list = list(records or ())
    ramp_list = list(ramp_records or ())

    if jar_index is not None:
        installed_jars = sorted(jar_index.jars.keys())
        prereq_jars = [j for j in installed_jars if prof.is_prerequisite_jar(j)]
        candidate_jars = [j for j in installed_jars if j not in prereq_jars]
    else:
        installed_jars, prereq_jars, candidate_jars = [], [], []

    # 1. Leak screening across episodes vs baseline
    leak_screen = screen_server_leaks(
        records=rec_list,
        baseline_records=base_list,
        jar_index=jar_index,
        profile=prof,
    )

    candidate_findings: list[dict[str, Any]] = []
    seen_leak_classes: set[str] = set()
    seen_leak_keys: set[tuple[str, str]] = set()
    for corr in leak_screen.get("correlations") or ():
        cls_name = str(corr.get("cls") or "")
        if not cls_name or cls_name in seen_leak_classes:
            continue
        feat = str(corr.get("feature") or "")
        node_name: str | None = None
        follow_up: str | None = None
        if feat.startswith("arm:"):
            parts = feat.split(":", 2)
            node_name = parts[1] if len(parts) > 1 else None
            follow_up = parts[2] if len(parts) > 2 else None
        elif feat.startswith("node:"):
            node_name = feat.partition(":")[2]

        suspect_key = str(corr.get("first_suspect") or "server")
        dedup_key = (str(node_name or feat), suspect_key if suspect_key == "server" else "node_primary")
        if dedup_key in seen_leak_keys or (str(node_name or feat), "node_primary") in seen_leak_keys:
            continue
        seen_leak_classes.add(cls_name)
        seen_leak_keys.add(dedup_key)

        rep_ep = 0
        rep_prog: list[dict[str, Any]] = []
        best_cls_delta = -float("inf")
        for r in rec_list:
            if not isinstance(r, dict):
                continue
            r_feats = extract_server_episode_features(r)
            if feat in r_feats and r_feats[feat] > 0:
                r_cls_deltas = _extract_record_class_deltas(r)
                c_delta = float(r_cls_deltas.get(cls_name, 0.0) or 0.0)
                if c_delta > best_cls_delta or not rep_prog:
                    best_cls_delta = c_delta
                    rep_ep = r.get("ep", 0)
                    if isinstance(r.get("bots"), list):
                        rep_prog = _clone_program_structure(r["bots"])
                    elif isinstance(r.get("program"), list):
                        rep_prog = _clone_program_structure(r["program"])
                    if isinstance(r.get("follow_up"), str):
                        follow_up = r["follow_up"]

        if not rep_prog and node_name:
            rep_prog = [
                {
                    "name": "fz_0",
                    "program": [
                        {"a": "cmd", "cmd": node_name, "node": node_name},
                        {"a": "wait", "ms": 400},
                        {"a": "quit"} if follow_up in ("quit_lt1s", "quit_delay") else {"a": "wait", "ms": 100},
                    ],
                }
            ]

        slope = float(corr.get("slope", 0.0) or 0.0)
        evidence_str = f"+{slope:.1f} {cls_name} per {feat} (z={corr.get('z', 0.0)}, p={corr.get('p_value', 0.0)})"
        candidate_findings.append(
            {
                "oracle": "leak",
                "signature": f"leak:{cls_name}:{feat}",
                "node": node_name,
                "follow_up": follow_up,
                "evidence": evidence_str,
                "first_suspect": corr.get("first_suspect") or "server",
                "program": rep_prog,
                "ep": rep_ep,
            }
        )

    # 2. StallOracle evaluation across episodes vs baseline
    baseline_gaps: list[Any] = []
    baseline_windows: list[Any] = []
    for br in base_list:
        if isinstance(br, dict):
            baseline_gaps.extend(br.get("tick_gaps") or ())
            baseline_windows.extend(br.get("exclusion_windows") or ())

    stall_oracle = StallOracle(
        baseline_gaps=baseline_gaps,
        stall_ms=stall_ms,
        profile=prof,
        jar_index=jar_index,
        baseline_exclusion_windows=baseline_windows,
    )

    baseline_stall_sigs: set[str] = set()
    for br in base_list:
        if isinstance(br, dict) and br.get("tick_gaps"):
            b_eval = stall_oracle.evaluate_episode(
                br.get("tick_gaps") or (),
                follow_up=br.get("follow_up"),
                thread_dumps=br.get("thread_dumps") or (),
                exclusion_windows=br.get("exclusion_windows"),
            )
            if b_eval.get("stalled") and b_eval.get("signature"):
                baseline_stall_sigs.add(str(b_eval["signature"]))

    seen_stall_sigs: set[str] = set()
    for r in rec_list:
        if not isinstance(r, dict) or not r.get("tick_gaps"):
            continue
        s_eval = stall_oracle.evaluate_episode(
            r.get("tick_gaps") or (),
            follow_up=r.get("follow_up"),
            thread_dumps=r.get("thread_dumps") or (),
            exclusion_windows=r.get("exclusion_windows"),
        )
        sig = s_eval.get("signature")
        if not s_eval.get("stalled") or not sig or sig in baseline_stall_sigs or sig in seen_stall_sigs:
            continue
        seen_stall_sigs.add(str(sig))
        r_node = r.get("node") or r.get("canonical_node")
        r_follow = r.get("follow_up")
        r_prog: list[dict[str, Any]] = []
        if isinstance(r.get("bots"), list):
            r_prog = _clone_program_structure(r["bots"])
        elif isinstance(r.get("program"), list):
            r_prog = _clone_program_structure(r["program"])
        elif isinstance(r.get("cmd"), str):
            r_prog = [{"name": "fz_0", "program": [{"a": "cmd", "cmd": r["cmd"], "node": r_node or r["cmd"]}]}]
        elif r_node:
            r_prog = [{"name": "fz_0", "program": [{"a": "cmd", "cmd": str(r_node), "node": str(r_node)}]}]

        max_ms = s_eval["summary"]["max_ms"]
        c_frame = s_eval.get("culprit_frame") or s_eval.get(" signature") or "server"
        ev_str = (
            f"stall ({s_eval.get('kind')}): max_gap={max_ms}ms "
            f"(baseline_p99={s_eval.get('baseline_p99_ms')}ms) frame={c_frame}"
        )
        candidate_findings.append(
            {
                "oracle": "stall",
                "signature": str(sig),
                "node": r_node,
                "follow_up": r_follow,
                "evidence": ev_str,
                "first_suspect": s_eval.get("first_suspect") or "server",
                "program": r_prog,
                "ep": r.get("ep", 0),
            }
        )

    # 3. Shrink, numeric bisection, and dependency-closed jar ddmin per candidate finding
    final_findings: list[dict[str, Any]] = []
    for cand in candidate_findings:
        prog = _clone_program_structure(cand.get("program") or [])
        if shrink_probe_fn is not None and prog:
            is_bots = isinstance(prog[0].get("program"), list)
            bot_input = prog if is_bots else [{"name": "fz_0", "program": prog}]
            shrink_res = bf.shrink_bots(
                bot_input,
                lambda b_sub, _c=cand: _invoke_optional_callback(shrink_probe_fn, b_sub, _c),
            )
            if shrink_res.get("bots"):
                prog = shrink_res["bots"] if is_bots else shrink_res["bots"][0].get("program", prog)

        mit_node, exec_node = _resolve_node_for_finding(cand.get("node"), tree, prog)
        bisected_bound: str | None = None
        if active_bisect_pred is not None and prog and (exec_node is not None or cand.get("node")):
            target_for_bisect = exec_node or mit_node or str(cand.get("node"))
            prog, bisected_bound = bisect_numeric_arg(
                prog,
                target_for_bisect,
                lambda c_prog, _c=cand: bool(_invoke_optional_callback(active_bisect_pred, c_prog, _c)),
                denylist=denylist,
                tree=tree,
            )
        cand["shrunk_program"] = prog

        attr_status = "unconfirmed"
        blamed: list[str] = []
        k_repro = 0
        n_repro = int(finding_n)
        suspect = cand.get("first_suspect") or "server"

        if active_jar_probe is not None:
            dd_res = ddmin_jars(
                candidate_jars,
                jar_index,
                lambda active_set, _c=cand: _invoke_optional_callback(active_jar_probe, active_set, _c),
                finding_k=finding_k,
                finding_n=finding_n,
                max_probes=max_ddmin_probes,
                first_suspect=suspect,
            )
            attr_status = str(dd_res.get("attribution_status") or "unconfirmed")
            blamed = list(dd_res.get("blamed_jars") or [])
            suspect = str(dd_res.get("first_suspect") or suspect)
            k_repro = int(dd_res.get("full_hits", finding_k if attr_status == "confirmed" else 0))

        finding_dict = build_finding_verdict(
            oracle=cand["oracle"],
            signature=cand["signature"],
            node=mit_node or exec_node or cand.get("node"),
            tree=tree,
            follow_up=cand.get("follow_up"),
            evidence=cand["evidence"],
            first_suspect=suspect,
            attribution_status=attr_status,
            blamed_jars=blamed,
            shrunk_program=prog,
            bisected_bound=bisected_bound,
            k=k_repro,
            n=n_repro,
            server_dir=server_dir,
            profile=prof.name,
            ep=cand.get("ep", 0),
        )
        final_findings.append(finding_dict)

    # Rank player-reachable (`default`) findings before `op` findings (ADR-125 2.4.1)
    final_findings.sort(
        key=lambda f: (
            tier_rank(str((f.get("operator_mitigation") or {}).get("min_tier") or "default")),
            0 if f.get("attribution_status") == "confirmed" else 1,
            str(f.get("signature") or ""),
        )
    )

    confirmed_culprits: list[str] = []
    for f in final_findings:
        if f.get("attribution_status") == "confirmed":
            for j in f.get("blamed_jars") or ():
                if j not in confirmed_culprits:
                    confirmed_culprits.append(j)

    if confirmed_culprits:
        overall_verdict = "FAIL"
    elif any(
        f.get("attribution_status") in ("unconfirmed", "not_reproducible_after_restart")
        for f in final_findings
    ):
        overall_verdict = "INCONCLUSIVE"
    else:
        overall_verdict = "PASS"

    survival_env = evaluate_survival_envelope(
        ramp_list,
        baseline_records=base_list,
        stall_ms=stall_ms,
        target_players=target_players,
        jar_index=jar_index,
        profile=prof,
    )
    if survival_env.get("first_failing_players") is not None and overall_verdict == "PASS":
        overall_verdict = "FAIL"

    counterfactuals = build_counterfactuals(
        final_findings,
        tree=tree,
        stall_thresh_ms=float(stall_oracle.baseline_p99_ms + stall_ms),
    )
    mt_attribution = summarize_main_thread_attribution(
        rec_list + ramp_list,
        profile=prof,
        jar_index=jar_index,
    )
    impact_table = build_command_impact_table(
        rec_list,
        baseline_records=base_list,
        tree=tree,
        jar_index=jar_index,
        profile=prof,
        findings=final_findings,
        baseline_p99_ms=float(stall_oracle.baseline_p99_ms),
        stall_ms=stall_ms,
    )

    verdict_out: dict[str, Any] = {
        "mode": "server",
        "status": "offline_trace_evaluated",
        "verdict": overall_verdict,
        "profile": prof.name,
        "server_dir": server_dir,
        "tiers": list(tree.tiers) if tree is not None and tree.tiers else ["default"],
        "leak_screen": leak_screen,
        "confirmed_culprits": confirmed_culprits,
        "blamed_jars": list(confirmed_culprits),
        "findings": final_findings,
        "survival_envelope": survival_env,
        "counterfactuals": counterfactuals,
        "main_thread_attribution": mt_attribution,
        "command_impact_table": impact_table,
    }
    bit_report, bit_rows = build_server_bit_report(verdict_out)
    verdict_out["bit_report"] = bit_report
    verdict_out["bit_table_csv"] = render_bit_table_csv(bit_rows)
    verdict_out["report_text"] = format_server_findings_report(verdict_out)
    return verdict_out


# --- Passive local server audit (`--mode server`, ADR-125) -------------------

LOCAL_LOOPBACK_HOSTS: frozenset[str] = frozenset({"127.0.0.1", "localhost", "::1"})
ALLOWED_BENCH_HOSTNAMES: frozenset[str] = frozenset({"127.0.0.1", "localhost", "::1", "leaf26", "bamboo"})
ALLOWED_BENCH_SUFFIXES: tuple[str, ...] = (".local", ".lan", ".internal", ".home", ".ts.net")
RFC1918_AND_TAILSCALE_NETS: tuple[ipaddress.IPv4Network | ipaddress.IPv6Network, ...] = (
    ipaddress.ip_network("10.0.0.0/8"),
    ipaddress.ip_network("172.16.0.0/12"),
    ipaddress.ip_network("192.168.0.0/16"),
    ipaddress.ip_network("100.64.0.0/10"),
    ipaddress.ip_network("fc00::/7"),
)
DISPOSABLE_MARKER_FILES: tuple[str, ...] = (
    "bench-loop.sh",
    ".bench-disposable",
    "no-restart-script.sh",
)

# Flags always prohibited in `--mode server` (even with `--active`) per ADR-125 section 2.1 & 2.5.
SERVER_ACTIVE_PROHIBITED_FLAGS: dict[str, tuple[str, str]] = {
    "--cmd": ("cmd", "target-specific"),
    "--names": ("names", "target-specific"),
    "--jars": ("jars", "target-specific"),
    "--plugin-packages": ("plugin_packages", "target-specific"),
    "--targets": ("targets", "target-specific"),
    "--rtp-args": ("rtp_args", "target-specific"),
    "--grant": ("grant", "unsafe/op"),
    "--lp-group": ("lp_group", "unsafe/op"),
    "--console": ("console", "unsafe/op"),
    "--console-p": ("console_p", "unsafe/op"),
    "--console-max": ("console_max", "unsafe/op"),
    "--console-timeout-s": ("console_timeout_s", "unsafe/op"),
}


def is_allowed_bench_host(host: str | None) -> tuple[bool, str]:
    """Validate that `host` is loopback, private RFC1918 LAN, Tailscale CGNAT (`100.64.0.0/10`), or a local bench hostname (`leaf26`, `bamboo`, `*.local`, `*.lan`).

    Rejects public WAN IP addresses and public internet domain names so the harness
    cannot be repurposed against third-party servers.
    """
    raw = (host or "").strip().lower()
    if not raw:
        return False, "host must not be empty"
    if raw.startswith("[") and raw.endswith("]"):
        raw = raw[1:-1]
    if raw in ALLOWED_BENCH_HOSTNAMES:
        return True, "ok"
    try:
        addr = ipaddress.ip_address(raw)
    except ValueError:
        # Hostname check: allow single-label local bench hostnames (e.g. leaf26, bamboo) or local suffixes
        if re.fullmatch(r"[a-z0-9][a-z0-9-]{0,62}", raw):
            return True, "ok"
        if any(raw.endswith(sfx) for sfx in ALLOWED_BENCH_SUFFIXES) and re.fullmatch(
            r"[a-z0-9.-]{1,253}", raw
        ):
            return True, "ok"
        return (
            False,
            f"refusing public or external hostname {host!r}; --mode server --active only permits "
            "loopback (127.0.0.1, localhost, ::1), RFC1918 LAN / Tailscale IPs, or local bench hostnames (leaf26, bamboo, *.local, *.lan)",
        )

    if addr.is_loopback or addr.is_link_local or any(addr in net for net in RFC1918_AND_TAILSCALE_NETS):
        return True, "ok"
    return (
        False,
        f"refusing public WAN IP address {host!r}; --mode server --active only permits "
        "loopback, private RFC1918 LAN (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16), or Tailscale CGNAT (100.64.0.0/10)",
    )

# Flags prohibited in `--mode server` passive local audit, grouped by category.
SERVER_PROHIBITED_FLAGS: dict[str, tuple[str, str]] = {
    # Target-specific flags (ADR-125 section 2.1)
    "--cmd": ("cmd", "target-specific"),
    "--names": ("names", "target-specific"),
    "--jars": ("jars", "target-specific"),
    "--plugin-packages": ("plugin_packages", "target-specific"),
    "--targets": ("targets", "target-specific"),
    "--rtp-args": ("rtp_args", "target-specific"),
    # Unsafe / op / mutation / container flags
    "--grant": ("grant", "unsafe/op"),
    "--lp-group": ("lp_group", "unsafe/op"),
    "--console": ("console", "unsafe/op"),
    "--console-p": ("console_p", "unsafe/op"),
    "--console-max": ("console_max", "unsafe/op"),
    "--console-timeout-s": ("console_timeout_s", "unsafe/op"),
    "--allow-online": ("allow_online", "unsafe/op"),
    "--container": ("container", "unsafe/op"),
    "--mat": ("mat", "unsafe/op"),
    "--no-restart": ("no_restart", "unsafe/op"),
    "--no-isolate": ("isolate", "unsafe/op"),
    "--restart-retries": ("restart_retries", "unsafe/op"),
    # Replay flags
    "--replay": ("replay", "replay"),
    "--ep": ("ep", "replay"),
    "--replay-seed": ("replay_seed", "replay"),
    "--replay-n": ("replay_n", "replay"),
    "--replay-budget": ("replay_budget", "replay"),
    # Budget / active execution / stall-probe flags
    "--episodes": ("episodes", "budget/active"),
    "--seeds": ("seeds", "budget/active"),
    "--reps": ("reps", "budget/active"),
    "--rungs": ("rungs", "budget/active"),
    "--eta": ("eta", "budget/active"),
    "--ladder-candidates": ("ladder_candidates", "budget/active"),
    "--explore-extra": ("explore_extra", "budget/active"),
    "--warmup": ("warmup", "budget/active"),
    "--max-empty": ("max_empty", "budget/active"),
    "--min-bots": ("min_bots", "budget/active"),
    "--max-bots": ("max_bots", "budget/active"),
    "--min-episode-s": ("min_episode_s", "budget/active"),
    "--max-episode-s": ("max_episode_s", "budget/active"),
    "--done-slack-s": ("done_slack_s", "budget/active"),
    "--quiet-s": ("quiet_s", "budget/active"),
    "--quiet-wait-s": ("quiet_wait_s", "budget/active"),
    "--checkpoint-wait-s": ("checkpoint_wait_s", "budget/active"),
    "--checkpoint-poll-s": ("checkpoint_poll_s", "budget/active"),
    "--files-wait-s": ("files_wait_s", "budget/active"),
    "--settle-s": ("settle_s", "budget/active"),
    "--shutdown-wait-s": ("shutdown_wait_s", "budget/active"),
    "--startup-wait-s": ("startup_wait_s", "budget/active"),
    "--min-players": ("min_players", "budget/active"),
    "--player-wait-s": ("player_wait_s", "budget/active"),
    "--output-subdir": ("output_subdir", "budget/active"),
    "--generation": ("generation", "budget/active"),
    "--novelty": ("novelty", "budget/active"),
    "--novelty-max": ("novelty_max", "budget/active"),
    "--novelty-step": ("novelty_step", "budget/active"),
    "--surprise-z": ("surprise_z", "budget/active"),
    "--surprise-n": ("surprise_n", "budget/active"),
    "--surprise-k": ("surprise_k", "budget/active"),
    "--shrink": ("shrink", "budget/active"),
    "--shrink-n": ("shrink_n", "budget/active"),
    "--shrink-k": ("shrink_k", "budget/active"),
    "--shrink-probes": ("shrink_probes", "budget/active"),
    "--shrink-budget": ("shrink_budget", "budget/active"),
    "--finding-n": ("finding_n", "budget/active"),
    "--finding-k": ("finding_k", "budget/active"),
    "--finding-shrink": ("finding_shrink", "budget/active"),
    "--elite": ("elite", "budget/active"),
    "--cem-alpha": ("cem_alpha", "budget/active"),
    "--stall-gens": ("stall_gens", "budget/active"),
    "--ridge-lam": ("ridge_lam", "budget/active"),
    "--no-optimise": ("no_optimise", "budget/active"),
    "--coverage": ("coverage", "budget/active"),
    "--promote-max": ("promote_max", "budget/active"),
    "--top-classes": ("top_classes", "budget/active"),
    "--max-classes": ("max_classes", "budget/active"),
    "--heap-stop-mb": ("heap_stop_mb", "budget/active"),
    "--heap-stop-frac": ("heap_stop_frac", "budget/active"),
    "--no-dump": ("dump", "budget/active"),
    "--dump-wait-s": ("dump_wait_s", "budget/active"),
    "--owner-timeout-s": ("owner_timeout_s", "budget/active"),
    "--stall-ms": ("stall_ms", "budget/active"),
    "--default-budget-share": ("default_budget_share", "budget/active"),
    "--target-players": ("target_players", "budget/active"),
    "--ramp-rungs": ("ramp_rungs", "budget/active"),
    "--isolate-targets": ("isolate_targets", "budget/active"),
}

SERVER_ONLY_FLAGS: dict[str, str] = {
    "--profile": "profile",
    "--tiers": "tiers",
    "--deny": "deny",
    "--pid-file": "pid_file",
    "--pid": "pid",
    "--jcmd-snapshot": "jcmd_snapshot",
    "--container": "container",
    "--stall-ms": "stall_ms",
    "--default-budget-share": "default_budget_share",
    "--active": "active",
    "--ssh": "ssh",
    "--remote-server-dir": "remote_server_dir",
    "--baseline-episodes": "baseline_episodes",
    "--target-players": "target_players",
    "--ramp-rungs": "ramp_rungs",
    "--isolate-targets": "isolate_targets",
}


def _extract_cli_option_tokens(argv: Iterable[str] | None) -> list[str]:
    if not argv:
        return []
    tokens: list[str] = []
    for raw in argv:
        s = str(raw)
        if s == "--":
            break
        if s.startswith("--"):
            opt = s.split("=", 1)[0]
            tokens.append(opt)
    return tokens


def resolve_server_port(props: dict[str, str], explicit_port: int | None = None) -> int:
    """Return the configured `server-port`; an explicit `--mc-port` must equal it."""
    raw = str(props.get("server-port", "25565")).strip()
    try:
        configured = int(raw)
    except ValueError as exc:
        raise ValueError(f"server.properties server-port is not an integer: {raw!r}") from exc
    if explicit_port is not None and int(explicit_port) != configured:
        raise ValueError(
            f"--mc-port {explicit_port} does not match server.properties server-port={configured}; "
            "omit --mc-port or pass the configured port"
        )
    return configured


def is_disposable_server_dir(server_dir: str, props: dict[str, str] | None = None) -> tuple[bool, str]:
    """Validate that `server_dir` is an explicit, local, disposable bench server copy."""
    raw = (server_dir or "").strip()
    if not raw:
        return False, "--server-dir must not be empty"
    if raw.startswith("\\\\") or raw.startswith("//"):
        return False, f"--server-dir must be a local filesystem path, not a network share: {server_dir!r}"
    if not os.path.isdir(raw):
        return False, f"--server-dir does not exist or is not a directory: {server_dir!r}"

    real_dir = os.path.normcase(os.path.realpath(raw))
    root_dir = os.path.normcase(os.path.realpath(os.path.abspath(os.sep)))
    home_dir = os.path.normcase(os.path.realpath(os.path.expanduser("~")))
    repo_root = os.path.normcase(os.path.realpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")))
    if real_dir in (root_dir, home_dir, repo_root):
        return False, f"--server-dir must not point to root, home, or repository root: {server_dir!r}"
    if os.path.exists(os.path.join(raw, ".git")):
        return False, f"--server-dir must be a disposable server copy, not a git repository: {server_dir!r}"

    props_file = os.path.join(raw, "server.properties")
    if not os.path.isfile(props_file):
        return False, f"--server-dir is missing server.properties: {props_file!r}"

    parsed_props = props if props is not None else bf.bo.read_properties(raw)
    online_mode_val = parsed_props.get("online-mode", "").strip().lower()
    if online_mode_val != "false":
        return (
            False,
            f"--server-dir has online-mode={online_mode_val or '<missing>'} in server.properties; "
            "--mode server requires an offline disposable bench copy with explicit online-mode=false",
        )

    has_marker = any(os.path.isfile(os.path.join(raw, m)) for m in DISPOSABLE_MARKER_FILES)
    if not has_marker:
        spigot_yml = os.path.join(raw, "spigot.yml")
        if os.path.isfile(spigot_yml):
            try:
                with open(spigot_yml, "r", encoding="utf-8", errors="replace") as fh:
                    if "no-restart-script" in fh.read():
                        has_marker = True
            except OSError:
                pass
    if not has_marker:
        return (
            False,
            "--server-dir must be a disposable bench copy prepared by scripts/bench_setup.py "
            "(missing bench-loop.sh, no-restart-script in spigot.yml, or .bench-disposable marker)",
        )
    return True, "ok"


def validate_target_mode_args(
    args: Any,
    parser: Any | None = None,
    argv: Iterable[str] | None = None,
) -> None:
    """Reject `--mode server`-only flags when running in `leak` or `faults` target mode."""
    def _fail(msg: str) -> None:
        if parser is not None:
            parser.error(msg)
        raise ValueError(msg)

    opt_tokens = set(_extract_cli_option_tokens(argv))
    for flag, attr in SERVER_ONLY_FLAGS.items():
        if flag in opt_tokens:
            _fail(f"{flag} is only valid with --mode server")
        val = getattr(args, attr, None)
        if attr == "tiers":
            if val is not None and str(val).strip().lower() != "default":
                _fail(f"{flag} is only valid with --mode server")
        elif attr == "deny":
            if val:
                _fail(f"{flag} is only valid with --mode server")
        elif attr in ("jcmd_snapshot", "active"):
            if bool(val):
                _fail(f"{flag} is only valid with --mode server")
        elif val is not None:
            _fail(f"{flag} is only valid with --mode server")


def validate_active_server_mode_args(
    args: Any,
    parser: Any | None = None,
    argv: Iterable[str] | None = None,
) -> tuple[PlatformProfile, Denylist]:
    """Validate `--mode server --active` arguments, enforcing anti-abuse host & proof-of-ownership gates."""
    def _fail(msg: str) -> None:
        if parser is not None:
            parser.error(msg)
        raise ValueError(msg)

    opt_tokens = _extract_cli_option_tokens(argv)
    for tok in opt_tokens:
        if tok in SERVER_ACTIVE_PROHIBITED_FLAGS:
            _, category = SERVER_ACTIVE_PROHIBITED_FLAGS[tok]
            _fail(f"--mode server --active rejects {category} flag {tok}")

    default_ns = bf.build_parser().parse_args(["--server-dir", "."])
    for flag, (attr, category) in SERVER_ACTIVE_PROHIBITED_FLAGS.items():
        if hasattr(args, attr) and hasattr(default_ns, attr):
            if getattr(args, attr) != getattr(default_ns, attr):
                _fail(f"--mode server --active rejects {category} flag {flag}")

    raw_profile = getattr(args, "profile", None)
    if raw_profile is None or not str(raw_profile).strip():
        _fail("--mode server requires an explicit --profile (paper, fabric, or neoforge)")
    try:
        profile = get_platform_profile(str(raw_profile))
    except ValueError as exc:
        _fail(f"--mode server invalid --profile: {exc}")
        raise AssertionError("unreachable")  # pragma: no cover

    raw_tiers = getattr(args, "tiers", "default")
    parsed_tiers = [t.strip().lower() for t in str(raw_tiers or "").split(",") if t.strip()]
    if not parsed_tiers or any(t not in TIER_ORDER for t in parsed_tiers):
        _fail(
            f"--mode server --active requires --tiers from {list(TIER_ORDER)} "
            f"(e.g. 'default' or 'default,op'); got {raw_tiers!r}"
        )

    host = (getattr(args, "host", None) or "127.0.0.1").strip()
    ok_host, host_reason = is_allowed_bench_host(host)
    if not ok_host:
        _fail(f"--mode server --active invalid --host: {host_reason}")

    mc_host = getattr(args, "mc_host", None)
    if mc_host is not None:
        ok_mc, mc_reason = is_allowed_bench_host(str(mc_host).strip())
        if not ok_mc:
            _fail(f"--mode server --active invalid --mc-host: {mc_reason}")

    ssh_target = getattr(args, "ssh", None)
    if ssh_target is not None:
        ssh_str = str(ssh_target).strip()
        if not ssh_str or ssh_str.startswith("-"):
            _fail(f"--mode server --active invalid --ssh target: {ssh_target!r}")
        ssh_host = ssh_str.rpartition("@")[2] if "@" in ssh_str else ssh_str
        ok_ssh, ssh_reason = is_allowed_bench_host(ssh_host)
        if not ok_ssh:
            _fail(f"--mode server --active invalid --ssh host: {ssh_reason}")

    server_dir = getattr(args, "server_dir", None)
    ok_disp, disp_reason = is_disposable_server_dir(server_dir)
    if not ok_disp:
        _fail(f"--mode server invalid --server-dir: {disp_reason}")

    props = bf.bo.read_properties(server_dir)
    try:
        resolve_server_port(props, getattr(args, "mc_port", None))
    except ValueError as exc:
        _fail(f"--mode server invalid --mc-port: {exc}")

    # Proof-of-ownership gate: active server mode requires a configured RCON password
    rcon_pw = getattr(args, "password", None) or props.get("rcon.password", "").strip()
    if not rcon_pw:
        _fail(
            "--mode server --active requires proof of server ownership: "
            "rcon.password must be set in server.properties (or passed via --password)"
        )

    max_bots = int(getattr(args, "max_bots", 8) or 8)
    min_bots = int(getattr(args, "min_bots", 2) or 2)
    if not (1 <= min_bots <= max_bots <= 128):
        _fail(f"--mode server --active requires 1 <= --min-bots <= --max-bots <= 128 (got {min_bots}..{max_bots})")

    target_players = getattr(args, "target_players", None)
    if target_players is not None:
        if not (1 <= int(target_players) <= 128):
            _fail(f"--mode server --active requires 1 <= --target-players <= 128; got {target_players!r}")

    ramp_rungs_raw = getattr(args, "ramp_rungs", None)
    if ramp_rungs_raw is not None:
        try:
            build_survival_ramp_rungs(explicit_rungs=str(ramp_rungs_raw))
        except ValueError as exc:
            _fail(f"--mode server --active invalid --ramp-rungs: {exc}")

    jar_dir = profile.jar_dir(server_dir)
    if not os.path.isdir(jar_dir):
        _fail(
            f"--mode server with --profile {profile.name} requires jar directory "
            f"{jar_dir!r} inside --server-dir"
        )

    raw_deny = getattr(args, "deny", None) or []
    if isinstance(raw_deny, str):
        raw_deny = [raw_deny]
    extra_deny: list[str] = []
    for entry in raw_deny:
        parts = [p.strip() for p in str(entry).split(",") if p.strip()]
        if not parts:
            _fail("--mode server --deny entry must not be empty")
        extra_deny.extend(parts)
    try:
        denylist = Denylist(extra_deny)
    except ValueError as exc:
        _fail(f"--mode server invalid --deny pattern: {exc}")
        raise AssertionError("unreachable")  # pragma: no cover

    pid_file = getattr(args, "pid_file", None)
    pid_val = getattr(args, "pid", None)
    if pid_file is not None and pid_val is not None:
        _fail("--mode server rejects passing both --pid and --pid-file; pass at most one")
    if pid_file is not None and not ssh_target:
        pf = str(pid_file).strip()
        if not pf or not os.path.isfile(pf):
            _fail(f"--mode server --pid-file does not exist or is not a file: {pid_file!r}")
    if pid_val is not None and int(pid_val) <= 0:
        _fail(f"--mode server --pid must be a positive integer; got {pid_val!r}")

    return profile, denylist


def validate_server_mode_args(
    args: Any,
    parser: Any | None = None,
    argv: Iterable[str] | None = None,
) -> tuple[PlatformProfile, Denylist]:
    """Validate `--mode server` arguments (passive local audit by default, or active when `--active` is passed)."""
    if bool(getattr(args, "active", False)):
        return validate_active_server_mode_args(args, parser=parser, argv=argv)
    def _fail(msg: str) -> None:
        if parser is not None:
            parser.error(msg)
        raise ValueError(msg)

    opt_tokens = _extract_cli_option_tokens(argv)
    opt_set = set(opt_tokens)

    # 1. Reject prohibited target-specific, unsafe/op, replay, and budget/active flags in argv.
    for tok in opt_tokens:
        if tok in SERVER_PROHIBITED_FLAGS:
            _, category = SERVER_PROHIBITED_FLAGS[tok]
            _fail(f"--mode server (passive_only local audit) rejects {category} flag {tok}")

    # Also compare attributes on `args` against parser defaults in case `args` was mutated directly.
    default_ns = bf.build_parser().parse_args(["--server-dir", "."])
    for flag, (attr, category) in SERVER_PROHIBITED_FLAGS.items():
        if hasattr(args, attr) and hasattr(default_ns, attr):
            if getattr(args, attr) != getattr(default_ns, attr):
                _fail(f"--mode server (passive_only local audit) rejects {category} flag {flag}")

    # 2. Require explicit `--profile` and validate against known profiles.
    raw_profile = getattr(args, "profile", None)
    if raw_profile is None or not str(raw_profile).strip():
        _fail("--mode server requires an explicit --profile (paper, fabric, or neoforge)")
    try:
        profile = get_platform_profile(str(raw_profile))
    except ValueError as exc:
        _fail(f"--mode server invalid --profile: {exc}")
        raise AssertionError("unreachable")  # pragma: no cover

    # 3. Require `--tiers default` only (reject `op`, `default,op`, or empty/unknown tiers).
    raw_tiers = getattr(args, "tiers", "default")
    parsed_tiers = [t.strip().lower() for t in str(raw_tiers or "").split(",") if t.strip()]
    if parsed_tiers != ["default"]:
        _fail(
            f"--mode server passive_only audit requires --tiers default only "
            f"(got {raw_tiers!r}; op and multi-tier execution are prohibited)"
        )

    # 4. Require local loopback `--host` and `--mc-host` (no cross-server / remote workflows).
    host = (getattr(args, "host", None) or "127.0.0.1").strip().lower()
    if host not in LOCAL_LOOPBACK_HOSTS:
        _fail(
            f"--mode server passive_only local audit requires a local loopback --host "
            f"(127.0.0.1, localhost, or ::1); got {getattr(args, 'host', None)!r}"
        )
    mc_host = getattr(args, "mc_host", None)
    if mc_host is not None and str(mc_host).strip().lower() not in LOCAL_LOOPBACK_HOSTS:
        _fail(
            f"--mode server passive_only local audit requires a local loopback --mc-host "
            f"(127.0.0.1, localhost, or ::1); got {mc_host!r}"
        )

    # 5. Validate explicit local disposable `--server-dir` and profile jar directory.
    server_dir = getattr(args, "server_dir", None)
    ok_disp, disp_reason = is_disposable_server_dir(server_dir)
    if not ok_disp:
        _fail(f"--mode server invalid --server-dir: {disp_reason}")
    try:
        resolve_server_port(bf.bo.read_properties(server_dir), getattr(args, "mc_port", None))
    except ValueError as exc:
        _fail(f"--mode server invalid --mc-port: {exc}")

    jar_dir = profile.jar_dir(server_dir)
    if not os.path.isdir(jar_dir):
        _fail(
            f"--mode server with --profile {profile.name} requires jar directory "
            f"{jar_dir!r} inside --server-dir"
        )

    # 6. Validate `--deny` patterns (if any) without shrinking DEFAULT_DENYLIST.
    raw_deny = getattr(args, "deny", None) or []
    if isinstance(raw_deny, str):
        raw_deny = [raw_deny]
    extra_deny: list[str] = []
    for entry in raw_deny:
        parts = [p.strip() for p in str(entry).split(",") if p.strip()]
        if not parts:
            _fail("--mode server --deny entry must not be empty")
        extra_deny.extend(parts)
    try:
        denylist = Denylist(extra_deny)
    except ValueError as exc:
        _fail(f"--mode server invalid --deny pattern: {exc}")
        raise AssertionError("unreachable")  # pragma: no cover

    # 7. Validate `--pid-file` / `--pid` if provided (mutually exclusive).
    pid_file = getattr(args, "pid_file", None)
    pid_val = getattr(args, "pid", None)
    if pid_file is not None and pid_val is not None:
        _fail("--mode server rejects passing both --pid and --pid-file; pass at most one")
    if pid_file is not None:
        pf = str(pid_file).strip()
        if not pf or not os.path.isfile(pf):
            _fail(f"--mode server --pid-file does not exist or is not a file: {pid_file!r}")
    if pid_val is not None and int(pid_val) <= 0:
        _fail(f"--mode server --pid must be a positive integer; got {pid_val!r}")

    return profile, denylist


def format_server_passive_report(verdict: dict[str, Any]) -> str:
    """Format human-readable `report.txt` for `--mode server` passive local audit."""
    lines: list[str] = []
    lines.append("=== Whole-Server Passive Local Audit (ADR-125) ===")
    lines.append(f"mode: {verdict.get('mode', 'server')}")
    lines.append(f"status: {verdict.get('status', 'passive_only')}")
    lines.append(f"verdict: {verdict.get('verdict', 'PASSIVE_ONLY')}")
    lines.append(f"profile: {verdict.get('profile')}")
    lines.append(f"server_dir: {verdict.get('server_dir')}")
    lines.append(f"tiers: {', '.join(verdict.get('tiers') or ['default'])}")
    lines.append("")

    baseline = verdict.get("baseline") or {}
    lines.append("Baseline:")
    lines.append(
        f"  status: {baseline.get('status', 'unavailable')} "
        f"(available={bool(baseline.get('available', False))})"
    )
    if baseline.get("reason"):
        lines.append(f"  reason: {baseline['reason']}")
    lines.append("")

    cmds = verdict.get("commands") or {}
    lines.append("Command Graph Discovery (read-only declare_commands):")
    lines.append(f"  commands_json: {cmds.get('file')}")
    lines.append(f"  total_nodes: {cmds.get('total_nodes', 0)}")
    lines.append(f"  allowed_executable_default_nodes: {cmds.get('allowed_executable_nodes', 0)}")
    lines.append(f"  denied_nodes: {cmds.get('denied_nodes', 0)}")
    lines.append(f"  truncated: {bool(cmds.get('truncated', False))}")
    lines.append("")

    jars = verdict.get("jars") or {}
    installed = jars.get("installed_jars") or []
    prereqs = jars.get("prerequisite_jars") or []
    cands = jars.get("candidate_jars") or []
    lines.append("Offline JarIndex Inventory:")
    lines.append(f"  jar_dir: {jars.get('jar_dir')}")
    lines.append(f"  installed_jars ({len(installed)}): {', '.join(installed) if installed else 'none'}")
    lines.append(f"  prerequisite_jars ({len(prereqs)}): {', '.join(prereqs) if prereqs else 'none'}")
    lines.append(f"  candidate_jars ({len(cands)}): {', '.join(cands) if cands else 'none'}")
    lines.append(f"  indexed_classes: {jars.get('indexed_classes', 0)}")
    lines.append("")

    snap = verdict.get("jcmd_snapshot") or {}
    lines.append("Local JcmdObserver Snapshot:")
    lines.append(f"  requested: {bool(snap.get('requested', False))}")
    lines.append(f"  status: {snap.get('status', 'not_requested')}")
    if snap.get("pid") is not None:
        lines.append(f"  pid: {snap['pid']}")
    if snap.get("total_classes") is not None:
        lines.append(
            f"  histogram: {snap.get('total_classes', 0)} classes, "
            f"{snap.get('total_instances', 0)} instances, "
            f"{snap.get('total_bytes', 0)} bytes"
        )
    top_cls = snap.get("top_classes") or []
    if top_cls:
        lines.append("  top_classes_by_bytes (offline jar owner only; non-causal):")
        for entry in top_cls[:5]:
            lines.append(
                f"    {entry['class_name']}: {entry['instances']} inst, "
                f"{entry['bytes']} B (owner={entry['jar_owner']})"
            )
    mt = snap.get("main_thread")
    if isinstance(mt, dict) and mt.get("top_frame"):
        lines.append(
            f"  main_thread: {mt.get('thread_name') or 'Server thread'} "
            f"top={mt.get('top_frame')} non_server_frame={mt.get('culprit_frame') or 'none'} "
            f"(owner={mt.get('first_suspect') or 'server'}; non-causal snapshot)"
        )
    if snap.get("note"):
        lines.append(f"  note: {snap['note']}")
    lines.append("")

    warnings = verdict.get("warnings") or []
    if warnings:
        lines.append("Warnings:")
        for w in warnings:
            lines.append(f"  - {w}")
        lines.append("")

    lines.append("Attribution & Culprits:")
    lines.append("  confirmed_culprits: none (0)")
    lines.append("  blamed_jars: none (0)")
    lines.append(
        "  note: passive_only audit executes no commands, runs no episodes, grants no op permissions, "
        "toggles no jars, and makes no causal leak, stall, mitigation, or replay claims."
    )
    return "\n".join(lines) + "\n"


class ServerRun:
    """Passive local server audit runner (`--mode server`, ADR-125).

    Validates the local disposable `--server-dir` and `--profile`, captures the
    `default` tier `declare_commands` graph once in read-only mode, writes
    denylist-filtered `commands.json`, scans the offline `JarIndex` inventory,
    optionally collects an operator-selected local `JcmdObserver` snapshot (with
    process ownership verification), and writes `verdict.json` and `report.txt`
    with `status: 'passive_only'` and zero confirmed culprits or mitigation claims.
    """

    def __init__(
        self,
        args: Any,
        run_dir: str,
        *,
        swarm: Any | None = None,
        observer: JcmdObserver | None = None,
        jar_index: JarIndex | None = None,
        driver: Any | None = None,
        stamp: str | None = None,
        focus_jar_prefix: str | None = None,
    ):
        self.args = args
        self.run_dir = os.path.abspath(run_dir)
        self.active = bool(getattr(args, "active", False))
        self.profile, self.denylist = validate_server_mode_args(args)
        self.server_dir = os.path.abspath(args.server_dir)
        self.swarm = swarm
        self.observer = observer
        self.jar_index = jar_index
        self.driver = driver
        self.stamp = stamp or time.strftime("%Y%m%d-%H%M%S")
        self.focus_jar_prefix = (focus_jar_prefix or "").strip().lower() or None
        self._focused_default_nodes: list[str] = []

    def _wants_jcmd_snapshot(self) -> bool:
        return bool(
            self.observer is not None
            or getattr(self.args, "pid_file", None)
            or getattr(self.args, "pid", None) is not None
            or getattr(self.args, "jcmd_snapshot", False)
            or getattr(self.args, "ssh", None)
            or getattr(self.args, "container", None)
            or self.active
        )

    def _build_observer(self) -> JcmdObserver:
        if self.observer is not None:
            if not self.active and (self.observer.container is not None or not self.observer.check_local_pid):
                raise JcmdError(
                    "--mode server passive_only local audit requires local JVM ownership verification "
                    "(container JcmdObserver or check_local_pid=False is not permitted)"
                )
            if not self.observer.server_dir:
                self.observer.server_dir = self.server_dir
            return self.observer
        pid_val = getattr(self.args, "pid", None)
        pid_file = getattr(self.args, "pid_file", None)
        container = getattr(self.args, "container", None) if self.active else None
        ssh_target = getattr(self.args, "ssh", None) if self.active else None
        remote_dir = getattr(self.args, "remote_server_dir", None) if self.active else None
        return JcmdObserver(
            pid=int(pid_val) if pid_val is not None else None,
            pid_file=str(pid_file) if pid_file else None,
            container=str(container) if container else None,
            ssh=str(ssh_target) if ssh_target else None,
            remote_server_dir=str(remote_dir) if remote_dir else None,
            server_dir=self.server_dir,
        )

    def _collect_jcmd_snapshot(self, obs: JcmdObserver, jar_index: JarIndex) -> dict[str, Any]:
        resolved_pid = obs.resolve_pid()
        histo = obs.class_histogram()
        tdump = obs.thread_print()

        total_instances = sum(v[0] for v in histo.values())
        total_bytes = sum(v[1] for v in histo.values())
        ranked = sorted(histo.items(), key=lambda kv: (-kv[1][1], -kv[1][0], kv[0]))[:15]
        top_classes = [
            {
                "class_name": cls_name,
                "instances": int(inst),
                "bytes": int(nbytes),
                "jar_owner": jar_index.owner_of_class(cls_name, profile=self.profile),
            }
            for cls_name, (inst, nbytes) in ranked
        ]
        main_thread = parse_main_thread_frame(tdump, profile=self.profile, jar_index=jar_index)

        return {
            "requested": True,
            "status": "collected",
            "pid": resolved_pid,
            "total_classes": len(histo),
            "total_instances": int(total_instances),
            "total_bytes": int(total_bytes),
            "top_classes": top_classes,
            "main_thread": {
                "thread_name": main_thread.get("thread_name"),
                "top_frame": main_thread.get("top_frame"),
                "culprit_frame": main_thread.get("culprit_frame"),
                "culprit_class": main_thread.get("culprit_class"),
                "first_suspect": main_thread.get("first_suspect"),
            },
            "exclusion_windows": [w.to_dict() for w in obs.exclusion_windows],
            "attribution_status": "passive_snapshot_only",
            "note": (
                "Single passive snapshot only; statistical leak screening requires exchangeable episodes "
                "and a vanilla baseline, so no causal leak or stall claims are made."
            ),
        }

    def run(self) -> dict[str, Any]:
        if self.active:
            return self.run_active()
        os.makedirs(self.run_dir, exist_ok=True)
        warnings: list[str] = []

        # 1. Offline JarIndex scan (read-only; never renames or toggles jars).
        jar_index = self.jar_index or JarIndex.from_server_dir(self.server_dir, self.profile)
        installed_jars = sorted(jar_index.jars.keys())
        prereq_jars = sorted(j for j in installed_jars if self.profile.is_prerequisite_jar(j))
        candidate_jars = sorted(j for j in installed_jars if not self.profile.is_prerequisite_jar(j))

        # 2. If operator requested a local JcmdObserver snapshot, verify PID ownership up front.
        want_jcmd = self._wants_jcmd_snapshot()
        obs: JcmdObserver | None = None
        if want_jcmd:
            obs = self._build_observer()
            obs.resolve_pid()

        # 3. Single read-only `declare_commands` discovery for `default` tier.
        props = bf.bo.read_properties(self.server_dir)
        mc_port = resolve_server_port(props, getattr(self.args, "mc_port", None))
        own_swarm = self.swarm is None
        swarm = self.swarm
        if own_swarm:
            swarm = bf.Swarm(
                bf.swarm_argv(self.args, mc_port),
                os.path.join(self.run_dir, "swarm.log"),
            )
        try:
            if own_swarm:
                swarm.start()
            episode_max_s = float(getattr(self.args, "episode_max_s", 15.0) or 15.0)
            discover_max_ms = max(1000, min(60000, int(episode_max_s * 1000)))
            cmd_ev, _done = swarm.discover(
                "discover-default",
                tier="default",
                bot=bf.bot_name(self.stamp, 0, "disc", 0),
                max_ms=discover_max_ms,
            )
        finally:
            if own_swarm and swarm is not None:
                swarm.close()

        commands_path = os.path.join(self.run_dir, "commands.json")
        tree = merge_command_trees(
            {"default": cmd_ev},
            denylist=self.denylist,
            out_path=commands_path,
        )
        if tree.truncated:
            trunc_msg = (
                f"Command graph truncated at {len(tree.nodes)} nodes; "
                f"inspect {commands_path} for partial inventory."
            )
            warnings.append(trunc_msg)

        allowed_exec = tree.allowed_executable_nodes(["default"])
        denied_nodes = [n for n in tree.nodes.values() if n.denied]

        # 4. Optional operator-selected local JcmdObserver snapshot.
        if obs is not None:
            jcmd_summary = self._collect_jcmd_snapshot(obs, jar_index)
        else:
            jcmd_summary = {
                "requested": False,
                "status": "not_requested",
                "note": "Pass --pid-file <path> (or --jcmd-snapshot) to collect a local read-only jcmd snapshot.",
            }

        verdict: dict[str, Any] = {
            "mode": "server",
            "status": "passive_only",
            "verdict": "PASSIVE_ONLY",
            "profile": self.profile.name,
            "server_dir": self.server_dir,
            "tiers": ["default"],
            "baseline": {
                "available": False,
                "status": "unavailable",
                "reason": (
                    "passive_only local audit does not toggle jars, restart the server, "
                    "or execute baseline episodes"
                ),
            },
            "commands": {
                "file": commands_path,
                "total_nodes": len(tree.nodes),
                "allowed_executable_nodes": len(allowed_exec),
                "denied_nodes": len(denied_nodes),
                "truncated": bool(tree.truncated),
                "denylist": list(self.denylist.entries),
            },
            "jars": {
                "jar_dir": jar_index.jar_dir,
                "installed_jars": installed_jars,
                "prerequisite_jars": prereq_jars,
                "candidate_jars": candidate_jars,
                "indexed_classes": len(jar_index.class_to_jars),
                "metadata": {
                    j: {
                        "plugin_id": jar_index.jars[j].plugin_id,
                        "depends": sorted(jar_index.jars[j].hard_depends),
                        "soft_depends": sorted(jar_index.jars[j].soft_depends),
                        "provides": sorted(jar_index.jars[j].provides),
                        "class_count": int(jar_index.jars[j].class_count),
                    }
                    for j in installed_jars
                },
            },
            "jcmd_snapshot": jcmd_summary,
            "confirmed_culprits": [],
            "blamed_jars": [],
            "findings": [],
            "warnings": warnings,
        }

        verdict_path = os.path.join(self.run_dir, getattr(bf, "VERDICT_FILE", "verdict.json"))
        bf.save_json(verdict_path, verdict)
        report_path = os.path.join(self.run_dir, getattr(bf, "REPORT_FILE", "report.txt"))
        with open(report_path, "w", encoding="utf-8") as fh:
            fh.write(format_server_passive_report(verdict))

        return verdict

    def _build_driver(self, props: dict[str, str]) -> Any:
        if self.driver is not None:
            return self.driver
        host = (getattr(self.args, "host", None) or "127.0.0.1").strip()
        port = getattr(self.args, "port", None) or int(props.get("rcon.port", "25575"))
        password = getattr(self.args, "password", None) or props.get("rcon.password", "").strip()
        return bf.FuzzDriver(self.args, host, int(port), str(password))

    def _set_candidate_jars_state(
        self,
        jar_index: JarIndex,
        candidate_jars: list[str],
        active_subset: Iterable[str],
    ) -> None:
        """Toggle candidate jars in `jar_index.jar_dir` by renaming disabled jars to `.jar.off`."""
        active_set = set(active_subset)
        jar_dir = jar_index.jar_dir
        for j_name in candidate_jars:
            enabled_path = os.path.join(jar_dir, j_name)
            disabled_path = enabled_path + bf.bo.OFF_SUFFIX
            if j_name in active_set:
                if not os.path.exists(enabled_path) and os.path.exists(disabled_path):
                    os.replace(disabled_path, enabled_path)
            else:
                if os.path.exists(enabled_path):
                    os.replace(enabled_path, disabled_path)

    def _verify_loaded_plugins(
        self,
        drv: Any,
        jar_index: JarIndex,
        active_subset: Iterable[str],
    ) -> bool:
        """Verify over RCON `plugins` / `fabric mods` / `forge mods` that all jars in `active_subset` loaded cleanly."""
        reply = drv.try_cmd(self.profile.loaded_check_cmd)
        if not reply:
            return True
        low_reply = reply.lower()
        if "unknown or incomplete command" in low_reply or "unknown command" in low_reply:
            return True
        for j_name in active_subset:
            meta = jar_index.jars.get(j_name)
            p_id = (meta.plugin_id if meta and meta.plugin_id else j_name[:-4]).lower()
            if p_id and p_id not in low_reply:
                return False
        return True

    def _restart_driver(self, drv: Any) -> bool:
        """Restart the disposable bench server and wait for RCON to go down and return."""
        if self.driver is not None:
            return bool(drv.restart_once())
        rc = getattr(self.args, "restart_command", "restart") or "restart"
        try:
            reply = drv.cmd(rc, timeout=15)
            if "unknown or incomplete" in (reply or "").lower():
                return False
        except bf.bo.RconError:
            pass
        shutdown_s = float(getattr(self.args, "shutdown_wait_s", 1200) or 1200)
        end_down = time.time() + shutdown_s
        while time.time() < end_down:
            if drv.try_cmd("list") is None:
                break
            time.sleep(0.25)
        else:
            return False
        startup_s = float(getattr(self.args, "startup_wait_s", 900) or 900)
        end_up = time.time() + startup_s
        poll_s = 0.25
        while time.time() < end_up:
            if drv.try_cmd("list") is not None:
                return True
            time.sleep(poll_s)
            poll_s = min(2.0, poll_s * 1.5)
        return False

    def _build_active_exploration_tree(
        self,
        tree: CommandTree,
        jar_index: JarIndex,
        candidate_jars: list[str],
        tiers: list[str],
        max_cmds_per_jar: int = 4,
    ) -> CommandTree:
        """Filter a copy of `tree` for active bandit exploration across `candidate_jars`."""
        allowed = tree.allowed_executable_nodes(tiers)
        if len(allowed) <= 8 or not candidate_jars:
            return tree

        # Map commands and aliases declared in plugin.yml across candidate vs prerequisite jars
        alias_to_canon: dict[str, str] = {}
        cmd_to_jar: dict[str, str] = {}
        prereq_cmds: set[str] = set()
        for j_name, meta in jar_index.jars.items():
            is_cand = j_name in candidate_jars
            p_ns = (meta.plugin_id or "").lower()
            for c_name in meta.commands:
                cl = c_name.lower()
                if is_cand:
                    cmd_to_jar.setdefault(cl, j_name)
                    if p_ns:
                        cmd_to_jar.setdefault(f"{p_ns}:{cl}", j_name)
                else:
                    prereq_cmds.add(cl)
                    if p_ns:
                        prereq_cmds.add(f"{p_ns}:{cl}")
            for al, canon in meta.command_aliases.items():
                al_l, canon_l = al.lower(), canon.lower()
                alias_to_canon[al_l] = canon_l
                if is_cand:
                    cmd_to_jar.setdefault(al_l, j_name)
                    if p_ns:
                        cmd_to_jar.setdefault(f"{p_ns}:{al_l}", j_name)
                else:
                    prereq_cmds.add(al_l)

        # Group allowed executable nodes by their root command token
        by_root: dict[str, list[CommandNode]] = {}
        for n in allowed:
            r_tok = (n._root_id or n.node_id.split()[0]).lower()
            by_root.setdefault(r_tok, []).append(n)

        # Collapse plugin.yml aliases onto canonical root commands present in `by_root`
        canonical_roots: dict[str, list[CommandNode]] = {}
        for r_tok, n_list in by_root.items():
            bare_r = r_tok.split(":", 1)[1] if ":" in r_tok else r_tok
            canon = alias_to_canon.get(bare_r, bare_r)
            target_root = r_tok
            if bare_r == "betterrtp" and "rtp" in by_root:
                target_root = "rtp"
            elif canon != bare_r:
                # Prefer `rtp` if `rtp` is present, else `canon` if `canon` is present
                if "rtp" in by_root and (bare_r == "rtp" or canon == "betterrtp" or alias_to_canon.get("rtp") == canon):
                    target_root = "rtp"
                elif canon in by_root:
                    target_root = canon
            if target_root != r_tok and target_root in by_root:
                root_node = tree.nodes.get(target_root)
                if root_node is not None and r_tok not in root_node.aliases:
                    root_node.aliases.append(r_tok)
                continue
            canonical_roots[r_tok] = n_list

        # Within each root command, prefer typed Brigadier argument nodes over bare 0-arg literals,
        # and bare 0-arg literals over untyped Bukkit `<args>` (`GREEDY_PHRASE`) wrappers.
        selected_per_root: dict[str, list[CommandNode]] = {}
        for r_tok, n_list in canonical_roots.items():
            typed_nodes = [
                n
                for n in n_list
                if n.kind == "argument"
                and not (
                    n.name == "args"
                    and n.parser == "brigadier:string"
                    and str(n.properties.get("value", "")).upper() == "GREEDY_PHRASE"
                    and not n.completions
                )
            ]
            literal_nodes = [n for n in n_list if n.kind == "literal"]
            if typed_nodes:
                selected_per_root[r_tok] = typed_nodes
            elif literal_nodes:
                selected_per_root[r_tok] = literal_nodes
            else:
                selected_per_root[r_tok] = n_list

        # Filter to candidate plugin commands when JarIndex identifies them, and cap per jar
        builtin_roots = {
            "?", "about", "help", "icanhasbukkit", "me", "msg", "pl", "plugins",
            "teammsg", "tell", "tm", "trigger", "ver", "version", "w",
        }
        focus_pfx = self.focus_jar_prefix
        focused_jar_name = next(
            (j for j in candidate_jars if focus_pfx and j.lower().startswith(focus_pfx)),
            None,
        )
        dynamic_cand_jars = [
            j for j in candidate_jars if not (jar_index.jars.get(j) and jar_index.jars[j].commands)
        ]
        rtp_family_roots = {"rtp", "wild", "randomtp", "forcertp", "justrtp", "asyrtp", "betterrtp"}
        cand_jar_nodes: dict[str, list[CommandNode]] = {}
        unassigned_nodes: list[CommandNode] = []
        for r_tok, n_list in selected_per_root.items():
            bare_r = r_tok.split(":", 1)[1] if ":" in r_tok else r_tok
            ns_r = r_tok.split(":", 1)[0] if ":" in r_tok else ""
            if ns_r in ("minecraft", "bukkit", "paper", "spigot") or bare_r in builtin_roots or r_tok in prereq_cmds or bare_r in prereq_cmds:
                continue
            owner_jar = cmd_to_jar.get(r_tok) or cmd_to_jar.get(bare_r)
            if not owner_jar and ns_r:
                owner_jar = jar_index.id_to_jar.get(ns_r)
            if not owner_jar and bare_r in jar_index.id_to_jar:
                owner_jar = jar_index.id_to_jar.get(bare_r)
            if not owner_jar and focused_jar_name and bare_r in rtp_family_roots:
                owner_jar = focused_jar_name
            if not owner_jar and len(dynamic_cand_jars) == 1 and bare_r in rtp_family_roots:
                owner_jar = dynamic_cand_jars[0]
            if owner_jar and owner_jar in candidate_jars:
                cand_jar_nodes.setdefault(owner_jar, []).extend(n_list)
            else:
                unassigned_nodes.extend(n_list)

        keep_ids: set[str] = set()
        focused_default: list[str] = []
        if cand_jar_nodes:
            for j_name, j_nodes in sorted(cand_jar_nodes.items()):
                j_nodes.sort(
                    key=lambda n: (
                        0 if (n.node_id.split()[0].lower() in rtp_family_roots) else 1,
                        0 if n.kind == "argument" else 1,
                        len(n.node_id),
                        n.node_id,
                    )
                )
                is_focused = bool(focus_pfx and j_name.lower().startswith(focus_pfx))
                jar_cap = max(12, max_cmds_per_jar * 3) if is_focused else (2 if focus_pfx else max(1, max_cmds_per_jar))
                for n in j_nodes[:jar_cap]:
                    keep_ids.add(n.node_id)
                    if is_focused and (n.min_tier or "default") == "default":
                        focused_default.append(n.node_id)
        else:
            for n in unassigned_nodes:
                keep_ids.add(n.node_id)
        self._focused_default_nodes = focused_default

        if not keep_ids:
            return tree

        cloned_nodes: dict[str, CommandNode] = {}
        for nid, n in tree.nodes.items():
            cp = CommandNode.from_dict(n.to_dict())
            if cp.executable and not cp.denied and nid not in keep_ids:
                cp.executable = False
            cloned_nodes[nid] = cp
        return CommandTree(
            tiers=list(tree.tiers),
            nodes=cloned_nodes,
            root_children=list(tree.root_children),
            alias_map=dict(tree.alias_map),
            denylist_entries=list(tree.denylist_entries),
            truncated=tree.truncated,
        )

    def run_active(self) -> dict[str, Any]:
        """Execute live active whole-server discovery fuzzing (`--mode server --active`, ADR-125)."""
        os.makedirs(self.run_dir, exist_ok=True)
        warnings: list[str] = []

        # 1. Offline JarIndex inventory
        jar_index = self.jar_index or JarIndex.from_server_dir(self.server_dir, self.profile)
        installed_jars = sorted(jar_index.jars.keys())
        prereq_jars = sorted(j for j in installed_jars if self.profile.is_prerequisite_jar(j))
        candidate_jars = sorted(j for j in installed_jars if not self.profile.is_prerequisite_jar(j))

        # 2. Proof-of-ownership RCON handshake before opening any bot connection
        props = bf.bo.read_properties(self.server_dir)
        mc_port = resolve_server_port(props, getattr(self.args, "mc_port", None))
        drv = self._build_driver(props)
        names_online = bf.online_names(drv)
        if names_online is None:
            raise ValueError(
                f"RCON proof-of-ownership handshake failed against {getattr(self.args, 'host', '127.0.0.1')} "
                "(check that the disposable bench server is running and rcon.password matches)"
            )
        foreign = bf.foreign_online(names_online)
        if foreign and not getattr(self.args, "allow_online", False):
            raise ValueError(
                f"non-fuzz players online ({foreign[:5]}); stop external clients or pass --allow-online"
            )

        # 3. Observer setup: JcmdObserver (local / --ssh / --container)
        obs = self._build_observer()
        obs.resolve_pid()

        raw_tiers = getattr(self.args, "tiers", "default")
        tiers = sort_tiers([t.strip().lower() for t in str(raw_tiers or "default").split(",") if t.strip()]) or ["default"]
        episode_max_s = float(getattr(self.args, "episode_max_s", 25.0) or 25.0)
        discover_max_ms = max(1000, min(60000, int(episode_max_s * 1000)))
        episode_max_ms = max(2000, min(300000, int(episode_max_s * 1000)))
        stall_ms = float(getattr(self.args, "stall_ms", None) or 2000.0)
        default_share = float(getattr(self.args, "default_budget_share", None) or 0.75)
        n_episodes = max(1, int(getattr(self.args, "episodes", 12) or 12))
        n_baseline = getattr(self.args, "baseline_episodes", None)
        n_baseline_eps = max(1, int(n_baseline if n_baseline is not None else min(4, max(2, n_episodes // 3))))
        finding_n = max(1, int(getattr(self.args, "finding_n", 3) or 3))
        finding_k = max(1, min(finding_n, int(getattr(self.args, "finding_k", 2) or 2)))
        seeds = [int(s) for s in str(getattr(self.args, "seeds", "1") or "1").split(",") if s.strip()] or [1]
        seed = seeds[0]
        rng = random.Random(seed)

        own_swarm = self.swarm is None
        swarm = self.swarm
        if own_swarm:
            swarm = bf.Swarm(
                bf.swarm_argv(self.args, mc_port),
                os.path.join(self.run_dir, "swarm.log"),
            )

        opped_bots: set[str] = set()

        def _grant_op(bot_name: str) -> None:
            if bot_name not in opped_bots:
                drv.try_cmd(f"op {bot_name}")
                opped_bots.add(bot_name)

        def _revoke_all_ops() -> None:
            for b_name in sorted(list(opped_bots)):
                try:
                    drv.try_cmd(f"deop {b_name}")
                except Exception:
                    pass
                opped_bots.discard(b_name)

        baseline_records: list[dict[str, Any]] = []
        explore_records: list[dict[str, Any]] = []
        ramp_records: list[dict[str, Any]] = []
        traces_path = os.path.join(self.run_dir, "episodes.jsonl")
        target_players = getattr(self.args, "target_players", None)
        ramp_rungs_raw = getattr(self.args, "ramp_rungs", None)
        ramp_rungs = build_survival_ramp_rungs(
            target_players=int(target_players) if target_players is not None else None,
            explicit_rungs=str(ramp_rungs_raw) if ramp_rungs_raw is not None else None,
        )

        try:
            if own_swarm:
                swarm.start()

            # 4. Multi-tier protocol discovery (`declare_commands`)
            tier_packets: dict[str, dict[str, Any]] = {}
            for tier in tiers:
                disc_bot = bf.bot_name(self.stamp, seed, f"d{tier[:3]}", 0)
                if tier == "op":
                    _grant_op(disc_bot)
                try:
                    pkt, _ = swarm.discover(
                        f"discover-{tier}",
                        tier=tier,
                        bot=disc_bot,
                        max_ms=discover_max_ms,
                    )
                    tier_packets[tier] = pkt
                finally:
                    if tier == "op":
                        _revoke_all_ops()

            commands_path = os.path.join(self.run_dir, "commands.json")
            tree = merge_command_trees(
                tier_packets,
                denylist=self.denylist,
                out_path=commands_path,
            )
            if tree.truncated:
                warnings.append(
                    f"Command graph truncated at {len(tree.nodes)} nodes; inspect {commands_path}."
                )

            allowed_exec = tree.allowed_executable_nodes(tiers)
            if not allowed_exec:
                raise ValueError(f"no allowed executable command nodes discovered for tiers={tiers}")

            explore_tree = self._build_active_exploration_tree(tree, jar_index, candidate_jars, tiers)
            active_exec = explore_tree.allowed_executable_nodes(tiers)
            bf.log(
                f"server active: discovered {len(allowed_exec)} executable nodes "
                f"({len(active_exec)} selected for active exploration: {[n.node_id for n in active_exec]}); "
                f"candidate_jars={candidate_jars}"
            )

            prev_histo = obs.class_histogram()

            def _run_live_episode(
                ep_label: str,
                bots_prog: list[dict[str, Any]],
                *,
                node_id: str | None = None,
                follow_up: str | None = None,
                need_op: bool = False,
                record_trace: bool = True,
            ) -> dict[str, Any]:
                nonlocal prev_histo
                if own_swarm and obs.exclusion_windows:
                    rem_ms = obs.exclusion_windows[-1].end_ms - obs._clock_ms()
                    if rem_ms > 0:
                        time.sleep((rem_ms + 30) / 1000.0)
                live_bots = _clone_program_structure(bots_prog)
                if own_swarm:
                    obs_wait_ms = max(1500, min(5000, int(stall_ms * 1.5 + 1100)))
                    for b_spec in live_bots:
                        acts = b_spec.get("program")
                        if isinstance(acts, list) and acts and acts[-1].get("a") not in ("quit", "rejoin"):
                            if acts[-1].get("a") == "wait":
                                acts[-1]["ms"] = max(int(acts[-1].get("ms", 0) or 0), obs_wait_ms)
                            else:
                                acts.append({"a": "wait", "ms": obs_wait_ms})

                if need_op:
                    for b_spec in live_bots:
                        _grant_op(str(b_spec["name"]))

                sampled_dumps: list[str] = []
                stop_sampler = threading.Event()
                sampler_thread: threading.Thread | None = None
                if own_swarm:
                    def _sample_inflight_threads() -> None:
                        if stop_sampler.wait(0.45):
                            return
                        for _ in range(8):
                            if stop_sampler.is_set():
                                break
                            try:
                                td = obs.thread_print(record_window=False)
                                parsed = parse_main_thread_frame(td, profile=self.profile, jar_index=jar_index)
                                if parsed.get("culprit_frame"):
                                    sampled_dumps.append(td)
                                    break
                                if not sampled_dumps:
                                    sampled_dumps.append(td)
                            except Exception:
                                pass
                            if stop_sampler.wait(0.4):
                                break

                    sampler_thread = threading.Thread(
                        target=_sample_inflight_threads,
                        name="jcmd-stall-sampler",
                        daemon=True,
                    )
                    sampler_thread.start()

                try:
                    eff_max_ms = (
                        max(episode_max_ms, 20000 + len(live_bots) * 350)
                        if len(live_bots) > 4
                        else episode_max_ms
                    )
                    req = bf.episode_request(ep_label[:64], "rtp", live_bots, eff_max_ms)
                    req["telemetry"] = True
                    try:
                        events, done = swarm.run_episode(req, req["max_ms"] / 1000.0 + 15.0)
                    except TypeError:
                        events, done = swarm.run_episode(
                            ep_label[:64],
                            "rtp",
                            live_bots,
                            eff_max_ms,
                            eff_max_ms / 1000.0 + 15.0,
                            telemetry=True,
                        )
                finally:
                    stop_sampler.set()
                    if sampler_thread is not None:
                        sampler_thread.join(timeout=2.0)
                    if need_op:
                        _revoke_all_ops()

                tick_gaps = [e for e in events if isinstance(e, dict) and e.get("ev") == "tick_gap"]
                kicks = sum(1 for e in events if isinstance(e, dict) and e.get("ev") == "kick")
                departures = sum(
                    1
                    for b in bots_prog
                    for act in (b.get("program") or ())
                    if isinstance(act, dict) and act.get("a") in ("quit", "rejoin")
                )

                # Allow deferred 1-2s warmup/timer tasks (e.g. BetterRTP RTPDelay, queue timers) to fire
                # after bot departure so their allocations are captured in the episode that scheduled them.
                if own_swarm and departures > 0:
                    time.sleep(2.2)

                # If an acute tick gap was observed outside observer windows, attach in-flight or post main-thread dump
                thread_dumps: list[str] = []
                valid_gaps, _ = filter_tick_gaps(tick_gaps, obs.exclusion_windows)
                max_gap = max((float(g.get("ms", 0.0) or 0.0) for g in valid_gaps), default=0.0)
                if max_gap >= stall_ms:
                    for td in sampled_dumps:
                        thread_dumps.append(td)
                    has_culprit = any(
                        parse_main_thread_frame(td, profile=self.profile, jar_index=jar_index).get("culprit_frame")
                        for td in thread_dumps
                    )
                    if not has_culprit:
                        try:
                            thread_dumps.append(obs.thread_print(record_window=not own_swarm))
                        except JcmdError:
                            pass

                curr_histo = obs.class_histogram()
                deltas: dict[str, float] = {}
                all_cls = set(prev_histo.keys()) | set(curr_histo.keys())
                for c_name in all_cls:
                    clean_c = _clean_raw_histogram_class(c_name)
                    if not clean_c or GC_FILLER_RE.search(clean_c):
                        continue
                    i0 = prev_histo.get(c_name, (0, 0))[0]
                    i1 = curr_histo.get(c_name, (0, 0))[0]
                    diff = float(i1 - i0)
                    if diff != 0.0:
                        deltas[f"{CLS_PREFIX}{clean_c}"] = diff
                prev_histo = curr_histo

                states = bf.extract_states(events, [b["name"] for b in bots_prog])
                rec: dict[str, Any] = {
                    "seed": seed,
                    "ep": ep_label,
                    "node": node_id,
                    "follow_up": follow_up,
                    "bots": bots_prog,
                    "departures": departures,
                    "kicks": kicks,
                    "tick_gaps": tick_gaps,
                    "thread_dumps": thread_dumps,
                    "exclusion_windows": [w.to_dict() for w in obs.exclusion_windows],
                    "deltas": deltas,
                    "states": states,
                    "ms": done.get("ms", 0),
                }
                if record_trace:
                    bf.append_jsonl(traces_path, rec)
                return rec

            # 5. Idle / movement baseline episodes
            for b_idx in range(n_baseline_eps):
                b_bot = bf.bot_name(self.stamp, seed, f"base{b_idx}", 0)
                base_prog = [
                    {
                        "name": b_bot,
                        "program": [
                            {"a": "wait", "ms": 200},
                            {"a": "move", "dx": 8, "dz": 8, "ms": 300},
                            {"a": "wait", "ms": 200},
                        ],
                    }
                ]
                b_rec = _run_live_episode(
                    f"base-{b_idx}",
                    base_prog,
                    node_id=None,
                    follow_up="idle",
                    need_op=False,
                )
                baseline_records.append(b_rec)
                bf.log(f"server active: baseline {b_idx + 1}/{n_baseline_eps} done ({b_rec.get('ms', 0)} ms)")

            # 5b. Optional progressive player-concurrency survival ramp (--target-players / --ramp-rungs)
            for rung_n in ramp_rungs:
                wave_bots = build_ramp_wave_program(
                    rung_n,
                    explore_tree,
                    rng,
                    denylist=self.denylist,
                    tiers=["default"],
                    bot_prefix=f"fzr{rung_n}",
                    preferred_node_ids=self._focused_default_nodes,
                )
                r_rec = _run_live_episode(
                    f"ramp-{rung_n}",
                    wave_bots,
                    node_id=None,
                    follow_up="survival_ramp",
                    need_op=False,
                )
                r_rec["players"] = int(rung_n)
                r_rec["disconnects"] = int(r_rec.get("kicks", 0) or 0)
                ramp_records.append(r_rec)
                r_gaps, _ = filter_tick_gaps(r_rec.get("tick_gaps") or (), obs.exclusion_windows)
                r_max = max((float(g.get("ms", 0.0) or 0.0) for g in r_gaps), default=0.0)
                bf.log(
                    f"server active: survival ramp players={rung_n} max_gap={r_max:.0f}ms "
                    f"disconnects={r_rec['disconnects']}"
                )
                if r_rec["disconnects"] > 0 or r_max > stall_ms * 2.5:
                    bf.log(f"server active: stopping survival ramp early after failure at players={rung_n}")
                    break

            # 6. Tier-stratified UCB1 exploration over discovered command nodes & player-taxonomy shapes
            priority_shapes = (
                "quit_lt1s",
                "quit_delay",
                "repeat",
                "homesteader_reroll",
                "rejoin",
                "coop_group",
                "explorer_wander",
                "concurrent",
                "window_click",
            )
            bandit = NodeBandit(
                explore_tree,
                tiers=tiers,
                default_budget_share=default_share,
                rng=rng,
                shapes=priority_shapes,
            )
            seen_states: set[tuple[str, str]] = set()
            node_pulls: dict[str, int] = {}
            node_stalled: set[str] = set()
            node_leaked: set[str] = set()
            max_node_pulls = max(6, n_episodes // max(2, len(active_exec)))
            cand_jar_set = set(candidate_jars)
            base_max_delta: dict[str, float] = {}
            for b_rec in baseline_records:
                for k_cls, v_delta in (b_rec.get("deltas") or {}).items():
                    if v_delta > base_max_delta.get(k_cls, 0.0):
                        base_max_delta[k_cls] = float(v_delta)

            leak_confirm_shapes = ("quit_lt1s", "quit_lt1s", "rejoin", "quit_lt1s", "rejoin", "quit_lt1s")
            for ep_idx in range(n_episodes):
                # 1) Ensure every active node gets at least 1 pull (and typed argument nodes get up to 3 pulls
                #    if not yet stalled) before saturating leak confirmation pulls.
                forced_choice: tuple[str, str] | None = None
                unvisited = [
                    n.node_id for n in active_exec if node_pulls.get(n.node_id, 0) == 0
                ]
                if unvisited:
                    unvisited.sort(
                        key=lambda nid: (
                            0 if tree.nodes[nid].kind == "argument" else 1,
                            len(nid),
                            nid,
                        )
                    )
                    next_nid = unvisited[0]
                    next_sh = "quit_delay" if tree.nodes[next_nid].kind == "argument" and "scan" in next_nid else "quit_lt1s"
                    if (next_nid, next_sh) not in bandit.arms:
                        next_sh = priority_shapes[0]
                    forced_choice = (next_nid, next_sh)
                else:
                    for n in active_exec:
                        if (
                            n.kind == "argument"
                            and n.node_id not in node_stalled
                            and n.node_id not in node_leaked
                            and node_pulls.get(n.node_id, 0) < 3
                        ):
                            sh = "explorer_wander" if node_pulls.get(n.node_id, 0) == 1 else "quit_delay"
                            forced_choice = (n.node_id, sh)
                            break
                if forced_choice is None:
                    for l_nid in sorted(node_leaked):
                        p_cnt = node_pulls.get(l_nid, 0)
                        if p_cnt < 6:
                            sh = leak_confirm_shapes[min(p_cnt, len(leak_confirm_shapes) - 1)]
                            forced_choice = (l_nid, sh)
                            break

                if forced_choice is not None:
                    node_id, shape = forced_choice
                    if (node_id, shape) in bandit.arms:
                        bandit.arms[(node_id, shape)].pulls += 1
                        bandit.total_pulls += 1
                        st_tier = bandit.arms[(node_id, shape)].min_tier
                        bandit.stratum_pulls[st_tier] = bandit.stratum_pulls.get(st_tier, 0) + 1
                else:
                    node_id, shape = bandit.select_arm()

                node_pulls[node_id] = node_pulls.get(node_id, 0) + 1
                node_obj = tree.nodes[node_id]
                need_op = node_obj.min_tier != "default"
                b0 = bf.bot_name(self.stamp, seed, f"e{ep_idx}", 0)
                b1 = bf.bot_name(self.stamp, seed, f"e{ep_idx}", 1)
                bots_prog = build_arm_program(
                    node_obj,
                    shape,
                    tree,
                    rng,
                    denylist=self.denylist,
                    bot_names=[b0, b1],
                )
                rec = _run_live_episode(
                    str(ep_idx),
                    bots_prog,
                    node_id=node_id,
                    follow_up=shape,
                    need_op=need_op,
                )
                explore_records.append(rec)

                new_pairs = 0
                for st_pair in rec.get("states") or ():
                    if isinstance(st_pair, (list, tuple)) and len(st_pair) == 2:
                        t_pair = (str(st_pair[0]), str(st_pair[1]))
                        if t_pair not in seen_states:
                            seen_states.add(t_pair)
                            new_pairs += 1

                # Score positive class growth on candidate plugin classes above idle join baseline
                cand_pos_growth = 0.0
                for k_cls, v_delta in (rec.get("deltas") or {}).items():
                    net_delta = float(v_delta) - base_max_delta.get(k_cls, 0.0)
                    if net_delta <= 0:
                        continue
                    c_clean = k_cls[len(CLS_PREFIX) :] if k_cls.startswith(CLS_PREFIX) else k_cls
                    if not cand_jar_set:
                        cand_pos_growth += net_delta
                    else:
                        owner_j = jar_index.owner_of_class(c_clean, profile=self.profile)
                        if owner_j in cand_jar_set and "$$Lambda" not in c_clean:
                            cand_pos_growth += net_delta

                valid_gaps, _ = filter_tick_gaps(rec.get("tick_gaps") or (), obs.exclusion_windows)
                max_gap = max((float(g.get("ms", 0.0) or 0.0) for g in valid_gaps), default=0.0)
                gap_excess = max(0.0, max_gap - stall_ms)
                if max_gap >= stall_ms:
                    node_stalled.add(node_id)
                if cand_pos_growth >= 2.0 and rec.get("departures", 0) > 0:
                    node_leaked.add(node_id)
                elif rec.get("departures", 0) > 0 and cand_pos_growth <= 0.0:
                    node_leaked.discard(node_id)

                usage_err = any(
                    "unknown command" in str(p[1]).lower() or "usage" in str(p[1]).lower()
                    for p in (rec.get("states") or ())
                    if len(p) == 2
                )
                bandit.update(
                    node_id,
                    shape,
                    novelty=float(new_pairs),
                    class_growth=min(500.0, cand_pos_growth * 25.0),
                    tick_gap_excess_ms=min(2000.0, gap_excess),
                    kicks=int(rec.get("kicks", 0) or 0),
                    usage_error=usage_err,
                )
                # Once a node has reached max_node_pulls (or confirmed a stall without a pending leak), damp its arms
                if node_pulls[node_id] >= max_node_pulls or (
                    node_id in node_stalled and node_id not in node_leaked and node_pulls[node_id] >= 2
                ):
                    for (a_nid, a_sh), a_st in bandit.arms.items():
                        if a_nid == node_id:
                            a_st.pulls = max(a_st.pulls, 50)
                            a_st.total_reward = min(a_st.total_reward, 0.0)

                bf.log(
                    f"server active: ep {ep_idx + 1}/{n_episodes} node={node_id!r} shape={shape} "
                    f"max_gap={max_gap:.0f}ms cand_growth=+{cand_pos_growth:.0f} pulls={node_pulls[node_id]}"
                )

            # 7. Live reproduction, shrinking, numeric bisection & jar ddmin callbacks
            repro_counter = {"idx": 0}
            current_active_jars: set[str] = set(candidate_jars)

            def _repro_candidate_once(prog_bots: list[dict[str, Any]], cand: dict[str, Any]) -> bool:
                repro_counter["idx"] += 1
                r_idx = repro_counter["idx"]
                c_node = cand.get("node")
                node_obj = tree.nodes.get(str(c_node)) if c_node and str(c_node) in tree.nodes else None
                need_op = bool(node_obj and node_obj.min_tier != "default")
                fresh_bots = [
                    {
                        "name": bf.bot_name(self.stamp, seed, f"r{r_idx}", i),
                        "program": _clone_program_structure(b.get("program") or []),
                    }
                    for i, b in enumerate(prog_bots)
                ]
                rec = _run_live_episode(
                    f"repro-{r_idx}",
                    fresh_bots,
                    node_id=str(c_node) if c_node else None,
                    follow_up=cand.get("follow_up"),
                    need_op=need_op,
                    record_trace=False,
                )
                oracle = cand.get("oracle")
                sig = str(cand.get("signature") or "")
                if oracle == "leak":
                    parts = sig.split(":", 2)
                    leak_cls = parts[1] if len(parts) > 1 else ""
                    delta_val = float((rec.get("deltas") or {}).get(f"{CLS_PREFIX}{leak_cls}", 0.0) or 0.0)
                    return delta_val > 0.0
                if oracle == "stall":
                    valid_g, _ = filter_tick_gaps(rec.get("tick_gaps") or (), obs.exclusion_windows)
                    return any(float(g.get("ms", 0.0) or 0.0) >= stall_ms for g in valid_g)
                return False

            no_restart = bool(getattr(self.args, "no_restart", False))

            def _ensure_active_jars(active_subset: list[str]) -> bool:
                nonlocal prev_histo, current_active_jars
                target_set = set(active_subset)
                if not no_restart and target_set != current_active_jars:
                    bf.log(f"server active: ddmin_jars toggling active_subset={sorted(target_set)}")
                    self._set_candidate_jars_state(jar_index, candidate_jars, active_subset)
                    if not self._restart_driver(drv):
                        return False
                    current_active_jars = set(active_subset)
                    obs.resolve_pid(refresh=True)
                    prev_histo = obs.class_histogram()
                    if not self._verify_loaded_plugins(drv, jar_index, active_subset):
                        return False
                return True

            def _run_repro_probe(prog_bots: list[dict[str, Any]], cand: dict[str, Any]) -> tuple[int, int, bool]:
                hits = 0
                for _ in range(finding_n):
                    if _repro_candidate_once(prog_bots, cand):
                        hits += 1
                return hits, finding_n, hits >= finding_k

            def _shrink_probe(prog_bots: list[dict[str, Any]], cand: dict[str, Any]) -> tuple[int, int, bool]:
                if not _ensure_active_jars(candidate_jars):
                    return 0, finding_n, False
                return _run_repro_probe(prog_bots, cand)

            def _bisect_pred(prog_bots: list[dict[str, Any]], cand: dict[str, Any]) -> bool:
                _, _, ok = _shrink_probe(prog_bots, cand)
                return ok

            def _jar_probe(active_subset: list[str], cand: dict[str, Any]) -> tuple[int, bool]:
                prog_bots = cand.get("shrunk_program") or cand.get("program") or []
                if not _ensure_active_jars(active_subset):
                    return 0, False
                hits, _, _ = _run_repro_probe(prog_bots, cand)
                return hits, True

            try:
                eval_verdict = evaluate_server_trace(
                    explore_records,
                    baseline_records=baseline_records,
                    tree=tree,
                    jar_index=jar_index,
                    profile=self.profile,
                    denylist=self.denylist,
                    stall_ms=stall_ms,
                    finding_k=finding_k,
                    finding_n=finding_n,
                    shrink_probe_fn=_shrink_probe,
                    bisect_predicate_fn=_bisect_pred,
                    jar_probe_fn=_jar_probe,
                    server_dir=self.server_dir,
                    ramp_records=ramp_records,
                    target_players=int(target_players) if target_players is not None else (ramp_rungs[-1] if ramp_rungs else None),
                )
            finally:
                if not no_restart and candidate_jars:
                    self._set_candidate_jars_state(jar_index, candidate_jars, candidate_jars)
                    if current_active_jars != set(candidate_jars) and not self.focus_jar_prefix:
                        self._restart_driver(drv)

        finally:
            _revoke_all_ops()
            if own_swarm and swarm is not None:
                swarm.close()

        eval_verdict["status"] = "active_completed"
        eval_verdict["tiers"] = tiers
        eval_verdict["baseline"] = {
            "available": True,
            "status": "collected",
            "episodes": len(baseline_records),
        }
        eval_verdict["commands"] = {
            "file": commands_path,
            "total_nodes": len(tree.nodes),
            "allowed_executable_nodes": len(allowed_exec),
            "denied_nodes": sum(1 for n in tree.nodes.values() if n.denied),
            "truncated": bool(tree.truncated),
            "denylist": list(self.denylist.entries),
        }
        eval_verdict["jars"] = {
            "jar_dir": jar_index.jar_dir,
            "installed_jars": installed_jars,
            "prerequisite_jars": prereq_jars,
            "candidate_jars": candidate_jars,
            "indexed_classes": len(jar_index.class_to_jars),
        }
        eval_verdict["warnings"] = warnings
        bit_report, bit_rows = build_server_bit_report(eval_verdict)
        eval_verdict["bit_report"] = bit_report
        eval_verdict["bit_table_csv"] = render_bit_table_csv(bit_rows)
        eval_verdict["report_text"] = format_server_findings_report(eval_verdict)

        verdict_path = os.path.join(self.run_dir, getattr(bf, "VERDICT_FILE", "verdict.json"))
        bf.save_json(verdict_path, eval_verdict)
        bit_report_path = os.path.join(self.run_dir, "bit-report.json")
        bf.save_json(bit_report_path, bit_report)
        bit_table_path = os.path.join(self.run_dir, "bit-table.csv")
        with open(bit_table_path, "w", encoding="utf-8") as fh:
            fh.write(eval_verdict["bit_table_csv"])
        report_path = os.path.join(self.run_dir, getattr(bf, "REPORT_FILE", "report.txt"))
        with open(report_path, "w", encoding="utf-8") as fh:
            fh.write(eval_verdict["report_text"])

        return eval_verdict


def format_isolated_targets_comparison(
    target_verdicts: dict[str, dict[str, Any]],
) -> tuple[str, str]:
    """Render an aligned CSV and declarative text report comparing isolated one-at-a-time server targets."""
    cols = (
        "target",
        "jar",
        "verdict",
        "health",
        "max_surviving_players",
        "first_failing_players",
        "p95_gap_ms",
        "max_gap_ms",
        "leaks",
        "stalls",
        "log_errors",
        "blamed_jars",
    )
    rows: list[dict[str, str]] = []
    for target, v in target_verdicts.items():
        if v.get("status") == "skipped_missing_jar":
            rows.append(
                {
                    "target": target,
                    "jar": str(v.get("jar") or "MISSING"),
                    "verdict": "SKIP",
                    "health": "SKIPPED",
                    "max_surviving_players": "-",
                    "first_failing_players": "-",
                    "p95_gap_ms": "-",
                    "max_gap_ms": "-",
                    "leaks": "0",
                    "stalls": "0",
                    "log_errors": "0",
                    "blamed_jars": "-",
                }
            )
            continue
        env = v.get("survival_envelope") or {}
        findings = [f for f in (v.get("findings") or ()) if isinstance(f, dict) and f.get("reproduced", True)]
        leaks = sum(1 for f in findings if f.get("oracle") == "leak")
        stalls = sum(1 for f in findings if f.get("oracle") == "stall")
        logs = sum(1 for f in findings if f.get("oracle") == "log")
        imp = v.get("command_impact_table") or []
        worst_p95 = max((float(r.get("p95_gap_ms", 0.0) or 0.0) for r in imp if isinstance(r, dict)), default=0.0)
        worst_max = max((float(r.get("max_gap_ms", 0.0) or 0.0) for r in imp if isinstance(r, dict)), default=0.0)
        for rr in (env.get("rungs") or ()):
            if isinstance(rr, dict):
                worst_p95 = max(worst_p95, float(rr.get("tick_gap_p95_ms", 0.0) or 0.0))
                worst_max = max(worst_max, float(rr.get("tick_gap_max_ms", 0.0) or 0.0))
        blamed = ";".join(str(x) for x in (v.get("confirmed_culprits") or v.get("blamed_jars") or ())) or "-"
        rows.append(
            {
                "target": target,
                "jar": str(v.get("focused_jar") or "-"),
                "verdict": str(v.get("verdict") or "UNKNOWN"),
                "health": str((v.get("bit_report") or {}).get("health") or "-"),
                "max_surviving_players": str(env.get("max_surviving_players", "-")),
                "first_failing_players": str(env.get("first_failing_players") or "-"),
                "p95_gap_ms": f"{worst_p95:.1f}" if worst_p95 > 0 else "-",
                "max_gap_ms": f"{worst_max:.1f}" if worst_max > 0 else "-",
                "leaks": str(leaks),
                "stalls": str(stalls),
                "log_errors": str(logs),
                "blamed_jars": blamed,
            }
        )

    widths = [len(c) for c in cols]
    for r in rows:
        for idx, c in enumerate(cols):
            widths[idx] = max(widths[idx], len(str(r.get(c, ""))))

    def _fmt_row(vals: Iterable[str]) -> str:
        parts = []
        for idx, val in enumerate(vals):
            s = str(val).replace(",", ";")
            parts.append(s.rjust(widths[idx]))
        return ", ".join(parts)

    csv_lines = [_fmt_row(cols)]
    for r in rows:
        csv_lines.append(_fmt_row(r.get(c, "") for c in cols))
    csv_text = "\n".join(csv_lines) + "\n"

    txt_lines = [
        f"isolated_targets_count: {len(target_verdicts)}",
        "=== CROSS-COMPETITOR DECLARATIVE BIT & SURVIVAL COMPARISON ===",
        csv_text.rstrip("\n"),
    ]
    for target, v in target_verdicts.items():
        txt_lines.append("")
        txt_lines.append(f"================ TARGET: {target} ({v.get('focused_jar', '-')}) ================")
        if v.get("report_text"):
            txt_lines.append(str(v["report_text"]).rstrip("\n"))
        else:
            txt_lines.append(f"status: {v.get('status')}")
    return csv_text, "\n".join(txt_lines) + "\n"


def _parse_roster_jar_prefixes(raw_jars: str | None = None) -> dict[str, str]:
    spec = raw_jars or getattr(bf.bo, "DEFAULT_JARS", "")
    mapping: dict[str, str] = {}
    for pair in str(spec).split(","):
        t, _, prefix = pair.partition("=")
        if t.strip() and prefix.strip():
            mapping[t.strip().lower()] = prefix.strip().lower()
    mapping.setdefault("fuzzfixture", "fuzzfixture")
    mapping.setdefault("stresstestrtp", "stresstestrtp")
    return mapping


def _isolate_single_roster_jar(
    jar_dir: str,
    roster_prefixes: dict[str, str],
    active_target: str | None,
) -> list[str]:
    """Enable only `active_target`'s jar among `roster_prefixes` in `jar_dir`; rename other roster jars to `.jar.off`."""
    all_prefixes = tuple(sorted(set(roster_prefixes.values())))
    want_prefix = roster_prefixes.get((active_target or "").lower()) if active_target else None
    enabled_matches: list[str] = []
    if not os.path.isdir(jar_dir):
        return enabled_matches
    off_sfx = bf.bo.OFF_SUFFIX
    for fname in sorted(os.listdir(jar_dir)):
        low = fname.lower()
        is_on = low.endswith(".jar")
        is_off = low.endswith(".jar" + off_sfx)
        if not (is_on or is_off):
            continue
        base_jar = fname if is_on else fname[: -len(off_sfx)]
        base_low = base_jar.lower()
        if not any(base_low.startswith(pfx) for pfx in all_prefixes):
            continue
        want_on = bool(want_prefix and base_low.startswith(want_prefix))
        src_path = os.path.join(jar_dir, fname)
        if want_on:
            dst_path = os.path.join(jar_dir, base_jar)
            if fname != base_jar:
                os.replace(src_path, dst_path)
            enabled_matches.append(base_jar)
        else:
            if is_on:
                os.replace(src_path, os.path.join(jar_dir, base_jar + off_sfx))
    return enabled_matches


def run_isolated_server_targets(
    args: Any,
    run_dir: str,
    stamp: str,
    targets: list[str],
    *,
    swarm: Any | None = None,
    observer: JcmdObserver | None = None,
    driver: Any | None = None,
) -> dict[str, Any]:
    """Run `ServerRun.run_active()` sequentially for each target in `targets` with one-at-a-time jar isolation."""
    profile, _ = validate_server_mode_args(args)
    server_dir = os.path.abspath(args.server_dir)
    jar_dir = os.path.join(server_dir, profile.jar_subdir)
    roster_prefixes = _parse_roster_jar_prefixes(getattr(args, "jars", None))
    props = bf.bo.read_properties(server_dir)
    no_restart = bool(getattr(args, "no_restart", False))

    probe_runner = ServerRun(
        args,
        run_dir,
        swarm=swarm,
        observer=observer,
        driver=driver,
        stamp=stamp,
    )
    drv = probe_runner._build_driver(props)

    target_verdicts: dict[str, dict[str, Any]] = {}
    for idx, target in enumerate(targets):
        t_clean = target.strip().lower()
        if not t_clean:
            continue
        pfx = roster_prefixes.get(t_clean, t_clean)
        enabled = _isolate_single_roster_jar(jar_dir, roster_prefixes, t_clean)
        if not enabled:
            bf.log(f"server active isolate: target={t_clean} (prefix={pfx}) has no jar in {jar_dir}; skipping")
            target_verdicts[t_clean] = {
                "target": t_clean,
                "status": "skipped_missing_jar",
                "verdict": "SKIP",
                "jar": f"{pfx}*.jar (missing)",
            }
            continue

        focused_jar = enabled[0]
        bf.log(
            f"server active isolate [{idx + 1}/{len(targets)}]: target={t_clean} "
            f"enabled_jar={focused_jar} restarting={not no_restart}"
        )
        if not no_restart:
            if not probe_runner._restart_driver(drv):
                raise ValueError(f"server failed to restart for isolated target {t_clean!r} ({focused_jar})")
            settle_s = max(2, int(getattr(args, "settle_s", 10) or 10))
            time.sleep(min(15, settle_s))

        t_dir = os.path.join(run_dir, t_clean)
        os.makedirs(t_dir, exist_ok=True)
        t_runner = ServerRun(
            args,
            t_dir,
            swarm=swarm,
            observer=observer,
            driver=drv,
            stamp=f"{stamp}-{t_clean}",
            focus_jar_prefix=pfx,
        )
        t_verdict = t_runner.run_active()
        t_verdict["target"] = t_clean
        t_verdict["focused_jar"] = focused_jar
        bf.save_json(os.path.join(t_dir, getattr(bf, "VERDICT_FILE", "verdict.json")), t_verdict)
        target_verdicts[t_clean] = t_verdict

        # Write incremental cross-target comparison after every completed target
        comp_csv, comp_txt = format_isolated_targets_comparison(target_verdicts)
        with open(os.path.join(run_dir, "comparison.csv"), "w", encoding="utf-8") as fh:
            fh.write(comp_csv)
        with open(os.path.join(run_dir, "comparison.txt"), "w", encoding="utf-8") as fh:
            fh.write(comp_txt)
        with open(os.path.join(run_dir, getattr(bf, "REPORT_FILE", "report.txt")), "w", encoding="utf-8") as fh:
            fh.write(comp_txt)

    comp_csv, comp_txt = format_isolated_targets_comparison(target_verdicts)
    any_fail = any(v.get("verdict") == "FAIL" for v in target_verdicts.values())
    combined_verdict: dict[str, Any] = {
        "mode": "server",
        "profile": profile.name,
        "status": "active_completed",
        "verdict": "FAIL" if any_fail else "PASS",
        "isolated_targets": list(target_verdicts.keys()),
        "targets": {
            t: {
                "focused_jar": v.get("focused_jar"),
                "verdict": v.get("verdict"),
                "status": v.get("status"),
                "health": (v.get("bit_report") or {}).get("health"),
                "survival_envelope": v.get("survival_envelope"),
                "confirmed_culprits": v.get("confirmed_culprits", []),
                "blamed_jars": v.get("blamed_jars", []),
                "findings_count": len(v.get("findings") or ()),
            }
            for t, v in target_verdicts.items()
        },
        "comparison_csv": comp_csv,
        "report_text": comp_txt,
    }
    bf.save_json(os.path.join(run_dir, getattr(bf, "VERDICT_FILE", "verdict.json")), combined_verdict)
    with open(os.path.join(run_dir, "comparison.csv"), "w", encoding="utf-8") as fh:
        fh.write(comp_csv)
    with open(os.path.join(run_dir, "comparison.txt"), "w", encoding="utf-8") as fh:
        fh.write(comp_txt)
    with open(os.path.join(run_dir, getattr(bf, "REPORT_FILE", "report.txt")), "w", encoding="utf-8") as fh:
        fh.write(comp_txt)
    return combined_verdict


def run_server_mode(
    args: Any,
    parser: Any | None = None,
    argv: Iterable[str] | None = None,
    *,
    swarm: Any | None = None,
    observer: JcmdObserver | None = None,
    jar_index: JarIndex | None = None,
    driver: Any | None = None,
) -> int:
    """Entry point for `bench_fuzz.py --mode server` (passive local audit or `--active` live discovery fuzzing)."""
    validate_server_mode_args(args, parser=parser, argv=argv)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    out_dir = getattr(args, "out_dir", None) or os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp")
    run_dir = os.path.join(out_dir, "runs", f"fuzz-{stamp}")
    os.makedirs(run_dir, exist_ok=True)
    prev_log_fh = bf.bo.LOG_FH
    bf.bo.LOG_FH = open(os.path.join(run_dir, "fuzz.log"), "a", encoding="utf-8")
    is_active = bool(getattr(args, "active", False))
    isolate_raw = getattr(args, "isolate_targets", None)
    isolate_list = [t.strip().lower() for t in str(isolate_raw or "").split(",") if t.strip()] if is_active and isolate_raw else []
    mode_label = "server active fuzz" if is_active else "server passive audit"
    try:
        bf.log(
            f"{mode_label}: stamp={stamp} profile={args.profile} "
            f"server_dir={args.server_dir} tiers={getattr(args, 'tiers', 'default')} "
            f"isolate_targets={isolate_list or None} run_dir={run_dir}"
        )
        if isolate_list:
            verdict = run_isolated_server_targets(
                args,
                run_dir,
                stamp,
                isolate_list,
                swarm=swarm,
                observer=observer,
                driver=driver,
            )
        else:
            runner = ServerRun(
                args,
                run_dir,
                swarm=swarm,
                observer=observer,
                jar_index=jar_index,
                driver=driver,
                stamp=stamp,
            )
            verdict = runner.run()
        bf.log(f"done ({verdict.get('status')}); artifacts under {run_dir}")
        return 0
    except (bf.SwarmError, JcmdError, ValueError) as exc:
        bf.log(f"{mode_label} failed: {exc}")
        print(f"error: {exc}", file=sys.stderr)
        return 2
    finally:
        try:
            bf.bo.LOG_FH.close()
        except OSError:
            pass
        bf.bo.LOG_FH = prev_log_fh
