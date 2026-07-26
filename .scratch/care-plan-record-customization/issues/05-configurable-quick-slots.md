# 05 — 四槽常用记录快捷栏

**What to build:** 把首页固定快捷按钮改为四个用户可配置的常用记录槽位，并始终保留第五个固定“更多”入口。

**Blocked by:** 01 — 具体记录项目身份与重复入口退出.

**Status:** done

- [x] 首页恰好显示四个可配置槽位和一个固定“更多”，四槽靠偏好手、“更多”位于远侧
- [x] 每个槽位绑定稳定具体项目身份，可选择、清空和拖动换位
- [x] 所有已开启内建项目和具体自定义项目都可成为槽位候选
- [x] 睡眠槽位继续随开放睡眠状态显示睡眠或醒来动作
- [x] 项目关闭、删除或旧泛化引用失效时不自动补位，原槽显示“＋ 选择常用记录”
- [x] 点击“＋ 选择常用记录”直达常用记录设置，关闭设置后返回记录首页
- [x] 空槽允许长期保留；升级默认依次为尿尿、睡眠、母乳、配方奶
- [x] DataStore 与快捷栏测试覆盖默认迁移、空槽、改名/图标跟随、偏好手和状态型外观

## Evidence

- 2026-07-27：RED 先证明空槽没有可执行动作；GREEN 后空槽映射为 `OpenSlotSettings`，应用导航直达常用记录弹窗，关闭后回退到记录首页。
- `./gradlew :feature:log:testDebugUnitTest :feature:settings:testDebugUnitTest :app:compileDebugKotlin` — BUILD SUCCESSFUL。
- 2026-07-27 Release 复核发现原槽位设置实际只有“上移 / 下移”按钮，并没有 ticket
  所要求的拖动手势。新增 48dp `ReorderDragHandle`：长按上下拖动后用各槽位实测中心
  命中释放目标，再以 remove/insert 换位；同时保留完整文案按钮作为 TalkBack 与键盘回退。
  纯 policy 测试先因排序 seam 不存在编译 RED；独立复核发现固定行高算法在字体放大时会
  跳项后，不等距实测中心用例再次 RED，改为真实布局中心后 GREEN。
