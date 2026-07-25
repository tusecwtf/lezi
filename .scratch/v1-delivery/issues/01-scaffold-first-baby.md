# 01 — 工程骨架 + 创建首个宝宝

**Parent:** [../spec.md](../spec.md) · PRD `docs/prd/README.md` §1–2、§5 引导

**What to build:** 可安装「乐记」；中文引导创建首个宝宝（昵称/性别/生日/主题色）；本机 Family/User/Baby；进入当日记录空态；无登录。

**Blocked by:** 无 — 可立刻开工

**Status:** done

## 交付物

| 类型 | 内容 |
|------|------|
| 用户可见 | 安装后完成引导；记录页空态显示昵称与日龄；主题色顶栏 |
| 工程 | Gradle 工程 `com.lezi.babylog`；Room 最小表；`CareLog.createBaby`；debug APK 可装 |

## 验收标准（Must）

- [x] **包身份**：applicationId=`com.lezi.babylog`，桌面名「乐记」，minSdk≥26
- [x] **无登录**：冷启到建档全程无账号/密码/强制网络
- [x] **建档必填**：昵称非空、生日有效；主题色可选 ≥6 种并生效于顶栏
- [x] **数据落库**：force-stop 后重开，同一宝宝仍在（Room）
- [x] **CareLog**：单测 `createBaby` 后 `getCurrentBaby()` 非空且字段一致
- [x] **空态**：无记录时有引导文案（非白屏/崩溃）
- [x] **中文**：引导与记录顶栏文案为简体中文
- [x] **无商业**：无广告 View、无 IAP/订阅入口

## 验收标准（Should）

- [x] 「加入家庭」入口可点，说明同步未开通（完整 Stub 可留给 11）
- [x] 触控主按钮 ≥48dp 量级

## 不在本票范围

- 任意记录类型写入、计时、同步、多宝宝、深色完整设置页
