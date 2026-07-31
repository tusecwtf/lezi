# 05 — 装前校验 APK 包名/版本/签名

**What to build:** 下载并 sha256 通过后、PackageInstaller commit 前，解析 APK 归档：包名必须等于本机 applicationId、versionCode 与元数据一致且高于本机、签名证书与已安装乐记一致；否则拒绝安装并清理暂存，不调起系统确认装「另一个应用」。

**Blocked by:** None — can start immediately（依赖 02 的串行/IO 更稳妥）

**Status:** done

## Acceptance criteria

- [x] 客户端装前用平台 API（如 `getPackageArchiveInfo` / 签名信息）校验包名、versionCode、与已装包签名匹配
- [x] 校验失败：不 commit、清理私有暂存、更新 UI 明确「更新包无效/不匹配」
- [x] 分类/展示元数据时尽量拒绝 `packageName !=` 本机 applicationId（不仅安装时硬编码字符串）
- [x] 可测 seam：fake 归档信息或注入校验器；至少覆盖包名不匹配拒绝
- [ ] （可选加强）package-nas 用 aapt/apksigner 校验 JSON 与 APK 内嵌 version 一致 — deferred (optional)

## Comments

- Review: B6（security）；plan package-nas aapt suggestion 可放本票可选
- Implement: `AppUpdateApkIdentityReader` + pure `verifyStagedApkIdentity`; Android reader via `getPackageArchiveInfo` / signing certs; RealSyncPort install + classify; tests in `AppUpdateApkIdentityTest` / `RealSyncPortTest`. Evidence: [../evidence/05/validation.md](../evidence/05/validation.md)
- Fix r1: fail-closed empty installed certs; typed `ClientAppVersion`/`AppUpdateMetadata` verify args; `PackageManagerCompat`; tech.md + SyncPort KDoc; install version/unreadable tests.
- Fix r2: aligned `SettingsScreen` / `FamilyViewModel.installOptionalUpdate` KDocs with SyncPort pipeline (download → sha256 → staged archive identity → PackageInstaller).
