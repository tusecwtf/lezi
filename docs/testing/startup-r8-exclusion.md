# Startup observation exclusion: R8 removed-class mapping

## Measured producer and original RED

The source producer was `d1165682aeea5dea903e7d2e415720404b1ee5a3`.
`:app:minifyReleaseWithR8 -x :app:validateReleaseSigning` completed successfully
using R8 8.7.18, mapping format 2.2 and minimum API26. No release APK was packaged.
The original checker then returned exit1: it treated every diagnostic class header
in mapping.txt as a retained runtime class. That failed result is preserved rather
than retroactively relabeled.

The only rejected entry was:

`com.lezi.babylog.core.common.validation.StartupBoundaryObservation -> R8$$REMOVED$$CLASS$$565`

Its mapping block contains only source-file metadata. A separate inlining frame
names `observeCollection` in the constructor mapping of `DataStoreSyncPreferences`.
This is not proof that an observer object, field, callback or method remains.

Three independent artifact observations establish the distinction:

- R8 usage.txt lists the original class as a removed whole class, without a colon
  or a member-only removal block.
- A binary identity-table census finds 8,695 defined classes and 9,946 types.
  Neither the original descriptor nor the sentinel descriptor exists; neither
  owns a field or method reference. References without definitions are checked too.
- Android SDK35 dexdump independently lists the same 8,695 class definitions and
  contains neither descriptor. All originally specified diagnostic strings are
  absent from DEX and the merged release manifest.

Measured input digests:

- DEX: `fd0b19f854986c67d91c9fb46edd0f5822b234d4c0946c9fc75fd8cfca0db770`
- mapping.txt: `4c7918ce61d1482ac4d2d0285ed3d1c2e1b4fd885c604517110fe1c959df81af`
- merged release manifest: `ff3b90999e11a0a00d42450c5b73d34d6b33d0aac27a860c4656b351bc0ef0d5`

The correction is confined to the checker and its tests; production sources and
the measured R8 outputs are unchanged. Do not claim instruction-for-instruction
parity: source-map inlining frames can remain after their original class is gone.
The claim is exclusion of the diagnostic classes, observation state, callbacks,
listed markers and their owner references, not removal of arbitrary ordinary
compiler-generated checks.

## Format authority and fail-closed behavior

R8's [`addSourceFileLinesForPrunedClasses`](https://r8.googlesource.com/r8/+/7b905d28fdaff5524db22960166261303d424f70/src/main/java/com/android/tools/r8/utils/positions/MappedPositionToClassNameMapperBuilder.java)
records source-file information for pruned inline holders under a collision-free
`R8$$REMOVED$$CLASS$$` destination. These are source recovery records, not ordinary
class renamings.

The revised checker accepts that exact numeric sentinel only when usage.txt and
the complete DEX identity census corroborate removal. Normal obfuscated class
mappings still fail, even if their original strings disappeared. A sentinel with
member mapping entries, any remaining original/sentinel type (including arrays),
class definition or field/method owner, or missing whole-class usage evidence fails.

The identity reader follows the [AOSP DEX layout](https://source.android.com/docs/core/runtime/dex-format).
It checks standard 035/037/038/039/040 little-endian headers, file/signature/checksum,
table/map bounds and agreement, MUTF-8 lengths and order, descriptor/member indices
and order, class identity and owner references. Unsupported containers/versions,
corrupt hashes, malformed identity tables and unknown map entries raise errors;
they never become an empty successful census. This is an exclusion-specific
identity parser, not an Android bytecode execution verifier.

Self-tests retain positive and negative controls for every old marker, ordinary
obfuscation, removed metadata, missing/member-only usage, definitions and reference
owners, secondary DEX, unsupported/truncated/corrupt files, malformed table indices,
MUTF-8 and legitimate array method owners. Synthetic fixtures are not app builds,
device execution, signing, deployment, or US-082 runtime acceptance.
