# 跨成员睡眠醒来 ACL — issues

Spec: [`spec.md`](./spec.md)  
Tracker status: **ready-for-agent** (frontier = **02**)  
Target: **0.3.11**

## Graph

```text
01 ──► 02
```

| # | File | Status | Blocked by |
|---|------|--------|------------|
| 01 | [`issues/01-family-wake-publishable-and-local-b1.md`](./issues/01-family-wake-publishable-and-local-b1.md) | complete | — |
| 02 | [`issues/02-release-0.3.11.md`](./issues/02-release-0.3.11.md) | ready-for-agent | 01 |

## Frontier

**01** complete. Start **02** (release 0.3.11 gates + CD; user must confirm NAS CD before deploy).

## Notes

- Merged former “publishable wake” + “local B1 UI” into ticket **01** (user decision).
- No server closer stamp; B1 is device-local and ends when dirty settles or authority overwrites.
- Related complete work: multi-open heal (`family-sync-hang` issue 32) — do not re-scope into 01.
