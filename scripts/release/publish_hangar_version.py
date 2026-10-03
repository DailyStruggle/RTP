#!/usr/bin/env python3
"""
Publishes a version of the LeafRTP Lite jar to Hangar (https://hangar.papermc.io/leaf26/LeafRTP).

Hangar hosts the Lite assembly variant only (ADR-024); Pro is never published to
nor advertised on Hangar. mc-publish (Kir-Antipov) supports only CurseForge /
Modrinth / GitHub, so Hangar is handled here directly against the Hangar REST API
using the Python standard library (no third-party actions).

Flow (per the Hangar REST API v1):
    1. POST /api/v1/authenticate?apiKey=<HANGAR_TOKEN>            -> JWT.
    2. GET  /api/v1/platforms/PAPER/versions                     -> valid versions.
    3. POST /api/v1/projects/<project>/upload  (multipart/form-data)
         - part "versionUpload": application/json VersionUpload body.
         - part "files":         the jar binary.

Usage:
    python scripts/release/publish_hangar_version.py \
        --project LeafRTP --file path/to/LeafRTP-3.3.0.jar --version 3.3.0 \
        [--changelog-file release-notes.md] [--version-type release] [--dry-run]
Environment:
    HANGAR_TOKEN: Hangar API key with the create_version permission.
"""

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

API_BASE = "https://hangar.papermc.io/api/v1"
USER_AGENT = "DailyStruggle/RTP-Release-Pipeline (github.com/dailystruggle/RTP)"
DEFAULT_PROJECT = "LeafRTP"
PLATFORM = "PAPER"

# Minecraft versions we claim support for on the PAPER platform. Filtered against
# the versions Hangar actually knows at publish time so an unknown entry never
# fails the whole upload.
CANDIDATE_VERSIONS = [
    "1.20", "1.20.1", "1.20.2", "1.20.3", "1.20.4", "1.20.5", "1.20.6",
    "1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4", "1.21.5", "1.21.6",
    "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11",
    "26.1", "26.1.1", "26.1.2", "26.2", "26.3",
]


def authenticate(api_key: str) -> str:
    url = f"{API_BASE}/authenticate?apiKey={urllib.parse.quote(api_key, safe='')}"
    req = urllib.request.Request(url, data=b"", headers={"User-Agent": USER_AGENT}, method="POST")
    try:
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        print(f"::error::Hangar authentication failed: HTTP {e.code} - {body}", file=sys.stderr)
        sys.exit(1)
    except urllib.error.URLError as e:
        print(f"::error::Network error authenticating with Hangar: {e.reason}", file=sys.stderr)
        sys.exit(1)
    jwt = data.get("token")
    if not jwt:
        print(f"::error::Hangar authentication response contained no token: {data}", file=sys.stderr)
        sys.exit(1)
    return jwt


def valid_platform_versions() -> list:
    url = f"{API_BASE}/platforms/{PLATFORM}/versions"
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT}, method="GET")
    known = set()
    try:
        with urllib.request.urlopen(req) as resp:
            for entry in json.loads(resp.read().decode("utf-8")):
                if entry.get("version"):
                    known.add(entry["version"])
                for sub in entry.get("subVersions", []) or []:
                    known.add(sub)
    except (urllib.error.HTTPError, urllib.error.URLError) as e:
        # Non-fatal: fall back to the candidate list and let the upload validate.
        print(f"::warning::Could not fetch Hangar {PLATFORM} versions ({e}); using candidate list as-is.")
        return list(CANDIDATE_VERSIONS)
    filtered = [v for v in CANDIDATE_VERSIONS if v in known]
    dropped = [v for v in CANDIDATE_VERSIONS if v not in known]
    if dropped:
        print(f"::warning::Dropping MC versions Hangar does not recognize for {PLATFORM}: {', '.join(dropped)}")
    if not filtered:
        print(f"::error::None of the candidate MC versions are valid Hangar {PLATFORM} versions.", file=sys.stderr)
        sys.exit(1)
    return filtered


def build_multipart(version_upload: dict, file_path: str) -> tuple:
    boundary = f"----hangar{uuid.uuid4().hex}"
    crlf = b"\r\n"
    parts = []

    # Part 1: versionUpload JSON.
    parts.append(("--" + boundary).encode("utf-8"))
    parts.append(b'Content-Disposition: form-data; name="versionUpload"')
    parts.append(b"Content-Type: application/json")
    parts.append(b"")
    parts.append(json.dumps(version_upload).encode("utf-8"))

    # Part 2: the jar binary.
    with open(file_path, "rb") as f:
        file_bytes = f.read()
    filename = os.path.basename(file_path)
    parts.append(("--" + boundary).encode("utf-8"))
    parts.append(
        f'Content-Disposition: form-data; name="files"; filename="{filename}"'.encode("utf-8")
    )
    parts.append(b"Content-Type: application/java-archive")
    parts.append(b"")
    parts.append(file_bytes)

    parts.append(("--" + boundary + "--").encode("utf-8"))
    parts.append(b"")

    body = crlf.join(parts)
    content_type = f"multipart/form-data; boundary={boundary}"
    return body, content_type


def main() -> None:
    parser = argparse.ArgumentParser(description="Publish the LeafRTP Lite jar to Hangar.")
    parser.add_argument("--project", default=DEFAULT_PROJECT, help="Hangar project slug or id (default: LeafRTP)")
    parser.add_argument("--file", required=True, help="Path to the Lite jar to upload")
    parser.add_argument("--version", required=True, help="Version string (e.g. 3.3.0)")
    parser.add_argument("--changelog-file", help="Path to a markdown file used as the version description")
    parser.add_argument("--version-type", default="release", help="release | beta | alpha (default: release)")
    parser.add_argument("--dry-run", action="store_true", help="Build the request but do not send it")
    args = parser.parse_args()

    if not os.path.isfile(args.file):
        print(f"::error::Lite jar not found: {args.file}", file=sys.stderr)
        sys.exit(1)

    description = ""
    if args.changelog_file and os.path.isfile(args.changelog_file):
        with open(args.changelog_file, "r", encoding="utf-8") as f:
            description = f.read().strip()

    # Hangar's default channels are "Release" (stable) and "Snapshot" (pre-release).
    channel = "Snapshot" if args.version_type.lower() in ("beta", "alpha") else "Release"

    token = os.environ.get("HANGAR_TOKEN")
    if not token and not args.dry_run:
        print("::warning::HANGAR_TOKEN environment variable not set; skipping Hangar publish.")
        sys.exit(0)

    versions = valid_platform_versions() if not args.dry_run else list(CANDIDATE_VERSIONS)

    version_upload = {
        "version": args.version,
        "channel": channel,
        "description": description,
        "files": [{"platforms": [PLATFORM]}],
        "platformDependencies": {PLATFORM: versions},
        "pluginDependencies": {},
    }

    body, content_type = build_multipart(version_upload, args.file)
    url = f"{API_BASE}/projects/{urllib.parse.quote(args.project, safe='')}/upload"

    if args.dry_run:
        print(f"[DRY-RUN] Would POST {url}")
        print(f"[DRY-RUN] channel={channel}, version={args.version}, platforms={PLATFORM}")
        print(f"[DRY-RUN] {len(versions)} MC versions, description {len(description)} chars, body {len(body)} bytes")
        return

    jwt = authenticate(token or "")
    req = urllib.request.Request(
        url,
        data=body,
        headers={
            "Authorization": jwt,
            "Content-Type": content_type,
            "User-Agent": USER_AGENT,
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req) as resp:
            print(f"Published LeafRTP {args.version} to Hangar '{args.project}' (HTTP {resp.status}).")
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8", errors="replace")
        print(f"::error::Failed to publish to Hangar '{args.project}': HTTP {e.code} - {err_body}", file=sys.stderr)
        sys.exit(1)
    except urllib.error.URLError as e:
        print(f"::error::Network error publishing to Hangar '{args.project}': {e.reason}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
