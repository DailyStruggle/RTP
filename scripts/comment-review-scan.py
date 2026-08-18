#!/usr/bin/env python3
"""Comment-quality scanner for Java sources.

A read-only static pass that flags code comments which are candidates for a
design-driven re-comment: comments that carry development process references,
comments that are overly long (essay blocks), and long inline/trailing
comments that are likely poorly placed. It does NOT modify any source; it only
emits a review checklist ranking whole files by "comment debt" so target files
can be re-commented according to design, session by session.

This complements scripts/emdash-comment-fix.py (which already handled the em/en
dash marker). Here we surface the remaining prose-quality markers the user
asked about:
  1. process references  - Step N / Phase N / Slice X / Session N / PR-NN /
                           CHECKLIST-* / *_PLAN.md / PROPOSAL references, which
                           the project's own conventions prohibit in committed
                           comments.
  2. long comment blocks - a contiguous comment unit spanning many lines
                           (generated exposition / essays).
  3. long comment lines  - a single comment content line over the width budget.
  4. long inline comments- trailing end-of-line comments (code precedes //)
                           past a length budget; often poorly placed.

Output: docs/dev/scratch/CHECKLIST-comment-review.md (or sectioned/sharded variants)
"""
import argparse
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# --- tunables -------------------------------------------------------------
LONG_BLOCK_LINES = 8     # comment unit spanning more than this many lines
LONG_LINE_CHARS = 120    # a single comment content line longer than this
INLINE_LONG_CHARS = 60   # trailing inline comment content longer than this

# Process / session-scaffolding references prohibited in committed comments.
PROCESS_PATTERNS = [
    re.compile(r"\bStep\s+\d"),
    re.compile(r"\bPhase\s+(?:\d|[MN]\d|\d[a-z])"),
    re.compile(r"\bSlice\s+[A-Z0-9]"),
    re.compile(r"\bSession\s+\d"),
    re.compile(r"\bPR-\d"),
    re.compile(r"\bCHECKLIST-"),
    re.compile(r"\b[A-Z][A-Z_]+_PLAN\.md"),
    re.compile(r"\bPROPOSAL\b"),
    re.compile(r"\bScratch/|\bscratch/"),
]

# tokenizer states
NORMAL, STR, CHR, LINE_C, BLOCK_C = range(5)


def process_hits(text):
    for pat in PROCESS_PATTERNS:
        m = pat.search(text)
        if m:
            return m.group(0)
    return None


def scan_file(path):
    """Return list of comment units: dicts with start,end,inline,text(list)."""
    with open(path, "r", encoding="utf-8") as f:
        raw = f.read()
    lines = raw.split("\n")

    units = []
    state = NORMAL
    # current block-comment accumulator
    block = None            # {'start':int, 'text':[str]}
    # current run of standalone line comments
    linerun = None          # {'start':int, 'text':[str]}

    for lineno, line in enumerate(lines, start=1):
        i = 0
        n = len(line)
        code_seen_before_comment = False
        line_comment_text = None
        line_comment_inline = False
        block_text_this_line = []
        # if a block comment is open at line start, everything counts as comment
        block_open_at_start = state == BLOCK_C

        while i < n:
            c = line[i]
            nxt = line[i + 1] if i + 1 < n else ""
            if state == NORMAL:
                if c == '"':
                    state = STR
                    code_seen_before_comment = True
                elif c == "'":
                    state = CHR
                    code_seen_before_comment = True
                elif c == "/" and nxt == "/":
                    line_comment_inline = code_seen_before_comment
                    line_comment_text = line[i + 2:].strip()
                    i = n
                    continue
                elif c == "/" and nxt == "*":
                    state = BLOCK_C
                    i += 2
                    continue
                elif not c.isspace():
                    code_seen_before_comment = True
            elif state == STR:
                if c == "\\":
                    i += 1
                elif c == '"':
                    state = NORMAL
            elif state == CHR:
                if c == "\\":
                    i += 1
                elif c == "'":
                    state = NORMAL
            elif state == BLOCK_C:
                if c == "*" and nxt == "/":
                    state = NORMAL
                    i += 2
                    continue
                else:
                    block_text_this_line.append(c)
            i += 1

        # line processing completed
        if line_comment_text is not None:
            if line_comment_inline:
                # inline comments are isolated single units
                if linerun is not None:
                    units.append({"kind": "linerun", **linerun, "inline": False})
                    linerun = None
                units.append({
                    "kind": "inline",
                    "start": lineno,
                    "end": lineno,
                    "inline": True,
                    "text": [line_comment_text],
                })
            else:
                # standalone line comment: accumulate into run
                if linerun is None:
                    linerun = {"start": lineno, "end": lineno, "text": [line_comment_text]}
                else:
                    linerun["end"] = lineno
                    linerun["text"].append(line_comment_text)
        else:
            # no line comment on this line; if a linerun was open, close it
            if linerun is not None and not (state == BLOCK_C or block_open_at_start):
                units.append({"kind": "linerun", **linerun, "inline": False})
                linerun = None

        if block_open_at_start or state == BLOCK_C or block_text_this_line:
            # extract text content (strip leading * margin)
            content = "".join(block_text_this_line).strip()
            # strip leading * if any
            content = re.sub(r"^\*+\s?", "", content)
            if block is None:
                block = {"start": lineno, "end": lineno, "text": [content] if content else []}
            else:
                block["end"] = lineno
                if content:
                    block["text"].append(content)
            if state == NORMAL and block is not None:
                units.append({"kind": "block", **block, "inline": False})
                block = None

    if linerun is not None:
        units.append({"kind": "linerun", **linerun, "inline": False})
    if block is not None:
        block.setdefault("end", len(lines))
        units.append({"kind": "block", **block, "inline": False})

    return units


def analyze(path):
    units = scan_file(path)
    findings = []  # (lineno, category, snippet)
    for u in units:
        joined = " ".join(t for t in u["text"] if t).strip()
        span = u["end"] - u["start"] + 1
        # process references
        hit = process_hits(joined)
        if hit:
            findings.append((u["start"], "process:" + hit, joined))
        # long block / long line-run
        if span > LONG_BLOCK_LINES:
            findings.append((u["start"], f"long-block({span}L)", joined))
        # long single comment content line
        for k, t in enumerate(u["text"]):
            if len(t) > LONG_LINE_CHARS:
                findings.append((u["start"] + k, f"long-line({len(t)}c)", t))
        # long inline / trailing comment
        if u["inline"] and len(joined) > INLINE_LONG_CHARS:
            findings.append((u["start"], f"inline-long({len(joined)}c)", joined))
    return findings


def get_module(rel_path: str) -> str:
    parts = rel_path.split("/")
    if parts[0] in ("platforms", "api", "addons") and len(parts) > 1:
        return f"{parts[0]}/{parts[1]}"
    return parts[0]


def partition_by_module(per_file, max_files=25, max_score=100):
    groups = {}
    for item in per_file:
        rel = item[0]
        mod = get_module(rel)
        if mod not in groups:
            groups[mod] = []
        groups[mod].append(item)

    # Sort groups by total score descending, then module name
    sorted_groups = []
    for mod, items in sorted(groups.items(), key=lambda t: (-sum(x[2] for x in t[1]), t[0])):
        mod_score = sum(x[2] for x in items)
        if (max_files is not None and len(items) > max_files) or (max_score is not None and mod_score > max_score):
            # Split into balanced sub-parts
            n_by_score = (mod_score + max_score - 1) // max_score if max_score else 1
            n_by_files = (len(items) + max_files - 1) // max_files if max_files else 1
            n_parts = max(n_by_score, n_by_files, 2)
            shards = partition_by_shards(items, n_parts)
            for i, (_, s_items) in enumerate(shards, start=1):
                sorted_groups.append((f"{mod} (part {i}/{n_parts})", s_items))
        else:
            sorted_groups.append((mod, items))

    return sorted_groups


def partition_by_shards(per_file, num_shards):
    if num_shards <= 0:
        raise ValueError("Number of shards must be positive")
    if num_shards == 1:
        return [("Shard 1 of 1", per_file)]

    # Greedy bin-packing by score descending to keep shards balanced
    shards = [[] for _ in range(num_shards)]
    shard_scores = [0] * num_shards

    for item in per_file:
        min_idx = min(range(num_shards), key=lambda i: (shard_scores[i], len(shards[i])))
        shards[min_idx].append(item)
        shard_scores[min_idx] += item[2]

    result = []
    for i, shard in enumerate(shards, start=1):
        shard.sort(key=lambda t: (-t[2], t[0]))
        result.append((f"Shard {i} of {num_shards}", shard))
    return result


def write_checklist_file(out_path, sections, process_only, title_suffix=""):
    all_items = [item for _, items in sections for item in items]
    total_files = len(all_items)
    total_process = sum(1 for _, fs, _ in all_items for _, c, _ in fs if c.startswith("process:"))
    total_block = sum(1 for _, fs, _ in all_items for _, c, _ in fs if c.startswith("long-block"))
    total_line = sum(1 for _, fs, _ in all_items for _, c, _ in fs if c.startswith("long-line"))
    total_inline = sum(1 for _, fs, _ in all_items for _, c, _ in fs if c.startswith("inline-long"))

    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        title = "# Comment quality review checklist"
        if title_suffix:
            title += f" - {title_suffix}"
        f.write(f"{title}\n\n")
        f.write("Auto-generated by `scripts/comment-review-scan.py` (read-only static pass).\n\n")
        if process_only:
            f.write("Focused (`--process-only`) pass: lists ONLY files that carry a "
                    "prohibited process/session label (the actual policy violation), "
                    "and shows only those process findings. The subjective "
                    "long-block/long-line candidates are intentionally omitted here "
                    "- they are style candidates, not violations - so this list "
                    "reflects the true required cleanup and is far shorter than the "
                    "full report.\n\n")
        else:
            f.write("Beyond the em/en dash marker (handled separately by "
                    "`scripts/emdash-comment-fix.py`), this list surfaces code comments "
                    "that are candidates for a design-driven re-comment: comments carrying "
                    "development process references, overly long comment blocks (essays), "
                    "long comment lines, and long trailing/inline comments (often poorly "
                    "placed).\n\n")
        f.write("Files are ranked by comment debt score "
                "(process x3, long-block x2, long-line/inline x1) within each section "
                "so whole target files can be re-commented according to design, session by session. "
                "Tick a file off once its comments have been reviewed and rewritten.\n\n")
        f.write(f"- Files flagged: {total_files}\n")
        f.write(f"- Process references: {total_process}\n")
        f.write(f"- Long comment blocks (> {LONG_BLOCK_LINES} lines): {total_block}\n")
        f.write(f"- Long comment lines (> {LONG_LINE_CHARS} chars): {total_line}\n")
        f.write(f"- Long inline comments (> {INLINE_LONG_CHARS} chars): {total_inline}\n\n")
        f.write("Legend: `process:<match>` a prohibited process/session label; "
                "`long-block(NL)` a comment unit spanning N lines; "
                "`long-line(Nc)` a single comment line N chars wide; "
                "`inline-long(Nc)` a trailing inline comment N chars wide.\n\n")

        if len(sections) > 1:
            f.write("---\n\n## Sections Overview\n\n")
            f.write("| Section | Target | Files | Score | Process | Blocks |\n")
            f.write("|---|---|---|---|---|---|\n")
            for idx, (sec_name, sec_items) in enumerate(sections, start=1):
                s_score = sum(it[2] for it in sec_items)
                s_proc = sum(1 for _, fs, _ in sec_items for _, c, _ in fs if c.startswith("process:"))
                s_blk = sum(1 for _, fs, _ in sec_items for _, c, _ in fs if c.startswith("long-block"))
                f.write(f"| Section {idx} | `{sec_name}` | {len(sec_items)} | {s_score} | {s_proc} | {s_blk} |\n")
            f.write("\n")

        for idx, (sec_name, sec_items) in enumerate(sections, start=1):
            s_score = sum(it[2] for it in sec_items)
            f.write("---\n\n")
            if len(sections) > 1:
                f.write(f"## Section {idx}: `{sec_name}` ({len(sec_items)} file(s), score {s_score})\n\n")
            else:
                f.write(f"## Files ({len(sec_items)} file(s), score {s_score})\n\n")
            for rel, findings, score in sec_items:
                f.write(f"- [ ] `{rel}` (score {score}, {len(findings)} finding(s))\n")
                for lineno, cat, snippet in sorted(findings):
                    s = snippet if len(snippet) <= 140 else snippet[:137] + "..."
                    f.write(f"    - L{lineno} [{cat}]: {s}\n")
            f.write("\n")


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--process-only",
        action="store_true",
        help="Only list files that carry at least one prohibited process/session "
             "label, and show only those process findings. This drops the "
             "subjective long-block/long-line candidates (which are not policy "
             "violations) so the checklist reflects the true required cleanup.",
    )
    ap.add_argument(
        "--shards",
        type=int,
        default=None,
        help="Partition the checklist into N balanced sections (by comment debt score) "
             "for concurrent assistant tasks.",
    )
    ap.add_argument(
        "--shard-index",
        type=int,
        default=None,
        help="1-based index (1..N) of a single shard to emit when --shards N is set.",
    )
    ap.add_argument(
        "--split-shards",
        type=int,
        default=None,
        help="Generate N separate checklist files (e.g. CHECKLIST-...-shard-1-of-N.md) "
             "for spawning N independent concurrent assistant tasks.",
    )
    ap.add_argument(
        "--max-files",
        type=int,
        default=25,
        help="Maximum files per section when partitioning (default: 25). Set to 0 to disable.",
    )
    ap.add_argument(
        "--max-score",
        type=int,
        default=100,
        help="Maximum score per section when partitioning (default: 100). Set to 0 to disable.",
    )
    ap.add_argument(
        "--split-sections",
        action="store_true",
        help="Generate separate checklist files for every section (e.g. CHECKLIST-...-sec-1.md) "
             "for spawning concurrent assistant tasks per section.",
    )
    ap.add_argument(
        "--section-index",
        type=int,
        default=None,
        help="1-based index (1..N) of a single section to emit.",
    )
    ap.add_argument(
        "--split-modules",
        action="store_true",
        help="Generate separate checklist files for each module/component (unsliced).",
    )
    ap.add_argument(
        "--module",
        type=str,
        default=None,
        help="Filter findings to only files belonging to matching module or path substring.",
    )
    ap.add_argument(
        "--out",
        type=str,
        default=None,
        help="Custom output file path.",
    )
    args = ap.parse_args()

    per_file = []  # (rel, findings, score)
    for dirpath, dirnames, filenames in os.walk(ROOT):
        if any(seg in dirpath for seg in (os.sep + "build", os.sep + ".gradle", os.sep + ".git")):
            continue
        for fn in filenames:
            if not fn.endswith(".java"):
                continue
            path = os.path.join(dirpath, fn)
            try:
                findings = analyze(path)
            except Exception as e:
                print(f"[WARN] {path}: {e}")
                continue
            if args.process_only:
                findings = [f for f in findings if f[1].startswith("process:")]
            if not findings:
                continue
            rel = os.path.relpath(path, ROOT).replace(os.sep, "/")
            if args.module and args.module.lower() not in rel.lower():
                continue
            score = 0
            for _, cat, _ in findings:
                if cat.startswith("process:"):
                    score += 3
                elif cat.startswith("long-block"):
                    score += 2
                else:
                    score += 1
            per_file.append((rel, findings, score))

    per_file.sort(key=lambda t: (-t[2], t[0]))

    scratch = os.path.join(ROOT, "docs", "dev", "scratch")
    os.makedirs(scratch, exist_ok=True)
    base_stem = ("CHECKLIST-comment-review-process" if args.process_only
                 else "CHECKLIST-comment-review")

    # Handle --split-shards N
    if args.split_shards:
        n = args.split_shards
        shards = partition_by_shards(per_file, n)
        for old in os.listdir(scratch):
            if old.startswith(f"{base_stem}-shard-") and old.endswith(".md"):
                try:
                    os.remove(os.path.join(scratch, old))
                except OSError:
                    pass
        for i, (name, items) in enumerate(shards, start=1):
            out_file = os.path.join(scratch, f"{base_stem}-shard-{i}-of-{n}.md")
            write_checklist_file(out_file, [(name, items)], args.process_only, title_suffix=f"Shard {i} of {n}")
            print(f"[DONE] wrote {os.path.relpath(out_file, ROOT)} ({len(items)} files)")
        return

    # Handle --split-modules
    if args.split_modules:
        modules = partition_by_module(per_file, max_files=None, max_score=None)
        for mod, items in modules:
            safe_mod = mod.replace("/", "-")
            out_file = os.path.join(scratch, f"{base_stem}-{safe_mod}.md")
            write_checklist_file(out_file, [(mod, items)], args.process_only, title_suffix=f"Module `{mod}`")
            print(f"[DONE] wrote {os.path.relpath(out_file, ROOT)} ({len(items)} files)")
        return

    # Determine default or sharded sections
    max_f = args.max_files if args.max_files > 0 else None
    max_s = args.max_score if args.max_score > 0 else None

    if args.shards:
        sections = partition_by_shards(per_file, args.shards)
    else:
        sections = partition_by_module(per_file, max_files=max_f, max_score=max_s)

    # Handle --split-sections
    if args.split_sections:
        for old in os.listdir(scratch):
            if old.startswith(f"{base_stem}-sec-") and old.endswith(".md"):
                try:
                    os.remove(os.path.join(scratch, old))
                except OSError:
                    pass
        for idx, (name, items) in enumerate(sections, start=1):
            out_file = os.path.join(scratch, f"{base_stem}-sec-{idx}.md")
            write_checklist_file(out_file, [(name, items)], args.process_only, title_suffix=f"Section {idx}: {name}")
            print(f"[DONE] wrote {os.path.relpath(out_file, ROOT)} ({len(items)} files)")
        return

    # Handle --section-index
    if args.section_index is not None:
        idx = args.section_index
        if idx < 1 or idx > len(sections):
            raise ValueError(f"--section-index must be between 1 and {len(sections)}")
        target_name, target_items = sections[idx - 1]
        out_path = args.out or os.path.join(scratch, f"{base_stem}-sec-{idx}.md")
        write_checklist_file(out_path, [(target_name, target_items)], args.process_only, title_suffix=f"Section {idx}: {target_name}")
        print(f"[DONE] wrote {os.path.relpath(out_path, ROOT)} ({len(target_items)} files)")
        return

    # Handle --shards N + --shard-index I
    if args.shards and args.shard_index is not None:
        n = args.shards
        idx = args.shard_index
        if idx < 1 or idx > n:
            raise ValueError(f"--shard-index must be between 1 and {n}")
        shards = partition_by_shards(per_file, n)
        target_name, target_items = shards[idx - 1]
        out_path = args.out or os.path.join(scratch, f"{base_stem}-shard-{idx}-of-{n}.md")
        write_checklist_file(out_path, [(target_name, target_items)], args.process_only, title_suffix=f"Shard {idx} of {n}")
        print(f"[DONE] wrote {os.path.relpath(out_path, ROOT)} ({len(target_items)} files)")
        return

    out_name = f"{base_stem}.md"
    out_path = args.out or os.path.join(scratch, out_name)
    write_checklist_file(out_path, sections, args.process_only)

    total_process = sum(1 for _, fs, _ in per_file for _, c, _ in fs if c.startswith("process:"))
    total_block = sum(1 for _, fs, _ in per_file for _, c, _ in fs if c.startswith("long-block"))
    total_line = sum(1 for _, fs, _ in per_file for _, c, _ in fs if c.startswith("long-line"))
    total_inline = sum(1 for _, fs, _ in per_file for _, c, _ in fs if c.startswith("inline-long"))

    print(f"[DONE] files flagged: {len(per_file)}")
    print(f"[DONE] sections: {len(sections)}")
    print(f"[DONE] process refs: {total_process}")
    print(f"[DONE] long blocks: {total_block}")
    print(f"[DONE] long lines: {total_line}")
    print(f"[DONE] long inline: {total_inline}")
    print(f"[DONE] checklist: {os.path.relpath(out_path, ROOT)}")


if __name__ == "__main__":
    main()
