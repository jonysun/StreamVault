import importlib.util
import json
import os
import shutil
import sqlite3
import tempfile
import threading
import unittest
import urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import Mock, patch


SCRIPT = Path(__file__).resolve().parents[2] / "main/docker/buildx/script/streamvault-backfill-thumbnails.py"
SPEC = importlib.util.spec_from_file_location("backfill_thumbnails", SCRIPT)
CLI = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CLI)


class BackfillThumbnailsTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name).resolve()
        self.addCleanup(self.directory.cleanup)
        self.root_patch = patch.object(CLI, "MEDIA_ROOT", self.root)
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)
        self.video = self.root / "video.mp4"
        self.video.write_bytes(b"local-video")
        self.target = self.video.with_suffix(".jpg")
        self.row = (1, str(self.video), "/cos/missing.jpg", "12345", "douyin", "抖音", "", "")
        self.database = Mock()
        self.database.rows.return_value = iter([self.row])
        self.database.payloads.return_value = (None, None)

    def test_preview_recognizes_local_cover_without_network_or_conversion(self):
        cover = self.root / "original.webp"
        cover.write_bytes(b"local-cover")
        self.database.rows.return_value = iter([self.row[:2] + ("/cos/original.webp",) + self.row[3:]])
        with patch.object(CLI, "CoverRefresher") as refresh, patch.object(CLI, "download_cover") as download, patch.object(CLI, "convert_to_jpg") as convert:
            counts = CLI.backfill(self.database, dry_run=True)
        self.assertEqual(counts["would_reuse"], 1)
        self.assertEqual(counts["would_generate"], 0)
        refresh.assert_not_called()
        download.assert_not_called()
        convert.assert_not_called()
        self.database.update_cover.assert_not_called()
        self.database.commit.assert_not_called()
        self.database.payloads.assert_not_called()
        self.assertFalse(self.target.exists())

    def test_preview_classifies_saved_url_refresh_and_final_frame_fallback(self):
        rows = [self.row, (2, str(self.video), "", "", "", "", "", ""),
                (3, str(self.video), "https://cdn.example/cover.jpg", "123", "douyin", "", "", "")]
        self.database.rows.return_value = iter(rows)
        with patch.object(CLI, "CoverRefresher") as refresh, patch.object(CLI, "extract_frame") as frame:
            counts = CLI.backfill(self.database, dry_run=True)
        self.assertEqual((counts["would_download"], counts["would_refresh"], counts["would_generate"]), (1, 1, 1))
        self.assertEqual(counts["would_update"], 3)
        refresh.assert_not_called()
        frame.assert_not_called()

    def test_extracts_nested_covers_but_not_music_author_or_conflicting_work(self):
        payload = json.dumps({"data": {"content_id": "12345", "cover_url": "https://cdn.example/top.jpg",
            "raw": {"aweme_id": "12345", "video": {"cover": {"url_list": ["https://cdn.example/raw.jpg"]}},
                    "author": {"cover_url": "https://cdn.example/author.jpg"},
                    "music": {"cover": "https://cdn.example/music.jpg"}}},
            "items": [{"aweme_id": "999", "cover_url": "https://cdn.example/other.jpg"}]})
        self.assertEqual(CLI.saved_cover_urls("", payload, work_id="12345"),
                         ["https://cdn.example/top.jpg", "https://cdn.example/raw.jpg"])

    def test_legacy_dict_repr_and_normalized_cover_urls(self):
        payload = repr({"aweme_detail": {"video": {"origin_cover": {"urls": ["https://cdn.example/cover.webp"]}}}})
        self.assertEqual(CLI.saved_cover_urls("", "invalid", payload), ["https://cdn.example/cover.webp"])
        self.assertEqual(CLI.saved_cover_urls("https://cdn.example/a?sig=[redacted]",
            json.dumps({"cover_url": "https://cdn.example/a?sig=%5Bredacted%5D"})), [])

    def test_expired_saved_url_refreshes_before_trying_frame(self):
        self.database.payloads.return_value = (json.dumps({"data": {"cover_url": "https://cdn.example/old.jpg"}}), None)
        def download(url, target):
            if url.endswith("fresh.jpg"):
                target.write_bytes(b"fresh-cover")
                return True
            return False
        with patch.object(CLI, "CoverRefresher") as refresh, patch.object(CLI, "download_cover", side_effect=download) as fetch, patch.object(CLI, "extract_frame") as frame:
            refresh.return_value.fetch.return_value = ["https://cdn.example/fresh.jpg"]
            counts = CLI.backfill(self.database)
        self.assertEqual([call.args[0] for call in fetch.call_args_list],
                         ["https://cdn.example/old.jpg", "https://cdn.example/fresh.jpg"])
        frame.assert_not_called()
        self.assertEqual((counts["downloaded"], counts["refreshed"], counts["generated"]), (1, 1, 0))
        self.database.update_cover.assert_called_once_with(1, "/cos/video.jpg")
        self.database.commit.assert_called_once()

    def test_successful_saved_url_never_refreshes(self):
        self.database.payloads.return_value = (json.dumps({"video": {"cover": "https://cdn.example/cover.jpg"}}), None)
        with patch.object(CLI, "CoverRefresher") as refresh, patch.object(CLI, "download_cover", return_value=True), patch.object(CLI, "extract_frame") as frame:
            counts = CLI.backfill(self.database)
        self.assertEqual(counts["downloaded"], 1)
        refresh.assert_not_called()
        frame.assert_not_called()

    def test_only_failed_refresh_falls_back_to_frame(self):
        with patch.object(CLI, "CoverRefresher") as refresh, patch.object(CLI, "extract_frame", return_value=True) as frame:
            refresh.return_value.fetch.return_value = []
            counts = CLI.backfill(self.database)
        refresh.return_value.fetch.assert_called_once_with(1)
        frame.assert_called_once_with(self.video, self.target)
        self.assertEqual(counts["generated"], 1)

    def test_total_failure_does_not_update_database(self):
        with patch.object(CLI, "CoverRefresher") as refresh, patch.object(CLI, "extract_frame", return_value=False):
            refresh.return_value.fetch.return_value = []
            counts = CLI.backfill(self.database)
        self.assertEqual(counts["failed"], 1)
        self.database.update_cover.assert_not_called()
        self.database.commit.assert_not_called()

    def test_limit_skips_already_completed_records(self):
        self.target.write_bytes(b"ready-cover")
        other = self.root / "other.mp4"
        other.write_bytes(b"video")
        self.database.rows.return_value = iter([
            self.row[:2] + ("/cos/video.jpg",) + self.row[3:],
            (2, str(other)) + self.row[2:]])
        counts = CLI.backfill(self.database, dry_run=True, limit=1)
        self.assertEqual(counts["would_update"], 1)
        self.assertEqual(counts["skipped"], 1)

    def test_refresh_disables_on_auth_failure_without_sending_token_in_url(self):
        self.database.app_token.return_value = "private-token"
        refresh = CLI.CoverRefresher(self.database)
        refresh.opener = Mock()
        refresh.opener.open.side_effect = urllib.error.HTTPError("local", 403, "denied", {}, None)
        self.assertEqual(refresh.fetch(1), [])
        self.assertEqual(refresh.fetch(2), [])
        request = refresh.opener.open.call_args.args[0]
        self.assertEqual(request.get_header("X-streamvault-token"), "private-token")
        self.assertNotIn("private-token", request.full_url)
        self.assertEqual(request.data, b"id=1")
        refresh.opener.open.assert_called_once()

    def test_refresher_rejects_remote_application_url(self):
        with patch.dict(os.environ, {"STREAMVAULT_APP_URL": "https://external.example"}):
            with self.assertRaises(ValueError):
                CLI.CoverRefresher(self.database)

    def test_local_transport_failure_disables_refresh_for_remaining_rows(self):
        self.database.app_token.return_value = "private-token"
        refresh = CLI.CoverRefresher(self.database)
        refresh.opener = Mock()
        refresh.opener.open.side_effect = urllib.error.URLError("connection refused")
        self.assertEqual(refresh.fetch(1), [])
        self.assertEqual(refresh.fetch(2), [])
        refresh.opener.open.assert_called_once()

    def test_malformed_refresh_payload_is_ignored(self):
        self.database.app_token.return_value = "private-token"
        refresh = CLI.CoverRefresher(self.database)
        refresh.opener = Mock()
        refresh.opener.open.return_value.__enter__ = Mock(return_value=Mock(read=Mock(return_value=b'[]')))
        refresh.opener.open.return_value.__exit__ = Mock(return_value=False)
        self.assertEqual(refresh.fetch(1), [])

    def test_sqlite_and_http_flow_refreshes_cover_then_is_idempotent(self):
        requests = []
        class Handler(BaseHTTPRequestHandler):
            def do_POST(handler):
                body = handler.rfile.read(int(handler.headers["Content-Length"]))
                requests.append((handler.path, body, handler.headers.get("X-StreamVault-Token")))
                response = json.dumps({"coverUrl": f"http://127.0.0.1:{handler.server.server_port}/fresh.jpg"}).encode()
                handler.send_response(200)
                handler.end_headers()
                handler.wfile.write(response)
            def do_GET(handler):
                requests.append((handler.path, None, None))
                handler.send_response(200)
                handler.end_headers()
                handler.wfile.write(b"fresh-image")
            def log_message(handler, *args):
                pass
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        path = self.root / "test.db"
        connection = sqlite3.connect(path)
        connection.execute("CREATE TABLE biz_video(id INTEGER, videoaddr TEXT, videocover TEXT, jsondata TEXT, videoinfo TEXT, videoid TEXT, platformkey TEXT, videoplatform TEXT, sourceurl TEXT, originaladdress TEXT)")
        connection.execute("INSERT INTO biz_video VALUES(1, ?, '/cos/missing.jpg', '{}', NULL, '12345', 'douyin', '抖音', '', '')", (str(self.video),))
        connection.execute("CREATE TABLE biz_config(id INTEGER, apptoken TEXT)")
        connection.execute("INSERT INTO biz_config VALUES(1, 'private-token')")
        connection.commit()
        connection.close()
        def convert(source, target):
            shutil.copyfile(source, target)
            return True
        with patch.dict(os.environ, {"STREAMVAULT_DB_URL": "jdbc:sqlite:" + str(path),
                                     "STREAMVAULT_APP_URL": f"http://127.0.0.1:{server.server_port}"}), patch.object(CLI, "convert_to_jpg", side_effect=convert), patch.object(CLI, "extract_frame") as frame:
            database = CLI.Database()
            try:
                first = CLI.backfill(database)
                second = CLI.backfill(database)
                cover = database.connection.execute("SELECT videocover FROM biz_video").fetchone()[0]
            finally:
                database.close()
        self.assertEqual(first["refreshed"], 1)
        self.assertEqual(second["updated"], 0)
        self.assertEqual(second["skipped"], 1)
        self.assertEqual(cover, "/cos/video.jpg")
        self.assertEqual(self.target.read_bytes(), b"fresh-image")
        self.assertEqual(requests, [("/api/maintenance/thumbnail-source", b"id=1", "private-token"), ("/fresh.jpg", None, None)])
        frame.assert_not_called()


if __name__ == "__main__":
    unittest.main()
