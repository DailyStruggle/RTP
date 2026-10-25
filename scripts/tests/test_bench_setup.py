"""Tests for the repeatable Paper fuzz-bench setup utility."""
from __future__ import annotations

import os
import shutil
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

    def test_prepare_server_strips_perf_disable_shared_mem_and_connection_throttle(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "source"
            destination = root / "bench"
            (source / "plugins").mkdir(parents=True)
            (source / "server.properties").write_text("server-port=25565\n", encoding="utf-8")
            (source / "spigot.yml").write_text("settings:\n  restart-script: ./start.sh\n", encoding="utf-8")
            (source / "bukkit.yml").write_text("settings:\n  connection-throttle: 4000\n", encoding="utf-8")
            (source / "start.sh").write_text(
                "java -Xms16G -Xmx16G -XX:+PerfDisableSharedMem -XX:+UseG1GC -jar paper.jar --nogui\n",
                encoding="utf-8",
            )

            setup.prepare_server(source, destination, heap="12G")

            start_text = (destination / "start.sh").read_text(encoding="utf-8")
            self.assertNotIn("-XX:+PerfDisableSharedMem", start_text)
            self.assertIn("-Xms12G -Xmx12G", start_text)
            bukkit_text = (destination / "bukkit.yml").read_text(encoding="utf-8")
            self.assertIn("connection-throttle: -1", bukkit_text)

    @unittest.skipUnless(shutil.which("javac"), "javac required to build FuzzFixturePaper.jar")
    def test_smp_sample_preset_excludes_leafrtp_and_builds_fuzz_fixture_and_betterrtp_cache_config(self):
        import zipfile
        import bench_fuzz_server as bfs

        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "source"
            destination = root / "bench"
            local_jars = root / "downloads"
            plugins = source / "plugins"
            plugins.mkdir(parents=True)
            local_jars.mkdir(parents=True)
            (source / "server.properties").write_text("server-port=25565\n", encoding="utf-8")
            (source / "spigot.yml").write_text("settings:\n  restart-script: ./start.sh\n", encoding="utf-8")
            (source / "start.sh").write_text("java -Xms8G -Xmx8G -jar paper.jar\n", encoding="utf-8")
            (plugins / "LeafRTP" / "lang").mkdir(parents=True)
            (plugins / "LeafRTP" / "config.yml").write_text("old", encoding="utf-8")

            for name in ("ViaVersion-5.12.1.jar", "ViaBackwards-5.12.1.jar", "LuckPerms-Bukkit-5.5.57.jar", "LeafRTP-Pro.jar"):
                with zipfile.ZipFile(plugins / name, "w") as zf:
                    zf.writestr("plugin.yml", f"name: {name.split('-')[0]}\nversion: 1.0\n")

            for name in (
                "EssentialsX-2.22.0.jar",
                "GriefPrevention-16.18.7.jar",
                "CoreProtect-CE-24.1.jar",
                "BetterRTP-3.6.13.jar",
            ):
                with zipfile.ZipFile(local_jars / name, "w") as zf:
                    zf.writestr("plugin.yml", f"name: {name.split('-')[0]}\nversion: 1.0\n")

            setup.prepare_server(source, destination, port=25568, rcon_port=25578, keep=setup.SMP_SAMPLE_KEEP)
            installed = setup.install_smp_sample_plugins(
                destination / "plugins",
                game_version="26.2",
                local_search_dirs=(local_jars,),
            )

            installed_names = {p.name for p in installed}
            self.assertIn("LuckPerms-Bukkit-5.5.57.jar", installed_names)
            self.assertIn("EssentialsX-2.22.0.jar", installed_names)
            self.assertIn("GriefPrevention-16.18.7.jar", installed_names)
            self.assertIn("CoreProtect-CE-24.1.jar", installed_names)
            self.assertIn("BetterRTP-3.6.13.jar", installed_names)
            self.assertIn("FuzzFixturePaper.jar", installed_names)

            # LeafRTP must be excluded from active plugins and moved to plugins-disabled
            self.assertFalse((destination / "plugins" / "LeafRTP-Pro.jar").exists())
            self.assertTrue((destination / "plugins-disabled" / "LeafRTP-Pro.jar").exists())
            self.assertFalse((destination / "plugins" / "LeafRTP").exists())

            # BetterRTP config has Queue (location cache) enabled and 2s warmup delay
            brtp_cfg = (destination / "plugins" / "BetterRTP" / "config.yml").read_text(encoding="utf-8")
            self.assertIn("Queue:\n    Enabled: true", brtp_cfg)
            self.assertIn("Delay:\n    Enabled: true\n    Time: 2", brtp_cfg)
            self.assertIn("Cooldown:\n    Enabled: false", brtp_cfg)

            perms_cfg = (destination / "permissions.yml").read_text(encoding="utf-8")
            self.assertIn("betterrtp.world.*: true", perms_cfg)
            self.assertIn("ff.scan: true", perms_cfg)
            self.assertIn("ff.queue: true", perms_cfg)

            # Verify FuzzFixturePaper.jar only packages FuzzFixturePlugin classes (not Bukkit/Brigadier stubs)
            fixture_path = destination / "plugins" / "FuzzFixturePaper.jar"
            with zipfile.ZipFile(fixture_path, "r") as zf:
                entries = set(zf.namelist())
            self.assertIn("plugin.yml", entries)
            self.assertIn("io/github/dailystruggle/fuzzfixture/FuzzFixturePlugin.class", entries)
            self.assertIn("io/github/dailystruggle/fuzzfixture/FuzzFixturePlugin$QueueTicket.class", entries)
            self.assertFalse(any(e.startswith("org/bukkit/") or e.startswith("com/mojang/") for e in entries))

            # JarIndex maps FuzzFixturePlugin and QueueTicket directly to FuzzFixturePaper.jar
            jidx = bfs.JarIndex.from_server_dir(str(destination), profile="paper")
            self.assertEqual(
                jidx.owner_of_class("io.github.dailystruggle.fuzzfixture.FuzzFixturePlugin"),
                "FuzzFixturePaper.jar",
            )
            self.assertEqual(
                jidx.owner_of_class("io.github.dailystruggle.fuzzfixture.FuzzFixturePlugin$QueueTicket"),
                "FuzzFixturePaper.jar",
            )


if __name__ == "__main__":
    unittest.main()