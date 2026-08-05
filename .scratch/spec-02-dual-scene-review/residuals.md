# Dual-scene residuals (living)

Updated as scenes are reviewed. **Do not** treat chrome items below as 1.0.0 blockers.

## From S-freeze / dual stills (2026-08-05) — accepted residual

| ID | Diff | Align attempt? | Status |
|----|------|----------------|--------|
| R-topbar | Legacy blue top bar + day-age + icon summary vs GF cream nickname + three-day axis | No (explicit non-compare) | residual / waived |
| R-icons | Material/line icons vs 汉字 glyph circles (E3) | No this batch | residual / waived |
| R-daychips | Legacy empty five chips vs GF “今日暂无汇总” (E4) | No this batch | residual / waived |
| R-composer-density | Legacy dense ml chips + 冲调量/耗时 vs GF stepper-only | **Yes** — default 120ml + quick chips + CTA 确认记录 | **partial align landed** |
| R-composer-default-0 | GF open formula at 0 ml vs legacy ~120 | **Yes** — `DEFAULT_MILK_AMOUNT_ML=120` | **aligned** |
| R-confirm-label | GF create CTA “确认” vs legacy “确认记录” | **Yes** — create path “确认记录” | **aligned** |
| R-timer-label | GF secondary “计 计时” glyph+text vs “计时” | **Yes** — label/contentDescription “计时” | **aligned** |
| R-dock-order | GF default 配方奶/尿/睡眠/喂奶 vs legacy 尿尿/睡眠/母乳/配方奶 | No this batch (layout customizable) | residual |
| R-§2.3 | Agent can tell packages apart on stills | Deferred post-1.0.0 | fail deferred |

## Scene tickets (fill when capturing)

| Scene | Residual note | Severity |
|-------|---------------|----------|
| log-home-empty-ia (03) | IA pass; chrome (top bar / five chips / icons) still distinguishes packages | residual (waived non-compare) |
| composer-formula (07) | Default 120 + 快捷 chips + 确认记录 **aligned**; GF keeps TimeDial first + full-width CTA; legacy has 冲调量/耗时/任意 ml + dual bottom buttons | residual (optional fields / chrome) |
| _(08–20 pending)_ | | |

## Alignable vs deferred policy

- **Align in this implement batch:** create-path CTA wording, milk default amount, quick amount chips, timer a11y/label for harness, capture harness hardening.  
- **Do not align here:** top-bar language, E3 icons, E4 empty chips, full §2.3 blind chrome — polish tickets only.
