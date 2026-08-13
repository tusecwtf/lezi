---
status: accepted
---

# offline-migrate 是显式 copy-out 迁移，不是启动迁移

`lezi-sync offline-migrate` 是家庭 NAS 维护工具。当前发布合同只接受冻结、完整的
server schema 11 或 12 data root，并在开发机写出一个新的 schema 13 data root。
服务启动和 ordinary CD 始终只接受精确 current schema 13；它们不得调用迁移器。

历史 v3→12 代码作为审计历史保留，但不再由当前 CLI 接受，也不是 0.4.0 切割输入。

## 不变量

| 不变量 | 合同 |
|---|---|
| 源只读 | `--in` 必须是独立 copy-out；迁移前后完整源树内容摘要一致 |
| 固定输入 | 只接受 frozen exact-shape `user_version=11` 或 `12` |
| 新目标 | `--out` 必须独立且为空/不存在；在私有临时根重建，通过后才原子 rename |
| 完整身份 | membership/device/session/token hash、`server.secret`、TLS cert/key 成对且字节保持 |
| 完整事实 | family facts、immutable versions、stable heads、branches/conflicts/resolutions、source relations 全保留 |
| 完整媒体 | `media/` 全树逐字节复制；staged/consumed 权威引用重新校验 size/SHA-256 |
| fail closed | wrong version/shape、SQLite sidecar、secret、TLS、media、integrity/FK/row count 任一失败都不产出可 promote 根 |
| 派生状态 | schema 13 新增的 reverse index、GC cursor/upload 表按 current schema 重建；不伪造业务事实 |

源必须是已经 checkpoint 的离线快照。出现 `lezi.db-wal`、`lezi.db-shm` 或
`lezi.db-journal` 即拒绝；迁移器不会猜测 sidecar 是否完整。TLS 必须已有完整、未过期且
证书/私钥匹配的 `tls/server.crt` + `tls/server.key`，不得生成、替换或修复身份。

## CLI

```text
lezi-sync offline-migrate dry-run  --in <backup_data_dir>
lezi-sync offline-migrate migrate  --in <backup_data_dir> --out <out_data_dir>
lezi-sync offline-migrate validate --out <out_data_dir>
lezi-sync offline-migrate copy-out-help
lezi-sync offline-migrate copy-back-help
lezi-sync offline-migrate live-cutover-help
lezi-sync offline-migrate help
```

旧脚本传入的 `--new-root-password` / `LEZI_MIGRATE_NEW_ROOT_PASSWORD` 暂时只为 argv
兼容而接受，迁移器不读取它来生成或轮换身份；短的非空兼容值仍作为配置错误拒绝。

`dry-run` 在开发机临时目录执行同一 migrate→validate 流程并清理；`migrate` 产出独立
schema 13 根；`validate` 复核 exact Store shape、quick/integrity/FK、媒体、secret 与 TLS。
三者均不 stop/rm、copy-back、SSH 或运行 CD。

## 维护窗边界

H28 只实现 source→13 migrator。H29 的独立 `deploy/schema-cutover.sh` 实现 schema-13
copy-back/CD orchestration，并复用 outer lease、age credential/TLS guards；ordinary
`push-and-deploy.sh` 仍不可调用 migrator。H29 通过不等于生产可切割：H30 的隔离 rollback
rehearsal 与 release ticket 09 的重新明确维护窗确认仍是前置门。当前 legacy
`copy-back-nas-data.sh` 不可用于 schema 13 现网切割。

最终维护窗仍必须在外层 lease、签名 APK/image/package attestation、off-repo age 加密的
credential/full-data rollback、旧 image/package pin、TLS certificate/SPKI pre/post equality
与实际协议 health/ready 门禁下执行。普通 CD 永不运行 `offline-migrate`。

## 禁止

- startup、ordinary CD 或 NAS build 自动迁移；
- 对 live/source `lezi.db` 执行 `ALTER`、checkpoint 或任何写入；
- 遇到 sidecar、残缺 TLS、失效证书、错配私钥或媒体缺失时“修复后继续”；
- 只改 `PRAGMA user_version`、跳过 exact shape/row/FK/integrity/media 验证；
- H28 完成即声明 NAS 已切割或执行 `push-and-deploy.sh`。
