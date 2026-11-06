"""MkDocs build hook: single-source the version stamp on the website.

Docs carry the literal token `@version@` (see the version-stamp comment in
`gradle.properties`). For the released jar that token is expanded at package
time by the rtp-plugin `copyDocs` task (Gradle `ReplaceTokens`). The MkDocs
site, however, publishes the raw Markdown, so without this hook every page's
header would show the literal `@version@` instead of the real version.

Only a subset of pages carry the `**Current Plugin Version:** @version@`
header line, so on the website the version appeared on some pages and not
others, which read inconsistently. Rather than hand-stamp every published
page (and keep them all in sync on a version bump), this hook shows the
version in exactly one place site-wide - the footer - and drops the scattered
per-page stamp lines from the website render. The per-page stamps stay in the
Markdown for the jar-packaged docs, which are expanded by Gradle, not by this
hook.

The authoritative `version` is read from `gradle.properties` (the same single
source Gradle uses), so the website stays in sync with the jar and neither has
to be hand-edited on a version bump.
"""

import gzip
import re
import shutil
from pathlib import Path

_TOKEN = "@version@"
_FALLBACK = "unknown"

# Repo location for rewriting links that escape docs_dir (see
# _rewrite_external_links). The published site serves pages under docs/ plus
# projected sibling product docs (/mc/<product>/...) and API specs (/mc/anvil-api/...).
_SITE_URL_BASE = "https://docs.leafcraft.net/mc"
_REPO_URL_BASE = "https://github.com/DailyStruggle/RTP"
_REPO_BRANCH = "V3"
_MD_LINK_RE = re.compile(r"\]\(\s*([^)\s]+?)((?:\s+\"[^\"]*\")?)\s*\)")
_SKIP_LINK_PREFIXES = (
    "http://",
    "https://",
    "mailto:",
    "tel:",
    "ftp:",
    "//",
)

_SIBLING_PRODUCT_SPECS = (
    ("leafanvil", "resource/LeafAnvil", "LeafAnvil (.lca Storage)"),
    ("leaftreegen", "resource/LeafTreeGen", "LeafTreeGen"),
    ("leafsleuth", "resource/LeafSleuth", "LeafSleuth"),
    ("leafconfig", "resource/LeafConfig", "LeafConfig"),
    ("leafsleep", "resource/LeafSleep", "LeafSleep (OnePlayerSleep)"),
    ("leaffuzz", "resource/LeafFuzz", "LeafFuzz"),
)
_PROJECTED_PREFIXES = tuple(f"{slug}/" for slug, _, _ in _SIBLING_PRODUCT_SPECS) + (
    "anvil-api/",
    "leafrtp/",
    "sdk/",
    "platforms/",
    "corpus-atlas/",
)
_STAGED_ORIGIN_DIRS: dict[str, Path] = {}
_STAGED_WEB_URLS: dict[str, str] = {}
_STAGED_ADR_URLS: dict[str, str] = {}
_STAGED_EXTRA_FILES: list[Path] = []

_SUITE_LANDING_MD = """# LeafCraft Minecraft Suite (`docs.leafcraft.net/mc/`)

Multi-loader Minecraft server plugins, mods, storage codecs, and diagnostic tools for **Paper**, **Folia**, **Spigot**, **Fabric**, **NeoForge**, and **Velocity**.

Every tool in the suite is engineered around three non-negotiable rules: **zero synchronous main-thread chunk loads**, **one multi-loader jar per product**, and **empirical benchmark & built-in self-test verification**.

---

## Products & Documentation

| Product | Category | Platforms | Documentation & Guides |
| :--- | :--- | :--- | :--- |
| [**LeafRTP**](leafrtp/index.md) | Zero-Wait Random Teleport (`/rtp` & `/wild`) + Visual Web Map Editor | Paper, Spigot, Folia, Fabric, NeoForge, Velocity | [Overview](leafrtp/index.md) · [Quick Start](admin/QUICK_START.md) · [Web Editor](editor/index.html) · [Commands](admin/COMMANDS.md) · [Configuration](admin/configuration/CONFIGURATION.md) |
| [**LeafAnvil (`.lca`)**](leafanvil/index.md) | `50%` Smaller Worlds, `15.5x` Faster Saves & 4-Tier Self-Healing Storage | Paper, Spigot, Folia, Fabric, NeoForge | [Overview & Benchmarks](leafanvil/index.md) · [Commands](leafanvil/admin/COMMANDS.md) · [Configuration](leafanvil/admin/CONFIGURATION.md) · [BIT Self-Healing](leafanvil/dev/BIT_CODES.md) |
| [**`.lca` Spec & Codec (`anvil-api`)**](anvil-api/index.md) | Open-Source MIT `.lca` Binary Spec (`LCA-ICD-1.0`) & Java Transcoder | Standalone Java 17+ / Maven / Gradle | [Codec Guide (`anvil-api`)](anvil-api/index.md) · [`.lca` Binary Spec (`ICD.md`)](anvil-api/ICD.md) |
| [**LeafTreeGen**](leaftreegen/index.md) | Procedural Custom Biome Trees & Native Jigsaw Worldgen Datapack Baker | Paper, Folia, Fabric, NeoForge | [Overview](leaftreegen/index.md) · [Usage & Commands](leaftreegen/usage.md) · [28 Biome Species](leaftreegen/biome-trees.md) |
| [**LeafSleuth**](leafsleuth/index.md) | Contraption-Safe Lag Governor (`-68.6%`), VPN Alt Detector & Anti-Dupe Forensics | Paper, Folia, Spigot | [Overview](leafsleuth/index.md) · [Statistical Calibration & Confusion Matrices](leafsleuth/dev/STATISTICAL_CALIBRATION.md) |
| [**LeafConfig**](leafconfig/index.md) | Universal Server Config Web Workspace, Comment Restorer & JAR Introspector | Paper, Folia, Spigot | [Overview](leafconfig/index.md) · [Input & Query Calibration](leafconfig/dev/NOVICE_INPUT_AND_QUERY_CALIBRATION.md) |
| [**LeafSleep (`OnePlayerSleep`)**](leafsleep/index.md) | Multi-Loader & Sharded Sleep Voting (`/sleep` & `/ops`) with Population Curves | Paper, Spigot, Folia, Fabric, NeoForge, Velocity | [Overview](leafsleep/index.md) · [Server Designs](leafsleep/admin/SERVER_DESIGNS.md) · [Commands](leafsleep/admin/COMMANDS.md) · [Configuration](leafsleep/admin/CONFIGURATION.md) |
| [**LeafFuzz**](leaffuzz/index.md) | 1-Command Plugin Security, Permission-Leak Scanner & Memory-Leak Detector | Paper, Spigot, Folia, Fabric, NeoForge, CLI | [Overview](leaffuzz/index.md) · [Commands](leaffuzz/admin/COMMANDS.md) · [Configuration](leaffuzz/admin/CONFIGURATION.md) · [BIT Codes](leaffuzz/dev/BIT_CODES.md) |

---

## Interactive Web Tools

- [**LeafRTP Visual Region & Map Editor (`/rtp editor`)**](editor/index.html) — Draw circular, square, or polygon teleport borders and preview spatial distributions in the browser.
- [**LeafConfig Universal Server Workspace (`/leafconfig editor`)**](leafconfig/editor/index.html) — Browse restored YAML comments, enum dropdowns, and spatial world gizmos across installed plugins.

---

## Architecture & Shared SDK

All products share the zero-dependency LeafCraft multi-platform SDK (`server-api`, `commands-api`, `yaml-api`, `units-api`, `bit-api`, `config-bit-api`, `menu-api`, `effects-api`, `predict-api`, `metrics-api`, `database-api`, `anvil-api`, `maps-api`, `schematics-api`, `tags-api`, `bstats-api`):

- [**LeafRTP Admin & Operations Guide**](FOR_SERVER_ADMINS.md)
- [**Addon & API Developer Guide**](FOR_ADDON_DEVELOPERS.md)
- [**Architecture Decision Records (`ADR-001`..`ADR-130`)**](adr/README.md)
"""

# Directory holding the project-wide ADRs, relative to this file / docs_dir.
_ADR_DIR = Path(__file__).parent / "docs" / "adr"
_H1_RE = re.compile(r"^#\s+(.+?)\s*$", re.MULTILINE)
_ADR_NUM_RE = re.compile(r"ADR-(\d+)")

# `.junie/AGENTS.md` is the canonical agent guide, but it lives outside the
# published `docs/` tree, so every relative link to it (`../../.junie/AGENTS.md`,
# `../.junie/AGENTS.md`, etc.) resolves outside `docs_dir` and MkDocs both warns
# and renders a dead link. Rewrite those relative links to the canonical GitHub
# URL (preserving any `#fragment`) so the published site links out to the file
# on GitHub instead of 404-ing. The repo-relative source links stay untouched.
_AGENTS_URL = "https://github.com/DailyStruggle/RTP/blob/V3/.junie/AGENTS.md"
_AGENTS_LINK_RE = re.compile(
    r"\]\((?:\.\./)*\.junie/AGENTS\.md(#[^)\s]*)?\)",
)

# Matches the per-page stamp line (e.g. `**Current Plugin Version:** `@version@``)
# plus any single trailing blank line, so removing it leaves no stray gap.
_STAMP_RE = re.compile(
    r"^\*\*Current Plugin Version:\*\*.*$\n?(?:\n)?",
    re.MULTILINE,
)


# Internal engineering trees stay published (deep links from GitHub, issues and
# the jar docs keep working) but are kept out of search results: every page
# under these prefixes gets a robots noindex meta tag and is dropped from
# sitemap.xml, so crawlers summarise the operator docs, not the internals.
# `follow` keeps link discovery intact. Do not also Disallow these paths in a
# robots.txt: a crawler that cannot fetch a page never sees its noindex tag.
_NOINDEX_PREFIXES = ("dev/", "adr/", "architecture/", "sdk/", "platforms/", "corpus-atlas/")
_NOINDEX_META = '<meta name="robots" content="noindex, follow">'
_SITEMAP_URL_RE = re.compile(r"\s*<url>\s*<loc>([^<]*)</loc>.*?</url>", re.DOTALL)
_ADR_CODE_RE = re.compile(r"((?:[a-z0-9-]+-)?ADR-\d+)", re.IGNORECASE)
_ADR_SLUG_RE = re.compile(r"ADR-\d+-(.+)$", re.IGNORECASE)


def _doc_uri_to_site_url(doc_uri: str) -> str:
    clean = doc_uri.replace("\\", "/").lstrip("/").rstrip("/")
    if clean in ("admin", "admin/proxies"):
        return f"{_SITE_URL_BASE}/{clean}/INDEX/"
    if clean.endswith(".md"):
        parts = [p for p in clean[:-3].split("/") if p]
        if parts and parts[-1] in ("index", "README"):
            parts.pop()
        return f"{_SITE_URL_BASE}/" + ("/".join(parts) + "/" if parts else "")
    return f"{_SITE_URL_BASE}/{clean}/" if clean else f"{_SITE_URL_BASE}/"


def _register_web_url(repo_rel: str, site_url: str) -> None:
    norm_rel = repo_rel.replace("\\", "/").lstrip("/")
    _STAGED_WEB_URLS[norm_rel] = site_url
    if norm_rel.endswith(".md"):
        fname = Path(norm_rel).name
        _STAGED_ADR_URLS.setdefault(fname.lower(), site_url)
        m_code = _ADR_CODE_RE.search(fname)
        if m_code:
            _STAGED_ADR_URLS.setdefault(m_code.group(1).lower(), site_url)
        m_slug = _ADR_SLUG_RE.search(fname)
        if m_slug:
            _STAGED_ADR_URLS.setdefault(m_slug.group(1).lower(), site_url)
            m_num = _ADR_NUM_RE.search(fname)
            if m_num:
                _STAGED_ADR_URLS.setdefault(f"adr-{m_num.group(1)}-{m_slug.group(1).lower()}", site_url)


def _stage_doc_file(
    src_file: Path,
    target_file: Path,
    docs_dir: Path,
    repo_root: Path,
    text: str | None = None,
    track_extra: bool = False,
) -> None:
    target_file.parent.mkdir(parents=True, exist_ok=True)
    if text is not None:
        target_file.write_text(text, encoding="utf-8")
    else:
        shutil.copy2(src_file, target_file)
    if track_extra:
        _STAGED_EXTRA_FILES.append(target_file)
    doc_uri = target_file.resolve().relative_to(docs_dir.resolve()).as_posix()
    _STAGED_ORIGIN_DIRS[doc_uri] = src_file.resolve().parent
    try:
        repo_rel = src_file.resolve().relative_to(repo_root.resolve()).as_posix()
        _register_web_url(repo_rel, _doc_uri_to_site_url(doc_uri))
    except ValueError:
        pass


def _is_noindex(src_uri: str) -> bool:
    if src_uri.startswith(_NOINDEX_PREFIXES):
        return True
    for prefix in _PROJECTED_PREFIXES:
        if src_uri.startswith(prefix):
            sub = src_uri[len(prefix):]
            if sub.startswith(_NOINDEX_PREFIXES):
                return True
    return False


def _read_version() -> str:
    repo_root = Path(__file__).resolve().parents[2]
    props = repo_root / "gradle.properties"
    if not props.is_file():
        props = Path(__file__).parent / "gradle.properties"
    try:
        for line in props.read_text(encoding="utf-8").splitlines():
            stripped = line.strip()
            if stripped.startswith("version="):
                return stripped.split("=", 1)[1].strip() or _FALLBACK
    except OSError:
        pass
    return _FALLBACK


_VERSION = _read_version()


def _adr_title(path: Path) -> str:
    """First H1 of an ADR file, trimmed for a compact sidebar label."""
    try:
        text = path.read_text(encoding="utf-8")
    except OSError:
        return path.stem
    m = _H1_RE.search(text)
    title = m.group(1).strip() if m else path.stem
    # ADR bodies open with a long descriptive H1; keep the sidebar readable by
    # dropping any trailing parenthetical and prefixing the ADR number.
    title = title.split(" (", 1)[0].strip()
    num = _ADR_NUM_RE.search(path.name)
    if num and not title.upper().startswith("ADR-"):
        title = f"ADR-{num.group(1)}: {title}"
    return title


def _adr_sort_key(path: Path):
    m = _ADR_NUM_RE.search(path.name)
    # README first, TEMPLATE last, real ADRs by number in between.
    if path.name.lower() == "readme.md":
        return (0, 0, path.name)
    if "TEMPLATE" in path.name.upper():
        return (2, 0, path.name)
    return (1, int(m.group(1)) if m else 1_000_000, path.name)


def _build_adr_nav():
    """An 'ADRs' nav section listing every project-wide ADR page.

    Without this, the ADR pages are published but absent from the nav, so
    Material renders them with no sidebar - a reader has to hit Back to reach
    the next ADR. Adding them to the nav gives every ADR page the shared
    left-hand sidebar so they can be browsed in sequence.
    """
    if not _ADR_DIR.is_dir():
        return None
    files = sorted(_ADR_DIR.glob("*.md"), key=_adr_sort_key)
    items = []
    for f in files:
        rel = f"adr/{f.name}"
        if f.name.lower() == "readme.md":
            items.append({"Overview": rel})
        else:
            items.append({_adr_title(f): rel})
    return {"Architecture decisions": items} if items else None


def _stage_sibling_docs(repo_root: Path, docs_dir: Path) -> list[dict]:
    """Projects sibling `resource/<Product>/docs/**` and `api/anvil-api/docs/**` into `docs_dir` during build."""
    nav_sections: list[dict] = []
    _STAGED_ORIGIN_DIRS.clear()
    _STAGED_WEB_URLS.clear()
    _STAGED_ADR_URLS.clear()
    _STAGED_EXTRA_FILES.clear()
    if not (repo_root / "resource").is_dir():
        return nav_sections

    for p in sorted(docs_dir.rglob("*.md")):
        rel_doc = p.relative_to(docs_dir).as_posix()
        if (
            rel_doc in ("site/README.md", "MAP.md")
            or rel_doc.startswith("publishing/")
            or rel_doc.startswith(_PROJECTED_PREFIXES)
        ):
            continue
        try:
            repo_rel = p.resolve().relative_to(repo_root.resolve()).as_posix()
            if rel_doc == "index.md":
                _register_web_url(repo_rel, f"{_SITE_URL_BASE}/leafrtp/")
            else:
                _register_web_url(repo_rel, _doc_uri_to_site_url(rel_doc))
        except ValueError:
            pass
    _register_web_url("resource/LeafRTP/README.md", f"{_SITE_URL_BASE}/leafrtp/")
    _register_web_url("resource/LeafRTP/docs/publishing/FRONT_PAGE.md", f"{_SITE_URL_BASE}/leafrtp/")
    _register_web_url("docs/FOR_CONTRIBUTORS.md", f"{_SITE_URL_BASE}/FOR_CONTRIBUTORS/")
    _register_web_url("docs/FOR_PLUGIN_AUTHORS.md", f"{_SITE_URL_BASE}/FOR_ADDON_DEVELOPERS/")

    root_adr_dir = repo_root / "docs" / "adr"
    if root_adr_dir.is_dir():
        for f in sorted(root_adr_dir.glob("*.md")):
            target_f = docs_dir / "adr" / f.name
            if target_f.exists():
                _register_web_url(f"docs/adr/{f.name}", _doc_uri_to_site_url(f"adr/{f.name}"))
            else:
                _stage_doc_file(f, target_f, docs_dir, repo_root, track_extra=True)

    root_dev_dir = repo_root / "docs" / "dev"
    if root_dev_dir.is_dir():
        for f in sorted(root_dev_dir.glob("*.md")):
            target_f = docs_dir / "dev" / f.name
            if target_f.exists():
                _register_web_url(f"docs/dev/{f.name}", _doc_uri_to_site_url(f"dev/{f.name}"))
            else:
                _stage_doc_file(f, target_f, docs_dir, repo_root, track_extra=True)

    rtp_index = docs_dir / "index.md"
    rtp_backup = docs_dir / ".index.leafrtp.bak"
    leafrtp_dir = docs_dir / "leafrtp"
    if rtp_index.is_file():
        if not rtp_backup.is_file():
            shutil.copy2(rtp_index, rtp_backup)
        if leafrtp_dir.exists():
            shutil.rmtree(leafrtp_dir)
        leafrtp_dir.mkdir(parents=True, exist_ok=True)
        rtp_text = rtp_backup.read_text(encoding="utf-8")
        rtp_text = re.sub(
            r"\]\((?!https?://|#|mailto:|/)([^)]+)\)",
            lambda m: f"](../{m.group(1)})",
            rtp_text,
        )
        (leafrtp_dir / "index.md").write_text(rtp_text, encoding="utf-8")
        rtp_index.write_text(_SUITE_LANDING_MD, encoding="utf-8")

    rtp_res_dir = repo_root / "resource" / "LeafRTP"
    if (rtp_res_dir / "CHANGELOG.md").is_file():
        _stage_doc_file(rtp_res_dir / "CHANGELOG.md", leafrtp_dir / "CHANGELOG.md", docs_dir, repo_root)
    for sub_req in ("rtp-api/REQUIREMENTS.md", "rtp-core/REQUIREMENTS.md"):
        src_req = rtp_res_dir / sub_req
        if src_req.is_file():
            _stage_doc_file(src_req, leafrtp_dir / sub_req, docs_dir, repo_root)
    if (rtp_res_dir / "addons").is_dir():
        for addon_md in sorted((rtp_res_dir / "addons").rglob("*.md")):
            rel_addon = addon_md.relative_to(rtp_res_dir / "addons")
            _stage_doc_file(addon_md, leafrtp_dir / "addons" / rel_addon, docs_dir, repo_root)
    if (repo_root / "helpers" / "StressTestRTP").is_dir():
        for h_md in sorted((repo_root / "helpers" / "StressTestRTP").rglob("*.md")):
            rel_h = h_md.relative_to(repo_root / "helpers" / "StressTestRTP")
            _stage_doc_file(h_md, leafrtp_dir / "helpers" / "StressTestRTP" / rel_h, docs_dir, repo_root)

    for slug, rel_dir, label in _SIBLING_PRODUCT_SPECS:
        res_dir = repo_root / rel_dir
        if not res_dir.is_dir():
            continue
        dest_root = docs_dir / slug
        if dest_root.exists():
            shutil.rmtree(dest_root)
        dest_root.mkdir(parents=True, exist_ok=True)
        _register_web_url(f"{rel_dir}/docs/publishing/FRONT_PAGE.md", f"{_SITE_URL_BASE}/{slug}/")
        _register_web_url(f"{rel_dir}/LICENSE", f"{_SITE_URL_BASE}/{slug}/#licensing")

        readme_src = res_dir / "README.md"
        if readme_src.is_file():
            text = readme_src.read_text(encoding="utf-8")
            text = text.replace("](docs/", "](")
            _stage_doc_file(readme_src, dest_root / "index.md", docs_dir, repo_root, text=text)

        changelog_src = res_dir / "CHANGELOG.md"
        if changelog_src.is_file():
            _stage_doc_file(changelog_src, dest_root / "CHANGELOG.md", docs_dir, repo_root)

        src_docs = res_dir / "docs"
        if src_docs.is_dir():
            for p in sorted(src_docs.rglob("*")):
                rel_p = p.relative_to(src_docs)
                if rel_p.parts and rel_p.parts[0] == "publishing":
                    continue
                target_p = dest_root / rel_p
                if p.is_dir():
                    target_p.mkdir(parents=True, exist_ok=True)
                elif p.is_file():
                    if p.suffix.lower() == ".md":
                        _stage_doc_file(p, target_p, docs_dir, repo_root)
                    else:
                        target_p.parent.mkdir(parents=True, exist_ok=True)
                        shutil.copy2(p, target_p)

        items: list[dict] = []
        if (dest_root / "index.md").is_file():
            items.append({"Overview": f"{slug}/index.md"})
        for admin_md in sorted((dest_root / "admin").glob("*.md")) if (dest_root / "admin").is_dir() else ():
            items.append({admin_md.stem.replace("_", " ").title(): f"{slug}/admin/{admin_md.name}"})
        for top_md in sorted(dest_root.glob("*.md")):
            if top_md.name in ("index.md", "CHANGELOG.md", "modrinth.md"):
                continue
            items.append({top_md.stem.replace("-", " ").replace("_", " ").title(): f"{slug}/{top_md.name}"})
        for dev_md in sorted((dest_root / "dev").glob("*.md")) if (dest_root / "dev").is_dir() else ():
            if dev_md.name == "BIT_CODES.md" or "CALIBRATION" in dev_md.name:
                items.append({dev_md.stem.replace("_", " ").title(): f"{slug}/dev/{dev_md.name}"})
        if (dest_root / "CHANGELOG.md").is_file():
            items.append({"Changelog": f"{slug}/CHANGELOG.md"})
        if items:
            nav_sections.append({label: items})

    anvil_api_dir = repo_root / "api" / "anvil-api"
    if anvil_api_dir.is_dir():
        dest_anvil = docs_dir / "anvil-api"
        if dest_anvil.exists():
            shutil.rmtree(dest_anvil)
        dest_anvil.mkdir(parents=True, exist_ok=True)
        if (anvil_api_dir / "README.md").is_file():
            text = (anvil_api_dir / "README.md").read_text(encoding="utf-8")
            text = text.replace("](docs/", "](")
            _stage_doc_file(anvil_api_dir / "README.md", dest_anvil / "index.md", docs_dir, repo_root, text=text)
        if (anvil_api_dir / "docs").is_dir():
            for p in sorted((anvil_api_dir / "docs").rglob("*")):
                rel_p = p.relative_to(anvil_api_dir / "docs")
                target_p = dest_anvil / rel_p
                if p.is_dir():
                    target_p.mkdir(parents=True, exist_ok=True)
                elif p.is_file():
                    if p.suffix.lower() == ".md":
                        _stage_doc_file(p, target_p, docs_dir, repo_root)
                    else:
                        target_p.parent.mkdir(parents=True, exist_ok=True)
                        shutil.copy2(p, target_p)
        api_items: list[dict] = []
        if (dest_anvil / "index.md").is_file():
            api_items.append({"Codec Overview (MIT)": "anvil-api/index.md"})
        if (dest_anvil / "ICD.md").is_file():
            api_items.append({".lca Binary Spec (LCA-ICD-1.0)": "anvil-api/ICD.md"})
        if api_items:
            nav_sections.append({".lca Spec & Codec": api_items})

    api_root = repo_root / "api"
    if api_root.is_dir():
        dest_sdk = docs_dir / "sdk"
        if dest_sdk.exists():
            shutil.rmtree(dest_sdk)
        if (api_root / "README.md").is_file():
            _stage_doc_file(api_root / "README.md", dest_sdk / "index.md", docs_dir, repo_root)
        for mod_dir in sorted(api_root.iterdir()):
            if not mod_dir.is_dir() or mod_dir.name == "anvil-api":
                continue
            mod_dest = dest_sdk / mod_dir.name
            if (mod_dir / "README.md").is_file():
                text = (mod_dir / "README.md").read_text(encoding="utf-8").replace("](docs/", "](")
                _stage_doc_file(mod_dir / "README.md", mod_dest / "index.md", docs_dir, repo_root, text=text)
            if (mod_dir / "docs").is_dir():
                for p in sorted((mod_dir / "docs").rglob("*.md")):
                    rel_p = p.relative_to(mod_dir / "docs")
                    if rel_p.as_posix().lower() == "readme.md":
                        if not (mod_dest / "index.md").exists():
                            _stage_doc_file(p, mod_dest / "index.md", docs_dir, repo_root)
                        else:
                            try:
                                repo_rel = p.resolve().relative_to(repo_root.resolve()).as_posix()
                                _register_web_url(repo_rel, _doc_uri_to_site_url(f"sdk/{mod_dir.name}/index.md"))
                            except ValueError:
                                pass
                        continue
                    _stage_doc_file(p, mod_dest / rel_p, docs_dir, repo_root)

    platforms_root = repo_root / "platforms"
    if platforms_root.is_dir():
        dest_plats = docs_dir / "platforms"
        if dest_plats.exists():
            shutil.rmtree(dest_plats)
        for plat_dir in sorted(platforms_root.iterdir()):
            if not plat_dir.is_dir():
                continue
            plat_dest = dest_plats / plat_dir.name
            if (plat_dir / "README.md").is_file():
                text = (plat_dir / "README.md").read_text(encoding="utf-8").replace("](docs/", "](")
                _stage_doc_file(plat_dir / "README.md", plat_dest / "index.md", docs_dir, repo_root, text=text)
            if (plat_dir / "REQUIREMENTS.md").is_file():
                _stage_doc_file(plat_dir / "REQUIREMENTS.md", plat_dest / "REQUIREMENTS.md", docs_dir, repo_root)
            if (plat_dir / "docs").is_dir():
                for p in sorted((plat_dir / "docs").rglob("*.md")):
                    rel_p = p.relative_to(plat_dir / "docs")
                    _stage_doc_file(p, plat_dest / rel_p, docs_dir, repo_root)
        _register_web_url("platforms/proxy/docs/adr", f"{_SITE_URL_BASE}/platforms/proxy/adr/")

    corpus_docs = repo_root / "corpus-atlas" / "docs"
    if corpus_docs.is_dir():
        dest_ca = docs_dir / "corpus-atlas"
        if dest_ca.exists():
            shutil.rmtree(dest_ca)
        for ca_md in sorted(corpus_docs.rglob("*.md")):
            rel_ca = ca_md.relative_to(corpus_docs)
            _stage_doc_file(ca_md, dest_ca / rel_ca, docs_dir, repo_root)

    return nav_sections


def _cleanup_staged_sibling_docs(docs_dir: Path) -> None:
    rtp_backup = docs_dir / ".index.leafrtp.bak"
    rtp_index = docs_dir / "index.md"
    if rtp_backup.is_file():
        shutil.move(str(rtp_backup), str(rtp_index))
    for extra_f in _STAGED_EXTRA_FILES:
        try:
            extra_f.unlink(missing_ok=True)
        except OSError:
            pass
    _STAGED_EXTRA_FILES.clear()
    for prefix in _PROJECTED_PREFIXES:
        staged = docs_dir / prefix.rstrip("/")
        if staged.is_dir():
            shutil.rmtree(staged, ignore_errors=True)


def on_config(config):
    """Surface the version once, site-wide, in the footer, and stage multi-product docs."""
    existing = config.get("copyright") or ""
    stamp = f"LeafCraft Suite v{_VERSION}"
    config["copyright"] = f"{stamp} &middot; {existing}" if existing else stamp

    plugin_dir = Path(__file__).resolve().parent
    docs_dir = Path(config["docs_dir"]).resolve()
    repo_root = plugin_dir.parents[1] if (plugin_dir.parents[1] / "settings.gradle").is_file() else plugin_dir

    sibling_navs = _stage_sibling_docs(repo_root, docs_dir)
    if isinstance(config.get("nav"), list):
        rtp_sub_items: list[dict] = [{"Overview": "leafrtp/index.md"}]
        for entry in config["nav"]:
            if isinstance(entry, dict) and "Home" not in entry:
                rtp_sub_items.append(entry)
        new_nav: list[dict] = [
            {"Suite Home": "index.md"},
            {"LeafRTP": rtp_sub_items},
            *sibling_navs,
        ]
        adr_nav = _build_adr_nav()
        if adr_nav is not None:
            new_nav.append(adr_nav)
        config["nav"] = new_nav

    try:
        import sys
        bot_dir = repo_root / "discord-support-bot"
        if bot_dir.is_dir():
            if str(bot_dir) not in sys.path:
                sys.path.insert(0, str(bot_dir))
            from web_relay_and_triage import export_web_corpus_json
            export_web_corpus_json(out_path=plugin_dir / "docs" / "assets" / "support-corpus.json")
    except Exception as exc:
        import sys
        sys.stderr.write(f"[mkdocs_hooks] warning: could not pre-export support-corpus.json: {exc}\n")

    return config


def _resolve_target_path(
    path_part: str,
    origin_page_dir: Path,
    staged_page_dir: Path,
    repo_root: Path,
) -> Path:
    alt_part = (
        path_part.replace("plugins/LeafRTP/", "resource/LeafRTP/")
        .replace("plugins/Leaf", "resource/Leaf")
        .replace("platforms/rtp-fabric", "platforms/fabric")
        .replace("platforms/rtp-neoforge", "platforms/neoforge")
        .replace("platforms/rtp-proxy", "platforms/proxy")
        .replace("platforms/rtp-bukkit", "platforms/bukkit")
        .replace("platforms/rtp-paper", "platforms/paper")
        .replace("platforms/rtp-folia", "platforms/folia")
    )
    candidates = [
        (origin_page_dir / path_part).resolve(),
        (origin_page_dir / alt_part).resolve(),
    ]
    if staged_page_dir != origin_page_dir:
        candidates.extend([
            (staged_page_dir / path_part).resolve(),
            (staged_page_dir / alt_part).resolve(),
        ])
    candidates.extend([
        (origin_page_dir / ".." / ".." / alt_part).resolve(),
        (origin_page_dir.parent / alt_part).resolve(),
        (repo_root / alt_part.lstrip("./")).resolve(),
        (repo_root / "resource" / "LeafRTP" / alt_part.lstrip("./")).resolve(),
        (repo_root / "api" / alt_part.lstrip("./")).resolve(),
    ])
    for cand in candidates:
        if cand.exists():
            return cand
    return candidates[0]


_HISTORICAL_DOC_FALLBACKS = {
    "adr-035": "/adr/leafrtp-ADR-021-interactive-menus-book-first/",
    "adr-044-written-book": "/adr/leafrtp-ADR-021-interactive-menus-book-first/",
    "adr-047-declarative": "/adr/leafrtp-ADR-030-declarative-chart-composition-bridge/",
    "adr-049-shared-platform": "/adr/leafrtp-ADR-032-network-mode-platform-neutral-lift/",
    "adr-050-menus": "/adr/leafrtp-ADR-021-interactive-menus-book-first/",
    "adr-064": "/sdk/yaml-api/adr/yaml-api-ADR-003-config-comment-format-summary-line-as-menu-hover/",
    "adr-086-external-web-map": "/adr/leafrtp-ADR-067-external-web-map-integration/",
    "adr-087-reactive-governor": "/adr/leafrtp-ADR-068-adaptive-compute-governor/",
    "adr-104": "/leafconfig/dev/NOVICE_INPUT_AND_QUERY_CALIBRATION/",
    "adr-109": "/adr/leafrtp-ADR-084-measured-cost-probe-first-gate/",
    "adr-114": "/adr/leafrtp-ADR-086-cost-governed-on-demand-chunk-reads/",
    "adr-122": "/adr/leafrtp-ADR-071-test-driver-and-synthetic-mca-architecture/",
    "adr-123": "/adr/leafrtp-ADR-092-folia-tick-region-allocation/",
    "adr-124": "/adr/leafrtp-ADR-093-consumer-attribution-for-background-refill/",
    "adr-129": "/leafsleuth/",
    "multi_platform_plan.md": "/dev/ROADMAP/",
    "neoforge_notes.md": "/platforms/neoforge/adr/rtp-neoforge-ADR-002-neoforge-platform-in-scope/",
    "adr-nnn": "/adr/ADR-TEMPLATE/",
}


def _lookup_fallback_doc_url(path_part: str) -> str | None:
    lower_part = path_part.lower()
    fname = Path(path_part).name.lower()
    for key, rel_url in _HISTORICAL_DOC_FALLBACKS.items():
        if key in fname or key in lower_part:
            return f"{_SITE_URL_BASE}{rel_url}"
    if "dev/scratch/" in lower_part or fname.startswith(("checklist-", "proposal-", "config_comment_preservation")):
        return f"{_SITE_URL_BASE}/adr/"
    if fname in _STAGED_ADR_URLS:
        return _STAGED_ADR_URLS[fname]
    m_code = _ADR_CODE_RE.search(fname)
    if m_code and m_code.group(1).lower() in _STAGED_ADR_URLS:
        return _STAGED_ADR_URLS[m_code.group(1).lower()]
    m_slug = _ADR_SLUG_RE.search(fname)
    if m_slug and m_slug.group(1).lower() in _STAGED_ADR_URLS:
        return _STAGED_ADR_URLS[m_slug.group(1).lower()]
    if "leafsleuth" in fname:
        return f"{_SITE_URL_BASE}/leafsleuth/"
    if "tether-addon" in fname:
        return f"{_SITE_URL_BASE}/admin/ACTIONS/"
    return None


def _rewrite_external_links(markdown: str, page, config) -> str:
    """Rewrite relative links that escape docs_dir or cross staged boundaries to published site or GitHub URLs."""
    docs_dir = Path(config["docs_dir"]).resolve()
    plugin_dir = Path(__file__).resolve().parent
    repo_root = plugin_dir.parents[1] if (plugin_dir.parents[1] / "settings.gradle").is_file() else docs_dir.parent
    src_uri = page.file.src_uri.replace("\\", "/")
    staged_page_dir = (docs_dir / src_uri).parent.resolve()
    origin_page_dir = _STAGED_ORIGIN_DIRS.get(src_uri, staged_page_dir)

    def repl(match: "re.Match") -> str:
        target = match.group(1)
        title = match.group(2) or ""
        lowered = target.lower()
        gh_md_prefix = "https://github.com/dailystruggle/rtp/"
        if lowered.startswith(gh_md_prefix) and (".md" in lowered):
            m_gh = re.match(
                r"^https://github\.com/DailyStruggle/RTP/(?:blob|tree)/[^/]+/(.+)$",
                target,
                flags=re.IGNORECASE,
            )
            if m_gh:
                target = m_gh.group(1)
                lowered = target.lower()
        if lowered.startswith(_SKIP_LINK_PREFIXES) or target.startswith("/"):
            return match.group(0)
        if target.startswith("#"):
            norm_in_page = re.sub(r"-{2,}", "-", target)
            return f"]({norm_in_page}{title})" if norm_in_page != target else match.group(0)

        path_part, sep, frag = target.partition("#")
        raw_frag = f"#{frag}" if sep else ""
        norm_frag = f"#{re.sub(r'-{2,}', '-', frag)}" if sep else ""
        if not path_part:
            return match.group(0)

        resolved = _resolve_target_path(path_part, origin_page_dir, staged_page_dir, repo_root)
        staged_direct = (staged_page_dir / path_part).resolve()

        try:
            rel = resolved.relative_to(repo_root).as_posix()
        except ValueError:
            rel = ""

        if rel and rel in _STAGED_WEB_URLS:
            if (
                staged_page_dir == origin_page_dir
                and staged_direct == resolved
                and resolved.is_file()
                and docs_dir in resolved.parents
            ):
                if norm_frag != raw_frag:
                    return f"]({path_part}{norm_frag}{title})"
                return match.group(0)
            return f"]({_STAGED_WEB_URLS[rel]}{norm_frag}{title})"

        try:
            doc_rel = resolved.relative_to(docs_dir).as_posix()
            if staged_direct == resolved and resolved.exists() and "../docs/" not in path_part:
                if norm_frag != raw_frag and path_part.endswith(".md"):
                    return f"]({path_part}{norm_frag}{title})"
                return match.group(0)
            if resolved.exists():
                return f"]({_doc_uri_to_site_url(doc_rel)}{norm_frag}{title})"
        except ValueError:
            pass

        if path_part.endswith(".md") or "/adr" in path_part:
            fb_url = _lookup_fallback_doc_url(path_part)
            if fb_url:
                return f"]({fb_url}{norm_frag}{title})"

        if not rel:
            return match.group(0)

        if rel.startswith("api/anvil-api/") and rel.endswith(".md"):
            sub = rel[len("api/anvil-api/"):]
            if sub.startswith("docs/"):
                sub = sub[len("docs/"):]
            parts = [p for p in sub[:-3].split("/") if p]
            if parts and parts[-1] in ("index", "README"):
                parts.pop()
            url = f"{_SITE_URL_BASE}/anvil-api/" + ("/".join(parts) + "/" if parts else "")
            return f"]({url}{norm_frag}{title})"

        for slug, res_rel, _ in _SIBLING_PRODUCT_SPECS:
            if rel == res_rel or rel.startswith(res_rel + "/"):
                if rel.endswith(".md"):
                    sub = rel[len(res_rel):].lstrip("/")
                    if sub.startswith("docs/"):
                        sub = sub[len("docs/"):]
                    if sub.startswith("publishing/"):
                        url = f"{_SITE_URL_BASE}/{slug}/"
                    else:
                        parts = [p for p in sub[:-3].split("/") if p]
                        if parts and parts[-1] in ("index", "README"):
                            parts.pop()
                        url = f"{_SITE_URL_BASE}/{slug}/" + ("/".join(parts) + "/" if parts else "")
                    return f"]({url}{norm_frag}{title})"
                return f"]({_SITE_URL_BASE}/{slug}/{norm_frag}{title})"

        if rel == "scripts/bench_lca_multiplatform.py":
            return f"]({_SITE_URL_BASE}/leafanvil/#2-continuous-multi-platform-abc-benchmark-driver-scriptsbench_lca_multiplatformpy{title})"

        if rel.startswith("corpus-atlas/"):
            return f"]({_SITE_URL_BASE}/adr/ADR-115-package-dag-disentanglement-and-decision-preservation-hypergraph/{norm_frag}{title})"

        if rel.startswith("discord-support-bot/"):
            return f"]({_SITE_URL_BASE}/{norm_frag}{title})"

        if not resolved.exists():
            fb_url = _lookup_fallback_doc_url(path_part)
            if fb_url:
                return f"]({fb_url}{norm_frag}{title})"
            if path_part.endswith(".md"):
                return f"]({_SITE_URL_BASE}/adr/{norm_frag}{title})" if "adr" in path_part.lower() else f"]({_SITE_URL_BASE}/{norm_frag}{title})"
            return match.group(0)

        kind = "tree" if path_part.endswith("/") or resolved.is_dir() else "blob"
        url = f"{_REPO_URL_BASE}/{kind}/{_REPO_BRANCH}/{rel}"
        return f"]({url}{raw_frag}{title})"

    return _MD_LINK_RE.sub(repl, markdown)


def on_page_markdown(markdown, page, config, files):
    """Drop the scattered per-page version stamp; expand any stray token."""
    markdown = _STAMP_RE.sub("", markdown)
    if _TOKEN in markdown:
        markdown = markdown.replace(_TOKEN, _VERSION)
    markdown = _AGENTS_LINK_RE.sub(
        lambda m: f"]({_AGENTS_URL}{m.group(1) or ''})", markdown
    )
    markdown = _rewrite_external_links(markdown, page, config)
    return markdown


def on_post_page(output, page, config):
    """Tag internal engineering pages noindex (see _NOINDEX_PREFIXES)."""
    if not _is_noindex(page.file.src_uri):
        return output
    head_end = output.find("</head>")
    if head_end < 0:
        raise ValueError(f"no </head> in {page.file.src_uri}; cannot add noindex")
    return output[:head_end] + _NOINDEX_META + "\n" + output[head_end:]


def on_post_build(config):
    """Drop noindex pages from sitemap.xml and its .gz twin, export support-corpus.json, and clean staged docs."""
    site_dir = Path(config["site_dir"])
    docs_dir = Path(config["docs_dir"]).resolve()
    _cleanup_staged_sibling_docs(docs_dir)
    try:
        import sys
        plugin_dir = Path(__file__).resolve().parent
        repo_root = plugin_dir.parents[1] if (plugin_dir.parents[1] / "discord-support-bot").is_dir() else plugin_dir
        bot_dir = repo_root / "discord-support-bot"
        if bot_dir.is_dir():
            if str(bot_dir) not in sys.path:
                sys.path.insert(0, str(bot_dir))
            from web_relay_and_triage import (
                export_web_corpus_json,
                sync_generated_site_with_relational_corpus,
            )
            bundle = export_web_corpus_json(out_path=site_dir / "assets" / "support-corpus.json")
            sync_generated_site_with_relational_corpus(bundle=bundle, site_dir=site_dir, repo_root=repo_root)
    except Exception as exc:
        import sys
        sys.stderr.write(f"[mkdocs_hooks] warning: could not export site support-corpus.json: {exc}\n")

    sitemap = site_dir / "sitemap.xml"
    if not sitemap.is_file():
        return
    base = (config.get("site_url") or "").rstrip("/") + "/"
    noindex_locs = tuple(base + prefix for prefix in _NOINDEX_PREFIXES)

    def keep(match: "re.Match") -> str:
        return "" if match.group(1).strip().startswith(noindex_locs) else match.group(0)

    text = _SITEMAP_URL_RE.sub(keep, sitemap.read_text(encoding="utf-8"))
    sitemap.write_text(text, encoding="utf-8")
    gz = site_dir / "sitemap.xml.gz"
    if gz.is_file():
        gz.write_bytes(gzip.compress(text.encode("utf-8"), mtime=0))
