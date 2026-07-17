#!/usr/bin/env python3
"""Validate local Markdown links in the Rules Engine documentation."""

from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MARKDOWN_LINK = re.compile(r"\[[^\]]+\]\(([^)]+)\)")
EXTERNAL_SCHEMES = ("http://", "https://", "mailto:")


def markdown_files() -> list[Path]:
    return [ROOT / "README.md", ROOT / "RELEASING.md", *sorted((ROOT / "docs").rglob("*.md"))]


def local_target(raw_target: str) -> str | None:
    target = raw_target.strip().split("#", 1)[0]
    if not target or target.startswith(EXTERNAL_SCHEMES):
        return None
    return target


def main() -> int:
    errors: list[str] = []
    for source in markdown_files():
        content = source.read_text(encoding="utf-8")
        for match in MARKDOWN_LINK.finditer(content):
            target = local_target(match.group(1))
            if target is None:
                continue
            if target.startswith("/") or re.match(r"^[A-Za-z]:[/\\]", target):
                errors.append(f"{source.relative_to(ROOT)}: machine-local or absolute link: {target}")
                continue
            if not (source.parent / target).resolve().exists():
                errors.append(f"{source.relative_to(ROOT)}: missing local link: {target}")

    if errors:
        print("Documentation link validation failed:", file=sys.stderr)
        print("\n".join(f"- {error}" for error in errors), file=sys.stderr)
        return 1

    print("Documentation link validation passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
