# 03 — 分页 pull：硬上限与无 has_more 触顶 fail-closed

**Parent:** [../spec.md](../spec.md)

**What to build:** 家庭历史很多时，客户端仍可按页拉完（正常服务器）。若页数超过具名硬上限，或本页已达服务端页容量语义却缺少 `has_more`，同步必须失败并可重试——不得静默当成「已同步完」。旧服务器单页完整且未触顶、省略 `has_more` 时仍成功。

**Blocked by:** None — can start immediately

**Status:** complete

## Acceptance criteria

- [x] 多页 pull 使用具名最大页数常量（约 500 量级；注释说明家用库远低於此、超限视为异常）
- [x] 超过上限 → sync 失败（非「部分成功当完成」）；用户能感知失败/可重试
- [x] 本页触达页容量且响应缺少 `has_more` → fail-closed（不得默认 hasMore=false 后成功）
- [x] 正常带 `has_more` 的多页循环在上限内仍可拉完
- [x] 遗留：单页、未触顶、无 `has_more` → 成功（兼容）
- [x] 接缝 S3 单测覆盖上限、触顶无标记、快乐路径、旧单页
- [x] 本票不做同步客户端三分离（留给 10）

## Comments

- R2 定稿。触顶启发式以实现时服务端实体页上限为主，并在测试中写清。
