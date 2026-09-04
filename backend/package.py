"""Builds the upload zip.

The one thing this exists to prevent: zipping the folder rather than its contents. The
platform reads paths relative to the app root, so an entry has to be `main.py`, never
`backend/main.py`. Get that wrong and a full upload looks fine while a later
changed-files-only upload quietly creates a second copy of the tree instead of
overwriting anything.

Run it with `uv run python package.py`.
"""

import sys
import tomllib
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent


def app_name() -> str:
    """The project name, so the zip cannot drift from what is being packaged.

    Read rather than hardcoded because this file already disagreed with pyproject.toml
    once, when the app was renamed and only some places followed.
    """
    with (HERE / "pyproject.toml").open("rb") as f:
        return tomllib.load(f)["project"]["name"]


OUT = HERE / "dist" / f"{app_name()}.zip"

# Directories never worth shipping. `.venv` in particular would blow past the 200MB cap.
SKIP_DIRS = {
    ".venv",
    "__pycache__",
    ".git",
    ".idea",
    ".pytest_cache",
    ".ruff_cache",
    "dist",
    ".appdata",
}
# `.zip` because a hand-made archive left in this directory otherwise ends up inside the
# one being built — a megabyte of the app packaged into itself, which is easy to miss in
# a size that still looks plausible.
SKIP_SUFFIXES = {".pyc", ".pyo", ".zip"}
SKIP_NAMES = {".env.local.sh", "uv.lock"}

MAX_BYTES = 200 * 1024 * 1024


def included() -> list[Path]:
    files = []
    for path in HERE.rglob("*"):
        if not path.is_file():
            continue
        relative = path.relative_to(HERE)
        if any(part in SKIP_DIRS for part in relative.parts):
            continue
        if path.suffix in SKIP_SUFFIXES or path.name in SKIP_NAMES:
            continue
        files.append(path)
    return sorted(files)


def main() -> int:
    if not (HERE / "web" / "index.html").exists():
        print(
            "web/index.html 이 없습니다. 프론트엔드를 먼저 빌드해 backend/web 에 넣으세요 "
            "(README 참고). 화면 없는 앱이 조용히 배포되는 것보다 여기서 멈추는 편이 낫습니다.",
            file=sys.stderr,
        )
        return 1

    # Fix what the frontend build cannot, then check. The platform runs the same check at
    # upload and rejects what it finds, so doing both here turns a failed upload into a
    # few lines of output.
    import check_paths
    import patch_bundle

    print("--- 번들 보정 ---")
    patch_bundle.main()
    print("--- 절대 경로 검사 ---")
    if check_paths.main() != 0:
        print(
            "위 경로들은 배포되면 앱 밖으로 나갑니다. 업로드 전에 고치세요.",
            file=sys.stderr,
        )
        return 1
    print("---")

    files = included()
    OUT.parent.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in files:
            # The arcname is relative to this directory, which is what puts `main.py` at
            # the root of the zip rather than `backend/main.py`.
            archive.write(path, path.relative_to(HERE).as_posix())

    size = OUT.stat().st_size
    print(f"{OUT}  ({len(files)} files, {size / 1024 / 1024:.1f}MB)")

    with zipfile.ZipFile(OUT) as archive:
        names = archive.namelist()
    print("zip 루트 확인:", "OK" if "pyproject.toml" in names and "main.py" in names else "실패")

    if size > MAX_BYTES:
        print(f"경고: 플랫폼 상한 200MB를 넘었습니다.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
