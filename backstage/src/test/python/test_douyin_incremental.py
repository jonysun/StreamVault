import json
import argparse
import contextlib
import importlib.util
import io
import os
import pathlib
import sys
import tempfile
import types
import unittest
from unittest import mock
from pathlib import Path


SCRIPT_DIR = (
    Path(__file__).resolve().parents[2] / "main" / "docker" / "buildx" / "script"
)
sys.path.insert(0, str(SCRIPT_DIR))

from douyin_incremental import UpstreamSchemaError, normalize_aweme, paginate


def work(
    aweme_id,
    create_time,
    *,
    desc="",
    nickname="author",
    uid="MS4-author",
    avatar="https://example.test/avatar.jpg",
    cover=None,
    images=None,
):
    return {
        "aweme_id": aweme_id,
        "desc": desc,
        "create_time": create_time,
        "author": {
            "nickname": nickname,
            "sec_uid": uid,
            "avatar_thumb": {"url_list": [avatar]},
        },
        "video": {"cover": {"url_list": cover or ["https://example.test/cover.jpg"]}},
        "images": images or [],
    }


def page(works, *, has_more, cursor):
    return {
        "aweme_list": works,
        "has_more": has_more,
        "max_cursor": cursor,
    }


def fake_fetch(pages):
    remaining = iter(pages)
    requested_cursors = []

    async def fetch_page(cursor):
        requested_cursors.append(cursor)
        return next(remaining)

    fetch_page.requested_cursors = requested_cursors
    return fetch_page


class NormalizeAwemeTest(unittest.TestCase):
    def test_normalizes_required_fields_and_image_media_type(self):
        normalized = normalize_aweme(
            work(
                123,
                1720000000,
                desc="caption",
                nickname="display name",
                uid="MS4-sec-uid",
                avatar="avatar-url",
                cover=["cover-1", "cover-2"],
                images=[{"url_list": ["image-url"]}],
            )
        )

        self.assertEqual(
            {
                "aweme_id": "123",
                "desc": "caption",
                "create_time": "1720000000",
                "nickname": "display name",
                "uid": "MS4-sec-uid",
                "avatar_thumb": "avatar-url",
                "cover": ["cover-1", "cover-2"],
                "media_type": "image",
            },
            normalized,
        )

    def test_normalizes_missing_nested_values_without_errors(self):
        normalized = normalize_aweme(
            {
                "aweme_id": None,
                "desc": None,
                "create_time": None,
                "author": None,
                "video": None,
                "images": None,
            }
        )

        self.assertEqual("", normalized["aweme_id"])
        self.assertEqual("", normalized["desc"])
        self.assertEqual("", normalized["create_time"])
        self.assertEqual("", normalized["nickname"])
        self.assertEqual("", normalized["uid"])
        self.assertEqual("", normalized["avatar_thumb"])
        self.assertEqual([], normalized["cover"])
        self.assertEqual("video", normalized["media_type"])

    def test_normalizes_numeric_aweme_id_and_create_time(self):
        normalized = normalize_aweme(work(123, 0))

        self.assertEqual("123", normalized["aweme_id"])
        self.assertEqual("0", normalized["create_time"])

    def test_rejects_invalid_normalized_scalar_fields(self):
        cases = [
            ("aweme_id", -1, "aweme_id must be a string, nonnegative integer, or null"),
            ("aweme_id", True, "aweme_id must be a string, nonnegative integer, or null"),
            ("aweme_id", 1.5, "aweme_id must be a string, nonnegative integer, or null"),
            ("aweme_id", [], "aweme_id must be a string, nonnegative integer, or null"),
            ("desc", 1, "desc must be a string or null"),
            ("desc", {}, "desc must be a string or null"),
            ("author.nickname", False, "author.nickname must be a string or null"),
            ("author.nickname", [], "author.nickname must be a string or null"),
            ("author.sec_uid", 1, "author.sec_uid must be a string or null"),
            ("author.sec_uid", {}, "author.sec_uid must be a string or null"),
            (
                "create_time",
                -1,
                "create_time must be a string, nonnegative integer, or null",
            ),
            (
                "create_time",
                True,
                "create_time must be a string, nonnegative integer, or null",
            ),
            (
                "create_time",
                1.5,
                "create_time must be a string, nonnegative integer, or null",
            ),
            (
                "create_time",
                [],
                "create_time must be a string, nonnegative integer, or null",
            ),
        ]

        for path, value, message in cases:
            with self.subTest(path=path, value=value):
                raw_work = work("valid-id", 100)
                target = raw_work
                parts = path.split(".")
                for part in parts[:-1]:
                    target = target[part]
                target[parts[-1]] = value
                with self.assertRaisesRegex(
                    UpstreamSchemaError, message.replace(".", r"\.")
                ):
                    normalize_aweme(raw_work)

    def test_rejects_non_mapping_work(self):
        with self.assertRaisesRegex(UpstreamSchemaError, "work must be an object"):
            normalize_aweme("not-a-work")

    def test_rejects_malformed_nested_media_and_author_fields(self):
        cases = [
            ({"author": "author"}, "author must be an object or null"),
            ({"video": "video"}, "video must be an object or null"),
            (
                {"video": {"cover": "cover"}},
                "video.cover must be an object or null",
            ),
            (
                {"video": {"cover": {"url_list": "cover-url"}}},
                "video.cover.url_list must be a list or null",
            ),
            (
                {"author": {"avatar_thumb": "avatar"}},
                "author.avatar_thumb must be an object or null",
            ),
            (
                {"author": {"avatar_thumb": {"url_list": "avatar-url"}}},
                "author.avatar_thumb.url_list must be a list or null",
            ),
            ({"images": "images"}, "images must be a list or null"),
        ]

        for raw_work, message in cases:
            with self.subTest(message=message):
                with self.assertRaisesRegex(UpstreamSchemaError, message.replace(".", r"\.")):
                    normalize_aweme(raw_work)

    def test_rejects_non_string_urls_in_url_lists(self):
        cases = [
            (
                {"video": {"cover": {"url_list": [123]}}},
                "video.cover.url_list must contain only strings",
            ),
            (
                {"author": {"avatar_thumb": {"url_list": [None]}}},
                "author.avatar_thumb.url_list must contain only strings",
            ),
        ]

        for raw_work, message in cases:
            with self.subTest(message=message):
                with self.assertRaisesRegex(UpstreamSchemaError, message.replace(".", r"\.")):
                    normalize_aweme(raw_work)


class IncrementalPaginatorTest(unittest.IsolatedAsyncioTestCase):
    async def test_rejects_invalid_configuration_before_fetch(self):
        cases = [
            ("mode", "unknown"),
            ("known_boundary", 0),
            ("known_boundary", True),
            ("max_pages", 0),
            ("max_pages", 1.5),
            ("empty_page_limit", -1),
            ("max_items", -1),
            ("max_items", False),
            ("watermark", "not-an-epoch"),
            ("watermark", -1),
        ]
        defaults = {
            "known_ids": set(),
            "watermark": None,
            "known_boundary": 20,
            "max_pages": 20,
            "empty_page_limit": 3,
            "mode": "initial",
            "max_items": 0,
        }

        for field, value in cases:
            with self.subTest(field=field, value=value):
                fetch_page = fake_fetch([page([], has_more=0, cursor=0)])
                arguments = dict(defaults)
                arguments[field] = value
                with self.assertRaisesRegex(ValueError, field):
                    await paginate(fetch_page, **arguments)
                self.assertEqual([], fetch_page.requested_cursors)

    async def test_rejects_invalid_upstream_page_schema(self):
        cases = [
            (None, "response must be an object"),
            ({}, "missing required keys"),
            (
                {"aweme_list": [], "has_more": 0},
                "missing required keys: max_cursor",
            ),
            (
                {"aweme_list": {}, "has_more": 0, "max_cursor": 0},
                "aweme_list must be a list or null",
            ),
            (
                {"aweme_list": [], "has_more": 2, "max_cursor": 0},
                "has_more must be boolean or integer 0/1",
            ),
            (
                {"aweme_list": [], "has_more": "0", "max_cursor": 0},
                "has_more must be boolean or integer 0/1",
            ),
            (
                {"aweme_list": [], "has_more": 0, "max_cursor": None},
                "max_cursor must be a nonnegative integer or ASCII decimal string",
            ),
            (
                {"aweme_list": [], "has_more": 0, "max_cursor": []},
                "max_cursor must be a nonnegative integer or ASCII decimal string",
            ),
        ]

        for bad_cursor in (True, 1.5, -1, "", "cursor", "-1", " 1", "\u0661"):
            cases.append(
                (
                    {"aweme_list": [], "has_more": 0, "max_cursor": bad_cursor},
                    "max_cursor must be a nonnegative integer or ASCII decimal string",
                )
            )

        for raw_page, message in cases:
            with self.subTest(message=message):
                with self.assertRaisesRegex(UpstreamSchemaError, message.replace("/", r"\/")):
                    await paginate(
                        fake_fetch([raw_page]), set(), None, 20, 20, 3, "initial"
                    )

    async def test_ascii_decimal_cursor_preserves_string_type_for_next_fetch(self):
        fetch_page = fake_fetch(
            [
                page([], has_more=1, cursor="001"),
                page([], has_more=0, cursor=2),
            ]
        )

        result = await paginate(
            fetch_page, set(), None, 20, 20, 3, "initial"
        )

        self.assertEqual("NO_PUBLIC_WORKS", result["outcome"])
        self.assertEqual([0, "001"], fetch_page.requested_cursors)
        self.assertEqual("2", result["lastCursor"])

    async def test_boolean_has_more_is_accepted(self):
        result = await paginate(
            fake_fetch([page([], has_more=False, cursor=0)]),
            set(),
            None,
            20,
            20,
            3,
            "initial",
        )

        self.assertEqual("NO_PUBLIC_WORKS", result["outcome"])

    async def test_incremental_batch_combines_new_head_and_historical_unknown_items(self):
        newest = [work(f"new-{index}", 300 - index) for index in range(5)]
        known = [work(f"known-{index}", 200 - index) for index in range(20)]
        historical = [work(f"history-{index}", 100 - index) for index in range(15)]
        fetch_page = fake_fetch(
            [
                page(newest + known[:15], has_more=1, cursor=100),
                page(known[15:] + historical, has_more=1, cursor=200),
            ]
        )

        result = await paginate(
            fetch_page,
            known_ids={item["aweme_id"] for item in known},
            watermark=200,
            known_boundary=20,
            max_pages=20,
            empty_page_limit=3,
            mode="incremental",
            max_items=20,
        )

        self.assertEqual("BATCH_LIMIT", result["outcome"])
        self.assertEqual(2, result["pagesFetched"])
        self.assertEqual(
            [item["aweme_id"] for item in newest + historical],
            result["newWorkIds"],
        )
        self.assertEqual(result["newWorkIds"], [item["aweme_id"] for item in result["items"]])
        self.assertEqual(40, result["diagnostics"]["observedCount"])
        self.assertEqual(20, result["diagnostics"]["selectedCount"])

    async def test_incremental_known_prefix_does_not_stop_before_unknown_quota(self):
        result = await paginate(
            fake_fetch(
                [
                    page(
                        [work("known-1", 150), work("known-2", 140)],
                        has_more=1,
                        cursor=10,
                    ),
                    page(
                        [work("new-1", 130), work("new-2", 120)],
                        has_more=1,
                        cursor=20,
                    ),
                ]
            ),
            known_ids={"known-1", "known-2"},
            watermark=150,
            known_boundary=2,
            max_pages=20,
            empty_page_limit=3,
            mode="incremental",
            max_items=2,
        )

        self.assertEqual("BATCH_LIMIT", result["outcome"])
        self.assertEqual(["new-1", "new-2"], result["newWorkIds"])
        self.assertEqual(["new-1", "new-2"], [item["aweme_id"] for item in result["items"]])

    async def test_same_page_duplicate_does_not_consume_unknown_quota(self):
        result = await paginate(
            fake_fetch(
                [
                    page(
                        [
                            work("duplicate", 200),
                            work("duplicate", 100),
                            work("second", 90),
                        ],
                        has_more=1,
                        cursor=10,
                    )
                ]
            ),
            known_ids=set(),
            watermark=None,
            known_boundary=1,
            max_pages=20,
            empty_page_limit=3,
            mode="incremental",
            max_items=2,
        )

        self.assertEqual("BATCH_LIMIT", result["outcome"])
        self.assertEqual(["duplicate", "second"], result["newWorkIds"])
        self.assertEqual(["duplicate", "second"], [item["aweme_id"] for item in result["items"]])

    async def test_cross_page_duplicate_does_not_consume_unknown_quota(self):
        result = await paginate(
            fake_fetch(
                [
                    page([work("duplicate", 200)], has_more=1, cursor=10),
                    page(
                        [work("duplicate", 100), work("second", 90)],
                        has_more=1,
                        cursor=20,
                    ),
                ]
            ),
            known_ids=set(),
            watermark=None,
            known_boundary=1,
            max_pages=20,
            empty_page_limit=3,
            mode="incremental",
            max_items=2,
        )

        self.assertEqual("BATCH_LIMIT", result["outcome"])
        self.assertEqual(2, result["pagesFetched"])
        self.assertEqual(["duplicate", "second"], result["newWorkIds"])

    async def test_empty_work_ids_are_neither_seen_nor_new(self):
        result = await paginate(
            fake_fetch(
                [
                    page(
                        [work("", 200), work("", 100)],
                        has_more=0,
                        cursor=10,
                    )
                ]
            ),
            known_ids={""},
            watermark=150,
            known_boundary=1,
            max_pages=20,
            empty_page_limit=3,
            mode="incremental",
        )

        self.assertEqual("NO_MORE", result["outcome"])
        self.assertEqual([], result["newWorkIds"])
        self.assertEqual([], result["items"])
        self.assertEqual(2, result["diagnostics"]["observedCount"])

    async def test_incremental_watermark_does_not_stop_historical_backfill(self):
        result = await paginate(
            fake_fetch(
                [
                    page(
                        [work("known-1", 300), work("known-2", 250)],
                        has_more=1,
                        cursor=1,
                    ),
                    page([work("known-3", 190), work("historical", 180)], has_more=0, cursor=2),
                ]
            ),
            known_ids={"known-1", "known-2", "known-3"},
            watermark=200,
            known_boundary=2,
            max_pages=20,
            empty_page_limit=3,
            mode="incremental",
            max_items=2,
        )

        self.assertEqual("NO_MORE", result["outcome"])
        self.assertEqual(2, result["pagesFetched"])
        self.assertEqual(["historical"], result["newWorkIds"])

    async def test_invalid_publish_times_do_not_block_unknown_selection(self):
        result = await paginate(
            fake_fetch(
                [
                    page(
                        [
                            work("known-missing", None),
                            work("known-bad", "not-an-epoch"),
                            work("known-unicode-digit", "\u00b2"),
                            work("new-later", 50),
                        ],
                        has_more=0,
                        cursor=10,
                    )
                ]
            ),
            known_ids={
                "known-missing",
                "known-bad",
                "known-unicode-digit",
            },
            watermark=1000,
            known_boundary=2,
            max_pages=20,
            empty_page_limit=3,
            mode="incremental",
        )

        self.assertEqual("NO_MORE", result["outcome"])
        self.assertEqual(["new-later"], result["newWorkIds"])
        self.assertEqual(["new-later"], [item["aweme_id"] for item in result["items"]])
        self.assertEqual(4, result["diagnostics"]["observedCount"])

    async def test_initial_mode_respects_max_items_inside_page(self):
        result = await paginate(
            fake_fetch(
                [
                    page(
                        [work("1", 30), work("2", 20), work("3", 10)],
                        has_more=1,
                        cursor=99,
                    )
                ]
            ),
            known_ids=set(),
            watermark=None,
            known_boundary=20,
            max_pages=20,
            empty_page_limit=3,
            mode="initial",
            max_items=2,
        )

        self.assertEqual("BATCH_LIMIT", result["outcome"])
        self.assertEqual(["1", "2"], result["newWorkIds"])
        self.assertEqual(["1", "2"], [item["aweme_id"] for item in result["items"]])

    async def test_empty_terminal_page_is_no_public_works(self):
        result = await paginate(
            fake_fetch([page([], has_more=0, cursor=0)]),
            set(),
            None,
            20,
            20,
            3,
            "initial",
        )

        self.assertEqual("NO_PUBLIC_WORKS", result["outcome"])
        self.assertEqual(1, result["emptyPages"])

    async def test_terminal_page_after_items_is_no_more(self):
        result = await paginate(
            fake_fetch([page([work("1", 100)], has_more=0, cursor=9)]),
            set(),
            None,
            20,
            20,
            3,
            "incremental",
        )

        self.assertEqual("NO_MORE", result["outcome"])
        self.assertEqual("9", result["lastCursor"])

    async def test_three_consecutive_empty_has_more_pages_are_empty_pagination(self):
        result = await paginate(
            fake_fetch([page([], has_more=1, cursor=i + 1) for i in range(3)]),
            set(),
            None,
            20,
            20,
            3,
            "initial",
        )

        self.assertEqual("EMPTY_PAGINATION", result["outcome"])
        self.assertEqual(3, result["pagesFetched"])
        self.assertEqual(3, result["emptyPages"])

    async def test_nonempty_page_resets_consecutive_empty_page_count(self):
        result = await paginate(
            fake_fetch(
                [
                    page([], has_more=1, cursor=1),
                    page([work("1", 100)], has_more=1, cursor=2),
                    page([], has_more=1, cursor=3),
                    page([], has_more=0, cursor=4),
                ]
            ),
            set(),
            None,
            20,
            20,
            3,
            "audit",
        )

        self.assertEqual("NO_MORE", result["outcome"])
        self.assertEqual(2, result["emptyPages"])

    async def test_null_aweme_list_is_works_unavailable(self):
        result = await paginate(
            fake_fetch([{"aweme_list": None, "has_more": 0, "max_cursor": 0}]),
            set(),
            None,
            20,
            20,
            3,
            "initial",
        )

        self.assertEqual("WORKS_UNAVAILABLE", result["outcome"])

    async def test_null_aweme_list_without_pagination_keys_is_works_unavailable(self):
        result = await paginate(
            fake_fetch([{"aweme_list": None}]),
            set(),
            None,
            20,
            20,
            3,
            "initial",
        )

        self.assertEqual("WORKS_UNAVAILABLE", result["outcome"])
        self.assertEqual("0", result["lastCursor"])
        summary = result["diagnostics"]["lastResponseSummary"]
        self.assertEqual(0, summary["hasMore"])
        self.assertEqual("0", summary["nextCursor"])

    async def test_audit_ignores_known_boundary(self):
        result = await paginate(
            fake_fetch(
                [
                    page([work("known-1", 100)], has_more=1, cursor=1),
                    page([work("new-2", 90)], has_more=0, cursor=2),
                ]
            ),
            {"known-1"},
            100,
            1,
            20,
            3,
            "audit",
        )

        self.assertEqual("NO_MORE", result["outcome"])
        self.assertEqual(["new-2"], result["newWorkIds"])

    async def test_max_page_guard_returns_observed_items(self):
        fetch_page = fake_fetch(
            [
                page([work("1", 200)], has_more=1, cursor=10),
                page([work("2", 100)], has_more=1, cursor=20),
            ]
        )
        result = await paginate(
            fetch_page, set(), None, 20, 2, 3, "incremental"
        )

        self.assertEqual("MAX_PAGE_GUARD", result["outcome"])
        self.assertEqual(2, result["pagesFetched"])
        self.assertEqual("20", result["lastCursor"])
        self.assertEqual([0, 10], fetch_page.requested_cursors)

    async def test_result_has_exact_keys_diagnostics_and_ordered_unique_new_ids(self):
        result = await paginate(
            fake_fetch(
                [
                    page(
                        [work("new-2", 30), work("new-1", 20), work("new-2", 10)],
                        has_more=0,
                        cursor=55,
                    )
                ]
            ),
            set(),
            None,
            20,
            20,
            3,
            "audit",
        )

        self.assertEqual(
            {
                "items",
                "newWorkIds",
                "outcome",
                "pagesFetched",
                "emptyPages",
                "lastCursor",
                "diagnostics",
            },
            set(result),
        )
        self.assertEqual(["new-2", "new-1"], result["newWorkIds"])
        self.assertEqual(
            {
                "page": 1,
                "cursor": "0",
                "nextCursor": "55",
                "hasMore": 0,
                "awemeListState": "list",
                "itemCount": 3,
            },
            result["diagnostics"]["pages"][0],
        )

    async def test_nonempty_response_summary_is_bounded_and_redacted(self):
        works = [work("work-%02d" % index, 100 - index) for index in range(25)]
        raw_page = page(works, has_more=0, cursor=55)
        raw_page.update({"extra_%02d" % index: index for index in range(25)})
        raw_page["sensitive_blob"] = "cookie=must-not-appear"

        result = await paginate(
            fake_fetch([raw_page]), set(), None, 20, 20, 3, "audit"
        )

        summary = result["diagnostics"]["lastResponseSummary"]
        self.assertEqual("dict", summary["responseType"])
        self.assertEqual("nonempty", summary["awemeListState"])
        self.assertEqual(25, summary["itemCount"])
        self.assertEqual(0, summary["hasMore"])
        self.assertEqual("55", summary["nextCursor"])
        self.assertEqual(20, len(summary["topLevelKeys"]))
        self.assertEqual(29, summary["topLevelKeyCount"])
        self.assertEqual(
            ["work-%02d" % index for index in range(20)],
            summary["observedAwemeIds"],
        )
        self.assertNotIn("must-not-appear", json.dumps(result["diagnostics"]))

    async def test_latest_response_summary_describes_empty_page(self):
        result = await paginate(
            fake_fetch(
                [
                    page([work("first", 100)], has_more=1, cursor=1),
                    page([], has_more=0, cursor=2),
                ]
            ),
            set(),
            None,
            20,
            20,
            3,
            "audit",
        )

        summary = result["diagnostics"]["lastResponseSummary"]
        self.assertEqual("empty", summary["awemeListState"])
        self.assertEqual(0, summary["itemCount"])
        self.assertEqual([], summary["observedAwemeIds"])
        self.assertEqual("2", summary["nextCursor"])

    async def test_latest_response_summary_describes_null_list(self):
        result = await paginate(
            fake_fetch(
                [
                    {
                        "aweme_list": None,
                        "has_more": 1,
                        "max_cursor": 7,
                        "raw_payload": "must-not-appear",
                    }
                ]
            ),
            set(),
            None,
            20,
            20,
            3,
            "initial",
        )

        summary = result["diagnostics"]["lastResponseSummary"]
        self.assertEqual("null", summary["awemeListState"])
        self.assertEqual(0, summary["itemCount"])
        self.assertEqual(0, summary["hasMore"])
        self.assertEqual("0", summary["nextCursor"])
        self.assertEqual([], summary["observedAwemeIds"])
        self.assertNotIn("must-not-appear", json.dumps(result["diagnostics"]))


class DouyinCommandIntegrationTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.module_path = SCRIPT_DIR / "douyin.py"

    async def test_uses_profile_precheck_and_direct_page_requests(self):
        profile = {"status_code": 0, "user": {"nickname": "author"}}
        pages = [{
            "status_code": 0,
            "aweme_list": [{
                "aweme_id": "new-1", "desc": "first", "create_time": 200,
                "author": {"nickname": "author", "sec_uid": "MS4-author"},
                "video": {"cover": {"url_list": ["cover"]}},
            }],
            "has_more": 0,
            "max_cursor": 10,
        }]
        module, crawlers = self._load_command_module(profile, pages)

        with tempfile.TemporaryDirectory() as temp_dir:
            known_file = pathlib.Path(temp_dir) / "known.json"
            output_file = pathlib.Path(temp_dir) / "result.json"
            known_file.write_text("[]", encoding="utf-8")
            await module.fetch_douyin_list_incremental(
                "sessionid=secret", "MS4-author", str(known_file), "", 20,
                20, 3, "initial", 1, str(output_file)
            )
            result = json.loads(output_file.read_text(encoding="utf-8"))

        crawler = crawlers[0]
        self.assertEqual("BATCH_LIMIT", result["outcome"])
        self.assertEqual([0], crawler.post_cursors)
        self.assertEqual(["MS4-author"], crawler.profile_ids)
        self.assertEqual(20, crawler.post_params[0].count)

    async def test_deactivated_profile_writes_successful_empty_envelope(self):
        module, crawlers = self._load_command_module(
            {"status_code": 0, "user": {"special_state_info": "账号已注销"}}, []
        )

        with tempfile.TemporaryDirectory() as temp_dir:
            known_file = pathlib.Path(temp_dir) / "known.json"
            output_file = pathlib.Path(temp_dir) / "result.json"
            known_file.write_text("[]", encoding="utf-8")
            await module.fetch_douyin_list_incremental(
                "cookie", "MS4-author", str(known_file), "", 20, 20, 3,
                "incremental", 0, str(output_file)
            )
            result = json.loads(output_file.read_text(encoding="utf-8"))

        crawler = crawlers[0]
        self.assertEqual("ACCOUNT_DEACTIVATED", result["outcome"])
        self.assertEqual([], result["items"])
        self.assertEqual([], crawler.post_cursors)
        self.assertIn("profileStatus", result["diagnostics"])

    async def test_verification_profile_emits_structured_nonzero_failure(self):
        module, _ = self._load_command_module(
            {
                "status_code": 10000,
                "status_msg": "请完成验证码后登录",
                "raw_payload": "must-not-appear",
            },
            [],
        )
        args = self._args()

        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr), self.assertRaises(SystemExit) as raised:
            await module.run_incremental_command(args)

        self.assertNotEqual(0, raised.exception.code)
        payload = self._error_payload(stderr.getvalue())
        self.assertEqual("F2_COOKIE_OR_VERIFY_REQUIRED", payload["errorCode"])
        profile = payload["diagnostics"]["profileStatus"]
        self.assertEqual(10000, profile["statusCode"])
        self.assertIn("raw_payload", profile["topLevelKeys"])
        self.assertNotIn("must-not-appear", stderr.getvalue())
        self.assertNotIn("sessionid=secret", stderr.getvalue())

    async def test_structured_profile_captcha_is_cookie_failure(self):
        module, _ = self._load_command_module(
            {
                "status_code": 0,
                "user": {
                    "nickname": "author",
                    "captcha": {"verify_id": "bounded-only"},
                },
            },
            [],
        )
        args = self._args()

        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr), self.assertRaises(SystemExit):
            await module.run_incremental_command(args)

        payload = self._error_payload(stderr.getvalue())
        self.assertEqual("F2_COOKIE_OR_VERIFY_REQUIRED", payload["errorCode"])

    async def test_structured_page_verification_signals_are_cookie_failures(self):
        signals = (
            {"verify_status": 1},
            {"verify_required": True},
            {"captcha": {"status": "pending"}},
            {"login_status": "expired"},
        )
        for signal in signals:
            with self.subTest(signal=signal):
                module, _ = self._load_command_module(
                    {"status_code": 0, "user": {"nickname": "author"}},
                    [{
                        "status_code": 0,
                        "aweme_list": [],
                        "has_more": 0,
                        "max_cursor": 0,
                        **signal,
                    }],
                )
                args = self._args()

                stderr = io.StringIO()
                with contextlib.redirect_stderr(stderr), self.assertRaises(SystemExit):
                    await module.run_incremental_command(args)

                payload = self._error_payload(stderr.getvalue())
                self.assertEqual(
                    "F2_COOKIE_OR_VERIFY_REQUIRED", payload["errorCode"]
                )

    async def test_structured_verification_success_values_are_not_failures(self):
        module, _ = self._load_command_module(
            {
                "status_code": 0,
                "verify_status": "passed",
                "verify_required": "not_required",
                "user": {
                    "nickname": "author",
                    "captcha": {"status": "success"},
                },
            },
            [{
                "status_code": 0,
                "captcha": {"result": "ok"},
                "verify_status": "pass",
                "verify_required": "no",
                "aweme_list": [],
                "has_more": 0,
                "max_cursor": 0,
            }],
        )
        args = self._args()

        result = await module.fetch_douyin_list_incremental(
            args.cookie, args.sec_user_id, args.known_ids_file,
            args.last_seen_publish_time, args.known_boundary, args.max_pages,
            args.empty_page_limit, args.mode, args.max_items, args.output,
        )

        self.assertEqual("NO_PUBLIC_WORKS", result["outcome"])

    async def test_unknown_structured_verification_strings_are_not_failures(self):
        module, _ = self._load_command_module(
            {
                "status_code": 0,
                "verify_status": "future-state",
                "user": {"nickname": "author"},
            },
            [{
                "status_code": 0,
                "verify_required": "upstream-extension",
                "aweme_list": [],
                "has_more": 0,
                "max_cursor": 0,
            }],
        )
        args = self._args()

        result = await module.fetch_douyin_list_incremental(
            args.cookie, args.sec_user_id, args.known_ids_file,
            args.last_seen_publish_time, args.known_boundary, args.max_pages,
            args.empty_page_limit, args.mode, args.max_items, args.output,
        )

        self.assertEqual("NO_PUBLIC_WORKS", result["outcome"])

    async def test_normal_login_status_is_not_verification_failure(self):
        module, _ = self._load_command_module(
            {
                "status_code": 0,
                "login_status": "login_success",
                "user": {"nickname": "author"},
            },
            [{
                "status_code": 0,
                "login_status": 1,
                "aweme_list": [],
                "has_more": 0,
                "max_cursor": 0,
            }],
        )
        args = self._args()

        result = await module.fetch_douyin_list_incremental(
            args.cookie, args.sec_user_id, args.known_ids_file,
            args.last_seen_publish_time, args.known_boundary, args.max_pages,
            args.empty_page_limit, args.mode, args.max_items, args.output,
        )

        self.assertEqual("NO_PUBLIC_WORKS", result["outcome"])

    async def test_verification_page_emits_cookie_failure_not_schema_failure(self):
        cookie = (
            "sid_guard=guard-secret; sid_tt=tt-secret; "
            "passport_auth_status=auth-secret"
        )
        module, _ = self._load_command_module(
            {"status_code": 0, "user": {"nickname": "author"}},
            [{
                "status_code": 10000,
                "status_msg": "login expired " + cookie,
                "raw_payload": "must-not-appear",
            }],
        )
        args = self._args(cookie)

        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr), self.assertRaises(SystemExit):
            await module.run_incremental_command(args)

        output = stderr.getvalue()
        payload = self._error_payload(output)
        self.assertEqual("F2_COOKIE_OR_VERIFY_REQUIRED", payload["errorCode"])
        page = payload["diagnostics"]["lastPage"]
        self.assertEqual(1, page["page"])
        self.assertEqual("0", page["cursor"])
        self.assertEqual(10000, page["statusCode"])
        self.assertIn("raw_payload", page["topLevelKeys"])
        for secret in (cookie, "guard-secret", "tt-secret", "auth-secret", "must-not-appear"):
            self.assertNotIn(secret, output)

    async def test_short_numeric_cookie_values_do_not_corrupt_diagnostic_json(self):
        cookie = "passport_auth_status=0; flag=1; sid_guard=guard-secret"
        module, _ = self._load_command_module(
            {"status_code": 0, "user": {"nickname": "author"}},
            [{
                "status_code": 10000,
                "status_msg": "login required cookie=" + cookie,
                "aweme_list": [],
                "has_more": 0,
                "max_cursor": 7,
            }],
        )
        args = self._args(cookie)

        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr), self.assertRaises(SystemExit):
            await module.run_incremental_command(args)

        output = stderr.getvalue()
        payload = self._error_payload(output)
        page = payload["diagnostics"]["lastPage"]
        self.assertEqual(10000, page["statusCode"])
        self.assertEqual(1, page["page"])
        self.assertEqual(0, page["itemCount"])
        self.assertEqual(0, page["hasMore"])
        self.assertEqual("7", page["nextCursor"])
        self.assertNotIn(cookie, output)
        self.assertNotIn("passport_auth_status=0", output)
        self.assertNotIn("guard-secret", output)

    async def test_profile_request_verification_exception_is_cookie_failure(self):
        cookie = "sid_guard=guard-secret; passport_auth_status=0"
        module, _ = self._load_command_module(
            RuntimeError("captcha verification required guard-secret"), []
        )
        args = self._args(cookie)

        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr), self.assertRaises(SystemExit):
            await module.run_incremental_command(args)

        output = stderr.getvalue()
        payload = self._error_payload(output)
        self.assertEqual("F2_COOKIE_OR_VERIFY_REQUIRED", payload["errorCode"])
        self.assertEqual("NoneType", payload["diagnostics"]["profileStatus"]["responseType"])
        self.assertNotIn("guard-secret", output)
        self.assertNotIn("captcha verification required", output)

    async def test_page_request_exception_classification_uses_safe_markers(self):
        module, _ = self._load_command_module(
            {"status_code": 0, "user": {"nickname": "author"}},
            [RuntimeError("login expired; raw upstream detail")],
        )
        args = self._args()

        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr), self.assertRaises(SystemExit):
            await module.run_incremental_command(args)

        output = stderr.getvalue()
        payload = self._error_payload(output)
        self.assertEqual("F2_COOKIE_OR_VERIFY_REQUIRED", payload["errorCode"])
        self.assertEqual(1, payload["diagnostics"]["lastPage"]["page"])
        self.assertNotIn("raw upstream detail", output)

    async def test_non_verification_page_request_exception_remains_schema_error(self):
        module, _ = self._load_command_module(
            {"status_code": 0, "user": {"nickname": "author"}},
            [RuntimeError("network timeout with raw detail")],
        )
        args = self._args()

        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr), self.assertRaises(SystemExit):
            await module.run_incremental_command(args)

        output = stderr.getvalue()
        payload = self._error_payload(output)
        self.assertEqual("UPSTREAM_SCHEMA_ERROR", payload["errorCode"])
        self.assertNotIn("network timeout with raw detail", output)

    async def test_top_level_special_state_is_deactivated(self):
        module, crawlers = self._load_command_module(
            {"status_code": 0, "special_state_info": {"type": 1}}, []
        )
        args = self._args()

        result = await module.fetch_douyin_list_incremental(
            args.cookie, args.sec_user_id, args.known_ids_file,
            args.last_seen_publish_time, args.known_boundary, args.max_pages,
            args.empty_page_limit, args.mode, args.max_items, args.output,
        )

        self.assertEqual("ACCOUNT_DEACTIVATED", result["outcome"])
        self.assertEqual([], crawlers[0].post_cursors)

    async def test_page_schema_error_emits_structured_nonzero_failure(self):
        module, _ = self._load_command_module(
            {"status_code": 0, "user": {"nickname": "author"}},
            [{"aweme_list": [], "has_more": 0, "raw_payload": "must-not-appear"}],
        )
        args = self._args()

        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr), self.assertRaises(SystemExit) as raised:
            await module.run_incremental_command(args)

        self.assertNotEqual(0, raised.exception.code)
        payload = self._error_payload(stderr.getvalue())
        self.assertEqual("UPSTREAM_SCHEMA_ERROR", payload["errorCode"])
        page = payload["diagnostics"]["lastPage"]
        self.assertEqual("list", page["awemeListState"])
        self.assertEqual(0, page["itemCount"])
        self.assertNotIn("must-not-appear", stderr.getvalue())

    async def test_nonzero_page_status_without_login_marker_is_schema_error(self):
        module, _ = self._load_command_module(
            {"status_code": 0, "user": {"nickname": "author"}},
            [{"status_code": 9, "status_msg": "upstream denied"}],
        )
        args = self._args()

        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr), self.assertRaises(SystemExit):
            await module.run_incremental_command(args)

        payload = self._error_payload(stderr.getvalue())
        self.assertEqual("UPSTREAM_SCHEMA_ERROR", payload["errorCode"])
        self.assertEqual(9, payload["diagnostics"]["lastPage"]["statusCode"])

    async def test_null_aweme_without_pagination_keys_is_works_unavailable(self):
        module, _ = self._load_command_module(
            {"status_code": 0, "user": {"nickname": "author"}},
            [{"status_code": 0, "aweme_list": None}],
        )
        args = self._args()

        result = await module.fetch_douyin_list_incremental(
            args.cookie, args.sec_user_id, args.known_ids_file,
            args.last_seen_publish_time, args.known_boundary, args.max_pages,
            args.empty_page_limit, args.mode, args.max_items, args.output,
        )

        self.assertEqual("WORKS_UNAVAILABLE", result["outcome"])
        self.assertEqual("0", result["lastCursor"])

    async def test_null_aweme_without_status_code_is_schema_error(self):
        module, _ = self._load_command_module(
            {"status_code": 0, "user": {"nickname": "author"}},
            [{"aweme_list": None}],
        )
        args = self._args()

        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr), self.assertRaises(SystemExit):
            await module.run_incremental_command(args)

        payload = self._error_payload(stderr.getvalue())
        self.assertEqual("UPSTREAM_SCHEMA_ERROR", payload["errorCode"])
        page = payload["diagnostics"]["lastPage"]
        self.assertIsNone(page["statusCode"])
        self.assertEqual("null", page["awemeListState"])
        self.assertIn("aweme_list", page["topLevelKeys"])

    async def test_delays_once_before_second_page_only(self):
        module, _ = self._load_command_module(
            {"status_code": 0, "user": {"nickname": "author"}},
            [
                {
                    "status_code": 0,
                    "aweme_list": [],
                    "has_more": 1,
                    "max_cursor": 10,
                },
                {
                    "status_code": 0,
                    "aweme_list": [],
                    "has_more": 0,
                    "max_cursor": 20,
                },
            ],
        )
        delays = []

        async def record_delay(page_number, delay_seconds):
            delays.append((page_number, delay_seconds))

        module._configured_page_delay_seconds = lambda: 0.75
        module._delay_before_page = record_delay
        args = self._args()

        result = await module.fetch_douyin_list_incremental(
            args.cookie, args.sec_user_id, args.known_ids_file,
            args.last_seen_publish_time, args.known_boundary, args.max_pages,
            args.empty_page_limit, args.mode, args.max_items, args.output,
        )

        self.assertEqual("NO_PUBLIC_WORKS", result["outcome"])
        self.assertEqual([(2, 0.75)], delays)

    async def test_page_delay_environment_is_validated(self):
        module, _ = self._load_command_module({}, [])

        for value in ("-0.1", "10.1", "nan", "not-a-number"):
            with self.subTest(value=value), mock.patch.dict(
                os.environ,
                {"STREAMVAULT_DOUYIN_PAGE_DELAY_SECONDS": value},
            ):
                with self.assertRaises(ValueError):
                    module._configured_page_delay_seconds()

    async def test_nested_item_schema_error_keeps_only_bounded_page_summary(self):
        module, _ = self._load_command_module(
            {"status_code": 0, "user": {"nickname": "author"}},
            [{
                "status_code": 0,
                "aweme_list": [{
                    "aweme_id": "bad-1", "create_time": 100,
                    "author": ["invalid"],
                }],
                "has_more": 0,
                "max_cursor": 7,
                "raw_payload": "must-not-appear",
            }],
        )
        args = self._args()

        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr), self.assertRaises(SystemExit):
            await module.run_incremental_command(args)

        payload = self._error_payload(stderr.getvalue())
        page = payload["diagnostics"]["lastPage"]
        self.assertEqual("UPSTREAM_SCHEMA_ERROR", payload["errorCode"])
        self.assertEqual(1, page["itemCount"])
        self.assertEqual(0, page["hasMore"])
        self.assertEqual("7", page["nextCursor"])
        self.assertNotIn("invalid", stderr.getvalue())
        self.assertNotIn("must-not-appear", stderr.getvalue())

    async def test_actual_argparse_dispatch_wires_incremental_arguments(self):
        module, _ = self._load_command_module({}, [])
        captured = []

        async def capture(args):
            captured.append(args)

        module.run_incremental_command = capture
        argv = [
            "douyin.py", "fetch_douyin_list_incremental",
            "--cookie", "sid_tt=secret",
            "--sec_user_id", "MS4-author",
            "--known_ids_file", "known.json",
            "--last_seen_publish_time", "",
            "--known_boundary", "20",
            "--max_pages", "30",
            "--empty_page_limit", "3",
            "--mode", "audit",
            "--max_items", "41",
            "--output", "result.json",
        ]

        with mock.patch.object(sys, "argv", argv):
            await module.main()

        self.assertEqual(1, len(captured))
        args = captured[0]
        self.assertEqual("", args.last_seen_publish_time)
        self.assertEqual("audit", args.mode)
        self.assertEqual(41, args.max_items)
        self.assertEqual("known.json", args.known_ids_file)
        self.assertEqual("result.json", args.output)

    def _args(self, cookie="sessionid=secret"):
        temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(temp_dir.cleanup)
        known_file = pathlib.Path(temp_dir.name) / "known.json"
        known_file.write_text("[]", encoding="utf-8")
        return argparse.Namespace(
            cookie=cookie, sec_user_id="MS4-author",
            known_ids_file=str(known_file), last_seen_publish_time="",
            known_boundary=20, max_pages=20, empty_page_limit=3,
            mode="incremental", max_items=0,
            output=str(pathlib.Path(temp_dir.name) / "result.json"),
        )

    def _error_payload(self, output):
        prefix = "stream-vault-fetch-error="
        line = next(line for line in output.splitlines() if line.startswith(prefix))
        return json.loads(line[len(prefix):])

    def _load_command_module(self, profile_response, post_responses):
        crawler_instances = []

        class FakeUserProfile:
            def __init__(self, sec_user_id):
                self.sec_user_id = sec_user_id

        class FakeUserPost:
            def __init__(self, max_cursor, count, sec_user_id):
                self.max_cursor = max_cursor
                self.count = count
                self.sec_user_id = sec_user_id

        class FakeCrawler:
            def __init__(self, kwargs):
                self.kwargs = kwargs
                self.profile_ids = []
                self.post_cursors = []
                self.post_params = []
                self._pages = list(post_responses)
                crawler_instances.append(self)

            async def __aenter__(self):
                return self

            async def __aexit__(self, *_):
                return None

            async def fetch_user_profile(self, params):
                self.profile_ids.append(params.sec_user_id)
                if isinstance(profile_response, Exception):
                    raise profile_response
                return profile_response

            async def fetch_user_post(self, params):
                self.post_cursors.append(params.max_cursor)
                self.post_params.append(params)
                response = self._pages.pop(0)
                if isinstance(response, Exception):
                    raise response
                return response

        fake_modules = {
            "f2": types.ModuleType("f2"),
            "f2.apps": types.ModuleType("f2.apps"),
            "f2.apps.douyin": types.ModuleType("f2.apps.douyin"),
            "f2.apps.douyin.handler": types.ModuleType("f2.apps.douyin.handler"),
            "f2.apps.douyin.crawler": types.ModuleType("f2.apps.douyin.crawler"),
            "f2.apps.douyin.model": types.ModuleType("f2.apps.douyin.model"),
            "f2.log": types.ModuleType("f2.log"),
            "f2.log.logger": types.ModuleType("f2.log.logger"),
        }
        fake_modules["f2.apps.douyin.handler"].DouyinHandler = object
        fake_modules["f2.apps.douyin.crawler"].DouyinCrawler = FakeCrawler
        fake_modules["f2.apps.douyin.model"].UserProfile = FakeUserProfile
        fake_modules["f2.apps.douyin.model"].UserPost = FakeUserPost
        fake_modules["f2.log.logger"].logger = types.SimpleNamespace(
            setLevel=lambda *_: None
        )

        saved_modules = {name: sys.modules.get(name) for name in fake_modules}
        sys.modules.update(fake_modules)
        try:
            module_name = "douyin_command_under_test"
            spec = importlib.util.spec_from_file_location(module_name, self.module_path)
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
        finally:
            for name, previous in saved_modules.items():
                if previous is None:
                    sys.modules.pop(name, None)
                else:
                    sys.modules[name] = previous

        return module, crawler_instances


if __name__ == "__main__":
    unittest.main()
