# 21 — 迁移 Record media commit-first

**What to build:** 将 Record media membership 与 Record tombstone 的媒体证据迁移到 spool/receipt commit-first，并保持 add/add 自动合并与 delete/edit 分支。

**Blocked by:** 20

**Status:** ready-for-agent

## Contract slice

Record root 与 media metadata/receipt 在同一 frozen mutation；branch/resolution 继续引用精确 bytes。

## Implementation sequence

1. 冻结 Record root+media envelope。
2. 依次 prepare receipts 后执行一次 commit。
3. 用统一 settlement 处理 accepted/merged/branched。
4. 删除 Record media reconcile path。

## Acceptance

- [ ] media add/add、delete/edit 与 tombstone evidence 正确
- [ ] no reconcile、no cursor move、lost response no re-upload
- [ ] branch media 在 conflict detail/resolution 可发现

## Validation

- [ ] Record media engine/DAO/server matrix 通过
- [ ] isolated byte-equality smoke 通过

## Out of scope

不迁移 Baby avatar 或 CarePlan attachment。
