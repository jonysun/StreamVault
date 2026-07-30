import json
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[4] / "scripts" / "sqlite_audit.py"


class SqliteAuditCliTest(unittest.TestCase):
    def test_snapshot_and_verify_do_not_modify_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.db"
            copy = root / "copy.db"
            connection = sqlite3.connect(source)
            try:
                connection.executescript(
                    "CREATE TABLE biz_video(id INTEGER PRIMARY KEY, jsonData TEXT, videoinfo TEXT);"
                    "CREATE TABLE biz_graphic_content(id INTEGER PRIMARY KEY, jsonData TEXT);"
                    "INSERT INTO biz_video(jsonData, videoinfo) VALUES ('same', 'same'), ('a', 'b');"
                    "CREATE TABLE biz_collect_data(id INTEGER PRIMARY KEY);"
                    "CREATE TABLE biz_collect_run(id INTEGER PRIMARY KEY);"
                    "CREATE TABLE biz_collect_run_item(id INTEGER PRIMARY KEY);"
                    "CREATE TABLE biz_job_queue(id INTEGER PRIMARY KEY);"
                )
                connection.commit()
            finally:
                connection.close()
            before = source.read_bytes()
            result = subprocess.run(
                [sys.executable, str(SCRIPT), "snapshot", str(source), str(copy)],
                check=False, capture_output=True, text=True,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            report = json.loads(result.stdout)
            self.assertEqual(report["status"], "ok")
            self.assertEqual(source.read_bytes(), before)
            verify = subprocess.run(
                [sys.executable, str(SCRIPT), "verify-copy", str(source), str(copy)],
                check=False, capture_output=True, text=True,
            )
            self.assertEqual(verify.returncode, 0, verify.stderr)
            self.assertEqual(json.loads(verify.stdout)["mismatches"], [])


if __name__ == "__main__":
    unittest.main()
