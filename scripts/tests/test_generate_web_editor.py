"""Unit test suite for scripts/generate_web_editor.py (ADR-104)."""

import json
import pathlib
import tempfile
import unittest

from scripts.generate_web_editor import (
    DOCS_DIR,
    EDITOR_DATA,
    RESOURCES_DIR,
    discover_config_files,
    generate_data,
    load_synonyms,
    read_data,
)

EDITOR_HTML = DOCS_DIR / "editor" / "index.html"


def _template() -> str:
    return EDITOR_HTML.read_bytes().decode("utf-8")


class GenerateWebEditorTest(unittest.TestCase):

    def test_discover_config_files_is_deterministic_and_excludes_lang(self):
        files = discover_config_files(RESOURCES_DIR)
        self.assertTrue(len(files) >= 15, f"Expected at least 15 configs, got {len(files)}")
        self.assertEqual(files, sorted(files), "Discovered files must be deterministically sorted")

        # Must include definitions
        self.assertIn("definitions/regions/default.yml", files)
        self.assertIn("definitions/worlds/default.yml", files)
        self.assertIn("definitions/effects/default.yml", files)

        # Must include core configs
        self.assertIn("config.yml", files)
        self.assertIn("economy.yml", files)
        self.assertIn("safety.yml", files)
        self.assertIn("language.yml", files)

        # Must exclude dotfiles, lang catalogs, and plugin.yml
        for f in files:
            self.assertFalse(f.startswith("."), f"Stray dotfile: {f}")
            self.assertFalse(f.startswith("lang/"), f"Stray lang catalog: {f}")
            self.assertNotEqual(f, "plugin.yml", f"Stray plugin manifest: {f}")

    def test_generated_data_carries_shipped_configs_doc_tracker_and_synonyms(self):
        data = json.loads(generate_data())
        self.assertEqual({"shipped", "docTracker", "synonyms"}, set(data))
        for rel in discover_config_files(RESOURCES_DIR):
            self.assertIn(rel, data["shipped"], f"editor-data.json must ship default config: {rel}")
        self.assertTrue(data["docTracker"], "Field docs must be generated")
        syn = data["synonyms"]
        self.assertIn("radius", syn["rad"])
        self.assertIn("price", syn["dinero"])
        self.assertIn("price", syn["geld"])
        self.assertTrue(all(k == k.lower() for k in syn), "Concepts are lower-case lookup keys")

    def test_committed_data_file_is_up_to_date(self):
        self.assertEqual(generate_data(), read_data(EDITOR_DATA),
                         "Run scripts/generate_web_editor.py to refresh docs/editor/editor-data.json")

    def test_generation_is_deterministic(self):
        self.assertEqual(generate_data(), generate_data())

    def test_load_synonyms_lowercases_and_rejects_malformed_entries(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "syn.json"
            path.write_text(json.dumps({"Geld": ["price"]}), encoding="utf-8")
            self.assertEqual({"geld": ["price"]}, load_synonyms(path))
            path.write_text(json.dumps({"geld": "price"}), encoding="utf-8")
            with self.assertRaises(ValueError):
                load_synonyms(path)
            path.write_text(json.dumps(["price"]), encoding="utf-8")
            with self.assertRaises(ValueError):
                load_synonyms(path)
            self.assertEqual({}, load_synonyms(pathlib.Path(tmp) / "missing.json"))

    def test_template_bundles_no_plugin_data(self):
        # ADR-104: configs, docs, field docs, shape names, walk paths, synonyms and metrics come from the plugin
        html = _template()
        self.assertIn("const defaultConfigs = {};", html)
        self.assertIn("const CONFIG_SYNONYMS = {};", html)
        self.assertIn("payload.synonyms", html)
        self.assertNotIn("FALLBACK_REGISTRY", html)
        self.assertNotIn('<option value="CIRCLE"', html)
        self.assertNotIn("dinero:", html)
        self.assertNotIn("ENGINE HEALTHY", html)
        self.assertNotIn('<b style="color:var(--accent);">nether_wastes</b>', html)
        self.assertEqual(1, html.count("</html>"), "Nothing may follow the document end")
        self.assertTrue(html.rstrip().endswith("</html>"))

    def test_template_decodes_hilbert_bins_and_requests_walk_paths(self):
        html = _template()
        self.assertIn("function derivePointEdgeChunks(radiusChunks)", html)
        self.assertIn("function hilbertToXY(d, n)", html)
        self.assertIn("function requestWalkPath(region)", html)

    def test_template_contains_zoom_and_shape_controls(self):
        html = _template()
        self.assertIn('id="reg-shape"', html)
        self.assertIn("function populateShapeSelect(", html)
        self.assertIn("canvas.addEventListener('wheel'", html)
        self.assertIn("canvasPanX", html)
        self.assertIn("canvasZoom", html)
        self.assertIn("resetCanvasView", html)
        self.assertIn("layer-p-label", html)
        self.assertIn("ctx.fill('evenodd')", html)
        self.assertIn("syncPolygonAABB", html)

    def test_template_contains_polygon_vertices_controls(self):
        html = _template()
        self.assertIn('id="grp-polygon-points"', html)
        self.assertIn('id="polygon-vertices-list"', html)
        self.assertIn('id="poly-points-count"', html)
        self.assertIn("function renderPolygonVerticesList()", html)
        self.assertIn("function updateVertexCoord(", html)
        self.assertIn("function addPolygonVertex()", html)
        self.assertIn("function removePolygonVertex(", html)

    def test_template_contains_docs_navigation(self):
        html = _template()
        self.assertIn("function populateDocSidebar(", html)
        self.assertIn("function selectDoc(", html)
        self.assertIn("function categorizeDoc(", html)
        self.assertIn("function getDocDisplay(", html)
        self.assertIn("function filterDocs(", html)
        self.assertIn('id="doc-filter-input"', html)

    def test_template_inherited_reference_detection_excludes_schema_annotations(self):
        html = _template()
        self.assertIn("function isInheritedReferenceToken(token)", html)
        self.assertIn("SCHEMA_DOC_TAGS", html)
        self.assertIn("'type'", html)
        self.assertIn("'range'", html)
        self.assertIn("'unit'", html)
        self.assertIn("'default'", html)
        self.assertIn("line.startsWith('#')", html)
        self.assertIn("isInheritedReferenceToken(candidate)", html)

    def test_template_contains_region_editor_add_and_remove_controls(self):
        html = _template()
        self.assertIn("<label>Selected Region:</label>", html)
        self.assertNotIn("Selected Profile Preset:", html)
        self.assertIn('id="reg-profile-select"', html)
        self.assertIn('value="__add_region__"', html)
        self.assertIn('id="btn-remove-region"', html)
        self.assertIn("function handleRegionSelectChange(", html)
        self.assertIn("function promptAddNewRegion()", html)
        self.assertIn("function removeCurrentRegion()", html)
        self.assertIn("function syncRegionProfiles()", html)
        self.assertIn("function getConfigDisplay(name)", html)
        self.assertIn("The default region configuration is protected and cannot be removed.", html)


if __name__ == "__main__":
    unittest.main()
