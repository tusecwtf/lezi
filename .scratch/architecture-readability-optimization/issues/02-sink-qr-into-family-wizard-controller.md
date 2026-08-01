# 02 — QR 登录进家庭向导并统一错误文案

**What to build:** 从账户或引导扫描/确认成员单次登录 QR 时，走同一套家庭向导状态机（校验、领取、取消进行中校验、领取后恢复）；失败时两端默认同一套家庭同步错误文案，而不是一边同步腔、一边泛化产品错误。

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] verify / claim / cancel / retry 由 FamilyWizardController（经既有 gateway）统一承担
- [ ] 账户与引导 host 不再各维护一份 QR Job/逻辑分叉
- [ ] 失败文案默认 familySyncError；仅纯本机信任写入等失败可用 productUiError
- [ ] 控制器或 host 层有可观察行为测试覆盖成功、失败与取消
- [ ] 不改变 QR 载荷、TOFU/SPKI、单次授权时效等产品合同
