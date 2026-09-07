"""Registering the databases nl2sql may query, and reading their schema.

Registration and sync are separate calls on purpose — see `datasources.sync`. The
password goes in and never comes back out: `Datasource` has no such field, so there is
no response shape that could leak it.
"""

import logging
from uuid import UUID

from fastapi import APIRouter, Response

from .. import datasources
from ..errors import ApiException
from ..models import Datasource, DatasourceInput, DatasourceTable, SyncResult

log = logging.getLogger(__name__)
router = APIRouter(prefix="/api/datasources", tags=["datasources"])


def require(datasource_id: UUID) -> Datasource:
    """@raises ApiException 404 so every handler below can assume it exists."""
    found = datasources.find_by_id(datasource_id)
    if found is None:
        raise ApiException.not_found(f"no datasource with id {datasource_id}")
    return found


@router.get("")
def list_datasources() -> list[Datasource]:
    return datasources.find_all()


@router.get("/{datasource_id}")
def get_datasource(datasource_id: UUID) -> Datasource:
    return require(datasource_id)


@router.post("", status_code=201)
def create(spec: DatasourceInput) -> Datasource:
    return datasources.insert(spec)


@router.put("/{datasource_id}")
def replace(datasource_id: UUID, spec: DatasourceInput) -> Datasource:
    require(datasource_id)
    saved = datasources.update(datasource_id, spec)
    if saved is None:
        raise ApiException.not_found(f"no datasource with id {datasource_id}")
    return saved


@router.delete("/{datasource_id}", status_code=204)
def remove(datasource_id: UUID) -> Response:
    require(datasource_id)
    datasources.delete_by_id(datasource_id)
    return Response(status_code=204)


@router.post("/{datasource_id}/sync")
def sync(datasource_id: UUID) -> SyncResult:
    """Reads the target database's catalog and replaces the stored schema with it.

    Slow by nature — it opens a connection to somebody else's database — so it is its
    own endpoint rather than something a save does silently.
    """
    require(datasource_id)
    return datasources.sync(datasource_id)


@router.get("/{datasource_id}/schema")
def get_schema(datasource_id: UUID) -> list[DatasourceTable]:
    """What the last sync stored. Empty until one has run."""
    require(datasource_id)
    return datasources.find_schema(datasource_id)
