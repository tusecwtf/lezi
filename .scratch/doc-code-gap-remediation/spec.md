# Spec · 文档–代码 gap 整改（原子同步 / UI / 契约）

**Status:** ready-for-agent  
**Source:** 四路只读审计（ADR·UI·Sync·Tech）+ grilling 共识（2026-07-30）  
**Out of band:** 代码改动已回滚；本目录只留可执行票。

## Problem Statement

产品文档与实现在若干关键契约上不一致：原子同步包范围、ordinary 发布路径、pull 照片共组、health 门闩、多孩日龄跳转，以及部分 PRD/design 状态滞后。审计已定位，需要按锁定决策逐票落地。

## Solution

按 ask-matt 票序：先统一文档契约，再收紧服务端与客户端 wire，再补 UI 与 PRD hygiene。其中 **ordinary `/v1/push` 整路径退役** 为明确产品方向（grill 后追加）：所有实体发布走 atomic bundle。

## Locked decisions (grilling)

| # | 决策 |
|---|------|
| A | 文档：全部 Record（0–3 图）+ 全部 CarePlan 走 atomic bundle；ordinary 枚举写清 |
| B→扩展 | **完整去除 ordinary 同步路径**（不仅拒 log media）；baby / avatar / custom_item / fulfillment 亦经 bundle 发布 |
| C | Pull：服务端共组 parent↔log media；客户端仍按页 stage（不跨页 hold） |
| D | 长按昵称：循环下一孩 + 日龄对齐；单孩 no-op；目标日允许空态（未来日夹到 today） |
| Health | 客户端 capabilities **超集**包含必选能力；**忽略 version** 门闩 |
| Timeline | **只改 PRD**：Composer 有排泄图标；时间轴文本摘要（本批不做行内 marks） |
| Record ACL | **文档写死**：当前契约允许任成员 LWW 改任意护理记录；CarePlan/自定义项仍 creator-or-owner |

## User Stories

1. As a family member, I want record+photos always to appear together on other devices, so that I never see a photo-less care fact.
2. As a developer, I want one publish path (atomic bundle), so that ordinary half-writes cannot exist.
3. As a multi-child parent, I want long-press on the nickname to jump to the sibling’s same day-of-life date, so that I can compare ages quickly.
4. As an operator, I want the app to accept servers with additive capabilities without hard-pinning version strings, so that patches do not brick sync.
5. As a reader of docs, I want PRD/ADR to match code, so that agents implement the right contract.

## Implementation Decisions

- Wire: expand atomic bundle roots to `record | care_plan | baby | custom_item | fulfillment_candidate`; media members: log for record/plan, avatar for baby; empty media for custom/fulfillment.
- Server: retire HTTP ordinary `/v1/push` (or make it fail-closed); pull dependency graph co-includes log media when emitting live record/care_plan (visit-mark to avoid cycles).
- Android: Outbox residual (baby/avatar/custom/fulfillment) publishes via bundles; health `capabilities.containsAll(required)` only.
- Docs: ADR-0008, `docs/prd/sync-home-lan.md`, `tools/lezi-sync/README.md`, `docs/prd/ui.md` / `data-model.md`, design Status fold/update.
- Tests: lezi-sync api fixtures seed via bundles; Android unit tests for day-age math and health superset.

## Testing Decisions

- Prefer highest seams: lezi-sync API tests, `RealSyncPort` / outbox pipeline tests, pure functions for day-age and health.
- Do not require Compose instrumented UI for nickname long-press if unit-level date math + header wiring are covered.
- Fail-closed on wire: ordinary log/entity publish must 422; pull page must not emit live root without its live log media on the same page when both exist.

## Out of Scope

- Timeline excretion marks / photo chrome implementation (PRD wording only).
- Record creator-or-owner ACL enforcement (document open edit instead).
- Physical dual-device NAS acceptance residual.
- Restoring deleted historical `.scratch` closed tickets.

## Further Notes

- Prior incomplete WIP was fully restored; implement from this spec only.
- Coordinate with existing open scratch features if they touch the same modules (layout edit, program audit).
