# 跨成员睡眠醒来 ACL — issues

Spec: [`spec.md`](./spec.md)  
Tracker status: **in-progress** (frontier = **02** — CD/smoke remaining)  
Target: **0.3.11**

## Graph

```text
01 ──► 02
```

| # | File | Status | Blocked by |
|---|------|--------|------------|
| 01 | [`issues/01-family-wake-publishable-and-local-b1.md`](./issues/01-family-wake-publishable-and-local-b1.md) | complete | — |
| 02 | [`issues/02-release-0.3.11.md`](./issues/02-release-0.3.11.md) | in-progress | 01 |

## Frontier

**02** in progress: versions/gates/APK pin landed; NAS CD + dual-device smoke next (user confirmed CD via implement request).

## Notes

- Merged former “publishable wake” + “local B1 UI” into ticket **01** (user decision).
- No server closer stamp; B1 is device-local and ends when dirty settles or authority overwrites.
- Related complete work: multi-open heal (`family-sync-hang` issue 32) — do not re-scope into 01.
