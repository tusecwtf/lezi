# Code volume 对照（实测）

Measured from trees on disk (excludes `build/`, `target/`, `.gradle/`).  
**Not** a quality score — greenfield is a rewrite with thinner seams; original includes long-lived tests and legacy paths.

## Summary

| Scope | Language | Files | Lines | Share of original |
|-------|----------|------:|------:|------------------:|
| Original Android product (`app`+`domain`+`sync`+`feature/*`+`core/*`+`designsystem`) | Kotlin etc. | 619 | **~154k** | 100% |
| Greenfield Android (`greenfield/android`) | Kotlin etc. | 63 | **~7.2k** | **~4.4%** of orig Kotlin |
| Original server `tools/lezi-sync` | Rust | 42 | **~26k** | 100% |
| Greenfield server `greenfield/sync-server` | Rust | 4–5 | **~1.5k** | **~5.5%** |
| Combined (kt+rs product-ish) | | | GF **~8.2k** / Orig **~179k** | **~4.6%** |

### Kotlin-only (*.kt)

| | Files | Lines |
|--|------:|------:|
| Original Android | 554 | 152 806 |
| Greenfield Android | 45 | 6 704 |
| **GF / Orig** | 8% files | **4.4%** lines |

### Rust-only (*.rs)

| | Files | Lines |
|--|------:|------:|
| Original lezi-sync | 42 | 26 204 |
| Greenfield lezi-gf-sync | 4 | 1 447 |
| **GF / Orig** | ~10% files | **~5.5%** lines |

### Main vs test (Android product trees)

| | main lines | test lines |
|--|----------:|-----------:|
| Original Android | ~82k | ~71k |
| Greenfield Android | ~4.9k | ~1.9k |

Original test surface is huge; greenfield keeps a thin L1/L2 + LiveWire set by design (blueprint: no old kitchen-sink 1:1 port).

## APK package size (related, not LOC)

See [apk-size-compare.md](./apk-size-compare.md):

| Package | Size |
|---------|-----:|
| Original release `com.lezi.babylog` 0.3.7 | ~5.5 MiB |
| Original debug | ~27.8 MiB |
| Greenfield debug `com.lezi.babylog.gf` 1.0.0 | ~22.3 MiB |

Debug APKs are both large (Compose + deps); release minify/R8 on original shrinks hard. Greenfield release minify is off for the gf line, so debug-vs-debug is the fairer APK compare for now.

## How to read this

- **Smaller LOC is intentional** for greenfield: capability verticals, one path per use case, no dual CareLog/SyncPort kitchen sink, no migrated mega-suites.
- **Parity goal is behavior (PRD/G1–G10)**, not “same number of lines as 0.3.x”.
- Gaps in pixel density / secondary UX may still exist even when LOC is lower — use [functional-parity-matrix.md](./functional-parity-matrix.md) for UV status.
