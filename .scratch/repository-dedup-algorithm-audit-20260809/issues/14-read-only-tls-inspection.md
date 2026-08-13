# 14 — `LEZI_TLS_INSPECT_ONLY=1` 必须真正只读

Status: implemented

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

- [x] absent、complete、partial、unsafe symlink、mode-700/read-failure 五种 inspect 前后，data-root
  inventory、inode、mode、mtime 与文件 SHA 完全不变
- [x] container inspection 使用 read-only bind；host inspection 不创建目录或临时文件
- [x] ordinary remote deploy 在 absent/partial/invalid/read-failure 状态 stop/rm 前退出，bind 零写入
- [x] 只有 `LEZI_ALLOW_TLS_BOOTSTRAP=1` + 独立验证 fresh root 才一次创建完整匹配 pair
- [x] complete identity 的 certificate/SPKI 输出与当前 runbook 兼容，任何普通 CD 都复用同一 identity

## Validation

- [x] 隔离 `mktemp` fixture 的 before/after inventory tests 与 `bash -n` 通过
- [x] authorized fresh bootstrap 测试只在开发者隔离 root/非生产端口运行
- [x] 不在家庭 NAS/live bind 上运行 certificate creation/replacement/partial/expiry/mismatch 测试
- [x] `shellcheck` 如环境可用则通过；当前环境未安装，已在本验收记录

## Implementation evidence (validated worktree based on fixed HEAD `c7e23f18`)

- `init-tls.sh` 的 host 与 uid-10001 container adapter 现在执行同一个
  `LEZI_TLS_IDENTITY_CLASSIFIER_V1` 判态程序。inspect-only 与 bootstrap flag 互斥；host adapter
  只读现有路径，container adapter 使用 `--mount ... readonly`，两者均不创建目录或临时文件。
  只有 read-only 判态精确返回 absent 且运维显式设置 `LEZI_ALLOW_TLS_BOOTSTRAP=1` 后，脚本才开启
  writable mount 并创建 TLS pair。`remote-deploy.sh` 的 inspection 显式清零 bootstrap，后续
  validate/bootstrap 调用显式退出 inspect-only，避免 ambient flag 让 capability 混用。
- `test-init-tls-read-only.sh` 通过 public shell seam 对 absent、complete、partial、symlink 与
  read-failure fixture 比对完整 inventory/type/inode/mode/mtime/SHA；container adapter 测试对
  readonly、uid 10001、唯一 classifier 与 `/data` 参数任一漂移 fail。另用本机既有
  `lezi-sync:0.3.12` 镜像（无 pull/build）真实执行 complete、partial、symlink、mode-700 read-failure
  以及 fresh bootstrap/复用/partial 矩阵，均只使用开发机 `mktemp` data root。
- `test-remote-deploy-tls-inspection-read-only.sh` 对 absent、partial、invalid、read-failure 逐态证明
  ordinary remote deploy 在 stop/rm 前失败，且 data bind 前后快照完全一致。更新后的既有
  certificate/SPKI drift harness 同时证明完整 identity 精确复用、同 key 重签证书也不可替换。
- Final gates：4 个 targeted TLS/deploy harness pass；全部 deploy shell `bash -n` 与
  `git diff --check` pass；`cargo fmt --all -- --check` pass；`cargo test --locked` 在允许开发机
  loopback bind 的环境中 227 unit + 181 API + 2 TLS pass；
  `cargo clippy --all-targets --all-features -- -D warnings` pass。当前环境没有 `shellcheck`。
  Independent serial fixed-point review：Standards 0 hard / 0 judgement；Spec 0 hard / 0 scope /
  0 judgement。
- 未构建 server image/package、未 push，未连接或探测家庭 NAS/live bind，也未执行 CD/前后端联调；
  所有 certificate creation/partial/mismatch 与 read-failure 测试均在开发机隔离 fixture。
