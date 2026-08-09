# 14 — `LEZI_TLS_INSPECT_ONLY=1` 必须真正只读

Status: ready-for-agent

Priority: P2

Audit baseline: `38cfbe7dbcf1f410237dce77b50ec1bd4854d8a6`

Blocked by: None.

## Findings

- `deploy/init-tls.sh:31-48` 在检查 `inspect_only` 前，host 分支先 `mkdir -p <data>/tls`，container
  分支先以 uid 10001 执行 `mkdir -p /data/tls`。
- `inspect_only` 到 `:148-150` 才退出；ordinary `remote-deploy.sh:431-435` 正是调用这条路径。
- 因此“检查 absent/partial/permission failure”已经改变 live data bind，违反家庭 NAS certificate
  check 只读不变量，也可能把本应证明 fresh 的 root 变成被工具预创建过的 root。

## Interface boundary

inspection 与 bootstrap 是两个 capability：inspection 只允许 read-only mount/stat/openssl read；任何
mkdir/chmod/temp/generation 都必须位于显式 bootstrap 分支，并且只能在 fresh-root guards 全通过之后。

## Acceptance

- [ ] absent、complete、partial、unsafe symlink、mode-700/read-failure 五种 inspect 前后，data-root
  inventory、inode、mode、mtime 与文件 SHA 完全不变
- [ ] container inspection 使用 read-only bind；host inspection 不创建目录或临时文件
- [ ] ordinary remote deploy 在 absent/partial/invalid/read-failure 状态 stop/rm 前退出，bind 零写入
- [ ] 只有 `LEZI_ALLOW_TLS_BOOTSTRAP=1` + 独立验证 fresh root 才一次创建完整匹配 pair
- [ ] complete identity 的 certificate/SPKI 输出与当前 runbook 兼容，任何普通 CD 都复用同一 identity

## Validation

- [ ] 隔离 `mktemp` fixture 的 before/after inventory tests 与 `bash -n` 通过
- [ ] authorized fresh bootstrap 测试只在开发者隔离 root/非生产端口运行
- [ ] 不在家庭 NAS/live bind 上运行 certificate creation/replacement/partial/expiry/mismatch 测试
- [ ] `shellcheck` 如环境可用则通过；不可用时在验收报告明确记录
