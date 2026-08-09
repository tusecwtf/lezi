# 22 — 迁移 Baby avatar commit-first

**What to build:** 将 Baby avatar/synthetic media root 迁移到 spool/receipt commit-first，保留 Owner ACL 与 deleted-root orphan 防护。

**Blocked by:** 21

**Status:** ready-for-agent

## Contract slice

Avatar root/media 在同一 frozen mutation；只有 Owner 可改变受限 avatar，删除 Baby 不留下可达 orphan media。

## Implementation sequence

1. 冻结 Baby root+avatar receipt envelope。
2. 在 server/client 两端强制 Owner ACL。
3. 接入 prepare/commit/settlement 与 tombstone orphan handling。
4. 删除 Baby avatar reconcile path。

## Acceptance

- [ ] Owner allowed、non-Owner denied
- [ ] lost response 不 re-upload/duplicate version
- [ ] deleted Baby 不暴露 orphan avatar，branch bytes 仍可审计

## Validation

- [ ] Baby avatar engine/ACL/server matrix 通过
- [ ] isolated byte-equality smoke 通过

## Out of scope

不迁移 CarePlan attachments。
