#!/usr/bin/env python3
"""
Updates the Modrinth project description (body) using docs/FRONT_PAGE_LITE_MODRINTH.md.
Usage:
    python scripts/release/update_modrinth_description.py [--project-id rtp-lite] [--dry-run]
Environment:
    MODRINTH_TOKEN: Personal access token with PROJECT_WRITE permission.
"""

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request

DEFAULT_PROJECT_ID = "rtp-lite"
DEFAULT_SOURCE_FILE = os.path.join("docs", "FRONT_PAGE_LITE_MODRINTH.md")


def read_description(file_path: str) -> str:
    if not os.path.isfile(file_path):
        raise FileNotFoundError(f"Source description file not found: {file_path}")

    with open(file_path, "r", encoding="utf-8") as f:
        content = f.read()

    # Strip top comment metadata if present (<!-- ... -->)
    content = re.sub(r"^<!--[\s\S]*?-->\s*", "", content)
    return content.strip()


def update_modrinth(project_id: str, body: str, token: str, dry_run: bool = False) -> None:
    url = f"https://api.modrinth.com/v2/project/{project_id}"
    payload = json.dumps({"body": body}).encode("utf-8")

    if dry_run:
        print(f"[DRY-RUN] Would PATCH {url}")
        print(f"[DRY-RUN] Payload length: {len(body)} chars, {len(payload)} bytes")
        print(f"[DRY-RUN] Headers: Authorization: Bearer ***, Content-Type: application/json")
        return

    req = urllib.request.Request(
        url,
        data=payload,
        headers={
            "Authorization": token,
            "Content-Type": "application/json",
            "User-Agent": "DailyStruggle/RTP-Release-Pipeline (github.com/dailystruggle/RTP)",
        },
        method="PATCH",
    )

    try:
        with urllib.request.urlopen(req) as resp:
            status = resp.status
            print(f"Modrinth project '{project_id}' description updated successfully (HTTP {status}).")
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8", errors="replace")
        print(f"::error::Failed to update Modrinth project '{project_id}' description: HTTP {e.code} - {err_body}", file=sys.stderr)
        sys.exit(1)
    except urllib.error.URLError as e:
        print(f"::error::Network error updating Modrinth project '{project_id}' description: {e.reason}", file=sys.stderr)
        sys.exit(1)


def main() -> None:
    parser = argparse.ArgumentParser(description="Update Modrinth project description from docs/FRONT_PAGE_LITE_MODRINTH.md")
    parser.add_argument("--project-id", default=DEFAULT_PROJECT_ID, help="Modrinth project ID or slug (default: rtp-lite)")
    parser.add_argument("--source-file", default=DEFAULT_SOURCE_FILE, help="Path to markdown source file")
    parser.add_argument("--dry-run", action="store_true", help="Perform a dry run without sending the request")
    args = parser.parse_args()

    token = os.environ.get("MODRINTH_TOKEN")
    if not token and not args.dry_run:
        print("::warning::MODRINTH_TOKEN environment variable not set; skipping Modrinth description update.")
        sys.exit(0)

    try:
        body = read_description(args.source_file)
    except Exception as e:
        print(f"::error::{e}", file=sys.stderr)
        sys.exit(1)

    update_modrinth(args.project_id, body, token or "", args.dry_run)


if __name__ == "__main__":
    main()
