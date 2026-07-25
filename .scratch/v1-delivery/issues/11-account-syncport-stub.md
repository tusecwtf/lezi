# 11 — 账户/共享 Stub + SyncPort NoOp

**Parent:** [.scratch/v1-delivery/spec.md](../spec.md)

## What to build

用户打开**账户**页看到家庭/本机说明与「**同步未开通** / 同步即将支持」；生成共享码、加入家庭等入口**可见**但点用后明确未启用，**不假装**联网成功。工程提供 **SyncPort NoOp**：`isEnabled=false`，pull/push 空成功，邀请类返回明确错误。全应用**无广告、无 IAP/会员**。

## Blocked by

01 — 工程骨架 + 创建首个宝宝

## Status

ready-for-agent

## 交付物

### 用户可见

- 底部 **账户** Tab 可进入，不崩溃
- 展示：本机/家庭说明、本地生成的标识（如乐记 ID / device 级 id，可匿名）
- 生成共享码 / QR / 加入家庭：UI 在；操作后文案说明同步开发中或等价，**无**「已加入某某家庭且开始同步」的假成功
- **无**会员订阅区、广告位、购买入口
- **无强制登录**门闸；未登录可返回记录页继续记账

### 工程产物

- `:sync`（或等价）模块：`SyncPort` 接口 + `NoOpSyncPort`
  - `isEnabled == false`
  - `pull` / `push`：成功空操作，不写脏业务表
  - 邀请/兑换码类：返回 `SyncNotEnabled`（或密封错误类型），不 throw 未捕获
- DI 绑定 NoOp 为 V1 默认
- 单测：NoOp 行为与「不污染 Record/Baby 表」

## 验收标准

### Must（可测 / 自动化优先）

- [x] **isEnabled**：NoOpSyncPort.`isEnabled` **== false**
- [x] **pull/push**：调用后返回成功/Completed；Record 表行数与调用前**相同**（给定 fixture）
- [x] **邀请 API**：createInvite / joinWithCode（命名以接口为准）返回 `SyncNotEnabled`（或等价），**不**插入 ShareInvite 为「已生效同步」状态
- [x] **不抛未捕获**：上述调用在测试中无 uncaught exception
- [x] **无 IAP 依赖**：app 依赖树不引入 Play Billing / 广告 SDK（构建脚本 grep/审查通过）

### 可人工冒烟

- [x] 账户页中文说明同步未开通；可见共享相关入口
- [x] 点生成共享码/加入家庭 → 得到明确「尚未支持」类反馈，非假成功进家庭
- [x] 从账户返回记录页，无登录墙
- [x] 全应用浏览菜单/账户无广告、无购买/会员入口
- [x] 引导「加入家庭」与账户 Stub 文案一致不互相矛盾

## 不在本票范围

- RealSync、双机 60s 可见、下拉刷新真同步（V2）
- 部分字段共享、伴侣逐条推送（明确不做）
- 上架隐私政策长文完备（可后做；关于页一句即可）
- 换机备份恢复真能力（可选说明文案）
