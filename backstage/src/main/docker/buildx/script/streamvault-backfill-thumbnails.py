#!/opt/venv/bin/python3
"""Backfill local video thumbnails for the StreamVault media feed."""

import argparse
import os
import shutil
import sqlite3
import subprocess
import tempfile
import urllib.error
import urllib.request
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
            SELECT id, videoaddr, videocover
            FROM biz_video
            WHERE videoaddr IS NOT NULL AND trim(videoaddr) <> ''
            ORDER BY id
        """)
        return cursor.fetchall()

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
        request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(request, timeout=60) as response, source.open("wb") as output:
            shutil.copyfileobj(response, output)
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


def main():
    parser = argparse.ArgumentParser(description="Backfill local StreamVault video thumbnails")
    parser.add_argument("--dry-run", action="store_true", help="report work without writing files or database rows")
    parser.add_argument("--limit", type=int, default=0, help="process at most N videos; 0 means all")
    args = parser.parse_args()
    if args.limit < 0:
        parser.error("--limit must be zero or positive")

    database = Database()
    counts = {"downloaded": 0, "generated": 0, "updated": 0, "skipped": 0, "failed": 0}
    planned = {"would_download": 0, "would_generate": 0, "would_update": 0}
    try:
        rows = database.rows()
        if args.limit:
            rows = rows[:args.limit]
        for video_id, raw_video, raw_cover in rows:
            video = Path(str(raw_video)).resolve()
            if not video.is_file() or not video.is_relative_to(MEDIA_ROOT):
                counts["skipped"] += 1
                continue
            target = video.with_suffix(".jpg")
            ready = target.is_file() and target.stat().st_size > 0
            if not ready and not args.dry_run:
                existing = local_cover(raw_cover)
                if existing and existing.is_file():
                    ready = convert_to_jpg(existing, target)
                if not ready and str(raw_cover or "").strip().startswith(("http://", "https://")):
                    ready = download_cover(str(raw_cover).strip(), target)
                if ready:
                    counts["downloaded"] += 1
                else:
                    ready = extract_frame(video, target)
                    if ready:
                        counts["generated"] += 1
            elif not ready and args.dry_run:
                # A dry run must not report planned work as failed. It cannot
                # know whether a remote URL or ffmpeg will succeed without
                # performing the actual work, so report the intended action.
                if str(raw_cover or "").strip().startswith(("http://", "https://")):
                    planned["would_download"] += 1
                else:
                    planned["would_generate"] += 1
                planned["would_update"] += 1
                continue

            if not ready:
                counts["failed"] += 1
                continue
            cover = public_path(target)
            if str(raw_cover or "").strip() != cover:
                if not args.dry_run:
                    database.update_cover(video_id, cover)
                counts["updated"] += 1
        if not args.dry_run:
            database.commit()
    finally:
        database.close()
    if args.dry_run:
        counts.update(planned)
    print(" ".join(f"{key}={value}" for key, value in counts.items()))


if __name__ == "__main__":
    main()
