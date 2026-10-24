"""Unit tests for scripts/discord/build_doc_index.py (Stage 1)."""

from __future__ import annotations

import pathlib
import tempfile
import unittest

from scripts.discord.build_doc_index import (
    ROOT,
    build_index,
    extract_bit_codes,
    lookup_bit_code,
    main,
    search_config_keys,
    search_docs,
)


class DiscordDocIndexTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls) -> None:
        cls._tmpdir = tempfile.TemporaryDirectory()
        cls.db_path = pathlib.Path(cls._tmpdir.name) / "doc-index.sqlite"
        cls.conn = build_index(ROOT, cls.db_path)

    @classmethod
    def tearDownClass(cls) -> None:
        cls.conn.close()
        cls._tmpdir.cleanup()

    def test_tables_populated(self) -> None:
        doc_count = self.conn.execute("SELECT count(*) FROM doc_sections").fetchone()[0]
        cfg_count = self.conn.execute("SELECT count(*) FROM config_keys").fetchone()[0]
        bit_count = self.conn.execute("SELECT count(*) FROM bit_codes").fetchone()[0]
        self.assertGreater(doc_count, 20)
        self.assertGreater(cfg_count, 50)
        self.assertGreater(bit_count, 100)

    def test_strictly_excludes_adrs_and_internal_dev_docs(self) -> None:
        adr_rows = self.conn.execute(
            "SELECT path FROM doc_sections WHERE path LIKE 'docs/adr/%' OR path LIKE 'docs/architecture/%'"
        ).fetchall()
        self.assertEqual([], adr_rows)

        dev_rows = self.conn.execute(
            "SELECT DISTINCT path FROM doc_sections WHERE path LIKE 'docs/dev/%'"
        ).fetchall()
        dev_paths = {r["path"] for r in dev_rows}
        self.assertTrue(
            dev_paths.issubset({"docs/dev/BIT_CODES.md", "docs/dev/SUPPORT_MATRIX.md"}),
            f"Unexpected internal dev docs indexed: {dev_paths}",
        )

    def test_canonical_site_urls_generated(self) -> None:
        rows = self.conn.execute(
            "SELECT url FROM doc_sections WHERE path = 'docs/admin/QUICK_START.md'"
        ).fetchall()
        self.assertTrue(rows)
        for row in rows:
            self.assertTrue(
                row["url"].startswith("https://dailystruggle.github.io/RTP/admin/QUICK_START/"),
                row["url"],
            )

    def test_yaml_key_lookup_radius_and_open_on_bare_rtp(self) -> None:
        radius_hits = search_config_keys(self.conn, "shape.radius", limit=5)
        self.assertTrue(radius_hits)
        self.assertEqual("definitions/regions/default.yml", radius_hits[0]["file_path"])
        self.assertEqual("shape.radius", radius_hits[0]["key_path"])

        gui_hits = search_config_keys(self.conn, "openOnBareRtp", limit=5)
        self.assertTrue(gui_hits)
        self.assertEqual("addons/guimenu.yml", gui_hits[0]["file_path"])
        self.assertEqual("openOnBareRtp", gui_hits[0]["key_path"])
        self.assertEqual("true", gui_hits[0]["default_value"])

    def test_yaml_directives_extracted(self) -> None:
        delay_hits = search_config_keys(self.conn, "teleportDelay", limit=3)
        self.assertTrue(delay_hits)
        top = delay_hits[0]
        self.assertEqual("config.yml", top["file_path"])
        self.assertIn("@type: integer", top["directives"])
        self.assertIn("@range: [0, null]", top["directives"])

    def test_natural_language_radius_query_returns_expected_top_3(self) -> None:
        for query in ("how do I set radius", "spawn distance"):
            cfg_hits = search_config_keys(self.conn, query, limit=3)
            cfg_keys = {(c["file_path"], c["key_path"]) for c in cfg_hits}
            self.assertTrue(
                ("definitions/regions/default.yml", "shape.radius") in cfg_keys
                or ("definitions/regions/default.yml", "shape.centerRadius") in cfg_keys,
                f"Expected default.yml radius keys in top 3 for {query!r}, got {cfg_keys}",
            )

            doc_hits = search_docs(self.conn, query, limit=3)
            doc_paths = [d["path"] for d in doc_hits]
            self.assertIn("docs/admin/QUICK_START.md", doc_paths)
            combined_bodies = "\n".join(d["body"] for d in doc_hits)
            self.assertTrue(
                "/rtp editor" in combined_bodies or "/rtp admin setup" in combined_bodies
            )

    def test_natural_language_disable_gui_query(self) -> None:
        query = "disable GUI menu on /rtp"
        cfg_hits = search_config_keys(self.conn, query, limit=3)
        self.assertEqual("addons/guimenu.yml", cfg_hits[0]["file_path"])
        self.assertEqual("openOnBareRtp", cfg_hits[0]["key_path"])

        doc_hits = search_docs(self.conn, query, limit=3)
        doc_paths = [d["path"] for d in doc_hits]
        self.assertTrue(
            "docs/admin/ADDONS.md" in doc_paths or "docs/admin/QUICK_START.md" in doc_paths,
            f"Expected ADDONS.md or QUICK_START.md in {doc_paths}",
        )

    def test_bit_code_deterministic_lookup_and_extraction(self) -> None:
        unknown_world = lookup_bit_code(self.conn, "RTP_REGION_UNKNOWN_WORLD")
        self.assertIsNotNone(unknown_world)
        assert unknown_world is not None
        self.assertEqual("RTP", unknown_world["namespace"])
        self.assertEqual(1301, unknown_world["id"])
        self.assertEqual("ERROR", unknown_world["severity"])
        self.assertIn("loaded world", str(unknown_world["remediation"]))

        by_id = lookup_bit_code(self.conn, "ACTION-2002")
        self.assertIsNotNone(by_id)
        assert by_id is not None
        self.assertEqual("ACTION_UNKNOWN_STEP_KEY", by_id["key"])

        log_paste = (
            "[12:00:01 WARN]: [RTP bit] RTP-1301 RTP_REGION_UNKNOWN_WORLD in default.yml\n"
            "[12:00:02 ERROR]: [RTP bit] ACTION_UNKNOWN_STEP_KEY in arena.yml"
        )
        extracted = extract_bit_codes(self.conn, log_paste)
        keys = [e["key"] for e in extracted]
        self.assertEqual(["RTP_REGION_UNKNOWN_WORLD", "ACTION_UNKNOWN_STEP_KEY"], keys)

    def test_cli_main_builds_sqlite_file(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out_db = pathlib.Path(tmp) / "cli-test.sqlite"
            rc = main(["--db", str(out_db)])
            self.assertEqual(0, rc)
            self.assertTrue(out_db.is_file())


if __name__ == "__main__":
    unittest.main()
