---
status: accepted
---

# 身份删除的有限不可变内容例外与恢复精简回执

2026-10-07，用户批准两项代码设计：删除成员时，历史版本与回执内身份也匿名化，护理事实保留；恢复成功后删除完整临时清单，保留精简回执维持原有重试能力，家庭删除后回执失效。本决策不授权操作真实家庭数据、外部备份或部署，不改变 schema、wire keys、floor 或权限。

## 成员删除

- 删除事务覆盖稳定实体、Wake 观察者、不可变版本、版本 provenance、已接受回执、来源关系和派生资格。保留护理字段、照片、版本 ID、父边与明确的事实选择；成员/设备身份不再通过任一读取、重放、Owner 后续编辑或 resolution 返回。
- 对 ADR-0020 的不可变内容与 ADR-0022 的历史回执内容作**仅身份元数据**的删除例外。重算被改写内容的存储完整性 hash，不伪造新护理事件或修改业务事实。opaque 请求 hash 仅保留幂等匹配用途，不保存旧身份映射或原始含身份 envelope。
- 匿名版本 provenance 的 actor_id / device_id 使用统一、不可回溯到原身份的非空占位值 `__anonymous__`，保持既有客户端非空字符串校验；护理根的作者／观察者字段仍为 null。不可认证的被删成员回执使用逐行随机内部 lookup key，不保存原成员映射，存储请求 hash 不变。
- 历史 migration_base 按原始 root／media JSON 的存储 hash 与媒体归属校验后匿名化；不为删除操作把既有 nullable／长 MIME 强转成较窄的 canonical 媒体格式，不改写或丢弃原始媒体证据与字节。
- 离线旧 base version ID 仍指向同一护理历史。作者/观察者盖章不作为业务冲突；服务器匿名事实 Owner-only ACL 始终优先，携带旧作者字段不恢复成员权限。
- 受影响的既有 ConflictSnapshot token/choice 全部失效，使用现有失效/刷新路径；不得把旧 CAS 或 choice 偷换成新候选。刷新后的冲突仍保留原护理差异和媒体。
- 已接受请求的精确重放仍匹配原请求 hash，保持原 outcome、mutation/version 身份和 replay 标记，但事实与 provenance 返回匿名版，不再逐字节返回旧身份信息。同 mutation ID 的不同请求仍拒绝 content drift；被删除身份先在认证边界拒绝。
- 匿名化不缩小 ADR-0023 §2 / wire §12 的自动归组范围：作者存在与否不是近邻准入条件。匿名记录继续使用相同宝宝/类型/时间/名称分片及开放睡眠排除规则；缺少称呼时沿用既有空称呼排序 fallback。Owner 普通编辑仍触发自动收口，显式改选仍可用，成员删除本身不拆除或重选既存来源关系。
- 删除与 commit/resolve 由 Store 事务串行化，任何提交顺序都不能重新暴露旧身份。其它成员身份不受影响。此承诺不等于抹除离线设备持有的旧副本或磁盘物理安全擦除。

### Android 身份元数据收敛

- 认证 pull 的显式 JSON null 作者／观察者在完整 root 类型与引用校验后、同 version ID／本地 dirty／冲突门禁前单独合入。仅清除身份列，不改护理字段、照片、版本 ID、本地修改时间、待提交状态或冻结重试 envelope；缺失字段与字符串 `"null"` 不表示删除。
- push-only 收到已接受回执的匿名重放时，即使本地护理 epoch 已更新，也在完整 proof 校验与原 mutation CAS 通过后于终态事务单独清除身份。较新的护理内容与 dirty intent 保留，旧 envelope 只按原终态回执流程退休。
- Wake 的 pull 与 stable proof 接受显式 null 观察者；历史 ConflictSnapshot 与 resolution 回执的五类 root 接受显式 null 身份。必需 key、其它类型与空字符串校验保持关闭失败。
- 收到明确身份匿名化的 root 时，失效其旧 complete／分页中 ConflictSnapshot 缓存与加载 lease，保留冲突摘要、分支与冻结重试。重新读取使用服务器的新 token／choice，不能继续展示或提交被撤销的身份快照。

## 恢复成功与家庭删除

- 恢复的 identity/事实激活成功后，完整 manifest 与冗余 staging bytes 退休。先耐久保存可重放的精简成功回执，再移除完整临时内容；崩溃/重启可幂等完成退休。
- 家庭仍存在时维持现有成功 commit 重试期限，不新增 24 小时的 committed 过期限制。精简回执仅保留验证请求与重建原 commit 响应必需的字段；不含历史护理实体、照片清单或不必要称呼。
- 重放不会重建已撤销 session、绕过根密码或恢复失效权限；返回的原凭据仍受现有 session 状态约束。
- 家庭删除连同其恢复清单、精简回执及 credential envelope 一起失效；旧请求走现有缺失批次的 401 无效恢复凭据路径，不再返回旧成功身份；与随机/无效凭据保持同一反枚举语义，不新增墓碑或残留凭据 hash。文件与数据库的故障窗口必须可恢复，不能在完整内容仍可访问时宣布删除完成。

## 验收

以公开 Store/API 和隔离临时 data root 验证：成员删除后旧 base 编辑、旧 accepted receipt 重放、旧 snapshot/CAS 拒绝、刷新与 resolution、Wake/来源关系、重启；恢复丢回包后的跨重启重放、提交与清单退休之间故障、家庭删除后重试及所有内容文件退休。先失败后修复的行为证据与每个 gate 的实际执行结果另行记录，ADR 本身不是实现通过证明。

## 持久化兼容说明（BN-03）

- 不修改数据库 schema、恢复 protocol_version 或 journal 字段类型。终态仍使用 `status=committed`；仅把已无用途的 start_request_id、owner_display_name、device_name 清空，created_at 置零，manifest_request_id/hash 置 null。其余原成功响应、认证及状态响应所需字段保留原值。原 reader 在 committed 分支不读取被清空字段，不需要 manifest；不可把精简回执恢复为 open。
- 读取旧 committed journal 时幂等精简并退休全部附属文件；启动先用 family→membership→device→session 原始 ID 关系确认 SQLite 已激活，再应用 open 的 24 小时期限。匹配不要求凭据仍有效，不重新写入 session；已过期、刷新或撤销的凭据不会因此复活。
- 家庭删除提交后才移除该家庭恢复目录；文件失败返回失败并由“已不存在家庭＋committed journal”启动恢复继续清理，不能报告完整删除成功。缺失 journal 的部分删除目录由既有启动孤儿清理收尾。
- Android 对恢复 status 的 401 清理恢复 checkpoint 并要求重新开始；不清除另外仍存在的正常家庭会话。此调整不改变通用身份认证错误处理。
- 已提交回执也以原完整身份链确认归属，不仅检查家庭 UUID：删除后同 UUID 的新恢复不能继承旧回执。刷新/撤销保留原 session 关系而不恢复其权限。
- 删除失败遗留的最终媒体不能盖过新恢复清单：持有 provisioning 排他权且确认服务仍无家庭后，已验证 staging 字节原子替换同路径遗留文件；没有 staging 时仅复用已通过原 size/hash 校验的最终字节。
- 兼容证据包含真实进程：新版本生成精简回执后，固定旧版本二进制重启并逐值重放原响应；临时 staging 目录权限故障命中 DB 激活之后，重启保持事实与重试。均为隔离合成数据，不触及真实家庭。
