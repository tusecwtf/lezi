# API35 export deadline diagnostics

The existing `actualRendererProducesReadableTextAndPhotoPages` test retains its
10-second wrapper and its exact two-page/readable-photo assertions. Its observed
API35 timeout remains a failure; a separate test does not supersede it.

`productionThirtySecondBoundaryProducesReadableTextAndPhotoPages` calls the same
`runBoundedExportGeneration` used by `ExportScreen`, around the same real PDF and
photo generator. It retains the two-page and positive photo-page dimension
assertions. The production 30-second deadline is unchanged. This is a separate
success-contract check, not a relaxed retry of the 10-second test.

Debug builds log a fixed `ExportMilestone` enum, monotonic elapsed milliseconds,
and PID under `LeziExportStage`. Milestones cover binding, HELLO/READY, request
handoff/decoding, rendering, completion, cancellation, and service lifetime.
No contents, filenames, paths, IPC tokens, exceptions, or user identifiers are
logged. The observer retains no state and adds no pacing, retries, or deadlines.
Release uses an inline no-op with no Android logging call. Inspect the final
release/R8 output to verify the debug tag and observation implementation are
absent; source separation alone is not artifact proof.

Run the original and separate production-boundary cases individually on the
same exact API26/API35 APK. Preserve instrumentation, logcat, guest health, and
artifact hashes. Correlate monotonic milestones within each process without
claiming that software-emulator timing represents phone performance. Missing
milestones identify an observation boundary, not necessarily the root cause.

At authoring, these additions are source-only. Compilation, device execution,
and final release-artifact absence checks remain pending.
