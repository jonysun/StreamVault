import importlib.util
import sqlite3
import tempfile
import unittest
from datetime import datetime
from pathlib import Path


SCRIPT = (Path(__file__).resolve().parents[2] / "main" / "docker" / "buildx" / "script"
          / "sqlite_to_postgresql.py")
SPEC = importlib.util.spec_from_file_location("sqlite_to_postgresql", SCRIPT)
MIGRATION = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MIGRATION)


class SqliteToPostgresqlTest(unittest.TestCase):
    def test_slims_only_exact_non_null_duplicate(self):
        rows = [["same", "same"], ["new", "legacy"], [None, None]]
        MIGRATION.slim_video_values(["jsondata", "videoinfo"], rows)
        self.assertEqual(rows, [["same", None], ["new", "legacy"], [None, None]])

    def test_source_connection_is_read_only(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.db"
            connection = sqlite3.connect(source)
            connection.execute("CREATE TABLE sample(id INTEGER PRIMARY KEY)")
            connection.commit()
            connection.close()
            readonly = MIGRATION.source_connection(source)
            try:
                with self.assertRaises(sqlite3.OperationalError):
                    readonly.execute("INSERT INTO sample(id) VALUES (1)")
            finally:
                readonly.close()

    def test_identity_lookup_uses_actual_primary_key(self):
        class Cursor:
            def __init__(self):
                self.calls = []

            def execute(self, statement, parameters):
                self.calls.append((statement, parameters))

            def fetchone(self):
                return (None,)

        cursor = Cursor()
        MIGRATION.reset_identity(cursor, "biz_runtime_control", "control_key")
        self.assertEqual(
            cursor.calls,
            [("SELECT pg_get_serial_sequence(%s, %s)",
              ("biz_runtime_control", "control_key"))],
        )

    def test_millisecond_timestamp_uses_sqlite_timezone(self):
        converted = MIGRATION.normalize_target_value(
            1704067200000, "timestamp without time zone")
        self.assertEqual(converted, datetime(2024, 1, 1, 8, 0, 0))

    def test_iso_timestamp_is_parsed_before_insert(self):
        converted = MIGRATION.normalize_target_value(
            "2024-01-01T00:00:00Z", "timestamp without time zone")
        self.assertEqual(converted, datetime(2024, 1, 1, 8, 0, 0))

    def test_non_timestamp_value_is_unchanged(self):
        self.assertEqual(MIGRATION.normalize_target_value(123, "integer"), 123)


if __name__ == "__main__":
    unittest.main()
