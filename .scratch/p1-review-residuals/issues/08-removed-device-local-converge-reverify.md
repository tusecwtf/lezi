# 08 — 被踢/撤设备本机收敛（0.3.1 后复核）

**What to build:** （复核票，默认不实现）确认 0.3.1 之后：管理员撤销某台设备或删除某成员后，该设备在**下次可信连接**收到明确结果并按合同收敛本地状态（例如 device_removed 清空家庭数据；与「普通凭证丢失保留本地」区分）。不要求离线即时远程擦除，但产品不得假装仍「已加入且可同步」却无限 Sticky Error。

**Blocked by:** 0.3.1 发布（trusted-sync 设备撤销、成员硬删、客户端 wipe 信号）

**Status:** planned

**Severity:** P1  
**Lane:** post-0.3.1-reverify  
**Implement before gate:** **禁止**

## Cutover 应已覆盖

- 撤销单台设备 → 明确 device_removed → 清本地家庭数据。
- 成员硬删 → 身份消失 + 受影响设备收敛。
- 与 generic 401 / refresh 丢失路径区分（见 06）。

## 0.3.0 残差（复核对照）

- 服务端 remove 会吊销凭证；客户端无分类处理，本地仍显示 joined 且保留副本。

## Re-verify checklist（0.3.1 后执行）

- [ ] 被撤设备在再次同步时是否收到明确信号并 unjoin/wipe（按合同）。
- [ ] 是否与「refresh 丢失保留本地」路径混淆。
- [ ] 管理员侧 remove 后被踢端是否长期卡在无意义 Error。
- [ ] **若已满足：** `Status: cancelled` + 证据。  
- [ ] **若仍不满足：** 升为 `ready-for-agent` 再实现。
