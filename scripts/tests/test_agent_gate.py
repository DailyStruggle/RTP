"""Unit tests for scripts/agent-gate.py (local CI-parity gate).

Covers ci-scope.py output parsing and the mapping from affected sets to the
Gradle task list, which mirrors the set-job conditions in gradle.yml.
"""

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

script_path = Path(__file__).resolve().parent.parent / "agent-gate.py"
spec = importlib.util.spec_from_file_location("agent_gate", script_path)
agent_gate = importlib.util.module_from_spec(spec)
sys.modules["agent_gate"] = agent_gate
spec.loader.exec_module(agent_gate)


def scope_output(scope, flags, set_tasks):
    lines = ["3 changed file(s) vs abc", f"scope={scope}", "tasks="]
    lines += [f"{name}={'true' if name in flags else 'false'}" for name in set_tasks]
    lines.append("full=false")
    lines += [f"{name}_tasks={' '.join(tasks)}" for name, tasks in set_tasks.items()]
    return "\n".join(lines) + "\n"


SET_TASKS = {
    "core": [":rtp-core:build", ":rtp-api:build"],
    "bukkit": [":rtp-bukkit:build"],
    "plugin": [":rtp-plugin:build"],
    "addons": [":addons:LeafRTPActionAddon:build", ":rtp-core:build"],
}


class ParseScopeTest(unittest.TestCase):
    def test_key_values_parsed_prose_skipped(self):
        parsed = agent_gate.parse_scope(scope_output("leaf", {"addons"}, SET_TASKS))
        self.assertEqual(parsed["scope"], "leaf")
        self.assertEqual(parsed["addons"], "true")
        self.assertEqual(parsed["core"], "false")
        self.assertNotIn("3 changed file(s) vs abc", parsed)


class GradleTasksTest(unittest.TestCase):
    def tasks(self, scope, flags):
        return agent_gate.gradle_tasks(agent_gate.parse_scope(scope_output(scope, flags, SET_TASKS)))

    def test_only_affected_sets_deduplicated(self):
        self.assertEqual(self.tasks("leaf", {"addons", "core"}),
                         [":rtp-core:build", ":rtp-api:build", ":addons:LeafRTPActionAddon:build"])

    def test_plugin_set_skipped_outside_full_scope(self):
        self.assertEqual(self.tasks("leaf", {"plugin", "addons"}),
                         [":addons:LeafRTPActionAddon:build", ":rtp-core:build"])

    def test_full_scope_adds_plugin_and_binary_compat(self):
        tasks = self.tasks("full", {"core", "bukkit", "plugin", "addons"})
        self.assertIn(":rtp-plugin:build", tasks)
        self.assertEqual(tasks[-1], "checkBinaryCompatibility")

    def test_no_affected_sets_no_tasks(self):
        self.assertEqual(self.tasks("leaf", set()), [])


if __name__ == "__main__":
    unittest.main()
