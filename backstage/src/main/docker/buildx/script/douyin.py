import warnings
warnings.simplefilter("ignore")

import asyncio
import sys
import argparse
from f2.apps.douyin.handler import DouyinHandler
from f2.apps.douyin.crawler import DouyinCrawler
from f2.apps.douyin.model import UserPost, UserProfile
from f2.apps.douyin.utils import XBogusManager
from f2.log.logger import logger
import json
import math
import os
import re

from douyin_incremental import UpstreamSchemaError, envelope, paginate

# 解决 GBK 编码问题
sys.stdout.reconfigure(encoding="utf-8")
logger.setLevel('ERROR')

_COOKIE_OR_VERIFY_MARKERS = (
    "captcha", "verify", "verification", "login", "log in", "challenge",
    "验证码", "验证", "请登录", "登录后",
)
_DEACTIVATED_MARKERS = (
    "deactivated", "cancelled", "canceled", "account unavailable",
    "账号已注销", "帐号已注销", "用户已注销", "账号不存在",
)
_BANNED_ACCOUNT_MARKERS = (
    "account banned", "account suspended", "user banned",
    "账号已封禁", "账号被封禁", "帐号已封禁", "帐号被封禁",
    "用户已封禁", "用户被封禁", "该用户已被封禁",
)
_SENSITIVE_COOKIE_PATTERN = re.compile(
    r"(?i)(sessionid(?:_ss)?|msToken|ttwid|odin_tt|passport_csrf_token)=([^;\s]+)"
)
_PAGE_DELAY_ENV = "STREAMVAULT_DOUYIN_PAGE_DELAY_SECONDS"
_DEFAULT_PAGE_DELAY_SECONDS = 0.75
_MAX_PAGE_DELAY_SECONDS = 10.0
_LOGIN_REQUIRED_VALUES = {
    "0", "false", "expired", "not_login", "not-logged-in", "not logged in",
    "required", "login_required", "login-required", "need_login",
    "unauthenticated",
}
_VERIFICATION_SUCCESS_VALUES = {
    "success", "passed", "pass", "ok", "no", "not_required", "0", "false",
}
_VERIFICATION_FAILURE_VALUES = {
    "required", "pending", "challenge", "failed", "fail", "yes", "true", "1",
}
_VERIFICATION_RESULT_FIELDS = (
    "status", "result", "state", "verify_status", "verify_result", "required",
)


class FetchCommandError(RuntimeError):
    def __init__(self, error_code, safe_message, diagnostics=None, exception_type=None):
        super().__init__(safe_message)
        self.error_code = error_code
        self.safe_message = safe_message
        self.diagnostics = diagnostics or {}
        self.exception_type = exception_type or type(self).__name__


class CookieOrVerifyRequired(FetchCommandError):
    def __init__(self, diagnostics):
        super().__init__(
            "F2_COOKIE_OR_VERIFY_REQUIRED",
            "Douyin login or verification is required",
            diagnostics,
        )


class InvalidAuthorIdError(FetchCommandError):
    def __init__(self, diagnostics):
        super().__init__(
            "INVALID_AUTHOR_ID",
            "Douyin author identifier is invalid; update the task source",
            diagnostics,
        )


class UpstreamCommandSchemaError(FetchCommandError):
    def __init__(self, safe_message, diagnostics, exception_type="UpstreamSchemaError"):
        super().__init__(
            "UPSTREAM_SCHEMA_ERROR",
            safe_message,
            diagnostics,
            exception_type,
        )


class UpstreamFetchError(FetchCommandError):
    pass


class UpstreamRequestEvidenceError(RuntimeError):
    """F2-compatible request failure that keeps the last HTTP evidence."""

    def __init__(self, message, request_evidence):
        super().__init__(message)
        self.request_evidence = request_evidence or {}


def _request_evidence(response=None, *, attempt=0, error_kind="", error=None):
    content = b""
    if response is not None:
        try:
            content = response.content or b""
        except Exception:
            content = b""
    headers = getattr(response, "headers", {}) or {}
    status_code = getattr(response, "status_code", None)
    return {
        "attempt": int(attempt) + 1,
        "responseType": type(response).__name__ if response is not None else "NoneType",
        "statusCode": status_code,
        "bodyLength": len(content),
        "bodyEmpty": not bool(content.strip()),
        "contentType": _bounded_status_text(headers.get("content-type", "")),
        "errorKind": error_kind or None,
        "exceptionType": type(error).__name__ if error is not None else None,
    }


class InstrumentedDouyinCrawler(DouyinCrawler):
    """Preserve response evidence lost by F2's generic GET helper."""

    EMPTY_RESPONSE_CONFIRMATION_ATTEMPTS = 2

    def __init__(self, kwargs):
        super().__init__(kwargs)
        self.last_request_evidence = None

    async def _fetch_get_json(self, endpoint):
        # F2 drops the status code when an empty response exhausts retries. Keep
        # the same retry count while retaining status/body/transport evidence.
        import httpx

        for attempt in range(self._max_retries):
            try:
                response = await self.aclient.get(endpoint, follow_redirects=True)
                evidence = _request_evidence(response, attempt=attempt)
                self.last_request_evidence = evidence
                content = response.content or b""
                if not content.strip():
                    evidence["errorKind"] = "EMPTY_RESPONSE"
                    evidence["exceptionType"] = "APIRetryExhaustedError"
                    empty_attempt_limit = min(
                        self._max_retries,
                        self.EMPTY_RESPONSE_CONFIRMATION_ATTEMPTS,
                    )
                    if attempt >= empty_attempt_limit - 1:
                        raise UpstreamRequestEvidenceError(
                            "Douyin endpoint returned repeated empty responses",
                            evidence,
                        )
                    await asyncio.sleep(self._timeout)
                    continue
                try:
                    response.raise_for_status()
                except httpx.HTTPStatusError as error:
                    evidence["errorKind"] = "HTTP_STATUS"
                    evidence["exceptionType"] = type(error).__name__
                    raise UpstreamRequestEvidenceError(
                        "Douyin endpoint returned HTTP status "
                        + str(response.status_code),
                        evidence,
                    ) from error
                try:
                    return response.json()
                except (json.JSONDecodeError, UnicodeDecodeError) as error:
                    evidence["errorKind"] = "INVALID_JSON"
                    evidence["exceptionType"] = type(error).__name__
                    raise UpstreamRequestEvidenceError(
                        "Douyin endpoint returned invalid JSON", evidence
                    ) from error
            except UpstreamRequestEvidenceError:
                raise
            except httpx.TimeoutException as error:
                evidence = _request_evidence(
                    attempt=attempt, error_kind="TIMEOUT", error=error
                )
                self.last_request_evidence = evidence
                raise UpstreamRequestEvidenceError(
                    "Douyin endpoint request timed out", evidence
                ) from error
            except httpx.ProxyError as error:
                evidence = _request_evidence(
                    attempt=attempt, error_kind="NETWORK_ERROR", error=error
                )
                self.last_request_evidence = evidence
                raise UpstreamRequestEvidenceError(
                    "Douyin endpoint proxy connection failed", evidence
                ) from error
            except httpx.NetworkError as error:
                evidence = _request_evidence(
                    attempt=attempt, error_kind="NETWORK_ERROR", error=error
                )
                self.last_request_evidence = evidence
                raise UpstreamRequestEvidenceError(
                    "Douyin endpoint network connection failed", evidence
                ) from error
            except httpx.RequestError as error:
                evidence = _request_evidence(
                    attempt=attempt, error_kind="NETWORK_ERROR", error=error
                )
                self.last_request_evidence = evidence
                raise UpstreamRequestEvidenceError(
                    "Douyin endpoint request failed", evidence
                ) from error

        raise UpstreamRequestEvidenceError(
            "Douyin endpoint request exhausted without a response",
            self.last_request_evidence,
        )


def _last_request_evidence(diagnostics):
    if not isinstance(diagnostics, dict):
        return {}
    candidates = [diagnostics.get("lastRequest")]
    for section_name in ("lastPage", "profileStatus"):
        section = diagnostics.get(section_name)
        if isinstance(section, dict):
            candidates.append(section.get("lastRequest"))
    return next(
        (candidate for candidate in candidates if isinstance(candidate, dict)),
        {},
    )


def _request_error(error, safe_message, diagnostics):
    request_evidence = _last_request_evidence(diagnostics)
    exception_type = request_evidence.get("exceptionType") or type(error).__name__
    lowered_type = str(exception_type).lower()
    raw_evidence = " ".join(
        value
        for value in (
            json.dumps(diagnostics or {}, ensure_ascii=False, sort_keys=True),
            str(error),
        )
        if value
    ).lower()
    evidence = raw_evidence
    status_code = request_evidence.get("statusCode")
    error_kind = request_evidence.get("errorKind")
    if status_code in (403, 429, "403", "429"):
        return UpstreamFetchError(
            "F2_UPSTREAM_RATE_LIMIT", safe_message, diagnostics, exception_type
        )
    if status_code in (401, "401"):
        return CookieOrVerifyRequired(diagnostics)
    if error_kind == "TIMEOUT" or status_code in (408, "408"):
        return UpstreamFetchError(
            "F2_UPSTREAM_TIMEOUT", safe_message, diagnostics, exception_type
        )
    if error_kind == "NETWORK_ERROR" or status_code in (503, "503"):
        return UpstreamFetchError(
            "F2_UPSTREAM_UNAVAILABLE", safe_message, diagnostics, exception_type
        )
    if error_kind == "EMPTY_RESPONSE":
        return UpstreamFetchError(
            "F2_UPSTREAM_SOFT_BLOCK",
            "Douyin author-work endpoint repeatedly returned an empty HTTP response",
            diagnostics,
            exception_type,
        )

    explicit_risk = any(
        marker in evidence
        for marker in ("http 403", "http 429", "status 403", "status 429")
    )
    explicit_risk = explicit_risk or bool(
        re.search(r"(?:statuscode|status_code)\D{0,8}(?:403|429)", evidence)
    )
    explicit_risk = explicit_risk or any(
        marker in evidence
        for marker in ("too many requests", "rate limit", "rate_limit", "risk control")
    )
    if explicit_risk:
        return UpstreamFetchError(
            "F2_UPSTREAM_RATE_LIMIT",
            safe_message,
            diagnostics,
            exception_type,
        )
    is_retry_exhausted = "retryexhausted" in lowered_type
    if (
        "timeout" in lowered_type
        or isinstance(error, TimeoutError)
        or any(marker in raw_evidence for marker in ("timeout", "timed out", "time out"))
    ):
        return UpstreamFetchError(
            "F2_UPSTREAM_TIMEOUT", safe_message, diagnostics, exception_type
        )
    if (
        is_retry_exhausted
        or "nonetype" in lowered_type
        or any(
            marker in evidence
            for marker in (
                "empty response",
                "empty responses",
                "connection",
                "network",
                "unavailable",
            )
        )
    ):
        return UpstreamFetchError(
            "F2_UPSTREAM_UNAVAILABLE",
            "Douyin author-work endpoint did not provide a usable response",
            diagnostics,
            exception_type,
        )
    return UpstreamFetchError(
        "F2_UPSTREAM_RESPONSE_ERROR",
        safe_message,
        diagnostics,
        exception_type,
    )


def douyin_kwargs(cookie):
    return {
        "headers": {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 "
            "Safari/537.36 Edg/130.0.0.0",
            "Referer": "https://www.douyin.com/",
        },
        "timeout": 10,
        "cookie": cookie,
        "proxies": {"http": None, "https": None},
    }


def _cookie_secrets(cookie):
    if not cookie:
        return []
    secrets = {cookie}
    for part in cookie.split(";"):
        token = part.strip()
        if not token:
            continue
        secrets.add(token)
        if "=" in token:
            value = token.split("=", 1)[1].strip()
            if len(value) >= 6 and not value.isdigit():
                secrets.add(value)
    return sorted(secrets, key=len, reverse=True)


def _redact_cookie(value, cookie):
    text = "" if value is None else str(value)
    for secret in _cookie_secrets(cookie):
        text = text.replace(secret, "***masked***")
    return _SENSITIVE_COOKIE_PATTERN.sub(r"\1=***masked***", text)


def _bounded_status_text(value, cookie=""):
    if value is None:
        return ""
    if isinstance(value, (dict, list)):
        value = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    return _redact_cookie(value, cookie)[:300]


def _exception_requires_verification(error, cookie=""):
    safe_message = _bounded_status_text(str(error), cookie).lower()
    return any(marker in safe_message for marker in _COOKIE_OR_VERIFY_MARKERS)


def _configured_page_delay_seconds():
    raw_value = os.getenv(
        _PAGE_DELAY_ENV, str(_DEFAULT_PAGE_DELAY_SECONDS)
    ).strip()
    try:
        delay_seconds = float(raw_value)
    except ValueError as error:
        raise ValueError(f"{_PAGE_DELAY_ENV} must be a number") from error
    if (
        not math.isfinite(delay_seconds)
        or delay_seconds < 0
        or delay_seconds > _MAX_PAGE_DELAY_SECONDS
    ):
        raise ValueError(
            f"{_PAGE_DELAY_ENV} must be between 0 and "
            f"{_MAX_PAGE_DELAY_SECONDS}"
        )
    return delay_seconds


async def _delay_before_page(page_number, delay_seconds):
    if page_number > 1 and delay_seconds > 0:
        await asyncio.sleep(delay_seconds)


def _bounded_key_names(response, cookie):
    if not isinstance(response, dict):
        return []
    return sorted(
        _redact_cookie(str(key), cookie)[:64] for key in response.keys()
    )[:20]


def _safe_scalar(value, cookie):
    if value is None or isinstance(value, (bool, int, float)):
        return value
    if isinstance(value, str):
        return _redact_cookie(value, cookie)[:128]
    return type(value).__name__


def _status_text(response, cookie=""):
    if not isinstance(response, dict):
        return ""
    user = response.get("user")
    sources = [response, user if isinstance(user, dict) else {}]
    values = []
    for source in sources:
        for key in (
            "status_msg", "status_message", "message", "prompts",
            "special_state_info",
        ):
            text = _bounded_status_text(source.get(key), cookie)
            if text:
                values.append(text)
    return " ".join(values)[:500]


def _verification_value_state(value):
    if value is None or value is False:
        return "success"
    if value is True:
        return "failure"
    if isinstance(value, (int, float)):
        return "success" if value == 0 else "failure"
    if isinstance(value, str):
        normalized = value.strip().lower()
        if not normalized or normalized in ("none", "null"):
            return "success"
        if normalized in _VERIFICATION_SUCCESS_VALUES:
            return "success"
        if normalized in _VERIFICATION_FAILURE_VALUES:
            return "failure"
    return "unknown"


def _object_verification_state(value):
    if not isinstance(value, dict):
        return _verification_value_state(value)
    for field in _VERIFICATION_RESULT_FIELDS:
        if field in value:
            state = _verification_value_state(value.get(field))
            if state != "unknown":
                return state
    return "unknown"


def _captcha_requires_verification(value):
    if isinstance(value, dict):
        if not value:
            return False
        return _object_verification_state(value) != "success"
    return _verification_value_state(value) == "failure"


def _verification_field_requires_verification(value):
    return _object_verification_state(value) == "failure"


def _login_status_requires_verification(value):
    if value is None or value == "":
        return False
    if value is False or value == 0:
        return True
    if isinstance(value, str):
        normalized = value.strip().lower()
        if normalized in _LOGIN_REQUIRED_VALUES:
            return True
        return any(
            marker in normalized
            for marker in ("expired", "not_login", "not logged", "required")
        )
    return False


def _has_structured_verification(response):
    if not isinstance(response, dict):
        return False
    user = response.get("user")
    sources = (response, user if isinstance(user, dict) else {})
    for source in sources:
        if _captcha_requires_verification(source.get("captcha")):
            return True
        if _verification_field_requires_verification(source.get("verify_status")):
            return True
        if _verification_field_requires_verification(source.get("verify_required")):
            return True
        if (
            "login_status" in source
            and _login_status_requires_verification(source.get("login_status"))
        ):
            return True
    return False


def _response_requires_verification(response, cookie=""):
    if _has_structured_verification(response):
        return True
    status_text = _status_text(response, cookie).lower()
    return any(marker in status_text for marker in _COOKIE_OR_VERIFY_MARKERS)


def _profile_status_summary(profile, cookie=""):
    profile_data = profile if isinstance(profile, dict) else {}
    user = profile_data.get("user")
    if not isinstance(user, dict):
        user = {}
    status_parts = []
    for source in (profile, user):
        if not isinstance(source, dict):
            continue
        for key in (
            "status_msg", "status_message", "message", "prompts",
            "captcha", "verify_status", "verify_required", "login_status",
        ):
            text = _bounded_status_text(source.get(key), cookie)
            if text:
                status_parts.append(text)
    return {
        "responseType": type(profile).__name__,
        "topLevelKeys": _bounded_key_names(profile, cookie),
        "topLevelKeyCount": len(profile) if isinstance(profile, dict) else 0,
        "statusCode": _safe_scalar(
            profile.get("status_code") if isinstance(profile, dict) else None,
            cookie,
        ),
        "statusText": " | ".join(status_parts)[:500],
        "hasUser": isinstance(profile.get("user"), dict) if isinstance(profile, dict) else False,
        "specialState": bool(
            profile_data.get("special_state_info") or user.get("special_state_info")
        ),
        "userNotSee": bool(
            profile_data.get("user_not_see") or user.get("user_not_see")
        ),
    }


def _page_status_summary(response, page_number, cursor, cookie=""):
    aweme_list = response.get("aweme_list") if isinstance(response, dict) else None
    if not isinstance(response, dict) or "aweme_list" not in response:
        list_state = "missing"
    elif aweme_list is None:
        list_state = "null"
    elif isinstance(aweme_list, list):
        list_state = "list"
    else:
        list_state = "invalid"
    return {
        "page": page_number,
        "cursor": str(cursor),
        "responseType": type(response).__name__,
        "topLevelKeys": _bounded_key_names(response, cookie),
        "topLevelKeyCount": len(response) if isinstance(response, dict) else 0,
        "statusCode": _safe_scalar(
            response.get("status_code") if isinstance(response, dict) else None,
            cookie,
        ),
        "statusText": _status_text(response, cookie),
        "awemeListState": list_state,
        "itemCount": len(aweme_list) if isinstance(aweme_list, list) else 0,
        "hasMore": _safe_scalar(
            response.get("has_more") if isinstance(response, dict) else None,
            cookie,
        ),
        "nextCursor": str(_safe_scalar(
            response.get("max_cursor") if isinstance(response, dict) else None,
            cookie,
        )),
    }


def _has_nonzero_status(response):
    if not isinstance(response, dict) or "status_code" not in response:
        return False
    return response.get("status_code") not in (None, 0, "0", False)


def _has_explicit_success_status(response):
    if not isinstance(response, dict) or "status_code" not in response:
        return False
    status_code = response.get("status_code")
    if status_code is False:
        return True
    if isinstance(status_code, int) and not isinstance(status_code, bool):
        return status_code == 0
    return status_code == "0"


def _is_invalid_author_profile(response, cookie=""):
    if not isinstance(response, dict) or not _has_nonzero_status(response):
        return False
    status_text = _status_text(response, cookie).lower()
    return any(
        marker in status_text
        for marker in (
            "userid不合法",
            "user id不合法",
            "invalid userid",
            "invalid user id",
            "invalid sec_user_id",
            "sec_user_id invalid",
            "user id is invalid",
        )
    )


def _validate_profile(profile, diagnostics, cookie=""):
    if not isinstance(profile, dict):
        raise UpstreamCommandSchemaError(
            "Douyin profile schema validation failed", diagnostics
        )
    status_text = _status_text(profile, cookie).lower()
    if _response_requires_verification(profile, cookie):
        raise CookieOrVerifyRequired(diagnostics)

    user = profile.get("user")
    if any(marker in status_text for marker in _DEACTIVATED_MARKERS):
        return "ACCOUNT_DEACTIVATED"
    if any(marker in status_text for marker in _BANNED_ACCOUNT_MARKERS):
        return "ACCOUNT_BANNED"
    if _is_invalid_author_profile(profile, cookie):
        raise InvalidAuthorIdError(diagnostics)
    if not isinstance(user, dict):
        raise UpstreamCommandSchemaError(
            "Douyin profile schema validation failed", diagnostics
        )
    if _has_nonzero_status(profile):
        raise UpstreamCommandSchemaError(
            "Douyin profile returned a nonzero status", diagnostics
        )
    return "ACTIVE"


def _write_fetch_result(result, output_file):
    if not write_to_file(result, output_file):
        raise RuntimeError("failed to write Douyin fetch result")
    print("stream-vault-ok")
    print("stream-vault-fetch-outcome=" + json.dumps({
        "outcome": result["outcome"],
        "pagesFetched": result["pagesFetched"],
        "emptyPages": result["emptyPages"],
        "lastCursor": result["lastCursor"],
    }, ensure_ascii=False))


async def fetch_douyin_list_incremental(
    cookie,
    sec_user_id,
    known_ids_file,
    last_seen_publish_time,
    known_boundary,
    max_pages,
    empty_page_limit,
    mode,
    max_items,
    output_file,
    backfill_cursor="",
    backfill_complete=False,
    backfill_verifying=False,
    backfill_clean_passes=0,
):
    with open(known_ids_file, "r", encoding="utf-8") as handle:
        known_ids_value = json.load(handle)
    if not isinstance(known_ids_value, list) or any(
        not isinstance(work_id, str) for work_id in known_ids_value
    ):
        raise UpstreamSchemaError("known IDs file must contain a JSON string array")
    known_ids = set(known_ids_value)
    page_delay_seconds = _configured_page_delay_seconds()

    async with InstrumentedDouyinCrawler(douyin_kwargs(cookie)) as crawler:
        # Scope the known-working signing path to incremental author fetches.
        crawler.bogus_manager = XBogusManager
        try:
            profile = await crawler.fetch_user_profile(
                UserProfile(sec_user_id=sec_user_id)
            )
        except Exception as error:
            profile_summary = _profile_status_summary(None, cookie)
            if crawler.last_request_evidence:
                profile_summary["lastRequest"] = crawler.last_request_evidence
            diagnostics = {"profileStatus": profile_summary}
            if _exception_requires_verification(error, cookie):
                raise CookieOrVerifyRequired(diagnostics) from None
            raise _request_error(
                error, "Douyin profile request failed", diagnostics
            ) from None
        profile_summary = _profile_status_summary(profile, cookie)
        if crawler.last_request_evidence:
            profile_summary["lastRequest"] = crawler.last_request_evidence
        command_diagnostics = {"profileStatus": profile_summary}
        profile_state = _validate_profile(profile, command_diagnostics, cookie)
        if profile_state in ("ACCOUNT_DEACTIVATED", "ACCOUNT_BANNED"):
            result = envelope(
                [], [], profile_state, 0, 0, 0,
                {"pages": [], "profileStatus": profile_summary},
                backfill_cursor or "0",
                backfill_complete,
                backfill_verifying,
                backfill_clean_passes,
            )
            _write_fetch_result(result, output_file)
            return result

        page_number = 0

        async def fetch_page(cursor):
            nonlocal page_number
            page_number += 1
            if page_number > 1:
                await _delay_before_page(page_number, page_delay_seconds)
            params = UserPost(
                max_cursor=int(cursor), count=20, sec_user_id=sec_user_id
            )
            try:
                response = await crawler.fetch_user_post(params)
            except Exception as error:
                page_summary = _page_status_summary(
                    None, page_number, cursor, cookie
                )
                if crawler.last_request_evidence:
                    page_summary["lastRequest"] = crawler.last_request_evidence
                command_diagnostics["lastPage"] = page_summary
                if _exception_requires_verification(error, cookie):
                    raise CookieOrVerifyRequired(
                        dict(command_diagnostics)
                    ) from None
                raise _request_error(
                    error,
                    "Douyin author-work page request failed",
                    dict(command_diagnostics),
                ) from None

            page_summary = _page_status_summary(
                response, page_number, cursor, cookie
            )
            if crawler.last_request_evidence:
                page_summary["lastRequest"] = crawler.last_request_evidence
            command_diagnostics["lastPage"] = page_summary
            if _response_requires_verification(response, cookie):
                raise CookieOrVerifyRequired(dict(command_diagnostics))
            if not isinstance(response, dict) or "status_code" not in response:
                raise UpstreamCommandSchemaError(
                    "Douyin page is missing status_code",
                    dict(command_diagnostics),
                )
            if not _has_explicit_success_status(response):
                raise UpstreamCommandSchemaError(
                    "Douyin page returned a nonzero status",
                    dict(command_diagnostics),
                )
            return response

        try:
            result = await paginate(
                fetch_page,
                known_ids,
                int(last_seen_publish_time) if last_seen_publish_time else None,
                int(known_boundary),
                int(max_pages),
                int(empty_page_limit),
                mode,
                int(max_items),
                backfill_cursor=backfill_cursor,
                backfill_complete=bool(backfill_complete),
                backfill_verifying=bool(backfill_verifying),
                backfill_clean_passes=int(backfill_clean_passes),
            )
        except FetchCommandError:
            raise
        except UpstreamSchemaError as error:
            raise UpstreamCommandSchemaError(
                "Douyin page schema validation failed",
                dict(command_diagnostics),
                type(error).__name__,
            ) from None
        result["diagnostics"]["profileStatus"] = profile_summary
        if "lastPage" in command_diagnostics:
            result["diagnostics"]["lastPage"] = command_diagnostics["lastPage"]

    _write_fetch_result(result, output_file)
    return result


def _emit_fetch_error(error, cookie):
    fault_domain = (
        "APPLICATION"
        if error.error_code == "F2_PROTOCOL_ERROR"
        else "TASK_CONFIGURATION"
        if error.error_code == "INVALID_AUTHOR_ID"
        else "REMOTE_API"
    )
    retryable = error.error_code not in ("INVALID_AUTHOR_ID", "F2_PROTOCOL_ERROR")
    cooldown_applied = error.error_code in (
        "F2_UPSTREAM_RATE_LIMIT",
        "F2_COOKIE_OR_VERIFY_REQUIRED",
    )
    payload = {
        "errorCode": error.error_code,
        "message": error.safe_message,
        "diagnostics": {
            **error.diagnostics,
            "exceptionType": error.exception_type,
            "faultDomain": fault_domain,
            "retryable": retryable,
            "cooldownApplied": cooldown_applied,
        },
    }
    rendered = json.dumps(payload, ensure_ascii=False)
    rendered = _redact_cookie(rendered, cookie)
    print(
        "stream-vault-fetch-error=" + rendered,
        file=sys.stderr,
    )


async def run_incremental_command(args):
    try:
        return await fetch_douyin_list_incremental(
            args.cookie,
            args.sec_user_id,
            args.known_ids_file,
            args.last_seen_publish_time,
            args.known_boundary,
            args.max_pages,
            args.empty_page_limit,
            args.mode,
            args.max_items,
            args.output,
            getattr(args, "backfill_cursor", ""),
            bool(getattr(args, "backfill_complete", 0)),
            bool(getattr(args, "backfill_verifying", 0)),
            getattr(args, "backfill_clean_passes", 0),
        )
    except FetchCommandError as error:
        _emit_fetch_error(error, args.cookie)
        exit_code = 2 if error.error_code == "F2_COOKIE_OR_VERIFY_REQUIRED" else 3
        raise SystemExit(exit_code) from None
    except Exception as error:
        wrapped = FetchCommandError(
            "F2_PROTOCOL_ERROR",
            "Douyin fetch command failed before producing a valid result",
            {},
            type(error).__name__,
        )
        _emit_fetch_error(wrapped, args.cookie)
        raise SystemExit(3) from None

def write_to_file(data, output_file: str) -> bool:
    """
    将数据写入文件
    Args:
        data: 要写入的数据
        output_file: 输出文件路径
    Returns:
        bool: 是否写入成功
    """
    try:
        # 创建目录
        os.makedirs(os.path.dirname(output_file), exist_ok=True)
    except Exception as e:
        print(f"创建目录时出错: {e}")
        return False
    
    try:
        # 删除已存在的文件
        if os.path.exists(output_file):
            os.remove(output_file)
    except Exception as e:
        print(f"删除文件时出错: {e}")
        return False
    
    try:
        # 写入新文件
        with open(output_file, 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        return True
    except Exception as e:
        print(f"写入文件时出错: {e}")
        return False

# 获取视频信息的方法
async def fetch_video(cookie: str, aweme_id: str):
    kwargs = {
        "headers": {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0",
            "Referer": "https://www.douyin.com/",
        },
        "cookie": cookie,
        "proxies": {"http": None, "https": None},
    }
    
    handler = DouyinHandler(kwargs)
    setattr(handler, "enable_bark", False)
    
    video = await handler.fetch_one_video(aweme_id=aweme_id)
    jsonres = {
        "cover": [video.cover],
        "aweme_id": video.aweme_id,
        "desc": video.desc,
        "video_play_addr": json.dumps(video.video_play_addr),
        "nickname": video.nickname,
        "avatar_thumb": video._get_attr_value("$.aweme_detail.author.avatar_thumb.url_list[0]"),
        "uid": video._get_attr_value("$.aweme_detail.author.sec_uid"),
        "create_time": video.create_time
    }
    print(jsonres)
    
# 获取视频信息的方法
async def fetch_post_data(cookie: str, aweme_id: str, output_file: str):
    kwargs = {
        "headers": {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0",
            "Referer": "https://www.douyin.com/",
        },
        "cookie": cookie,
        "proxies": {"http": None, "https": None},
    }
    
    handler = DouyinHandler(kwargs)
    setattr(handler, "enable_bark", False)
    
    video = await handler.fetch_one_video(aweme_id=aweme_id)

    if write_to_file(video._to_raw(), output_file):
        print("stream-vault-ok")

# Return one raw work as JSON without creating an output file. The Java adapter
# uses this path for parse/preview so media and persistence remain side-effect free.
async def fetch_work_data(cookie: str, aweme_id: str):
    kwargs = {
        "headers": {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0",
            "Referer": "https://www.douyin.com/",
        },
        "cookie": cookie,
        "proxies": {"http": None, "https": None},
    }

    handler = DouyinHandler(kwargs)
    setattr(handler, "enable_bark", False)
    work = await handler.fetch_one_video(aweme_id=aweme_id)
    print(json.dumps(work._to_raw(), ensure_ascii=False))

# 获取用户点赞列表方法
async def fetch_user_like_videos(cookie: str, uid: str, maxc: str, output_file: str):
    kwargs = {
        "headers": {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0",
            "Referer": "https://www.douyin.com/",
        },
        "timeout": 10,
        "cookie": cookie,
        "proxies": {"http": None, "https": None},
    }
    handler = DouyinHandler(kwargs)
    setattr(handler, "enable_bark", False)
    all_videos = []
    async for aweme_data_list in handler.fetch_user_like_videos(
        uid, 0,  20, int(maxc)
    ):
        videos = aweme_data_list._to_list()
        for video in videos:
            jsonres = {
                "cover": [video["cover"]],
                "aweme_id": video["aweme_id"],
                "desc": video["desc"],
                "video_play_addr": json.dumps(video["video_play_addr"]),
                "nickname": video["nickname"],
                "avatar_thumb": video['author_avatar_thumb'],
                "uid": video["uid"],
                "create_time": video["create_time"]
            }
            all_videos.append(jsonres)
    
    if write_to_file(all_videos, output_file):
        print("stream-vault-ok")

# 获取用户视频发布列表方法
async def fetch_user_post_videos(cookie: str, uid: str, maxc: str, output_file: str):
    kwargs = {
        "headers": {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0",
            "Referer": "https://www.douyin.com/",
        },
        "timeout": 10,
        "cookie": cookie,
        "proxies": {"http": None, "https": None},
    }
    handler = DouyinHandler(kwargs)
    setattr(handler, "enable_bark", False)
    all_videos = []
    async for aweme_data_list in handler.fetch_user_post_videos(
        uid, 0, 0,  20, int(maxc)
    ):
        videos = aweme_data_list._to_list()
        for video in videos:
            jsonres = {
                "cover": [video["cover"]],
                "aweme_id": video["aweme_id"],
                "desc": video["desc"],
                "video_play_addr": json.dumps(video["video_play_addr"]),
                "nickname": video["nickname"],
                "avatar_thumb": video['author_avatar_thumb'],
                "uid": video["uid"],
                "create_time": video["create_time"]
            }
            all_videos.append(jsonres)
    
    if write_to_file(all_videos, output_file):
        print("stream-vault-ok")

# 获取收藏夹名称及id
async def fetch_user_collects(cookie: str):
    # 设置日志级别为CRITICAL，只显示严重错误
    logger.setLevel('CRITICAL')
    
    kwargs = {
        "headers": {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0",
            "Referer": "https://www.douyin.com/",
        },
        "timeout": 10,
        "cookie": cookie,
        "proxies": {"http": None, "https": None},
    }
    handler = DouyinHandler(kwargs)
    setattr(handler, "enable_bark", False)
    all_collects = []
    probe = {
        "probeStatus": "INDETERMINATE",
        "upstreamStatus": "",
        "listState": "NO_RESPONSE",
        "collectCount": 0,
        "errorCategory": "UPSTREAM_EMPTY",
    }
    try:
        async for collection_list in handler.fetch_user_collects(
            max_cursor=0,
            page_counts=10,
            max_counts=40,
        ):
            raw_data = collection_list._to_raw()
            if not isinstance(raw_data, dict):
                probe.update(listState="INVALID", errorCategory="UPSTREAM_SCHEMA")
                break
            upstream_status = raw_data.get("status_code")
            probe["upstreamStatus"] = "" if upstream_status is None else str(upstream_status)
            if upstream_status not in (None, 0, "0"):
                message = str(raw_data.get("status_msg") or raw_data.get("message") or "")
                status, category = _classify_cookie_probe_error(message)
                probe.update(probeStatus=status, listState="UNAVAILABLE", errorCategory=category)
                break
            if "collects_list" not in raw_data:
                probe.update(listState="MISSING", errorCategory="UPSTREAM_SCHEMA")
                break
            collects = raw_data.get("collects_list")
            if collects is None:
                probe.update(listState="NULL", errorCategory="UPSTREAM_SCHEMA")
                break
            if not isinstance(collects, list):
                probe.update(listState="INVALID", errorCategory="UPSTREAM_SCHEMA")
                break
            malformed = False
            for collect in collects:
                if not isinstance(collect, dict):
                    malformed = True
                    break
                collects_id = collect.get("collects_id")
                collects_name = collect.get("collects_name")
                if collects_id is None or collects_name is None:
                    malformed = True
                    break
                all_collects.append({
                    "collects_id": collects_id,
                    "collects_name": collects_name,
                })
            if malformed:
                probe.update(listState="INVALID", errorCategory="UPSTREAM_SCHEMA")
                break
            probe.update(
                probeStatus="VALID",
                listState="ARRAY",
                collectCount=len(all_collects),
                errorCategory="NONE",
            )
    except Exception as error:
        status, category = _classify_cookie_probe_error(str(error))
        probe.update(probeStatus=status, listState="UNAVAILABLE", errorCategory=category)
    print(
        "stream-vault-start-cookie-probe",
        json.dumps(probe, ensure_ascii=False),
        "stream-vault-end-cookie-probe",
    )
    print("stream-vault-start-collects",json.dumps(all_collects, ensure_ascii=False),"stream-vault-end-collects")


def _classify_cookie_probe_error(message: str):
    normalized = (message or "").lower()
    if any(signal in normalized for signal in ("login", "verify", "401", "403", "unauthorized")):
        return "EXPIRED", "AUTHENTICATION"
    if "risk" in normalized or "captcha" in normalized:
        return "EXPIRED", "RISK_CONTROL"
    if any(signal in normalized for signal in ("timeout", "timed out", "connection", "network")):
        return "INDETERMINATE", "NETWORK"
    return "INDETERMINATE", "UPSTREAM_ERROR"

# 获取收藏夹下的视频
async def fetch_user_collects_videos(cookie: str, cid: str, maxc:str, output_file: str):
    kwargs = {
        "headers": {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0",
            "Referer": "https://www.douyin.com/",
        },
        "timeout": 10,
        "cookie": cookie,
        "proxies": {"http": None, "https": None},
    }
    handler = DouyinHandler(kwargs)
    setattr(handler, "enable_bark", False)
    all_videos = []
    async for collection_list in handler.fetch_user_collects_videos(
        collects_id=cid,
        max_cursor=0,
        page_counts=10,
        max_counts=int(maxc)
    ):
        print(collection_list._to_raw())
        videos = collection_list._to_list()
        for video in videos:
            jsonres = {
                "cover": [video["cover"]],
                "aweme_id": video["aweme_id"],
                "desc": video["desc"],
                "video_play_addr": json.dumps(video["video_play_addr"]),
                "nickname": video["nickname"],
                "avatar_thumb": video['author_avatar_thumb'],
                "uid": video["uid"],
                "create_time": video["create_time"]
            }
            all_videos.append(jsonres)
    
    if write_to_file(all_videos, output_file):
        print("stream-vault-ok")


# 获取首页推荐
async def fetch_user_feed_videos(cookie: str, sec_user_id: str, output_file: str):
    print(sec_user_id)
    kwargs = {
        "headers": {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0",
            "Referer": "https://www.douyin.com/",
        },
        "timeout": 10,
        "cookie": cookie,
        "proxies": {"http": None, "https": None},
    }
    handler = DouyinHandler(kwargs)
    setattr(handler, "enable_bark", False)
    all_videos = []
    async for feed_list in handler.fetch_user_feed_videos(
        sec_user_id,
        max_cursor=0,
        page_counts=10,
        max_counts=20,
    ):
        print(feed_list._to_raw())
        videos = feed_list._to_list()
        for video in videos:
            jsonres = {
                "cover": [video["cover"]],
                "aweme_id": video["aweme_id"],
                "desc": video["desc"],
                "video_play_addr": json.dumps(video["video_play_addr"]),
                "nickname": video["nickname"],
                "uid": video["uid"],
                "create_time": video["create_time"]
            }
            all_videos.append(jsonres)
    
    if write_to_file(all_videos, output_file):
        print("stream-vault-ok")


# 主函数
async def main():
    parser = argparse.ArgumentParser(description="Douyin API Helper")
    subparsers = parser.add_subparsers(dest="command", help="sub-command help")
    
    # 单视频解析
    fetch_video_parser = subparsers.add_parser("fetch_video", help="Fetch a video from Douyin")
    fetch_video_parser.add_argument("--cookie", type=str, required=True, help="Douyin cookie")
    fetch_video_parser.add_argument("--aweme_id", type=str, required=True, help="Aweme ID of the video")
    
    # 获取用户点赞视频
    fetch_user_like_videos_parser = subparsers.add_parser("fetch_user_like_videos", help="Fetch user_like info from Douyin")
    fetch_user_like_videos_parser.add_argument("--cookie", type=str, required=True, help="Douyin cookie")
    fetch_user_like_videos_parser.add_argument("--uid", type=str, required=True, help="User ID")
    fetch_user_like_videos_parser.add_argument("--maxc", type=str, required=True, help="maxc")
    fetch_user_like_videos_parser.add_argument("--output", type=str, required=True, help="Output file path")

    # 获取用户发布的作品
    fetch_user_post_videos_parser = subparsers.add_parser("fetch_user_post_videos", help="Fetch user_post info from Douyin")
    fetch_user_post_videos_parser.add_argument("--cookie", type=str, required=True, help="Douyin cookie")
    fetch_user_post_videos_parser.add_argument("--uid", type=str, required=True, help="User ID")
    fetch_user_post_videos_parser.add_argument("--maxc", type=str, required=True, help="maxc")
    fetch_user_post_videos_parser.add_argument("--output", type=str, required=True, help="Output file path")

    incremental_parser = subparsers.add_parser(
        "fetch_douyin_list_incremental",
        help="Fetch a project-controlled incremental Douyin post list",
    )
    incremental_parser.add_argument("--cookie", type=str, required=True)
    incremental_parser.add_argument("--sec_user_id", type=str, required=True)
    incremental_parser.add_argument("--known_ids_file", type=str, required=True)
    incremental_parser.add_argument("--last_seen_publish_time", type=str, default="")
    incremental_parser.add_argument("--known_boundary", type=int, required=True)
    incremental_parser.add_argument("--max_pages", type=int, required=True)
    incremental_parser.add_argument("--empty_page_limit", type=int, required=True)
    incremental_parser.add_argument(
        "--mode", choices=("initial", "incremental", "audit"), required=True
    )
    incremental_parser.add_argument("--max_items", type=int, default=0)
    incremental_parser.add_argument("--backfill_cursor", type=str, default="")
    incremental_parser.add_argument(
        "--backfill_complete", type=int, choices=(0, 1), default=0
    )
    incremental_parser.add_argument(
        "--backfill_verifying", type=int, choices=(0, 1), default=0
    )
    incremental_parser.add_argument(
        "--backfill_clean_passes", type=int, choices=(0, 1, 2), default=0
    )
    incremental_parser.add_argument("--output", type=str, required=True)
   
   
    #获取用户收藏夹
    fetch_user_collects_parser = subparsers.add_parser("fetch_user_collects", help="Fetch user_collects info from Douyin")
    fetch_user_collects_parser.add_argument("--cookie", type=str, required=True, help="Douyin cookie")

    #获取对应收藏夹的视频
    fetch_user_collects_videos_parser = subparsers.add_parser("fetch_user_collects_videos", help="Fetch user_collects_video info from Douyin")
    fetch_user_collects_videos_parser.add_argument("--cookie", type=str, required=True, help="Douyin cookie")
    fetch_user_collects_videos_parser.add_argument("--cid", type=str, required=True, help="Collect ID")
    fetch_user_collects_videos_parser.add_argument("--maxc", type=str, required=True, help="maxc")
    fetch_user_collects_videos_parser.add_argument("--output", type=str, required=True, help="Output file path")


    # 获取首页推荐
    fetch_user_feed_videos_parser = subparsers.add_parser("fetch_user_feed_videos", help="Fetch user_post info from Douyin")
    fetch_user_feed_videos_parser.add_argument("--cookie", type=str, required=True, help="Douyin cookie")
    fetch_user_feed_videos_parser.add_argument("--uid", type=str, required=True, help="User ID")
    fetch_user_feed_videos_parser.add_argument("--output", type=str, required=True, help="Output file path")
    
    # 获取作品通用 
    fetch_user_post_parser = subparsers.add_parser("fetch_post_data", help="Fetch a Post from Douyin")
    fetch_user_post_parser.add_argument("--cookie", type=str, required=True, help="Douyin cookie")
    fetch_user_post_parser.add_argument("--aweme_id", type=str, required=True, help="Aweme ID of the video")
    fetch_user_post_parser.add_argument("--output", type=str, required=True, help="Output file path")

    fetch_work_data_parser = subparsers.add_parser("fetch_work_data", help="Fetch one raw work as JSON")
    fetch_work_data_parser.add_argument("--cookie", type=str, required=True, help="Douyin cookie")
    fetch_work_data_parser.add_argument("--aweme_id", type=str, required=True, help="Aweme ID")

    args = parser.parse_args()
    
    if args.command == "fetch_video":
        await fetch_video(args.cookie, args.aweme_id)
    if args.command == "fetch_user_like_videos":
        await fetch_user_like_videos(args.cookie, args.uid ,args.maxc, args.output)
    if args.command == "fetch_user_post_videos":
        await fetch_user_post_videos(args.cookie, args.uid ,args.maxc, args.output)
    if args.command == "fetch_douyin_list_incremental":
        await run_incremental_command(args)
    if args.command == "fetch_user_collects":
        await fetch_user_collects(args.cookie)
    if args.command == "fetch_user_collects_videos":
        await fetch_user_collects_videos(args.cookie, args.cid ,args.maxc, args.output)
    if args.command == "fetch_user_feed_videos":
        await fetch_user_feed_videos(args.cookie, args.uid, args.output)
    if args.command == "fetch_post_data":
    	await fetch_post_data(args.cookie, args.aweme_id, args.output)
    if args.command == "fetch_work_data":
        await fetch_work_data(args.cookie, args.aweme_id)

if __name__ == "__main__":
    asyncio.run(main())
