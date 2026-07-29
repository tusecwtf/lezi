# 11 — 配置变更保持布局编辑会话

**What to build:** 在旋转、深浅色或其他 Activity 配置重建后继续显示同一个布局编辑会话，恢复目录位置与最后成功快照，同时取消无法安全恢复的半途拖动；真正冷启动仍从普通记录页开始。

**Blocked by:** 02 — 原子持久化 DeviceLayoutSnapshot

**Status:** ready-for-agent

## Acceptance criteria

- [ ] 配置重建后仍处于布局编辑页，保留进入前的宝宝/日期上下文、主目录滚动位置和最后成功 `DeviceLayoutSnapshot`。
- [ ] 配置变更发生在拖动中时取消该拖动，清理浮层、当前目标、触觉/动画状态且不产生 drop intent；已成功提交的先前意图不回退。
- [ ] 配置变更发生在快照写入中时不重复提交或丢失结果；重建后的界面继续显示真实的等待、成功或可重试失败状态。
- [ ] warm/journal 与浅色/深色切换后使用新主题重绘，但四槽、隐藏集合、类内序、类别序和目录位置保持一致。
- [ ] force-stop、进程死亡恢复或冷启动只读取最后成功布局并进入普通记录页，不自动重新打开布局编辑器。
- [ ] 编辑会话标记、滚动位置和临时拖动态均为设备 UI 状态，不进入家庭同步、数据库事实或 `DeviceLayoutSnapshot` wire。
- [ ] 系统返回与“完成”仍遵守 Ticket 02 的最后快照提交语义；配置重建不能绕过等待或伪装成功。
- [ ] 重建测试覆盖空闲、滚动后、拖动中、写入中、失败待重试、主题切换与冷启动负例。

## Validation

- 运行编辑宿主状态、ViewModel、快照 writer 与 Activity/Compose recreation 定向测试。
- 运行 `:core:datastore:testDebugUnitTest`、`:feature:log:testDebugUnitTest`、相关 connected/Compose 重建测试、`:feature:log:lintDebug` 与 `:app:assembleDebug`。
- 设备 smoke“滚动并编辑→旋转/切主题→继续编辑”“拖动中重建→无误投放”“force-stop→普通记录页”。
- 运行 `git diff --check`。

## Documentation Gate

在 UI PRD 与技术说明中区分配置重建和真正进程重启：前者恢复编辑会话但取消拖动，后者只恢复耐久布局且不自动进入编辑态。
