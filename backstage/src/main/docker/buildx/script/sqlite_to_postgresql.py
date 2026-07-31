#!/usr/bin/env python3
"""Explicit, one-shot Stream Vault SQLite to PostgreSQL migration."""

from __future__ import annotations

import argparse
import json
import os
import sqlite3
import sys
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo


ALLOWED_PREFILL = {"biz_runtime_control"}
SQLITE_TIMEZONE = ZoneInfo(os.getenv("STREAMVAULT_SQLITE_TIMEZONE", "Asia/Shanghai"))


def source_connection(path: Path) -> sqlite3.Connection:
    if not path.is_file():
        raise RuntimeError(f"SQLite source does not exist: {path}")
    connection = sqlite3.connect(f"file:{path.resolve().as_posix()}?mode=ro", uri=True)
    connection.execute("PRAGMA query_only=ON")
    connection.row_factory = sqlite3.Row
    return connection


def target_connection():
    try:
        import psycopg
    except ImportError as error:
        raise RuntimeError("psycopg is not installed in the migration image") from error
    return psycopg.connect(
        host=os.getenv("STREAMVAULT_PG_HOST", "postgres"),
        port=int(os.getenv("STREAMVAULT_PG_PORT", "5432")),
        dbname=os.getenv("STREAMVAULT_DB_NAME", "streamvault"),
        user=os.getenv("STREAMVAULT_DB_USER", "streamvault"),
        password=os.environ["STREAMVAULT_DB_PASSWORD"],
    )


def source_tables(connection: sqlite3.Connection) -> list[str]:
    return [row[0] for row in connection.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
    )]


def target_tables(connection) -> list[str]:
    with connection.cursor() as cursor:
        cursor.execute(
            "SELECT table_name FROM information_schema.tables "
            "WHERE table_schema='public' AND table_type='BASE TABLE' "
            "AND table_name <> 'flyway_schema_history' ORDER BY table_name"
        )
        return [row[0] for row in cursor.fetchall()]


def source_count(connection: sqlite3.Connection, table: str) -> int:
    return int(connection.execute(f'SELECT COUNT(*) FROM "{table}"').fetchone()[0])


def target_count(connection, table: str) -> int:
    from psycopg import sql
    with connection.cursor() as cursor:
        cursor.execute(sql.SQL("SELECT COUNT(*) FROM {}").format(sql.Identifier(table)))
        return int(cursor.fetchone()[0])


def audit(source, target) -> dict:
    quick = [row[0] for row in source.execute("PRAGMA quick_check(1)")]
    integrity = [row[0] for row in source.execute("PRAGMA integrity_check")]
    source_names = source_tables(source)
    target_names = target_tables(target)
    shared = sorted(set(source_names) & set(target_names))
    counts = {name: {"sqlite": source_count(source, name), "postgresql": target_count(target, name)}
              for name in shared}
    exact_duplicates = int(source.execute(
        "SELECT COUNT(*) FROM biz_video WHERE jsonData IS NOT NULL AND jsonData = videoinfo"
    ).fetchone()[0])
    return {
        "quickCheck": quick,
        "integrityCheck": integrity,
        "sourceTables": source_names,
        "targetTables": target_names,
        "missingTargetTables": sorted(set(source_names) - set(target_names)),
        "counts": counts,
        "exactDuplicateVideoRowsToSlim": exact_duplicates,
        "status": "ok" if quick == ["ok"] and integrity == ["ok"] else "blocked",
    }


def dry_run(source, target) -> dict:
    report = audit(source, target)
    unexpected = {name: values["postgresql"] for name, values in report["counts"].items()
                  if values["postgresql"] and name not in ALLOWED_PREFILL}
    if report["missingTargetTables"] or unexpected:
        report["status"] = "blocked"
    report["unexpectedTargetRows"] = unexpected
    return report


def source_columns(source, table: str) -> tuple[list[str], str]:
    rows = source.execute(f'PRAGMA table_info("{table}")').fetchall()
    columns = [row[1] for row in rows]
    primary = next((row[1] for row in rows if int(row[5]) == 1), None)
    if not primary:
        raise RuntimeError(f"table has no single primary key: {table}")
    return columns, primary


def target_column_specs(target, table: str) -> list[tuple[str, str]]:
    with target.cursor() as cursor:
        cursor.execute(
            "SELECT column_name, data_type FROM information_schema.columns "
            "WHERE table_schema='public' AND table_name=%s ORDER BY ordinal_position", (table,)
        )
        return [(row[0], row[1]) for row in cursor.fetchall()]


def normalize_target_value(value, data_type: str):
    if value is None or not data_type.startswith("timestamp"):
        return value
    if isinstance(value, (int, float)):
        seconds = value / 1000 if abs(value) >= 100_000_000_000 else value
        return datetime.fromtimestamp(seconds, SQLITE_TIMEZONE).replace(tzinfo=None)
    if isinstance(value, str):
        stripped = value.strip()
        try:
            numeric = float(stripped)
        except ValueError:
            parsed = datetime.fromisoformat(stripped.replace("Z", "+00:00"))
            if parsed.tzinfo is not None:
                parsed = parsed.astimezone(SQLITE_TIMEZONE).replace(tzinfo=None)
            return parsed
        return normalize_target_value(numeric, data_type)
    raise ValueError(f"unsupported timestamp value type: {type(value).__name__}")


def load_table(source, target, table: str, batch_size: int) -> int:
    from psycopg import sql
    available, primary = source_columns(source, table)
    source_by_lower = {name.lower(): name for name in available}
    target_specs = target_column_specs(target, table)
    columns = [name for name, _ in target_specs if name.lower() in source_by_lower]
    target_types = {name: data_type for name, data_type in target_specs}
    source_names = [source_by_lower[name.lower()] for name in columns]
    primary_source = source_by_lower[primary.lower()]
    primary_target = next((name for name in columns if name.lower() == primary.lower()), None)
    if primary_target is None:
        raise RuntimeError(f"target table is missing source primary key {primary}: {table}")
    last_key = None
    inserted = 0
    with target.cursor() as cursor:
        if table in ALLOWED_PREFILL:
            cursor.execute(sql.SQL("TRUNCATE TABLE {}").format(sql.Identifier(table)))
        statement = sql.SQL("INSERT INTO {} ({}) VALUES ({})").format(
            sql.Identifier(table),
            sql.SQL(",").join(map(sql.Identifier, columns)),
            sql.SQL(",").join(sql.Placeholder() for _ in columns),
        )
        while True:
            quoted = ",".join(f'"{name}"' for name in source_names)
            if last_key is None:
                query = f'SELECT {quoted} FROM "{table}" ORDER BY "{primary_source}" LIMIT ?'
                rows = source.execute(query, (batch_size,)).fetchall()
            else:
                query = (f'SELECT {quoted} FROM "{table}" WHERE "{primary_source}" > ? '
                         f'ORDER BY "{primary_source}" LIMIT ?')
                rows = source.execute(query, (last_key, batch_size)).fetchall()
            if not rows:
                break
            values = []
            for row in rows:
                converted = []
                for index, column in enumerate(columns):
                    try:
                        converted.append(normalize_target_value(row[index], target_types[column]))
                    except (OverflowError, OSError, ValueError) as error:
                        raise RuntimeError(
                            f"cannot convert {table}.{column} to {target_types[column]}"
                        ) from error
                values.append(converted)
            if table == "biz_video":
                slim_video_values(columns, values)
            cursor.executemany(statement, values)
            last_key = rows[-1][source_names.index(primary_source)]
            inserted += len(rows)
        reset_identity(cursor, table, primary_target)
    target.commit()
    return inserted


def slim_video_values(columns: list[str], values: list[list]) -> None:
    if "jsondata" not in columns or "videoinfo" not in columns:
        return
    json_index = columns.index("jsondata")
    legacy_index = columns.index("videoinfo")
    for row in values:
        if row[json_index] is not None and row[json_index] == row[legacy_index]:
            row[legacy_index] = None


def reset_identity(cursor, table: str, primary: str) -> None:
    cursor.execute("SELECT pg_get_serial_sequence(%s, %s)", (table, primary))
    sequence = cursor.fetchone()[0]
    if not sequence:
        return
    from psycopg import sql
    cursor.execute(sql.SQL("SELECT COALESCE(MAX({}), 0) FROM {}").format(
        sql.Identifier(primary), sql.Identifier(table)))
    maximum = int(cursor.fetchone()[0])
    cursor.execute("SELECT setval(%s, %s, %s)", (sequence, max(1, maximum), maximum > 0))


def load(source, target, batch_size: int, confirm: str) -> dict:
    if confirm != "LOAD":
        raise RuntimeError("load requires --confirm LOAD")
    preview = dry_run(source, target)
    if preview["status"] != "ok":
        return preview
    inserted = {}
    for table in preview["sourceTables"]:
        if table in preview["targetTables"]:
            inserted[table] = load_table(source, target, table, batch_size)
    result = verify(source, target)
    result["inserted"] = inserted
    return result


def verify(source, target) -> dict:
    report = audit(source, target)
    mismatches = {name: values for name, values in report["counts"].items()
                  if values["sqlite"] != values["postgresql"]}
    with target.cursor() as cursor:
        cursor.execute("SELECT COUNT(*) FROM biz_video WHERE jsondata IS NOT NULL AND jsondata = videoinfo")
        remaining_duplicates = int(cursor.fetchone()[0])
    report["countMismatches"] = mismatches
    report["remainingExactDuplicateVideoRows"] = remaining_duplicates
    if report["missingTargetTables"] or mismatches or remaining_duplicates:
        report["status"] = "blocked"
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("audit", "dry-run", "load", "verify"),
                        default=os.getenv("STREAMVAULT_MIGRATION_MODE", "dry-run"))
    parser.add_argument("--source", type=Path,
                        default=Path(os.getenv("STREAMVAULT_SQLITE_SOURCE", "/app/db/spirit.db")))
    parser.add_argument("--batch-size", type=int, default=500)
    parser.add_argument("--confirm", default=os.getenv("STREAMVAULT_MIGRATION_CONFIRM", ""))
    args = parser.parse_args()
    source = source_connection(args.source)
    target = target_connection()
    try:
        if args.mode == "audit":
            result = audit(source, target)
        elif args.mode == "dry-run":
            result = dry_run(source, target)
        elif args.mode == "load":
            result = load(source, target, max(1, args.batch_size), args.confirm)
        else:
            result = verify(source, target)
        print(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True, default=str))
        return 0 if result.get("status") == "ok" else 2
    finally:
        source.close()
        target.close()


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(json.dumps({"status": "blocked", "error": str(error)}, ensure_ascii=False), file=sys.stderr)
        raise SystemExit(2)
