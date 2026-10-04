"""Unit test suite for scripts/generate_web_editor.py (ADR-104)."""

import pathlib
import unittest

from scripts.generate_web_editor import (
    DOCS_DIR,
    RESOURCES_DIR,
    discover_config_files,
    discover_doc_files,
    generate_html,
)


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

    def test_discover_doc_files_discovers_all_shipped_manuals(self):
        doc_files = discover_doc_files(DOCS_DIR)
        self.assertTrue(len(doc_files) >= 35, f"Expected at least 35 shipped documentation manuals, got {len(doc_files)}")

        # Must include core operator manuals and guides
        self.assertIn("admin/QUICK_START.md", doc_files)
        self.assertIn("admin/COMMANDS.md", doc_files)
        if (DOCS_DIR / "admin" / "HAZARDS.md").is_file():
            self.assertIn("admin/HAZARDS.md", doc_files)
        self.assertIn("admin/RUNBOOK.md", doc_files)
        self.assertIn("admin/WEB_EDITOR_GUIDE.md", doc_files)

        # Must include configuration reference suite
        self.assertIn("admin/configuration/REGIONS.md", doc_files)
        self.assertIn("admin/configuration/CORE_CONFIG.md", doc_files)
        self.assertIn("admin/configuration/SAFETY.md", doc_files)
        self.assertIn("admin/configuration/PERFORMANCE.md", doc_files)
        self.assertIn("admin/configuration/ECONOMY.md", doc_files)
        self.assertIn("admin/configuration/CONFIGURATION.md", doc_files)

        # Must include multi-server proxies
        self.assertIn("admin/proxies/CONFIGURATION.md", doc_files)
        self.assertIn("admin/proxies/INDEX.md", doc_files)

        # Must include root operator landing pages
        self.assertIn("FOR_SERVER_ADMINS.md", doc_files)
        self.assertIn("MAP.md", doc_files)

        # Must exclude internal engineering trees (dev/, adr/, architecture/, site/)
        for rel in doc_files:
            self.assertFalse(rel.startswith("dev/"), f"Internal dev doc leaked: {rel}")
            self.assertFalse(rel.startswith("adr/"), f"Internal adr doc leaked: {rel}")
            self.assertFalse(rel.startswith("architecture/"), f"Internal architecture doc leaked: {rel}")
            self.assertFalse(rel.startswith("site/"), f"Site build artifact leaked: {rel}")
            self.assertFalse(rel.endswith(".bak"), f"Backup file leaked: {rel}")

    def test_generated_html_contains_unambiguous_definitions_display(self):
        html = generate_html()

        # Must contain getConfigDisplay function
        self.assertIn("function getConfigDisplay(name)", html)

        # Definitions must format with relative subpaths and distinct pills
        self.assertIn("regions/default.yml", html)
        self.assertIn("worlds/default.yml", html)

        # Verify defaultConfigs json includes all authentic definitions
        self.assertIn('"definitions/regions/default.yml"', html)
        self.assertIn('"definitions/worlds/default.yml"', html)
        self.assertIn('"definitions/effects/default.yml"', html)

    def test_generated_html_contains_dual_layer_hilbert_chaining(self):
        html = generate_html()
        self.assertIn("function derivePointEdgeChunks(radiusChunks)", html)
        self.assertIn("function orientationFor(px, pz)", html)
        self.assertIn("function hilbertToXY(d, n, orientation", html)
        self.assertIn("Walk Path (Hilbert / Spiral)", html)
        self.assertIn("CIRCLE (Dual-Layer Chained Hilbert)", html)
        self.assertIn("SQUARE (Dual-Layer Chained Hilbert)", html)

    def test_generated_html_contains_full_shape_catalog_and_zoom(self):
        html = generate_html()
        # Verify complete plugin shape catalog presence
        self.assertIn('value="RECTANGLE"', html)
        self.assertIn('value="ELLIPSE"', html)
        self.assertIn('value="CIRCLE_NORMAL"', html)
        self.assertIn('value="SQUARE_NORMAL"', html)
        self.assertIn('value="POLYGON"', html)
        self.assertIn('value="CIRCLE_DEPRECATED_PURE_SPIRAL"', html)
        self.assertIn('value="SQUARE_DEPRECATED_PURE_SPIRAL"', html)

        # Verify scroll wheel zooming and panning support
        self.assertIn("canvas.addEventListener('wheel'", html)
        self.assertIn("canvasPanX", html)
        self.assertIn("canvasZoom", html)
        self.assertIn("resetCanvasView", html)

        # Verify dynamic P-Bin scaling and exclusion donut shading
        self.assertIn("layer-p-label", html)
        self.assertIn("ctx.fill('evenodd')", html)
        self.assertIn("syncPolygonAABB", html)

    def test_generated_html_contains_plugin_sourced_walk_paths(self):
        html = generate_html()
        # Verify Rectangle is raster scan (not spiral)
        self.assertIn("locationToXZ computes raster order", html)
        self.assertIn("row - Math.floor(h / 2)", html)

        # Verify legacy spirals and polygon use plugin exact formulas
        self.assertIn("Sourced directly from Circle.java", html)
        self.assertIn("Sourced directly from Square.java and SquareGeometry.java", html)
        self.assertIn("Polygon inherits from Square bounded by the polygon AABB", html)

    def test_generated_html_contains_polygon_vertices_controls(self):
        html = generate_html()
        # Verify polygon vertices container and controls
        self.assertIn('id="grp-polygon-points"', html)
        self.assertIn('id="polygon-vertices-list"', html)
        self.assertIn('id="poly-points-count"', html)
        self.assertIn("function renderPolygonVerticesList()", html)
        self.assertIn("function updateVertexCoord(", html)
        self.assertIn("function addPolygonVertex()", html)
        self.assertIn("function removePolygonVertex(", html)

    def test_generated_html_contains_complete_shipped_docs_catalog(self):
        html = generate_html()
        # Verify defaultDocs json includes full shipped catalog (at least 35 manuals)
        doc_files = discover_doc_files(DOCS_DIR)
        for rel in doc_files:
            self.assertIn(f'"{rel}"', html, f"defaultDocs must embed shipped documentation file: {rel}")

        # Verify documentation navigation and display helpers
        self.assertIn("function populateDocSidebar(", html)
        self.assertIn("function selectDoc(", html)
        self.assertIn("function categorizeDoc(", html)
        self.assertIn("function getDocDisplay(", html)
        self.assertIn("function filterDocs(", html)
        self.assertIn('id="doc-filter-input"', html)

    def test_generated_html_inherited_reference_detection_excludes_schema_annotations(self):
        html = generate_html()
        self.assertIn("function isInheritedReferenceToken(token)", html)
        self.assertIn("SCHEMA_DOC_TAGS", html)
        self.assertIn("'type'", html)
        self.assertIn("'range'", html)
        self.assertIn("'unit'", html)
        self.assertIn("'default'", html)
        self.assertIn("line.startsWith('#')", html)
        self.assertIn("isInheritedReferenceToken(candidate)", html)

    def test_generated_html_contains_region_editor_add_and_remove_controls(self):
        html = generate_html()
        # Verify renamed label
        self.assertIn("<label>Selected Region:</label>", html)
        self.assertNotIn("Selected Profile Preset:", html)

        # Verify add region option and remove button
        self.assertIn('id="reg-profile-select"', html)
        self.assertIn('value="__add_region__"', html)
        self.assertIn('id="btn-remove-region"', html)

        # Verify JS handlers
        self.assertIn("function handleRegionSelectChange(", html)
        self.assertIn("function promptAddNewRegion()", html)
        self.assertIn("function removeCurrentRegion()", html)
        self.assertIn("function syncRegionProfiles()", html)
        self.assertIn("The default region configuration is protected and cannot be removed.", html)


if __name__ == "__main__":
    unittest.main()
