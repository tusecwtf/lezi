# Ticket 24 shared open-sleep normalization

Date: 2026-07-30 (Asia/Shanghai)

## Red-to-green receipts

1. `:core:model:test --tests '*OpenSleepNormalizationTest'` first failed compilation because the
   public pure decision types and function did not exist. It then passed after the minimal pure
   rule was added.
2. `:domain:testDebugUnitTest --tests
   '*CareLogTest.localOpenSleepRepairUsesStableUuidAndInjectedClock'` first failed compilation
   because `CareLog` did not accept the injected clock. It passed after the local adapter delegated
   to the shared rule.
3. `:sync:testDebugUnitTest --tests
   '*RealSyncPortTest.pullOpenSleepTieUsesStableUuidAndInjectedRepairClock'` failed behaviorally
   under the prior local-row-id tie-break. It passed after the pull adapter delegated to the same
   rule.

The pure matrix covers empty/single/multiple inputs, permutations, cross-day starts, equal starts,
future anomalous starts, deterministic family UUID ordering and saturating `Long.MAX_VALUE`
handling. Adapter tests retain local anomaly/dirty behavior and remote author/replica-repair
behavior.

## Full gate

The combined command completed successfully:

```text
./gradlew :core:model:test :domain:testDebugUnitTest :sync:testDebugUnitTest \
  :app:assembleDebug :app:lintDebug
```

No Room schema, wire schema, version, Record ACL or timer behavior changed. This ticket has no
device-only interaction; its acceptance surface is the deterministic pure rule plus both database
adapters, so no device smoke was claimed.
