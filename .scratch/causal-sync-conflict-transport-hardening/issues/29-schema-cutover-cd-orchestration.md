# 29 — 接入 0.4.0 schema-cutover CD

**What to build:** 在受保护 deploy workflow 中增加显式 0.4.0 schema-cutover maintenance mode，串联 APK 预发布、lease、完整 rollback backup、copy-out、offline-migrate、validate、copy-back、container replacement 与 post-check。

**Blocked by:** 28

**Status:** ready-for-agent

## Contract slice

Ordinary `push-and-deploy` 仍禁止 migration；schema-cutover 必须单独授权并走同一 outer lease/age/TLS guards。任何 source/version/inventory/backup/migration validation 失败必须在 stop/rm 或 copy-back 前终止。

## Implementation sequence

1. 为 0.4.0 package 固定 code 21 APK、floor 21、image/schema 13 与旧 0.3.13 rollback manifest。
2. 在开发机增加 guarded cutover orchestration：先发布并验证 installable APK pair，再 lease；把完整 source data root/WAL/SHM/media/start contract 与 credentials 直接流入 off-repo age rollback bundle，然后 copy-out/migrate/validate。
3. 只把 validated out copy-back 到 staging data root，再替换 container 并验证 schema/version/image/TLS/data。
4. 定义开放新写入前的自动 rollback 点与开放后需人工授权的数据回退边界。

## Acceptance

- [ ] ordinary CD 无法意外触发 offline-migrate
- [ ] cutover 需要明确维护授权、outer lease、credential backup 与 off-repo age 加密的完整 data rollback bundle
- [ ] stop 前记录 old image/package、source schema/inventory、certificate SHA/SPKI
- [ ] post-check 要求 0.4.0/schema13/health/ready、数据 inventory 与 TLS 完全匹配

## Validation

- [ ] deploy script contract/failure-injection/syntax tests 通过
- [ ] package inventory 包含当前 guarded helpers，不携带秘密或 DB backup

## Out of scope

不执行家庭 NAS cutover；真实演练由票 30。
