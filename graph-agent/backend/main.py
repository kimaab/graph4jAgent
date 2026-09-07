"""Agent Studio on the Agent Appstore platform.

One process serves both halves: the JSON API under `/api`, and the exported Next.js
bundle in `web/` for everything else.

Two things about paths, because getting them wrong is what breaks a deployed app while
every local run and every test still passes:

  * Route definitions here are absolute (`/api/agents`), which is correct — they are
    the server's own paths, and `root_path` is what makes FastAPI mount them under the
    platform's sub-path.
  * Anything the *browser* asks for must be relative. A leading `/` sends the request to
    the platform root instead of to this app, and the app never sees it — the page comes
    up bare with nothing in its log. The frontend bundle is built with `BASE_PATH` for
    exactly this reason (see README), and the one page this file serves itself carries a
    `<base href>` and relative links.
"""

import logging
import os
import re
import sys
from contextlib import asynccontextmanager
from pathlib import Path

import uvicorn
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, HTMLResponse

from app import config, db, errors, tools
from app.routers import agents, code, datasources, documents, graph, run
from app.routers import tools as tools_router


def _utf8_output() -> None:
    """Makes stdout and stderr carry non-ASCII without killing the process.

    The platform passes `LANG`/`LC_ALL` through only when the host has them, and a
    Windows console defaults to a legacy code page, so the encoding here is not
    something the app can assume. Logs are Korean and the specs they mention are worse,
    and a print that raises inside startup fails the boot with an encoding error instead
    of whatever it was about to report — which is how this was found.
    """
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            reconfigure(encoding="utf-8", errors="replace")


_utf8_output()

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)-5s %(name)s - %(message)s",
    stream=sys.stdout,
)
log = logging.getLogger("agent-studio")

# The placeholder segment the static export writes for `agents/[id]`. An agent id is a
# UUID assigned at runtime, so there is no set of them to prerender; one shell page is
# built and served for every id, and the page reads the real one off the URL.
SHELL_ID = "_id"


def bundle_base_path() -> str | None:
    """The sub-path Next.js baked into the exported bundle, or None if there is none.

    Read straight off the asset URLs in index.html, because that is the thing that has
    to match — not a build flag someone remembered to write down.
    """
    index = config.WEB_DIR / "index.html"
    if not index.exists():
        return None
    found = re.search(r'(?:src|href)="([^"]*)/_next/', index.read_text(encoding="utf-8"))
    return found.group(1) if found else ""


def check_bundle() -> None:
    """Warns when the built frontend does not match the sub-path this app is mounted at.

    Next.js writes the sub-path into every asset URL at build time, so a bundle built for
    one app id serves nothing under another: the browser asks the platform root for each
    script, gets 404, and shows a bare page — with no request reaching this app and
    nothing in its log to explain it. Renaming the app without rebuilding the frontend is
    the ordinary way to land there, which is why this is checked out loud at startup
    rather than left to be discovered in a browser.
    """
    baked = bundle_base_path()
    if baked is None:
        log.warning(
            "web/index.html is missing — the API will answer but there is no UI. "
            "Build the frontend into backend/web (see README)."
        )
        return

    if baked != config.ROOT_PATH:
        log.error(
            "the frontend bundle was built for %r but this app is mounted at %r. "
            "Every asset URL in the page points at the wrong prefix, so the UI will "
            "load blank. Rebuild it: BASE_PATH=%s NEXT_PUBLIC_API_BASE_URL=%s",
            baked or "/",
            config.ROOT_PATH or "/",
            config.ROOT_PATH or '""',
            config.ROOT_PATH or "/",
        )


@asynccontextmanager
async def lifespan(_: FastAPI):
    # Startup work is logged at both ends: a failure here shows the platform this log
    # next to the reason, and an app that prints nothing leaves nothing to go on.
    print(f"starting Agent Studio: {config.describe()}", flush=True)

    config.APP_DATA_DIR.mkdir(parents=True, exist_ok=True)

    print("checking code templates...", flush=True)
    tools.verify_templates()

    try:
        db.start()
    except Exception as error:
        # Said plainly before the traceback: on a failed boot the platform shows this
        # log next to the reason, and a reader should not have to find the one useful
        # line inside twenty frames of ASGI plumbing.
        print(f"STARTUP FAILED: {error}", flush=True)
        raise

    check_bundle()

    print(f"listening on port {config.PORT}", flush=True)
    yield
    db.stop()


app = FastAPI(
    title="Agent Studio",
    # The platform's sub-path. FastAPI uses it for /docs and for redirects; it never
    # gets concatenated into a URL the browser is handed.
    root_path=config.ROOT_PATH,
    lifespan=lifespan,
)

# The Next.js dev server calls this API straight from the browser during development.
# The code endpoints answer in headers rather than a JSON envelope, and a browser hides
# every response header that is not on the exposed list.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:3000"],
    allow_methods=["GET", "POST", "PUT", "DELETE", "OPTIONS"],
    allow_headers=["*"],
    expose_headers=[code.EDITED_HEADER, code.FILENAME_HEADER],
)


@app.middleware("http")
async def strip_root_path(request, call_next):
    """Drops the platform's sub-path from the request path when the proxy left it on.

    An ASGI proxy is expected to strip its mount prefix and pass it as `root_path`, and
    everything below — the routes, and the asset URLs baked into the bundle — is written
    for that. A proxy that forwards the whole path instead would miss every route here
    and answer 404 to each script the page asks for, with the page itself coming up bare.
    Normalising once, before routing, means both halves of the app agree about it.
    """
    prefix = config.ROOT_PATH
    if prefix:
        path = request.scope["path"]
        if path == prefix:
            request.scope["path"] = "/"
        elif path.startswith(prefix + "/"):
            request.scope["path"] = path[len(prefix) :]
    return await call_next(request)


errors.install(app)

# Registered before the static catch-all below, which would otherwise swallow them.
app.include_router(agents.router)
app.include_router(tools_router.router)
app.include_router(documents.router)
app.include_router(datasources.router)
app.include_router(code.router)
app.include_router(graph.router)
app.include_router(run.router)


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


# HEAD as well as GET: Next.js prefetches every `<Link>` with a HEAD request, and a
# GET-only route answers those 405. Nothing breaks visibly — navigation still works —
# so it shows up only as a page full of failed requests in the network panel.
@app.api_route("/{full_path:path}", methods=["GET", "HEAD"])
def web(full_path: str):
    """Serves the exported frontend.

    Assets resolve to themselves; a page URL resolves to the `index.html` in its
    directory, which is what `trailingSlash` in the Next config emits. `/agents/<uuid>`
    has no directory of its own, so it falls back to the shell page built for the
    placeholder segment.
    """
    target = _resolve(full_path)
    if target is not None:
        return FileResponse(target)

    not_found = config.WEB_DIR / "404.html"
    if not_found.exists():
        return FileResponse(not_found, status_code=404)
    return HTMLResponse(_placeholder(), status_code=404 if full_path else 200)


def _resolve(full_path: str) -> Path | None:
    """The file to answer this path with, or None when there is nothing to serve."""
    # Ordered most specific first. Everything derived from the path as asked is tried
    # before the shell fallback, which matches any `/agents/<anything>` and would
    # otherwise answer a payload request with the editor's HTML.
    candidates: list[str] = []
    for base in (full_path.strip("/"), _shell_path(full_path)):
        if not base:
            continue
        candidates.append(base)
        # The same name may be a flattened RSC payload; see _rsc_payload.
        nested = _rsc_payload(base)
        if nested:
            candidates.insert(len(candidates) - 1, nested)

    for candidate in candidates or [""]:
        base = _safe_join(candidate)
        if base is None:
            continue
        if base.is_file():
            return base
        page = base / "index.html"
        if page.is_file():
            return page
    return None


def _rsc_payload(candidate: str) -> str | None:
    """Rewrites Next's flattened RSC payload name onto the nested file the export wrote.

    The client router asks for `__next.agents.$d$id.__PAGE__.txt` while the export writes
    `__next.agents/$d$id/__PAGE__.txt`: dots in the URL are directories on disk, except
    the one inside `__next.agents` and the `.txt` itself. Next's own server does that
    mapping; a plain file server has to, and without it every navigation into an agent
    404s on its payload and falls back to a full page load.

    Returns None unless the shape matches, and the caller only serves what exists, so the
    worst this can do is leave the 404 exactly where it was.
    """
    head, _, last = candidate.rpartition("/")
    if not last.startswith("__next.") or not last.endswith(".txt"):
        return None

    parts = last[: -len(".txt")].split(".")
    if len(parts) < 3:
        return None

    # parts[0:2] are "__next" and the route group, which stay one directory name.
    nested = "/".join([".".join(parts[:2]), *parts[2:]]) + ".txt"
    return f"{head}/{nested}" if head else nested


def _shell_path(full_path: str) -> str | None:
    """Rewrites `agents/<id>[/...]` onto the one page the export built for that segment.

    Only the id segment is replaced; `agents/<id>/run` keeps its own page.
    """
    parts = [p for p in full_path.strip("/").split("/") if p]
    if len(parts) >= 2 and parts[0] == "agents" and parts[1] != SHELL_ID:
        return "/".join(["agents", SHELL_ID, *parts[2:]])
    return None


def _safe_join(relative: str) -> Path | None:
    """Keeps `../` in a request path from reaching outside the bundle."""
    target = (config.WEB_DIR / relative).resolve()
    root = config.WEB_DIR.resolve()
    return target if target == root or root in target.parents else None


def _placeholder() -> str:
    """Shown when the bundle is missing, so a deploy without it says what to do.

    Every URL below is relative and the document carries a `<base href>`: this page is
    served under the platform's sub-path, and a leading `/` would point the link at the
    platform root instead of at this app.
    """
    return f"""<!doctype html>
<html lang="ko">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <base href="{config.ROOT_PATH}/">
  <title>Agent Studio</title>
  <style>
    body {{ font: 15px/1.7 system-ui, sans-serif; margin: 0; padding: 3rem 1.5rem;
           background: #fafaf9; color: #1c1917; }}
    main {{ max-width: 34rem; margin: 0 auto; }}
    code {{ background: #f5f5f4; padding: .15em .4em; border-radius: 4px; }}
    a {{ color: #0369a1; }}
  </style>
</head>
<body>
  <main>
    <h1>Agent Studio</h1>
    <p>API는 살아 있지만 화면 번들이 없습니다. 프론트엔드를 <code>backend/web</code>에
       빌드해 넣으세요 — <code>backend/README.md</code>에 명령이 있습니다.</p>
    <p><a href="api/tools">api/tools</a> · <a href="health">health</a></p>
  </main>
</body>
</html>
"""


if __name__ == "__main__":
    # Never a hardcoded port: the platform assigns one and nothing else is reachable.
    uvicorn.run(app, host="127.0.0.1", port=int(os.environ.get("PORT", config.PORT)))
