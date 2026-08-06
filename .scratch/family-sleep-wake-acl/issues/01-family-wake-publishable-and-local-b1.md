# 01 — 跨成员醒来可发布 + 本机 B1 受限纠错

**What to build:** 家人 A 记睡下后，普通成员 B 在任意入口点「醒来」能成功闭合该开放睡眠并上传到家庭服务器；闭合后仍显示 A 为作者。B 不得改写睡下时刻与 is_nap 等开睡字段，但可带醒来时刻、备注与照片。B 在本机刚完成这次家庭 wake 且本地修订仍 dirty（或尚未被权威更高修订覆盖）时，可再改 end/备注/照片：时间轴编辑入口亮起、删除保持灰；编辑面板只暴露允许字段。资格随 dirty 收敛或权威覆盖作废后，B 不能再改也不能再把 end 推上家庭（服务端对已闭合睡眠的非作者更新仍拒绝）。管理员始终可全量编辑与删除。所有闭合入口（确认醒来、一键 sleepUp、小组件等）同一套规则。并发多次闭合走普通 LWW。

**Blocked by:** None — can start immediately.

**Status:** complete

- [x] 非作者对他人仍 open 的 sleep 执行醒来后，本机 open 消失且该条可进入家庭发布并成功（不再因「改他人记录」被永久拒绝）
- [x] 非作者 open→close 时服务端与本机均强制保留已发布开睡字段（至少睡下主时间与 is_nap 语义）；只合并 end、备注、照片（及约定的 sleep 异常标记规则）
- [x] 非作者对 **已闭合** 睡眠的再次权威推送（改 end/备注/正文）仍被拒绝；无 closer 盖章旁路
- [x] 本机 B1：仅本机用醒来路径刚闭合的那条，在 dirty 未收敛且未被权威更高修订覆盖期间，允许受限 update（end+备注+照片）
- [x] B1 资格作废后，非作者受限 update 与全量 manage 均失败；作者与 Owner 不受 B1 限制
- [x] 时间轴：B1 有效时 canEdit=true 且 canDelete=false；Owner/作者 manage 时编辑与删除同现网
- [x] 受限编辑 UI 不提供改睡下时间/开睡字段；保存路径与 capabilities 同构（不能「面板全开、保存再拦」为唯一体验）
- [x] 非作者任意时刻不能 soft-delete 该睡眠（含 open 与 B1 窗内）
- [x] confirmSleep、sleepUp 与其它一键醒来入口字段策略与 B1 打点一致
- [x] Owner 对他人睡眠保持全量编辑/删除
- [x] 并发两台非作者以不同 end 闭合：家庭权威按普通 LWW 收敛，无额外 sleep 择优特判
- [x] 隔离 lezi-sync 与 Android domain（及必要 UI）回归覆盖上述路径；半成品粗放行被收紧为字段合同
