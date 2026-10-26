#!/usr/bin/env python3
"""
Unit tests for `discord-support-bot/corpus_atlas.py` covering:
  - Stage 1: Two-column layout de-interleaving (`deinterleave_two_column_page`) and sentence trimming
  - Stage 2: Config Tree Lowest-Common-Ancestor (LCA) key disambiguation (`resolve_config_leaf_via_lca`)
  - Stage 3: Multi-facet evidence compilation, deterministic multi-subsection compilation
    (zero 1-sentence placeholders), and fail-closed LLM + deterministic merging
  - Stage 4: Book-by-book archive summary compilation (`compile_book_summaries`)
"""
from __future__ import annotations

import pathlib
import sqlite3
import sys
import tempfile
import unittest

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
ATLAS_DIR = REPO_ROOT / "corpus-atlas"
BOT_DIR = REPO_ROOT / "discord-support-bot"
if str(ATLAS_DIR) not in sys.path:
    sys.path.insert(0, str(ATLAS_DIR))

from corpus_atlas import (  # noqa: E402
    AdaptiveRateLimitBackoff,
    PipelineStatusTracker,
    TopicEvidencePacket,
    bake_project_ast,
    build_deterministic_article_from_packet,
    classify_rate_limit_error,
    classify_source_register,
    clean_sentence_excerpt,
    compile_book_summaries,
    decode_inverted_cmap_text,
    deinterleave_two_column_page,
    discover_sources_of_record,
    ensure_inline_passage_quotes_in_paragraphs,
    explain_query,
    extract_embedded_heading_and_body,
    extract_inline_quote_clause,
    merge_llm_and_deterministic_article,
    repair_and_parse_json,
    repair_ocr_and_layout_text,
    resolve_config_leaf_via_lca,
)


class TestCorpusAtlasEngine(unittest.TestCase):
    def test_deinterleave_two_column_page_restores_reading_order(self) -> None:
        lines = [
            "CHAPTER ONE: THE PATH OF VISION",
            "",
            "Left column line one about Visionary hunters.        Right column line one about the Messengers.",
            "Left column line two about Foresee edges.            Right column line two about Second Sight.",
            "Left column line three about Mercy and Zeal.         Right column line three about Conviction.",
            "Left column line four about Hunter-net posts.        Right column line four about Bystanders.",
            "Left column line five concluding the left side.      Right column line five concluding the right.",
        ]
        raw = "\n".join(lines)
        cleaned = deinterleave_two_column_page(raw)
        self.assertIn("Left column line one about Visionary hunters.\nLeft column line two", cleaned)
        self.assertIn("Right column line one about the Messengers.\nRight column line two", cleaned)
        self.assertLess(
            cleaned.index("Left column line five"),
            cleaned.index("Right column line one"),
        )

    def test_resolve_config_leaf_via_lca_disambiguates_vert_name(self) -> None:
        conn = sqlite3.connect(":memory:")
        conn.execute(
            "CREATE TABLE config_keys (file_path TEXT, key_path TEXT, default_value TEXT, project TEXT)"
        )
        conn.executemany(
            "INSERT INTO config_keys VALUES (?, ?, ?, ?)",
            [
                ("regions/default.yml", "shape.name", "CIRCLE", "leafrtp"),
                ("regions/default.yml", "shape.radius", "5000", "leafrtp"),
                ("regions/default.yml", "shape.centerRadius", "1000", "leafrtp"),
                ("regions/default.yml", "vert.name", "LINEAR", "leafrtp"),
                ("regions/default.yml", "vert.minY", "32", "leafrtp"),
                ("regions/default.yml", "vert.maxY", "120", "leafrtp"),
                ("regions/default.yml", "vert.requireSkyLight", "false", "leafrtp"),
            ],
        )
        resolved = resolve_config_leaf_via_lca(
            conn,
            "name",
            ["name", "minY", "maxY", "requireSkyLight"],
            project="leafrtp",
        )
        self.assertEqual(resolved, "vert.name")
        conn.close()

    def test_deterministic_and_merged_article_never_emits_one_sentence_placeholders(self) -> None:
        packet = TopicEvidencePacket(
            gameline="werewolf",
            category="Tribe",
            name="Black Furies",
            query="Black Furies Pegasus Wyld",
            sources_of_record=[
                {
                    "abbr": "W20-CRB",
                    "title": "Werewolf: The Apocalypse 20th Anniversary Edition",
                    "pages": "pp. 76–77",
                    "hits": 42,
                    "headings": ["Black Furies", "Tribal Camps", "Totem: Pegasus"],
                    "voice_note": "Primary out-of-character Storyteller & rulebook reference",
                },
                {
                    "abbr": "WTA-TBBF",
                    "title": "Tribebook: Black Furies",
                    "pages": "pp. 12–68",
                    "hits": 95,
                    "headings": ["Legend of the Furies", "The Moon-Daughters"],
                    "voice_note": "Mixed designer & in-character narrator voice",
                },
            ],
            resolved_symbols=[
                {"id": "werewolf:black_furies", "name": "Black Furies", "mentions": 137, "aliases": ["Furies", "Daughters of Pegasus"]}
            ],
            top_neighbors=[
                {"dst_name": "Pegasus", "support_pages": 28, "sample_citations": ["W20-CRB, p. 76"]},
                {"dst_name": "Children of Gaia", "support_pages": 19, "sample_citations": ["W20-CRB, p. 80"]},
                {"dst_name": "Get of Fenris", "support_pages": 22, "sample_citations": ["W20-CRB, p. 84"]},
            ],
            cross_domain_bridges=[
                {"gameline": "mage", "name": "Verbena", "mentions": 45, "weight": 0.31},
            ],
            facet_passages={
                "identity_and_doctrine": [
                    {
                        "cite": "W20-CRB, p. 76",
                        "title": "W20 Core",
                        "heading": "Black Furies",
                        "snippet": "The Black Furies are fierce defenders of the Wyld, women, and sacred places across the world.",
                    }
                ],
                "organization_and_subfactions": [
                    {
                        "cite": "WTA-TBBF, p. 38",
                        "title": "Tribebook: Black Furies",
                        "heading": "Camps of the Furies",
                        "snippet": "Internal camps include the Amazons of Diana, the Moon-Daughters, the Freebooters, and the Order of Our Merciful Mother.",
                    }
                ],
                "chronology_and_history": [
                    {
                        "cite": "WTA-TBBF, p. 14",
                        "title": "Tribebook: Black Furies",
                        "heading": "Ancient Greece",
                        "snippet": "From ancient Hellas and the Aegean islands, the Furies guarded the oldest Caerns of the Wyld against patriarchal empires.",
                    }
                ],
                "perspectives_and_rivals": [
                    {
                        "cite": "W20-CRB, p. 77",
                        "title": "W20 Core",
                        "heading": "Stereotypes",
                        "snippet": "They clash frequently with the Get of Fenris over pride while respecting the Children of Gaia.",
                    }
                ],
            },
            quick_facts={"Tribal Totem": "Pegasus", "Starting Willpower": "3 [W20-CRB, p. 76]"},
        )
        det = build_deterministic_article_from_packet(
            packet,
            edition_meta={"game_title": "Werewolf: The Apocalypse", "edition_name": "W20", "badge": "[W20]"},
        )
        self.assertGreaterEqual(len(det["lead_summary"]), 2)
        self.assertGreaterEqual(len(det["culture_and_doctrine"]), 2)
        self.assertGreaterEqual(len(det["history_eras"]), 3)
        self.assertGreaterEqual(len(det["subfactions_table"]), 2)
        self.assertGreaterEqual(len(det["voice_caution"]), 2)
        self.assertGreaterEqual(len(det["edition_coverage"]), 2)
        self.assertGreaterEqual(len(det["open_questions"]), 2)

        # Simulate a broken/empty LLM response and confirm merge preserves full deterministic depth
        merged = merge_llm_and_deterministic_article({"lead_summary": "Too short."}, det)
        self.assertEqual(merged["lead_summary"], det["lead_summary"])
        self.assertGreaterEqual(len(merged["history_eras"]), 3)

    def test_compile_book_summaries_extracts_chapters_and_excerpts(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            db_p = pathlib.Path(td) / "test.sqlite"
            conn = sqlite3.connect(str(db_p))
            conn.executescript(
                """
                CREATE TABLE source_register (
                    abbr TEXT PRIMARY KEY, title TEXT, gameline TEXT, rel_path TEXT, md_rel_path TEXT,
                    credits TEXT, copyright_line TEXT, pdf_pages INTEGER, offset_delta INTEGER,
                    offset_label TEXT, has_text INTEGER, word_count INTEGER, status TEXT
                );
                CREATE VIRTUAL TABLE doc_sections USING fts5(title, heading, path, url UNINDEXED, body, tags, project UNINDEXED);
                """
            )
            conn.execute(
                "INSERT INTO source_register VALUES ('HTR-VIS', 'Visionary', 'hunter', 'Hunter/Visionary.pdf', 'hunter/visionary.md', 'White Wolf', '2001', 96, 1, 'PDF = printed + 1', 1, 48000, 'Indexed')"
            )
            conn.execute(
                "INSERT INTO doc_sections VALUES ('Visionary', 'Chapter One: The Path of Vision', 'hunter/visionary.md', '/hunter/', 'Visionaries seek to understand why the Messengers chose humanity and where the Reckoning leads.', 'abbr:HTR-VIS printed_p:18', 'hunter')"
            )
            summaries = compile_book_summaries(conn)
            self.assertEqual(len(summaries), 1)
            self.assertEqual(summaries[0]["abbr"], "HTR-VIS")
            self.assertIn("Chapter One: The Path of Vision", summaries[0]["headings"])
            self.assertEqual(len(summaries[0]["excerpts"]), 1)
            conn.close()

    def test_repair_and_parse_json_salvages_truncated_llm_payload(self) -> None:
        truncated = (
            '```json\n'
            '{\n'
            '  "lead_summary": ["First full paragraph with [HTR-CORE, p. 18].", "Second full paragraph with [HTR-VIS, p. 45]."],\n'
            '  "culture_and_doctrine": ["Detailed doctrine paragraph one.", "Detailed doctrine paragraph two."],\n'
            '  "playing_and_portraying": ["Incomplete trailing string'
        )
        salvaged = repair_and_parse_json(truncated)
        self.assertEqual(len(salvaged.get("lead_summary", [])), 2)
        self.assertEqual(len(salvaged.get("culture_and_doctrine", [])), 2)

    def test_leafrtp_in_repo_index_integration(self) -> None:
        leafrtp_db = BOT_DIR / "generated" / "doc-index.sqlite"
        if not leafrtp_db.exists():
            self.skipTest("LeafRTP doc-index.sqlite not present")
        conn = sqlite3.connect(str(leafrtp_db))
        conn.row_factory = sqlite3.Row
        try:
            resolved = resolve_config_leaf_via_lca(
                conn,
                "name",
                ["name", "minY", "maxY", "requireSkyLight"],
                project="leafrtp",
            )
            self.assertEqual(resolved, "vert.name")
            sources = discover_sources_of_record(conn, "openOnBareRtp", "leafrtp", extra_query="guimenu chest")
            self.assertGreaterEqual(len(sources), 1)
        finally:
            conn.close()

    def test_adaptive_rate_limit_backoff_coordinates_quota_and_pacing(self) -> None:
        fake_now = [1000.0]
        backoff = AdaptiveRateLimitBackoff(
            min_request_interval_sec=0.5,
            api_base_delay_sec=2.0,
            max_api_delay_sec=30.0,
            quota_base_delay_sec=60.0,
            max_quota_delay_sec=600.0,
            time_fn=lambda: fake_now[0],
        )
        self.assertEqual(classify_rate_limit_error("HTTP Error 509: Bandwidth Limit Exceeded"), "QUOTA_WINDOW")
        self.assertEqual(classify_rate_limit_error("MEGA API error code -17"), "QUOTA_WINDOW")
        self.assertEqual(classify_rate_limit_error("MEGA API error code -3 (EAGAIN)"), "API_THROTTLE")
        self.assertEqual(classify_rate_limit_error("HTTP Error 429: Too Many Requests"), "API_THROTTLE")
        self.assertEqual(classify_rate_limit_error("urlopen error timed out"), "TRANSIENT_NET")

        # Token-bucket request pacing across consecutive calls
        w1 = backoff.reserve_request_slot()
        w2 = backoff.reserve_request_slot()
        self.assertAlmostEqual(w1, 0.0, places=3)
        self.assertAlmostEqual(w2, 0.5, places=3)

        # Quota window failure triggers 60s global cooldown gate across all workers
        kind, delay = backoff.record_failure("HTTP Error 509: Bandwidth Limit Exceeded")
        self.assertEqual(kind, "QUOTA_WINDOW")
        self.assertAlmostEqual(delay, 60.0, places=2)
        snap = backoff.snapshot()
        self.assertTrue(snap["in_backoff"])
        self.assertEqual(snap["kind"], "QUOTA_WINDOW")
        self.assertAlmostEqual(backoff.reserve_request_slot(), 60.0, places=2)

        # Second consecutive quota error escalates cooldown
        kind2, delay2 = backoff.record_failure("MEGA API error code -17")
        self.assertEqual(kind2, "QUOTA_WINDOW")
        self.assertGreater(delay2, 60.0)

        # Advancing past cooldown and recording success clears streak
        fake_now[0] += delay2 + 5.0
        backoff.record_success()
        self.assertFalse(backoff.snapshot()["in_backoff"])

    def test_pipeline_status_tracker_persists_and_renders_bars(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            state_path = pathlib.Path(td) / "status.json"
            tracker = PipelineStatusTracker(state_path, title="Test Corpus Pipeline")
            tracker.register_bar("dl", "1. MEGA Download", total=908, current=412, unit="files", status="ACTIVE")
            tracker.register_bar("ocr", "2. GPU RapidOCR", total=350, current=124, unit="books", status="ACTIVE")
            tracker.update_bar("dl", current=420, rate_str="12.4 MiB/s", detail="Book of Lost Dreams.pdf", phase_title="Downloading & OCR")
            tracker.set_backoff_state(
                {
                    "in_backoff": True,
                    "remaining_sec": 45.0,
                    "kind": "QUOTA_WINDOW",
                    "reason": "HTTP 509 Bandwidth Limit",
                    "total_backoff_events": 1,
                }
            )
            self.assertTrue(state_path.exists())
            rendered = PipelineStatusTracker.render_text_bars(tracker.to_dict())
            self.assertIn("Test Corpus Pipeline [RUNNING]", rendered)
            self.assertIn("BACKOFF ACTIVE (QUOTA_WINDOW)", rendered)
            self.assertIn("1. MEGA Download", rendered)
            self.assertIn("420/ 908 files", rendered)
            tracker.mark_completed("All 908 books indexed and packaged.")
            self.assertEqual(tracker.to_dict()["overall_state"], "COMPLETED")
            self.assertEqual(tracker.to_dict()["overall_percent"], 100.0)

    def test_inline_quote_extraction_and_voice_conscious_synthesis(self) -> None:
        raw_psg = (
            "However, the Storytellers Handbook warns that \"the game would be greatly lessened were we "
            "to provide a definitive answer\" regarding the Messengers' true origin."
        )
        clause = extract_inline_quote_clause(raw_psg, focus_term="Messengers")
        self.assertEqual(clause, "the game would be greatly lessened were we to provide a definitive answer")

        reg_ic = classify_source_register("Clanbook: Tzimisce", "In-character faction narration", "We Fiends rule the Carpathians.")
        self.assertEqual(reg_ic["register"], "in-character")
        reg_des = classify_source_register("Storytellers Handbook Revised", "Out-of-character Storyteller rules", "Storytellers should adjudicate Chronicle hooks.")
        self.assertEqual(reg_des["register"], "designer")
        self.assertEqual(reg_des["edition_tag"], "Revised")

        enriched = ensure_inline_passage_quotes_in_paragraphs(
            ["Visionaries debate the origin of the Messengers across every cell."],
            [{"cite": "HTR-STH, p. 100", "title": "Storytellers Handbook Revised", "snippet": raw_psg, "inline_quote": clause}],
            [{"title": "Storytellers Handbook Revised", "voice_note": "Out-of-character Storyteller rules"}],
            focus_term="Messengers",
        )
        self.assertEqual(len(enriched), 1)
        self.assertIn('"the game would be greatly lessened were we to provide a definitive answer" [HTR-STH, p. 100]', enriched[0])

    def test_zero_rewiring_bake_and_explain_arbitrary_new_project(self) -> None:
        conn = sqlite3.connect(":memory:")
        try:
            conn.executescript(
                """
                CREATE VIRTUAL TABLE doc_sections USING fts5(title, heading, path, url UNINDEXED, body, tags, project UNINDEXED);
                CREATE VIRTUAL TABLE config_keys USING fts5(file_path, key_path, default_value, directives, description, project UNINDEXED);
                """
            )
            conn.executemany(
                "INSERT INTO config_keys VALUES (?, ?, ?, ?, ?, ?)",
                [
                    ("cache.yml", "eviction.policy", "LRU", "@options: [LRU, LFU]", "Controls cache eviction policy when `/cache flush` is not used.", "acmecache"),
                    ("cache.yml", "eviction.syncDiskFlush", "false", "@type: boolean", "Force synchronous disk flush on every write.", "acmecache"),
                ],
            )
            conn.executemany(
                "INSERT INTO doc_sections VALUES (?, ?, ?, ?, ?, ?, ?)",
                [
                    (
                        "FAQ",
                        "Why does /cache flush open an interactive confirmation prompt?",
                        "docs/faq.md",
                        "https://example.org/faq",
                        "When `/cache flush` runs, `eviction.policy` governs the retention behavior. Furthermore, Valdris, the Architect of Glass, founded Nexus.",
                        "faq",
                        "acmecache",
                    ),
                    (
                        "What Not To Do",
                        "Do not enable synchronous disk flushing",
                        "docs/what-not-to-do.md",
                        "https://example.org/what-not-to-do",
                        "Never set `eviction.syncDiskFlush` to true in production because it stalls the event loop and violates S-005. Keep `eviction.syncDiskFlush` false.",
                        "antipattern",
                        "acmecache",
                    ),
                ],
            )
            stats = bake_project_ast(conn, project="acmecache")
            self.assertGreater(stats["ast_symbols"], 0)
            self.assertGreater(stats["ast_edges"], 0)
            self.assertGreater(stats["ast_conflicts"], 0)

            exp_faq = explain_query(conn, "interactive confirmation prompt on /cache flush", project="acmecache")
            self.assertTrue(
                any("eviction.policy" in e["dst"] or "eviction.policy" in s["id"] for e in exp_faq["ast_subgraph_edges"] for s in exp_faq["resolved_ast_symbols"]),
                str(exp_faq),
            )

            exp_conflict = explain_query(conn, "Should I enable eviction.syncDiskFlush?", project="acmecache")
            self.assertGreaterEqual(len(exp_conflict["flagged_conflicts"]), 1, str(exp_conflict))
            self.assertEqual(exp_conflict["corpus_regime"], "TECHNICAL_SCHEMA")
            self.assertEqual(exp_conflict["intentional_meta_lore_perspectives"], [])
        finally:
            conn.close()

    def test_technical_vs_prose_regime_quality_and_inverted_cmap_recovery(self) -> None:
        # 1. Verify deterministic recovery of pdfFactory Pro inverted-ASCII CMap pages (`288 - ord(c)`)
        corrupted_line = "Ì¸»²ô × ²±¬·½»¼ ·¬ò Ì¸» ©±®´¼ ·-²K¬ °®»¬¬§ò NÌ¸» -µ§ÿM Ý±¬¬±² -¿·¼ò"
        decoded = decode_inverted_cmap_text(corrupted_line)
        self.assertEqual(decoded, 'Then, I noticed it. The world isn\'t pretty. "The sky!" Cotton said.')

        # 2. Verify LeafRTP in-repo AST has zero lore leakage (`TECHNICAL_SCHEMA`) and high precision
        leafrtp_db = BOT_DIR / "generated" / "doc-index.sqlite"
        if leafrtp_db.exists():
            conn_l = sqlite3.connect(str(leafrtp_db))
            try:
                # Zero internal ADR headings promoted to user-facing BEHAVIORAL_SYMPTOM nodes
                adr_symptoms = conn_l.execute(
                    "SELECT count(*) FROM ast_symbols WHERE symbol_type = 'BEHAVIORAL_SYMPTOM' AND defining_table = 'arch_sections'"
                ).fetchone()[0]
                self.assertEqual(adr_symptoms, 0)

                # Zero prose CONCEPT_ENTITY lore symbols in LeafRTP
                lore_syms = conn_l.execute(
                    "SELECT count(*) FROM ast_symbols WHERE domain = 'leafrtp' AND symbol_type = 'CONCEPT_ENTITY'"
                ).fetchone()[0]
                self.assertEqual(lore_syms, 0)

                # Bare /rtp GUI query resolves `openOnBareRtp` with zero false-positive safety conflicts
                exp_gui = explain_query(
                    conn_l,
                    "Every time players type /rtp it opens a chest inventory GUI instead of teleporting them",
                    project="leafrtp",
                )
                self.assertEqual(exp_gui["corpus_regime"], "TECHNICAL_SCHEMA")
                gui_sym_ids = [s["id"] for s in exp_gui["resolved_ast_symbols"]]
                self.assertIn("cfg:addons/guimenu.yml:openOnBareRtp", gui_sym_ids)
                self.assertEqual(exp_gui["flagged_conflicts"], [])

                # Lag / syncLoading / rtp.unqueued query resolves both symbols and surfaces safety conflicts
                exp_lag = explain_query(
                    conn_l,
                    "My server has lag spikes during RTP, should I set syncLoading: true or give players rtp.unqueued?",
                    project="leafrtp",
                )
                lag_sym_ids = [s["id"] for s in exp_lag["resolved_ast_symbols"]]
                self.assertIn("cfg:advanced/performance.yml:syncLoading", lag_sym_ids)
                self.assertIn("perm:rtp.unqueued", lag_sym_ids)
                conflict_fids = {c["focal_symbol_id"] for c in exp_lag["flagged_conflicts"]}
                self.assertIn("cfg:advanced/performance.yml:syncLoading", conflict_fids)
                self.assertIn("perm:rtp.unqueued", conflict_fids)
            finally:
                conn_l.close()

    def test_adr_115_hypergraph_symbols_and_edges_from_traceability_and_java_meta(self) -> None:
        conn = sqlite3.connect(":memory:")
        try:
            conn.executescript(
                """
                CREATE VIRTUAL TABLE doc_sections USING fts5(title, heading, path, url UNINDEXED, body, tags, project UNINDEXED);
                CREATE VIRTUAL TABLE arch_sections USING fts5(title, heading, path, url UNINDEXED, body, tags, project UNINDEXED);
                CREATE VIRTUAL TABLE dev_sections USING fts5(title, heading, path, url UNINDEXED, body, tags, project UNINDEXED);
                CREATE VIRTUAL TABLE code_sections USING fts5(class_name, package_name, path, methods, diagnostics, config_refs, summary, project UNINDEXED);
                CREATE TABLE code_class_meta (
                    class_name TEXT NOT NULL,
                    package_name TEXT NOT NULL,
                    path TEXT PRIMARY KEY,
                    module_name TEXT NOT NULL,
                    dag_tier TEXT NOT NULL,
                    super_class TEXT NOT NULL,
                    interfaces_json TEXT NOT NULL,
                    imports_json TEXT NOT NULL,
                    governed_by_json TEXT NOT NULL,
                    gradle_test_cmd TEXT NOT NULL,
                    kind TEXT NOT NULL DEFAULT 'CLASS',
                    project TEXT NOT NULL DEFAULT 'leafrtp'
                );
                CREATE TABLE test_class_meta (
                    test_class_name TEXT NOT NULL,
                    package_name TEXT NOT NULL,
                    path TEXT PRIMARY KEY,
                    module_name TEXT NOT NULL,
                    gradle_test_cmd TEXT NOT NULL,
                    tested_classes_json TEXT NOT NULL,
                    req_ids_json TEXT NOT NULL,
                    project TEXT NOT NULL DEFAULT 'leafrtp'
                );
                CREATE TABLE traceability_rows (
                    req_id TEXT NOT NULL,
                    summary TEXT NOT NULL,
                    design_refs_json TEXT NOT NULL,
                    implementing_classes_json TEXT NOT NULL,
                    test_classes_json TEXT NOT NULL,
                    project TEXT NOT NULL DEFAULT 'leafrtp',
                    PRIMARY KEY (req_id, project)
                );
                """
            )
            conn.executemany(
                "INSERT INTO code_sections VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                [
                    ("EwmaEstimator", "io.github.dailystruggle.predict.api", "api/predict-api/src/main/java/EwmaEstimator.java", "observe value", "", "", "Exponential moving average estimator SPI", "leafrtp"),
                    ("BaseGovernor", "io.github.dailystruggle.predict.api", "api/predict-api/src/main/java/BaseGovernor.java", "evaluate", "", "", "Abstract base governor", "leafrtp"),
                    ("ComparativeCostGovernor", "io.github.dailystruggle.predict.api", "api/predict-api/src/main/java/ComparativeCostGovernor.java", "decide", "", "", "Adaptive cost governor Gov: ADR-115", "leafrtp"),
                ],
            )
            conn.executemany(
                "INSERT INTO code_class_meta VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                [
                    ("EwmaEstimator", "io.github.dailystruggle.predict.api", "api/predict-api/src/main/java/EwmaEstimator.java", ":predict-api", "TIER_1_SPI_PURE", "", "[]", "[]", '["ADR-115"]', "./gradlew :predict-api:test", "INTERFACE", "leafrtp"),
                    ("BaseGovernor", "io.github.dailystruggle.predict.api", "api/predict-api/src/main/java/BaseGovernor.java", ":predict-api", "TIER_1_SPI_PURE", "", "[]", "[]", '["ADR-115"]', "./gradlew :predict-api:test", "ABSTRACT_CLASS", "leafrtp"),
                    ("ComparativeCostGovernor", "io.github.dailystruggle.predict.api", "api/predict-api/src/main/java/ComparativeCostGovernor.java", ":predict-api", "TIER_1_SPI_PURE", "BaseGovernor", '["EwmaEstimator"]', '["EwmaEstimator"]', '["ADR-115", "REQ-RTP-S-005", "S-005"]', './gradlew :predict-api:test --tests "ComparativeCostGovernorTest"', "CLASS", "leafrtp"),
                ],
            )
            conn.execute(
                "INSERT INTO test_class_meta VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ("ComparativeCostGovernorTest", "io.github.dailystruggle.predict.api", "api/predict-api/src/test/java/ComparativeCostGovernorTest.java", ":predict-api", './gradlew :predict-api:test --tests "ComparativeCostGovernorTest"', '["ComparativeCostGovernor"]', '["REQ-RTP-S-005", "ADR-115"]', "leafrtp"),
            )
            conn.execute(
                "INSERT INTO traceability_rows VALUES (?, ?, ?, ?, ?, ?)",
                ("REQ-RTP-S-005", "No sync chunk loads", '["ADR-115", "S-005"]', '["ComparativeCostGovernor"]', '["ComparativeCostGovernorTest"]', "leafrtp"),
            )

            stats = bake_project_ast(conn, project="leafrtp")
            self.assertGreater(stats["ast_symbols"], 4)

            sym_types = {
                r[0]: r[1]
                for r in conn.execute("SELECT symbol_id, symbol_type FROM ast_symbols").fetchall()
            }
            self.assertEqual("REQ_ID", sym_types.get("req:REQ-RTP-S-005"))
            self.assertEqual("TEST_CLASS", sym_types.get("test:predict-api:ComparativeCostGovernorTest"))
            self.assertEqual("SPI_CONTRACT", sym_types.get("spi:EwmaEstimator"))

            edge_types = {
                (r[0], r[1], r[2])
                for r in conn.execute("SELECT src_symbol_id, dst_symbol_id, edge_kind FROM ast_edges").fetchall()
            }
            self.assertIn(("cls:ComparativeCostGovernor", "cls:BaseGovernor", "EXTENDS_CLASS"), edge_types)
            self.assertIn(("cls:ComparativeCostGovernor", "spi:EwmaEstimator", "IMPLEMENTS_SPI"), edge_types)
            self.assertIn(("cls:ComparativeCostGovernor", "cls:EwmaEstimator", "IMPORTS_CLASS"), edge_types)
            self.assertIn(("cls:ComparativeCostGovernor", "req:REQ-RTP-S-005", "SATISFIES_REQ"), edge_types)
            self.assertIn(("cls:ComparativeCostGovernor", "adr:ADR-115", "GOVERNED_BY_ADR"), edge_types)
            self.assertIn(("cls:ComparativeCostGovernor", "test:predict-api:ComparativeCostGovernorTest", "VERIFIED_BY_TEST"), edge_types)
        finally:
            conn.close()

    def test_ocr_spell_repair_and_embedded_heading_intent_recovery(self) -> None:
        raw_mta_ab_p28 = (
            "Religion and the Akashic Brotherho\n"
            "The Akashic Brotherhood doesn’t require a specific religious\n"
            "practice from its members, although some faiths require radical\n"
            "reinterpretation in order to jibe with Akashic belief. One myth\n"
            "outsiders have about the Akashayana is that all members are\n"
            "Buddhists, or followers of Eastern religions in general."
        )
        heading, body = extract_embedded_heading_and_body(
            raw_mta_ab_p28,
            raw_heading="Page 28 (PDF p. 29)",
            topic_name="Akashic Brotherhood",
        )
        self.assertEqual(heading, "Religion and the Akashic Brotherhood")
        self.assertTrue(
            body.startswith(
                "The Akashic Brotherhood doesn’t require a specific religious practice from its members"
            )
        )
        self.assertNotIn("Brotherho ", body)

        raw_mta_ab_p87 = (
            "Meru’s Children: Templates\n"
            "Warriors who master the killing arts. Honor-\n"
            "able ascetics who blind themselves to the flesh."
        )
        h87, b87 = extract_embedded_heading_and_body(
            raw_mta_ab_p87,
            raw_heading="Page 87 (PDF p. 88)",
            topic_name="Akashic Brotherhood",
        )
        self.assertEqual(h87, "Meru’s Children: Templates")
        self.assertIn("Honorable ascetics", b87)
        self.assertNotIn("Honor- able", b87)
        self.assertEqual(repair_ocr_and_layout_text("the Celestial ChoM and Mage the Acension"), "the Celestial Chorus and Mage: The Ascension")

    def test_idempotent_html_escape_and_inline_markdown_entity_linker(self) -> None:
        from corpus_atlas import esc_html_idempotent, render_inline_markdown_and_links

        # 1. Pre-escaped `&amp;` is never double-escaped to `&amp;amp;`
        self.assertEqual("Player &amp; Storyteller Guide", esc_html_idempotent("Player &amp; Storyteller Guide"))
        self.assertEqual("Player &amp; Storyteller Guide", esc_html_idempotent("Player &amp;amp; Storyteller Guide"))
        self.assertEqual("Player &amp; Storyteller Guide", esc_html_idempotent("Player & Storyteller Guide"))

        # 2. Backticked or <code>-wrapped entities (`Hunter-net`) unwrap and link to their symbol URL
        sym_map = {
            "arcanum": "/hunter/creeds-and-agencies/arcanum/",
            "hunter-net": "/hunter/creeds-and-agencies/hunter-net/",
            "society of leopold": "/hunter/creeds-and-agencies/inquisition/",
        }
        raw_prose = (
            "**Narrator Bias &amp; Canon:** Society of Leopold Inquisitors, Arcanum scholars, "
            "and `Hunter-net` posters fiercely debate what the *Imbued* really are (`CC-008`) [HTR-CORE, p. 24]."
        )
        rendered = render_inline_markdown_and_links(raw_prose, sym_map, current_url="/sources/how-to-read/")
        self.assertNotIn("&amp;amp;", rendered)
        self.assertIn("<strong>Narrator Bias &amp; Canon:</strong>", rendered)
        self.assertIn('<em>Imbued</em>', rendered)
        self.assertIn('<a class="wikilink" href="/hunter/creeds-and-agencies/arcanum/">Arcanum</a>', rendered)
        self.assertIn('<a class="wikilink" href="/hunter/creeds-and-agencies/hunter-net/">Hunter-net</a>', rendered)
        self.assertNotIn("<code>Hunter-net</code>", rendered)
        self.assertIn("<code>CC-008</code>", rendered)
        self.assertIn('<span class="cite">[HTR-CORE, p. 24]</span>', rendered)


if __name__ == "__main__":
    unittest.main()
