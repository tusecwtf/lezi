---
triage: ready-for-agent
title: 0.4.8 generation 热接续与前台自动续完 full resync
tracker: .scratch（本仓库工单目录；AGENTS.md 指定 GitHub Issues 非跟踪器）
---

# 0.4.8 generation 热接续 + 前台续轮

## Problem

NAS/进程重启重铸内存 `generation`，全家被 409 `full_resync` 打回 cursor 0。一轮 120
秒装不下时，`recoverFullResync` 还 `deferCursorUntilComplete`，失败后从 0 重走；未识别
异常落到 `InvalidInput`（「称呼空了 / 本机草稿还在」），用户只能连拉。

## Solution

1. `{LEZI_DATA_DIR}/generation` 原子 0o600；生产路径 load-or-create，显式覆盖仍可换代。
2. 副本周期失败不得落到 `InvalidInput`。
3. full resync 每页耐久推进 cursor。
4. 前台未收敛静默再入队 `Foreground`；零进度熔断；不做 WorkManager。

## Status

in tree（本票实现）
