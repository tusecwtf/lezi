# 01 — 保留 CUR 强制更新壳

**What to build:** 让手动 `checkAppUpdate` 与同步遇到 `client_update_required` 后使用同一 fail-closed 分类策略；已有 `PackageUnknown`/强制包时，非 Forced 元数据不能把强制壳拆掉。

**Source:** `AUDIT-20260801-P1-01`  
**Blocked by:** None — can start immediately  
**Status:** done  
**Size:** S–M

## Acceptance criteria

- [x] 回归先证明 `CUR → PackageUnknown → checkAppUpdate(UpToDate)` 当前会清壳，再修到仍保留 `PackageUnknown`。
- [x] `CUR → PackageUnknown → checkAppUpdate(OptionalUpdate)` 不展示 optional banner，且强制壳仍在。
- [x] 已有 `WithPackage` 时，暂时失败或非 Forced 元数据保留最后一个已验证可安装包；新的合法 Forced 元数据可替换它。
- [x] 没有 CUR/forced 历史的正常手动检查仍可返回 UpToDate/Optional 并清理过期 optional 状态。
- [x] 未加入家庭时继续清除 update surface；手动检查失败仍不把 `SyncStatus` 伪装成 NAS 网络错误。
- [x] `classifyAndPublishAppUpdate` 不再被可拆壳调用方绕过；测试覆盖同步和手动重试两条入口。

## Validation

运行 `:sync:test` 中 RealSyncPort/app-update 回归、`:app:assembleDebug`、`lintDebug`；设备 smoke 强制壳“重试”后仍不可进入业务页。

## Documentation Gate

若状态机文字调整，同步更新 `docs/prd/sync-trusted-endpoint.md` 与 `docs/prd/tech.md` 的 CUR/强制更新表。

## Out of scope

不改 APK 下载、签名校验或 NAS 发布元数据格式。
