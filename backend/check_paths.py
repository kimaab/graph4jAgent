"""Finds absolute URLs in the bundle that would leave this app when it is mounted.

The platform runs this check at upload and lists what it finds. Running it here means a
rebuild can be judged before the zip goes anywhere, and the one thing it is looking for
is the thing that breaks a deployed app silently: a browser-facing URL starting with `/`
goes to the platform root, not to `/apps/<app id>/`, so the app never sees the request.

Absolute URLs under the app's own mount are fine — that is what `BASE_PATH` bakes in —
so anything starting with the mount prefix is skipped.

    uv run python check_paths.py [/apps/<app id>]

Exits non-zero when something would leave the app.
"""

import re
import sys
from pathlib import Path

WEB = Path(__file__).resolve().parent / "web"

# What the browser is told to fetch or follow. `action` covers <form action>.
ATTRIBUTES = re.compile(r'\b(?:href|src|action)="(/[^"]*)"')

# Text files worth scanning. Fonts and images cannot carry a URL of their own.
SUFFIXES = {".html", ".js", ".css", ".txt", ".json"}

# `//example.com` is protocol-relative, an external host rather than an app path.
EXTERNAL = re.compile(r"^//")


def mount_prefix() -> str:
    if len(sys.argv) > 1:
        return sys.argv[1].rstrip("/")
    # Read it back off the bundle, so the check uses whatever the build actually baked in.
    index = WEB / "index.html"
    if index.exists():
        found = re.search(r'(?:src|href)="([^"]*)/_next/', index.read_text(encoding="utf-8"))
        if found:
            return found.group(1)
    return ""


def main() -> int:
    if not WEB.exists():
        print("web/ 이 없습니다. 프론트엔드를 먼저 빌드하세요.", file=sys.stderr)
        return 1

    prefix = mount_prefix()
    print(f"마운트 경로: {prefix or '(루트)'}\n")

    findings = 0
    for path in sorted(WEB.rglob("*")):
        if not path.is_file() or path.suffix.lower() not in SUFFIXES:
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue

        for line_number, line in enumerate(text.splitlines(), start=1):
            for url in ATTRIBUTES.findall(line):
                if EXTERNAL.match(url):
                    continue
                if prefix and (url == prefix or url.startswith(prefix + "/")):
                    continue
                findings += 1
                print(f"{path.relative_to(WEB.parent)}:{line_number}  {url}")

    print()
    if findings:
        print(f"앱을 벗어나는 절대 경로 {findings}건 — 상대 경로나 next/link 를 쓰세요.")
        return 1
    print("앱을 벗어나는 절대 경로 없음.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
