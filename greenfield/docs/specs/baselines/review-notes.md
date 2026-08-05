# Spec 02 dual-install review notes

**Date:** 2026-08-05  
**Device:** emulator-5554 · lezi_api35 · 1080×2400  
**Packages:** `com.lezi.babylog.gf` **1.0.0** (`versionCode` 100) · `com.lezi.babylog.debug` (0.3.x)  
**Automation:** `greenfield/scripts/capture-uiux-baselines.sh` · motion prototype `prototype-capture-motion-clips.sh`  
**Reviewer:** **agent**（双装静帧 + 单场景 S-freeze；非实现者盲测非 1.0.0 门闩）

## Freeze scene S-freeze（1.0.0 门闩）

**Path:** 空数据记录首页 warm → 打开配方奶 Composer 静置。  
**Spec:** [02-apk-visual-parity.md](../02-apk-visual-parity.md) §2.4 / §10.

| 检查 | Result | Evidence |
|------|--------|----------|
| 五 Tab + 坞 4+更多 | **pass** | `log-home-warm-gf.png` / `log-home-warm-legacy.png` |
| 空态可读 | **pass** | 同上 |
| 配方奶 Composer 身份 | **pass** | `composer-formula-gf.png` / `composer-formula-legacy.png` |
| 奶量 + 时间主控 + 主 CTA/取消 | **pass** | Composer 双端；GF 圆盘 E1 + 全宽保存 E10 |
| confirm-before-write | **pass** | 取消/关闭路径；写仅确认后 |
| 顶栏/图标/日汇总像素同构 | **waived** | residual，不挡 1.0.0 |

**S-freeze verdict: PASS → 固化 GF 1.0.0 壳层门闩。**

## §2.3 完整盲测（非 1.0.0 门闩）

| Scene | GF file | Legacy file | Notes |
|-------|---------|-------------|-------|
| 记录首页 | `log-home-warm-gf.png` | `log-home-warm-legacy.png` | IA 同构；chrome **可稳定分辨**（蓝顶栏+五格 vs 奶油+三日轴） |
| Composer 配方奶 | `composer-formula-gf.png` | `composer-formula-legacy.png` | 双方 confirm；GF 圆盘；legacy 快捷 chips 更密 |
| 计时 | `timer-running-active-gf.png` | — | 双大圆 L/R |
| 布局 | `layout-editor-gf.png` | — | ≡ 拖动手柄 |
| 更多 | `more-sheet-gf.png` | — | 四列底 sheet |
| 滑动 | `timeline-swipe-*.png` · motion clips | — | 满行程左滑编辑 / 右滑删除 |

**§2.3 agent 结论：** **FAIL**（可稳定分辨换壳）→ 记入 residual，**不**否定 S-freeze / 1.0.0 冻结。

## Residual（1.0.0 接受）

1. **顶栏语言不同：** legacy 蓝顶栏 + 日龄 + 图标汇总条；GF 奶油/主题色昵称 + 三日轴卡。  
2. **类型图标：** legacy Material/线标；GF 汉字 glyph 圆（E3）。  
3. **Composer 密度：** legacy 快捷 ml chips / 冲调量/耗时；GF 圆盘 + 步进 + **快捷奶量 chips（已对齐默认 120ml + chips）**；冲调量/耗时仍 residual。  
4. **日汇总 chips：** legacy 空数据五格；GF 无数据类不进空筛选（E4 / PRD）。  
5. **全场景盲测 + 非实现者签字：** post-1.0.0 polish。  
6. **多场景矩阵：** `.scratch/spec-02-dual-scene-review/` · harness `scripts/dual-scene-capture.sh`。

## Gate table

| Gate | Status |
|------|--------|
| **S-freeze（1.0.0 门闩）** | **PASS** |
| 自动化静帧入库 | **pass** |
| E1–E10 代码 + 证据（E3/E4 residual） | **pass / waived** |
| §2.3 未看包名分不出 | **fail · deferred** |
| 非实现者盲测 | **not required for 1.0.0** |
| 像素 CI | out of scope |

## Reproduce

```bash
./greenfield/android/gradlew -p greenfield/android :app:assembleDebug
./greenfield/scripts/capture-uiux-baselines.sh emulator-5554
# optional motion:
./greenfield/scripts/prototype-capture-motion-clips.sh emulator-5554 both
```
