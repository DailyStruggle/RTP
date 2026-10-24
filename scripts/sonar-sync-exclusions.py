"""Sync the root Gradle Sonar exclusions to SonarCloud for connected IDE parity."""

import argparse
import base64
import json
import os
import re
import sys
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


def gradle_property(source, key):
    property_pattern = re.compile(
        rf"property\s+['\"]{re.escape(key)}['\"]\s*,\s*['\"]([^'\"]+)['\"]"
    )
    match = property_pattern.search(source)
    if match is None:
        raise ValueError(f"Missing Gradle Sonar property: {key}")
    return match.group(1)


def exclusion_settings(source):
    block_pattern = re.compile(
        r"(?ms)^\s*def\s+ignoredRuleExceptions\s*=\s*\[(.*?)^\s*\]"
    )
    block = block_pattern.search(source)
    if block is None:
        raise ValueError("Could not locate ignoredRuleExceptions in build.gradle")

    entry_pattern = re.compile(
        r"(?m)^\s*(\w+)\s*:\s*\[\s*rule:\s*'([^']+)'"
        r"(?:\s*,\s*resource:\s*'([^']+)')?\s*\]\s*,?\s*$"
    )
    entries = entry_pattern.findall(block.group(1))
    if not entries:
        raise ValueError("No Sonar exclusions found in ignoredRuleExceptions")

    project_key = gradle_property(source, "sonar.projectKey")
    settings = []
    for name, rule_key, resource_key in entries:
        settings.append({
            "component": project_key,
            "key": f"sonar.issue.ignore.multicriteria.{name}.ruleKey",
            "value": rule_key,
        })
        settings.append({
            "component": project_key,
            "key": f"sonar.issue.ignore.multicriteria.{name}.resourceKey",
            "value": resource_key or "**/*",
        })
    settings.append({
        "component": project_key,
        "key": "sonar.issue.ignore.multicriteria",
        "value": ",".join(name for name, _, _ in entries),
    })
    return gradle_property(source, "sonar.host.url").rstrip("/"), settings


def post_setting(url, setting, token):
    credentials = base64.b64encode(f"{token}:".encode("utf-8")).decode("ascii")
    request = Request(
        url,
        data=urlencode(setting).encode("utf-8"),
        headers={
            "Authorization": f"Basic {credentials}",
            "Content-Type": "application/x-www-form-urlencoded",
        },
        method="POST",
    )
    with urlopen(request, timeout=30) as response:
        response.read()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--gradle-file",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "build.gradle",
        help="root build.gradle (defaults to the repository root)",
    )
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument(
        "--apply",
        action="store_true",
        help="write settings to SonarCloud; the default only prints the payload",
    )
    mode.add_argument(
        "--dry-run",
        action="store_true",
        help="print the payload without making changes (the default)",
    )
    args = parser.parse_args()

    try:
        source = args.gradle_file.read_text(encoding="utf-8")
        host, settings = exclusion_settings(source)
        endpoint = f"{host}/api/settings/set"
        if args.dry_run or not args.apply:
            payload = [{"url": endpoint, **setting} for setting in settings]
            print(json.dumps(payload, indent=2))
            return 0

        token = os.environ.get("SONAR_TOKEN")
        if not token:
            raise ValueError("SONAR_TOKEN is required when using --apply")
        for setting in settings:
            post_setting(endpoint, setting, token)
        project_key = gradle_property(source, "sonar.projectKey")
        print(f"Updated {len(settings)} SonarCloud settings for {project_key}.")
        return 0
    except (OSError, ValueError, HTTPError, URLError, TimeoutError) as error:
        print(f"Sonar exclusion sync failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())