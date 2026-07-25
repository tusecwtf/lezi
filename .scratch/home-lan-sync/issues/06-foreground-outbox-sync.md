# 06 — 前台触发 + Outbox（baby/record/media）

**Parent:** [../spec.md](../spec.md) · PRD `sync-home-lan` §1#8、§2.3、§5
**Blocked by:** 05；API 契约 02–04（可用 FakeSyncBackend）
**Status:** done

## What to build

- Outbox 覆盖 **baby / record / media** 元数据
- Record 同步载荷使用 **baby_client_uuid**
- 触发（**仅前台**）：
  - ProcessLifecycle **ON_START** / 回前台 → push + pull
  - 下拉 → push + pull
  - **前台写成功** → push（门闩通过时）
- **禁止**：WorkManager 周期同步、后台 60s、FCM 拉同步、为同步常驻 FGS
- SettingsLocal / 下次喂奶 **不**入 Outbox
- 媒体：压缩后 PUT；缺图 pull 后下载

## 交付物

| 工程 | SyncPort 实现对齐规格触发与实体 |
| 用户可见 | 打开 App 后数据可对齐；写后上 NAS（在家时） |

## 验收标准（Must）

- [x] 前台写 record → Outbox →（allowSync 时）push 被调用
- [x] 进后台后无周期同步任务注册（或可证明不调度）
- [x] allowSync=false 时写本地成功且 Outbox 保留
- [x] pull 合入 Room 后时间轴可见对端记录
- [x] dark mode 变更不出现在 Outbox

## 不在本票范围

- 账户扫码 UI（07）
- 真机双端正式验收（09）

## Comments

- 2026-07-25：已实现进程 `ON_START`、记录页下拉和 `LocalWrite` 三种显式
  前台 trigger；无 WorkManager/后台轮询路径。
- `CareLog` 写入会触发本地同步请求；`RealSyncPort` 先持久化
  baby/record/media Outbox，再由 Wi-Fi + health + 前台门闩决定网络行为。
- 自动化覆盖离线保留 Outbox、依赖批次、增量 dirty 标记、前台中止、
  pull 引用顺序、媒体压缩上传/缺字节下载与日志媒体 portable 关联。
