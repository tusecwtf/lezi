# 05 — 拷出、dry-run、校验 CLI

**What to build:** 一条运维可跟的本机流水线：**从 NAS 拷出** data 到本地 backup（只读保留）、对副本 **dry-run/正式迁移**、用当前二进制 **校验** 产出。默认不改 NAS；报告含计数与失败原因。为 06 的拷回提供「已校验的 out/ 目录」约定。

**Blocked by:** 02 — 本机离线库迁移器 v3→当前；03 — 媒体文件与 publications 映射；04 — 根密码重置与当前服务可启动

**Status:** ready-for-agent

## Acceptance criteria

- [ ] 文档/脚本步骤：SSH/scp 或 rsync 从 NAS 数据卷拷出完整 data（含 db、media、secret 等）到带时间戳的本地 backup
- [ ] 迁移只读 backup、写入独立 out/（或显式 --in/--out），永不就地改 backup
- [ ] dry-run 或 validate 模式：成功打印将迁对象计数；失败非零退出 + 报告
- [ ] 校验步骤：当前 lezi-sync（或与其同构的预检）对 out/ 通过 ready/预检
- [ ] 明确：本票不停止现网容器、不拷回

## Out of scope

- 维护窗替换现网（见 06）
