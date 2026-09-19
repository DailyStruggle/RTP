#!/usr/bin/env python3
"""Unit + end-to-end tests for the locale config TSV pipeline scripts.

Covers the pure helpers of each hyphenated pipeline script:
  - locale-files-to-csv.py   : strip_quotes, convert_yaml_file_to_rows,
                               get_reverse_lang_map, set_base_keys
  - locale-files-from-csv.py : test_in_scope, test_is_bare_scalar,
                               format_scalar, resolve_baseline_relpath
  - locale-changeset-to-csv.py: is_translatable_value_row, matches_keys_selection

Plus an end-to-end self-consistency test that runs the real scripts as
subprocesses over a synthetic resources tree and asserts the
to-csv -> from-csv -> to-csv round trip is stable (idempotent) and that
reconcile + changeset-to-csv + changeset-from-csv operate without error.
"""

from __future__ import annotations

import importlib.util
import subprocess
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


to_csv = _load("locale_files_to_csv", "locale-files-to-csv.py")
from_csv = _load("locale_files_from_csv", "locale-files-from-csv.py")
cs_to = _load("locale_changeset_to_csv", "locale-changeset-to-csv.py")


class StripQuotesTest(unittest.TestCase):
    def test_double_quotes_removed(self):
        self.assertEqual(to_csv.strip_quotes('"hello"'), "hello")

    def test_single_quotes_removed(self):
        self.assertEqual(to_csv.strip_quotes("'hello'"), "hello")

    def test_unquoted_trimmed(self):
        self.assertEqual(to_csv.strip_quotes("  hello  "), "hello")

    def test_mismatched_quotes_kept(self):
        self.assertEqual(to_csv.strip_quotes("'hello\""), "'hello\"")


class ConvertYamlFileTest(unittest.TestCase):
    def _rows(self, text):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "messages.yml"
            p.write_text(text, encoding="utf-8")
            return to_csv.convert_yaml_file_to_rows(p, "messages.yml")

    def test_simple_key_value(self):
        rows = self._rows('hello: "Hi"\n')
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["key"], "hello")
        self.assertEqual(rows[0]["value"], "Hi")

    def test_preceding_comment_attached(self):
        rows = self._rows("# greeting\nhello: Hi\n")
        self.assertEqual(rows[0]["preceding_comment"], "# greeting")

    def test_nested_map_parent_sentinel(self):
        rows = self._rows("parent:\n  child: value\n")
        self.assertEqual(rows[0]["value"], "__MAP_OR_LIST_PARENT__")
        self.assertEqual(rows[1]["parent_path"], "parent")
        self.assertEqual(rows[1]["key"], "child")

    def test_list_items_get_indices(self):
        rows = self._rows("items:\n  - first\n  - second\n")
        items = [r for r in rows if r["index"] != ""]
        self.assertEqual([r["index"] for r in items], [0, 1])
        self.assertEqual([r["value"] for r in items], ["first", "second"])

    def test_blank_before_flag(self):
        rows = self._rows("a: 1\n\nb: 2\n")
        b = [r for r in rows if r["key"] == "b"][0]
        self.assertEqual(b["blank_before"], 1)


class ReverseLangMapTest(unittest.TestCase):
    def test_reverse_map_built(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "messages.lang.yml"
            p.write_text("hello: hallo\nbye: tschuess\n", encoding="utf-8")
            rev = to_csv.get_reverse_lang_map(p)
            self.assertEqual(rev, {"hallo": "hello", "tschuess": "bye"})

    def test_missing_file_returns_empty(self):
        self.assertEqual(to_csv.get_reverse_lang_map(Path("does-not-exist.lang.yml")), {})


class SetBaseKeysTest(unittest.TestCase):
    def _row(self, **kw):
        from locale_common import TSV_COLS
        base = {c: "" for c in TSV_COLS}
        base.update(kw)
        return base

    def test_base_key_from_reverse_map(self):
        rows = [self._row(relpath="lang/de/messages.yml", key="hallo")]
        to_csv.set_base_keys(rows, {"lang/de/messages.yml": {"hallo": "hello"}})
        self.assertEqual(rows[0]["base_key"], "hello")

    def test_base_key_identity_when_no_map(self):
        rows = [self._row(relpath="messages.yml", key="hello")]
        to_csv.set_base_keys(rows, {})
        self.assertEqual(rows[0]["base_key"], "hello")


class FromCsvHelpersTest(unittest.TestCase):
    def test_in_scope_no_filter(self):
        self.assertTrue(from_csv.test_in_scope("messages.yml", None))

    def test_in_scope_leaf_match(self):
        self.assertTrue(from_csv.test_in_scope("lang/de/messages.yml", ["messages.yml"]))

    def test_in_scope_no_match(self):
        self.assertFalse(from_csv.test_in_scope("lang/de/config.yml", ["messages.yml"]))

    def test_is_bare_scalar(self):
        for v in ["5", "-3", "2.5", "true", "false", "null", "~"]:
            self.assertTrue(from_csv.test_is_bare_scalar(v), v)
        for v in ["hello", "", "5 apples", "1.2.3"]:
            self.assertFalse(from_csv.test_is_bare_scalar(v), v)

    def test_format_scalar_quotes_strings(self):
        self.assertEqual(from_csv.format_scalar("hello"), '"hello"')
        self.assertEqual(from_csv.format_scalar(""), '""')
        self.assertEqual(from_csv.format_scalar("5"), "5")
        self.assertEqual(from_csv.format_scalar('a"b'), '"a\\"b"')

    def test_format_scalar_rejects_parent_sentinel(self):
        with self.assertRaises(ValueError):
            from_csv.format_scalar("__MAP_OR_LIST_PARENT__")

    def test_resolve_baseline_relpath(self):
        self.assertEqual(from_csv.resolve_baseline_relpath("lang/de/messages.yml"), "messages.yml")
        self.assertEqual(from_csv.resolve_baseline_relpath("lang/de/shape/c.yml"), "lang/shape/c.yml")
        self.assertEqual(from_csv.resolve_baseline_relpath("messages.yml"), "messages.yml")


class ChangesetToCsvHelpersTest(unittest.TestCase):
    def _row(self, **kw):
        from locale_common import TSV_COLS
        base = {c: "" for c in TSV_COLS}
        base.update(kw)
        return base

    def test_translatable_value_row(self):
        self.assertTrue(cs_to.is_translatable_value_row(
            self._row(relpath="messages.yml", key="hello", value="Hi")))

    def test_parent_sentinel_not_translatable(self):
        self.assertFalse(cs_to.is_translatable_value_row(
            self._row(relpath="messages.yml", key="p", value="__MAP_OR_LIST_PARENT__")))

    def test_lang_map_file_not_translatable(self):
        self.assertFalse(cs_to.is_translatable_value_row(
            self._row(relpath="lang/messages.lang.yml", key="hello", value="hello")))

    def test_matches_keys_selection_variants(self):
        r = self._row(relpath="messages.yml", base_key="hello")
        self.assertTrue(cs_to.matches_keys_selection(r, ["hello"]))
        self.assertTrue(cs_to.matches_keys_selection(r, ["messages.yml"]))
        self.assertTrue(cs_to.matches_keys_selection(r, ["messages.yml:hello"]))
        self.assertFalse(cs_to.matches_keys_selection(r, ["other"]))


class EndToEndPipelineTest(unittest.TestCase):
    """Run the actual scripts as subprocesses over a synthetic resources tree."""

    BASELINE = (
        "# Greeting line\n"
        "hello: \"Hello there\"\n"
        "\n"
        "nested:\n"
        "  child: \"Child value\"\n"
        # A value carrying a literal backslash (mirrors zh @options enum lists)
        # so the to->from->to idempotency check exercises the backslash codec.
        "backslashy: \"a\\\\b\\\\c\"\n"
        "items:\n"
        "  - \"first\"\n"
        "  - \"second\"\n"
    )
    LOCALE_DE = (
        "# Greeting line\n"
        "hello: \"Hallo da\"\n"
        "\n"
        "nested:\n"
        "  child: \"Kindwert\"\n"
        "backslashy: \"a\\\\b\\\\c\"\n"
        "items:\n"
        "  - \"erste\"\n"
        "  - \"zweite\"\n"
    )

    def _make_tree(self, root: Path):
        (root / "lang" / "de").mkdir(parents=True)
        (root / "messages.yml").write_text(self.BASELINE, encoding="utf-8")
        (root / "lang" / "de" / "messages.yml").write_text(self.LOCALE_DE, encoding="utf-8")

    def _run(self, script: str, *args: str):
        cmd = [sys.executable, str(SCRIPTS_DIR / script), *args]
        proc = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(proc.returncode, 0,
                         f"{script} failed:\nSTDOUT:\n{proc.stdout}\nSTDERR:\n{proc.stderr}")
        return proc

    def test_to_from_to_roundtrip_is_idempotent(self):
        with tempfile.TemporaryDirectory() as d:
            base = Path(d)
            res1 = base / "res1"
            out1 = base / "out1"
            regen = base / "regen"
            out2 = base / "out2"
            self._make_tree(res1)

            self._run("locale-files-to-csv.py",
                      "--resources-root", str(res1), "--output-dir", str(out1))
            self.assertTrue((out1 / "baseline.tsv").exists())
            self.assertTrue((out1 / "locale-de.tsv").exists())

            self._run("locale-files-from-csv.py",
                      "--resources-root", str(regen), "--input-dir", str(out1))
            self.assertTrue((regen / "messages.yml").exists())
            self.assertTrue((regen / "lang" / "de" / "messages.yml").exists())

            self._run("locale-files-to-csv.py",
                      "--resources-root", str(regen), "--output-dir", str(out2))

            self.assertEqual((out1 / "baseline.tsv").read_bytes(),
                             (out2 / "baseline.tsv").read_bytes())
            self.assertEqual((out1 / "locale-de.tsv").read_bytes(),
                             (out2 / "locale-de.tsv").read_bytes())

    def test_regenerated_values_preserved(self):
        with tempfile.TemporaryDirectory() as d:
            base = Path(d)
            res1 = base / "res1"
            out1 = base / "out1"
            regen = base / "regen"
            self._make_tree(res1)
            self._run("locale-files-to-csv.py",
                      "--resources-root", str(res1), "--output-dir", str(out1))
            self._run("locale-files-from-csv.py",
                      "--resources-root", str(regen), "--input-dir", str(out1))
            de = (regen / "lang" / "de" / "messages.yml").read_text(encoding="utf-8")
            self.assertIn("Hallo da", de)
            self.assertIn("Kindwert", de)
            self.assertIn("erste", de)

    def test_reconcile_and_changeset_run_clean(self):
        with tempfile.TemporaryDirectory() as d:
            base = Path(d)
            res1 = base / "res1"
            out1 = base / "out1"
            self._make_tree(res1)
            self._run("locale-files-to-csv.py",
                      "--resources-root", str(res1), "--output-dir", str(out1))
            # reconcile + changeset scripts discover locales from the real repo
            # resources tree, so they only need a populated out-dir to operate.
            self._run("reconcile-locale-csvs.py", "--out-dir", str(out1))
            self._run("locale-changeset-to-csv.py",
                      "--out-dir", str(out1), "--all", "--out-file", str(out1 / "changeset.csv"))
            self.assertTrue((out1 / "changeset.csv").exists())
            self._run("locale-changeset-from-csv.py",
                      "--out-dir", str(out1), "--in-file", str(out1 / "changeset.csv"))


if __name__ == "__main__":
    unittest.main()
