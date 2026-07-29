# 03 — 全屏照片预览单源

**Parent:** [../spec.md](../spec.md)

**What to build:** 记录 Composer 与冲突审计/日历等路径打开多图预览时，使用同一全屏预览体验：黑底、可横滑分页、失败占位、关闭与无障碍描述。解码与 OOM/失败处理只维护一处（ADR-0003 公共附件 chrome 单源）。

**Blocked by:** None — can start immediately

**Status:** complete

**Size:** M  
**Theme:** C（R5）  
**Seams:** 公共记录照片预览 chrome

## Acceptance criteria

- [x] 至少两条原并行预览路径改为同一预览组件/入口
- [x] 多图横滑、关闭、无法解码时的用户可见失败态行为一致
- [x] 无第二套复制粘贴的 `Dialog + HorizontalPager + BitmapFactory.decodeFile` 生产实现
- [x] 相关 feature 模块测试或截图级静态调用检查通过；预览失败不崩溃进程

## Implementation notes

- live audit 确认 `QuickRecordSheet` 记录 Composer 与 `CalendarScreen` 冲突审计已经调用同一
  `designsystem.LeziPhotoPreviewDialog`；共享组件独占全屏黑底、`HorizontalPager`、关闭按钮、
  页码/图片描述与用户可见的“无法预览图片”失败态，两调用文件不再内嵌全屏 pager。
- 新增 public `decodePhotoPreviewBitmap` 作为本地预览解码边界。空解码结果、普通解码异常与
  `OutOfMemoryError` 均返回 `null`，共享组件统一显示既有失败占位，不让损坏或过大图片使
  预览流程崩溃。
- JVM source-contract 回归仅锁两条生产调用方使用共享组件，以及共享组件保有横滑、关闭、
  失败文案、无障碍描述与安全解码调用；没有采用脆弱的整文件快照。

## Validation evidence

- TDD RED：批准的 public seam 测试因 `decodePhotoPreviewBitmap` unresolved 而编译失败；最小
  GREEN 后注入 OOM、`IllegalArgumentException` 与 `null` 解码结果均安全返回 `null`。
- `./gradlew :designsystem:testDebugUnitTest :feature:log:testDebugUnitTest
  :feature:settings:testDebugUnitTest :designsystem:lintDebug :feature:log:lintDebug
  :feature:settings:lintDebug :app:assembleDebug --no-daemon`：通过。
- `git diff --check`：通过；未使用模拟器，未修改版本、上传/原子包、Program 22 或 Layout 05。
- 完整回执见 `../evidence/03/validation.md`。

## Out of scope

- 改上传/原子包协议
- 相机启动样板统一（审查 P2，非本票）
