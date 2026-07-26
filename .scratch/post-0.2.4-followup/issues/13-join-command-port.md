# 13 — Join 命令对象 + bootstrap 非空（Port 层）

**Parent:** [../spec.md](../spec.md)

**What to build:** 加入家庭收成单一命令（邀请材料 + 必填家网配置 + 可选展示名）；Port 内不做「saved vs QR」隐式优先级。建家 bootstrap 密钥在 Port 层拒绝空/空白。产品路径弱化/删除易脚枪的「只传码不传配置」别名（测试可保留 deprecated）。QR 只做表单预填，不在 Port 内重排真源。

**Blocked by:** 10 — 同步客户端三分离

**Status:** complete

## Acceptance criteria

- [x] Join 以命令对象表达；必填配置在调用方构造后传入
- [x] bootstrap 空/空白在进网前失败（Port 可测）
- [x] 产品路径不再依赖裸 joinWithCode 作为主入口（或明确 deprecated）
- [x] 接缝 S6 单测：命令校验、bootstrap 非空、join 失败模式
- [x] 本票不强制完成 onboarding/family UI 共用（见 14）

## Comments

- R2：从原 10 拆出 Port 半程。
