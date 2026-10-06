# Contributing

乐记是 private 仓库；默认协作者很少。本文件约定 **票单仍在本地 Markdown，代码评审迁到 GitHub Pull Request**。

## Source of truth

| 类型 | 真源 | 不是真源 |
|------|------|----------|
| 功能票 / spec | [`.scratch/`](.scratch/)（见 [`docs/agents/issue-tracker.md`](docs/agents/issue-tracker.md)） | GitHub Issues |
| 产品规格 | [`docs/spec/`](docs/spec/) | 聊天记录 alone |
| 领域用语 | [`CONTEXT.md`](CONTEXT.md) | 临时命名 |
| 架构决策 | [`docs/adr/`](docs/adr/) | 未记录的口头约定 |
| 代码评审与合并 | **GitHub Pull Request** → `master` | 仅局域网 push 无 PR（紧急热修除外） |
| 安全报告 | [`SECURITY.md`](SECURITY.md) | 公开 issue 贴 exploit |

文档目录与 ask-matt 流向见 [`docs/README.md`](docs/README.md)。

## License

贡献默认按根目录 [`LICENSE`](LICENSE)（**MIT**）授权，与 `tools/lezi-sync` 的 `Cargo.toml` `license = "MIT"` 一致。  
不要提交你无权以 MIT 再许可的代码或资源。

## 开发环境

见根 [`README.md`](README.md)：JDK 21、Android SDK、`local.properties`、密钥 gitignore。

```bash
./gradlew test
./gradlew :app:assembleDebug
# 若改 tools/lezi-sync：
cd tools/lezi-sync && cargo test --locked && cargo clippy --all-targets --all-features -- -D warnings
```

## 分支与 remotes

| Remote | 用途 |
|--------|------|
| `github` | **PR 与 CI 所在**（`tusecwtf/lezi`） |
| `origin` | 局域网 Gitea 镜像；可在 PR 合并后同步 |

推荐流程：

```bash
git fetch github
git switch -c feat/<short-slug> github/master   # 或 fix/…, refactor/…
# … 开发与本地测试 …
git push -u github HEAD
gh pr create --base master --fill   # 或在网页开 PR
```

- PR **base** 固定为 `master`。
- 标题建议：`type(scope): summary`（与现有 commit 风格一致）。
- 描述使用仓库 PR 模板（`.github/pull_request_template.md`），并链到
  `.scratch/<feature>/issues/NN-….md`（若有）。

### 合并后同步局域网

```bash
git switch master
git pull github master
git push origin master
```

紧急仅内网热修：可先推 `origin`，事后仍应补一个 GitHub PR 或至少把同一 commit 推到 `github/master`，避免双 remote 分叉。

## Pull Request 检查清单（摘要）

模板全文见 [`.github/pull_request_template.md`](.github/pull_request_template.md)。合并前至少：

1. CI 绿（Android unit tests；改了 `tools/lezi-sync` 则 cargo job 也要绿）
2. 无密钥 / 真实家庭数据 / keystore
3. 行为或 wire 变更时更新 `docs/spec/` 或 ADR：wire → `contracts/causal-sync-wire.md`；架构 / seam / 算法 → `architecture.md` 或对应 `layers/*.md`
4. 用语符合 `CONTEXT.md`（护理记录 vs 护理计划、同步包等）
5. 记录/计划照片包保持原子可见性（见 `docs/spec/` 与 `SECURITY.md`）

## Agent / 自动化

- 票单「publish to the issue tracker」→ 仍写 `.scratch/`，**不要**自动开 GitHub Issue。
- 需要人工或 CI 把关的代码变更 → 开 **GitHub PR**（可 `gh pr create`）。
- 标签字符串（`ready-for-agent` 等）只写在 Markdown 票的 `Status:` 行；不必镜像到 GitHub labels。

## 不要提交

- `local.properties`、`keystore.properties`、`*.jks`、`*.apk`
- `LEZI_BOOTSTRAP_SECRET`、family token、真实 SSID / 宝宝身份数据
- 生成物：`**/build/`、`tools/lezi-sync/target/`、`.scratch/**/shots|dumps|logs`
