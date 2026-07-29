# 15 — 串行化成长记录写入

**What to build:** 对身高、体重等成长测量的保存与删除增加明确的单次执行状态，避免连点产生重复记录，并让失败结果留在可恢复界面。

**Blocked by:** None — can start immediately

**Status:** complete

**Size:** S–M

## Acceptance criteria

- [x] 首次保存开始后，确认控件进入忙碌并拒绝后续点击，底层最多收到一次等价写入。
- [x] 保存成功只关闭一次编辑界面并只插入一条测量记录。
- [x] 校验失败不启动写入；字段错误可被读屏识别且用户输入保留。
- [x] 保存异常或明确失败时界面不假装成功关闭，恢复为可重试状态并显示原因。
- [x] 删除进行中同样阻止重复请求；返回 false、冲突或异常时保留条目并显示失败。
- [x] 测试覆盖快速连点、慢存储、失败后重试、配置变化和保存/删除互斥。

## Validation

运行成长记录 ViewModel/仓储与 Compose 测试，以及应用编译和静态检查。

## Documentation Gate

若用户可见反馈有变化，同步更新成长记录交互文案。

## Implementation evidence

- `GrowthMeasurementWriteCoordinator` 通过原子状态占用让保存与删除共用一个互斥操作槽；忙碌期间拒绝重复保存、重复删除、关闭和草稿修改。
- 草稿、字段错误、操作错误、删除确认和忙碌操作均由 `GrowthViewModel` 持有的 `StateFlow` 驱动，配置变化后的界面重新订阅同一状态。
- 保存或删除失败保留草稿；明确拒绝、返回 `false` 与异常都恢复到可重试状态。保存成功或删除成功统一清空编辑状态。
- 数值校验错误显示在测量值字段的 `supportingText`；异步失败反馈使用 polite live region。

## Interaction copy

- 保存忙碌：`保存中…`
- 删除忙碌：`删除中…`
- 保存异常：`保存失败，请重试`
- 删除返回 `false`：`删除失败，测量记录可能已不存在，请重试`
- 删除异常：`删除失败，请重试`

## Validation evidence

- `./gradlew :feature:growth:compileDebugKotlin :feature:growth:testDebugUnitTest` — pass
- `./gradlew :feature:growth:testDebugUnitTest :feature:growth:lintDebug` — pass（7 个公共写入 seam 测试）
- `./gradlew :app:assembleDebug` — pass
- `git diff --check` — pass
