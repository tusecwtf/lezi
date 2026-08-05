# Spec 02 · GF ↔ 0.3.x 对齐战役（post-inventory）

**Status:** ready-for-agent  
**Parent inventory:** [../spec-02-dual-scene-review/uiux-diff-spec.md](../spec-02-dual-scene-review/uiux-diff-spec.md)  
**Acceptance log:** [../spec-02-acceptance-verify/VERIFICATION.md](../spec-02-acceptance-verify/VERIFICATION.md)  
**1.0.0 freeze (closed):** [../spec-02-apk-1.0-freeze/spec.md](../spec-02-apk-1.0-freeze/spec.md)  
**Engineering:** `greenfield/docs/specs/02-apk-visual-parity.md` · `03-uiux-diff-inventory.md`

---

## Problem Statement

1.0.0 壳层已由 S-freeze 冻住；配方奶默认/快捷/确认文案与 debug 文案已对齐。护理者日常仍能从顶栏、日汇总、类型图标、Composer 字段序、写后计划、坞/计时入口等感知「换了 App」。需要按 residual 竖切把 GF 再逼近 0.3.x，**不**重开 S-freeze 因 chrome 打回 fail。

## Solution

执行 10 张 tracer-bullet 票：文案收尾 → Composer/计划/坞 → 记录首页 chrome（E4/E7）→ 图标与更多（E3）→ 双装重验 → §2.3 盲测建议。每票可独立验收；写路径仍 confirm-before-write。

## Already done (do not re-ticket)

120ml default · quick chips · 确认记录 · 计时 label · sleep wall-clock · summary no D0 dump · PENDING→待履行 · dual harness mid-frames · S-freeze PASS

## Out of Scope

Spec 01 migration · NAS CD · pixel CI · reopening S-freeze for waived chrome alone · P1 growth/template unless pulled in later

## Comments

- 2026-08-05: Breakdown approved (user “ok”) at 10-ticket full chrome+interaction scope; 06 after 05; 08 after 07.
