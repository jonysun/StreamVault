"""Pure pagination policy for incremental Douyin author-work fetching."""


_SUMMARY_KEY_LIMIT = 20
_SUMMARY_ID_LIMIT = 20
_SUMMARY_KEY_LENGTH = 64
_SUMMARY_VALUE_LENGTH = 128
_VALID_MODES = ("initial", "incremental", "audit")
_REQUIRED_PAGE_KEYS = ("aweme_list", "has_more", "max_cursor")


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
    watermark, known_boundary, max_pages, empty_page_limit, mode, max_items
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

    if watermark is None:
        return None
    parsed_watermark = _parse_nonnegative_epoch(watermark)
    if parsed_watermark is None:
        raise ValueError("watermark must be a nonnegative integer epoch or null")
    return parsed_watermark


def _validate_page(raw):
    if not isinstance(raw, dict):
        raise UpstreamSchemaError("response must be an object")

    missing_keys = [key for key in _REQUIRED_PAGE_KEYS if key not in raw]
    if missing_keys:
        raise UpstreamSchemaError(
            "missing required keys: " + ", ".join(missing_keys)
        )

    aweme_list = raw["aweme_list"]
    if aweme_list is not None and not isinstance(aweme_list, list):
        raise UpstreamSchemaError("aweme_list must be a list or null")

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
):
    return {
        "items": items,
        "newWorkIds": list(dict.fromkeys(new_work_ids)),
        "outcome": outcome,
        "pagesFetched": pages_fetched,
        "emptyPages": empty_pages,
        "lastCursor": str(last_cursor),
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
):
    watermark_epoch = _validate_config(
        watermark,
        known_boundary,
        max_pages,
        empty_page_limit,
        mode,
        max_items,
    )
    observed = []
    new_ids = []
    cursor = 0
    known_streak = 0
    empty_pages = 0
    seen_work_ids = set()
    diagnostics = {"pages": [], "lastResponseSummary": None}

    for page_number in range(1, max_pages + 1):
        raw = await fetch_page(cursor)
        aweme_list, has_more, next_cursor = _validate_page(raw)
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
            }
        )

        if aweme_list is None:
            return envelope(
                observed,
                new_ids,
                "WORKS_UNAVAILABLE",
                page_number,
                empty_pages,
                next_cursor,
                diagnostics,
            )

        if not aweme_list:
            empty_pages += 1
            if not has_more:
                outcome = "NO_PUBLIC_WORKS" if not observed else "NO_MORE"
                return envelope(
                    observed,
                    new_ids,
                    outcome,
                    page_number,
                    empty_pages,
                    next_cursor,
                    diagnostics,
                )
            if empty_pages >= empty_page_limit:
                return envelope(
                    observed,
                    new_ids,
                    "EMPTY_PAGINATION",
                    page_number,
                    empty_pages,
                    next_cursor,
                    diagnostics,
                )
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

            if known:
                known_streak += 1
            else:
                known_streak = 0
                if work_id:
                    new_ids.append(work_id)

            if mode == "initial" and max_items > 0 and len(observed) >= max_items:
                return envelope(
                    observed,
                    new_ids,
                    "INITIAL_LIMIT",
                    page_number,
                    empty_pages,
                    next_cursor,
                    diagnostics,
                )

            publish_time = _parse_nonnegative_epoch(item["create_time"])
            if (
                mode == "incremental"
                and known_streak >= known_boundary
                and watermark_epoch is not None
                and publish_time is not None
                and publish_time <= watermark_epoch
            ):
                return envelope(
                    observed,
                    new_ids,
                    "KNOWN_BOUNDARY",
                    page_number,
                    empty_pages,
                    next_cursor,
                    diagnostics,
                )

        if not has_more:
            return envelope(
                observed,
                new_ids,
                "NO_MORE",
                page_number,
                empty_pages,
                next_cursor,
                diagnostics,
            )
        cursor = next_cursor

    return envelope(
        observed,
        new_ids,
        "MAX_PAGE_GUARD",
        max_pages,
        empty_pages,
        cursor,
        diagnostics,
    )
