#!/usr/bin/env python3
"""Create an isolated Paper server copy for repeatable bench_fuzz runs."""
from __future__ import annotations

import argparse
import fnmatch
import hashlib
import json
import re
import secrets
import shlex
import shutil
import subprocess
import tempfile
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path
from typing import Iterable


DEFAULT_KEEP = ("LuckPerms*", "Vault*", "StressTestRTP*", "LeafRTP*", "ViaVersion*", "ViaBackwards*")
SMP_SAMPLE_KEEP = ("LuckPerms*", "Vault*", "ViaVersion*", "ViaBackwards*")
SMP_SAMPLE_MODRINTH_PROJECTS: tuple[tuple[str, str], ...] = (
    ("viaversion", "ViaVersion*"),
    ("viabackwards", "ViaBackwards*"),
    ("luckperms", "LuckPerms*"),
    ("essentialsx", "EssentialsX*"),
    ("griefprevention", "GriefPrevention*"),
    ("coreprotect", "CoreProtect*"),
)
BETTERRTP_FILENAME = "BetterRTP-3.6.13.jar"
BETTERRTP_SPIGET_URL = "https://api.spiget.org/v2/resources/36081/versions/542316/download/proxy"
BETTERRTP_SPIGET_SHA256 = "960c49cb7c9a03f31d2dd751f83a78479b3465bcb5eb65789b19874e13d503f2"
BETTERRTP_HANGAR_FILENAME = "BetterRTP-3.6.8.jar"
BETTERRTP_HANGAR_URL = "https://hangar.papermc.io/api/v1/projects/Ronan/BetterRTP/versions/3.6.8/PAPER/download"
BETTERRTP_HANGAR_SHA256 = "8f88c3c975e4037ecd5ef90ad9de09ceb3a6aa761d1bc7123734c5632722847f"
SKIP_DIRS = {"logs", "crash-reports"}
NO_RESTART_SCRIPT = "./no-restart-script.sh"


def read_properties(path: str | Path) -> dict[str, str]:
    values = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith(("#", "!")):
            continue
        key, sep, value = stripped.partition("=")
        if sep:
            values[key.strip()] = value.strip()
    return values


def update_properties(path: str | Path, changes: dict[str, str]) -> None:
    path = Path(path)
    lines = path.read_text(encoding="utf-8").splitlines()
    missing = set(changes)
    out = []
    for line in lines:
        match = re.match(r"^(\s*)([^\s=:#]+)\s*=.*$", line)
        key = match.group(2) if match else None
        if key not in changes:
            out.append(line)
            continue
        if key in missing:
            out.append(f"{match.group(1)}{key}={changes[key]}")
            missing.remove(key)
    out.extend(f"{key}={changes[key]}" for key in changes if key in missing)
    path.write_text("\n".join(out) + "\n", encoding="utf-8")


def update_restart_script(path: str | Path) -> None:
    path = Path(path)
    text = path.read_text(encoding="utf-8")
    replacement = f"  restart-script: {NO_RESTART_SCRIPT}"
    pattern = re.compile(r"^(\s*)restart-script\s*:.*$", re.MULTILINE)
    text, count = pattern.subn(lambda m: f"{m.group(1)}restart-script: {NO_RESTART_SCRIPT}", text)
    if not count:
        if re.search(r"^\s*settings\s*:\s*$", text, re.MULTILINE):
            text = re.sub(r"^(\s*settings\s*:\s*)$", r"\1\n" + replacement, text,
                          count=1, flags=re.MULTILINE)
        else:
            text = text.rstrip() + "\nsettings:\n" + replacement + "\n"
    path.write_text(text.rstrip() + "\n", encoding="utf-8")


def copy_ignore(directory: str, names: list[str]) -> set[str]:
    ignored = set(names) & SKIP_DIRS
    if Path(directory).name.lower() == "plugins":
        ignored.update(name for name in names if name.lower() == "playit-gg")
    return ignored


def keep_plugin(filename: str, patterns: tuple[str, ...]) -> bool:
    name = filename.lower()
    return any(fnmatch.fnmatchcase(name, pattern.lower()) for pattern in patterns)


def set_heap(path: str | Path, heap: str) -> None:
    path = Path(path)
    text = path.read_text(encoding="utf-8")
    text, xms = re.subn(r"-Xms\S+", f"-Xms{heap}", text, count=1)
    text, xmx = re.subn(r"-Xmx\S+", f"-Xmx{heap}", text, count=1)
    if xms != 1 or xmx != 1:
        raise ValueError(f"{path} must contain one -Xms and one -Xmx option")
    path.write_text(text, encoding="utf-8")


def enable_jcmd_attach(path: str | Path) -> None:
    """Remove -XX:+PerfDisableSharedMem from start.sh so jcmd can attach to the bench JVM."""
    path = Path(path)
    if not path.is_file():
        return
    text = path.read_text(encoding="utf-8")
    updated = re.sub(
        r"^[ \t]*-XX:\+PerfDisableSharedMem[ \t]*(\\[ \t]*)?\r?\n",
        "",
        text,
        flags=re.MULTILINE,
    )
    updated = re.sub(r"[ \t]*-XX:\+PerfDisableSharedMem\b", "", updated)
    updated = re.sub(r"\\[ \t]+\\", r"\\", updated)
    if updated != text:
        path.write_text(updated, encoding="utf-8")


def disable_connection_throttle(path: str | Path) -> None:
    """Set connection-throttle: -1 in bukkit.yml so multi-bot joins/rejoins are not throttled."""
    path = Path(path)
    if not path.is_file():
        return
    text = path.read_text(encoding="utf-8")
    updated = re.sub(
        r"^(\s*connection-throttle\s*:\s*)-?\d+\s*$",
        r"\g<1>-1",
        text,
        flags=re.MULTILINE,
    )
    if updated != text:
        path.write_text(updated, encoding="utf-8")


def prepare_server(source: str | Path, destination: str | Path, *, port: int = 25566,
                   rcon_port: int = 25576, heap: str | None = None,
                   keep: tuple[str, ...] = DEFAULT_KEEP,
                   add_jars: tuple[str | Path, ...] = ()) -> Path:
    source, destination = Path(source).resolve(), Path(destination).resolve()
    if not source.is_dir():
        raise FileNotFoundError(f"server source does not exist: {source}")
    if source == destination or source in destination.parents:
        raise ValueError("bench destination must not be the source or inside it")
    if destination.exists():
        raise FileExistsError(f"bench destination already exists: {destination}")
    if not 1 <= port <= 65534 or not 1 <= rcon_port <= 65535:
        raise ValueError("game and RCON ports must be in [1, 65535], with game port <= 65534")
    if heap and not re.fullmatch(r"\d+[KMG]", heap.upper()):
        raise ValueError("heap must be a size such as 12G, 8192M, or 512K")
    required = (source / "server.properties", source / "spigot.yml", source / "start.sh", source / "plugins")
    missing = [str(path) for path in required if not path.exists()]
    if missing:
        raise FileNotFoundError("server source is missing: " + ", ".join(missing))
    for jar in add_jars:
        source_jar = Path(jar).resolve()
        if not source_jar.is_file() or source_jar.suffix.lower() != ".jar":
            raise FileNotFoundError(f"add-on plugin jar does not exist: {source_jar}")

    shutil.copytree(source, destination, ignore=copy_ignore)
    props = destination / "server.properties"
    password = secrets.token_hex(16)
    update_properties(props, {
        "server-port": str(port), "enable-rcon": "true", "rcon.port": str(rcon_port),
        "rcon.password": password, "online-mode": "false", "white-list": "false",
        "enforce-whitelist": "false", "enforce-secure-profile": "false", "max-players": "128",
        "allow-flight": "true",
        "enable-query": "false", "query.port": str(port + 1), "broadcast-rcon-to-ops": "false",
    })
    update_restart_script(destination / "spigot.yml")
    disable_connection_throttle(destination / "bukkit.yml")

    start_script = destination / "start.sh"
    start_script.chmod(start_script.stat().st_mode | 0o111)
    enable_jcmd_attach(start_script)
    if heap:
        set_heap(start_script, heap.upper())

    plugins = destination / "plugins"
    if not plugins.is_dir():
        raise FileNotFoundError(f"server source has no plugins directory: {plugins}")
    disabled = destination / "plugins-disabled"
    for jar in sorted(plugins.glob("*.jar")):
        if not keep_plugin(jar.name, keep):
            disabled.mkdir(exist_ok=True)
            shutil.move(str(jar), disabled / jar.name)
    plugins.mkdir(exist_ok=True)
    for jar in add_jars:
        source_jar = Path(jar).resolve()
        target_jar = plugins / source_jar.name
        if target_jar.exists():
            raise FileExistsError(f"plugin already exists in bench copy: {target_jar}")
        shutil.copy2(source_jar, target_jar)

    loop = destination / "bench-loop.sh"
    loop.write_text("#!/bin/sh\ncd \"$(dirname \"$0\")\"\nwhile true; do ./start.sh; sleep 2; done\n",
                    encoding="utf-8")
    loop.chmod(loop.stat().st_mode | 0o111)
    return destination


def game_version_from_history(server_dir: str | Path) -> str | None:
    path = Path(server_dir) / "version_history.json"
    if not path.is_file():
        return None
    data = json.loads(path.read_text(encoding="utf-8"))
    candidates = []

    def collect(value):
        if isinstance(value, dict):
            for key, child in value.items():
                if key.lower() in {"id", "version", "minecraftversion", "mcversion"} and isinstance(child, str):
                    if re.fullmatch(r"\d+\.\d+(?:\.\d+)?", child):
                        candidates.append(child)
                collect(child)
        elif isinstance(value, list):
            for child in value:
                collect(child)

    collect(data)
    return candidates[-1] if candidates else None


def install_via(plugins: str | Path, game_version: str) -> list[Path]:
    plugins = Path(plugins)
    params = urllib.parse.urlencode({"loaders": json.dumps(["paper"]),
                                     "game_versions": json.dumps([game_version])})
    headers = {"User-Agent": "LeafRTP-bench-setup/1.0 (local fuzz bench)"}
    installed = []
    for project in ("viaversion", "viabackwards"):
        request = urllib.request.Request(f"https://api.modrinth.com/v2/project/{project}/version?{params}",
                                         headers=headers)
        with urllib.request.urlopen(request, timeout=30) as response:
            versions = json.load(response)
        release = next((v for v in versions if v.get("version_type") == "release"), None)
        selected = release or (versions[0] if versions else None)
        if selected is None:
            raise RuntimeError(f"Modrinth has no {project} build for Minecraft {game_version}")
        file = next((f for f in selected.get("files", []) if f.get("primary")), None)
        file = file or next((f for f in selected.get("files", []) if f.get("filename", "").endswith(".jar")), None)
        if file is None:
            raise RuntimeError(f"Modrinth returned no jar for {project} {selected.get('version_number')}")
        target = plugins / file["filename"]
        request = urllib.request.Request(file["url"], headers=headers)
        with urllib.request.urlopen(request, timeout=120) as response, target.open("wb") as output:
            shutil.copyfileobj(response, output)
        installed.append(target)
    return installed


def build_fuzz_fixture_paper_jar(target_jar: str | Path) -> Path:
    """Compile and package FuzzFixturePaper.jar (ADR-125 section 2.7 planted stall + quit-retention leak)."""
    target_jar = Path(target_jar)
    target_jar.parent.mkdir(parents=True, exist_ok=True)
    javac_bin = shutil.which("javac")
    if not javac_bin:
        raise RuntimeError("javac is required on PATH to build FuzzFixturePaper.jar")

    stubs: dict[str, str] = {
        "org/bukkit/command/CommandSender.java": (
            "package org.bukkit.command;\n"
            "public interface CommandSender {\n"
            "    String getName();\n"
            "    void sendMessage(String message);\n"
            "}\n"
        ),
        "org/bukkit/entity/Player.java": (
            "package org.bukkit.entity;\n"
            "import org.bukkit.command.CommandSender;\n"
            "public interface Player extends CommandSender {\n"
            "    boolean isOnline();\n"
            "}\n"
        ),
        "org/bukkit/command/Command.java": (
            "package org.bukkit.command;\n"
            "public abstract class Command {\n"
            "    public String getName() { return \"\"; }\n"
            "}\n"
        ),
        "org/bukkit/plugin/Plugin.java": (
            "package org.bukkit.plugin;\n"
            "public interface Plugin {}\n"
        ),
        "org/bukkit/scheduler/BukkitTask.java": (
            "package org.bukkit.scheduler;\n"
            "public interface BukkitTask {}\n"
        ),
        "org/bukkit/scheduler/BukkitScheduler.java": (
            "package org.bukkit.scheduler;\n"
            "import org.bukkit.plugin.Plugin;\n"
            "public interface BukkitScheduler {\n"
            "    BukkitTask runTask(Plugin plugin, Runnable task);\n"
            "    BukkitTask runTaskLater(Plugin plugin, Runnable task, long delay);\n"
            "}\n"
        ),
        "org/bukkit/Bukkit.java": (
            "package org.bukkit;\n"
            "import org.bukkit.scheduler.BukkitScheduler;\n"
            "public final class Bukkit {\n"
            "    public static BukkitScheduler getScheduler() { return null; }\n"
            "    public static boolean isPrimaryThread() { return true; }\n"
            "}\n"
        ),
        "org/bukkit/plugin/java/JavaPlugin.java": (
            "package org.bukkit.plugin.java;\n"
            "import java.util.logging.Logger;\n"
            "import org.bukkit.command.Command;\n"
            "import org.bukkit.command.CommandSender;\n"
            "import org.bukkit.plugin.Plugin;\n"
            "public abstract class JavaPlugin implements Plugin {\n"
            "    public void onEnable() {}\n"
            "    public void onDisable() {}\n"
            "    public Logger getLogger() { return Logger.getGlobal(); }\n"
            "    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {\n"
            "        return false;\n"
            "    }\n"
            "}\n"
        ),
        "com/mojang/brigadier/exceptions/CommandSyntaxException.java": (
            "package com.mojang.brigadier.exceptions;\n"
            "public class CommandSyntaxException extends Exception {}\n"
        ),
        "com/mojang/brigadier/context/CommandContext.java": (
            "package com.mojang.brigadier.context;\n"
            "public class CommandContext<S> {\n"
            "    public S getSource() { return null; }\n"
            "}\n"
        ),
        "com/mojang/brigadier/Command.java": (
            "package com.mojang.brigadier;\n"
            "import com.mojang.brigadier.context.CommandContext;\n"
            "import com.mojang.brigadier.exceptions.CommandSyntaxException;\n"
            "@FunctionalInterface\n"
            "public interface Command<S> {\n"
            "    int run(CommandContext<S> context) throws CommandSyntaxException;\n"
            "}\n"
        ),
        "com/mojang/brigadier/arguments/ArgumentType.java": (
            "package com.mojang.brigadier.arguments;\n"
            "public interface ArgumentType<T> {}\n"
        ),
        "com/mojang/brigadier/arguments/IntegerArgumentType.java": (
            "package com.mojang.brigadier.arguments;\n"
            "import com.mojang.brigadier.context.CommandContext;\n"
            "public class IntegerArgumentType implements ArgumentType<Integer> {\n"
            "    public static IntegerArgumentType integer(int min, int max) { return null; }\n"
            "    public static int getInteger(CommandContext<?> context, String name) { return 0; }\n"
            "}\n"
        ),
        "com/mojang/brigadier/tree/CommandNode.java": (
            "package com.mojang.brigadier.tree;\n"
            "public abstract class CommandNode<S> {\n"
            "    public void removeCommand(String name) {}\n"
            "}\n"
        ),
        "com/mojang/brigadier/tree/RootCommandNode.java": (
            "package com.mojang.brigadier.tree;\n"
            "public class RootCommandNode<S> extends CommandNode<S> {}\n"
        ),
        "com/mojang/brigadier/tree/LiteralCommandNode.java": (
            "package com.mojang.brigadier.tree;\n"
            "public class LiteralCommandNode<S> extends CommandNode<S> {}\n"
        ),
        "com/mojang/brigadier/builder/ArgumentBuilder.java": (
            "package com.mojang.brigadier.builder;\n"
            "import com.mojang.brigadier.Command;\n"
            "import com.mojang.brigadier.tree.CommandNode;\n"
            "public abstract class ArgumentBuilder<S, T extends ArgumentBuilder<S, T>> {\n"
            "    public T then(ArgumentBuilder<S, ?> argument) { return null; }\n"
            "    public T executes(Command<S> command) { return null; }\n"
            "    public abstract CommandNode<S> build();\n"
            "}\n"
        ),
        "com/mojang/brigadier/builder/LiteralArgumentBuilder.java": (
            "package com.mojang.brigadier.builder;\n"
            "import com.mojang.brigadier.tree.LiteralCommandNode;\n"
            "public class LiteralArgumentBuilder<S> extends ArgumentBuilder<S, LiteralArgumentBuilder<S>> {\n"
            "    public static <S> LiteralArgumentBuilder<S> literal(String name) { return null; }\n"
            "    @Override public LiteralCommandNode<S> build() { return null; }\n"
            "}\n"
        ),
        "com/mojang/brigadier/builder/RequiredArgumentBuilder.java": (
            "package com.mojang.brigadier.builder;\n"
            "import com.mojang.brigadier.arguments.ArgumentType;\n"
            "import com.mojang.brigadier.tree.CommandNode;\n"
            "public class RequiredArgumentBuilder<S, T> extends ArgumentBuilder<S, RequiredArgumentBuilder<S, T>> {\n"
            "    public static <S, T> RequiredArgumentBuilder<S, T> argument(String name, ArgumentType<T> type) { return null; }\n"
            "    @Override public CommandNode<S> build() { return null; }\n"
            "}\n"
        ),
        "com/mojang/brigadier/CommandDispatcher.java": (
            "package com.mojang.brigadier;\n"
            "import com.mojang.brigadier.builder.LiteralArgumentBuilder;\n"
            "import com.mojang.brigadier.tree.LiteralCommandNode;\n"
            "import com.mojang.brigadier.tree.RootCommandNode;\n"
            "public class CommandDispatcher<S> {\n"
            "    public LiteralCommandNode<S> register(LiteralArgumentBuilder<S> command) { return null; }\n"
            "    public RootCommandNode<S> getRoot() { return null; }\n"
            "}\n"
        ),
        "net/minecraft/commands/CommandSourceStack.java": (
            "package net.minecraft.commands;\n"
            "import org.bukkit.command.CommandSender;\n"
            "public class CommandSourceStack {\n"
            "    public CommandSender getBukkitSender() { return null; }\n"
            "}\n"
        ),
        "net/minecraft/commands/Commands.java": (
            "package net.minecraft.commands;\n"
            "import com.mojang.brigadier.CommandDispatcher;\n"
            "import com.mojang.brigadier.arguments.ArgumentType;\n"
            "import com.mojang.brigadier.builder.LiteralArgumentBuilder;\n"
            "import com.mojang.brigadier.builder.RequiredArgumentBuilder;\n"
            "public class Commands {\n"
            "    public static LiteralArgumentBuilder<CommandSourceStack> literal(String name) { return null; }\n"
            "    public static <T> RequiredArgumentBuilder<CommandSourceStack, T> argument(String name, ArgumentType<T> type) { return null; }\n"
            "    public CommandDispatcher<CommandSourceStack> getDispatcher() { return null; }\n"
            "}\n"
        ),
        "net/minecraft/server/MinecraftServer.java": (
            "package net.minecraft.server;\n"
            "import net.minecraft.commands.Commands;\n"
            "public abstract class MinecraftServer {\n"
            "    public static MinecraftServer getServer() { return null; }\n"
            "    public Commands getCommands() { return null; }\n"
            "}\n"
        ),
        "io/github/dailystruggle/fuzzfixture/FuzzFixturePlugin.java": (
            "package io.github.dailystruggle.fuzzfixture;\n"
            "import java.util.ArrayList;\n"
            "import java.util.Collections;\n"
            "import java.util.Iterator;\n"
            "import java.util.List;\n"
            "import org.bukkit.Bukkit;\n"
            "import org.bukkit.command.Command;\n"
            "import org.bukkit.command.CommandSender;\n"
            "import org.bukkit.entity.Player;\n"
            "import org.bukkit.plugin.java.JavaPlugin;\n"
            "import com.mojang.brigadier.CommandDispatcher;\n"
            "import com.mojang.brigadier.arguments.IntegerArgumentType;\n"
            "import com.mojang.brigadier.tree.RootCommandNode;\n"
            "import net.minecraft.commands.CommandSourceStack;\n"
            "import net.minecraft.commands.Commands;\n"
            "import net.minecraft.server.MinecraftServer;\n"
            "\n"
            "public final class FuzzFixturePlugin extends JavaPlugin {\n"
            "    public static final class QueueTicket {\n"
            "        public final Object player;\n"
            "        public final byte[] payload;\n"
            "        public final long createdAtMs;\n"
            "        public QueueTicket(Object player, int n) {\n"
            "            this.player = player;\n"
            "            this.payload = new byte[4096];\n"
            "            this.createdAtMs = System.currentTimeMillis() + n;\n"
            "        }\n"
            "    }\n"
            "\n"
            "    public static final List<QueueTicket> PENDING_QUEUE = Collections.synchronizedList(new ArrayList<>());\n"
            "    public static volatile long scanSink = 0L;\n"
            "\n"
            "    @Override\n"
            "    public void onEnable() {\n"
            "        try {\n"
            "            registerBrigadierCommands();\n"
            "        } catch (Throwable ignored) {}\n"
            "        try {\n"
            "            if (Bukkit.getScheduler() != null) {\n"
            "                Bukkit.getScheduler().runTask(this, this::registerBrigadierCommands);\n"
            "            }\n"
            "        } catch (Throwable ignored) {}\n"
            "    }\n"
            "\n"
            "    public void registerBrigadierCommands() {\n"
            "        try {\n"
            "            MinecraftServer srv = MinecraftServer.getServer();\n"
            "            if (srv == null || srv.getCommands() == null) return;\n"
            "            CommandDispatcher<CommandSourceStack> disp = srv.getCommands().getDispatcher();\n"
            "            if (disp == null) return;\n"
            "            RootCommandNode<CommandSourceStack> root = disp.getRoot();\n"
            "            String[] scanLabels = new String[]{\"ff:scan\", \"scan\"};\n"
            "            for (String lbl : scanLabels) {\n"
            "                try { root.removeCommand(lbl); } catch (Throwable ignored) {}\n"
            "                disp.register(\n"
            "                    Commands.literal(lbl)\n"
            "                        .executes(ctx -> {\n"
            "                            handleScan(ctx.getSource().getBukkitSender(), 64);\n"
            "                            return 1;\n"
            "                        })\n"
            "                        .then(\n"
            "                            Commands.argument(\"radius\", IntegerArgumentType.integer(1, 128))\n"
            "                                .executes(ctx -> {\n"
            "                                    int r = IntegerArgumentType.getInteger(ctx, \"radius\");\n"
            "                                    handleScan(ctx.getSource().getBukkitSender(), r);\n"
            "                                    return 1;\n"
            "                                })\n"
            "                        )\n"
            "                );\n"
            "            }\n"
            "            String[] queueLabels = new String[]{\"ff:queue\", \"queue\"};\n"
            "            for (String lbl : queueLabels) {\n"
            "                try { root.removeCommand(lbl); } catch (Throwable ignored) {}\n"
            "                disp.register(\n"
            "                    Commands.literal(lbl)\n"
            "                        .executes(ctx -> {\n"
            "                            handleQueue(ctx.getSource().getBukkitSender(), 4);\n"
            "                            return 1;\n"
            "                        })\n"
            "                        .then(\n"
            "                            Commands.argument(\"n\", IntegerArgumentType.integer(1, 16))\n"
            "                                .executes(ctx -> {\n"
            "                                    int n = IntegerArgumentType.getInteger(ctx, \"n\");\n"
            "                                    handleQueue(ctx.getSource().getBukkitSender(), n);\n"
            "                                    return 1;\n"
            "                                })\n"
            "                        )\n"
            "                );\n"
            "            }\n"
            "        } catch (Throwable ignored) {}\n"
            "    }\n"
            "\n"
            "    @Override\n"
            "    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {\n"
            "        String name = command != null ? command.getName().toLowerCase() : (label != null ? label.toLowerCase() : \"\");\n"
            "        if (name.endsWith(\"scan\")) {\n"
            "            int radius = 64;\n"
            "            if (args != null && args.length > 0) {\n"
            "                try { radius = Integer.parseInt(args[0]); } catch (NumberFormatException ignored) {}\n"
            "            }\n"
            "            handleScan(sender, radius);\n"
            "            return true;\n"
            "        }\n"
            "        if (name.endsWith(\"queue\")) {\n"
            "            int n = 4;\n"
            "            if (args != null && args.length > 0) {\n"
            "                try { n = Integer.parseInt(args[0]); } catch (NumberFormatException ignored) {}\n"
            "            }\n"
            "            handleQueue(sender, n);\n"
            "            return true;\n"
            "        }\n"
            "        return false;\n"
            "    }\n"
            "\n"
            "    public void handleScan(CommandSender sender, int radius) {\n"
            "        if (radius > 48) {\n"
            "            if (Bukkit.isPrimaryThread() || Bukkit.getScheduler() == null) {\n"
            "                runMainThreadScan(radius);\n"
            "            } else {\n"
            "                Bukkit.getScheduler().runTask(this, () -> runMainThreadScan(radius));\n"
            "            }\n"
            "        }\n"
            "        if (sender != null) {\n"
            "            sender.sendMessage(\"Scanned radius \" + radius);\n"
            "        }\n"
            "    }\n"
            "\n"
            "    public static void runMainThreadScan(int radius) {\n"
            "        long until = System.currentTimeMillis() + 3000L;\n"
            "        while (System.currentTimeMillis() < until) {\n"
            "            scanSink += radius;\n"
            "            try {\n"
            "                Thread.sleep(5L);\n"
            "            } catch (InterruptedException ignored) {\n"
            "                Thread.currentThread().interrupt();\n"
            "                break;\n"
            "            }\n"
            "        }\n"
            "    }\n"
            "\n"
            "    public void handleQueue(CommandSender sender, int n) {\n"
            "        int count = Math.max(1, Math.min(64, n));\n"
            "        Object holder = sender;\n"
            "        for (int i = 0; i < count * 32; i++) {\n"
            "            PENDING_QUEUE.add(new QueueTicket(holder, i));\n"
            "        }\n"
            "        if (sender instanceof Player && Bukkit.getScheduler() != null) {\n"
            "            final Player player = (Player) sender;\n"
            "            Bukkit.getScheduler().runTaskLater(this, () -> {\n"
            "                if (player.isOnline()) {\n"
            "                    synchronized (PENDING_QUEUE) {\n"
            "                        Iterator<QueueTicket> it = PENDING_QUEUE.iterator();\n"
            "                        while (it.hasNext()) {\n"
            "                            if (it.next().player == player) {\n"
            "                                it.remove();\n"
            "                            }\n"
            "                        }\n"
            "                    }\n"
            "                }\n"
            "            }, 30L);\n"
            "        }\n"
            "        if (sender != null) {\n"
            "            sender.sendMessage(\"Queued \" + count);\n"
            "        }\n"
            "    }\n"
            "}\n"
        ),
    }

    plugin_yml = (
        "name: ff\n"
        "main: io.github.dailystruggle.fuzzfixture.FuzzFixturePlugin\n"
        "version: 1.0.0\n"
        "api-version: '1.13'\n"
        "commands:\n"
        "  queue:\n"
        "    description: Queue a deferred player action (ADR-125 planted quit-retention leak)\n"
        "    usage: /<command> <n>\n"
        "    permission: ff.queue\n"
        "  scan:\n"
        "    description: Scan a block radius (ADR-125 planted main-thread stall for radius > 48)\n"
        "    usage: /<command> <radius>\n"
        "    permission: ff.scan\n"
        "permissions:\n"
        "  ff.queue:\n"
        "    default: true\n"
        "  ff.scan:\n"
        "    default: true\n"
    )

    with tempfile.TemporaryDirectory() as tmp:
        src_root = Path(tmp) / "src"
        out_root = Path(tmp) / "out"
        out_root.mkdir(parents=True, exist_ok=True)
        java_files: list[str] = []
        for rel_path, content in stubs.items():
            full_path = src_root / rel_path
            full_path.parent.mkdir(parents=True, exist_ok=True)
            full_path.write_text(content, encoding="utf-8")
            java_files.append(str(full_path))

        subprocess.run(
            [javac_bin, "--release", "17", "-d", str(out_root), *java_files],
            check=True,
            timeout=60,
        )

        pkg_dir = out_root / "io" / "github" / "dailystruggle" / "fuzzfixture"
        with zipfile.ZipFile(target_jar, "w", compression=zipfile.ZIP_DEFLATED) as zf:
            zf.writestr("plugin.yml", plugin_yml)
            for cls_file in sorted(pkg_dir.glob("*.class")):
                arcname = f"io/github/dailystruggle/fuzzfixture/{cls_file.name}"
                zf.write(cls_file, arcname)

    return target_jar


def configure_smp_sample_configs(destination: str | Path) -> None:
    """Configure BetterRTP with location cache + 2s delay and grant default world/fixture permissions."""
    destination = Path(destination)
    plugins = destination / "plugins"
    # Ensure no residual LeafRTP data directory remains in the sample server
    for dir_name in ("LeafRTP", "RTP"):
        leaf_dir = plugins / dir_name
        if leaf_dir.is_dir():
            shutil.rmtree(leaf_dir)

    brtp_dir = plugins / "BetterRTP"
    brtp_dir.mkdir(parents=True, exist_ok=True)
    brtp_cfg = (
        "Language-File: 'en.yml'\n"
        "Settings:\n"
        "  Respect:\n"
        "    MinePlots: false\n"
        "    WorldGuard: false\n"
        "    GriefPrevention: true\n"
        "    Towny: false\n"
        "    RedProtect: false\n"
        "    FactionsUUID: false\n"
        "    Lands: false\n"
        "    Residence: false\n"
        "    KingdomsX: false\n"
        "    hClaims: false\n"
        "    GriefDefender: false\n"
        "    UltimateClaims: false\n"
        "    Pueblos: false\n"
        "    SaberFactions: false\n"
        "    HuskTowns: false\n"
        "  Debugger: false\n"
        "  PreloadRadius: 1\n"
        "  MaxAttempts: 16\n"
        "  RtpOnFirstJoin:\n"
        "    Enabled: false\n"
        "    World: 'world'\n"
        "    SetAsRespawn: false\n"
        "  Cooldown:\n"
        "    Enabled: false\n"
        "    LockAfter: 0\n"
        "    Time: 0\n"
        "    PerWorld: false\n"
        "  Delay:\n"
        "    Enabled: true\n"
        "    Time: 2\n"
        "    CancelOnMove: false\n"
        "  Queue:\n"
        "    Enabled: true\n"
        "  DisableUpdater: true\n"
        "  Logger:\n"
        "    Enabled: true\n"
        "    LogToConsole: false\n"
        "    Format: 'yyyy-MM-dd HH:mm:ss'\n"
        "Default:\n"
        "  UseWorldBorder: false\n"
        "  Biomes: []\n"
        "  MaxRadius: 500\n"
        "  MinRadius: 10\n"
        "  CenterX: 0\n"
        "  CenterZ: 0\n"
        "  Shape: 'square'\n"
        "  MaxY: 320\n"
        "  MinY: 0\n"
        "BlacklistedBlocks:\n"
        "  - stationary_water\n"
        "  - stationary_lava\n"
        "  - water\n"
        "  - flowing_water\n"
        "  - lava\n"
        "  - flowing_lava\n"
        "  - cactus\n"
        "  - air\n"
        "  - void_air\n"
        "  - bedrock\n"
        "DisabledWorlds:\n"
        "  - prison\n"
        "  - creative\n"
        "CustomWorlds: []\n"
        "Overrides: []\n"
        "WorldType:\n"
        "  - world: NORMAL\n"
        "  - world_nether: NETHER\n"
        "  - world_the_end: NORMAL\n"
        "PermissionGroup:\n"
        "  Enabled: false\n"
        "  Groups: []\n"
    )
    (brtp_dir / "config.yml").write_text(brtp_cfg, encoding="utf-8")

    perms_yml = (
        "smp_sample.default:\n"
        "  description: Default player permissions for the ADR-125 sample and competitor benchmark servers\n"
        "  default: true\n"
        "  children:\n"
        "    rtp.use: true\n"
        "    rtp.world.*: true\n"
        "    betterrtp.use: true\n"
        "    betterrtp.world.*: true\n"
        "    betterrtp.world.world: true\n"
        "    betterrtp.world.world_nether: true\n"
        "    betterrtp.world.world_the_end: true\n"
        "    justrtp.command.rtp: true\n"
        "    justrtp.command.rtp.world: true\n"
        "    justrtp.command.rtp.world.*: true\n"
        "    justrtp.cooldown.bypass: true\n"
        "    justrtp.delay.bypass: true\n"
        "    ezrtp.use: true\n"
        "    ezrtp.bypass.cooldown: true\n"
        "    ezrtp.bypass.limit: true\n"
        "    ezrtp.queue.bypass: true\n"
        "    jakesrtp.use: true\n"
        "    jakesrtp.use.default-settings: true\n"
        "    jakesrtp.nocooldown: true\n"
        "    jakesrtp.nowarmup: true\n"
        "    huskhomes.command.rtp: true\n"
        "    huskhomes.bypass_cooldowns: true\n"
        "    huskhomes.bypass_warmup: true\n"
        "    advancedrtp.use: true\n"
        "    advancedrtp.world.*: true\n"
        "    advancedrtp.world.world: true\n"
        "    advancedrtp.bypass.cooldown: true\n"
        "    asyrtp.command.rtp: true\n"
        "    asyrtp.use: true\n"
        "    asyrtp.direct: true\n"
        "    asyrtp.profile.default: true\n"
        "    asyrtp.gui.world.world: true\n"
        "    sorekillrtp.rtp: true\n"
        "    sorekillrtp.cooldown.bypass: true\n"
        "    sorekillrtp.tptimer.bypass: true\n"
        "    ff.scan: true\n"
        "    ff.queue: true\n"
    )
    (destination / "permissions.yml").write_text(perms_yml, encoding="utf-8")


def _download_modrinth_latest_jar(project: str, plugins: Path, game_version: str | None = None) -> Path:
    headers = {"User-Agent": "LeafRTP-bench-setup/1.0 (local fuzz bench)"}
    versions: list[dict] = []
    if game_version:
        params = urllib.parse.urlencode({"loaders": json.dumps(["paper", "bukkit", "spigot"]),
                                         "game_versions": json.dumps([game_version])})
        req = urllib.request.Request(f"https://api.modrinth.com/v2/project/{project}/version?{params}",
                                     headers=headers)
        with urllib.request.urlopen(req, timeout=30) as resp:
            versions = json.load(resp)
    if not versions:
        req = urllib.request.Request(f"https://api.modrinth.com/v2/project/{project}/version",
                                     headers=headers)
        with urllib.request.urlopen(req, timeout=30) as resp:
            all_versions = json.load(resp)
        versions = [
            v for v in all_versions
            if any(ldr in ("paper", "bukkit", "spigot", "folia") for ldr in (v.get("loaders") or []))
            and not any(
                "velocity" in (f.get("filename") or "").lower() or "bungee" in (f.get("filename") or "").lower()
                for f in (v.get("files") or [])
            )
        ] or all_versions

    release = next((v for v in versions if v.get("version_type") == "release"), None)
    selected = release or (versions[0] if versions else None)
    if selected is None:
        raise RuntimeError(f"Modrinth returned no versions for {project}")
    files = selected.get("files") or []
    file_entry = next(
        (
            f for f in files
            if f.get("filename", "").endswith(".jar")
            and not any(s in f.get("filename", "").lower() for s in ("anti-build", "antibuild", "chat", "discord", "geoip", "protect", "xmpp"))
            and f.get("primary")
        ),
        None,
    ) or next((f for f in files if f.get("filename", "").endswith(".jar")), None)
    if file_entry is None:
        raise RuntimeError(f"Modrinth returned no jar file for {project}")
    target = plugins / file_entry["filename"]
    req = urllib.request.Request(file_entry["url"], headers=headers)
    with urllib.request.urlopen(req, timeout=120) as resp, target.open("wb") as out:
        shutil.copyfileobj(resp, out)
    return target


def _download_betterrtp_jar(plugins: Path) -> Path:
    headers = {"User-Agent": "LeafRTP-bench-setup/1.0 (local fuzz bench)"}
    candidates = (
        (BETTERRTP_FILENAME, BETTERRTP_SPIGET_URL, BETTERRTP_SPIGET_SHA256),
        (BETTERRTP_HANGAR_FILENAME, BETTERRTP_HANGAR_URL, BETTERRTP_HANGAR_SHA256),
    )
    last_err: Exception | None = None
    for filename, url, expected_sha256 in candidates:
        try:
            req = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(req, timeout=60) as resp:
                raw = resp.read()
            digest = hashlib.sha256(raw).hexdigest()
            if expected_sha256 and digest.lower() != expected_sha256.lower():
                raise RuntimeError(f"{filename} SHA-256 mismatch: expected {expected_sha256}, got {digest}")
            target = plugins / filename
            target.write_bytes(raw)
            return target
        except Exception as err:
            last_err = err
    raise RuntimeError(f"Unable to download BetterRTP jar: {last_err}")


def install_smp_sample_plugins(
    plugins: str | Path,
    game_version: str | None = None,
    local_search_dirs: Iterable[str | Path] = (),
) -> list[Path]:
    """Provision EssentialsX, LuckPerms, GriefPrevention, CoreProtect, BetterRTP (cached), and FuzzFixturePaper."""
    plugins = Path(plugins)
    plugins.mkdir(parents=True, exist_ok=True)
    search_dirs = [Path(d) for d in local_search_dirs if d and Path(d).is_dir()]
    default_downloads = Path.home() / "Downloads"
    if default_downloads.is_dir() and default_downloads not in search_dirs:
        search_dirs.append(default_downloads)

    installed: list[Path] = []
    for project, pattern in SMP_SAMPLE_MODRINTH_PROJECTS:
        existing = sorted(p for p in plugins.glob("*.jar") if fnmatch.fnmatchcase(p.name.lower(), pattern.lower()))
        if existing:
            installed.append(existing[0])
            continue
        local_match: Path | None = None
        for sdir in search_dirs:
            matches = sorted(
                p for p in sdir.glob("*.jar")
                if fnmatch.fnmatchcase(p.name.lower(), pattern.lower())
                and "spawn" not in p.name.lower()
                and "chat" not in p.name.lower()
            )
            if matches:
                local_match = matches[-1]
                break
        if local_match is not None:
            target = plugins / local_match.name
            shutil.copy2(local_match, target)
            installed.append(target)
        else:
            installed.append(_download_modrinth_latest_jar(project, plugins, game_version=game_version))

    existing_brtp = sorted(p for p in plugins.glob("*.jar") if fnmatch.fnmatchcase(p.name.lower(), "betterrtp*"))
    if existing_brtp:
        installed.append(existing_brtp[0])
    else:
        local_brtp = None
        for sdir in search_dirs:
            matches = sorted(p for p in sdir.glob("*.jar") if fnmatch.fnmatchcase(p.name.lower(), "betterrtp*"))
            if matches:
                local_brtp = matches[-1]
                break
        if local_brtp is not None:
            target = plugins / local_brtp.name
            shutil.copy2(local_brtp, target)
            installed.append(target)
        else:
            installed.append(_download_betterrtp_jar(plugins))

    fixture_jar = build_fuzz_fixture_paper_jar(plugins / "FuzzFixturePaper.jar")
    installed.append(fixture_jar)
    configure_smp_sample_configs(plugins.parent)
    return installed


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", help="existing Paper server directory; it is never modified")
    parser.add_argument("destination", help="new bench directory (must not already exist)")
    parser.add_argument("--port", type=int, default=25566)
    parser.add_argument("--rcon-port", type=int, default=25576)
    parser.add_argument("--heap", help="replace -Xms/-Xmx in start.sh, e.g. 12G")
    parser.add_argument(
        "--preset",
        choices=("default", "smp-sample"),
        default="default",
        help="plugin preset: 'default' keeps LeafRTP/StressTestRTP; 'smp-sample' excludes LeafRTP and installs "
             "EssentialsX, LuckPerms, GriefPrevention, CoreProtect, BetterRTP (cached), and FuzzFixturePaper.jar",
    )
    parser.add_argument("--keep", action="append", help="plugin jar filename glob to keep (repeatable)")
    parser.add_argument("--add", dest="add_jars", action="append", default=[], help="plugin jar to copy (repeatable)")
    parser.add_argument("--via", action="store_true", help="download ViaVersion and ViaBackwards from Modrinth")
    parser.add_argument("--game-version", help="Minecraft version for --via (or version_history.json)")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        game_version = args.game_version or game_version_from_history(args.source)
        if args.via and not game_version:
            raise ValueError("--via needs --game-version or a readable version_history.json")
        default_keep = SMP_SAMPLE_KEEP if args.preset == "smp-sample" else DEFAULT_KEEP
        destination = prepare_server(args.source, args.destination, port=args.port, rcon_port=args.rcon_port,
                                     heap=args.heap, keep=tuple(args.keep or default_keep),
                                     add_jars=tuple(args.add_jars))
        if args.via:
            added = install_via(destination / "plugins", game_version)
            print("Installed: " + ", ".join(p.name for p in added))
        if args.preset == "smp-sample":
            sample_jars = install_smp_sample_plugins(destination / "plugins", game_version=game_version)
            print("SMP sample plugins ready: " + ", ".join(p.name for p in sample_jars))
        quoted = shlex.quote(str(destination))
        print(f"Bench copy ready: {destination}")
        print(f"Start: tmux new-session -d -s bench -c {quoted} ./bench-loop.sh")
        if args.preset == "smp-sample":
            print(f"Run: python3 scripts/bench_fuzz.py --mode server --active --profile paper "
                  f"--server-dir {quoted} --host 127.0.0.1 --tiers default,op --baseline-episodes 4 --episodes 60")
        else:
            print(f"Run: python3 scripts/bench_fuzz.py --server-dir {quoted} --host 127.0.0.1 "
                  "--targets direct,rtp --seeds 1 --episodes 24 --reps 3")
    except (OSError, ValueError, RuntimeError) as error:
        parser.error(str(error))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())