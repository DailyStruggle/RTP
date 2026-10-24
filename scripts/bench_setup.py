#!/usr/bin/env python3
"""Create an isolated Paper server copy for repeatable bench_fuzz runs."""
from __future__ import annotations

import argparse
import fnmatch
import json
import re
import secrets
import shlex
import shutil
import urllib.parse
import urllib.request
from pathlib import Path


DEFAULT_KEEP = ("LuckPerms*", "Vault*", "StressTestRTP*", "LeafRTP*", "ViaVersion*", "ViaBackwards*")
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
        "enforce-whitelist": "false", "enforce-secure-profile": "false", "max-players": "64",
        "enable-query": "false", "query.port": str(port + 1), "broadcast-rcon-to-ops": "false",
    })
    update_restart_script(destination / "spigot.yml")

    start_script = destination / "start.sh"
    start_script.chmod(start_script.stat().st_mode | 0o111)
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


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", help="existing Paper server directory; it is never modified")
    parser.add_argument("destination", help="new bench directory (must not already exist)")
    parser.add_argument("--port", type=int, default=25566)
    parser.add_argument("--rcon-port", type=int, default=25576)
    parser.add_argument("--heap", help="replace -Xms/-Xmx in start.sh, e.g. 12G")
    parser.add_argument("--keep", action="append", help="plugin jar filename glob to keep (repeatable)")
    parser.add_argument("--add", dest="add_jars", action="append", default=[], help="plugin jar to copy (repeatable)")
    parser.add_argument("--via", action="store_true", help="download ViaVersion and ViaBackwards from Modrinth")
    parser.add_argument("--game-version", help="Minecraft version for --via (or version_history.json)")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        game_version = args.game_version or game_version_from_history(args.source) if args.via else None
        if args.via and not game_version:
            raise ValueError("--via needs --game-version or a readable version_history.json")
        destination = prepare_server(args.source, args.destination, port=args.port, rcon_port=args.rcon_port,
                                     heap=args.heap, keep=tuple(args.keep or DEFAULT_KEEP),
                                     add_jars=tuple(args.add_jars))
        if args.via:
            added = install_via(destination / "plugins", game_version)
            print("Installed: " + ", ".join(p.name for p in added))
        quoted = shlex.quote(str(destination))
        print(f"Bench copy ready: {destination}")
        print(f"Start: tmux new-session -d -s bench -c {quoted} ./bench-loop.sh")
        print(f"Run: python3 scripts/bench_fuzz.py --server-dir {quoted} --host 127.0.0.1 "
              "--targets direct,rtp --seeds 1 --episodes 24 --reps 3")
    except (OSError, ValueError, RuntimeError) as error:
        parser.error(str(error))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())