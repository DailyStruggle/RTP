"""Unit tests for scripts/ci-scope.py (CI build-scope classification).

Covers settings.gradle parsing (includes + projectDir overrides), ignorable
paths, leaf vs full classification, reverse-dependency closure among leaves,
core test consumers discovered from path literals, and the fail-to-full rules.
"""

from __future__ import annotations

import importlib.util
import os
import sys
import tempfile
import unittest
from pathlib import Path

script_path = Path(__file__).resolve().parent.parent / "ci-scope.py"
spec = importlib.util.spec_from_file_location("ci_scope", script_path)
ci_scope = importlib.util.module_from_spec(spec)
sys.modules["ci_scope"] = ci_scope
spec.loader.exec_module(ci_scope)

SETTINGS = """
include 'rtp-core'
include 'rtp-bukkit:rtp-bukkit-common'
project(':rtp-bukkit:rtp-bukkit-common').projectDir = file('platforms/rtp-bukkit/rtp-bukkit-common')
include 'addons:LeafRTPActionAddon'
include 'addons:LeafRTPClaimAddon'
include 'addons:LeafRTPGuiAddon:rtp-gui-common'
if (!excludeJdk25) {
    include 'addons:LeafRTPGuiAddon:rtp-gui-fabric'
}
include 'helpers:MockItemPlugins'
include 'rtp-neoforge:rtp-neoforge-common'
project(':rtp-neoforge').projectDir = file('platforms/rtp-neoforge')
project(':rtp-neoforge:rtp-neoforge-common').projectDir = file('platforms/rtp-neoforge/rtp-neoforge-common')
include 'rtp-plugin'
// include 'addons:Commented'
"""

DIRS = ci_scope.project_dirs(SETTINGS)
REFS = {
    "addons:LeafRTPActionAddon": {"rtp-core"},
    "addons:LeafRTPClaimAddon": {"helpers:MockItemPlugins"},
    "addons:LeafRTPGuiAddon:rtp-gui-common": set(),
    "addons:LeafRTPGuiAddon:rtp-gui-fabric": {"addons:LeafRTPGuiAddon:rtp-gui-common"},
    "helpers:MockItemPlugins": set(),
}
CONSUMERS = {"addons:LeafRTPActionAddon": {"rtp-core"}}


def classify(changed):
    return ci_scope.classify(changed, DIRS, REFS, CONSUMERS)


class SettingsParsingTest(unittest.TestCase):
    def test_includes_default_and_overridden_dirs(self):
        self.assertEqual(DIRS["rtp-core"], "rtp-core")
        self.assertEqual(DIRS["rtp-bukkit:rtp-bukkit-common"], "platforms/rtp-bukkit/rtp-bukkit-common")
        self.assertEqual(DIRS["addons:LeafRTPGuiAddon:rtp-gui-common"], "addons/LeafRTPGuiAddon/rtp-gui-common")

    def test_gated_includes_listed_and_comments_skipped(self):
        self.assertIn("addons:LeafRTPGuiAddon:rtp-gui-fabric", DIRS)
        self.assertNotIn("addons:Commented", DIRS)


class IgnoredPathTest(unittest.TestCase):
    def test_docs_and_root_markdown_ignored(self):
        self.assertTrue(ci_scope.is_ignored("docs/dev/INDEX.md"))
        self.assertTrue(ci_scope.is_ignored("CHANGELOG.md"))
        self.assertTrue(ci_scope.is_ignored("LICENSE-MIT"))

    def test_nested_markdown_not_ignored(self):
        # GitHub's `*.md` does not cross `/`; mirrored here.
        self.assertFalse(ci_scope.is_ignored("rtp-core/README.md"))


class ClassifyTest(unittest.TestCase):
    def test_single_addon_builds_only_that_addon(self):
        self.assertEqual(
            classify(["addons/LeafRTPClaimAddon/src/main/java/A.java"]),
            ("leaf", [":addons:LeafRTPClaimAddon:build"]))

    def test_core_test_consumer_added(self):
        self.assertEqual(
            classify(["addons/LeafRTPActionAddon/src/main/resources/definitions/actions/x.yml"]),
            ("leaf", [":addons:LeafRTPActionAddon:build", ":rtp-core:test"]))

    def test_multi_project_addon_selects_all_members(self):
        scope, tasks = classify(["addons/LeafRTPGuiAddon/build.gradle"])
        self.assertEqual(scope, "leaf")
        self.assertEqual(tasks, [":addons:LeafRTPGuiAddon:rtp-gui-common:build",
                                 ":addons:LeafRTPGuiAddon:rtp-gui-fabric:build"])

    def test_helper_change_pulls_in_referencing_addon(self):
        self.assertEqual(
            classify(["helpers/MockItemPlugins/src/main/java/M.java"]),
            ("leaf", [":addons:LeafRTPClaimAddon:build", ":helpers:MockItemPlugins:build"]))

    def test_any_core_path_forces_full(self):
        self.assertEqual(
            classify(["addons/LeafRTPClaimAddon/a.yml", "rtp-core/src/main/java/A.java"]),
            ("full", []))

    def test_build_and_ci_config_force_full(self):
        for path in ("settings.gradle", "build.gradle", "gradle/libs.versions.toml",
                     ".github/workflows/gradle.yml", "scripts/ci-scope.py"):
            self.assertEqual(classify([path]), ("full", []), path)

    def test_unknown_addon_directory_forces_full(self):
        self.assertEqual(classify(["addons/NotInSettings/src/A.java"]), ("full", []))

    def test_loose_leaf_markdown_and_docs_are_none(self):
        self.assertEqual(classify(["addons/REQUIREMENTS.md", "docs/a.md"]), ("none", []))

    def test_loose_non_markdown_leaf_file_forces_full(self):
        self.assertEqual(classify(["addons/shared.gradle"]), ("full", []))

    def test_windows_separators_normalised(self):
        self.assertEqual(
            classify(["addons\\LeafRTPClaimAddon\\src\\A.java"]),
            ("leaf", [":addons:LeafRTPClaimAddon:build"]))


TREE = ci_scope.project_dirs(SETTINGS, parents=True)
ALL_REFS = {
    **REFS,
    "rtp-core": set(),
    "rtp-bukkit": set(),
    "rtp-bukkit:rtp-bukkit-common": {"rtp-core"},
    "rtp-neoforge": {"rtp-core"},
    "rtp-neoforge:rtp-neoforge-common": {"rtp-core"},
    "rtp-plugin": {"rtp-bukkit:rtp-bukkit-common", "rtp-neoforge:rtp-neoforge-common",
                   "addons:LeafRTPClaimAddon"},
}


def flags(changed):
    result = ci_scope.affected_sets(changed, TREE, ALL_REFS, CONSUMERS)
    return {name for name, on in result.items() if on}


class ProjectTreeTest(unittest.TestCase):
    def test_parents_added_with_overridden_dirs(self):
        self.assertEqual(TREE["rtp-neoforge"], "platforms/rtp-neoforge")
        self.assertEqual(TREE["addons:LeafRTPGuiAddon"], "addons/LeafRTPGuiAddon")
        self.assertNotIn("rtp-neoforge", DIRS)

    def test_bare_leaf_containers_not_projects(self):
        self.assertNotIn("addons", TREE)
        self.assertNotIn("helpers", TREE)

    def test_ancestor_refs_inherited(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "p").mkdir()
            (root / "p" / "build.gradle").write_text("api project(':rtp-core')", encoding="utf-8")
            (root / "p" / "c").mkdir()
            (root / "p" / "c" / "build.gradle").write_text(
                "def x = findProject(':addons:A')", encoding="utf-8")
            refs = ci_scope.all_refs(root, {"p": "p", "p:c": "p/c", "rtp-core": "rtp-core"})
        self.assertEqual(refs["p"], {"rtp-core"})
        self.assertEqual(refs["p:c"], {"rtp-core", "addons:A"})
        self.assertEqual(refs["rtp-core"], set())


class AffectedSetsTest(unittest.TestCase):
    def test_core_change_flags_dependents_not_helpers(self):
        self.assertEqual(flags(["rtp-core/src/main/java/A.java"]),
                         {"core", "bukkit", "neoforge", "plugin", "addons"})

    def test_platform_change_flags_platform_and_plugin(self):
        self.assertEqual(flags(["platforms/rtp-neoforge/rtp-neoforge-common/src/A.java"]),
                         {"neoforge", "plugin"})

    def test_parent_build_file_owned_by_parent(self):
        self.assertEqual(flags(["platforms/rtp-neoforge/build.gradle"]), {"neoforge", "plugin"})

    def test_bundled_addon_flags_plugin(self):
        self.assertEqual(flags(["addons/LeafRTPClaimAddon/src/A.java"]), {"addons", "plugin"})

    def test_helper_change_flags_referencing_addon(self):
        self.assertEqual(flags(["helpers/MockItemPlugins/src/M.java"]),
                         {"helpers", "addons", "plugin"})

    def test_core_test_consumer_flags_core_only(self):
        self.assertEqual(flags(["addons/LeafRTPActionAddon/src/main/resources/a.yml"]),
                         {"addons", "core"})

    def test_out_of_project_path_flags_everything(self):
        for path in ("build.gradle", "gradle/libs.versions.toml", ".github/workflows/gradle.yml",
                     "addons/shared.gradle"):
            self.assertEqual(flags([path]), set(ci_scope.SETS), path)

    def test_ignored_only_flags_nothing(self):
        self.assertEqual(flags(["docs/a.md", "addons/REQUIREMENTS.md"]), set())

    def test_project_outside_every_set_flags_everything(self):
        tree = {**TREE, "new-module": "new-module"}
        result = ci_scope.affected_sets(["new-module/src/A.java"], tree, ALL_REFS, CONSUMERS)
        self.assertTrue(all(result.values()))


class SetTasksTest(unittest.TestCase):
    def _tasks(self, changed):
        result = ci_scope.affected_projects(changed, TREE, ALL_REFS, CONSUMERS)
        return {k: v for k, v in ci_scope.set_tasks(TREE, result).items() if v}

    def test_addon_change_builds_addon_and_plugin_only(self):
        self.assertEqual(self._tasks(["addons/LeafRTPClaimAddon/src/A.java"]), {
            "addons": [":addons:LeafRTPClaimAddon:build"],
            "plugin": [":rtp-plugin:build"],
        })

    def test_core_test_consumer_runs_test_not_build(self):
        self.assertEqual(self._tasks(["addons/LeafRTPActionAddon/src/main/resources/a.yml"]), {
            "addons": [":addons:LeafRTPActionAddon:build"],
            "core": [":rtp-core:test"],
        })

    def test_unscoped_builds_every_project(self):
        tasks = ci_scope.set_tasks(TREE, None)
        self.assertIn(":rtp-core:build", tasks["core"])
        self.assertIn(":rtp-neoforge:rtp-neoforge-common:build", tasks["neoforge"])
        self.assertIn(":helpers:MockItemPlugins:build", tasks["helpers"])

    def test_unscoped_change_returns_none(self):
        self.assertIsNone(ci_scope.affected_projects(["build.gradle"], TREE, ALL_REFS, CONSUMERS))


WORKFLOW = """\
name: x
on: push
jobs:
  changes:
    runs-on: ubuntu-latest
  core:
    needs: changes
  bukkit:
    needs: [changes, core]
  neoforge:
    needs:
      - changes
      - core
  helpers:
    needs: changes
  addons:
    needs: [changes, core, helpers]
  fabric:
    needs: [changes, core]
  proxy:
    needs: [changes, core]
  plugin:
    needs: [changes, bukkit, neoforge, addons]
"""


class WorkflowCheckTest(unittest.TestCase):
    GRAPH = ci_scope.set_graph(TREE, ALL_REFS)

    def test_set_graph_edges(self):
        self.assertEqual(self.GRAPH["bukkit"], {"core"})
        self.assertEqual(self.GRAPH["plugin"], {"bukkit", "neoforge", "addons"})
        self.assertEqual(self.GRAPH["addons"], {"core", "helpers"})

    def test_needs_forms_parsed(self):
        needs = ci_scope.workflow_needs(WORKFLOW)
        self.assertEqual(needs["changes"], set())
        self.assertEqual(needs["core"], {"changes"})
        self.assertEqual(needs["neoforge"], {"changes", "core"})
        self.assertEqual(needs["plugin"], {"changes", "bukkit", "neoforge", "addons"})

    def test_matching_workflow_passes(self):
        self.assertEqual(ci_scope.check_workflow(self.GRAPH, ci_scope.workflow_needs(WORKFLOW)), [])

    def test_missing_edge_reported(self):
        broken = WORKFLOW.replace("    needs: [changes, core, helpers]", "    needs: [changes, core]")
        errors = ci_scope.check_workflow(self.GRAPH, ci_scope.workflow_needs(broken))
        self.assertEqual(len(errors), 1)
        self.assertIn("'addons'", errors[0])
        self.assertIn("'helpers'", errors[0])

    def test_missing_job_reported(self):
        no_proxy = WORKFLOW.replace("  proxy:\n    needs: [changes, core]\n", "")
        errors = ci_scope.check_workflow(self.GRAPH, ci_scope.workflow_needs(no_proxy))
        self.assertEqual(errors, ["no job 'proxy' for project set 'proxy'"])

    def test_cycle_met_by_either_direction(self):
        graph = {name: set() for name in ci_scope.SETS}
        graph["core"] = {"proxy"}
        graph["proxy"] = {"core"}
        self.assertEqual(ci_scope.check_workflow(graph, ci_scope.workflow_needs(WORKFLOW)), [])

    def test_repo_workflow_matches_repo_graph(self):
        root = Path(__file__).resolve().parent.parent.parent
        tree = ci_scope.project_dirs((root / "settings.gradle").read_text(encoding="utf-8"), parents=True)
        graph = ci_scope.set_graph(tree, ci_scope.all_refs(root, tree))
        needs = ci_scope.workflow_needs(
            (root / ".github" / "workflows" / "gradle.yml").read_text(encoding="utf-8"))
        self.assertEqual(ci_scope.check_workflow(graph, needs), [])


class ConsumerScanTest(unittest.TestCase):
    def test_path_literals_detected_prose_ignored(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            test_dir = root / "rtp-core" / "src" / "test" / "java"
            test_dir.mkdir(parents=True)
            (test_dir / "UsesAddon.java").write_text(
                'class A { java.io.File f = new java.io.File("../addons/LeafRTPActionAddon/src/main/resources"); }\n'
                'class B { String s = "belongs to helpers/StressTestRTP."; }\n',
                encoding="utf-8")
            leaf_test = root / "addons" / "LeafRTPClaimAddon" / "src" / "test"
            leaf_test.mkdir(parents=True)
            (leaf_test / "Self.java").write_text('String p = "addons/LeafRTPClaimAddon/x";', encoding="utf-8")
            found = ci_scope.test_source_consumers(root, DIRS)
        self.assertEqual(found, {"addons:LeafRTPActionAddon": {"rtp-core"}})


class MainFallbackTest(unittest.TestCase):
    def _run(self, argv):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "out"
            old = os.environ.get("GITHUB_OUTPUT")
            os.environ["GITHUB_OUTPUT"] = str(out)
            try:
                self.assertEqual(ci_scope.main(["ci-scope.py", *argv]), 0)
            finally:
                if old is None:
                    del os.environ["GITHUB_OUTPUT"]
                else:
                    os.environ["GITHUB_OUTPUT"] = old
            return out.read_text(encoding="utf-8")

    def test_no_baseline_is_full(self):
        out = self._run(["--base", ""])
        self.assertIn("scope=full\n", out)
        self.assertIn("full=true\n", out)
        for name in ci_scope.SETS:
            self.assertIn(f"{name}=true\n", out)

    def test_zero_sha_is_full(self):
        self.assertIn("scope=full\n", self._run(["--base", "0" * 40]))

    def test_force_full(self):
        self.assertIn("scope=full\n", self._run(["--force-full", "--base", "HEAD"]))

    def test_unresolvable_baseline_is_full(self):
        self.assertIn("scope=full\n", self._run(["--base", "no-such-ref-ci-scope-test"]))


if __name__ == "__main__":
    unittest.main()
