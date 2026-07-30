import importlib.util
import sqlite3
import tempfile
import unittest
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


if __name__ == "__main__":
    unittest.main()
