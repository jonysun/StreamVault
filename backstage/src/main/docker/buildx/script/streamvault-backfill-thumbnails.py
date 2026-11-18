#!/opt/venv/bin/python3
"""Backfill local video thumbnails for the StreamVault media feed."""

import argparse
import ast
import json
import os
import sqlite3
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from urllib.parse import urlencode, urlsplit
from pathlib import Path


MEDIA_ROOT = Path(os.environ.get("STREAMVAULT_MEDIA_ROOT", "/app/resources")).resolve()
PUBLIC_ROOT = "/cos"


class Database:
    def __init__(self):
        url = os.environ.get("STREAMVAULT_DB_URL", "")
        if url.startswith("jdbc:postgresql:"):
            import psycopg

            self.kind = "postgresql"
            self.placeholder = "%s"
            self.connection = psycopg.connect(
                url.removeprefix("jdbc:"),
                user=os.environ.get("STREAMVAULT_DB_USER"),
                password=os.environ.get("STREAMVAULT_DB_PASSWORD"),
            )
        else:
            path = url.removeprefix("jdbc:sqlite:").split("?", 1)[0] or "/app/db/spirit.db"
            self.kind = "sqlite"
            self.placeholder = "?"
            self.connection = sqlite3.connect(path)

    def rows(self):
        cursor = self.connection.cursor()
        cursor.execute("""
            SELECT id, videoaddr, videocover,
                   videoid, platformkey, videoplatform, sourceurl, originaladdress
            FROM biz_video
            WHERE videoaddr IS NOT NULL AND trim(videoaddr) <> ''
            ORDER BY id
        """)
        while rows := cursor.fetchmany(100):
            yield from rows

    def payloads(self, video_id):
        cursor = self.connection.cursor()
        cursor.execute(f"SELECT jsondata, videoinfo FROM biz_video WHERE id = {self.placeholder}", (video_id,))
        return cursor.fetchone() or (None, None)

    def app_token(self):
        cursor = self.connection.cursor()
        cursor.execute("SELECT apptoken FROM biz_config ORDER BY id LIMIT 1")
        row = cursor.fetchone()
        return str(row[0] or "") if row else ""

    def update_cover(self, video_id, cover):
        cursor = self.connection.cursor()
        cursor.execute(
            f"UPDATE biz_video SET videocover = {self.placeholder} WHERE id = {self.placeholder}",
            (cover, video_id),
        )

    def commit(self):
        self.connection.commit()

    def close(self):
        self.connection.close()


def public_path(path):
    return PUBLIC_ROOT + "/" + path.relative_to(MEDIA_ROOT).as_posix()


def local_cover(raw):
    value = str(raw or "").strip()
    if value.startswith(PUBLIC_ROOT + "/"):
        candidate = MEDIA_ROOT / value[len(PUBLIC_ROOT) + 1:]
    elif value.startswith("/"):
        candidate = Path(value)
    else:
        return None
    try:
        candidate = candidate.resolve()
        return candidate if candidate.is_relative_to(MEDIA_ROOT) else None
    except OSError:
        return None


def convert_to_jpg(source, target):
    temporary = target.with_name("." + target.name + ".tmp.jpg")
    temporary.unlink(missing_ok=True)
    try:
        result = subprocess.run(
            ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(source),
             "-frames:v", "1", "-q:v", "3", str(temporary)],
            timeout=120,
        )
        if result.returncode == 0 and temporary.is_file() and temporary.stat().st_size > 0:
            temporary.replace(target)
            return True
    except (OSError, subprocess.SubprocessError):
        pass
    temporary.unlink(missing_ok=True)
    return False


def download_cover(url, target):
    with tempfile.NamedTemporaryFile(prefix="streamvault-cover-", suffix=".source", delete=False) as handle:
        source = Path(handle.name)
    try:
        headers = {"User-Agent": "Mozilla/5.0"}
        host = urlsplit(url).hostname or ""
        if any(host == domain or host.endswith("." + domain) for domain in ("douyinpic.com", "douyincdn.com", "byteimg.com")):
            headers["Referer"] = "https://www.douyin.com/"
        request = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(request, timeout=60) as response, source.open("wb") as output:
            deadline = time.monotonic() + 60
            total = 0
            while chunk := response.read1(65536):
                total += len(chunk)
                if time.monotonic() > deadline or total > 16 * 1024 * 1024:
                    return False
                output.write(chunk)
        return convert_to_jpg(source, target)
    except (OSError, ValueError, urllib.error.URLError):
        return False
    finally:
        source.unlink(missing_ok=True)


def extract_frame(video, target):
    for seek in ("1", "0"):
        temporary = target.with_name("." + target.name + ".tmp.jpg")
        temporary.unlink(missing_ok=True)
        try:
            result = subprocess.run(
                ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-ss", seek,
                 "-i", str(video), "-frames:v", "1", "-q:v", "3", str(temporary)],
                timeout=120,
            )
            if result.returncode == 0 and temporary.is_file() and temporary.stat().st_size > 0:
                temporary.replace(target)
                return True
        except (OSError, subprocess.SubprocessError):
            pass
        temporary.unlink(missing_ok=True)
    return False


def remote_urls(value):
    if isinstance(value, str):
        value = value.strip()
        if value.startswith(("http://", "https://")) and "[redacted]" not in value and "%5bredacted%5d" not in value.lower():
            return [value]
    if isinstance(value, list):
        return [url for item in value for url in remote_urls(item)]
    if isinstance(value, dict):
        return [url for key in ("url_list", "urls", "url", "src") for url in remote_urls(value.get(key))]
    return []


def saved_cover_urls(raw_cover, *payloads, work_id=None):
    urls = remote_urls(raw_cover)

    def visit(node, depth=0):
        if depth > 12:
            return
        if isinstance(node, list):
            for item in node:
                visit(item, depth + 1)
        elif isinstance(node, dict):
            observed_id = node.get("aweme_id") or node.get("content_id") or node.get("videoid")
            if work_id and observed_id and str(work_id) != str(observed_id):
                return
            for key in ("cover", "origin_cover", "dynamic_cover", "cover_url", "coverUrl", "thumbnail", "thumbnails"):
                urls.extend(remote_urls(node.get(key)))
            # Only work containers: author/music covers are not video thumbnails.
            for key in ("data", "raw", "aweme_detail", "aweme_details", "detail", "item", "items", "video", "media", "result"):
                visit(node.get(key), depth + 1)

    for raw in payloads:
        if not raw:
            continue
        try:
            value = json.loads(raw)
        except (ValueError, TypeError, RecursionError):
            try:
                value = ast.literal_eval(raw)  # Older F2 rows used Python dict repr.
            except (ValueError, SyntaxError, TypeError, RecursionError):
                continue
        visit(value)
    return list(dict.fromkeys(urls))


def can_refresh(row):
    return bool(str(row[6] or row[7] or "").strip()
                or (str(row[4] or row[5] or "").lower() in ("douyin", "抖音") and str(row[3] or "").isdigit()))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class CoverRefresher:
    def __init__(self, database):
        self.database = database
        self.token = None
        self.last_request = 0
        self.disabled = False
        self.base = os.environ.get("STREAMVAULT_APP_URL", "http://127.0.0.1:" + os.environ.get("SERVER_PORT", "28081")).rstrip("/")
        parsed = urlsplit(self.base)
        if (parsed.scheme != "http" or parsed.hostname not in ("127.0.0.1", "::1", "localhost")
                or parsed.username or parsed.path or parsed.query or parsed.fragment):
            raise ValueError("STREAMVAULT_APP_URL must be a loopback HTTP origin")
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def fetch(self, video_id):
        if self.disabled:
            return []
        if self.token is None:
            self.token = self.database.app_token()
        if not self.token:
            self.disabled = True
            print("detail refresh unavailable: application token is not configured", file=sys.stderr)
            return []
        time.sleep(max(0, 0.75 - (time.monotonic() - self.last_request)))
        self.last_request = time.monotonic()
        request = urllib.request.Request(self.base + "/api/maintenance/thumbnail-source",
            data=urlencode({"id": video_id}).encode(),
            headers={"X-StreamVault-Token": self.token}, method="POST")
        try:
            with self.opener.open(request, timeout=90) as response:
                payload = json.loads(response.read(1024 * 1024))
                return remote_urls(payload.get("coverUrl")) if isinstance(payload, dict) else []
        except urllib.error.HTTPError as error:
            if error.code in (401, 403, 404):
                self.disabled = True
            print(f"detail refresh failed id={video_id} http={error.code}", file=sys.stderr)
        except (OSError, urllib.error.URLError):
            self.disabled = True
            print(f"detail refresh unavailable id={video_id}; disabling refresh for this run", file=sys.stderr)
        except ValueError:
            print(f"detail refresh failed id={video_id} invalid_response", file=sys.stderr)
        finally:
            self.last_request = time.monotonic()
        return []


def backfill(database, dry_run=False, limit=0):
    counts = {"downloaded": 0, "generated": 0, "updated": 0, "skipped": 0, "failed": 0,
              "reused": 0, "refresh_attempted": 0, "refreshed": 0}
    planned = {"would_reuse": 0, "would_download": 0, "would_refresh": 0, "would_generate": 0, "would_update": 0}
    refresher = None
    processed = 0
    for row in database.rows():
        video_id, raw_video, raw_cover = row[:3]
        video = Path(str(raw_video)).resolve()
        if not video.is_file() or not video.is_relative_to(MEDIA_ROOT):
            counts["skipped"] += 1
            continue
        target = video.with_suffix(".jpg")
        ready = target.is_file() and target.stat().st_size > 0
        if ready and str(raw_cover or "").strip() == public_path(target):
            counts["skipped"] += 1
            continue
        if limit and processed >= limit:
            break
        processed += 1
        existing = local_cover(raw_cover)
        reusable = existing is not None and existing.is_file() and existing.stat().st_size > 0
        urls = []
        if not ready and not (dry_run and reusable):
            urls = saved_cover_urls(raw_cover, *database.payloads(video_id), work_id=row[3])
        if dry_run:
            if ready or reusable:
                planned["would_reuse"] += 1
            elif urls:
                planned["would_download"] += 1
            elif can_refresh(row):
                planned["would_refresh"] += 1
            else:
                planned["would_generate"] += 1
            planned["would_update"] += 1
            continue
        if ready or (reusable and convert_to_jpg(existing, target)):
            ready = True
            counts["reused"] += 1
        if not ready:
            for url in urls:
                if download_cover(url, target):
                    ready = True
                    counts["downloaded"] += 1
                    break
        if not ready and can_refresh(row):
            if refresher is None:
                refresher = CoverRefresher(database)
            counts["refresh_attempted"] += 1
            for url in refresher.fetch(video_id):
                if download_cover(url, target):
                    ready = True
                    counts["downloaded"] += 1
                    counts["refreshed"] += 1
                    break
        if not ready:
            ready = extract_frame(video, target)
            if ready:
                counts["generated"] += 1
        if ready:
            database.update_cover(video_id, public_path(target))
            database.commit()
            counts["updated"] += 1
        else:
            counts["failed"] += 1
        if processed % 50 == 0:
            print("processed=" + str(processed) + " " + " ".join(f"{k}={v}" for k, v in counts.items()), file=sys.stderr, flush=True)
    if dry_run:
        counts.update(planned)
    return counts


def main():
    parser = argparse.ArgumentParser(description="Backfill local StreamVault video thumbnails")
    parser.add_argument("--dry-run", action="store_true", help="report work without writing files or database rows")
    parser.add_argument("--limit", type=int, default=0, help="process at most N pending videos; 0 means all")
    args = parser.parse_args()
    if args.limit < 0:
        parser.error("--limit must be zero or positive")

    database = Database()
    try:
        counts = backfill(database, args.dry_run, args.limit)
    finally:
        database.close()
    print(" ".join(f"{key}={value}" for key, value in counts.items()))


if __name__ == "__main__":
    main()
