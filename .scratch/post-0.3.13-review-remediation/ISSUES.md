# 0.3.13 审查修复与预切割闭环 — issues

Status: implemented — device, isolated rollback, and production cutover residual

Spec: [`spec.md`](./spec.md)

Audit baseline: `38cfbe7dbcf1f410237dce77b50ec1bd4854d8a6`

## Graph

```text
01 causal upgrade safety + Wake/conflict/duplicate product closure
  └─► 02 legacy contraction + test simplification + pre-cutover acceptance
```

| # | File | Status | Blocked by |
|---|------|--------|------------|
| 01 | [`issues/01-close-causal-safety-and-product-surfaces.md`](./issues/01-close-causal-safety-and-product-surfaces.md) | implemented; acceptance residual | — |
| 02 | [`issues/02-contract-legacy-and-rebuild-acceptance.md`](./issues/02-contract-legacy-and-rebuild-acceptance.md) | implemented; acceptance residual | 01 |

## Frontier

`01` can start immediately. `02` starts only after 01 has fixed the public behaviour and replaced the
missing UI/E2E coverage, so contraction cannot erase the only evidence for a still-unfinished surface.

## Relationship to the causal tracker

This tracker is the sole owner of the 0.3.13 fixed-HEAD review findings. The existing
`lossless-family-causal-sync/09` remains the sole owner of production NAS cutover and joined-device
production smoke. Publishing these tickets is planning, not implementation or release acceptance.
