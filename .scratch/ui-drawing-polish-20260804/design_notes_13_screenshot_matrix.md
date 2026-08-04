# Design notes — 13 screenshot matrix + program close-out

## Public seams (self-confirmed; AFK)

1. **Human smoke matrix** under this tracker’s `smoke/` with stable names
   `{style}-{mode}-{surface}.png` for
   `warm|journal` × `light|dark` ×
   `record|summary|growth|family|menu` (20 full-screen screencaps).
2. **`smoke/README.md`** — capture metadata only: AVD, **API 35**, and build
   identity (`versionName` **and** capture-time git sha). No CI inventory gate.
3. **Program close-out markers** — ticket 13 + parent `ISSUES.md` / `spec.md`
   status complete; `.scratch/README.md` active row marks this program complete.

## Explicit non-seams (review fix)

- **No product-less StructureTest** over `.scratch/**` paths, PNG byte sizes, or
  README string presence. That would violate `docs/prd/tech.md` §2.1 / AGENTS.md
  (tracker paths are not long-term product truth; complete trackers may be
  deleted). Ticket 08 already avoided such inventory gates; ticket 13 does not
  amend the ban.
- Screenshots remain **human acceptance evidence**, not golden CI pixels.

## Corpus choice

Empty / local-only matrix is intentional for close-out (Phase F allows empty
record). Data-bearing polish from blockers (lazy growth, joined members, photo
thumbs, populated charts) is not re-proven by this matrix; those tickets keep
their own behavior tests.

## Out of scope

- Macrobenchmark / screenshot CI golden diffs
- Permanent docs/design relocation of the matrix (optional later; not required
  for close-out)
- Re-capturing after every leaf UI tweak
- Product empty-glyph redesign (static ring vs spinner already split in 06/07
  contracts: `StateKind.Empty` stroke + `onSurfaceVariant` vs loading
  `CircularProgressIndicator` + primary)

## Capture notes

- AVD `lezi_api35`, package `com.lezi.babylog.debug` `0.3.6-debug`.
- Theme switches via in-app 显示设置 (not only system night mode) so darkMode
  preference is explicit light/dark for the matrix.
