#!/usr/bin/env python3
"""Unit tests for scripts/scan_command_parameters.py.

Verifies the Java-source argument extractor that powers the CommandParameter
audit: balanced-paren / string-aware argument splitting, whitespace
normalization, line numbering, and end-to-end scan of both supported patterns
(direct `new <X>Parameter(...)` and `super(...)` inside a `*Parameter` class).
"""

from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS_DIR))


def _load(name: str, filename: str):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS_DIR / filename)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


scp = _load("scan_command_parameters", "scan_command_parameters.py")


class ExtractArgsTest(unittest.TestCase):
    def _extract(self, text):
        idx = text.index("(")
        return scp.extract_args(text, idx)

    def test_simple_args(self):
        args, end = self._extract('("a", "b", c)')
        self.assertEqual(args, ['"a"', '"b"', "c"])

    def test_nested_parens_not_split(self):
        args, _ = self._extract('(foo(x, y), "b")')
        self.assertEqual(args, ["foo(x, y)", '"b"'])

    def test_comma_inside_string_literal_not_split(self):
        args, _ = self._extract('("a, b", c)')
        self.assertEqual(args, ['"a, b"', "c"])

    def test_escaped_quote_in_string(self):
        args, _ = self._extract(r'("a\"b", c)')
        self.assertEqual(args, [r'"a\"b"', "c"])

    def test_line_comment_inside_args_ignored(self):
        text = '(\n  "a", // a comment with , comma\n  "b"\n)'
        args, _ = self._extract(text)
        self.assertEqual(args, ['"a"', '"b"'])

    def test_block_comment_inside_args_ignored(self):
        args, _ = self._extract('("a" /* , not a sep */ , "b")')
        self.assertEqual(args, ['"a"', '"b"'])

    def test_unbalanced_returns_none(self):
        self.assertIsNone(scp.extract_args('("a", "b"', 0))

    def test_end_index_points_after_close_paren(self):
        text = '("a")tail'
        _, end = self._extract(text)
        self.assertEqual(text[end:], "tail")


class NormalizeArgTest(unittest.TestCase):
    def test_collapses_whitespace_and_newlines(self):
        self.assertEqual(scp.normalize_arg('  "a"\n   + "b"  '), '"a" + "b"')


class LineOfTest(unittest.TestCase):
    def test_line_numbers_are_one_based(self):
        text = "l1\nl2\nl3"
        self.assertEqual(scp.line_of(text, 0), 1)
        self.assertEqual(scp.line_of(text, text.index("l2")), 2)
        self.assertEqual(scp.line_of(text, text.index("l3")), 3)


class ScanFileTest(unittest.TestCase):
    def _scan(self, java_src):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "Foo.java"
            p.write_text(java_src, encoding="utf-8")
            return scp.scan_file(str(p))

    def test_new_parameter_pattern(self):
        src = (
            "class X {\n"
            "  void m() {\n"
            '    new BoolParameter("rtp.use", "Toggle thing", true);\n'
            "  }\n"
            "}\n"
        )
        rows = self._scan(src)
        self.assertEqual(len(rows), 1)
        _, line, kind, cls, perm, desc = rows[0]
        self.assertEqual(kind, "new")
        self.assertEqual(cls, "BoolParameter")
        self.assertEqual(perm, '"rtp.use"')
        self.assertEqual(desc, '"Toggle thing"')
        self.assertEqual(line, 3)

    def test_super_pattern_inside_parameter_class(self):
        src = (
            "public class WorldParameter extends CommandParameter {\n"
            "  public WorldParameter() {\n"
            '    super("rtp.worlds", "Pick a world", 5);\n'
            "  }\n"
            "}\n"
        )
        rows = self._scan(src)
        kinds = {r[2] for r in rows}
        self.assertIn("super", kinds)
        super_row = [r for r in rows if r[2] == "super"][0]
        self.assertEqual(super_row[3], "WorldParameter")
        self.assertEqual(super_row[4], '"rtp.worlds"')
        self.assertEqual(super_row[5], '"Pick a world"')

    def test_no_parameter_no_rows(self):
        self.assertEqual(self._scan("class Plain { int x = foo(1, 2); }\n"), [])


if __name__ == "__main__":
    unittest.main()
