# 06 — 长按昵称跳下一孩相同日龄

**What to build:** 多孩时，顶栏昵称区 **长按** → 切到列表中下一个非当前宝宝，并将选中日设为「相同日龄」日历日：`target = siblingBirth + (selectedDate − currentBirth)`（整天数）。单孩 no-op；目标日可为空态；若晚于 today 则夹到 today。短按仍仅循环宝宝不改日。

**Blocked by:** None — can start immediately.

**Status:** complete

- [x] 纯函数 `siblingSameDayAgeDate` + 单测
- [x] `MainActivity` ViewModel：`jumpSiblingSameDayAge`
- [x] `AppHeaderBar`：`combinedClickable` 长按回调 + 可达性描述
- [x] 与 `docs/prd/ui.md` §5.2 手势表一致（07 可同步 PRD 若需补一句实现细节）
