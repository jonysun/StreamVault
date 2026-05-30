# Diagnostics Cookie Playback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Improve Douyin author rebuild accuracy, collect-task failure diagnostics, Douyin/Kuaishou multi-cookie strategies, and admin feed playback mode.

**Architecture:** Add small focused helpers rather than broad rewrites: task-level failure records reuse collect detail rows, author rebuild remains in `AuthorProfileService`, cookie selection is centralized in `PlatformCookieService`, and playback mode is a front-end state machine over existing feed controls.

**Tech Stack:** Spring Boot/JPA, Thymeleaf/jQuery, existing FastJSON utilities and f2 command wrappers.

---

## Tasks

- [ ] Persist fetch-level collect-task failures as synthetic collect detail rows.
- [ ] Rebuild Douyin authors from API-first data with explicit fallback statistics.
- [ ] Replace feed auto-next boolean with playback mode: `auto_next` or `single_loop`.
- [ ] Add multi-cookie pools and strategies for Douyin/Kuaishou with 10-minute risk cooldown.
- [ ] Run available verification and document blocked Java/Maven checks.
