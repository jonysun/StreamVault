"""Pure pagination policy for incremental Douyin author-work fetching."""


_SUMMARY_KEY_LIMIT = 20
_SUMMARY_ID_LIMIT = 20
_SUMMARY_KEY_LENGTH = 64
_SUMMARY_VALUE_LENGTH = 128
_VALID_MODES = ("initial", "incremental", "audit")
_REQUIRED_PAGINATION_KEYS = ("has_more", "max_cursor")


class UpstreamSchemaError(ValueError):
    """Raised when an upstream response does not match the expected JSON shape."""


def _bounded_text(value, limit):
    return str(value)[:limit]


def _optional_object(value, path):
    if value is None:
        return {}
    if not isinstance(value, dict):
        raise UpstreamSchemaError(f"{path} must be an object or null")
    return value


def _optional_url_list(value, path):
    if value is None:
        return []
    if not isinstance(value, list):
        raise UpstreamSchemaError(f"{path} must be a list or null")
    if any(not isinstance(url, str) for url in value):
        raise UpstreamSchemaError(f"{path} must contain only strings")
    return value


def _normalize_aweme_id(value):
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    if isinstance(value, int) and not isinstance(value, bool) and value >= 0:
        return str(value)
    raise UpstreamSchemaError(
        "aweme_id must be a string, nonnegative integer, or null"
    )


def _normalize_optional_string(value, path):
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    raise UpstreamSchemaError(f"{path} must be a string or null")


def _normalize_create_time(value):
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    if isinstance(value, int) and not isinstance(value, bool) and value >= 0:
        return str(value)
    raise UpstreamSchemaError(
        "create_time must be a string, nonnegative integer, or null"
    )


def _parse_nonnegative_epoch(value):
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value if value >= 0 else None
    if isinstance(value, str):
        text = value.strip()
        if text.isascii() and text.isdecimal():
            return int(text)
    return None


def _validate_config(
    watermark,
    known_boundary,
    max_pages,
    empty_page_limit,
    mode,
    max_items,
    backfill_cursor,
    backfill_complete,
    backfill_verifying,
    backfill_clean_passes,
):
    if not isinstance(mode, str) or mode not in _VALID_MODES:
        raise ValueError("mode must be one of: initial, incremental, audit")

    for name, value in (
        ("known_boundary", known_boundary),
        ("max_pages", max_pages),
        ("empty_page_limit", empty_page_limit),
    ):
        if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
            raise ValueError(f"{name} must be a positive integer")

    if isinstance(max_items, bool) or not isinstance(max_items, int) or max_items < 0:
        raise ValueError("max_items must be a nonnegative integer")

    if backfill_cursor not in (None, "") and _parse_nonnegative_epoch(backfill_cursor) is None:
        raise ValueError("backfill_cursor must be a nonnegative integer epoch or null")
    for name, value in (
        ("backfill_complete", backfill_complete),
        ("backfill_verifying", backfill_verifying),
    ):
        if not isinstance(value, bool):
            raise ValueError(f"{name} must be a boolean")
    if (
        isinstance(backfill_clean_passes, bool)
        or not isinstance(backfill_clean_passes, int)
        or backfill_clean_passes < 0
        or backfill_clean_passes > 2
    ):
        raise ValueError("backfill_clean_passes must be an integer from 0 to 2")
    if (
        (backfill_complete and (backfill_verifying or backfill_clean_passes != 2))
        or (backfill_verifying and backfill_clean_passes >= 2)
        or (
            not backfill_complete
            and not backfill_verifying
            and backfill_clean_passes != 0
        )
    ):
        raise ValueError("backfill state is inconsistent")

    if watermark is None:
        return None
    parsed_watermark = _parse_nonnegative_epoch(watermark)
    if parsed_watermark is None:
        raise ValueError("watermark must be a nonnegative integer epoch or null")
    return parsed_watermark


def _validate_page(raw, current_cursor):
    if not isinstance(raw, dict):
        raise UpstreamSchemaError("response must be an object")

    if "aweme_list" not in raw:
        if raw.get("status_code") == 0:
            return [], 0, current_cursor
        raise UpstreamSchemaError("missing required keys: aweme_list")

    aweme_list = raw["aweme_list"]
    if aweme_list is None:
        return None, 0, current_cursor
    if not isinstance(aweme_list, list):
        raise UpstreamSchemaError("aweme_list must be a list or null")

    missing_keys = [key for key in _REQUIRED_PAGINATION_KEYS if key not in raw]
    if missing_keys:
        raise UpstreamSchemaError(
            "missing required keys: " + ", ".join(missing_keys)
        )

    raw_has_more = raw["has_more"]
    if isinstance(raw_has_more, bool):
        has_more = int(raw_has_more)
    elif isinstance(raw_has_more, int) and raw_has_more in (0, 1):
        has_more = raw_has_more
    else:
        raise UpstreamSchemaError("has_more must be boolean or integer 0/1")

    next_cursor = raw["max_cursor"]
    valid_integer_cursor = (
        isinstance(next_cursor, int)
        and not isinstance(next_cursor, bool)
        and next_cursor >= 0
    )
    valid_string_cursor = (
        isinstance(next_cursor, str)
        and bool(next_cursor)
        and next_cursor.isascii()
        and next_cursor.isdecimal()
    )
    if not valid_integer_cursor and not valid_string_cursor:
        raise UpstreamSchemaError(
            "max_cursor must be a nonnegative integer or ASCII decimal string"
        )

    return aweme_list, has_more, next_cursor


def _response_summary(raw, aweme_list, has_more, next_cursor):
    if isinstance(raw, dict):
        top_level_keys = sorted(
            _bounded_text(key, _SUMMARY_KEY_LENGTH) for key in raw.keys()
        )
        top_level_key_count = len(raw)
    else:
        top_level_keys = []
        top_level_key_count = 0

    if aweme_list is None:
        list_state = "null"
    elif isinstance(aweme_list, list):
        list_state = "nonempty" if aweme_list else "empty"
    else:
        list_state = "invalid"

    observed_ids = []
    seen_ids = set()
    if isinstance(aweme_list, list):
        for aweme in aweme_list:
            if not isinstance(aweme, dict):
                continue
            work_id = _bounded_text(
                aweme.get("aweme_id") or "", _SUMMARY_VALUE_LENGTH
            )
            if work_id and work_id not in seen_ids:
                observed_ids.append(work_id)
                seen_ids.add(work_id)
            if len(observed_ids) >= _SUMMARY_ID_LIMIT:
                break

    return {
        "responseType": type(raw).__name__,
        "topLevelKeys": top_level_keys[:_SUMMARY_KEY_LIMIT],
        "topLevelKeyCount": top_level_key_count,
        "awemeListState": list_state,
        "itemCount": len(aweme_list) if isinstance(aweme_list, list) else 0,
        "hasMore": has_more,
        "nextCursor": _bounded_text(next_cursor, _SUMMARY_VALUE_LENGTH),
        "observedAwemeIds": observed_ids,
        "compatibilityReason": (
            "STATUS_ONLY_EMPTY_PAGE"
            if isinstance(raw, dict)
            and raw.get("status_code") == 0
            and "aweme_list" not in raw
            else None
        ),
    }


def normalize_aweme(aweme):
    if not isinstance(aweme, dict):
        raise UpstreamSchemaError("work must be an object")

    author = _optional_object(aweme.get("author"), "author")
    video = _optional_object(aweme.get("video"), "video")
    video_cover = _optional_object(video.get("cover"), "video.cover")
    avatar = _optional_object(author.get("avatar_thumb"), "author.avatar_thumb")
    cover = _optional_url_list(video_cover.get("url_list"), "video.cover.url_list")
    avatar_urls = _optional_url_list(
        avatar.get("url_list"), "author.avatar_thumb.url_list"
    )
    images = aweme.get("images")
    if images is None:
        images = []
    elif not isinstance(images, list):
        raise UpstreamSchemaError("images must be a list or null")

    aweme_id = _normalize_aweme_id(aweme.get("aweme_id"))
    desc = _normalize_optional_string(aweme.get("desc"), "desc")
    create_time = _normalize_create_time(aweme.get("create_time"))
    nickname = _normalize_optional_string(author.get("nickname"), "author.nickname")
    uid = _normalize_optional_string(author.get("sec_uid"), "author.sec_uid")
    return {
        "aweme_id": aweme_id,
        "desc": desc,
        "create_time": create_time,
        "nickname": nickname,
        "uid": uid,
        "avatar_thumb": avatar_urls[0] if avatar_urls else "",
        "cover": cover,
        "media_type": "image" if images else "video",
    }


def envelope(
    items,
    new_work_ids,
    outcome,
    pages_fetched,
    empty_pages,
    last_cursor,
    diagnostics,
    backfill_cursor="0",
    backfill_complete=False,
    backfill_verifying=False,
    backfill_clean_passes=0,
):
    return {
        "items": items,
        "newWorkIds": list(dict.fromkeys(new_work_ids)),
        "outcome": outcome,
        "pagesFetched": pages_fetched,
        "emptyPages": empty_pages,
        "lastCursor": str(last_cursor),
        "backfillCursor": str(backfill_cursor),
        "backfillComplete": bool(backfill_complete),
        "backfillVerifying": bool(backfill_verifying),
        "backfillCleanPasses": int(backfill_clean_passes),
        "diagnostics": diagnostics,
    }


async def paginate(
    fetch_page,
    known_ids,
    watermark,
    known_boundary,
    max_pages,
    empty_page_limit,
    mode,
    max_items=0,
    backfill_cursor=None,
    backfill_complete=False,
    backfill_verifying=False,
    backfill_clean_passes=0,
):
    parsed_watermark = _validate_config(
        watermark,
        known_boundary,
        max_pages,
        empty_page_limit,
        mode,
        max_items,
        backfill_cursor,
        backfill_complete,
        backfill_verifying,
        backfill_clean_passes,
    )
    observed = []
    selected = []
    new_ids = []
    empty_pages = 0
    seen_work_ids = set()
    stored_backfill_cursor = (
        _parse_nonnegative_epoch(backfill_cursor)
        if backfill_cursor not in (None, "")
        else 0
    )
    progress_cursor = stored_backfill_cursor
    progress_complete = backfill_complete
    progress_verifying = backfill_verifying
    progress_clean_passes = backfill_clean_passes
    phase = (
        "AUDIT"
        if mode == "audit"
        else "BACKFILL"
        if mode == "initial"
        else "VERIFY"
        if backfill_verifying
        else "HEAD"
    )
    cursor = stored_backfill_cursor if phase in ("BACKFILL", "VERIFY") else 0
    known_streak = 0
    head_boundary_reached = False
    diagnostics = {
        "pages": [],
        "lastResponseSummary": None,
        "phase": phase,
        "headPagesFetched": 0,
        "backfillPagesFetched": 0,
        "verifyPagesFetched": 0,
    }

    def finish(outcome, pages_fetched, last_cursor):
        diagnostics["observedCount"] = len(observed)
        diagnostics["selectedCount"] = len(selected)
        diagnostics["phase"] = phase
        return envelope(
            observed if mode == "audit" or phase == "VERIFY" else selected,
            new_ids,
            outcome,
            pages_fetched,
            empty_pages,
            last_cursor,
            diagnostics,
            progress_cursor,
            progress_complete,
            progress_verifying,
            progress_clean_passes,
        )

    for page_number in range(1, max_pages + 1):
        request_cursor = cursor
        raw = await fetch_page(cursor)
        aweme_list, has_more, next_cursor = _validate_page(raw, cursor)
        if phase == "HEAD":
            diagnostics["headPagesFetched"] += 1
        elif phase == "BACKFILL":
            diagnostics["backfillPagesFetched"] += 1
        elif phase == "VERIFY":
            diagnostics["verifyPagesFetched"] += 1
        diagnostics["lastResponseSummary"] = _response_summary(
            raw, aweme_list, has_more, next_cursor
        )
        diagnostics["pages"].append(
            {
                "page": page_number,
                "cursor": str(cursor),
                "nextCursor": str(next_cursor),
                "hasMore": has_more,
                "awemeListState": "null" if aweme_list is None else "list",
                "itemCount": 0 if not aweme_list else len(aweme_list),
                "phase": phase,
                "compatibilityReason": diagnostics["lastResponseSummary"].get(
                    "compatibilityReason"
                ),
            }
        )

        if aweme_list is None:
            return finish("WORKS_UNAVAILABLE", page_number, next_cursor)

        if not aweme_list:
            empty_pages += 1
            if not has_more:
                if (
                    phase == "HEAD"
                    and mode == "incremental"
                    and not progress_complete
                    and stored_backfill_cursor != 0
                ):
                    phase = "BACKFILL"
                    cursor = stored_backfill_cursor
                    continue
                if phase in ("HEAD", "BACKFILL") and mode != "audit":
                    progress_cursor = 0
                    progress_complete = False
                    progress_verifying = True
                    progress_clean_passes = 0
                elif phase == "VERIFY":
                    if selected:
                        progress_cursor = 0
                        progress_complete = False
                        progress_verifying = False
                        progress_clean_passes = 0
                    else:
                        progress_cursor = 0
                        progress_clean_passes = min(2, progress_clean_passes + 1)
                        progress_complete = progress_clean_passes >= 2
                        progress_verifying = not progress_complete
                outcome = "NO_PUBLIC_WORKS" if not observed else "NO_MORE"
                return finish(outcome, page_number, next_cursor)
            if empty_pages >= empty_page_limit:
                return finish("EMPTY_PAGINATION", page_number, next_cursor)
            cursor = next_cursor
            continue

        empty_pages = 0
        for raw_work in aweme_list:
            item = normalize_aweme(raw_work)
            work_id = item["aweme_id"]
            known = bool(work_id) and (
                work_id in known_ids or work_id in seen_work_ids
            )
            item["knownAtFetch"] = known
            observed.append(item)
            if work_id:
                seen_work_ids.add(work_id)

            if not known:
                known_streak = 0
                if work_id:
                    new_ids.append(work_id)
                    selected.append(item)
            elif phase == "HEAD" and parsed_watermark is not None:
                publish_time = _parse_nonnegative_epoch(item["create_time"])
                if publish_time is not None and publish_time <= parsed_watermark:
                    known_streak += 1
                    if known_streak >= known_boundary:
                        head_boundary_reached = True
                else:
                    known_streak = 0

            if mode in ("initial", "incremental") and max_items > 0 and len(selected) >= max_items:
                if phase == "BACKFILL":
                    progress_cursor = request_cursor
                    progress_complete = False
                    progress_verifying = False
                    progress_clean_passes = 0
                elif phase == "VERIFY":
                    progress_cursor = request_cursor
                    progress_complete = False
                    progress_verifying = False
                    progress_clean_passes = 0
                return finish("BATCH_LIMIT", page_number, next_cursor)

        if phase == "HEAD" and head_boundary_reached:
            if progress_complete:
                return finish("KNOWN_BOUNDARY", page_number, next_cursor)
            phase = "BACKFILL"
            cursor = stored_backfill_cursor
            continue

        if not has_more:
            if phase in ("HEAD", "BACKFILL") and mode != "audit":
                progress_cursor = 0
                progress_complete = False
                progress_verifying = True
                progress_clean_passes = 0
            elif phase == "VERIFY":
                if selected:
                    progress_cursor = 0
                    progress_complete = False
                    progress_verifying = False
                    progress_clean_passes = 0
                else:
                    progress_cursor = 0
                    progress_clean_passes = min(2, progress_clean_passes + 1)
                    progress_complete = progress_clean_passes >= 2
                    progress_verifying = not progress_complete
            return finish("NO_MORE", page_number, next_cursor)
        cursor = next_cursor

    if phase == "BACKFILL" and mode != "audit":
        progress_cursor = cursor
        progress_complete = False
        progress_verifying = False
        progress_clean_passes = 0
    elif phase == "VERIFY":
        if selected:
            progress_cursor = 0
            progress_complete = False
            progress_verifying = False
            progress_clean_passes = 0
        else:
            progress_cursor = cursor
            progress_complete = False
            progress_verifying = True
    return finish("MAX_PAGE_GUARD", max_pages, cursor)
