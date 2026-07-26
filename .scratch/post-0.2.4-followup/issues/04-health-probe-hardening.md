# 04 — 家网 health：禁重定向与响应限长

**Parent:** [../spec.md](../spec.md)

**What to build:** 家网门闩用的 health 探测不得因 HTTP 重定向或超大响应体被骗成「服务器可达」。合法的小 JSON 健康响应仍成功。策略与同步 HTTP 客户端 hardening 同量级（可共享读体模式，勿另起更松的一套）。

**Blocked by:** None — can start immediately

**Status:** complete

## Acceptance criteria

- [x] health 请求不跟随重定向；重定向响应不得判为健康/Allowed
- [x] 响应体有大小上限；超限不得判为健康
- [x] 正常小 body 健康响应仍成功
- [x] 与同步侧限长/禁重定向策略同量级（优先复用既有读体模式）
- [x] 接缝 S4 单测：重定向、超大 body、正常健康
- [x] 本票不改 endpoint 持久化真源（留给 12）

## Comments

- R2 定稿。对票 10（同步三分离）仅为软对齐，无硬阻塞边。
