# 跨成员睡眠醒来 ACL 与本机纠错窗

Status: ready-for-agent

Target release: **versionName `0.3.11`**（接续当前 `0.3.10`；实现时单调抬高
versionCode，具体整数以发版清单为准）。

Authority: [`CONTEXT.md`](../../CONTEXT.md)；
[`docs/prd/data-model.md`](../../docs/prd/data-model.md) 家庭权限与睡眠配对；
[`docs/prd/ui.md`](../../docs/prd/ui.md) 睡下/醒来；既有多设备 open sleep 收敛
（历史票 family-sync-hang `32-family-global-open-sleep`，heal 路径已 complete，本专题不重做）。

## Problem Statement

妈妈在设备 A 记「睡下」后，爸爸在设备 B 点「醒来」时，本机往往能闭合开放睡眠，但
上传被家庭服务器拒绝（非作者不得改他人护理记录）。时间轴上该条对爸爸 `canEdit=false`，
事后也无法纠错醒来时刻。产品文档已写「家庭 wake 是宝宝级事实」，同步侧也有 pull 后
按已闭合睡眠 heal 本机 open 的路径，但 **跨 membership 的醒来写入 ACL** 与 **本机
短暂纠错** 未闭合，导致「记了醒来却同步不了 / 编辑没效果」。

半成品（树内可能已有）：服务端对 open→close 的粗放行、本机 `confirmSleep` 醒来不查
全量 manage。0.3.11 必须把字段子集、本机 B1 纠错窗、时间轴受限编辑与发版验收收齐，
且 **不** 新增 wire 上的 closer 盖章字段。

## Product decisions (grilled, locked)

| # | Decision |
|---|----------|
| 1 | 非作者 **首次醒来**：可写 **end + 备注 + 照片**；不可改睡下 `timestamp`、is_nap 等开睡字段 |
| 2 | 闭合后可再改 end（及同字段集）的主体：**原作者**，或 **本机 B1 闭合者**（资格期内） |
| 3–5 | **不**新增 closer 服务端字段；B1 = 仅本机刚用醒来路径闭合过的那条；资格 **持久到 dirty 收敛或权威更高修订覆盖** |
| 6 | B1 窗内权限 = 与首次醒来相同：**end + 备注 + 照片** |
| 7 | **Owner 始终全权**（可改全部字段、可删） |
| 8 | 并发多次闭合：**普通 LWW**（`updated_at`） |
| 9 | 服务端对非作者 open→close：**强制保留**已发布 open 的开睡字段，只合并 end/备注/照片（及同包 log media） |
| 10 | B1 资格期内时间轴 **亮受限编辑**（编辑亮、删除灰；面板只暴露允许字段） |
| 11 | composer / sleepUp / 小组件等闭合入口 **同一套** 规则 |
| 12 | 非作者 **永不** soft-delete 该条（含 open 与 B1 窗内） |

### B1 与权威的必然推论

B1 是 **本机** 特权，服务端无 closer。因此：非作者在 **已闭合** 睡眠上再次 push 改
end，服务端仍 **403**。资格在 dirty 收敛或权威覆盖后作废后，爸爸不能再把新的 end
推上家庭；再改只能作者或 Owner。这是锁定决策，不是实现偷懒。

## Solution

0.3.11 做 **两件** 可独立验收的事：

1. **跨成员家庭 wake 可发布 + 字段合同 + 本机 B1 与受限编辑**  
   任意家庭成员可将他人仍 open 的 sleep 闭合为醒来并成功进入家庭权威图；作者戳不变。
   非作者路径只合并 end/备注/照片；开睡字段由服务端与本机强制保留。本机闭合者在
   B1 窗内可受限再改；时间轴 capabilities 与 composer 同构。Owner 全权；删除仅
   作者/Owner。并发 LWW。所有醒来入口一致。

2. **合同收口与 0.3.11 发版门**  
   PRD/术语与行为对齐；Rust/Android 门禁；签名 APK 与更新 metadata；用户确认后
   NAS CD；双机冒烟矩阵。

## User Stories

1. As a non-owner parent, I want to record 醒来 after my partner recorded 睡下, so that the family timeline shows one closed sleep after sync.
2. As the parent who just recorded 醒来 on this phone, I want to fix the wake time, note, or photos before sync settles, so that small mistakes do not require the original author.
3. As the original author or family owner, I want full edit/delete of the sleep fact after close, so that long-term ownership stays clear.
4. As a family member who did not author the sleep, I must not be able to delete it or rewrite the sleep-down time, so that authorship stays meaningful.

## Out of scope

- 服务端 `sleep_closed_by_membership_id` 或任何 closer 盖章字段  
- 近邻重复（sleep 不在近邻白名单）  
- 重做多 open UUID heal（既有 family-global open sleep 行为）  
- 保育只读角色、字段级 ACL 的其它记录类型  

## Release

- Android + lezi-sync **0.3.11**  
- 升级保留本地数据与家庭会话；不要求清空 App 或重建家庭  
- NAS CD 须用户确认；TLS 身份与 bootstrap secret 不因本版替换而轮换  

## Related history

- Multi-device open sleep heal: `.scratch/family-sync-hang-and-account-fidelity/issues/32-family-global-open-sleep.md` (complete)
