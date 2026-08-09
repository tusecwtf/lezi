# 18 — 持久化 snapshot receipt 与有界分页

Status: ready-for-agent

Priority: P1

Audit baseline: `38cfbe7dbcf1f410237dce77b50ec1bd4854d8a6`

Blocked by: 17；以及 [`causal hardening 01`](../../causal-sync-conflict-transport-hardening/issues/01-freeze-conflict-v2-contract.md)。

## What to build

为完整 branch-set fingerprint 签发持久随机 snapshot receipt，并按 count/encoded-byte budget 提供稳定 continuation。receipt 跨重复 detail、分页和服务重启有效，直到过期或 stable/branch set 变化。

## Implementation sequence

1. 冻结 receipt binding、TTL、continuation 与 page budget。
2. 持久化 family/root/stable/branch fingerprint/contract/expiry。
3. 用票 17 loader 生成不重不漏的有界 raw page。
4. 对重启、过期、新 branch、stable 变化和篡改返回 typed stale/error。

## Acceptance

- [ ] 每页同时满足 count/encoded-byte budget
- [ ] continuation 遍历完整 raw head set，不重不漏
- [ ] receipt 跨重启稳定，state change/expiry 明确 stale
- [ ] partial page 从不授权 partial-set resolution

## Validation

- [ ] continuation/response-budget/restart/tamper tests 通过
- [ ] Rust gates 与隔离分页 smoke 通过

## Out of scope

不生成 semantic candidate/choice ID，不定义 N 方 merge。
