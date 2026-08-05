# Issues — 0.3.9 家庭同步协议切换与历史收敛

Status: implementation complete; NAS deployed on `ff80edbf`; ticket 04 live joined-client gate blocked

| # | File | Title | Blocked by | Status |
|---|------|-------|------------|--------|
| 01 | [issues/01-protocol-epoch-reset.md](./issues/01-protocol-epoch-reset.md) | 0.3.9 协议代际切换与无损状态重置 | — | complete (`ff80edbf`) |
| 02 | [issues/02-defer-legacy-incomplete-fulfillment.md](./issues/02-defer-legacy-incomplete-fulfillment.md) | 隔离旧协议不完整履行并恢复全家庭 pull | 01 | complete (`ff80edbf`) |
| 03 | [issues/03-heal-record-and-close-commit-gap.md](./issues/03-heal-record-and-close-commit-gap.md) | 重对账修复历史 Record 并封闭未来提交窗口 | 02 | complete (`ff80edbf`) |
| 04 | [issues/04-release-0.3.9.md](./issues/04-release-0.3.9.md) | 发布 0.3.9 APK、Docker 与 NAS CD 验收 | 03 | blocked: no valid retained joined-client session |

Parent: [spec.md](./spec.md)

## Dependency graph

```text
01 protocol epoch + lossless reset
  └─► 02 quarantine legacy incomplete fulfillment
        └─► 03 heal historical Record + close commit gap
              └─► 04 release APK/image + confirmed NAS CD
```

## Acceptance frontier

- [04 — live joined-admin and second-client recovery](./issues/04-release-0.3.9.md)
- Receipt: [acceptance-0.3.9.md](./acceptance-0.3.9.md)

## 无感升级口径

Android 仍会按系统安全模型要求用户确认安装或升级 APK；安装完成并首次进入前台后，协议切换、
同步元数据重置、全量重对账和历史 Record 回补必须自动完成。用户不需要清 App 数据、退出或重建
家庭、重新扫码、重新信任 NAS，或手工修改服务器数据库。

该保证以至少一个仍获授权的客户端保有真实 Record 为恢复前提。当前领域写入把 Record、计划完成
状态与履行候选放在一个 Room 事务中，因此正常覆盖升级会保留管理员副本；若事实已被从所有设备
删除，0.3.9 只能隔离残留证据并恢复其它同步，不能伪造护理记录。
