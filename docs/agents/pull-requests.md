# Pull requests (GitHub)

代码评审与合入 **`master` 的真源在 GitHub PR**，不再以「只推局域网 `origin`」作为默认合并路径。

## 与本地票单的关系

```
.scratch/<feature>/spec.md + issues/NN-*.md   →  要做什么
branch + GitHub PR                            →  怎么改、是否可合
docs/prd/ + CONTEXT.md                        →  合入后产品/用语真源
```

PR 描述里用相对路径引用票单，例如：

`.scratch/care-plan-record-customization/issues/03-….md`

不要把整张票复制进 GitHub Issue，除非人类明确要求公开跟踪。

## 开 PR

1. 从最新 `github/master` 拉特性分支。
2. 本地 `./gradlew test`（及必要时 cargo）。
3. `git push -u github HEAD`。
4. `gh pr create --base master` 或网页创建；套用
   [`.github/pull_request_template.md`](../../.github/pull_request_template.md)。

## CI

| Workflow | 触发 |
|----------|------|
| `Android unit tests` | push/PR 到 `master`（忽略纯 docs / lezi-sync-only 时可 paths-ignore） |
| `lezi-sync` | `tools/lezi-sync/**` 变更 |

CI 红不要强行合；例外需在 PR 里写明原因与跟进票。

## 合入后

- 删除已合分支（可选）。
- 把 `master` 推到局域网 `origin`，保持镜像。
- 关闭/更新对应 `.scratch` 票的 `Status:`（例如 done / partial）。

## 历史说明（迁移）

此前工作流偏局域网 Gitea 直推。自 GitHub remote + Actions 落地后：

- **票单**继续本地 Markdown（未迁 Issues）
- **PR / CI / 许可证展示**以 GitHub 为准
- 双 remote 职责见根 [`CONTRIBUTING.md`](../../CONTRIBUTING.md)
