# 记录 Composer 并轨与审查修复：模拟器验收

- 日期：2026-07-24
- 设备：`lezi_api35` / `emulator-5554` / Android 15 / 1080×2400
- 最终 APK：`app/build/outputs/apk/debug/app-debug.apk`
- APK 构建时间：`2026-07-24 02:53:07 +0800`
- SHA-256：`937ba0047af5423f7c3f2aa69becb81fdb197312376ec0a75c925592c9bd9dae`
- 安装：`adb install -r` 成功；冷启动 `MainActivity` 成功（1025 ms）
- 总结：本任务要求的 10 项设备门禁全部 **PASS**；最终会话应用 FATAL 计数 **0**。

## 逐项结果

| # | 验收项 | 结果 | 设备证据 |
|---|---|---|---|
| 1 | 快捷新增初始半屏，向上滑动可展开近全屏 | **PASS** | `dumps/10-final-add-half.xml` 的背景关闭区为 `[0,0][1080,1200]`；`dumps/11-final-add-expanded.xml` 为 `[0,0][1080,86]`，即 sheet 从半屏展开至约 96% 可用高度。截图：`shots/10-final-add-half.png`、`shots/11-final-add-expanded.png`。 |
| 2 | 点击已有记录进入同风格 Composer，编辑态可删除 | **PASS** | `shots/23-final-edit-half-delete.png` 与新增页共享相同图标、档位选择、时间卡及半屏交互；布局树包含“编辑记录”“删除”。删除确认弹窗已打开但未执行删除，以保留 AVD 测试数据：`shots/08-delete-confirm.png`。 |
| 3 | 圆盘时钟显示日期，点击弹中文日历并可选日期 | **PASS** | `dumps/19-head-final-clock-date.xml` 含“日期 / 2026年7月24日 周五 / 日历”；`shots/22-final-calendar-padded.png` 显示中文标题、月份、周标题及日期网格，单行无裁切，24dp 内边距正常。日期切换回填曾实测成功且未保存测试记录：`shots/05-clock-date-selected.png`。 |
| 4 | 深色顶栏低眩光且与内容统一 | **PASS** | `shots/27-final-log-dark-header.png`。像素抽样：header `(38,39,39)`、body `(38,39,39)`、应用底栏 `(38,38,38)`，旧版亮粉顶栏已消失。 |
| 5 | 列表末项不被快捷坞遮挡 | **PASS** | 滚动到底后，末项 bounds 为 `[42,1668][1038,1806]`，快捷坞首个热区从 y=`1872` 开始，留有 66px 安全间隔；截图/布局：`shots/18-list-bottom-clear.png`、`dumps/18-list-bottom-clear.xml`。 |
| 6 | 便便图标网格无溢出 | **PASS** | `shots/17-stool-expanded-grid.png`。便量、软硬均为 4 档图形；颜色为 4×2，`未选/白/黄/橙/褐/绿/红/黑` 全部可见，最右 bounds 结束于 x=`1027`，未超出 1080px。 |
| 7 | “更多”顶部可达成长快捷 | **PASS** | `shots/15-more-growth-visible.png`、`dumps/15-more-growth-visible.xml`；首屏“常用补充”第三项为“添加体重”，bounds `[559,1559][776,1703]`，无需滚动。 |
| 8 | 账户/菜单无全局宝宝日期顶栏 | **PASS** | `shots/24-final-account-no-header.png` 与 `shots/25-final-menu-no-header.png` 均从各自页面标题开始；对应布局树不含“今天 / 7月24日 / 前一天 / 后一天”。 |
| 9 | Tab 切换无双页叠影 | **PASS** | `shots/28a-tab-before.png`、`28b-tab-immediate.png`、`28c-tab-stable.png`：即时帧仍为完整旧页，稳定帧为完整汇总页，没有旧/新文字交叠或透明 crossfade。 |
| 10 | 本会话 logcat 无应用 FATAL | **PASS** | 清空 logcat 后覆盖冷启动、Composer 新增/编辑、日期/日历、更多、深色、账户/菜单与 Tab 切换；`FATAL EXCEPTION`、`AndroidRuntime FATAL`、`Process: com.lezi.babylog.debug` 匹配均为 0。见 `logcat-summary.txt`。 |

## `docs/reviews` 可视审查项复核

| 审查项 | 结果 | 证据/说明 |
|---|---|---|
| D1 深色顶栏 | **PASS** | `27-final-log-dark-header.png` 与像素抽样。 |
| D2 列表被快捷坞遮挡 | **PASS** | `18-list-bottom-clear.*`，末项与快捷坞无重叠。 |
| D3 便便颜色溢出 | **PASS** | `17-stool-expanded-grid.*`，8 档完整。 |
| D4 排泄缺图标语言 | **PASS** | 尿量与便量/软硬/颜色全部使用图形档位。 |
| D7 成长异常点提示 | **PASS** | `29-final-growth-warning.*` 含“该数值高于同月龄参考范围，请确认单位和录入值”。 |
| D8 0 分钟睡眠 | **PASS** | `18-list-bottom-clear.xml` 显示“时长 不足1分”，无“时长 0分”。 |
| D9 非记录页全局顶栏 | **PASS** | `24-final-account-no-header.*`、`25-final-menu-no-header.*`。 |
| D10 低价值 Sheet 副文案 | **PASS** | 最终尿尿/便便 Composer 无“小中大”“三组分档”粘连副标题。 |
| D11 更多成长入口 | **PASS** | “添加体重”位于首屏常用补充。 |
| D12 Tab 叠影 | **PASS** | `28a/28b/28c` 连续帧。 |

D5 家庭错误中文化、D6 结构化搜索属于源码/单测门禁，不在本次 10 项设备操作范围内；本报告不重复声称未在 AVD 上触发的网络失败路径。

## 数据库迁移设备门禁

- 命令：`./gradlew :core:database:connectedDebugAndroidTest`
- 初跑发现测试包缺少 `AndroidJUnitRunner`，修复依赖后复跑。
- 最终结果：`Starting 1 tests` / `Finished 1 tests` / `BUILD SUCCESSFUL in 30s`。
- 判定：**PASS（1/1）**。

## 限制

- AVD 保留了既有宝宝与记录数据；未清数据重跑 onboarding。
- 删除路径验证到二次确认弹窗，未实际删除既有记录。
- 本报告只写入 `.scratch/remediation-ui-2026-07-24/`，未修改产品源码或 `docs/`。
- 目录中编号 00–09、12–13、19–20 的部分截图记录了实测反馈与修复迭代；最终权威 DatePicker 证据为 `22-final-calendar-padded.*`，最终 APK 信息以本报告顶部为准。
