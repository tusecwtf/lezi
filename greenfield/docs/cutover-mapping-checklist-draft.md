# 绿场切换映射表草案与清单（票 39）

**性质：** 映射草案与意图清单；**不含**生产 NAS CD / ssh / docker / push-and-deploy 命令或密钥。  
**权威行为：** `docs/prd/` · `docs/adr/` · `CONTEXT.md` · G1–G10。

---

## 1. 范围与冻结语义

- 切换在维护窗执行：新栈 fresh + 数据映射导入 + 会话重建。
- **冻结：** 护理记录≠计划；原子同步包；membership≠凭证；本地优先；唯一 Owner。
- **不做：** 长期双协议；两已配置家庭合并；本批生产 CD。

## 2. 实体映射表

| 旧概念 | 绿场实体 | 可迁？ | 说明 |
|--------|----------|--------|------|
| Baby | family.Baby / wire baby | 是 | 权威宝宝由 Owner 导入；成员孤宝宝不上传，按并入规则 |
| Record + photos | care.CareRecord 原子包 | 是 | 映射 client_uuid；照片齐备才可见 |
| CarePlan + photos | care.CarePlan 原子包 | 是 | 状态机 PENDING/MISSED/COMPLETED/SKIPPED |
| CustomItemDef | care.CustomItemDef | 是 | 定义同步；布局坞槽 **不可迁/不同步** |
| Layout / dock | LayoutSnapshot | **不可迁** | 本机设备快照 |
| Theme / dark / handedness | settings.LocalSettings | **不可迁** | 本机 |
| Family / Membership / Device | server membership graph | 部分 | 身份重建；旧 token **不迁** |
| DeviceSession / refresh | 新 session | **不可迁** | 全员重建会话 |
| LocalUser display cache | LocalAccount.displayName | 是（展示） | 对齐 membership |
| Non-adopted fulfill | nonAdopted store | 是 | 不进普通时间轴 |
| App update meta | /v1/app-update | 新 | 本机 mock 可测壳 |

## 3. 媒体与原子包

- 记录/计划与其 ≤3 照片同一可见性单元；接收方禁止半包。
- 导入时缺媒体则整包失败或暂不可见，不得先出元数据。
- 草稿自有照片仅在本机；取消草稿清理 draft-owned。

## 4. 服务端顺序（意图，无运维命令）

1. 独立环境新 server + 空数据根。  
2. 映射导入护理/宝宝/定义（无旧凭证）。  
3. 验证 capability / setup-status=configured。  
4. 不在此清单写入生产部署脚本。

## 5. 客户端顺序

1. 安装 `com.lezi.babylog.gf`（或正式包去掉 `.gf` 的后续努力）。  
2. TOFU 信任新 endpoint。  
3. Owner 登录/恢复会话；成员重新申请或 QR。  
4. 前台对账拉全量。

## 6. 会话与信任重建

- 全员重新建立 DeviceSession；旧 refresh/access 作废。  
- SPKI 变更硬阻断；同 family 改地址可恢复。  
- 根密码仅 Owner 操作时使用，App 不持久化。

## 7. 成功判据（G* 子集）

至少：

- G1 离线记账仍可用（本地数据保留或映射后可见）  
- G3 Owner 会话与家庭名可见  
- G5 原子记录+照片跨端  
- G10 退出/离开策略正确  

完整 G1–G10 见票 40。

## 8. 失败回滚意图

- 前提：旧栈数据与旧 server **未毁**。  
- 回滚：客户端切回旧包/旧 endpoint；新 server 可弃用。  
- 不合并两家庭数据。

## 9. 明确不做

- 双协议并行长期支持  
- 合并两个已配置家庭  
- 本决策地图内生产 CD / 密钥材料  
- offline-migrate 运维工具（另开）
