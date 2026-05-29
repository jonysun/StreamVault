# Douyin Work Maintenance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add in-place redownload for downloaded Douyin videos/graphics, one-click repair for Douyin jsonData/source links, and mobile UI fixes for author/source links and Douyin user task parsing.

**Architecture:** Add a focused maintenance service for Douyin work refresh and metadata repair. Admin and mobile frontends call small endpoints or consume existing entity fields without changing persistence shape.

**Tech Stack:** Spring Boot/JPA, Thymeleaf/jQuery, UniApp Vue, FastJSON, existing DouUtil and DouyinSourceUrlUtil helpers.

---

## Confirmed Rules

- Redownload updates the existing database row and must not delete or duplicate it.
- Redownload updates `createtime` to the current local download time.
- Douyin API `create_time` is the work publish time and must only populate `publishtime`.
- The merged maintenance button repairs both `jsonData` and original work links.
- Douyin video source links use `https://www.douyin.com/video/{videoid}`.
- Douyin graphic source links use `https://www.douyin.com/note/{videoid}`.
- Mobile collect-task parsing names tasks as `{nickname}的作品`.

## Tasks

- [ ] Create backend maintenance service and admin endpoints.
- [ ] Implement batch Douyin metadata repair for `jsonData` and `sourceurl`.
- [ ] Implement in-place video redownload.
- [ ] Implement in-place graphic redownload.
- [ ] Add admin Web buttons for redownload and repair.
- [ ] Fix UniApp detail/source-link display and collect-task parsing.
- [ ] Run compile/tests where possible and record local Java limitations.
