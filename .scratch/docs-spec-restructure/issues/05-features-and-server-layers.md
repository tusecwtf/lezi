# 05: layers/features.md + layers/server.md + 根 README 版本头

## What to build

1. `docs/spec/layers/features.md`：app 壳（`MainActivity` 导航图：`TopDest` 五目的地、
   全屏 push 路由、`rootChromeVisibility` 策略、`ConflictOverlayState` 冲突 overlay、
   本地数据升级流水线入口、外部导航信任）+ 10 个 feature 各一节（屏幕/VM/关键交互与
   UI 决策指针到 `contracts/ui.md` 对应 §；log↔timer 经 `TimerHandoffSeed` 与 composition
   root 的协作、无 feature↔feature 边）。
2. `docs/spec/layers/server.md`（`tools/lezi-sync`）：`ServerConfig` 环境变量表（含
   bootstrap secret 必填、TLS、限流、invite origins）、路由清单（health/ready、setup、
   app-update、family 生命周期、成员申请/登录授权、session refresh、sync handshake/
   heartbeat/causal commit/media、conflicts、source-relations、bundles、disaster-restore、
   维护只读中间件）、鉴权层（bootstrap secret / Bearer 会话 / TOFU 客户端侧）、
   `Store` 因果图算法（entity_versions/parents/stable_heads/mutation_receipts、
   `branched` 回执、每根最大开分支）、三方合并 + 内容哈希、准入、媒体两阶段暂存、冲突
   保留、schema 13 与 fresh-only、offline_migrate 维护窗。运维面指向 deploy/DEPLOY.md，
   不复制。
3. 根 `README.md`：版本头 0.3.9 → "以 `app/build.gradle.kts` + `config/android-release-
   compatibility.json` 为准"，不再硬编码具体版本号。

## Blocked by

01（模板）；02（contracts 锚点）

## Status

done

- [x] features.md 每 feature 有屏幕清单 + UI 决策指针，无 ui.md 内容复制
- [x] server.md 路由清单与 `lib.rs` 路由表一致（抽检）
- [x] README.md 不再含硬编码版本号
- [x] 两文均有权界声明（wire 字段唯一权威 = contracts/causal-sync-wire.md）

## Parent

[`../spec.md`](../spec.md)
