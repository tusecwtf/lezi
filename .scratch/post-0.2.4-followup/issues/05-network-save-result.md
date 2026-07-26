# 05 — 家庭网络保存：显式 Result（去文案子串控制流）

**Parent:** [../spec.md](../spec.md)

**What to build:** 照护者保存家庭网络/服务器配置时，成功与失败由显式 Result（或等价 sealed）驱动后续 UI（例如保存后再创建/加入），不再用中文提示是否包含「已保存」等子串决定分支。改文案不得弄坏流程。

**Blocked by:** None — can start immediately

**Status:** complete

## Acceptance criteria

- [x] 保存家网配置成功/失败由 VM/用例显式结果回到 UI，无 `contains("已保存")` 或等价字符串嗅探控制流
- [x] 成功与失败续体（含「保存后再…」）在文案变更后仍正确
- [x] 本票不含完整 PRD/威胁模型长文（见 06）；不拆家庭五件套（见 07/08）
- [x] 仅允许最薄提取以露出 Result 类型

## Comments

- R2：与 01 无硬依赖（假边已删除）。文档见 06。
