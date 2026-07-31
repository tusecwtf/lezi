# 09 — 家庭成员与设备页面

**What to build:** 在账户信息架构下新增“家庭成员与设备”页面，按成员称呼分组展示；普通成员只展开和管理自己的设备，管理员查看全部设备和待处理申请。

**Blocked by:** 05 — 管理员新设备登录、接管与根密码轮换；07 — 绑定既有成员与多设备权限

**Status:** complete

- [x] 服务端提供按当前会话角色裁剪的成员与设备投影；普通成员不得取得其他成员的设备明细或凭证状态。
- [x] 页面按当前家庭的成员称呼分组，标出管理员身份；普通成员可看成员称呼列表，但仅自己的分组展开设备明细。
- [x] 每台可见设备至少展示设备称呼和最近使用时间，不展示 token、内部 ID、IP、端口或证书技术字段。
- [x] 管理员可查看全部成员设备、待确认设备和待处理改名申请，并从相应分组进入审批和管理动作。
- [x] 账户页只展示家庭名、我的称呼、一句同步结果和“家庭成员与设备”入口；管理员有待确认设备时显示角标。
- [x] 不新增独立同步页或安全设置页；页面结构为后续添加、改名、删除成员和撤销凭证提供稳定入口。
- [x] 增加角色投影、Compose 状态、空态、错误态、动态字体和无障碍回归测试。

## Verification

- `GET /v1/family/members` now derives the current device marker from the authenticated session and returns role-trimmed device projections: Owner sees every active device, while Member sees device detail only for their own membership. The projection contains device name and last-used time but no token, credential state, endpoint, certificate, IP, port, or internal identifiers in rendered UI.
- The Account overview is limited to family name, self name, one sync-result sentence, and the grouped member/device entry. The Owner badge includes pending device count; the sheet supplies stable actions for pending devices, pending rename requests, add/rename, login QR, removal, and later credential revocation without adding a separate sync or security page.
- Rust formatting and Clippy passed; 38 library tests, 103 Router/API tests, and the HTTPS TLS integration test passed. Server tests cover Owner and Member projections, cross-family isolation, stable membership grouping, role redaction, current-device derivation, and safe empty-device projection.
- Android gates passed: sync 347/347, domain 293/293, family 19/19, onboarding 6/6, plus `:app:assembleDebug`. `:feature:family:connectedDebugAndroidTest` passed 15/15 on `lezi_api35(AVD) - 15`, including Owner/Member visibility, technical-field redaction, current/recent device labels, empty state, error retry at 200% font scale, and accessibility semantics. `git diff --check` passed.
- No live NAS or physical-phone run was performed for this non-release ticket. Spoken TalkBack and cross-device hardware acceptance remain ticket 16 evidence boundaries; no such result is claimed here.
