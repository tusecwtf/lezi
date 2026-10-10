# US-090 opt-in ExportRoute file, retry and return slices

Prepared source only, 2026-10-10 UTC. No Gradle, APK build, emulator/device,
ADB, renderer execution, NAS access or publication was performed for this
checkpoint. Source review and whitespace checks are not a story PASS or a
runtime result.

## Assembly and boundary

`-PleziUiHostAcceptance=routes` uses the shared optional route test-DI group and
`IsolatedUiHostTestRunner`. The normal default production-Application runner
and the separate widget group are unchanged by these export files. The routes
runner requires a fresh disposable debug installation and exactly one allowed
class/method selector, preserving pre-existing data by refusing to start.

Each slice renders actual MainActivity, navigates its menu to ExportRoute, and
uses the actual ExportViewModel, TxtExportPort, CareLog, guarded Room graph,
ExportFileGenerator, normal production rendering path and FileProvider.
HiltTestApplication supplies the isolated test host. This is not LeziApp
startup or process-boundary acceptance.

The shared route CareLog binding adds only
`recordWakeProjectionDao = exportReads.wrap(projections)` for export control.
`ExportReadControl` selects a complete `(babyId, startInclusive, endExclusive)`
tuple at the existing `loadRecordProjection` port. It can suspend one matching
read through the common `HeldRouteRead`, or fail that read once with a
synthetic IOException. Nonmatching reads and every write remain delegated.
No ExportPort, document, ViewModel, renderer, file generator or UI state is
replaced. The held read is released in `finally`; when entered, its caller
completion is awaited before the fixture is left. For export this acknowledgment
belongs to the current IO/transaction coroutine, not the outer ViewModel's
subsequent UI publication. The tests use actual chooser capture and rendered
failure/preview/idle state as terminal oracles, not that acknowledgment alone.

The real process SyncPort is checked to be unjoined and endpoint-free. All
babies are required to have the fixture prefix before adding data. The
fixture contains four synthetic records around the exact inclusive-start /
exclusive-end date boundaries, with distinct blue/red PNG attachments on the
two included records. Images are tiny, generated locally and confined to the
app-private record-media directory. Original Room rows and media metadata
must be unchanged after the interaction.

Espresso Intents stubs ACTION_CHOOSER with RESULT_CANCELED and captures the
actual nested ACTION_SEND. It never chooses or contacts a third-party app.
No custom renderer lifecycle service, process observer, PID inspection,
cross-process observation bridge or new main/debug hook is added.
Android's PdfRenderer is used only as a reader of the already generated public
file URI; the tests never bind to, inspect or instrument the production renderer
service or its process.

## Prepared selectors and oracles

Class: `com.lezi.babylog.validation.export.ExportRouteFilesDeviceTest`

### `historicalPdfRequestsFreezeOptionsAndKeepDistinctFiles`

- Navigate the actual date picker to 2025-01-30 through 2025-02-02 and turn
  photos off. Hold the matching real projection read before release.
- Check the visible range, disabled date/photo controls and disabled export
  buttons. Attempt edits and duplicate clicks; the selected options remain
  unchanged and the exact tuple has one read, with no premature share.
- Release and verify a real, readable one-page PDF and the selected preview.
  No-photo output is meaningful because the selected records do have images.
- Change the next draft to 2025-02-02 only, turn photos on, and repeat the
  in-flight control checks. The new real PDF must have two pages, with the
  synthetic red photo rendered on its second page. Reusing the previous wider
  document would include both blue/red photos and produce three pages.
- Require distinct chooser stream URIs and filenames. Re-read the first URI
  and verify its PDF page count and SHA-256 remain unchanged after the later
  draft and successful output. Exactly the two completed files are added to
  the export directory, without a partial-file residue.

The body/range oracle uses the real selected DAO tuple and rendered preview;
the PDF file oracle checks readable structure, page count, photo pixels and
identity. This does not claim PDF text extraction.

### `readFailurePreservesDraftAndExplicitRetryGeneratesFile`

- Hold the matching historical photo-off PDF read, then release it into the
  single injected IOException, before any renderer/file generation.
- Require the real shared local-save failure dialog, no share/file, the same
  source records/media, and exactly one selected read.
- Click the dialog's “再试一次”, verify the unchanged editable draft, then
  click the export CTA. The current production dialog action only dismisses
  the failure; the test does not treat dismissal as an automatic IO retry.
- Require exactly one additional selected read, one chooser, one readable
  no-photo PDF, preserved preview/options and no residual failure presentation.

This covers a real route generation failure/retry driven by a domain-read
fault. It does not claim disk-full, native renderer failure, timeout or
process-death recovery.

### `stubbedChooserReturnKeepsRouteUsableAndTxtReadable`

- Generate real TXT through the UI and inspect its UTF-8 bytes via the
  captured FileProvider URI. Verify baby/range and both included notes while
  excluding records immediately outside the range. The URI bytes must equal
  the actual cache file bytes.
- Explicitly move the same Activity to CREATED and back to RESUMED. Require
  the real “文件已生成，可在本页再次分享” return feedback, enabled controls,
  retained draft/preview and continued readability of the generated file.
- Export again through the same route, requiring a new URI with equal TXT
  content, the first URI still readable, no extra files and unchanged source
  rows/media.

All captured shares check ACTION_SEND, MIME, subject, chooser title,
FLAG_GRANT_READ_URI_PERMISSION, matching EXTRA_STREAM/ClipData, FileProvider
authority and export-directory containment. The return test exercises actual
host lifecycle callbacks with a stubbed chooser; it is deliberately not
evidence of a real system chooser or external receiving app round trip.

## Verification still required

The parent controls optional route-group compilation. Any eventual manual
device invocation must select `leziUiHostAcceptance=routes` and exactly one
of the class/method pairs above on a new disposable installation. Whole-class
or mixed-group runs are refused by the runner. No command in this document
authorizes execution.

These sources complement, rather than repeat,
`ProductionExportDraftRecreationDeviceTest`, which already prepares the
production-Application empty-range / historical photo-off draft / Room queue /
Activity-recreation retry slice. Successful-file cases here do not recreate
the Activity, and the explicit return transition is not process death.

Still unproven: compilation, API26/API35 device behavior, actual system chooser
return, third-party reading of the URI, native failure/deadline/lifecycle and
cross-process guarantees. The blocked cross-process and renderer-observation
groups remain outside this change.
