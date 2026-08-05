# Dual-scene scoring rubric

Used by every capture ticket (03–20) and rollup (21). One matrix: [matrix.md](./matrix.md).

## Bands

### 1. 结构 (structure)

**Pass when both APKs expose the same control identity for the scene:**

- Log home: five bottom tabs (记录/汇总/成长/账户/菜单), dock 4 slots + 更多, empty or seeded content readable.  
- Composer: type identity (配方奶 / 睡眠 / 尿·便 / 喂奶), primary field(s), time control path, primary confirm CTA, dismiss/cancel.  
- Timer: dual L/R affordance, complete → confirm path, dismiss without write.  
- More: multi-item grid (GF: four columns).  
- Layout: enter, reorder affordance, exit/complete.  
- Swipe: half-reveal vs full-commit targets exist on a real row.

**Fail:** missing tab/dock/Composer type/CTA, or wrong surface for the scene path.

**Residual:** same identity, different chrome density or ordering that does not break the path (e.g. secondary timer row vs dock-only entry).

### 2. 交互 (interaction)

**Pass when:**

- Gestures produce the intended outcome (open sheet, reveal edit, commit edit, delete confirm, tab switch, day pan).  
- **confirm-before-write:** open entry does not persist; only confirm/save writes.  
- Cancel / back / 放弃 does not write.  
- Automation and human review both tap the real confirm control (文案 may be 确认记录 / 保存 / 确认睡下…).

**Fail:** write without confirm, cancel writes, swipe commit missing, or broken seed path.

**Residual:** extra confirm step, different cancel label, or seed requires extra taps but semantics hold.

### 3. 动效语言 (motion language)

**Pass when** the scene shows the appropriate class if motion is expected:

| Class | ≈ ms | Scenes |
|-------|------|--------|
| Fast | 150 | swipe reveal start |
| Base | 200 | Tab content crossfade, Composer sheet |
| Emphasized | 300 | layout editor enter/exit |

Judge **presence of class**, not frame-accurate duration on emulator.

**Fail:** jarring hard-cut where both products animate the transition and GF has none (path still works → usually residual unless product marks fail).

**N/A:** static-only capture ticket.

### 4. 明确不比 (explicit non-compare)

Never score fail for:

- Top-bar blue vs cream/nickname accent  
- Material icons vs 汉字 glyph  
- Empty five-chip day summary vs data-driven chips  
- Pixel hashes / icon asset identity  
- Emulator timing jitter  

Document under residual if useful for later polish.

## Ticket 21 rollup rules

1. Copy scores from 03–20 into [matrix.md](./matrix.md); do not add columns.  
2. List open residuals in [residuals.md](./residuals.md).  
3. §2.3 blind “cannot tell package” remains **deferred** unless residuals shrink and a human re-runs blind review.  
4. Do **not** reopen S-freeze as fail for chrome-only diffs.
