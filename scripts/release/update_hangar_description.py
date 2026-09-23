#!/usr/bin/env python3
"""
Updates the Hangar project main page (description) using docs/FRONT_PAGE_LITE_MODRINTH.md.

Hangar (https://hangar.papermc.io/leaf26/LeafRTP) hosts the Lite assembly variant
only (ADR-024). The main page is markdown, so the same source we feed to Modrinth
is reused here. Pro is never published to nor advertised on Hangar.

Usage:
    python scripts/release/update_hangar_description.py [--project LeafRTP] [--dry-run]
Environment:
    HANGAR_TOKEN: Hangar API key ("token" identifier) with the edit_page permission.

Flow (per the Hangar REST API):
    1. POST /api/v1/authenticate?apiKey=<HANGAR_TOKEN> -> JWT.
    2. PATCH /api/v1/pages/editmain/<project> with {"content": <markdown>} and the
       JWT in the Authorization header.
"""

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

DEFAULT_PROJECT = "LeafRTP"
DEFAULT_SOURCE_FILE = os.path.join("docs", "FRONT_PAGE_LITE_MODRINTH.md")
API_BASE = "https://hangar.papermc.io/api/v1"
USER_AGENT = "DailyStruggle/RTP-Release-Pipeline (github.com/dailystruggle/RTP)"


def read_description(file_path: str) -> str:
    if not os.path.isfile(file_path):
        raise FileNotFoundError(f"Source description file not found: {file_path}")

    with open(file_path, "r", encoding="utf-8") as f:
        content = f.read()

    # Strip top comment metadata if present (<!-- ... -->)
    content = re.sub(r"^<!--[\s\S]*?-->\s*", "", content)
    return content.strip()


def authenticate(api_key: str) -> str:
    url = f"{API_BASE}/authenticate?apiKey={urllib.parse.quote(api_key, safe='')}"
    req = urllib.request.Request(
        url,
        data=b"",
        headers={"User-Agent": USER_AGENT},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8", errors="replace")
        print(f"::error::Hangar authentication failed: HTTP {e.code} - {err_body}", file=sys.stderr)
        sys.exit(1)
    except urllib.error.URLError as e:
        print(f"::error::Network error authenticating with Hangar: {e.reason}", file=sys.stderr)
        sys.exit(1)

    jwt = data.get("token")
    if not jwt:
        print(f"::error::Hangar authentication response contained no token: {data}", file=sys.stderr)
        sys.exit(1)
    return jwt


def update_hangar(project: str, body: str, jwt: str, dry_run: bool = False) -> None:
    url = f"{API_BASE}/pages/editmain/{urllib.parse.quote(project, safe='')}"
    payload = json.dumps({"content": body}).encode("utf-8")

    if dry_run:
        print(f"[DRY-RUN] Would PATCH {url}")
        print(f"[DRY-RUN] Payload length: {len(body)} chars, {len(payload)} bytes")
        print("[DRY-RUN] Headers: Authorization: ***, Content-Type: application/json")
        return

    req = urllib.request.Request(
        url,
        data=payload,
        headers={
            "Authorization": jwt,
            "Content-Type": "application/json",
            "User-Agent": USER_AGENT,
        },
        method="PATCH",
    )

    try:
        with urllib.request.urlopen(req) as resp:
            print(f"Hangar project '{project}' main page updated successfully (HTTP {resp.status}).")
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8", errors="replace")
        print(f"::error::Failed to update Hangar project '{project}' main page: HTTP {e.code} - {err_body}", file=sys.stderr)
        sys.exit(1)
    except urllib.error.URLError as e:
        print(f"::error::Network error updating Hangar project '{project}' main page: {e.reason}", file=sys.stderr)
        sys.exit(1)


def main() -> None:
    parser = argparse.ArgumentParser(description="Update Hangar main page from docs/FRONT_PAGE_LITE_MODRINTH.md")
    parser.add_argument("--project", default=DEFAULT_PROJECT, help="Hangar project slug or id (default: LeafRTP)")
    parser.add_argument("--source-file", default=DEFAULT_SOURCE_FILE, help="Path to markdown source file")
    parser.add_argument("--dry-run", action="store_true", help="Perform a dry run without sending the request")
    args = parser.parse_args()

    token = os.environ.get("HANGAR_TOKEN")
    if not token and not args.dry_run:
        print("::warning::HANGAR_TOKEN environment variable not set; skipping Hangar description update.")
        sys.exit(0)

    try:
        body = read_description(args.source_file)
    except Exception as e:
        print(f"::error::{e}", file=sys.stderr)
        sys.exit(1)

    if args.dry_run:
        update_hangar(args.project, body, "", dry_run=True)
        return

    jwt = authenticate(token or "")
    update_hangar(args.project, body, jwt)


if __name__ == "__main__":
    main()
