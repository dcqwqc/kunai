#!/usr/bin/env python3
"""Manage opt-in direct media URLs for the Kunai Personal Sources provider."""
import argparse
import json
import os
import re
import sys
import tempfile
from pathlib import Path
from urllib.parse import urlsplit

CONFIG = Path(os.getenv("KUNAI_PERSONAL_SOURCES_FILE", "~/.config/kunai/personal-sources.json")).expanduser()
ID_RE = re.compile(r"^(?:tmdb:)?([1-9][0-9]*)$")


def key_from_args(args):
    m = ID_RE.fullmatch(args.id)
    if not m:
        raise ValueError("ID must be a numeric TMDB ID or tmdb:<number>")
    key = f"tmdb:{m[1]}"
    if args.kind == "series":
        if args.season is None or args.episode is None or args.season < 0 or args.episode < 1:
            raise ValueError("Series source requires --season N and --episode N")
        return f"{key}:s{args.season}e{args.episode}"
    if args.season is not None or args.episode is not None:
        raise ValueError("--season/--episode requires --kind series")
    return key


def validate_url(value):
    if not isinstance(value, str) or len(value) > 16384:
        raise ValueError("URL invalid or too long")
    parts = urlsplit(value)
    if parts.scheme not in ("http", "https") or not parts.hostname or parts.username or parts.password:
        raise ValueError("Use a playable HTTP(S) video/playlist URL with no embedded credentials")
    if any(ch in value for ch in "\r\n"):
        raise ValueError("URL contains unexpected characters")
    return value


def load():
    try:
        parsed = json.loads(CONFIG.read_text())
    except FileNotFoundError:
        return {"version": 1, "titles": {}}
    if parsed.get("version") != 1 or not isinstance(parsed.get("titles"), dict):
        raise ValueError("Unknown personal-sources.json format; original file left untouched")
    return parsed


def save(data):
    CONFIG.parent.mkdir(parents=True, exist_ok=True)
    fd, path = tempfile.mkstemp(prefix=".personal-sources.", suffix=".tmp", dir=CONFIG.parent)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w") as file:
            json.dump(data, file, ensure_ascii=False, indent=2)
            file.write("\n")
            file.flush()
            os.fsync(file.fileno())
        os.replace(path, CONFIG)
    finally:
        if os.path.exists(path):
            os.unlink(path)


def main():
    parser = argparse.ArgumentParser(description="Add or remove playable URLs attached to Kunai TMDB titles")
    sub = parser.add_subparsers(dest="command", required=True)
    add = sub.add_parser("add", help="Associate a direct media URL with a TMDB movie or episode")
    add.add_argument("id")
    add.add_argument("url")
    add.add_argument("--title", default="")
    add.add_argument("--kind", choices=("movie", "series"), default="movie")
    add.add_argument("--season", type=int)
    add.add_argument("--episode", type=int)
    add.add_argument("--label", default="Personal source")
    add.add_argument("--quality", default="User source")
    remove = sub.add_parser("remove", help="Remove all personal sources for a title/episode")
    remove.add_argument("id")
    remove.add_argument("--kind", choices=("movie", "series"), default="movie")
    remove.add_argument("--season", type=int)
    remove.add_argument("--episode", type=int)
    sub.add_parser("list", help="List attached titles and source labels, not URLs")
    args = parser.parse_args()
    try:
        data = load()
        if args.command == "list":
            for key, item in data["titles"].items():
                print(f"{key} — {item.get('title') or '(title not set)'}: " +
                      ", ".join(src.get("label", "source") for src in item.get("sources", [])))
            if not data["titles"]:
                print("No personal sources configured.")
            return 0
        key = key_from_args(args)
        if args.command == "remove":
            if key not in data["titles"]:
                print(f"No configured source for {key}")
                return 0
            del data["titles"][key]
            save(data)
            print(f"Removed personal sources for {key}")
            return 0
        url = validate_url(args.url)
        entry = data["titles"].get(key)
        if entry and entry.get("kind") != args.kind:
            raise ValueError("Configured media kind differs; remove the old entry first")
        if not entry:
            entry = {"title": args.title or key, "kind": args.kind, "sources": []}
        if args.title:
            entry["title"] = args.title
        existing = {s["url"] for s in entry["sources"]}
        if url not in existing:
            entry["sources"].append({"url": url, "label": args.label[:80], "quality": args.quality[:48]})
        data["titles"][key] = entry
        save(data)
        print(f"Saved {len(entry['sources'])} personal source(s) for {key}. The URL is stored privately, not displayed.")
        print(f"Select provider 'personal-sources' in Kunai or launch with --open 'kunai://play?cat={key}&kind={args.kind}&src=personal-sources'")
        return 0
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
