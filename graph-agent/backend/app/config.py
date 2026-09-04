"""Everything the platform injects, read once and validated here.

The app inherits none of the platform's own environment: only the variables the
Appstore guide lists, the locale ones, and whatever `[tool.appstore] env` declares.
Reading anything else would raise KeyError at import and fail the boot, so every
lookup below is either a documented variable or has a fallback for local runs.
"""

import os
from pathlib import Path

# The sub-path the platform mounts this app under, e.g. "/apps/agent-studio".
# Empty when the app is served at the root, which is how it runs locally.
#
# It is FastAPI's `root_path` and nothing else. It never gets concatenated into a URL
# the browser sees: those are all relative, so the sub-path resolves itself.
ROOT_PATH = os.environ.get("ROOT_PATH", "").rstrip("/")

# The only writable directory. The app's own source tree is read-only, so temp files
# for uploads go here too — tempfile would otherwise pick a path this user cannot write.
APP_DATA_DIR = Path(os.environ.get("APP_DATA_DIR", ".appdata"))

PORT = int(os.environ.get("PORT", "8080"))

# Declared in [tool.appstore] env, so it arrives once someone fills it in on the deploy
# screen. Absent until then, which is a startup failure worth naming clearly.
DB_URL = os.environ.get("DB_URL", "")

# Injected because [tool.appstore] llm = true. The gateway holds the key, so the SDK
# is handed a placeholder — it insists on a non-empty one.
LLM_BASE_URL = os.environ.get("LLM_BASE_URL", "")
LLM_MODEL = os.environ.get("LLM_MODEL", "")
LLM_API_KEY = "not-needed"

# Where other apps live, for an agent that wants to call one.
APPSTORE_BASE_URL = os.environ.get("APPSTORE_BASE_URL", "")

# Uploads are spooled to disk; this bounds what the request may carry.
MAX_UPLOAD_BYTES = int(os.environ.get("UPLOAD_MAX_FILE_SIZE", str(300 * 1024 * 1024)))

# The exported static frontend, served at the app root. Next.js baked the sub-path into
# its asset URLs at build time, so this directory is only correct for the app id it was
# built for — see backend/README.md.
WEB_DIR = Path(__file__).resolve().parent.parent / "web"

# Uploads land here before pypdf reads them, then are deleted.
UPLOAD_SPOOL_DIR = APP_DATA_DIR / "uploads"


def describe() -> str:
    """One startup line saying what this process resolved. Failures show it in the log."""
    return (
        f"ROOT_PATH={ROOT_PATH or '(root)'} "
        f"APP_DATA_DIR={APP_DATA_DIR} "
        f"DB={'set' if DB_URL else 'MISSING'} "
        f"LLM={LLM_BASE_URL or 'MISSING'} model={LLM_MODEL or 'MISSING'} "
        f"web={'present' if (WEB_DIR / 'index.html').exists() else 'absent'}"
    )
