# lezi-greenfield · 文档专用 worktree

**路径：** `/home/zhangtianshu/lezi-greenfield`  
**分支：** `greenfield/workspace`  
**主仓：** `/home/zhangtianshu/lezi`（`master`，完整代码）

## 用途

只做绿场重写的**决策对照与实现票推进**，不在此树编译旧 App/旧 lezi-sync。

## 保留内容

| 路径 | 作用 |
|------|------|
| `CONTEXT.md` | 领域术语 |
| `AGENTS.md` / `CONTRIBUTING.md` / `README.md` | 代理与贡献约定 / 产品总览 |
| `docs/prd/` `docs/adr/` `docs/design/` `docs/agents/` | 产品与架构权威 |
| `.scratch/greenfield-rewrite-blueprint/` | wayfinder 地图 + blueprint |
| `.scratch/greenfield-implementation/` | 实现票 01–41 |

## 刻意不在此树

- `app/` `feature/` `domain/` `sync/` `tools/` 等全部产品代码  
- 其它 `.scratch/*` 历史项目  
- `docs/reviews` `docs/research` 等非合同文档  
- Gradle/Cargo 构建入口  

## sparse-checkout

本 worktree 启用 **sparse-checkout（no-cone）**。改集合：

```bash
cd /home/zhangtianshu/lezi-greenfield
git sparse-checkout list
# 需要代码时另开实现 worktree，或在主仓操作，勿把 11G 树拉回本目录 unless 有意
```

## 与主仓同步

- 在主仓改代码；在本树改蓝图/票/文档。  
- 合并时注意：本分支提交以文档与 `.scratch/greenfield-*` 为主。  
- 删除本 worktree：`git -C /home/zhangtianshu/lezi worktree remove lezi-greenfield`

## 开工

实现从 [01 空壳](.scratch/greenfield-implementation/issues/01-greenfield-shell-isolation.md) 开始；原则见 [blueprint.md](.scratch/greenfield-rewrite-blueprint/blueprint.md)。
