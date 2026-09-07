"""Every failure leaves the API as {"detail": ...}.

Anything the spec form could plausibly fix also carries an "errors" map keyed by the
wire field name, so the editor can highlight individual inputs; a source file that will
not compile carries "compile_errors" instead, because those belong to lines in the code
editor rather than to inputs on a form.
"""

import logging
import traceback

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

log = logging.getLogger(__name__)


class ApiException(Exception):
    """Carries an HTTP status so the handler can render the body uniformly."""

    def __init__(self, status: int, detail: str) -> None:
        super().__init__(detail)
        self.status = status
        self.detail = detail

    @staticmethod
    def not_found(detail: str) -> "ApiException":
        return ApiException(404, detail)

    @staticmethod
    def bad_request(detail: str) -> "ApiException":
        return ApiException(400, detail)

    @staticmethod
    def conflict(detail: str) -> "ApiException":
        """The request was well formed but collides with what is already stored — a
        datasource name that is taken, say. 400 would read as "you typed it wrong"."""
        return ApiException(409, detail)

    @staticmethod
    def bad_gateway(detail: str) -> "ApiException":
        """Something this server depends on failed, and it is not the caller's doing:
        a registered database that will not answer. Reported apart from 500 so a
        reachability problem is not mistaken for a bug in the studio."""
        return ApiException(502, detail)


class CodeCompilationException(Exception):
    """The user's own source did not compile. `errors` holds the interpreter's messages."""

    def __init__(self, errors: list[str]) -> None:
        super().__init__("the agent's source does not compile")
        self.errors = errors


class GraphBuildException(Exception):
    """Source that imports and runs but does not expose the entry point the studio calls."""


def install(app: FastAPI) -> None:
    @app.exception_handler(ApiException)
    async def _api(_: Request, exc: ApiException) -> JSONResponse:
        return JSONResponse(status_code=exc.status, content={"detail": exc.detail})

    @app.exception_handler(RequestValidationError)
    async def _validation(_: Request, exc: RequestValidationError) -> JSONResponse:
        # A path variable that will not convert — a malformed UUID, say — is the
        # caller's mistake about the URL, not about a field on the spec form. Giving it
        # an `errors` map would ask the editor to highlight an input that does not exist.
        bad_path = _path_error(exc)
        if bad_path is not None:
            return JSONResponse(status_code=400, content={"detail": bad_path})

        return JSONResponse(
            status_code=400,
            content={"detail": "spec validation failed", "errors": field_errors(exc)},
        )

    @app.exception_handler(CodeCompilationException)
    async def _compile(_: Request, exc: CodeCompilationException) -> JSONResponse:
        return JSONResponse(
            status_code=400,
            content={
                "detail": "the agent's source does not compile",
                "compile_errors": exc.errors,
            },
        )

    @app.exception_handler(GraphBuildException)
    async def _graph(_: Request, exc: GraphBuildException) -> JSONResponse:
        return JSONResponse(status_code=400, content={"detail": str(exc)})

    @app.exception_handler(Exception)
    async def _unexpected(_: Request, exc: Exception) -> JSONResponse:
        log.error("unhandled exception\n%s", traceback.format_exc())
        return JSONResponse(
            status_code=500,
            content={"detail": f"{type(exc).__name__}: {exc}"},
        )


def _path_error(exc: RequestValidationError) -> str | None:
    """The message for a path variable that would not convert, or None."""
    for error in exc.errors():
        location = error["loc"]
        if location and location[0] == "path":
            name = location[-1]
            given = error.get("input")
            return f"'{name}' is not a valid value: {given}"
    return None


def field_errors(exc: RequestValidationError) -> dict[str, str]:
    """Renders pydantic's location tuples as the wire field names the form knows.

    A location is ("body", "steps", 0, "name"); the form's field is "steps[0].name".
    The leading "body" is dropped — the client never sent a field by that name.
    """
    from .models import WHOLE_BODY_FIELDS

    errors: dict[str, str] = {}
    for error in exc.errors():
        location = [part for part in error["loc"] if part != "body"]
        message = _message(error["msg"])
        if not location:
            # A rule about the whole spec, which pydantic reports with no location at
            # all. Dropping it here is what left the client a "validation failed" with
            # an empty errors map and nothing to highlight.
            field = WHOLE_BODY_FIELDS.get(message)
            if field is not None:
                errors.setdefault(field, message)
            continue
        field = ""
        for part in location:
            if isinstance(part, int):
                field += f"[{part}]"
            else:
                field = f"{field}.{part}" if field else str(part)
        errors.setdefault(field, message)
    return errors


def _message(message: str) -> str:
    """Drops pydantic's own framing.

    A raised ValueError arrives as "Value error, must not be blank". The editor prints
    these under the offending input, where the prefix is noise the user cannot act on.
    """
    prefix = "Value error, "
    return message[len(prefix) :] if message.startswith(prefix) else message
