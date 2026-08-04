# 04 — 绿场 monorepo 隔离机制

**Type:** grilling  
**Status:** resolved  
**Blocked by:**

## Question

在同 monorepo 内，绿场如何与旧栈**硬隔离**：Gradle/Cargo workspace 形态、package/crate 前缀、构建入口、本机端口与数据目录、以及对「禁止依赖旧实现细节、旧码只读对照」的可执行规则。

## Constraints from map

- 旧栈继续服务现网；绿场只本机实验
- 禁止生产 CD
- 路径命名可在本票决定或给出默认并允许微调

## Answer

### 物理布局

| 部分 | 路径 / 入口 | 说明 |
|------|-------------|------|
| Android 绿场 | `greenfield/android/` | **独立** `settings.gradle.kts` + 自有 `gradlew`；根 `settings.gradle.kts` **不** `include` 绿场模块 |
| 同步服务绿场 | `greenfield/sync-server/` | **独立** Cargo workspace/crate；与 `tools/lezi-sync` 并列、无 path 依赖 |
| 本机数据 | `greenfield/.data/` | gitignore；禁止写家庭 NAS 数据 bind |
| 旧栈 | 仓库现有树 | 根 `./gradlew`、`tools/lezi-sync`、生产 deploy 脚本**不挂**绿场 |

### 身份与包

| 项 | 值 |
|----|-----|
| `applicationId` | `com.lezi.babylog.gf`（可与现网 App 同机并立） |
| Kotlin 根包 | `com.lezi.gf` |
| 显示名 | 可带「绿场/实验」后缀（实现时定文案） |
| 现网 `applicationId` | `com.lezi.babylog` 不变 |

### 本机运行隔离

| 项 | 规则 |
|----|------|
| 绿场 HTTPS 端口 | **18765**（非生产 8765） |
| 数据根 | `greenfield/.data/`（或实现时等价 gitignored 本机路径） |
| 默认 endpoint | **禁止**默认指向家庭 NAS `192.168.50.4:8765`；本机 loopback/局域网实验机显式配置 |
| 生产 CD | 绿场镜像/包**不**进入 `push-and-deploy` / 家庭 NAS 替换路径 |

### 依赖与对照规则

1. **硬禁止**绿场对旧实现的**编译期**依赖：不得 `project(":…")` / Cargo `path` 到 `:app`、`:domain`、`:sync`、`:feature:*`、`:core:*`、`:designsystem`、`tools/lezi-sync` 等。  
2. **允许**只读打开旧源码、`docs/prd`、`docs/adr`、`CONTEXT.md`、截图金线资产。  
3. Token/文案/组件在绿场**自有**演进；需要时人工复制后切断对旧路径的引用，**不**链旧 AAR。  
4. 可选门禁（实现阶段）：脚本扫描绿场 `build.gradle.kts` / `Cargo.toml` 越界依赖。

### 对后续票

- 模块内部分层仍由 [06 — 第一性原理分层与依赖规则](./06-first-principles-layering.md) 决定；本票只锁**与旧栈之间**的隔离壳。  
- 蓝图落盘后实现首步应先建上述空壳目录与构建入口，再填业务。  
