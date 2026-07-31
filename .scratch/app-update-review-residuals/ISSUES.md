# Issues · app-update-review-residuals

**Status:** ready-for-agent · 2/6 done  
**Spec:** [spec.md](./spec.md)  
**Source review:** app-update commits `8651a9a`…`47828af`（并行 correctness/security/tests/plan）

## Dependency graph

```text
01 安装失败不污染 SyncStatus          ──┐
02 安装串行 + 全 IO（竞态/ANR）        ──┤
03 package/server 拒绝 min>version     ──┼──► 06 文档诚实 + 关键测试补洞（可最后）
04 强制升级兜底（元数据失败仍有 UI）   ──┤
05 装前校验 APK 包名/版本/签名         ──┘
```

01–05 互不严格阻塞（可并行），建议实现顺序：**01 → 02 → 03 → 04 → 05 → 06**。

## Tickets

| # | Ticket | Blocked by | Status |
|---|--------|------------|--------|
| [01](./issues/01-install-failure-not-sync-error.md) | 安装/校验失败不污染同步状态 | — | done · [evidence](./evidence/01/validation.md) |
| [02](./issues/02-serialize-install-on-io.md) | 更新安装串行且全程 IO | — | done · [evidence](./evidence/02/validation.md) |
| [03](./issues/03-reject-min-supported-gt-version.md) | 拒绝 minSupported > versionCode | — | ready-for-agent |
| [04](./issues/04-force-shell-when-metadata-missing.md) | client_update_required 后强制 UI 兜底 | — | ready-for-agent |
| [05](./issues/05-verify-apk-identity-before-install.md) | 装前校验 APK 包名/版本/签名 | — | ready-for-agent |
| [06](./issues/06-docs-and-gate-test-gaps.md) | 文档诚实门槛 + 关键门禁测试 | 01–05 优先；可部分并行 | ready-for-agent |

## Frontier

- **现在可领：** 03、04、05（01–02 done）  
- **收口：** 06  

## Explicitly deferred

| 主题 | 说明 |
|------|------|
| 服务端对伪造 version 头的强绑定 | 产品诚实文档即可；真硬闸靠 wire/capabilities |
| package-nas 全量 docker CI | 06 可加轻量 shell 校验，不做完整 NAS 部署矩阵 |
