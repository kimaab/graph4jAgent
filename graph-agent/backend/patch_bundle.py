"""Points Next's own escape hatches at the app root instead of the platform root.

Next's built-in error screen falls back to `window.location.href="/"` for its "Back"
button when there is no history to go back to. Under `/apps/<app id>/` that leaves the
app entirely and lands on the platform root — and the upload check flags it.

It cannot be fixed from application code: the string lives in the framework chunk and
stays there even with a custom `app/global-error.tsx`, which only stops it being
*rendered*. So the built bundle is edited after the fact.

This is not a way to quiet the check. `"/"` becomes the app's own mount path, which is
what that button meant in the first place, so the button starts working rather than
stopping being reported. Where the app is served from the root there is nothing to
change and nothing is written.

Idempotent: after a run the pattern no longer matches. Run it after every frontend
build; `package.py` does.

    uv run python patch_bundle.py
"""

import re
import sys
from pathlib import Path

WEB = Path(__file__).resolve().parent / "web"

# The bare-root navigation Next emits. Matched narrowly — an assignment of exactly "/" —
# so nothing else in the bundle can be caught by it.
PATTERN = re.compile(r'(\blocation\.href\s*=\s*)"/"')


def mount_prefix() -> str | None:
    """The sub-path the build baked in, read off an asset URL, or None if unknown."""
    index = WEB / "index.html"
    if not index.exists():
        return None
    found = re.search(r'(?:src|href)="([^"]*)/_next/', index.read_text(encoding="utf-8"))
    return found.group(1) if found else None


def main() -> int:
    prefix = mount_prefix()
    if prefix is None:
        print("web/index.html 이 없습니다. 프론트엔드를 먼저 빌드하세요.", file=sys.stderr)
        return 1
    if not prefix:
        print("루트에 마운트된 빌드입니다 — 고칠 것이 없습니다.")
        return 0

    patched = 0
    for path in sorted(WEB.rglob("*.js")):
        text = path.read_text(encoding="utf-8")
        replaced, count = PATTERN.subn(rf'\1"{prefix}/"', text)
        if count:
            path.write_text(replaced, encoding="utf-8")
            patched += count
            print(f'  {path.relative_to(WEB.parent)}: location.href="/" -> "{prefix}/" ({count})')

    print(f'프레임워크의 루트 이동 {patched}건을 "{prefix}/" 로 맞췄습니다.')
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
