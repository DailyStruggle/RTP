"""Tests for the repeatable Paper fuzz-bench setup utility."""
from __future__ import annotations

import os
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))
import bench_setup as setup  # noqa: E402


class BenchSetupTest(unittest.TestCase):
    def test_properties_replace_existing_keys_and_append_missing_keys(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "server.properties"
            path.write_text("# test\nserver-port=25565\nonline-mode=true\n", encoding="utf-8")

            setup.update_properties(path, {"server-port": "25566", "enable-rcon": "true"})

            self.assertEqual(path.read_text(encoding="utf-8"),
                             "# test\nserver-port=25566\nonline-mode=true\nenable-rcon=true\n")

    def test_prepare_copies_and_configures_only_the_bench_server(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "source"
            destination = root / "bench"
            plugins = source / "plugins"
            plugins.mkdir(parents=True)
            (source / "server.properties").write_text(
                "server-port=25565\nenable-rcon=false\nrcon.port=25575\nrcon.password=\n"
                "online-mode=true\nwhite-list=true\nenforce-secure-profile=true\n"
                "enable-query=true\nquery.port=25565\nbroadcast-rcon-to-ops=true\n",
                encoding="utf-8")
            (source / "spigot.yml").write_text("settings:\n  restart-script: ./start.sh\n", encoding="utf-8")
            (source / "start.sh").write_text("java -Xms24G -Xmx24G -jar paper.jar\n", encoding="utf-8")
            (source / "logs").mkdir()
            (source / "logs" / "latest.log").write_text("preserve source", encoding="utf-8")
            (plugins / "playit-gg").mkdir()
            (plugins / "playit-gg" / "secret.txt").write_text("live tunnel token", encoding="utf-8")
            (plugins / "LuckPerms-Bukkit.jar").write_text("keep", encoding="utf-8")
            (plugins / "LeafRTP-Pro.jar").write_text("keep", encoding="utf-8")
            (plugins / "OtherPlugin.jar").write_text("disable", encoding="utf-8")
            add_jar = root / "StressTestRTP.jar"
            add_jar.write_text("added", encoding="utf-8")

            setup.prepare_server(source, destination, port=25566, rcon_port=25576, heap="12G",
                                 keep=("LuckPerms*", "LeafRTP*"), add_jars=(add_jar,))

            props = setup.read_properties(destination / "server.properties")
            self.assertEqual(props["server-port"], "25566")
            self.assertEqual(props["enable-rcon"], "true")
            self.assertEqual(props["rcon.port"], "25576")
            self.assertTrue(props["rcon.password"])
            self.assertEqual(props["online-mode"], "false")
            self.assertEqual(props["white-list"], "false")
            self.assertEqual(props["enforce-secure-profile"], "false")
            self.assertEqual(props["enable-query"], "false")
            self.assertEqual(props["broadcast-rcon-to-ops"], "false")
            self.assertIn("no-restart-script", (destination / "spigot.yml").read_text(encoding="utf-8"))
            self.assertIn("-Xms12G -Xmx12G", (destination / "start.sh").read_text(encoding="utf-8"))
            self.assertTrue((destination / "plugins" / "LuckPerms-Bukkit.jar").exists())
            self.assertTrue((destination / "plugins" / "LeafRTP-Pro.jar").exists())
            self.assertTrue((destination / "plugins" / "StressTestRTP.jar").exists())
            self.assertTrue((destination / "plugins-disabled" / "OtherPlugin.jar").exists())
            self.assertFalse((destination / "logs").exists())
            self.assertFalse((destination / "plugins" / "playit-gg").exists())
            self.assertEqual((source / "server.properties").read_text(encoding="utf-8").splitlines()[0],
                             "server-port=25565")
            self.assertEqual((source / "plugins" / "OtherPlugin.jar").read_text(encoding="utf-8"), "disable")
            self.assertTrue(os.access(destination / "bench-loop.sh", os.X_OK))

    def test_existing_destination_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source, destination = root / "source", root / "bench"
            source.mkdir()
            destination.mkdir()

            with self.assertRaises(FileExistsError):
                setup.prepare_server(source, destination)


if __name__ == "__main__":
    unittest.main()