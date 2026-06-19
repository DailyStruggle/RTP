#!/usr/bin/env python3
"""Unit tests for scripts/locale_common.py (shared locale-pipeline helpers).

Covers the OS-agnostic primitives that every pipeline script depends on:
TSV cell encode/decode, comment-cell escaping, RFC-4180 CSV read/write/split,
mojibake scanning, and the relpath / langmap helpers.

Run:
    python -m unittest discover -s scripts/tests -p "test_*.py"
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS_DIR))

import locale_common as lc  # noqa: E402


class TsvCellCodecTest(unittest.TestCase):
    def test_plain_text_roundtrips(self):
        for s in ["hello", "with spaces", "[placeholder]", "&aColor", "\u00a7820", ""]:
            self.assertEqual(lc._tsv_decode_cell(lc._tsv_encode_cell(s)), s)

    def test_newline_and_tab_are_escaped_to_literals(self):
        enc = lc._tsv_encode_cell("line1\nline2\tcol")
        self.assertNotIn("\n", enc)
        self.assertNotIn("\t", enc)
        self.assertIn("\\n", enc)
        self.assertIn("\\t", enc)

    def test_newline_and_tab_roundtrip(self):
        s = "a\nb\tc\nd"
        self.assertEqual(lc._tsv_decode_cell(lc._tsv_encode_cell(s)), s)

    def test_crlf_normalized_to_lf_on_roundtrip(self):
        # Encode collapses CRLF -> LF; decode restores LF (not CRLF).
        self.assertEqual(lc._tsv_decode_cell(lc._tsv_encode_cell("a\r\nb")), "a\nb")

    def test_backslash_encode_is_idempotent(self):
        # Regression guard: a single backslash must encode to a 2-backslash run
        # and decode back to exactly one, so that to-csv/from-csv round-trips are
        # byte-idempotent. The former pipeline emitted FOUR backslashes here while
        # decode collapsed only 2-runs, doubling every backslash on each pass and
        # corrupting locale comments/values containing literal backslashes.
        self.assertEqual(lc._tsv_encode_cell("a\\b"), "a\\\\b")
        self.assertEqual(lc._tsv_decode_cell("a\\\\b"), "a\\b")
        # Multiple full round trips must not grow the backslash count.
        s = "@options=[\\a\\b\\c]"
        once = lc._tsv_decode_cell(lc._tsv_encode_cell(s))
        twice = lc._tsv_decode_cell(lc._tsv_encode_cell(once))
        self.assertEqual(once, s)
        self.assertEqual(twice, s)

    def test_comment_cell_backslash_is_idempotent(self):
        s = "# enum: \\a, \\b"
        self.assertEqual(lc.from_comment_cell(lc.to_comment_cell(s)), s)
        once = lc.from_comment_cell(lc.to_comment_cell(s))
        twice = lc.from_comment_cell(lc.to_comment_cell(once))
        self.assertEqual(twice, s)

    def test_none_encodes_to_empty(self):
        self.assertEqual(lc._tsv_encode_cell(None), "")
        self.assertEqual(lc._tsv_decode_cell(None), "")


class TsvFileTest(unittest.TestCase):
    def test_write_then_read_roundtrips_rows(self):
        rows = [
            {c: "" for c in lc.TSV_COLS},
            {**{c: "" for c in lc.TSV_COLS},
             "relpath": "messages.yml", "key": "hello", "value": "Hi\nthere",
             "preceding_comment": "# greeting", "base_key": "hello"},
        ]
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "x.tsv"
            lc.write_tsv(rows, p)
            back = lc.read_tsv(p)
            self.assertEqual(len(back), len(rows))
            self.assertEqual(back[1]["key"], "hello")
            self.assertEqual(back[1]["value"], "Hi\nthere")
            self.assertEqual(back[1]["preceding_comment"], "# greeting")

    def test_header_is_written_and_skipped_on_read(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "x.tsv"
            lc.write_tsv([], p)
            text = lc.read_text_no_bom(p)
            self.assertTrue(text.startswith("\t".join(lc.TSV_COLS)))
            self.assertEqual(lc.read_tsv(p), [])

    def test_no_bom_written(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "x.tsv"
            lc.write_tsv([], p)
            self.assertNotEqual(p.read_bytes()[:1], b"\xef")


class CommentCellTest(unittest.TestCase):
    def test_multiline_comment_roundtrips_single_line(self):
        c = "# line one\n# line two"
        enc = lc.to_comment_cell(c)
        self.assertNotIn("\n", enc)
        self.assertEqual(lc.from_comment_cell(enc), c)

    def test_empty_and_none(self):
        self.assertEqual(lc.to_comment_cell(None), "")
        self.assertEqual(lc.from_comment_cell(None), "")


class CsvTest(unittest.TestCase):
    def test_field_quotes_only_when_needed(self):
        self.assertEqual(lc._csv_field("plain"), "plain")
        self.assertEqual(lc._csv_field("has,comma"), '"has,comma"')
        self.assertEqual(lc._csv_field('has"quote'), '"has""quote"')
        self.assertEqual(lc._csv_field("has\nnewline"), '"has\nnewline"')

    def test_split_csv_line_handles_quotes_and_escapes(self):
        self.assertEqual(lc.split_csv_line('a,b,c'), ["a", "b", "c"])
        self.assertEqual(lc.split_csv_line('"a,b",c'), ["a,b", "c"])
        self.assertEqual(lc.split_csv_line('"he said ""hi""",x'), ['he said "hi"', "x"])
        self.assertEqual(lc.split_csv_line('a,,c'), ["a", "", "c"])

    def test_write_then_read_csv_roundtrips(self):
        import tempfile
        cols = ["a", "b"]
        rows = [{"a": "1", "b": "x,y"}, {"a": '"q"', "b": ""}]
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "c.csv"
            lc.write_csv(rows, cols, p)
            res = lc.read_csv(p)
            self.assertEqual(res["Header"], cols)
            self.assertEqual(res["Rows"][0]["b"], "x,y")
            self.assertEqual(res["Rows"][1]["a"], '"q"')


class MojibakeTest(unittest.TestCase):
    def test_clean_text_has_no_hits(self):
        self.assertEqual(lc.find_mojibake("Clean ASCII and \u00a78 section sign"), [])

    def test_replacement_char_detected(self):
        self.assertIn("\ufffd", lc.find_mojibake("broken \ufffd glyph"))

    def test_double_encoded_dash_detected(self):
        self.assertTrue(lc.find_mojibake("\u00e2\u20ac\u201d"))

    def test_empty_text(self):
        self.assertEqual(lc.find_mojibake(""), [])


class RelpathTest(unittest.TestCase):
    def test_get_locale_relpath_for_top_level_file(self):
        self.assertEqual(lc.get_locale_relpath("messages.yml", "de"), "lang/de/messages.yml")

    def test_get_locale_relpath_for_shape_under_lang(self):
        self.assertEqual(lc.get_locale_relpath("lang/shape/circle.yml", "de"),
                         "lang/de/shape/circle.yml")

    def test_get_baseline_relpath_inverse_value_file(self):
        self.assertEqual(lc.get_baseline_relpath("lang/de/messages.yml", "de"), "messages.yml")

    def test_get_baseline_relpath_inverse_shape(self):
        self.assertEqual(lc.get_baseline_relpath("lang/de/shape/circle.yml", "de"),
                         "lang/shape/circle.yml")

    def test_get_baseline_relpath_lang_map_file(self):
        self.assertEqual(lc.get_baseline_relpath("lang/de/messages.lang.yml", "de"),
                         "lang/messages.lang.yml")

    def test_get_baseline_relpath_wrong_locale_returns_none(self):
        self.assertIsNone(lc.get_baseline_relpath("lang/fr/messages.yml", "de"))


class BuildLangmapsTest(unittest.TestCase):
    def _row(self, **kw):
        base = {c: "" for c in lc.TSV_COLS}
        base.update(kw)
        return base

    def test_translated_key_recorded(self):
        rows = [self._row(relpath="lang/de/messages.yml", key="hallo", base_key="hello")]
        maps = lc.build_langmaps(rows, "de")
        self.assertEqual(maps.get("messages.yml"), {"hello": "hallo"})

    def test_identity_key_skipped(self):
        rows = [self._row(relpath="lang/de/messages.yml", key="hello", base_key="hello")]
        self.assertEqual(lc.build_langmaps(rows, "de"), {})

    def test_lang_yml_rows_ignored(self):
        rows = [self._row(relpath="lang/de/messages.lang.yml", key="hallo", base_key="hello")]
        self.assertEqual(lc.build_langmaps(rows, "de"), {})

    def test_shape_relpath_grouped_under_lang_prefix(self):
        rows = [self._row(relpath="lang/de/shape/circle.yml", key="kreis", base_key="circle")]
        maps = lc.build_langmaps(rows, "de")
        self.assertEqual(maps.get("lang/shape/circle.yml"), {"circle": "kreis"})


if __name__ == "__main__":
    unittest.main()
