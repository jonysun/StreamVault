#!/usr/bin/env python3
"""Read-only SQLite audit and snapshot helper for Stream Vault.

The source database is never opened for writes. Snapshot and copy operations
require a destination path different from the source and use SQLite's backup
API so an existing WAL is included in the consistent copy.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sqlite3
import sys
from pathlib import Path


def source_uri(path: Path) -> str:
    return f"file:{path.resolve().as_posix()}?mode=ro"


def connect_readonly(path: Path) -> sqlite3.Connection:
    if not path.is_file():
        raise SystemExit(f"source database does not exist: {path}")
    connection = sqlite3.connect(source_uri(path), uri=True)
    connection.execute("PRAGMA query_only=ON")
    return connection


def scalar(connection: sqlite3.Connection, sql: str) -> int:
    value = connection.execute(sql).fetchone()[0]
    return int(value or 0)


def table_exists(connection: sqlite3.Connection, name: str) -> bool:
    row = connection.execute(
        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", (name,)
    ).fetchone()
    return bool(row[0])


def digest_schema(connection: sqlite3.Connection) -> str:
    rows = connection.execute(
        "SELECT type, name, sql FROM sqlite_master WHERE sql IS NOT NULL "
        "ORDER BY type, name"
    ).fetchall()
    payload = "\n".join("|".join(str(part) for part in row) for row in rows)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def audit(path: Path) -> dict:
    connection = connect_readonly(path)
    try:
        quick = [row[0] for row in connection.execute("PRAGMA quick_check(1)")]
        integrity = [row[0] for row in connection.execute("PRAGMA integrity_check")]
        video = {
            "rows": scalar(connection, "SELECT COUNT(*) FROM biz_video"),
            "jsonDataRows": scalar(connection, "SELECT COUNT(*) FROM biz_video WHERE COALESCE(jsonData,'') <> ''"),
            "videoinfoRows": scalar(connection, "SELECT COUNT(*) FROM biz_video WHERE COALESCE(videoinfo,'') <> ''"),
            "exactDuplicateRows": scalar(connection, "SELECT COUNT(*) FROM biz_video WHERE jsonData = videoinfo"),
            "exactDuplicateChars": scalar(connection, "SELECT COALESCE(SUM(LENGTH(videoinfo)),0) FROM biz_video WHERE jsonData = videoinfo"),
            "differentRows": scalar(connection, "SELECT COUNT(*) FROM biz_video WHERE jsonData IS NOT NULL AND videoinfo IS NOT NULL AND jsonData <> videoinfo"),
        }
        tables = {}
        for name in ("biz_video", "biz_graphic_content", "biz_collect_data", "biz_collect_run",
                     "biz_collect_run_item", "biz_job_queue"):
            tables[name] = scalar(connection, f"SELECT COUNT(*) FROM {name}") if table_exists(connection, name) else None
        return {
            "source": str(path.resolve()),
            "sizeBytes": path.stat().st_size,
            "walPresent": path.with_name(path.name + "-wal").exists(),
            "shmPresent": path.with_name(path.name + "-shm").exists(),
            "quickCheck": quick,
            "integrityCheck": integrity,
            "schemaSha256": digest_schema(connection),
            "tables": tables,
            "video": video,
            "status": "ok" if quick == ["ok"] and integrity == ["ok"] else "blocked",
        }
    finally:
        connection.close()


def ensure_destination(source: Path, destination: Path) -> None:
    if source.resolve() == destination.resolve():
        raise SystemExit("destination must differ from source")
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        raise SystemExit(f"destination already exists; refusing to overwrite: {destination}")


def snapshot(source: Path, destination: Path) -> None:
    ensure_destination(source, destination)
    source_connection = connect_readonly(source)
    try:
        destination_connection = sqlite3.connect(destination)
        try:
            source_connection.backup(destination_connection)
            destination_connection.commit()
        finally:
            destination_connection.close()
    finally:
        source_connection.close()


def verify_copy(source: Path, copy: Path) -> dict:
    source_report = audit(source)
    copy_report = audit(copy)
    keys = ("schemaSha256", "tables", "video", "quickCheck", "integrityCheck")
    mismatches = [key for key in keys if source_report[key] != copy_report[key]]
    return {"source": source_report, "copy": copy_report, "mismatches": mismatches,
            "status": "ok" if not mismatches and copy_report["status"] == "ok" else "blocked"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("audit",):
        command = sub.add_parser(name)
        command.add_argument("source", type=Path)
    command = sub.add_parser("snapshot")
    command.add_argument("source", type=Path)
    command.add_argument("destination", type=Path)
    command = sub.add_parser("verify-copy")
    command.add_argument("source", type=Path)
    command.add_argument("copy", type=Path)
    args = parser.parse_args()
    if args.command == "audit":
        result = audit(args.source)
    elif args.command == "snapshot":
        snapshot(args.source, args.destination)
        result = audit(args.destination)
    else:
        result = verify_copy(args.source, args.copy)
    print(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True))
    return 0 if result.get("status") == "ok" else 2


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except sqlite3.DatabaseError as error:
        print(json.dumps({"status": "blocked", "error": str(error)}, ensure_ascii=False), file=sys.stderr)
        raise SystemExit(2)
